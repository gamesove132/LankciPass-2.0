package world.lankci.pass.token;

import world.lankci.pass.LankciPassCore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Валюта «Жетони». Баланс зберігається лише в SQLite; усі зміни атомарні (UPDATE у транзакції),
 * тому від'ємний баланс і подвійне списання неможливі, а переповнення обмежується tokens.max-balance.
 */
public final class TokenService {
    /** Результат зарахування. found=false - гравця немає в базі. */
    public record Credit(boolean found, long applied, long balance, long points) {
    }

    private final LankciPassCore plugin;

    public TokenService(LankciPassCore plugin) {
        this.plugin = plugin;
    }

    /**
     * Зарахування в межах уже відкритої транзакції (викликається з потоку БД).
     * counts=true - жетони додають очки поточного сезону (points-per-token).
     */
    public static Credit creditInTx(LankciPassCore plugin, Connection c, UUID uuid, long amount, String reason,
                                    boolean counts) throws SQLException {
        long now = System.currentTimeMillis();
        long current;
        try (PreparedStatement ps = c.prepareStatement("SELECT tokens FROM players WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new Credit(false, 0, 0, 0);
                }
                current = rs.getLong(1);
            }
        }
        long max = plugin.settings().maxBalance;
        long newBalance;
        if (amount >= 0) {
            newBalance = current > max - amount ? max : current + amount;
            if (newBalance < current) {
                newBalance = current; // баланс уже перевищує ліміт (ліміт зменшили) - не змінюємо
            }
        } else {
            newBalance = Math.max(0, current + amount);
        }
        long applied = newBalance - current;
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE players SET tokens = ?, total_earned = total_earned + ? WHERE uuid = ?")) {
            ps.setLong(1, newBalance);
            ps.setLong(2, Math.max(0, applied));
            ps.setString(3, uuid.toString());
            ps.executeUpdate();
        }
        if (applied != 0) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO token_log(uuid, delta, balance, reason, at) VALUES (?,?,?,?,?)")) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, applied);
                ps.setLong(3, newBalance);
                ps.setString(4, reason);
                ps.setLong(5, now);
                ps.executeUpdate();
            }
        }
        long points = 0;
        long runId = -1;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT run_id FROM season_runs WHERE status = 'ACTIVE' AND end_at > ?")) {
            ps.setLong(1, now);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    runId = rs.getLong(1);
                }
            }
        }
        if (runId >= 0) {
            if (counts && applied > 0 && plugin.settings().pointsPerToken > 0) {
                long add = (long) Math.floor(applied * plugin.settings().pointsPerToken);
                if (add > 0) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO run_players(run_id, uuid, points) VALUES (?,?,?) "
                                    + "ON CONFLICT(run_id, uuid) DO UPDATE SET points = points + excluded.points")) {
                        ps.setLong(1, runId);
                        ps.setString(2, uuid.toString());
                        ps.setLong(3, add);
                        ps.executeUpdate();
                    }
                }
            }
            points = readPoints(c, runId, uuid);
        }
        return new Credit(true, applied, newBalance, points);
    }

    public static long readPoints(Connection c, long runId, UUID uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT points FROM run_players WHERE run_id = ? AND uuid = ?")) {
            ps.setLong(1, runId);
            ps.setString(2, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    /** Зарахувати жетони (асинхронно). Кеш оновлюється після успіху. */
    public CompletableFuture<Credit> credit(UUID uuid, long amount, String reason, boolean counts) {
        return plugin.database().tx(c -> creditInTx(plugin, c, uuid, amount, reason, counts))
                .whenComplete((r, ex) -> {
                    if (r != null && r.found()) {
                        plugin.sync(() -> plugin.players().refresh(uuid));
                    }
                });
    }

    /** Списати жетони. false - недостатньо жетонів (баланс не змінюється). */
    public CompletableFuture<Boolean> take(UUID uuid, long amount, String reason) {
        if (amount <= 0) {
            return CompletableFuture.completedFuture(false);
        }
        return plugin.database().tx(c -> {
            int rows;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE players SET tokens = tokens - ? WHERE uuid = ? AND tokens >= ?")) {
                ps.setLong(1, amount);
                ps.setString(2, uuid.toString());
                ps.setLong(3, amount);
                rows = ps.executeUpdate();
            }
            if (rows != 1) {
                return false;
            }
            long balance;
            try (PreparedStatement ps = c.prepareStatement("SELECT tokens FROM players WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    balance = rs.next() ? rs.getLong(1) : 0;
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO token_log(uuid, delta, balance, reason, at) VALUES (?,?,?,?,?)")) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, -amount);
                ps.setLong(3, balance);
                ps.setString(4, reason);
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return true;
        }).whenComplete((ok, ex) -> {
            if (Boolean.TRUE.equals(ok)) {
                plugin.sync(() -> plugin.players().refresh(uuid));
            }
        });
    }

    /** Встановити точний баланс (адмін). Повертає новий баланс або -1, якщо гравця немає. */
    public CompletableFuture<Long> set(UUID uuid, long amount, String reason) {
        long target = Math.max(0, Math.min(amount, plugin.settings().maxBalance));
        return plugin.database().tx(c -> {
            long current;
            try (PreparedStatement ps = c.prepareStatement("SELECT tokens FROM players WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return -1L;
                    }
                    current = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE players SET tokens = ? WHERE uuid = ?")) {
                ps.setLong(1, target);
                ps.setString(2, uuid.toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO token_log(uuid, delta, balance, reason, at) VALUES (?,?,?,?,?)")) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, target - current);
                ps.setLong(3, target);
                ps.setString(4, reason);
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return target;
        }).whenComplete((v, ex) -> {
            if (v != null && v >= 0) {
                plugin.sync(() -> plugin.players().refresh(uuid));
            }
        });
    }

    /** Поточний баланс з бази (або -1, якщо гравця немає). */
    public CompletableFuture<Long> balance(UUID uuid) {
        return plugin.database().async(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT tokens FROM players WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : -1L;
                }
            }
        });
    }
}
