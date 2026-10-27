package world.lankci.pass;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import world.lankci.pass.command.ConfirmManager;
import world.lankci.pass.command.LankciPassCommand;
import world.lankci.pass.compat.LegacyImporter;
import world.lankci.pass.compat.PapiHook;
import world.lankci.pass.config.Messages;
import world.lankci.pass.config.PluginSettings;
import world.lankci.pass.db.Database;
import world.lankci.pass.gui.AdminGui;
import world.lankci.pass.listener.GameplayListener;
import world.lankci.pass.mode.ModeManager;
import world.lankci.pass.pvp.PvpService;
import world.lankci.pass.quest.Cycle;
import world.lankci.pass.quest.QuestKind;
import world.lankci.pass.quest.QuestManager;
import world.lankci.pass.quest.QuestService;
import world.lankci.pass.quest.QuestType;
import world.lankci.pass.reward.RewardService;
import world.lankci.pass.season.SeasonDef;
import world.lankci.pass.season.SeasonManager;
import world.lankci.pass.season.SeasonRun;
import world.lankci.pass.token.PlayerCache;
import world.lankci.pass.token.PlayerService;
import world.lankci.pass.token.ShopService;
import world.lankci.pass.token.TokenService;
import world.lankci.pass.util.Text;
import world.lankci.pass.util.TimeUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** LankciPassCore - серверна частина LankciPass 2.0: жетони, квести, сезони, SQLite, PlaceholderAPI. */
public final class LankciPassCore extends JavaPlugin {
    private static final List<String> RESOURCES = List.of("messages.yml", "quests.yml", "seasons.yml", "shop.yml");

    private PluginSettings settings;
    private Messages messages;
    private Database database;
    private PlayerCache cache;
    private TokenService tokens;
    private PlayerService players;
    private ModeManager modes;
    private SeasonManager seasons;
    private QuestManager quests;
    private QuestService questService;
    private RewardService rewards;
    private ShopService shop;
    private PvpService pvp;
    private LegacyImporter legacy;
    private AdminGui adminGui;
    private ConfirmManager confirms;
    private final PapiHook papi = new PapiHook();
    private boolean papiHooked;
    private volatile boolean ready;
    private final Map<UUID, String> viewModes = new ConcurrentHashMap<>();
    private final List<BukkitTask> tasks = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        for (String r : RESOURCES) {
            if (!new File(getDataFolder(), r).exists()) {
                saveResource(r, false);
            }
        }
        settings = new PluginSettings(this);
        settings.load();
        messages = new Messages(this);
        messages.load();

        database = new Database(this);
        try {
            database.init();
        } catch (Exception e) {
            getLogger().log(java.util.logging.Level.SEVERE,
                    "Не вдалося відкрити базу даних SQLite - плагін вимкнено. Дані не пошкоджено.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        cache = new PlayerCache();
        tokens = new TokenService(this);
        players = new PlayerService(this);
        modes = new ModeManager(this);
        seasons = new SeasonManager(this);
        quests = new QuestManager(this);
        questService = new QuestService(this);
        rewards = new RewardService(this);
        shop = new ShopService(this);
        pvp = new PvpService(this);
        legacy = new LegacyImporter(this);
        adminGui = new AdminGui(this);
        confirms = new ConfirmManager(this);

        modes.load();
        seasons.loadDefinitions();
        seasons.startup();
        quests.loadConfig();
        quests.startup();
        shop.load();
        legacy.init();

        getServer().getPluginManager().registerEvents(new GameplayListener(this), this);
        getServer().getPluginManager().registerEvents(adminGui, this);
        PluginCommand cmd = getCommand("lankcipass");
        if (cmd != null) {
            LankciPassCommand handler = new LankciPassCommand(this);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }

        var papiPlugin = getServer().getPluginManager().getPlugin("PlaceholderAPI");
        if (papiPlugin != null && papiPlugin.isEnabled()) {
            try {
                papiHooked = papi.register(this);
            } catch (Throwable t) {
                getLogger().warning("Не вдалося зареєструвати плейсхолдери: " + t.getMessage());
            }
        } else {
            getLogger().warning("PlaceholderAPI не знайдено: меню DeluxeMenus не отримають даних. Працюють команди й адмін-GUI.");
        }
        if (!deluxeMenusPresent()) {
            getLogger().warning("DeluxeMenus не знайдено: /lankcipass показує текстову сторінку замість меню.");
        }

        startTasks();
        ready = true;
        for (Player p : getServer().getOnlinePlayers()) {
            pvp.onJoin(p.getUniqueId());
            players.onJoin(p);
        }
        modes.bindLoadedWorlds();
        List<String> problems = new ArrayList<>(rewards.validateConfig());
        for (String p : problems) {
            getLogger().warning(p);
        }
        getLogger().info("LankciPassCore " + getPluginMeta().getVersion() + " увімкнено.");
    }

    private void startTasks() {
        var sch = getServer().getScheduler();
        tasks.add(sch.runTaskTimer(this, () -> seasons.tick(), 100L, settings.seasonCheckSeconds * 20L));
        tasks.add(sch.runTaskTimer(this, () -> quests.tick(), 120L, settings.cycleCheckSeconds * 20L));
        tasks.add(sch.runTaskTimer(this, () -> questService.flush(), settings.flushTicks, settings.flushTicks));
        tasks.add(sch.runTaskTimer(this, () -> players.refreshAllOnline(), 1200L, 1200L));
        tasks.add(sch.runTaskTimer(this, this::playMinutes, 1200L, 1200L));
    }

    private void playMinutes() {
        for (Player p : getServer().getOnlinePlayers()) {
            if (settings.ignoreCreative && p.getGameMode() == GameMode.CREATIVE) {
                continue;
            }
            String mode = modes.modeOf(p.getWorld());
            if (mode != null) {
                questService.event(p, mode, QuestType.PLAY_MINUTES, null, null, 1);
            }
        }
    }

    @Override
    public void onDisable() {
        ready = false;
        for (BukkitTask t : tasks) {
            t.cancel();
        }
        tasks.clear();
        papi.unregister();
        if (questService != null && database != null && database.isOpen()) {
            questService.flushBlocking();
        }
        if (database != null) {
            database.close();
        }
        if (cache != null) {
            cache.clear();
        }
        if (confirms != null) {
            confirms.clear();
        }
    }

    // ------------------------------------------------------------------ спільні сервіси

    /** Виконати в головному потоці (безпечно, якщо плагін уже вимкнено). */
    public void sync(Runnable r) {
        if (!isEnabled()) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            r.run();
        } else {
            Bukkit.getScheduler().runTask(this, r);
        }
    }

    public boolean isReady() {
        return ready;
    }

    public boolean isDebugEnabled() {
        return getConfig().getBoolean("debug", false);
    }

    public boolean papiHooked() {
        return papiHooked;
    }

    public boolean deluxeMenusPresent() {
        var dm = getServer().getPluginManager().getPlugin("DeluxeMenus");
        return dm != null && dm.isEnabled();
    }

    public PluginSettings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public Database database() {
        return database;
    }

    public PlayerCache cache() {
        return cache;
    }

    public TokenService tokens() {
        return tokens;
    }

    public PlayerService players() {
        return players;
    }

    public ModeManager modes() {
        return modes;
    }

    public SeasonManager seasons() {
        return seasons;
    }

    public QuestManager quests() {
        return quests;
    }

    public QuestService questService() {
        return questService;
    }

    public RewardService rewards() {
        return rewards;
    }

    public ShopService shop() {
        return shop;
    }

    public PvpService pvp() {
        return pvp;
    }

    public LegacyImporter legacy() {
        return legacy;
    }

    public AdminGui adminGui() {
        return adminGui;
    }

    public ConfirmManager confirms() {
        return confirms;
    }

    public Map<UUID, String> viewModes() {
        return viewModes;
    }

    /** Режим, який гравець переглядає в меню квестів: вибраний вкладкою, інакше режим його світу, інакше перший. */
    public String viewMode(Player p) {
        String chosen = viewModes.get(p.getUniqueId());
        if (chosen != null && modes.get(chosen) != null) {
            return chosen;
        }
        String byWorld = modes.modeOf(p.getWorld());
        if (byWorld != null) {
            return byWorld;
        }
        var it = modes.all().iterator();
        return it.hasNext() ? it.next().id() : null;
    }

    public CompletableFuture<Void> legacyImport(Player p) {
        return legacy.importFor(p);
    }

    // ------------------------------------------------------------------ події всередині системи

    public void onSeasonStateChanged() {
        players.refreshAllOnline();
    }

    public void notifyNewCycle(Cycle cycle) {
        String key = cycle.kind() == QuestKind.DAILY ? "quest.new-daily" : "quest.new-mega";
        var mode = modes.get(cycle.mode());
        for (Player p : getServer().getOnlinePlayers()) {
            if (p.hasPermission("lankcipass.use")) {
                messages.send(p, key, "mode", mode == null ? cycle.mode() : mode.display());
            }
        }
    }

    /** Відкрити меню DeluxeMenus за ключем (main, quests, mega, seasons, shop) або показати текстову сторінку. */
    public void openMenu(Player p, String key) {
        String menu = getConfig().getString("menus." + key);
        if (menu != null && !menu.isBlank() && deluxeMenusPresent() && papiHooked) {
            String safe = menu.replaceAll("[^A-Za-z0-9_.-]", "");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "dm open " + safe + " " + p.getName());
            return;
        }
        textSummary(p);
    }

    private void textSummary(Player p) {
        PlayerCache.Stats st = cache.get(p.getUniqueId());
        SeasonRun run = seasons.activeRun();
        SeasonDef def = seasons.defOf(run);
        long left = seasons.remainingMs();
        messages.sendList(p, "menu.fallback",
                "season", def == null ? "-" : def.coloredName(),
                "timer", run == null ? "-" : TimeUtil.timerColor(left, settings) + TimeUtil.duration(left),
                "tokens", Text.num(st == null ? 0 : st.tokens),
                "points", Text.num(st == null ? 0 : st.points),
                "claimed", st == null ? 0 : st.claimed.size(),
                "levels", def == null ? 0 : def.levels().size());
    }

    /** Результат адміністративної операції із сезонами: повідомлення відправнику. */
    public void reportOp(CommandSender to, CompletableFuture<SeasonManager.OpResult> f) {
        f.whenComplete((r, ex) -> sync(() -> {
            if (ex != null || r == null) {
                messages.send(to, "common.error");
            } else {
                messages.send(to, r.key(), r.kv());
            }
        }));
    }

    public List<String> warningLines(String action, SeasonDef d) {
        SeasonRun a = seasons.activeRun();
        SeasonDef ad = seasons.defOf(a);
        return messages.list("confirm.warn." + action, "id", d.id(), "name", d.coloredName(), "value", d.id(),
                "active", ad == null ? "немає" : ad.coloredName(),
                "left", a == null ? "-" : TimeUtil.duration(seasons.remainingMs()));
    }

    public List<String> warningLines(String action, String value) {
        SeasonRun a = seasons.activeRun();
        SeasonDef ad = seasons.defOf(a);
        return messages.list("confirm.warn." + action, "id", value, "name", value, "value", value,
                "active", ad == null ? "немає" : ad.coloredName(),
                "left", a == null ? "-" : TimeUtil.duration(seasons.remainingMs()));
    }

    /** /lankcipass reload. Повертає список проблем конфігурації. */
    public List<String> reloadAll() {
        List<String> problems = new ArrayList<>();
        settings.load();
        messages.load();
        modes.load();
        if (seasons.loadDefinitions()) {
            seasons.tick();
        } else {
            problems.add("seasons.yml не прочитано - залишено попередні сезони");
        }
        if (quests.loadConfig()) {
            quests.tick();
        } else {
            problems.add("quests.yml не прочитано - залишено попередні квести");
        }
        if (!shop.load()) {
            problems.add("shop.yml не прочитано - залишено попередній магазин");
        }
        problems.addAll(seasons.warnings());
        problems.addAll(quests.warnings());
        problems.addAll(rewards.validateConfig());
        modes.bindLoadedWorlds();
        players.refreshAllOnline();
        return problems;
    }
}
