package world.lankci.pass.season;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import world.lankci.pass.LankciPassCore;
import world.lankci.pass.db.Database;
import world.lankci.pass.util.TimeUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * Сезони LankciPass: визначення (seasons.yml) + запуски (SQLite).
 * Одночасно активний рівно один запуск. Решта - SCHEDULED (майбутні) або ENDED (історія).
 */
public final class SeasonManager {
    private static final long DAY = TimeUtil.DAY;
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_-]{2,32}");

    /** Результат адміністративної операції: ключ повідомлення з messages.yml і параметри. */
    public record OpResult(boolean success, String key, Object[] kv) {
        public static OpResult ok(String key, Object... kv) {
            return new OpResult(true, key, kv);
        }

        public static OpResult fail(String key, Object... kv) {
            return new OpResult(false, key, kv);
        }
    }

    public record TickResult(SeasonRun ended, SeasonRun started) {
    }

    private final LankciPassCore plugin;
    private volatile Map<String, SeasonDef> defs = new LinkedHashMap<>();
    private volatile Set<String> archived = Set.of();
    private volatile List<SeasonRun> runs = List.of();
    private volatile List<String> bootstrapList = List.of();
    private final List<String> warnings = new CopyOnWriteArrayList<>();

    public SeasonManager(LankciPassCore plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ конфігурація

    /** @return false, якщо seasons.yml не вдалося прочитати (тоді залишаються попередні сезони). */
    public boolean loadDefinitions() {
        File f = new File(plugin.getDataFolder(), "seasons.yml");
        YamlConfiguration y = world.lankci.pass.util.Yaml.load(plugin, f);
        if (y == null) {
            warnings.clear();
            warn("seasons.yml не прочитано - використовується попередня конфігурація");
            return false;
        }
        warnings.clear();
        ConfigurationSection root = y.getConfigurationSection("seasons");
        List<SeasonDef> list = new ArrayList<>();
        Set<Integer> numbers = new HashSet<>();
        if (root != null) {
            for (String id : root.getKeys(false)) {
                try {
                    SeasonDef d = parse(id, root.getConfigurationSection(id));
                    if (d == null) {
                        continue;
                    }
                    if (!numbers.add(d.number())) {
                        warn("seasons." + id + ": номер сезону " + d.number() + " вже використовується іншим сезоном");
                    }
                    list.add(d);
                } catch (Exception e) {
                    warn("seasons." + id + ": помилка читання - " + e.getMessage());
                }
            }
        }
        list.sort(Comparator.comparingInt(SeasonDef::number));
        Map<String, SeasonDef> m = new LinkedHashMap<>();
        for (SeasonDef d : list) {
            m.put(d.id(), d);
        }
        defs = m;
        bootstrapList = y.getStringList("bootstrap");
        if (m.isEmpty()) {
            warn("seasons.yml не містить жодного коректного сезону");
        }
        for (String w : warnings) {
            plugin.getLogger().warning(w);
        }
        return true;
    }

    private void warn(String s) {
        warnings.add(s);
    }

    private SeasonDef parse(String id, ConfigurationSection s) {
        if (s == null) {
            return null;
        }
        if (!ID_PATTERN.matcher(id).matches()) {
            warn("seasons." + id + ": id має відповідати [a-z0-9_-]{2,32} - сезон пропущено");
            return null;
        }
        int number = s.getInt("number", 0);
        if (number < 1) {
            warn("seasons." + id + ": потрібен number >= 1 - сезон пропущено");
            return null;
        }
        Material icon = material(s.getString("icon", "NETHER_STAR"), Material.NETHER_STAR, "seasons." + id + ".icon");
        int dur = s.getInt("duration-days", plugin.settings().defaultDurationDays);
        if (dur < 1 || dur > 3650) {
            warn("seasons." + id + ".duration-days поза діапазоном 1..3650, використано "
                    + plugin.settings().defaultDurationDays);
            dur = plugin.settings().defaultDurationDays;
        }
        ConfigurationSection ls = s.getConfigurationSection("levels");
        List<LevelDef> levels = new ArrayList<>();
        if (ls != null) {
            List<Integer> nums = new ArrayList<>();
            for (String k : ls.getKeys(false)) {
                try {
                    nums.add(Integer.parseInt(k));
                } catch (NumberFormatException e) {
                    warn("seasons." + id + ".levels." + k + ": ключ рівня має бути числом - пропущено");
                }
            }
            nums.sort(Integer::compare);
            for (int n : nums) {
                ConfigurationSection l = ls.getConfigurationSection(String.valueOf(n));
                if (l == null) {
                    continue;
                }
                String path = "seasons." + id + ".levels." + n;
                RequirementType type;
                try {
                    type = RequirementType.valueOf(l.getString("requirement.type", "POINTS").toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    warn(path + ".requirement.type: невідомий тип (дозволено POINTS, PLAYER_LEVEL) - використано POINTS");
                    type = RequirementType.POINTS;
                }
                long amount = l.getLong("requirement.amount", 0);
                if (amount < 0) {
                    warn(path + ".requirement.amount не може бути від'ємним - використано 0");
                    amount = 0;
                }
                List<String> cmds = new ArrayList<>(l.getStringList("reward.commands"));
                cmds.removeIf(c -> c == null || c.isBlank());
                if (cmds.isEmpty()) {
                    warn(path + ".reward.commands порожні - нагорода нічого не видаватиме");
                }
                levels.add(new LevelDef(n, l.getString("name", "Рівень " + n), type, amount,
                        material(l.getString("icon", "CHEST"), Material.CHEST, path + ".icon"),
                        new ArrayList<>(l.getStringList("reward.description")), cmds,
                        l.getString("reward.message", "")));
            }
        }
        if (levels.isEmpty()) {
            warn("seasons." + id + ": немає жодного рівня - сезон пропущено");
            return null;
        }
        return new SeasonDef(id, number, s.getString("name", "Сезон " + number), s.getString("theme", ""),
                s.getString("color", "&b"), icon, dur, s.getBoolean("legacy-import", false), List.copyOf(levels));
    }

    private Material material(String name, Material fallback, String path) {
        Material m = name == null ? null : Material.matchMaterial(name);
        if (m == null || !m.isItem()) {
            warn(path + ": невідомий матеріал '" + name + "', використано " + fallback.name());
            return fallback;
        }
        return m;
    }

    public List<String> warnings() {
        return warnings;
    }

    // ------------------------------------------------------------------ запуск і ротація

    /** Блокуючий запуск під час onEnable: створює початкову чергу та виконує ротацію. */
    public void startup() {
        long now = now();
        plugin.database().async(c -> {
            bootstrap(c, now);
            rotate(c, now);
            refreshCache(c);
            return null;
        }).join();
    }

    /** Періодична перевірка закінчення сезону (викликається з головного потоку за таймером). */
    public void tick() {
        long now = now();
        plugin.database().async(c -> {
            TickResult r = rotate(c, now);
            refreshCache(c);
            return r;
        }).thenAccept(r -> {
            if (r.ended() != null || r.started() != null) {
                plugin.sync(() -> onRotated(r));
            }
        });
    }

    private void onRotated(TickResult r) {
        plugin.onSeasonStateChanged();
        if (r.ended() != null) {
            SeasonDef d = defs.get(r.ended().seasonId());
            Bukkit.broadcast(world.lankci.pass.util.Text.c(plugin.messages().get("season.broadcast.ended",
                    "name", d == null ? r.ended().seasonId() : d.coloredName())));
        }
        if (r.started() != null) {
            SeasonDef d = defs.get(r.started().seasonId());
            SeasonRun now = run(r.started().runId());
            Bukkit.broadcast(world.lankci.pass.util.Text.c(plugin.messages().get("season.broadcast.started",
                    "name", d == null ? r.started().seasonId() : d.coloredName(),
                    "end", fmt(now == null ? r.started().endAt() : now.endAt()))));
        }
    }

    private void bootstrap(Connection c, long now) throws Exception {
        for (SeasonDef d : defs.values()) {
            ensureDefRow(c, d.id(), now);
        }
        if (scalar(c, "SELECT COUNT(*) FROM season_runs") > 0 || defs.isEmpty()) {
            return;
        }
        List<String> order = new ArrayList<>();
        for (String id : bootstrapList) {
            if (defs.containsKey(id) && !order.contains(id)) {
                order.add(id);
            }
        }
        if (order.isEmpty()) {
            for (SeasonDef d : defs.values()) {
                if (order.size() < 3) {
                    order.add(d.id());
                }
            }
        }
        Database.inTx(c, cc -> {
            long start = now;
            boolean first = true;
            for (String id : order) {
                SeasonDef d = defs.get(id);
                long end = start + d.durationDays() * DAY;
                insertRun(cc, id, first ? "ACTIVE" : "SCHEDULED", start, end, d.durationDays(),
                        first ? "INITIAL" : "SCHEDULED", "ALLOW", now);
                start = end;
                first = false;
            }
            return null;
        });
        plugin.getLogger().info("Створено початкову чергу сезонів: " + String.join(", ", order));
    }

    private TickResult rotate(Connection c, long now) throws Exception {
        return Database.inTx(c, cc -> {
            SeasonRun ended = null;
            SeasonRun started = null;
            SeasonRun active = queryActive(cc);
            if (active != null && now >= active.endAt()) {
                endRun(cc, active, now, "EXPIRED");
                ended = active;
                active = null;
            }
            if (active == null) {
                ensureQueue(cc, now);
                SeasonRun next = firstScheduled(cc);
                if (next != null && next.startAt() <= now) {
                    activate(cc, next, now);
                    started = next;
                    rechain(cc, now);
                }
            }
            ensureQueue(cc, now);
            return new TickResult(ended, started);
        });
    }

    private void activate(Connection c, SeasonRun next, long now) throws SQLException {
        long start = next.startAt();
        long end = next.endAt();
        if (end <= now) {
            start = now;
            end = now + next.durationDays() * DAY;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE season_runs SET status = 'ACTIVE', start_at = ?, end_at = ? WHERE run_id = ?")) {
            ps.setLong(1, start);
            ps.setLong(2, end);
            ps.setLong(3, next.runId());
            ps.executeUpdate();
        }
    }

    private void endRun(Connection c, SeasonRun run, long now, String reason) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE season_runs SET status = 'ENDED', ended_at = ?, end_reason = ?, remaining_ms = ? WHERE run_id = ?")) {
            ps.setLong(1, now);
            ps.setString(2, reason);
            ps.setLong(3, Math.max(0, run.endAt() - now));
            ps.setLong(4, run.runId());
            ps.executeUpdate();
        }
    }

    /** Підтримує потрібну кількість запланованих сезонів (seasons.keep-scheduled-ahead) з ще не запускавшихся. */
    private void ensureQueue(Connection c, long now) throws SQLException {
        int keep = plugin.settings().keepScheduledAhead;
        int guard = 0;
        while (guard++ < 50 && scalar(c, "SELECT COUNT(*) FROM season_runs WHERE status = 'SCHEDULED'") < keep) {
            Set<String> used = new HashSet<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT DISTINCT season_id FROM season_runs")) {
                while (rs.next()) {
                    used.add(rs.getString(1));
                }
            }
            Set<String> arch = archivedIds(c);
            SeasonDef pick = null;
            for (SeasonDef d : defs.values()) {
                if (!used.contains(d.id()) && !arch.contains(d.id())) {
                    pick = d;
                    break;
                }
            }
            if (pick == null) {
                return;
            }
            long start = lastChainEnd(c, now);
            insertRun(c, pick.id(), "SCHEDULED", start, start + pick.durationDays() * DAY, pick.durationDays(),
                    "SCHEDULED", "ALLOW", now);
        }
    }

    /** Перераховує початок/кінець запланованих запусків одразу після активного (ланцюжок). */
    private void rechain(Connection c, long now) throws SQLException {
        SeasonRun active = queryActive(c);
        List<SeasonRun> scheduled = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM season_runs WHERE status = 'SCHEDULED' ORDER BY start_at, run_id")) {
            while (rs.next()) {
                scheduled.add(map(rs));
            }
        }
        if (scheduled.isEmpty()) {
            return;
        }
        long prevEnd = active != null ? active.endAt() : scheduled.get(0).startAt();
        // активний запуск міг змінитися в цій же транзакції - читаємо його кінець свіжо
        for (SeasonRun s : scheduled) {
            long start = prevEnd;
            long end = start + s.durationDays() * DAY;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE season_runs SET start_at = ?, end_at = ? WHERE run_id = ?")) {
                ps.setLong(1, start);
                ps.setLong(2, end);
                ps.setLong(3, s.runId());
                ps.executeUpdate();
            }
            prevEnd = end;
        }
    }

    private long lastChainEnd(Connection c, long now) throws SQLException {
        long v = scalar(c, "SELECT COALESCE(MAX(end_at), 0) FROM season_runs WHERE status IN ('ACTIVE','SCHEDULED')");
        return v <= 0 ? now : v;
    }

    // ------------------------------------------------------------------ адміністративні операції

    private CompletableFuture<OpResult> run(Database.SqlFn<OpResult> fn) {
        return plugin.database().async(c -> {
            OpResult r = fn.apply(c);
            refreshCache(c);
            return r;
        }).whenComplete((r, ex) -> {
            if (r != null && r.success()) {
                plugin.sync(plugin::onSeasonStateChanged);
            }
        });
    }

    /**
     * Запуск сезону. reset=true: новий запуск з нуля, нагороди можна отримати знову (ALLOW).
     * reset=false: новий незалежний запуск, але нагороди, вже отримані в цьому сезоні, повторно не видаються.
     * Поточний активний сезон завершується (SWITCHED) і може бути відновлений командою restore.
     */
    public CompletableFuture<OpResult> start(String id, boolean reset, Integer days) {
        return run(c -> {
            SeasonDef d = defs.get(id);
            if (d == null) {
                return OpResult.fail("season.err.unknown", "id", id);
            }
            if (archivedIds(c).contains(id)) {
                return OpResult.fail("season.err.archived", "id", id);
            }
            int dur = days != null ? days : d.durationDays();
            if (dur < 1 || dur > 3650) {
                return OpResult.fail("season.err.days");
            }
            long now = now();
            SeasonRun active = queryActive(c);
            long previous = scalarArg(c, "SELECT COUNT(*) FROM season_runs WHERE season_id = ? AND status = 'ENDED'", id)
                    + (active != null && active.seasonId().equals(id) ? 1 : 0);
            plugin.database().backup(c, "pre-start-" + id);
            return Database.inTx(c, cc -> {
                ensureDefRow(cc, id, now);
                if (active != null) {
                    endRun(cc, active, now, "SWITCHED");
                }
                try (PreparedStatement ps = cc.prepareStatement(
                        "DELETE FROM season_runs WHERE season_id = ? AND status = 'SCHEDULED'")) {
                    ps.setString(1, id);
                    ps.executeUpdate();
                }
                String pm = previous == 0 ? "INITIAL" : (reset ? "RESET" : "NEW");
                String policy = (previous == 0 || reset) ? "ALLOW" : "LOCK_PREVIOUS";
                long end = now + dur * DAY;
                long runId = insertRun(cc, id, "ACTIVE", now, end, dur, pm, policy, now);
                rechain(cc, now);
                ensureQueue(cc, now);
                plugin.getLogger().info("Сезон " + id + " запущено (run #" + runId + ", режим " + pm + ")");
                return OpResult.ok("season.ok.started", "id", id, "run", runId, "end", fmt(end), "mode", pm);
            });
        });
    }

    /** Відновлення сезону: повертає останній завершений запуск (той самий runId) зі збереженим прогресом. */
    public CompletableFuture<OpResult> restore(String id, Integer days) {
        return run(c -> {
            SeasonDef d = defs.get(id);
            if (d == null) {
                return OpResult.fail("season.err.unknown", "id", id);
            }
            if (days != null && (days < 1 || days > 3650)) {
                return OpResult.fail("season.err.days");
            }
            SeasonRun active = queryActive(c);
            if (active != null && active.seasonId().equals(id)) {
                return OpResult.fail("season.err.alreadyactive", "id", id);
            }
            SeasonRun last = null;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM season_runs WHERE season_id = ? AND status = 'ENDED' "
                            + "ORDER BY COALESCE(ended_at, 0) DESC, run_id DESC LIMIT 1")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        last = map(rs);
                    }
                }
            }
            if (last == null) {
                return OpResult.fail("season.err.norun", "id", id);
            }
            final SeasonRun target = last;
            long now = now();
            plugin.database().backup(c, "pre-restore-" + id);
            return Database.inTx(c, cc -> {
                if (active != null) {
                    endRun(cc, active, now, "SWITCHED");
                }
                try (PreparedStatement ps = cc.prepareStatement("UPDATE season_defs SET archived = 0 WHERE season_id = ?")) {
                    ps.setString(1, id);
                    ps.executeUpdate();
                }
                long remaining;
                if (days != null) {
                    remaining = days * DAY;
                } else if ("SWITCHED".equals(target.endReason()) && target.remainingMs() > 0) {
                    remaining = target.remainingMs();
                } else {
                    remaining = d.durationDays() * DAY;
                }
                long end = now + remaining;
                int dur = (int) Math.max(1, Math.ceil(remaining / (double) DAY));
                try (PreparedStatement ps = cc.prepareStatement(
                        "UPDATE season_runs SET status = 'ACTIVE', start_at = ?, end_at = ?, duration_days = ?, "
                                + "ended_at = NULL, end_reason = NULL, remaining_ms = NULL, "
                                + "restore_count = restore_count + 1 WHERE run_id = ?")) {
                    ps.setLong(1, now);
                    ps.setLong(2, end);
                    ps.setInt(3, dur);
                    ps.setLong(4, target.runId());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = cc.prepareStatement(
                        "DELETE FROM season_runs WHERE season_id = ? AND status = 'SCHEDULED'")) {
                    ps.setString(1, id);
                    ps.executeUpdate();
                }
                rechain(cc, now);
                ensureQueue(cc, now);
                plugin.getLogger().info("Сезон " + id + " відновлено (run #" + target.runId() + ")");
                return OpResult.ok("season.ok.restored", "id", id, "run", target.runId(), "end", fmt(end));
            });
        });
    }

    /** Встановити точну дату завершення активного сезону. Прогрес гравців не змінюється. */
    public CompletableFuture<OpResult> setEnd(long endMs) {
        return run(c -> {
            long now = now();
            if (endMs <= now) {
                return OpResult.fail("season.err.endpast");
            }
            if (endMs > now + 3650 * DAY) {
                return OpResult.fail("season.err.days");
            }
            SeasonRun a = queryActive(c);
            if (a == null) {
                return OpResult.fail("season.err.noactive");
            }
            return Database.inTx(c, cc -> {
                int days = (int) Math.max(1, Math.ceil((endMs - a.startAt()) / (double) DAY));
                try (PreparedStatement ps = cc.prepareStatement(
                        "UPDATE season_runs SET end_at = ?, duration_days = ? WHERE run_id = ?")) {
                    ps.setLong(1, endMs);
                    ps.setInt(2, days);
                    ps.setLong(3, a.runId());
                    ps.executeUpdate();
                }
                rechain(cc, now);
                return OpResult.ok("season.ok.setend", "end", fmt(endMs), "left", TimeUtil.duration(endMs - now));
            });
        });
    }

    /** До завершення активного сезону залишається рівно days днів від зараз. */
    public CompletableFuture<OpResult> setDays(int days) {
        if (days < 1 || days > 3650) {
            return CompletableFuture.completedFuture(OpResult.fail("season.err.days"));
        }
        return setEnd(now() + days * DAY);
    }

    /**
     * Змінити тривалість: кінець = початок + days. Для активного сезону (seasonId == null) або для
     * запланованого запуску вказаного сезону.
     */
    public CompletableFuture<OpResult> setDuration(int days, String seasonId) {
        if (days < 1 || days > 3650) {
            return CompletableFuture.completedFuture(OpResult.fail("season.err.days"));
        }
        return run(c -> {
            long now = now();
            SeasonRun target = null;
            if (seasonId == null) {
                target = queryActive(c);
                if (target == null) {
                    return OpResult.fail("season.err.noactive");
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT * FROM season_runs WHERE season_id = ? AND status IN ('ACTIVE','SCHEDULED') "
                                + "ORDER BY run_id LIMIT 1")) {
                    ps.setString(1, seasonId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            target = map(rs);
                        }
                    }
                }
                if (target == null) {
                    return OpResult.fail("season.err.nothingscheduled", "id", seasonId);
                }
            }
            final SeasonRun t = target;
            long newEnd = t.startAt() + days * DAY;
            if (t.status() == RunStatus.ACTIVE && newEnd <= now) {
                return OpResult.fail("season.err.durationpast");
            }
            return Database.inTx(c, cc -> {
                try (PreparedStatement ps = cc.prepareStatement(
                        "UPDATE season_runs SET end_at = ?, duration_days = ? WHERE run_id = ?")) {
                    ps.setLong(1, newEnd);
                    ps.setInt(2, days);
                    ps.setLong(3, t.runId());
                    ps.executeUpdate();
                }
                rechain(cc, now);
                return OpResult.ok("season.ok.duration", "days", days, "end", fmt(newEnd));
            });
        });
    }

    /** Архівація: сезон не можна запланувати чи запустити (лише restore). Історія та прогрес зберігаються. */
    public CompletableFuture<OpResult> archive(String id) {
        return run(c -> {
            if (!defs.containsKey(id)) {
                return OpResult.fail("season.err.unknown", "id", id);
            }
            SeasonRun active = queryActive(c);
            if (active != null && active.seasonId().equals(id)) {
                return OpResult.fail("season.err.archiveactive", "id", id);
            }
            long now = now();
            plugin.database().backup(c, "pre-archive-" + id);
            return Database.inTx(c, cc -> {
                ensureDefRow(cc, id, now);
                try (PreparedStatement ps = cc.prepareStatement("UPDATE season_defs SET archived = 1 WHERE season_id = ?")) {
                    ps.setString(1, id);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = cc.prepareStatement(
                        "DELETE FROM season_runs WHERE season_id = ? AND status = 'SCHEDULED'")) {
                    ps.setString(1, id);
                    ps.executeUpdate();
                }
                rechain(cc, now);
                ensureQueue(cc, now);
                return OpResult.ok("season.ok.archived", "id", id);
            });
        });
    }

    /** Додати сезон у кінець черги запланованих. */
    public CompletableFuture<OpResult> schedule(String id) {
        return run(c -> {
            SeasonDef d = defs.get(id);
            if (d == null) {
                return OpResult.fail("season.err.unknown", "id", id);
            }
            if (archivedIds(c).contains(id)) {
                return OpResult.fail("season.err.archived", "id", id);
            }
            if (scalarArg(c, "SELECT COUNT(*) FROM season_runs WHERE season_id = ? AND status IN ('ACTIVE','SCHEDULED')", id) > 0) {
                return OpResult.fail("season.err.alreadyscheduled", "id", id);
            }
            long now = now();
            return Database.inTx(c, cc -> {
                ensureDefRow(cc, id, now);
                boolean had = scalarArg(cc, "SELECT COUNT(*) FROM season_runs WHERE season_id = ?", id) > 0;
                long start = lastChainEnd(cc, now);
                insertRun(cc, id, "SCHEDULED", start, start + d.durationDays() * DAY, d.durationDays(),
                        "SCHEDULED", had ? "LOCK_PREVIOUS" : "ALLOW", now);
                return OpResult.ok("season.ok.scheduled", "id", id, "start", fmt(start));
            });
        });
    }

    /** Створити новий сезон: дописує шаблон на 15 рівнів у кінець seasons.yml (коментарі файлу не втрачаються). */
    public CompletableFuture<OpResult> create(String id) {
        if (!ID_PATTERN.matcher(id).matches()) {
            return CompletableFuture.completedFuture(OpResult.fail("season.err.badid"));
        }
        if (defs.containsKey(id)) {
            return CompletableFuture.completedFuture(OpResult.fail("season.err.exists", "id", id));
        }
        File f = new File(plugin.getDataFolder(), "seasons.yml");
        int number = 1;
        for (SeasonDef d : defs.values()) {
            number = Math.max(number, d.number() + 1);
        }
        try {
            String existing = Files.exists(f.toPath()) ? Files.readString(f.toPath(), StandardCharsets.UTF_8) : "seasons:\n";
            if (Pattern.compile("(?m)^ {2}[\"']?" + Pattern.quote(id) + "[\"']?\\s*:").matcher(existing).find()) {
                return CompletableFuture.completedFuture(OpResult.fail("season.err.exists", "id", id));
            }
            String text = (existing.endsWith("\n") ? "" : "\n") + template(id, number);
            Files.writeString(f.toPath(), text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            plugin.getLogger().warning("Не вдалося записати seasons.yml: " + e.getMessage());
            return CompletableFuture.completedFuture(OpResult.fail("season.err.io", "error", e.getMessage()));
        }
        loadDefinitions();
        final int n = number;
        return run(c -> {
            ensureDefRow(c, id, now());
            if (!defs.containsKey(id)) {
                return OpResult.fail("season.err.unknown", "id", id);
            }
            return OpResult.ok("season.ok.created", "id", id, "number", n);
        });
    }

    private static String template(String id, int number) {
        long[] points = {1000, 2500, 4500, 7000, 10000, 13500, 17500, 22000, 27000, 32500, 38500, 44500, 50500, 55500, 60000};
        String[] icons = {"IRON_INGOT", "GOLD_INGOT", "DIAMOND", "EXPERIENCE_BOTTLE", "OBSIDIAN", "EMERALD", "BEACON",
                "NETHERITE_SCRAP", "TOTEM_OF_UNDYING", "GOLDEN_APPLE", "DIAMOND_BLOCK", "NETHERITE_INGOT",
                "ENCHANTED_GOLDEN_APPLE", "ELYTRA", "DRAGON_HEAD"};
        String[] items = {"iron_ingot 32", "gold_ingot 16", "diamond 5", "experience_bottle 64", "obsidian 32",
                "emerald 32", "beacon 1", "netherite_scrap 4", "totem_of_undying 1", "golden_apple 8",
                "diamond_block 8", "netherite_ingot 3", "enchanted_golden_apple 2", "elytra 1", "dragon_head 1"};
        StringBuilder sb = new StringBuilder();
        sb.append("\n  # Створено командою /lankcipass season create. Відредагуйте тематику, нагороди й умови.\n");
        sb.append("  ").append(id).append(":\n");
        sb.append("    number: ").append(number).append("\n");
        sb.append("    name: \"Сезон ").append(number).append("\"\n");
        sb.append("    theme: \"Опишіть тематику сезону\"\n");
        sb.append("    color: \"&b\"\n");
        sb.append("    icon: NETHER_STAR\n");
        sb.append("    duration-days: 30\n");
        sb.append("    levels:\n");
        for (int i = 0; i < 15; i++) {
            sb.append("      ").append(i + 1).append(":\n");
            sb.append("        name: \"Рівень ").append(i + 1).append("\"\n");
            sb.append("        requirement: {type: POINTS, amount: ").append(points[i]).append("}\n");
            sb.append("        icon: ").append(icons[i]).append("\n");
            sb.append("        reward:\n");
            sb.append("          description: [\"&fВміст: &7").append(items[i].replace('_', ' ')).append("\"]\n");
            sb.append("          commands:\n");
            sb.append("            - \"give {player} minecraft:").append(items[i]).append("\"\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ доступ до стану (потокобезпечні геттери)

    public Collection<SeasonDef> allDefs() {
        return defs.values();
    }

    public SeasonDef def(String id) {
        return id == null ? null : defs.get(id);
    }

    public SeasonDef defOf(SeasonRun r) {
        return r == null ? null : defs.get(r.seasonId());
    }

    public boolean isArchived(String id) {
        return archived.contains(id);
    }

    public SeasonRun activeRun() {
        for (SeasonRun r : runs) {
            if (r.status() == RunStatus.ACTIVE) {
                return r;
            }
        }
        return null;
    }

    public List<SeasonRun> scheduledRuns() {
        List<SeasonRun> out = new ArrayList<>();
        for (SeasonRun r : runs) {
            if (r.status() == RunStatus.SCHEDULED) {
                out.add(r);
            }
        }
        out.sort(Comparator.comparingLong(SeasonRun::startAt).thenComparingLong(SeasonRun::runId));
        return out;
    }

    /** Завершені запуски, від найновіших. */
    public List<SeasonRun> endedRuns() {
        List<SeasonRun> out = new ArrayList<>();
        for (SeasonRun r : runs) {
            if (r.status() == RunStatus.ENDED) {
                out.add(r);
            }
        }
        out.sort(Comparator.comparingLong(SeasonRun::endedAt).reversed());
        return out;
    }

    public List<SeasonRun> runsOf(String seasonId) {
        List<SeasonRun> out = new ArrayList<>();
        for (SeasonRun r : runs) {
            if (r.seasonId().equals(seasonId)) {
                out.add(r);
            }
        }
        return out;
    }

    public SeasonRun run(long runId) {
        for (SeasonRun r : runs) {
            if (r.runId() == runId) {
                return r;
            }
        }
        return null;
    }

    public int totalRuns() {
        return runs.size();
    }

    /** Короткий статус сезону для списків: ACTIVE, SCHEDULED, ARCHIVED, ENDED, UNUSED. */
    public String stateOf(SeasonDef d) {
        SeasonRun a = activeRun();
        if (a != null && a.seasonId().equals(d.id())) {
            return "ACTIVE";
        }
        for (SeasonRun r : runs) {
            if (r.seasonId().equals(d.id()) && r.status() == RunStatus.SCHEDULED) {
                return "SCHEDULED";
            }
        }
        if (archived.contains(d.id())) {
            return "ARCHIVED";
        }
        for (SeasonRun r : runs) {
            if (r.seasonId().equals(d.id())) {
                return "ENDED";
            }
        }
        return "UNUSED";
    }

    /** Залишок часу активного сезону від реального системного часу (а не від відкритого меню). */
    public long remainingMs() {
        SeasonRun a = activeRun();
        return a == null ? 0 : a.remaining(now());
    }

    public String fmt(long ms) {
        return Instant.ofEpochMilli(ms).atZone(plugin.settings().zone).format(plugin.settings().dateFormat);
    }

    public static long now() {
        return System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ SQL-допоміжні

    private void refreshCache(Connection c) throws SQLException {
        List<SeasonRun> list = queryRuns(c);
        Set<String> arch = archivedIds(c);
        runs = List.copyOf(list);
        archived = Set.copyOf(arch);
    }

    private static List<SeasonRun> queryRuns(Connection c) throws SQLException {
        List<SeasonRun> out = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM season_runs ORDER BY run_id")) {
            while (rs.next()) {
                out.add(map(rs));
            }
        }
        return out;
    }

    private static Set<String> archivedIds(Connection c) throws SQLException {
        Set<String> out = new HashSet<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT season_id FROM season_defs WHERE archived = 1")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private static SeasonRun queryActive(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM season_runs WHERE status = 'ACTIVE'")) {
            return rs.next() ? map(rs) : null;
        }
    }

    private static SeasonRun firstScheduled(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM season_runs WHERE status = 'SCHEDULED' ORDER BY start_at, run_id LIMIT 1")) {
            return rs.next() ? map(rs) : null;
        }
    }

    private static SeasonRun map(ResultSet rs) throws SQLException {
        return new SeasonRun(rs.getLong("run_id"), rs.getString("season_id"),
                RunStatus.valueOf(rs.getString("status")), rs.getLong("start_at"), rs.getLong("end_at"),
                rs.getInt("duration_days"), rs.getString("progress_mode"), rs.getString("claim_policy"),
                rs.getLong("created_at"), rs.getLong("ended_at"), rs.getString("end_reason"),
                rs.getLong("remaining_ms"), rs.getInt("restore_count"));
    }

    private static long insertRun(Connection c, String seasonId, String status, long start, long end, int days,
                                  String progressMode, String claimPolicy, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO season_runs(season_id, status, start_at, end_at, duration_days, progress_mode, claim_policy, created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, seasonId);
            ps.setString(2, status);
            ps.setLong(3, start);
            ps.setLong(4, end);
            ps.setInt(5, days);
            ps.setString(6, progressMode);
            ps.setString(7, claimPolicy);
            ps.setLong(8, now);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    private static void ensureDefRow(Connection c, String id, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR IGNORE INTO season_defs(season_id, archived, created_at) VALUES (?,0,?)")) {
            ps.setString(1, id);
            ps.setLong(2, now);
            ps.executeUpdate();
        }
    }

    private static long scalar(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private static long scalarArg(Connection c, String sql, String arg) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }
}
