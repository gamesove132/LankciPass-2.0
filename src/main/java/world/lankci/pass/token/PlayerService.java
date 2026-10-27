package world.lankci.pass.token;

import org.bukkit.entity.Player;
import world.lankci.pass.LankciPassCore;
import world.lankci.pass.reward.RewardService;
import world.lankci.pass.season.SeasonRun;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Створення запису гравця та оновлення кешу (баланс, очки, отримані рівні, прогрес квестів). */
public final class PlayerService {
    private final LankciPassCore plugin;

    public PlayerService(LankciPassCore plugin) {
        this.plugin = plugin;
    }

    /** Викликати при вході гравця (головний потік). */
    public void onJoin(Player player) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        long now = System.currentTimeMillis();
        plugin.database().tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO players(uuid, name, tokens, total_earned, first_seen, last_seen) VALUES (?,?,0,0,?,?) "
                            + "ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, last_seen = excluded.last_seen")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setLong(3, now);
                ps.setLong(4, now);
                ps.executeUpdate();
            }
            return null;
        }).whenComplete((r, ex) -> {
            if (ex == null) {
                plugin.sync(() -> {
                    Player p = plugin.getServer().getPlayer(uuid);
                    if (p != null) {
                        plugin.legacyImport(p).whenComplete((x, e) -> plugin.sync(() -> refresh(uuid)));
                    }
                });
            }
        });
    }

    /** Перечитати з бази баланс, очки та отримані рівні активного сезону, прогрес квестів. */
    public CompletableFuture<Void> refresh(UUID uuid) {
        return plugin.database().async(c -> {
            long tokens = 0;
            boolean found = false;
            try (PreparedStatement ps = c.prepareStatement("SELECT tokens FROM players WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        tokens = rs.getLong(1);
                        found = true;
                    }
                }
            }
            if (!found) {
                return null;
            }
            long points = 0;
            long runId = -1;
            Set<Integer> claimed = Set.of();
            SeasonRun active = plugin.seasons().activeRun();
            if (active != null) {
                runId = active.runId();
                points = TokenService.readPoints(c, runId, uuid);
                claimed = RewardService.loadClaimed(c, active, uuid);
            }
            Map<String, PlayerCache.QuestProg> quests = plugin.questService().loadProgress(c, uuid);
            final long t = tokens;
            final long p = points;
            final long r = runId;
            final Set<Integer> cl = claimed;
            plugin.sync(() -> {
                PlayerCache.Stats s = plugin.cache().getOrCreate(uuid);
                s.tokens = t;
                s.points = p;
                s.runId = r;
                s.claimed = cl;
                s.quests = quests;
                s.loaded = true;
            });
            return null;
        });
    }

    public void refreshAllOnline() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            refresh(p.getUniqueId());
        }
    }
}
