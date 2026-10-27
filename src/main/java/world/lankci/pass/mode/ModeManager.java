package world.lankci.pass.mode;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import world.lankci.pass.LankciPassCore;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Прив'язка світів до режимів (smp / hardcore / oneblock). Пріоритет визначення режиму світу:
 *  1) явний UUID світу в config.yml (modes.&lt;id&gt;.world-uuids);
 *  2) назва світу в config.yml (modes.&lt;id&gt;.worlds);
 *  3) UUID, запам'ятований у базі (world_bindings) - працює, навіть якщо світ перейменували.
 * Невідомий світ = режиму немає = прогрес квестів не нараховується.
 */
public final class ModeManager {
    public record Mode(String id, String display, Material icon, Set<String> worlds, Set<UUID> worldUuids) {
    }

    private final LankciPassCore plugin;
    private volatile Map<String, Mode> modes = new LinkedHashMap<>();
    private final Map<UUID, String> learned = new ConcurrentHashMap<>();

    public ModeManager(LankciPassCore plugin) {
        this.plugin = plugin;
    }

    /** Читає config.yml і (при першому виклику) підтягує збережені прив'язки світів з бази. */
    public void load() {
        Map<String, Mode> m = new LinkedHashMap<>();
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("modes");
        if (sec != null) {
            for (String id : sec.getKeys(false)) {
                ConfigurationSection s = sec.getConfigurationSection(id);
                if (s == null) {
                    continue;
                }
                String key = id.toLowerCase(java.util.Locale.ROOT);
                Material icon = Material.matchMaterial(s.getString("icon", "GRASS_BLOCK"));
                if (icon == null) {
                    plugin.getLogger().warning("modes." + id + ".icon - невідомий матеріал, використано GRASS_BLOCK");
                    icon = Material.GRASS_BLOCK;
                }
                Set<UUID> uuids = new HashSet<>();
                for (String u : s.getStringList("world-uuids")) {
                    try {
                        uuids.add(UUID.fromString(u.trim()));
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("modes." + id + ".world-uuids: некоректний UUID '" + u + "'");
                    }
                }
                m.put(key, new Mode(key, s.getString("display", id), icon,
                        new HashSet<>(s.getStringList("worlds")), uuids));
            }
        }
        modes = m;
        if (m.isEmpty()) {
            plugin.getLogger().warning("У config.yml не налаштовано жодного режиму (modes) - квести не працюватимуть.");
        }
        loadLearned();
    }

    private void loadLearned() {
        if (plugin.database() == null || !plugin.database().isOpen()) {
            return;
        }
        try {
            Map<UUID, String> rows = plugin.database().async(c -> {
                Map<UUID, String> out = new LinkedHashMap<>();
                try (PreparedStatement ps = c.prepareStatement("SELECT world_uuid, mode FROM world_bindings");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        try {
                            out.put(UUID.fromString(rs.getString(1)), rs.getString(2));
                        } catch (IllegalArgumentException ignored) {
                            // пошкоджений рядок - пропускаємо
                        }
                    }
                }
                return out;
            }).join();
            learned.clear();
            learned.putAll(rows);
        } catch (Exception e) {
            plugin.getLogger().warning("Не вдалося завантажити прив'язки світів: " + e.getMessage());
        }
    }

    public Collection<Mode> all() {
        return modes.values();
    }

    public Mode get(String id) {
        return id == null ? null : modes.get(id.toLowerCase(java.util.Locale.ROOT));
    }

    /** id режиму для світу або null, якщо світ не належить жодному режиму. */
    public String modeOf(World world) {
        if (world == null) {
            return null;
        }
        UUID uid = world.getUID();
        String name = world.getName();
        for (Mode m : modes.values()) {
            if (m.worldUuids().contains(uid)) {
                return m.id();
            }
        }
        for (Mode m : modes.values()) {
            if (m.worlds().contains(name)) {
                return m.id();
            }
        }
        String l = learned.get(uid);
        return l != null && modes.containsKey(l) ? l : null;
    }

    /** Запам'ятати UUID відомих світів (щоб перейменування світу не ламало режим). */
    public void bindLoadedWorlds() {
        for (World w : Bukkit.getWorlds()) {
            bind(w);
        }
    }

    public void bind(World w) {
        String viaConfig = null;
        for (Mode m : modes.values()) {
            if (m.worldUuids().contains(w.getUID()) || m.worlds().contains(w.getName())) {
                viaConfig = m.id();
                break;
            }
        }
        if (viaConfig == null || viaConfig.equals(learned.get(w.getUID()))) {
            return;
        }
        learned.put(w.getUID(), viaConfig);
        final String mode = viaConfig;
        final UUID uid = w.getUID();
        final String name = w.getName();
        plugin.database().tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO world_bindings(world_uuid, world_name, mode, updated_at) VALUES (?,?,?,?) "
                            + "ON CONFLICT(world_uuid) DO UPDATE SET world_name = excluded.world_name, "
                            + "mode = excluded.mode, updated_at = excluded.updated_at")) {
                ps.setString(1, uid.toString());
                ps.setString(2, name);
                ps.setString(3, mode);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Список світів без режиму (для /lankcipass debug). */
    public List<String> unmappedWorlds() {
        List<String> out = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) {
            if (modeOf(w) == null) {
                out.add(w.getName());
            }
        }
        return out;
    }
}
