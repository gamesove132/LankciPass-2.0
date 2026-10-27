package world.lankci.pass.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import world.lankci.pass.LankciPassCore;
import world.lankci.pass.mode.ModeManager;
import world.lankci.pass.quest.QuestKind;
import world.lankci.pass.season.RunStatus;
import world.lankci.pass.season.SeasonDef;
import world.lankci.pass.season.SeasonRun;
import world.lankci.pass.util.Text;
import world.lankci.pass.util.TimeUtil;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** /lankcipass і всі підкоманди (гравці й адміністратори). */
public final class LankciPassCommand implements CommandExecutor, TabCompleter {
    private static final List<String> MENUS = List.of("main", "quests", "mega", "seasons", "shop");

    private final LankciPassCore plugin;

    public LankciPassCommand(LankciPassCore plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ допоміжне

    private boolean perm(CommandSender s, String node) {
        if (s.hasPermission(node) || s.hasPermission("lankcipass.admin")) {
            return true;
        }
        plugin.messages().send(s, "common.no-permission");
        return false;
    }

    private Player playerOnly(CommandSender s) {
        if (s instanceof Player p) {
            return p;
        }
        plugin.messages().send(s, "common.players-only");
        return null;
    }

    private static Long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Шукає UUID гравця: онлайн за ніком або в базі (за збереженим ніком). */
    private CompletableFuture<UUID> resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return CompletableFuture.completedFuture(online.getUniqueId());
        }
        return plugin.database().async(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid FROM players WHERE lower(name) = lower(?) LIMIT 1")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? UUID.fromString(rs.getString(1)) : null;
                }
            }
        });
    }

    private void withPlayer(CommandSender sender, String name, java.util.function.Consumer<UUID> then) {
        resolve(name).whenComplete((id, ex) -> plugin.sync(() -> {
            if (ex != null) {
                plugin.messages().send(sender, "common.error");
            } else if (id == null) {
                plugin.messages().send(sender, "common.player-not-found", "name", name);
            } else {
                then.accept(id);
            }
        }));
    }

    // ------------------------------------------------------------------ виконання

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!plugin.isReady()) {
            plugin.messages().send(sender, "common.not-ready");
            return true;
        }
        try {
            dispatch(sender, args);
        } catch (Exception e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Помилка команди /" + label + " " + String.join(" ", args), e);
            plugin.messages().send(sender, "common.error");
        }
        return true;
    }

    private void dispatch(CommandSender sender, String[] args) {
        if (args.length == 0) {
            Player p = playerOnly(sender);
            if (p != null && perm(p, "lankcipass.use")) {
                plugin.openMenu(p, "main");
            }
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help", "?" -> help(sender);
            case "menu", "open" -> menu(sender, args);
            case "balance", "bal" -> balance(sender);
            case "claim" -> claim(sender, args);
            case "quest", "quests" -> quest(sender, args);
            case "view" -> view(sender, args);
            case "shop" -> shop(sender, args);
            case "tokens" -> tokens(sender, args);
            case "season", "seasons" -> season(sender, args);
            case "reload" -> reload(sender);
            case "debug" -> debug(sender);
            case "admin" -> {
                Player p = playerOnly(sender);
                if (p != null && perm(p, "lankcipass.admin.season")) {
                    plugin.adminGui().open(p);
                }
            }
            case "confirm" -> plugin.confirms().confirm(sender);
            case "cancel" -> plugin.confirms().cancel(sender);
            default -> plugin.messages().send(sender, "common.unknown-command");
        }
    }

    private void help(CommandSender s) {
        plugin.messages().sendList(s, "help.player");
        if (s.hasPermission("lankcipass.admin") || s.hasPermission("lankcipass.admin.season")
                || s.hasPermission("lankcipass.admin.tokens")) {
            plugin.messages().sendList(s, "help.admin");
        }
    }

    private void menu(CommandSender sender, String[] args) {
        Player p = playerOnly(sender);
        if (p == null || !perm(p, "lankcipass.use")) {
            return;
        }
        String name = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "main";
        if (!MENUS.contains(name)) {
            plugin.messages().send(p, "menu.unknown", "menus", String.join(", ", MENUS));
            return;
        }
        plugin.openMenu(p, name);
    }

    private void balance(CommandSender sender) {
        Player p = playerOnly(sender);
        if (p == null || !perm(p, "lankcipass.use")) {
            return;
        }
        var st = plugin.cache().get(p.getUniqueId());
        plugin.messages().send(p, "balance.self", "tokens", Text.num(st == null ? 0 : st.tokens),
                "points", Text.num(st == null ? 0 : st.points));
    }

    private void claim(CommandSender sender, String[] args) {
        Player p = playerOnly(sender);
        if (p == null || !perm(p, "lankcipass.use")) {
            return;
        }
        Integer lvl = args.length > 1 ? parseInt(args[1]) : null;
        if (lvl == null) {
            plugin.messages().send(p, "usage.claim");
            return;
        }
        plugin.rewards().claim(p, lvl).thenAccept(r -> plugin.sync(() -> {
            Player online = Bukkit.getPlayer(p.getUniqueId());
            if (online != null) {
                plugin.rewards().report(online, r);
            }
        }));
    }

    private void view(CommandSender sender, String[] args) {
        Player p = playerOnly(sender);
        if (p == null || !perm(p, "lankcipass.use")) {
            return;
        }
        ModeManager.Mode m = args.length > 1 ? plugin.modes().get(args[1]) : null;
        if (m == null) {
            plugin.messages().send(p, "usage.view");
            return;
        }
        plugin.viewModes().put(p.getUniqueId(), m.id());
    }

    private void shop(CommandSender sender, String[] args) {
        Player p = playerOnly(sender);
        if (p == null || !perm(p, "lankcipass.use")) {
            return;
        }
        if (args.length >= 3 && args[1].equalsIgnoreCase("buy")) {
            Integer i = parseInt(args[2]);
            if (i != null) {
                plugin.shop().buy(p, i);
                return;
            }
        }
        plugin.messages().send(p, "usage.shop");
    }

    // ------------------------------------------------------------------ квести

    private void quest(CommandSender sender, String[] args) {
        String a = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (a) {
            case "claim" -> {
                Player p = playerOnly(sender);
                if (p == null || !perm(p, "lankcipass.use")) {
                    return;
                }
                QuestKind kind = args.length > 2 ? QuestKind.parse(args[2]) : null;
                Integer idx = args.length > 3 ? parseInt(args[3]) : null;
                if (kind == null || idx == null) {
                    plugin.messages().send(p, "usage.quest-claim");
                    return;
                }
                String mode = plugin.viewMode(p);
                plugin.questService().claim(p.getUniqueId(), mode, kind, idx).thenAccept(r -> plugin.sync(() -> {
                    Player online = Bukkit.getPlayer(p.getUniqueId());
                    if (online == null) {
                        return;
                    }
                    switch (r.status()) {
                        case "OK" -> plugin.messages().send(online, "quest.reward-received",
                                "tokens", Text.num(r.applied()), "balance", Text.num(r.balance()));
                        case "ALREADY" -> plugin.messages().send(online, "quest.claim.already");
                        case "NOT_READY" -> plugin.messages().send(online, "quest.claim.not-ready");
                        case "EXPIRED" -> plugin.messages().send(online, "quest.claim.expired");
                        default -> plugin.messages().send(online, "quest.claim.none");
                    }
                }));
            }
            case "reset" -> {
                if (!perm(sender, "lankcipass.admin.quest")) {
                    return;
                }
                if (args.length < 3) {
                    plugin.messages().send(sender, "usage.quest-reset");
                    return;
                }
                String modeArg = args.length > 3 ? args[3].toLowerCase(Locale.ROOT) : null;
                if (modeArg != null && plugin.modes().get(modeArg) == null) {
                    plugin.messages().send(sender, "common.unknown-mode", "mode", modeArg);
                    return;
                }
                withPlayer(sender, args[2], id -> plugin.confirms().request(sender,
                        plugin.messages().list("confirm.warn.quest-reset", "player", args[2],
                                "mode", modeArg == null ? "усі" : modeArg),
                        () -> plugin.questService().resetPlayer(id, modeArg).whenComplete((n, ex) -> plugin.sync(() -> {
                            if (ex != null) {
                                plugin.messages().send(sender, "common.error");
                            } else {
                                plugin.messages().send(sender, "quest.reset.done", "player", args[2], "count", n);
                            }
                        }))));
            }
            case "rotate" -> {
                if (!perm(sender, "lankcipass.admin.quest")) {
                    return;
                }
                String modeArg = args.length > 2 && !args[2].equalsIgnoreCase("all") ? args[2].toLowerCase(Locale.ROOT) : null;
                QuestKind kind = args.length > 3 && !args[3].equalsIgnoreCase("all") ? QuestKind.parse(args[3]) : null;
                if (modeArg != null && plugin.modes().get(modeArg) == null) {
                    plugin.messages().send(sender, "common.unknown-mode", "mode", modeArg);
                    return;
                }
                if (args.length > 3 && !args[3].equalsIgnoreCase("all") && kind == null) {
                    plugin.messages().send(sender, "usage.quest-rotate");
                    return;
                }
                plugin.confirms().request(sender, plugin.messages().list("confirm.warn.quest-rotate"),
                        () -> plugin.quests().forceRotate(modeArg, kind).whenComplete((n, ex) -> plugin.sync(() -> {
                            if (ex != null) {
                                plugin.messages().send(sender, "common.error");
                            } else {
                                plugin.messages().send(sender, "quest.rotate.done", "count", n);
                            }
                        })));
            }
            default -> plugin.messages().send(sender, "usage.quest");
        }
    }

    // ------------------------------------------------------------------ жетони

    private void tokens(CommandSender sender, String[] args) {
        if (args.length == 1) {
            balance(sender);
            return;
        }
        if (!perm(sender, "lankcipass.admin.tokens")) {
            return;
        }
        if (args.length < 3) {
            plugin.messages().send(sender, "usage.tokens");
            return;
        }
        String target = args[1];
        String op = args[2].toLowerCase(Locale.ROOT);
        long max = plugin.settings().maxBalance;
        if (op.equals("check")) {
            withPlayer(sender, target, id -> plugin.tokens().balance(id).whenComplete((b, ex) -> plugin.sync(() ->
                    plugin.messages().send(sender, "tokens.check", "player", target, "tokens", Text.num(b == null ? 0 : b)))));
            return;
        }
        Long amount;
        String mode;
        if (op.equals("give") || op.equals("take") || op.equals("set")) {
            amount = args.length > 3 ? parseLong(args[3]) : null;
            mode = op;
        } else {
            amount = parseLong(args[2]);
            mode = "add";
        }
        if (amount == null) {
            plugin.messages().send(sender, "usage.tokens");
            return;
        }
        if (Math.abs(amount) > max) {
            plugin.messages().send(sender, "tokens.too-large", "max", Text.num(max));
            return;
        }
        if ((mode.equals("give") || mode.equals("take") || mode.equals("set")) && amount < 0) {
            plugin.messages().send(sender, "tokens.negative");
            return;
        }
        final long value = amount;
        withPlayer(sender, target, id -> {
            String reason = "ADMIN:" + sender.getName();
            CompletableFuture<Long> result;
            if (mode.equals("set")) {
                result = plugin.tokens().set(id, value, reason);
            } else {
                long delta = mode.equals("take") ? -value : value;
                result = plugin.tokens().credit(id, delta, reason, false)
                        .thenApply(c -> c.found() ? c.balance() : -1L);
            }
            result.whenComplete((bal, ex) -> plugin.sync(() -> {
                if (ex != null) {
                    plugin.messages().send(sender, "common.error");
                } else if (bal == null || bal < 0) {
                    plugin.messages().send(sender, "common.player-not-found", "name", target);
                } else {
                    plugin.messages().send(sender, "tokens.done", "player", target, "tokens", Text.num(bal));
                }
            }));
        });
    }

    // ------------------------------------------------------------------ сезони

    private void season(CommandSender sender, String[] args) {
        String a = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "info";
        if (!perm(sender, "lankcipass.admin.season")) {
            return;
        }
        switch (a) {
            case "info" -> seasonInfo(sender, args.length > 2 ? args[2] : null);
            case "list" -> seasonList(sender);
            case "start" -> seasonStart(sender, args);
            case "restore" -> {
                String id = args.length > 2 ? args[2] : null;
                if (!knownSeason(sender, id)) {
                    return;
                }
                Integer days = args.length > 3 ? parseInt(args[3]) : null;
                if (args.length > 3 && days == null) {
                    plugin.messages().send(sender, "season.err.days");
                    return;
                }
                confirmThen(sender, "restore", id, () -> plugin.reportOp(sender, plugin.seasons().restore(id, days)));
            }
            case "setdays" -> {
                Integer days = args.length > 2 ? parseInt(args[2]) : null;
                if (days == null) {
                    plugin.messages().send(sender, "usage.season-setdays");
                    return;
                }
                confirmThen(sender, "setdays", String.valueOf(days), () -> plugin.reportOp(sender, plugin.seasons().setDays(days)));
            }
            case "setend" -> {
                Long end = args.length > 3 ? parseEnd(args[2], args[3]) : null;
                if (end == null) {
                    plugin.messages().send(sender, "usage.season-setend");
                    return;
                }
                confirmThen(sender, "setend", plugin.seasons().fmt(end), () -> plugin.reportOp(sender, plugin.seasons().setEnd(end)));
            }
            case "duration" -> {
                Integer days = args.length > 2 ? parseInt(args[2]) : null;
                String id = args.length > 3 ? args[3] : null;
                if (days == null || (id != null && !knownSeason(sender, id))) {
                    if (days == null) {
                        plugin.messages().send(sender, "usage.season-duration");
                    }
                    return;
                }
                confirmThen(sender, "duration", String.valueOf(days), () -> plugin.reportOp(sender, plugin.seasons().setDuration(days, id)));
            }
            case "create" -> {
                if (args.length < 3) {
                    plugin.messages().send(sender, "usage.season-create");
                    return;
                }
                plugin.reportOp(sender, plugin.seasons().create(args[2].toLowerCase(Locale.ROOT)));
            }
            case "archive" -> {
                String id = args.length > 2 ? args[2] : null;
                if (!knownSeason(sender, id)) {
                    return;
                }
                confirmThen(sender, "archive", id, () -> plugin.reportOp(sender, plugin.seasons().archive(id)));
            }
            case "schedule" -> {
                String id = args.length > 2 ? args[2] : null;
                if (!knownSeason(sender, id)) {
                    return;
                }
                plugin.reportOp(sender, plugin.seasons().schedule(id));
            }
            default -> plugin.messages().send(sender, "usage.season");
        }
    }

    private boolean knownSeason(CommandSender s, String id) {
        if (id == null) {
            plugin.messages().send(s, "usage.season");
            return false;
        }
        if (plugin.seasons().def(id) == null) {
            plugin.messages().send(s, "season.err.unknown", "id", id);
            return false;
        }
        return true;
    }

    private void seasonStart(CommandSender sender, String[] args) {
        String id = args.length > 2 ? args[2] : null;
        if (!knownSeason(sender, id)) {
            return;
        }
        String mode = args.length > 3 ? args[3].toLowerCase(Locale.ROOT) : "new";
        Integer days = null;
        if (args.length > 4) {
            days = parseInt(args[4]);
            if (days == null) {
                plugin.messages().send(sender, "season.err.days");
                return;
            }
        }
        final Integer d = days;
        switch (mode) {
            case "new" -> confirmThen(sender, "start-new", id, () -> plugin.reportOp(sender, plugin.seasons().start(id, false, d)));
            case "reset" -> confirmThen(sender, "start-reset", id, () -> plugin.reportOp(sender, plugin.seasons().start(id, true, d)));
            case "restore" -> confirmThen(sender, "restore", id, () -> plugin.reportOp(sender, plugin.seasons().restore(id, d)));
            default -> plugin.messages().send(sender, "usage.season-start");
        }
    }

    private void confirmThen(CommandSender sender, String action, String arg, Runnable run) {
        SeasonDef d = plugin.seasons().def(arg);
        List<String> lines = d != null ? plugin.warningLines(action, d) : plugin.warningLines(action, arg);
        plugin.confirms().request(sender, lines, run);
    }

    private void seasonInfo(CommandSender s, String id) {
        SeasonRun a = plugin.seasons().activeRun();
        long now = System.currentTimeMillis();
        if (id == null) {
            SeasonDef d = plugin.seasons().defOf(a);
            if (a == null) {
                plugin.messages().send(s, "season.info.none");
            } else {
                long left = a.remaining(now);
                plugin.messages().send(s, "season.info.active", "name", d == null ? a.seasonId() : d.coloredName(),
                        "run", a.runId(), "mode", a.progressMode(), "start", plugin.seasons().fmt(a.startAt()),
                        "end", plugin.seasons().fmt(a.endAt()),
                        "timer", TimeUtil.timerColor(left, plugin.settings()) + TimeUtil.duration(left),
                        "policy", a.claimPolicy());
            }
            for (SeasonRun r : plugin.seasons().scheduledRuns()) {
                SeasonDef sd = plugin.seasons().defOf(r);
                plugin.messages().send(s, "season.info.scheduled", "name", sd == null ? r.seasonId() : sd.coloredName(),
                        "start", plugin.seasons().fmt(r.startAt()), "end", plugin.seasons().fmt(r.endAt()), "run", r.runId());
            }
            return;
        }
        SeasonDef d = plugin.seasons().def(id);
        if (d == null) {
            plugin.messages().send(s, "season.err.unknown", "id", id);
            return;
        }
        plugin.messages().send(s, "season.info.def", "name", d.coloredName(), "id", d.id(), "number", d.number(),
                "levels", d.levels().size(), "days", d.durationDays(), "state", plugin.seasons().stateOf(d));
        for (SeasonRun r : plugin.seasons().runsOf(d.id())) {
            plugin.messages().send(s, "season.info.run", "run", r.runId(), "status", r.status().name(),
                    "mode", r.progressMode(), "start", plugin.seasons().fmt(r.startAt()),
                    "end", plugin.seasons().fmt(r.endAt()), "restored", r.restoreCount(),
                    "reason", r.status() == RunStatus.ENDED && r.endReason() != null ? r.endReason() : "-");
        }
    }

    private void seasonList(CommandSender s) {
        plugin.messages().send(s, "season.list.header");
        for (SeasonDef d : plugin.seasons().allDefs()) {
            plugin.messages().send(s, "season.list.entry", "name", d.coloredName(), "id", d.id(),
                    "number", d.number(), "state", plugin.seasons().stateOf(d), "levels", d.levels().size(),
                    "days", d.durationDays());
        }
    }

    /** "2026-12-31 23:59" або "31.12.2026 23:59" у часовому поясі з config.yml. */
    private Long parseEnd(String date, String time) {
        try {
            LocalDate d;
            if (date.contains(".")) {
                d = LocalDate.parse(date, DateTimeFormatter.ofPattern("dd.MM.yyyy"));
            } else {
                d = LocalDate.parse(date);
            }
            LocalTime t = LocalTime.parse(time);
            return LocalDateTime.of(d, t).atZone(plugin.settings().zone).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ reload / debug

    private void reload(CommandSender s) {
        if (!perm(s, "lankcipass.admin.reload")) {
            return;
        }
        List<String> problems = plugin.reloadAll();
        plugin.messages().send(s, "reload.done");
        if (!problems.isEmpty()) {
            plugin.messages().send(s, "reload.problems", "count", problems.size());
            for (int i = 0; i < Math.min(8, problems.size()); i++) {
                s.sendMessage(Text.c("&8- &c" + problems.get(i)));
            }
            if (problems.size() > 8) {
                plugin.messages().send(s, "reload.more", "count", problems.size() - 8);
            }
        }
    }

    private void debug(CommandSender s) {
        if (!perm(s, "lankcipass.admin.debug")) {
            return;
        }
        List<String> out = new ArrayList<>();
        out.add("&d&lLankciPass debug &8(v" + plugin.getPluginMeta().getVersion() + ")");
        out.add("&7БД: " + (plugin.database().isOpen() ? "&aOK" : "&cзакрита") + " &8" + plugin.database().path().getFileName());
        out.add("&7PlaceholderAPI: " + (plugin.papiHooked() ? "&aпідключено" : "&eнемає (плейсхолдери недоступні)"));
        out.add("&7DeluxeMenus: " + (plugin.deluxeMenusPresent() ? "&aзнайдено" : "&eнемає (меню замінює текст у чаті)"));
        out.add("&7LuckPerms: " + (plugin.legacy().available() ? "&aзнайдено (імпорт старих нагород)" : "&eнемає"));
        SeasonRun a = plugin.seasons().activeRun();
        out.add("&7Активний сезон: " + (a == null ? "&cнемає" : "&f" + a.seasonId() + " &8(run #" + a.runId() + ", "
                + TimeUtil.duration(plugin.seasons().remainingMs()) + ")"));
        out.add("&7Сезонів у конфігурації: &f" + plugin.seasons().allDefs().size() + " &8| &7запусків у БД: &f" + plugin.seasons().totalRuns());
        out.add("&7Квестів у конфігурації: &f" + plugin.quests().totalQuests() + " &8| &7активних циклів: &f" + plugin.quests().allCurrent().size());
        out.add("&7Події в буфері квестів: &f" + plugin.questService().pendingCount()
                + " &8| &7гравців у кеші: &f" + plugin.cache().size());
        out.add("&7Режими: &f" + plugin.modes().all().stream().map(ModeManager.Mode::id).toList());
        List<String> unmapped = plugin.modes().unmappedWorlds();
        out.add("&7Світи без режиму: " + (unmapped.isEmpty() ? "&aнемає" : "&e" + unmapped));
        List<String> warns = new ArrayList<>(plugin.seasons().warnings());
        warns.addAll(plugin.quests().warnings());
        warns.addAll(plugin.rewards().validateConfig());
        out.add("&7Попередження конфігурації: " + (warns.isEmpty() ? "&aнемає" : "&e" + warns.size()));
        for (int i = 0; i < Math.min(6, warns.size()); i++) {
            out.add("&8 - &e" + warns.get(i));
        }
        for (String l : out) {
            s.sendMessage(Text.c(l));
        }
    }

    // ------------------------------------------------------------------ автодоповнення

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        boolean admin = sender.hasPermission("lankcipass.admin");
        if (args.length == 1) {
            out.addAll(List.of("help", "menu", "balance", "claim", "quest", "shop", "tokens"));
            if (admin || sender.hasPermission("lankcipass.admin.season")) {
                out.addAll(List.of("season", "admin", "confirm", "cancel"));
            }
            if (admin || sender.hasPermission("lankcipass.admin.reload")) {
                out.add("reload");
            }
            if (admin || sender.hasPermission("lankcipass.admin.debug")) {
                out.add("debug");
            }
        } else {
            String a0 = args[0].toLowerCase(Locale.ROOT);
            switch (a0) {
                case "menu" -> {
                    if (args.length == 2) {
                        out.addAll(MENUS);
                    }
                }
                case "tokens" -> {
                    if (args.length == 2) {
                        out.addAll(onlineNames());
                    } else if (args.length == 3) {
                        out.addAll(List.of("give", "take", "set", "check"));
                    }
                }
                case "quest", "quests" -> {
                    if (args.length == 2) {
                        out.addAll(List.of("claim", "reset", "rotate"));
                    } else if (args.length == 3 && args[1].equalsIgnoreCase("claim")) {
                        out.addAll(List.of("daily", "mega"));
                    } else if (args.length == 3 && args[1].equalsIgnoreCase("reset")) {
                        out.addAll(onlineNames());
                    } else if (args.length == 3 && args[1].equalsIgnoreCase("rotate")) {
                        out.add("all");
                        plugin.modes().all().forEach(m -> out.add(m.id()));
                    } else if (args.length == 4 && args[1].equalsIgnoreCase("reset")) {
                        plugin.modes().all().forEach(m -> out.add(m.id()));
                    } else if (args.length == 4 && args[1].equalsIgnoreCase("rotate")) {
                        out.addAll(List.of("all", "daily", "mega"));
                    }
                }
                case "view" -> {
                    if (args.length == 2) {
                        plugin.modes().all().forEach(m -> out.add(m.id()));
                    }
                }
                case "season", "seasons" -> {
                    if (args.length == 2) {
                        out.addAll(List.of("info", "list", "start", "setdays", "setend", "restore", "duration",
                                "create", "archive", "schedule"));
                    } else if (args.length == 3 && List.of("info", "start", "restore", "archive", "schedule")
                            .contains(args[1].toLowerCase(Locale.ROOT))) {
                        plugin.seasons().allDefs().forEach(d -> out.add(d.id()));
                    } else if (args.length == 4 && args[1].equalsIgnoreCase("start")) {
                        out.addAll(List.of("new", "reset", "restore"));
                    } else if (args.length == 4 && args[1].equalsIgnoreCase("duration")) {
                        plugin.seasons().allDefs().forEach(d -> out.add(d.id()));
                    } else if (args.length == 3 && args[1].equalsIgnoreCase("setend")) {
                        out.add("2026-12-31");
                    } else if (args.length == 4 && args[1].equalsIgnoreCase("setend")) {
                        out.add("23:59");
                    }
                }
                default -> { /* без підказок */ }
            }
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }

    private List<String> onlineNames() {
        List<String> n = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            n.add(p.getName());
        }
        return n;
    }
}
