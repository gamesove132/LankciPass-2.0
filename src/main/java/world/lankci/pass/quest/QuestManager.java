package world.lankci.pass.quest;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import world.lankci.pass.LankciPassCore;
import world.lankci.pass.db.Database;
import world.lankci.pass.mode.ModeManager;
import world.lankci.pass.util.TimeUtil;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** Конфігурація квестів (quests.yml) і цикли (24 год / 3 дні) для кожного режиму. */
public final class QuestManager {
    public record Difficulty(String id, String name, String color, Material icon) {
        public String coloredName() {
            return color + name;
        }
    }

    public record KindSettings(long periodMs, Map<String, Integer> pick) {
    }

    private final LankciPassCore plugin;
    private volatile Map<String, Difficulty> difficulties = new LinkedHashMap<>();
    private volatile Map<QuestKind, KindSettings> settings = new HashMap<>();
    /** mode -> kind -> id -> quest. */
    private volatile Map<String, Map<QuestKind, Map<String, QuestDef>>> quests = new HashMap<>();
    private volatile Map<String, Cycle> cycles = new HashMap<>();
    private final List<String> warnings = new ArrayList<>();

    public QuestManager(LankciPassCore plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ конфігурація

    /** @return false, якщо файл не вдалося прочитати (залишається попередня конфігурація). */
    public boolean loadConfig() {
        YamlConfiguration y = world.lankci.pass.util.Yaml.load(plugin, new File(plugin.getDataFolder(), "quests.yml"));
        if (y == null) {
            return false;
        }
        List<String> w = new ArrayList<>();

        Map<String, Difficulty> diffs = new LinkedHashMap<>();
        ConfigurationSection ds = y.getConfigurationSection("difficulties");
        if (ds != null) {
            for (String id : ds.getKeys(false)) {
                ConfigurationSection s = ds.getConfigurationSection(id);
                if (s == null) {
                    continue;
                }
                Material icon = Material.matchMaterial(s.getString("icon", "PAPER"));
                if (icon == null || !icon.isItem()) {
                    w.add("difficulties." + id + ".icon: невідомий матеріал, використано PAPER");
                    icon = Material.PAPER;
                }
                String key = id.toUpperCase(Locale.ROOT);
                diffs.put(key, new Difficulty(key, s.getString("name", id), s.getString("color", "&f"), icon));
            }
        }
        if (diffs.isEmpty()) {
            w.add("difficulties порожні - квести не завантажено");
        }

        Map<QuestKind, KindSettings> st = new HashMap<>();
        for (QuestKind k : QuestKind.values()) {
            ConfigurationSection s = y.getConfigurationSection("settings." + k.key());
            long hours = s == null ? (k == QuestKind.DAILY ? 24 : 72) : s.getLong("period-hours", k == QuestKind.DAILY ? 24 : 72);
            if (hours < 1 || hours > 24 * 365) {
                w.add("settings." + k.key() + ".period-hours поза діапазоном 1..8760, використано типове");
                hours = k == QuestKind.DAILY ? 24 : 72;
            }
            Map<String, Integer> pick = new LinkedHashMap<>();
            ConfigurationSection ps = s == null ? null : s.getConfigurationSection("pick");
            if (ps != null) {
                for (String d : ps.getKeys(false)) {
                    String key = d.toUpperCase(Locale.ROOT);
                    int n = ps.getInt(d, 0);
                    if (!diffs.containsKey(key)) {
                        w.add("settings." + k.key() + ".pick." + d + ": невідома складність - пропущено");
                    } else if (n < 0 || n > 6) {
                        w.add("settings." + k.key() + ".pick." + d + ": дозволено 0..6 - пропущено");
                    } else if (n > 0) {
                        pick.put(key, n);
                    }
                }
            }
            int total = pick.values().stream().mapToInt(Integer::intValue).sum();
            if (total == 0) {
                w.add("settings." + k.key() + ".pick порожній - цикли " + k.key() + " не створюватимуться");
            } else if (total > 6) {
                w.add("settings." + k.key() + ".pick: разом більше 6 квестів - GUI показує лише перші 6");
            }
            st.put(k, new KindSettings(hours * TimeUtil.HOUR, pick));
        }

        Map<String, Map<QuestKind, Map<String, QuestDef>>> all = new HashMap<>();
        ConfigurationSection ms = y.getConfigurationSection("modes");
        if (ms != null) {
            for (String modeId : ms.getKeys(false)) {
                String mode = modeId.toLowerCase(Locale.ROOT);
                if (plugin.modes().get(mode) == null) {
                    w.add("quests.yml: режим '" + modeId + "' не оголошено в config.yml (modes) - квести режиму пропущено");
                    continue;
                }
                for (QuestKind k : QuestKind.values()) {
                    ConfigurationSection ks = ms.getConfigurationSection(modeId + "." + k.key());
                    if (ks == null) {
                        continue;
                    }
                    Map<String, QuestDef> map = new LinkedHashMap<>();
                    for (String qid : ks.getKeys(false)) {
                        QuestDef d = parse(mode, k, qid, ks.getConfigurationSection(qid), diffs, w);
                        if (d != null) {
                            map.put(qid, d);
                        }
                    }
                    all.computeIfAbsent(mode, m -> new HashMap<>()).put(k, map);
                }
            }
        }
        for (var m : plugin.modes().all()) {
            for (QuestKind k : QuestKind.values()) {
                Map<String, QuestDef> pool = all.getOrDefault(m.id(), Map.of()).getOrDefault(k, Map.of());
                for (String d : st.get(k).pick().keySet()) {
                    boolean any = pool.values().stream().anyMatch(q -> q.difficulty().equals(d));
                    if (!any) {
                        w.add("quests.yml: у режимі " + m.id() + " немає " + k.key() + "-квестів складності " + d);
                    }
                }
            }
        }

        difficulties = diffs;
        settings = st;
        quests = all;
        warnings.clear();
        warnings.addAll(w);
        for (String s : w) {
            plugin.getLogger().warning(s);
        }
        return true;
    }

    private QuestDef parse(String mode, QuestKind kind, String id, ConfigurationSection s,
                           Map<String, Difficulty> diffs, List<String> w) {
        String path = "modes." + mode + "." + kind.key() + "." + id;
        if (s == null) {
            return null;
        }
        if (id.contains(":") || id.contains(",") || id.contains(" ")) {
            w.add(path + ": id не може містити пробіли, ',' або ':' - квест пропущено");
            return null;
        }
        QuestType type;
        try {
            type = QuestType.valueOf(s.getString("type", "").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            w.add(path + ".type: невідомий тип - квест пропущено");
            return null;
        }
        long amount = s.getLong("amount", 0);
        if (amount < 1 || amount > 10_000_000) {
            w.add(path + ".amount має бути 1..10000000 - квест пропущено");
            return null;
        }
        long reward = s.getLong("reward", 0);
        if (reward < 0 || reward > 100_000_000) {
            w.add(path + ".reward має бути 0..100000000 - квест пропущено");
            return null;
        }
        String diff = s.getString("difficulty", "").toUpperCase(Locale.ROOT);
        if (!diffs.containsKey(diff)) {
            w.add(path + ".difficulty: невідома складність '" + diff + "' - квест пропущено");
            return null;
        }
        Set<EntityType> ents = new LinkedHashSet<>();
        for (String e : s.getStringList("entities")) {
            try {
                ents.add(EntityType.valueOf(e.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                w.add(path + ".entities: невідомий тип '" + e + "' - пропущено");
            }
        }
        Set<Material> mats = new LinkedHashSet<>();
        for (String m : s.getStringList("materials")) {
            Material mat = Material.matchMaterial(m);
            if (mat == null) {
                w.add(path + ".materials: невідомий матеріал '" + m + "' - пропущено");
            } else {
                mats.add(mat);
            }
        }
        return new QuestDef(id, mode, kind, s.getString("name", id), new ArrayList<>(s.getStringList("description")),
                diff, type, amount, reward, ents, mats, new ArrayList<>(s.getStringList("bonus-commands")));
    }

    public List<String> warnings() {
        return warnings;
    }

    public Map<String, Difficulty> difficulties() {
        return difficulties;
    }

    public Difficulty difficulty(String id) {
        return difficulties.get(id);
    }

    public KindSettings settings(QuestKind k) {
        return settings.get(k);
    }

    public QuestDef def(String mode, QuestKind kind, String id) {
        return quests.getOrDefault(mode, Map.of()).getOrDefault(kind, Map.of()).get(id);
    }

    public Collection<QuestDef> pool(String mode, QuestKind kind) {
        return quests.getOrDefault(mode, Map.of()).getOrDefault(kind, Map.of()).values();
    }

    public int totalQuests() {
        int n = 0;
        for (var a : quests.values()) {
            for (var b : a.values()) {
                n += b.size();
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ цикли

    private static String key(String mode, QuestKind kind) {
        return mode + ":" + kind.key();
    }

    public Cycle current(String mode, QuestKind kind) {
        if (mode == null) {
            return null;
        }
        Cycle c = cycles.get(key(mode, kind));
        return c != null && c.endAt() > System.currentTimeMillis() ? c : null;
    }

    /** Квести поточного циклу в порядку збереження; видалені з конфігурації пропускаються. */
    public List<QuestDef> questsOf(Cycle c) {
        List<QuestDef> out = new ArrayList<>();
        if (c == null) {
            return out;
        }
        for (String id : c.questIds()) {
            QuestDef d = def(c.mode(), c.kind(), id);
            if (d != null) {
                out.add(d);
            }
        }
        return out;
    }

    public Collection<Cycle> allCurrent() {
        return cycles.values();
    }

    /** Блокуюча ініціалізація під час onEnable. */
    public void startup() {
        plugin.database().async(c -> {
            rotate(c, System.currentTimeMillis(), null, null);
            reloadCache(c);
            return null;
        }).join();
    }

    /** Перевірка закінчення циклів; після зміни оновлює кеші гравців. */
    public void tick() {
        plugin.database().async(c -> {
            List<Cycle> created = rotate(c, System.currentTimeMillis(), null, null);
            if (!created.isEmpty()) {
                reloadCache(c);
            }
            return created;
        }).thenAccept(created -> {
            if (!created.isEmpty()) {
                plugin.sync(() -> {
                    plugin.getLogger().info("Створено нових циклів квестів: " + created.size());
                    plugin.players().refreshAllOnline();
                    for (Cycle cy : created) {
                        plugin.notifyNewCycle(cy);
                    }
                });
            }
        });
    }

    /** Примусова зміна циклу (адмін). mode/kind = null означає «усі». */
    public java.util.concurrent.CompletableFuture<Integer> forceRotate(String mode, QuestKind kind) {
        return plugin.database().async(c -> {
            plugin.database().backup(c, "pre-quest-rotate");
            List<Cycle> created = rotate(c, System.currentTimeMillis(), mode, kind == null ? null : kind);
            reloadCache(c);
            return created.size();
        }).whenComplete((n, ex) -> plugin.sync(() -> plugin.players().refreshAllOnline()));
    }

    private List<Cycle> rotate(Connection c, long now, String forceMode, QuestKind forceKind) throws SQLException {
        List<Cycle> created = new ArrayList<>();
        for (ModeManager.Mode m : plugin.modes().all()) {
            for (QuestKind k : QuestKind.values()) {
                boolean force = (forceMode != null || forceKind != null)
                        && (forceMode == null || forceMode.equals(m.id()))
                        && (forceKind == null || forceKind == k);
                KindSettings ks = settings.get(k);
                if (ks == null || ks.pick().isEmpty()) {
                    continue;
                }
                Cycle last = latest(c, m.id(), k);
                if (last != null && last.endAt() > now && !force) {
                    continue;
                }
                List<String> ids = pick(m.id(), k, last);
                if (ids.isEmpty()) {
                    continue;
                }
                long period = ks.periodMs();
                long start = now;
                if (last != null && !force && last.endAt() <= now) {
                    // без дрейфу: початок кратний періоду від попереднього циклу
                    start = last.endAt() + ((now - last.endAt()) / period) * period;
                }
                long end = start + period;
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO quest_cycles(mode, kind, start_at, end_at, quest_ids) VALUES (?,?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, m.id());
                    ps.setString(2, k.name());
                    ps.setLong(3, start);
                    ps.setLong(4, end);
                    ps.setString(5, String.join(",", ids));
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        long id = rs.next() ? rs.getLong(1) : -1;
                        created.add(new Cycle(id, m.id(), k, start, end, ids));
                    }
                }
            }
        }
        return created;
    }

    /** Випадковий вибір квестів за складністю; уникає повтору квестів попереднього циклу, якщо є альтернатива. */
    private List<String> pick(String mode, QuestKind kind, Cycle last) {
        KindSettings ks = settings.get(kind);
        List<String> out = new ArrayList<>();
        for (var e : ks.pick().entrySet()) {
            List<QuestDef> pool = new ArrayList<>();
            for (QuestDef q : pool(mode, kind)) {
                if (q.difficulty().equals(e.getKey())) {
                    pool.add(q);
                }
            }
            List<QuestDef> fresh = new ArrayList<>(pool);
            if (last != null) {
                fresh.removeIf(q -> last.questIds().contains(q.id()));
            }
            List<QuestDef> source = fresh.size() >= e.getValue() ? fresh : pool;
            List<QuestDef> copy = new ArrayList<>(source);
            for (int i = 0; i < e.getValue() && !copy.isEmpty(); i++) {
                QuestDef q = copy.remove(ThreadLocalRandom.current().nextInt(copy.size()));
                out.add(q.id());
            }
        }
        return out;
    }

    private static Cycle latest(Connection c, String mode, QuestKind kind) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM quest_cycles WHERE mode = ? AND kind = ? ORDER BY cycle_id DESC LIMIT 1")) {
            ps.setString(1, mode);
            ps.setString(2, kind.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    private static Cycle map(ResultSet rs) throws SQLException {
        String ids = rs.getString("quest_ids");
        List<String> list = ids == null || ids.isBlank() ? List.of() : List.of(ids.split(","));
        return new Cycle(rs.getLong("cycle_id"), rs.getString("mode"), QuestKind.valueOf(rs.getString("kind")),
                rs.getLong("start_at"), rs.getLong("end_at"), list);
    }

    /** Завантажує останній цикл кожного режиму та виду з бази в пам'ять (виклик у потоці БД). */
    public void reloadCache(Connection c) throws SQLException {
        Map<String, Cycle> m = new HashMap<>();
        for (ModeManager.Mode mode : plugin.modes().all()) {
            for (QuestKind k : QuestKind.values()) {
                Cycle cy = latest(c, mode.id(), k);
                if (cy != null) {
                    m.put(key(mode.id(), k), cy);
                }
            }
        }
        cycles = m;
    }

    /** Для спільного використання з Database.inTx у зовнішніх класах. */
    public static <T> T tx(Connection c, Database.SqlFn<T> fn) throws Exception {
        return Database.inTx(c, fn);
    }
}
