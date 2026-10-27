package world.lankci.pass.papi;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import world.lankci.pass.LankciPassCore;
import world.lankci.pass.mode.ModeManager;
import world.lankci.pass.quest.Cycle;
import world.lankci.pass.quest.QuestDef;
import world.lankci.pass.quest.QuestKind;
import world.lankci.pass.quest.QuestManager;
import world.lankci.pass.season.LevelDef;
import world.lankci.pass.season.RequirementType;
import world.lankci.pass.season.SeasonDef;
import world.lankci.pass.season.SeasonRun;
import world.lankci.pass.token.PlayerCache;
import world.lankci.pass.token.ShopService;
import world.lankci.pass.util.Text;
import world.lankci.pass.util.TimeUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Плейсхолдери %lankcipass_...%. Читає лише кеші в пам'яті (БД не торкається), тому безпечний для будь-якого потоку.
 * Повний список - у docs/PLACEHOLDERS.md. Невідомий плейсхолдер повертає null (PlaceholderAPI залишає його як є).
 */
public final class LankciPassExpansion extends PlaceholderExpansion {
    private final LankciPassCore plugin;

    public LankciPassExpansion(LankciPassCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "lankcipass";
    }

    @Override
    public String getAuthor() {
        return "LankciWorld";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer off, String params) {
        try {
            String r = resolve(off, params.toLowerCase(Locale.ROOT));
            return r == null ? null : Text.legacy(r);
        } catch (Exception e) {
            plugin.getLogger().warning("Помилка плейсхолдера %lankcipass_" + params + "%: " + e);
            return "";
        }
    }

    // ------------------------------------------------------------------ розбір

    private String resolve(OfflinePlayer off, String p) {
        Player player = off == null ? null : off.getPlayer();
        PlayerCache.Stats st = off == null ? null : plugin.cache().get(off.getUniqueId());
        SeasonRun run = plugin.seasons().activeRun();
        SeasonDef def = plugin.seasons().defOf(run);

        switch (p) {
            case "tokens":
                return Text.num(st == null ? 0 : st.tokens);
            case "tokens_raw":
                return String.valueOf(st == null ? 0 : st.tokens);
            case "points":
                return Text.num(st == null ? 0 : st.points);
            case "points_raw":
                return String.valueOf(st == null ? 0 : st.points);
            case "mode_current":
                {
                String cur = player == null ? null : plugin.modes().modeOf(player.getWorld());
                return cur == null ? "" : cur;
            }
            case "ready":
                return plugin.isReady() ? "yes" : "no";
            default:
                break;
        }

        if (p.startsWith("season_slot_")) {
            return seasonSlot(p.substring("season_slot_".length()));
        }
        if (p.startsWith("season_")) {
            return season(p.substring("season_".length()), run, def);
        }
        if (p.startsWith("history_")) {
            return history(p.substring("history_".length()));
        }
        if (p.startsWith("level_")) {
            return levelField(p.substring("level_".length()), player, st, run, def);
        }
        if (p.startsWith("quest_")) {
            return quest(p.substring("quest_".length()), player, st);
        }
        if (p.startsWith("shop_")) {
            return shop(p.substring("shop_".length()), st);
        }
        switch (p) {
            case "level":
                return def == null ? "0" : String.valueOf(currentLevel(player, st, def));
            case "levels_total":
                return def == null ? "0" : String.valueOf(def.levels().size());
            case "claimed_count":
                return String.valueOf(st == null ? 0 : st.claimed.size());
            case "claimable_count":
                return String.valueOf(claimable(player, st, def));
            case "next_level":
                return nextLevel(player, st, def) == null ? "MAX" : String.valueOf(nextLevel(player, st, def).number());
            case "next_name": {
                LevelDef n = nextLevel(player, st, def);
                return n == null ? "&6Максимум" : n.name();
            }
            case "next_req": {
                LevelDef n = nextLevel(player, st, def);
                return n == null ? "-" : req(n);
            }
            case "progress_percent":
                return String.valueOf(progressPercent(player, st, def));
            case "progress_pct":
                return progressPercent(player, st, def) + "%";
            case "progress_bar":
                return Text.bar(progressPercent(player, st, def), 100, "&a", "&8");
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------ рівні

    private boolean unlocked(Player player, PlayerCache.Stats st, LevelDef l) {
        if (l.requirementType() == RequirementType.PLAYER_LEVEL) {
            return player != null && player.getLevel() >= l.requirementAmount();
        }
        return st != null && st.points >= l.requirementAmount();
    }

    private String state(Player player, PlayerCache.Stats st, SeasonRun run, SeasonDef def, LevelDef l) {
        if (run == null || def == null) {
            return "NONE";
        }
        if (st != null && st.claimed.contains(l.number())) {
            return "CLAIMED";
        }
        return unlocked(player, st, l) ? "AVAILABLE" : "LOCKED";
    }

    private int currentLevel(Player player, PlayerCache.Stats st, SeasonDef def) {
        int cur = 0;
        for (LevelDef l : def.levels()) {
            if (unlocked(player, st, l)) {
                cur = Math.max(cur, l.number());
            }
        }
        return cur;
    }

    private int claimable(Player player, PlayerCache.Stats st, SeasonDef def) {
        if (def == null || st == null) {
            return 0;
        }
        int n = 0;
        for (LevelDef l : def.levels()) {
            if (!st.claimed.contains(l.number()) && unlocked(player, st, l)) {
                n++;
            }
        }
        return n;
    }

    private LevelDef nextLevel(Player player, PlayerCache.Stats st, SeasonDef def) {
        if (def == null) {
            return null;
        }
        for (LevelDef l : def.levels()) {
            if (!unlocked(player, st, l)) {
                return l;
            }
        }
        return null;
    }

    private int progressPercent(Player player, PlayerCache.Stats st, SeasonDef def) {
        LevelDef n = nextLevel(player, st, def);
        if (n == null) {
            return 100;
        }
        long cur = n.requirementType() == RequirementType.PLAYER_LEVEL ? (player == null ? 0 : player.getLevel())
                : (st == null ? 0 : st.points);
        if (n.requirementAmount() <= 0) {
            return 100;
        }
        return (int) Math.max(0, Math.min(100, cur * 100 / n.requirementAmount()));
    }

    private static String req(LevelDef l) {
        return l.requirementType() == RequirementType.PLAYER_LEVEL
                ? l.requirementAmount() + " LVL" : Text.num(l.requirementAmount()) + " очок";
    }

    private String levelField(String rest, Player player, PlayerCache.Stats st, SeasonRun run, SeasonDef def) {
        int us = rest.indexOf('_');
        if (us < 0) {
            return null;
        }
        int n;
        try {
            n = Integer.parseInt(rest.substring(0, us));
        } catch (NumberFormatException e) {
            return null;
        }
        String f = rest.substring(us + 1);
        LevelDef l = def == null ? null : def.level(n);
        if (f.equals("exists")) {
            return l == null ? "no" : "yes";
        }
        if (l == null) {
            return f.equals("state") ? "NONE" : "";
        }
        switch (f) {
            case "state":
                return state(player, st, run, def, l);
            case "name":
                return l.name();
            case "icon":
                return l.icon().name();
            case "req":
                return req(l);
            case "need": {
                long cur = l.requirementType() == RequirementType.PLAYER_LEVEL ? (player == null ? 0 : player.getLevel())
                        : (st == null ? 0 : st.points);
                long left = Math.max(0, l.requirementAmount() - cur);
                return l.requirementType() == RequirementType.PLAYER_LEVEL ? left + " LVL" : Text.num(left) + " очок";
            }
            default:
                break;
        }
        if (f.startsWith("lore")) {
            try {
                int i = Integer.parseInt(f.substring(4));
                List<String> d = l.description();
                return i >= 1 && i <= d.size() ? d.get(i - 1) : "";
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ сезони

    private String season(String f, SeasonRun run, SeasonDef def) {
        long left = plugin.seasons().remainingMs();
        String color = TimeUtil.timerColor(left, plugin.settings());
        switch (f) {
            case "state":
                return run == null ? "NONE" : "ACTIVE";
            case "id":
                return run == null ? "" : run.seasonId();
            case "run":
                return run == null ? "" : String.valueOf(run.runId());
            case "number":
                return def == null ? "" : String.valueOf(def.number());
            case "name":
                return def == null ? "&7Немає активного сезону" : def.coloredName();
            case "name_plain":
                return def == null ? "Немає активного сезону" : Text.plain(def.name());
            case "theme":
                return def == null ? "" : def.theme();
            case "color":
                return def == null ? "&7" : def.color();
            case "levels":
                return def == null ? "0" : String.valueOf(def.levels().size());
            case "start":
                return run == null ? "-" : plugin.seasons().fmt(run.startAt());
            case "end":
                return run == null ? "-" : plugin.seasons().fmt(run.endAt());
            case "timer":
                return run == null ? "&7-" : color + TimeUtil.duration(left);
            case "timer_plain":
                return run == null ? "-" : TimeUtil.duration(left);
            case "timer_color":
                return color;
            case "days_left":
                return run == null ? "0" : String.valueOf(TimeUtil.fullDays(left));
            default:
                return null;
        }
    }

    private List<SeasonRun> slotRuns() {
        List<SeasonRun> l = new ArrayList<>();
        SeasonRun a = plugin.seasons().activeRun();
        if (a != null) {
            l.add(a);
        }
        l.addAll(plugin.seasons().scheduledRuns());
        return l;
    }

    private String seasonSlot(String rest) {
        int us = rest.indexOf('_');
        if (us < 0) {
            return null;
        }
        int i;
        try {
            i = Integer.parseInt(rest.substring(0, us));
        } catch (NumberFormatException e) {
            return null;
        }
        String f = rest.substring(us + 1);
        List<SeasonRun> runs = slotRuns();
        SeasonRun r = i >= 1 && i <= runs.size() ? runs.get(i - 1) : null;
        if (f.equals("exists")) {
            return r == null ? "no" : "yes";
        }
        if (r == null) {
            return "";
        }
        SeasonDef d = plugin.seasons().defOf(r);
        long now = System.currentTimeMillis();
        switch (f) {
            case "id":
                return r.seasonId();
            case "name":
                return d == null ? r.seasonId() : d.coloredName();
            case "icon":
                return d == null ? "NETHER_STAR" : d.icon().name();
            case "theme":
                return d == null ? "" : d.theme();
            case "number":
                return d == null ? "" : String.valueOf(d.number());
            case "levels":
                return d == null ? "0" : String.valueOf(d.levels().size());
            case "state":
                return r.status() == world.lankci.pass.season.RunStatus.ACTIVE ? "ACTIVE" : "SCHEDULED";
            case "statusline":
                return r.status() == world.lankci.pass.season.RunStatus.ACTIVE ? "&a&lЗАРАЗ ТРИВАЄ" : "&e&lСКОРО";
            case "start":
                return plugin.seasons().fmt(r.startAt());
            case "end":
                return plugin.seasons().fmt(r.endAt());
            case "days":
                return String.valueOf(r.durationDays());
            case "timer":
                if (r.status() == world.lankci.pass.season.RunStatus.ACTIVE) {
                    long left = r.remaining(now);
                    return TimeUtil.timerColor(left, plugin.settings()) + "Залишилось: " + TimeUtil.duration(left);
                }
                return "&7Почнеться через: &f" + TimeUtil.duration(r.startAt() - now);
            default:
                return null;
        }
    }

    private String history(String rest) {
        int us = rest.indexOf('_');
        if (us < 0) {
            return null;
        }
        int i;
        try {
            i = Integer.parseInt(rest.substring(0, us));
        } catch (NumberFormatException e) {
            return null;
        }
        String f = rest.substring(us + 1);
        List<SeasonRun> ended = plugin.seasons().endedRuns();
        SeasonRun r = i >= 1 && i <= ended.size() ? ended.get(i - 1) : null;
        if (f.equals("exists")) {
            return r == null ? "no" : "yes";
        }
        if (r == null) {
            return "";
        }
        SeasonDef d = plugin.seasons().defOf(r);
        switch (f) {
            case "name":
                return d == null ? r.seasonId() : d.coloredName();
            case "icon":
                return d == null ? "NETHER_STAR" : d.icon().name();
            case "ended":
                return plugin.seasons().fmt(r.endedAt());
            case "reason":
                return "EXPIRED".equals(r.endReason()) ? "завершився" : "зупинено адміністратором";
            case "run":
                return String.valueOf(r.runId());
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------ квести

    /** Режим, який зараз переглядає гравець (вкладки меню) або режим його світу. */
    private String viewMode(Player player) {
        return player == null ? defaultMode() : plugin.viewMode(player);
    }

    private String defaultMode() {
        var it = plugin.modes().all().iterator();
        return it.hasNext() ? it.next().id() : null;
    }

    private String quest(String rest, Player player, PlayerCache.Stats st) {
        String mode = viewMode(player);
        if (rest.equals("mode")) {
            return mode == null ? "" : mode;
        }
        if (rest.equals("mode_name")) {
            ModeManager.Mode m = plugin.modes().get(mode);
            return m == null ? "-" : m.display();
        }
        if (rest.startsWith("modetab_")) {
            // quest_modetab_<mode>_active | _name
            String r = rest.substring("modetab_".length());
            int us = r.lastIndexOf('_');
            if (us < 0) {
                return null;
            }
            ModeManager.Mode m = plugin.modes().get(r.substring(0, us));
            String f = r.substring(us + 1);
            if (m == null) {
                return f.equals("active") ? "no" : "";
            }
            return switch (f) {
                case "active" -> m.id().equals(mode) ? "yes" : "no";
                case "name" -> m.display();
                case "icon" -> m.icon().name();
                default -> null;
            };
        }
        String[] parts = rest.split("_", 3);
        QuestKind kind = QuestKind.parse(parts[0]);
        if (kind == null || parts.length < 2) {
            return null;
        }
        Cycle cycle = plugin.quests().current(mode, kind);
        List<QuestDef> list = plugin.quests().questsOf(cycle);
        long now = System.currentTimeMillis();
        // поля без номера: timer, count, done, overall, overall_pct, overall_bar (містять "_", тому беремо хвіст рядка)
        switch (rest.substring(parts[0].length() + 1)) {
            case "timer":
                return cycle == null ? "&7-" : "&b" + TimeUtil.duration(cycle.remaining(now));
            case "count":
                return String.valueOf(list.size());
            case "done":
                return String.valueOf(doneCount(st, cycle, list));
            case "overall":
                return doneCount(st, cycle, list) + "/" + list.size();
            case "overall_pct":
                return (list.isEmpty() ? 0 : doneCount(st, cycle, list) * 100 / list.size()) + "%";
            case "overall_bar":
                return Text.bar(doneCount(st, cycle, list), Math.max(1, list.size()), "&d", "&8");
            default:
                break;
        }
        int idx;
        try {
            idx = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (parts.length < 3) {
            return null;
        }
        String f = parts[2];
        QuestDef q = idx >= 1 && idx <= list.size() ? list.get(idx - 1) : null;
        if (f.equals("exists")) {
            return q == null ? "no" : "yes";
        }
        if (q == null) {
            return f.equals("state") ? "EMPTY" : "";
        }
        PlayerCache.QuestProg qp = prog(st, cycle, q);
        long cur = qp == null ? 0 : qp.progress();
        boolean completed = qp != null && qp.completed();
        boolean claimed = qp != null && qp.claimed();
        if (completed) {
            cur = q.amount();
        }
        QuestManager.Difficulty diff = plugin.quests().difficulty(q.difficulty());
        switch (f) {
            case "name":
                return q.name();
            case "difficulty":
                return diff == null ? q.difficulty() : diff.coloredName();
            case "icon":
                return diff == null ? "PAPER" : diff.icon().name();
            case "reward":
                return Text.num(q.reward());
            case "progress":
                return Text.num(cur) + "/" + Text.num(q.amount());
            case "cur":
                return Text.num(cur);
            case "target":
                return Text.num(q.amount());
            case "percent":
                return String.valueOf(q.amount() <= 0 ? 100 : Math.min(100, cur * 100 / q.amount()));
            case "pct":
                return (q.amount() <= 0 ? 100 : Math.min(100, cur * 100 / q.amount())) + "%";
            case "bar":
                return Text.bar(cur, q.amount(), completed ? "&a" : "&b", "&8");
            case "state":
                return claimed ? "CLAIMED" : completed ? "READY" : "ACTIVE";
            case "statusline":
                return claimed ? "&a✔ Нагороду отримано" : completed ? "&e▶ Натисніть, щоб забрати нагороду" : "&7У процесі виконання";
            case "desc1":
                return q.description().size() >= 1 ? q.description().get(0) : "";
            case "desc2":
                return q.description().size() >= 2 ? q.description().get(1) : "";
            default:
                return null;
        }
    }

    private static int doneCount(PlayerCache.Stats st, Cycle cycle, List<QuestDef> list) {
        int n = 0;
        for (QuestDef q : list) {
            PlayerCache.QuestProg qp = prog(st, cycle, q);
            if (qp != null && qp.completed()) {
                n++;
            }
        }
        return n;
    }

    private static PlayerCache.QuestProg prog(PlayerCache.Stats st, Cycle cycle, QuestDef q) {
        return st == null || cycle == null ? null : st.quests.get(cycle.id() + ":" + q.id());
    }

    // ------------------------------------------------------------------ магазин

    private String shop(String rest, PlayerCache.Stats st) {
        int us = rest.indexOf('_');
        if (us < 0) {
            return null;
        }
        int i;
        try {
            i = Integer.parseInt(rest.substring(0, us));
        } catch (NumberFormatException e) {
            return null;
        }
        String f = rest.substring(us + 1);
        ShopService.Item it = plugin.shop().get(i);
        if (f.equals("exists")) {
            return it == null ? "no" : "yes";
        }
        if (it == null) {
            return "";
        }
        switch (f) {
            case "name":
                return it.name();
            case "icon":
                return it.icon().name();
            case "price":
                return Text.num(it.price());
            case "canbuy":
                return st != null && st.tokens >= it.price() ? "yes" : "no";
            default:
                break;
        }
        if (f.startsWith("lore")) {
            try {
                int n = Integer.parseInt(f.substring(4));
                return n >= 1 && n <= it.lore().size() ? it.lore().get(n - 1) : "";
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
