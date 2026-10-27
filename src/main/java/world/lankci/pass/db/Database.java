package world.lankci.pass.db;

import world.lankci.pass.LankciPassCore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.stream.Stream;

/**
 * SQLite з одним з'єднанням і одним потоком-виконавцем. Усі запити йдуть через цей потік, тому:
 *  - головний потік сервера ніколи не чекає на диск;
 *  - операції виконуються строго послідовно (немає гонок при паралельному отриманні нагород).
 */
public final class Database {
    @FunctionalInterface
    public interface SqlFn<T> {
        T apply(Connection c) throws Exception;
    }

    private final LankciPassCore plugin;
    private Connection connection;
    private ExecutorService executor;
    private Path dbPath;

    public Database(LankciPassCore plugin) {
        this.plugin = plugin;
    }

    /** Викликається з головного потоку під час onEnable (до запуску виконавця). */
    public void init() throws Exception {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Драйвер SQLite (org.sqlite.JDBC) не знайдено. Paper 1.21.1 має його містити.", e);
        }
        Files.createDirectories(plugin.getDataFolder().toPath());
        dbPath = plugin.getDataFolder().toPath().resolve(plugin.settings().dbFile);
        boolean existed = Files.exists(dbPath) && Files.size(dbPath) > 0;

        connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode = WAL");
            st.execute("PRAGMA synchronous = FULL");
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("PRAGMA busy_timeout = 5000");
        }

        int version = Migrations.currentVersion(connection);
        if (existed && version < Migrations.LATEST) {
            backup(connection, "pre-migration-v" + version + "-to-v" + Migrations.LATEST);
        }
        Migrations.apply(connection, version, plugin.getLogger());

        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "LankciPass-DB");
            t.setDaemon(false);
            return t;
        });
        plugin.getLogger().info("SQLite підключено: " + dbPath.getFileName() + " (схема v" + Migrations.LATEST + ")");
    }

    public CompletableFuture<Void> run(SqlFn<Void> fn) {
        return async(fn);
    }

    /** Виконує запит у потоці БД без явної транзакції. */
    public <T> CompletableFuture<T> async(SqlFn<T> fn) {
        CompletableFuture<T> f = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    f.complete(fn.apply(connection));
                } catch (Throwable t) {
                    plugin.getLogger().log(Level.SEVERE, "Помилка бази даних", t);
                    f.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            f.completeExceptionally(e);
        }
        return f;
    }

    /** Виконує запит у потоці БД всередині транзакції (commit/rollback автоматично). */
    public <T> CompletableFuture<T> tx(SqlFn<T> fn) {
        return async(c -> inTx(c, fn));
    }

    public static <T> T inTx(Connection c, SqlFn<T> fn) throws Exception {
        boolean old = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            T r = fn.apply(c);
            c.commit();
            return r;
        } catch (Exception e) {
            try {
                c.rollback();
            } catch (SQLException ignored) {
                // нічого не робимо: початкова помилка важливіша
            }
            throw e;
        } finally {
            c.setAutoCommit(old);
        }
    }

    /**
     * Консистентна резервна копія через VACUUM INTO. Викликати лише з потоку БД (або до init() виконавця).
     * Не можна викликати всередині транзакції.
     */
    public void backup(Connection c, String label) {
        if (!plugin.settings().backupEnabled) {
            return;
        }
        try {
            Path dir = plugin.getDataFolder().toPath().resolve("backups");
            Files.createDirectories(dir);
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
            String safe = label.replaceAll("[^a-zA-Z0-9_.-]", "_");
            Path target = dir.resolve("lankcipass-" + ts + "-" + safe + ".db");
            try (Statement st = c.createStatement()) {
                st.execute("VACUUM INTO '" + target.toAbsolutePath().toString().replace("'", "''") + "'");
            }
            plugin.getLogger().info("Створено резервну копію: backups/" + target.getFileName());
            prune(dir);
        } catch (Exception e) {
            // Резервна копія - додатковий захист. Якщо вона не вдалася, операцію все одно виконуємо, але гучно повідомляємо.
            plugin.getLogger().log(Level.SEVERE, "НЕ ВДАЛОСЯ створити резервну копію (" + label + ")", e);
        }
    }

    private void prune(Path dir) throws IOException {
        int keep = plugin.settings().backupKeep;
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> files = s.filter(p -> p.getFileName().toString().startsWith("lankcipass-")
                            && p.getFileName().toString().endsWith(".db"))
                    .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                    .toList();
            for (int i = keep; i < files.size(); i++) {
                Files.deleteIfExists(files.get(i));
            }
        }
    }

    public Path path() {
        return dbPath;
    }

    public boolean isOpen() {
        try {
            return connection != null && !connection.isClosed() && executor != null && !executor.isShutdown();
        } catch (SQLException e) {
            return false;
        }
    }

    /** Завершує чергу запитів і закриває з'єднання (чекає до 15 с, щоб нічого не загубити при вимкненні). */
    public void close() {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                    plugin.getLogger().warning("Черга БД не завершилась за 15 с - примусове закриття.");
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Помилка закриття БД", e);
            }
        }
    }
}
