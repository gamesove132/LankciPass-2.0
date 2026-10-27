package world.lankci.pass.pvp;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import world.lankci.pass.LankciPassCore;
import world.lankci.pass.config.PluginSettings;
import world.lankci.pass.quest.QuestType;
import world.lankci.pass.token.TokenService;
import world.lankci.pass.util.Text;

import java.net.InetAddress;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/** Жетони за вбивства гравців із захистом від фарму (самогубства, одна жертва, один IP, дублікати подій). */
public final class PvpService {
    private record Result(String status, long applied, long balance) {
    }

    private final LankciPassCore plugin;
    private final Map<UUID, Long> joinTimes = new ConcurrentHashMap<>();

    public PvpService(LankciPassCore plugin) {
        this.plugin = plugin;
    }

    public void onJoin(UUID uuid) {
        joinTimes.put(uuid, System.currentTimeMillis());
    }

    public void onQuit(UUID uuid) {
        joinTimes.remove(uuid);
    }

    /** Головний потік, викликається з PlayerDeathEvent. */
    public void handleDeath(Player victim, Player killer) {
        PluginSettings s = plugin.settings();
        if (!s.pvpEnabled || killer == null || victim.getUniqueId().equals(killer.getUniqueId())) {
            return; // самогубство або смерть без гравця-вбивці
        }
        String mode = plugin.modes().modeOf(victim.getWorld());
        if (mode == null && s.pvpRequireMode) {
            reject(killer, "mode");
            return;
        }
        if (killer.getWorld() != victim.getWorld()) {
            reject(killer, "world");
            return;
        }
        if (s.ignoreCreative && (killer.getGameMode() == GameMode.CREATIVE || killer.getGameMode() == GameMode.SPECTATOR
                || victim.getGameMode() == GameMode.CREATIVE)) {
            reject(killer, "gamemode");
            return;
        }
        if (s.pvpIgnoreSameIp) {
            InetAddress a = killer.getAddress() == null ? null : killer.getAddress().getAddress();
            InetAddress b = victim.getAddress() == null ? null : victim.getAddress().getAddress();
            if (a != null && a.equals(b)) {
                reject(killer, "ip");
                return;
            }
        }
        Long joined = joinTimes.get(victim.getUniqueId());
        long now = System.currentTimeMillis();
        if (s.pvpVictimMinOnlineMs > 0 && joined != null && now - joined < s.pvpVictimMinOnlineMs) {
            reject(killer, "fresh");
            return;
        }
        UUID kId = killer.getUniqueId();
        UUID vId = victim.getUniqueId();
        long amount = s.pvpMin >= s.pvpMax ? s.pvpMin : ThreadLocalRandom.current().nextLong(s.pvpMin, (long) s.pvpMax + 1);
        String eventKey = kId + ":" + vId + ":" + Bukkit.getCurrentTick();
        long cooldown = s.pvpVictimCooldownMs;
        int perVictim = s.pvpMaxPerVictimDay;
        int perDay = s.pvpMaxPerDay;
        String modeForLog = mode;

        plugin.database().tx(c -> {
            long t = System.currentTimeMillis();
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM pvp_log WHERE event_key = ?")) {
                ps.setString(1, eventKey);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new Result("duplicate", 0, 0);
                    }
                }
            }
            if (cooldown > 0) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT COUNT(*) FROM pvp_log WHERE killer = ? AND victim = ? AND at > ?")) {
                    ps.setString(1, kId.toString());
                    ps.setString(2, vId.toString());
                    ps.setLong(3, t - cooldown);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next() && rs.getLong(1) > 0) {
                            return new Result("cooldown", 0, 0);
                        }
                    }
                }
            }
            long day = t - 86_400_000L;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM pvp_log WHERE killer = ? AND victim = ? AND at > ?")) {
                ps.setString(1, kId.toString());
                ps.setString(2, vId.toString());
                ps.setLong(3, day);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getLong(1) >= perVictim) {
                        return new Result("victim-limit", 0, 0);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM pvp_log WHERE killer = ? AND at > ?")) {
                ps.setString(1, kId.toString());
                ps.setLong(2, day);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getLong(1) >= perDay) {
                        return new Result("day-limit", 0, 0);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR IGNORE INTO pvp_log(event_key, killer, victim, mode, tokens, at) VALUES (?,?,?,?,?,?)")) {
                ps.setString(1, eventKey);
                ps.setString(2, kId.toString());
                ps.setString(3, vId.toString());
                ps.setString(4, modeForLog);
                ps.setLong(5, amount);
                ps.setLong(6, t);
                if (ps.executeUpdate() != 1) {
                    return new Result("duplicate", 0, 0);
                }
            }
            TokenService.Credit cr = TokenService.creditInTx(plugin, c, kId, amount, "PVP", true);
            if (!cr.found()) {
                throw new IllegalStateException("профіль убивці не знайдено");
            }
            return new Result("ok", cr.applied(), cr.balance());
        }).whenComplete((r, ex) -> plugin.sync(() -> {
            Player k = Bukkit.getPlayer(kId);
            if (ex != null || r == null) {
                return;
            }
            if (!"ok".equals(r.status())) {
                if (k != null) {
                    reject(k, r.status());
                }
                return;
            }
            plugin.players().refresh(kId);
            if (k != null) {
                plugin.messages().send(k, "pvp.reward", "tokens", Text.num(r.applied()), "victim", victim.getName(),
                        "balance", Text.num(r.balance()));
                plugin.questService().event(k, mode, QuestType.KILL_PLAYER, null, null, 1);
            }
        }));
    }

    private void reject(Player killer, String reason) {
        if (plugin.settings().pvpNotifyRejected) {
            plugin.messages().send(killer, "pvp.rejected." + reason);
        }
        if (plugin.isDebugEnabled()) {
            plugin.getLogger().info("[pvp] нагороду для " + killer.getName() + " відхилено: " + reason);
        }
    }
}
