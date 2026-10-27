package world.lankci.pass.quest;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import world.lankci.pass.LankciPassCore;
import world.lankci.pass.db.Database;
import world.lankci.pass.token.TokenService;
import world.lankci.pass.util.Text;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Прогрес квестів: події накопичуються в пам'яті (лише головний потік) і пачками записуються в SQLite.
 * Виконання квесту й видача нагороди - одна транзакція, тому перезапуск чи збій не дублюють і не гублять нагороду.
 */
public final class QuestService {
    private static final class Pending {
        final UUID uuid;
        final Cycle cycle;
        final QuestDef quest;
        long amount;
        final Set<String> uniques = new HashSet<>();

        Pending(UUID uuid, Cycle cycle, QuestDef quest) {
            this.uuid = uuid;
            this.cycle = cycle;
            this.quest = quest;
        }
    }

    private record Completion(UUID uuid, Cycle cycle, QuestDef quest, boolean claimedNow, long applied, long balance) {
    }

    /** Результат ручного отримання нагороди. */
    public record ClaimResult(String status, QuestDef quest, long applied, long balance) {
    }

    private final LankciPassCore plugin;
    private Map<String, Pending> buffer = new LinkedHashMap<>();

    public QuestService(LankciPassCore plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ збір подій (головний потік)

    /** Подія гравця у світі з режимом mode. ent/mat - необов'язкові (залежно від типу). */
    public void event(Player player, String mode, QuestType type, EntityType ent, Material mat, long amount) {
        if (mode == null || amount <= 0 || type == QuestType.COMPLETE_QUESTS) {
            return;
        }
        for (QuestKind kind : QuestKind.values()) {
            Cycle cycle = plugin.quests().current(mode, kind);
            if (cycle == null) {
                continue;
            }
            for (QuestDef q : plugin.quests().questsOf(cycle)) {
                if (q.type() != type) {
                    continue;
                }
                if ((type == QuestType.KILL_MOB || type == QuestType.KILL_UNIQUE_MOB) && ent != null && !q.matchesEntity(ent)) {
                    continue;
                }
                if ((type == QuestType.BREAK_BLOCK || type == QuestType.CRAFT_ITEM || type == QuestType.FISH)
                        && mat != null && !q.matchesMaterial(mat)) {
                    continue;
                }
                record(player.getUniqueId(), cycle, q, amount, type == QuestType.KILL_UNIQUE_MOB && ent != null ? ent.name() : null);
            }
        }
    }

    private void record(UUID uuid, Cycle cycle, QuestDef q, long amount, String unique) {
        // не накопичуємо те, що вже виконано (за кешем), щоб не навантажувати БД
        var st = plugin.cache().get(uuid);
        if (st != null) {
            var qp = st.quests.get(cycle.id() + ":" + q.id());
            if (qp != null && qp.completed()) {
                return;
            }
        }
        String key = uuid + "|" + cycle.id() + "|" + q.id();
        Pending p = buffer.computeIfAbsent(key, k -> new Pending(uuid, cycle, q));
        p.amount += amount;
        if (unique != null) {
            p.uniques.add(unique);
        }
    }

    public int pendingCount() {
        return buffer.size();
    }

    // ------------------------------------------------------------------ запис у БД

    /** Скидає буфер у базу (асинхронно). Повертає future завершення запису. */
    public CompletableFuture<Void> flush() {
        if (buffer.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        Map<String, Pending> snapshot = buffer;
        buffer = new LinkedHashMap<>();
        boolean auto = plugin.settings().autoClaim;
        return plugin.database().async(c -> {
            List<Completion> done = new ArrayList<>();
            Database.inTx(c, cc -> {
                for (Pending p : snapshot.values()) {
                    Completion comp = apply(cc, p, auto);
                    if (comp != null) {
                        done.add(comp);
                    }
                }
                return null;
            });
            return done;
        }).thenAccept(done -> plugin.sync(() -> afterFlush(snapshot, done)));
    }

    /** Блокуючий запис під час вимкнення сервера. */
    public void flushBlocking() {
        try {
            flush().get(20, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().warning("Не вдалося записати прогрес квестів при вимкненні: " + e.getMessage());
        }
    }

    private Completion apply(Connection c, Pending p, boolean auto) throws SQLException {
        QuestDef q = p.quest;
        long progress = 0;
        boolean completed = false;
        boolean claimed = false;
        String extra = null;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT progress, completed, claimed, extra FROM quest_progress WHERE uuid = ? AND cycle_id = ? AND quest_id = ?")) {
            ps.setString(1, p.uuid.toString());
            ps.setLong(2, p.cycle.id());
            ps.setString(3, q.id());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    progress = rs.getLong(1);
                    completed = rs.getInt(2) == 1;
                    claimed = rs.getInt(3) == 1;
                    extra = rs.getString(4);
                }
            }
        }
        if (completed) {
            return null;
        }
        if (q.type() == QuestType.KILL_UNIQUE_MOB) {
            Set<String> set = new HashSet<>();
            if (extra != null && !extra.isBlank()) {
                set.addAll(List.of(extra.split(",")));
            }
            set.addAll(p.uniques);
            progress = Math.min(q.amount(), set.size());
            extra = String.join(",", set);
        } else {
            progress = Math.min(q.amount(), progress + p.amount);
        }
        boolean nowDone = progress >= q.amount();
        long now = System.currentTimeMillis();
        boolean claimNow = nowDone && auto && p.cycle.endAt() > now;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO quest_progress(uuid, cycle_id, quest_id, progress, completed, claimed, completed_at, claimed_at, extra) "
                        + "VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT(uuid, cycle_id, quest_id) DO UPDATE SET "
                        + "progress = excluded.progress, completed = excluded.completed, claimed = excluded.claimed, "
                        + "completed_at = excluded.completed_at, claimed_at = excluded.claimed_at, extra = excluded.extra")) {
            ps.setString(1, p.uuid.toString());
            ps.setLong(2, p.cycle.id());
            ps.setString(3, q.id());
            ps.setLong(4, progress);
            ps.setInt(5, nowDone ? 1 : 0);
            ps.setInt(6, claimNow ? 1 : 0);
            if (nowDone) {
                ps.setLong(7, now);
            } else {
                ps.setNull(7, java.sql.Types.INTEGER);
            }
            if (claimNow) {
                ps.setLong(8, now);
            } else {
                ps.setNull(8, java.sql.Types.INTEGER);
            }
            ps.setString(9, extra);
            ps.executeUpdate();
        }
        if (!nowDone) {
            return null;
        }
        long applied = 0;
        long balance = 0;
        if (claimNow) {
            TokenService.Credit cr = TokenService.creditInTx(plugin, c, p.uuid, q.reward(),
                    "QUEST:" + q.kind().key() + ":" + q.id(), true);
            applied = cr.applied();
            balance = cr.balance();
        }
        return new Completion(p.uuid, p.cycle, q, claimNow, applied, balance);
    }

    private void afterFlush(Map<String, Pending> snapshot, List<Completion> done) {
        Set<UUID> touched = new HashSet<>();
        for (Pending p : snapshot.values()) {
            touched.add(p.uuid);
        }
        for (Completion c : done) {
            touched.add(c.uuid());
            Player pl = plugin.getServer().getPlayer(c.uuid());
            if (pl != null) {
                var diff = plugin.quests().difficulty(c.quest().difficulty());
                plugin.messages().send(pl, "quest.completed", "name", c.quest().name(),
                        "difficulty", diff == null ? c.quest().difficulty() : diff.coloredName(),
                        "kind", plugin.messages().get("kind." + c.quest().kind().key()));
                if (c.claimedNow()) {
                    plugin.messages().send(pl, "quest.reward-received", "tokens", Text.num(c.applied()),
                            "balance", Text.num(c.balance()));
                    runBonus(pl, c.quest());
                } else {
                    plugin.messages().send(pl, "quest.ready-to-claim");
                }
                if (plugin.settings().sounds) {
                    pl.playSound(pl.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f);
                }
            }
            // ланцюжок: виконаний звичайний квест рухає мега-квести «виконай N квестів» того ж режиму
            if (c.quest().kind() == QuestKind.DAILY && pl != null) {
                Cycle mega = plugin.quests().current(c.cycle().mode(), QuestKind.MEGA);
                if (mega != null) {
                    for (QuestDef q : plugin.quests().questsOf(mega)) {
                        if (q.type() == QuestType.COMPLETE_QUESTS) {
                            record(c.uuid(), mega, q, 1, null);
                        }
                    }
                }
            }
        }
        for (UUID u : touched) {
            plugin.players().refresh(u);
        }
    }

    private void runBonus(Player pl, QuestDef q) {
        if (!q.bonusCommands().isEmpty()) {
            plugin.rewards().runCommands(pl, q.bonusCommands(), Map.of("quest", q.id(), "mode", q.mode()),
                    "quest " + q.mode() + ":" + q.id());
        }
    }

    // ------------------------------------------------------------------ ручне отримання нагороди

    /** Отримати нагороду за квест (використовується, якщо quests.auto-claim=false). Атомарно й лише раз. */
    public CompletableFuture<ClaimResult> claim(UUID uuid, String mode, QuestKind kind, int index) {
        Cycle cycle = plugin.quests().current(mode, kind);
        if (cycle == null) {
            return CompletableFuture.completedFuture(new ClaimResult("NO_CYCLE", null, 0, 0));
        }
        List<QuestDef> list = plugin.quests().questsOf(cycle);
        if (index < 1 || index > list.size()) {
            return CompletableFuture.completedFuture(new ClaimResult("NO_QUEST", null, 0, 0));
        }
        QuestDef q = list.get(index - 1);
        return plugin.database().tx(c -> {
            long now = System.currentTimeMillis();
            if (cycle.endAt() <= now) {
                return new ClaimResult("EXPIRED", q, 0, 0);
            }
            int rows;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE quest_progress SET claimed = 1, claimed_at = ? "
                            + "WHERE uuid = ? AND cycle_id = ? AND quest_id = ? AND completed = 1 AND claimed = 0")) {
                ps.setLong(1, now);
                ps.setString(2, uuid.toString());
                ps.setLong(3, cycle.id());
                ps.setString(4, q.id());
                rows = ps.executeUpdate();
            }
            if (rows != 1) {
                boolean completed = false;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT completed FROM quest_progress WHERE uuid = ? AND cycle_id = ? AND quest_id = ?")) {
                    ps.setString(1, uuid.toString());
                    ps.setLong(2, cycle.id());
                    ps.setString(3, q.id());
                    try (ResultSet rs = ps.executeQuery()) {
                        completed = rs.next() && rs.getInt(1) == 1;
                    }
                }
                return new ClaimResult(completed ? "ALREADY" : "NOT_READY", q, 0, 0);
            }
            TokenService.Credit cr = TokenService.creditInTx(plugin, c, uuid, q.reward(),
                    "QUEST:" + q.kind().key() + ":" + q.id(), true);
            return new ClaimResult("OK", q, cr.applied(), cr.balance());
        }).whenComplete((r, ex) -> plugin.sync(() -> {
            plugin.players().refresh(uuid);
            if (r != null && "OK".equals(r.status())) {
                Player pl = plugin.getServer().getPlayer(uuid);
                if (pl != null) {
                    runBonus(pl, r.quest());
                }
            }
        }));
    }

    // ------------------------------------------------------------------ адміністрування

    /** Скинути прогрес гравця в поточних циклах (усі режими або один). Повертає кількість видалених записів. */
    public CompletableFuture<Integer> resetPlayer(UUID uuid, String mode) {
        buffer.entrySet().removeIf(e -> e.getValue().uuid.equals(uuid) && (mode == null || e.getValue().cycle.mode().equals(mode)));
        List<Long> ids = new ArrayList<>();
        for (Cycle c : plugin.quests().allCurrent()) {
            if (mode == null || c.mode().equals(mode)) {
                ids.add(c.id());
            }
        }
        if (ids.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        return plugin.database().tx(c -> {
            plugin.database().backup(c, "pre-quest-reset");
            int total = 0;
            for (long id : ids) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM quest_progress WHERE uuid = ? AND cycle_id = ?")) {
                    ps.setString(1, uuid.toString());
                    ps.setLong(2, id);
                    total += ps.executeUpdate();
                }
            }
            return total;
        }).whenComplete((n, ex) -> plugin.sync(() -> plugin.players().refresh(uuid)));
    }

    /** Завантажує прогрес гравця по поточних циклах (виклик у потоці БД). */
    public Map<String, world.lankci.pass.token.PlayerCache.QuestProg> loadProgress(Connection c, UUID uuid) throws SQLException {
        Map<String, world.lankci.pass.token.PlayerCache.QuestProg> out = new HashMap<>();
        for (Cycle cy : plugin.quests().allCurrent()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT quest_id, progress, completed, claimed FROM quest_progress WHERE uuid = ? AND cycle_id = ?")) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, cy.id());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(cy.id() + ":" + rs.getString(1), new world.lankci.pass.token.PlayerCache.QuestProg(
                                rs.getLong(2), rs.getInt(3) == 1, rs.getInt(4) == 1));
                    }
                }
            }
        }
        return out;
    }
}
