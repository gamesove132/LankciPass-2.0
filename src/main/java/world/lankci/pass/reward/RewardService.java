package world.lankci.pass.reward;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import world.lankci.pass.LankciPassCore;
import world.lankci.pass.season.LevelDef;
import world.lankci.pass.season.RequirementType;
import world.lankci.pass.season.RunStatus;
import world.lankci.pass.season.SeasonDef;
import world.lankci.pass.season.SeasonRun;
import world.lankci.pass.token.TokenService;
import world.lankci.pass.util.Text;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/** Отримання нагород рівнів Battle Pass і безпечний запуск команд-нагород. */
public final class RewardService {
    public record ClaimResult(String status, LevelDef level, String detail) {
        public boolean ok() {
            return "OK".equals(status);
        }
    }

    private final LankciPassCore plugin;

    public RewardService(LankciPassCore plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ стан рівнів (SQL)

    /** Рівні, нагороди за які вже отримано (для політики LOCK_PREVIOUS - також у попередніх запусках сезону). */
    public static Set<Integer> loadClaimed(Connection c, SeasonRun run, UUID uuid) throws SQLException {
        Set<Integer> out = new HashSet<>();
        String sql = "LOCK_PREVIOUS".equals(run.claimPolicy())
                ? "SELECT DISTINCT level FROM reward_claims WHERE uuid = ? AND (run_id = ? OR season_id = ?)"
                : "SELECT DISTINCT level FROM reward_claims WHERE uuid = ? AND run_id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, run.runId());
            if ("LOCK_PREVIOUS".equals(run.claimPolicy())) {
                ps.setString(3, run.seasonId());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getInt(1));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ отримання нагороди рівня

    /** Головний потік. Атомарно: перевірка + запис отримання в одній транзакції на єдиному потоці БД. */
    public CompletableFuture<ClaimResult> claim(Player player, int levelNumber) {
        SeasonRun run = plugin.seasons().activeRun();
        long now = System.currentTimeMillis();
        if (run == null || run.status() != RunStatus.ACTIVE || now >= run.endAt()) {
            return done("NO_SEASON", null, null);
        }
        SeasonDef def = plugin.seasons().defOf(run);
        if (def == null) {
            return done("NO_SEASON", null, null);
        }
        LevelDef lvl = def.level(levelNumber);
        if (lvl == null) {
            return done("NO_LEVEL", null, null);
        }
        if (lvl.requirementType() == RequirementType.PLAYER_LEVEL && player.getLevel() < lvl.requirementAmount()) {
            return done("LOCKED", lvl, null);
        }
        if (plugin.settings().precheckCommands) {
            String bad = firstUnknownCommand(lvl.commands());
            if (bad != null) {
                plugin.getLogger().severe("Нагорода рівня " + levelNumber + " сезону " + def.id()
                        + " містить невідому команду '" + bad + "'. Нагороду не видано, гравець " + player.getName() + " може спробувати знову.");
                return done("BAD_COMMAND", lvl, bad);
            }
        }
        UUID uuid = player.getUniqueId();
        final long runId = run.runId();
        final String seasonId = run.seasonId();
        final boolean pointsReq = lvl.requirementType() == RequirementType.POINTS;
        CompletableFuture<ClaimResult> f = plugin.database().tx(c -> {
            long t = System.currentTimeMillis();
            try (PreparedStatement ps = c.prepareStatement("SELECT status, end_at FROM season_runs WHERE run_id = ?")) {
                ps.setLong(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || !"ACTIVE".equals(rs.getString(1)) || rs.getLong(2) <= t) {
                        return new ClaimResult("NO_SEASON", lvl, null);
                    }
                }
            }
            boolean known;
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM players WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    known = rs.next();
                }
            }
            if (!known) {
                return new ClaimResult("NO_PROFILE", lvl, null);
            }
            if (loadClaimed(c, run, uuid).contains(levelNumber)) {
                return new ClaimResult("ALREADY", lvl, null);
            }
            if (pointsReq && TokenService.readPoints(c, runId, uuid) < lvl.requirementAmount()) {
                return new ClaimResult("LOCKED", lvl, null);
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR IGNORE INTO reward_claims(run_id, uuid, level, season_id, claimed_at, source) VALUES (?,?,?,?,?, 'GUI')")) {
                ps.setLong(1, runId);
                ps.setString(2, uuid.toString());
                ps.setInt(3, levelNumber);
                ps.setString(4, seasonId);
                ps.setLong(5, t);
                if (ps.executeUpdate() != 1) {
                    return new ClaimResult("ALREADY", lvl, null);
                }
            }
            return new ClaimResult("OK", lvl, null);
        });
        CompletableFuture<ClaimResult> out = new CompletableFuture<>();
        f.whenComplete((r, ex) -> plugin.sync(() -> {
            if (ex != null || r == null) {
                out.complete(new ClaimResult("ERROR", lvl, ex == null ? null : ex.getMessage()));
                return;
            }
            if (!r.ok()) {
                out.complete(r);
                return;
            }
            Player online = Bukkit.getPlayer(uuid);
            if (online == null) {
                // гравець вийшов між перевіркою й видачею: скасовуємо запис, щоб нагорода не пропала
                plugin.database().tx(c -> {
                    try (PreparedStatement ps = c.prepareStatement(
                            "DELETE FROM reward_claims WHERE run_id = ? AND uuid = ? AND level = ?")) {
                        ps.setLong(1, runId);
                        ps.setString(2, uuid.toString());
                        ps.setInt(3, levelNumber);
                        ps.executeUpdate();
                    }
                    return null;
                });
                out.complete(new ClaimResult("OFFLINE", lvl, null));
                return;
            }
            int ok = runCommands(online, lvl.commands(), Map.of("level", String.valueOf(levelNumber), "season", seasonId),
                    "level " + levelNumber + " of " + seasonId);
            if (ok < lvl.commands().size()) {
                plugin.getLogger().severe("Нагорода рівня " + levelNumber + " сезону " + seasonId + " для " + online.getName()
                        + ": виконано " + ok + " з " + lvl.commands().size() + " команд. Запис про отримання збережено (щоб уникнути дублювання).");
            }
            plugin.players().refresh(uuid);
            out.complete(r);
        }));
        return out;
    }

    private static CompletableFuture<ClaimResult> done(String status, LevelDef lvl, String detail) {
        return CompletableFuture.completedFuture(new ClaimResult(status, lvl, detail));
    }

    /** Показує гравцеві результат claim. */
    public void report(Player p, ClaimResult r) {
        String lvlName = r.level() == null ? "?" : r.level().name();
        switch (r.status()) {
            case "OK" -> {
                plugin.messages().send(p, "reward.claimed", "level", r.level().number(), "name", lvlName);
                if (r.level().message() != null && !r.level().message().isBlank()) {
                    p.sendMessage(Text.c(r.level().message().replace("{player}", p.getName())));
                }
                if (plugin.settings().sounds) {
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
                }
            }
            case "ALREADY" -> plugin.messages().send(p, "reward.already", "level", r.level().number());
            case "LOCKED" -> plugin.messages().send(p, "reward.locked", "level", r.level().number());
            case "NO_SEASON" -> plugin.messages().send(p, "reward.no-season");
            case "NO_LEVEL" -> plugin.messages().send(p, "reward.no-level");
            case "NO_PROFILE" -> plugin.messages().send(p, "reward.no-profile");
            case "BAD_COMMAND" -> plugin.messages().send(p, "reward.bad-config");
            case "OFFLINE" -> { /* гравець вийшов - повідомляти нікому */ }
            default -> plugin.messages().send(p, "reward.error");
        }
    }

    // ------------------------------------------------------------------ команди нагород

    /**
     * Виконує команди від консолі з підстановкою {player}, {uuid} та додаткових ключів.
     * Підтримує сумісність зі старим синтаксисом: префікс "[console] " та %player_name%.
     * @return кількість успішно виконаних команд
     */
    public int runCommands(Player player, List<String> commands, Map<String, String> extra, String context) {
        int ok = 0;
        for (String raw : commands) {
            String cmd = raw == null ? "" : raw.trim();
            if (cmd.regionMatches(true, 0, "[console]", 0, 9)) {
                cmd = cmd.substring(9).trim();
            }
            if (cmd.startsWith("/")) {
                cmd = cmd.substring(1);
            }
            if (cmd.isEmpty()) {
                continue;
            }
            cmd = cmd.replace("{player}", player.getName()).replace("%player_name%", player.getName())
                    .replace("{uuid}", player.getUniqueId().toString());
            for (var e : extra.entrySet()) {
                cmd = cmd.replace("{" + e.getKey() + "}", e.getValue());
            }
            try {
                if (Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)) {
                    ok++;
                } else {
                    plugin.getLogger().warning("Команда нагороди не виконана (" + context + "): " + cmd);
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Помилка команди нагороди (" + context + "): " + cmd, e);
            }
        }
        return ok;
    }

    /** Перша команда з невідомою міткою або null. Мітки з rewards.trusted-command-labels пропускаються. */
    public String firstUnknownCommand(List<String> commands) {
        for (String raw : commands) {
            String cmd = raw == null ? "" : raw.trim();
            if (cmd.regionMatches(true, 0, "[console]", 0, 9)) {
                cmd = cmd.substring(9).trim();
            }
            if (cmd.startsWith("/")) {
                cmd = cmd.substring(1);
            }
            if (cmd.isEmpty()) {
                return "<порожня команда>";
            }
            String label = cmd.split("\\s+")[0].toLowerCase(Locale.ROOT);
            if (plugin.settings().vanillaLabels.contains(label)) {
                continue;
            }
            Command found = Bukkit.getCommandMap().getCommand(label);
            if (found == null) {
                return cmd;
            }
        }
        return null;
    }

    /** Перевіряє всі команди всіх сезонів і магазину; повертає список проблем (для reload/debug). */
    public List<String> validateConfig() {
        List<String> problems = new ArrayList<>();
        for (SeasonDef d : plugin.seasons().allDefs()) {
            for (LevelDef l : d.levels()) {
                String bad = firstUnknownCommand(l.commands());
                if (bad != null) {
                    problems.add("seasons.yml: " + d.id() + " рівень " + l.number() + " - невідома команда: " + bad);
                }
            }
        }
        for (String s : plugin.shop().validate()) {
            problems.add(s);
        }
        return problems;
    }
}
