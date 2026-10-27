package world.lankci.pass.compat;

import org.bukkit.entity.Player;
import world.lankci.pass.LankciPassCore;
import world.lankci.pass.season.SeasonDef;
import world.lankci.pass.season.SeasonRun;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Одноразовий імпорт нагород зі старого LankciPass (права pass.N.done у LuckPerms) у SQLite.
 * Працює для сезону з legacy-import: true, один раз на гравця. Старі нагороди повторно не видаються.
 */
public final class LegacyImporter {
    private final LankciPassCore plugin;
    private LuckPermsHook hook;

    public LegacyImporter(LankciPassCore plugin) {
        this.plugin = plugin;
    }

    public void init() {
        var lp = plugin.getServer().getPluginManager().getPlugin("LuckPerms");
        if (lp != null && lp.isEnabled()) {
            try {
                hook = new LuckPermsHook();
            } catch (Throwable t) {
                plugin.getLogger().warning("LuckPerms знайдено, але API недоступне: " + t.getMessage());
            }
        }
    }

    public boolean available() {
        return hook != null;
    }

    public CompletableFuture<Void> importFor(Player player) {
        if (hook == null || !plugin.settings().legacyImport) {
            return CompletableFuture.completedFuture(null);
        }
        SeasonRun run = plugin.seasons().activeRun();
        SeasonDef def = plugin.seasons().defOf(run);
        if (run == null || def == null || !def.legacyImport()) {
            return CompletableFuture.completedFuture(null);
        }
        UUID uuid = player.getUniqueId();
        return plugin.database().async(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT legacy_imported FROM players WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getInt(1) == 0;
                }
            }
        }).thenCompose(needed -> {
            if (!needed) {
                return CompletableFuture.completedFuture(null);
            }
            int max = def.levels().stream().mapToInt(l -> l.number()).max().orElse(15);
            return hook.legacyLevels(uuid, max).thenCompose((Set<Integer> levels) ->
                    plugin.database().tx(c -> {
                        int imported = 0;
                        for (int lvl : levels) {
                            if (def.level(lvl) == null) {
                                continue;
                            }
                            try (PreparedStatement ps = c.prepareStatement(
                                    "INSERT OR IGNORE INTO reward_claims(run_id, uuid, level, season_id, claimed_at, source) "
                                            + "VALUES (?,?,?,?,?, 'LEGACY')")) {
                                ps.setLong(1, run.runId());
                                ps.setString(2, uuid.toString());
                                ps.setInt(3, lvl);
                                ps.setString(4, def.id());
                                ps.setLong(5, System.currentTimeMillis());
                                imported += ps.executeUpdate();
                            }
                        }
                        try (PreparedStatement ps = c.prepareStatement("UPDATE players SET legacy_imported = 1 WHERE uuid = ?")) {
                            ps.setString(1, uuid.toString());
                            ps.executeUpdate();
                        }
                        if (imported > 0) {
                            plugin.getLogger().info("Імпортовано " + imported + " старих нагород LuckPerms для " + player.getName());
                        }
                        return null;
                    })).thenApply(x -> (Void) null);
        });
    }
}
