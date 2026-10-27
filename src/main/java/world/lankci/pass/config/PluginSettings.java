package world.lankci.pass.config;

import org.bukkit.configuration.file.FileConfiguration;
import world.lankci.pass.LankciPassCore;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;

/** Значення з config.yml із перевіркою діапазонів. Усі поля читаються з головного потоку або після load(). */
public final class PluginSettings {
    private final LankciPassCore plugin;

    public ZoneId zone = ZoneId.of("Europe/Kyiv");
    public DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // database
    public String dbFile = "lankcipass.db";
    public boolean backupEnabled = true;
    public int backupKeep = 15;

    // tokens
    public long maxBalance = 1_000_000_000L;
    public double pointsPerToken = 1.0;

    // pvp
    public boolean pvpEnabled = true;
    public int pvpMin = 10;
    public int pvpMax = 50;
    public long pvpVictimCooldownMs = 1_800_000L;
    public int pvpMaxPerVictimDay = 3;
    public int pvpMaxPerDay = 20;
    public boolean pvpIgnoreSameIp = true;
    public long pvpVictimMinOnlineMs = 120_000L;
    public boolean pvpRequireMode = true;
    public boolean pvpNotifyRejected = false;

    // quests
    public boolean autoClaim = true;
    public boolean ignoreCreative = true;
    public boolean ignoreSpawnerMobs = true;
    public boolean ignorePlacedBlocks = true;
    public int flushTicks = 40;
    public int cycleCheckSeconds = 30;

    // seasons
    public int defaultDurationDays = 30;
    public int keepScheduledAhead = 2;
    public int seasonCheckSeconds = 20;
    public int warningDays = 15;
    public int criticalDays = 5;
    public String colorNormal = "&b";
    public String colorWarning = "&e";
    public String colorCritical = "&c";
    public boolean legacyImport = true;

    // rewards
    public boolean precheckCommands = true;
    public Set<String> vanillaLabels = new HashSet<>();

    // gui
    public boolean sounds = true;
    public int guiRefreshTicks = 20;

    // admin
    public int confirmTimeoutSeconds = 30;

    public PluginSettings(LankciPassCore plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.reloadConfig();
        FileConfiguration c = plugin.getConfig();

        try {
            zone = ZoneId.of(c.getString("timezone", "Europe/Kyiv"));
        } catch (DateTimeException e) {
            plugin.getLogger().warning("Невірний timezone у config.yml, використано Europe/Kyiv");
            zone = ZoneId.of("Europe/Kyiv");
        }
        try {
            dateFormat = DateTimeFormatter.ofPattern(c.getString("date-format", "yyyy-MM-dd HH:mm"));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Невірний date-format у config.yml, використано yyyy-MM-dd HH:mm");
            dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        }

        dbFile = c.getString("database.file", "lankcipass.db");
        if (dbFile.contains("/") || dbFile.contains("\\") || dbFile.contains("..")) {
            plugin.getLogger().warning("database.file не може містити шляхи, використано lankcipass.db");
            dbFile = "lankcipass.db";
        }
        backupEnabled = c.getBoolean("database.backup.enabled", true);
        backupKeep = clamp(c.getInt("database.backup.keep", 15), 1, 500, "database.backup.keep");

        maxBalance = Math.max(1, Math.min(c.getLong("tokens.max-balance", 1_000_000_000L), 4_000_000_000_000L));
        pointsPerToken = Math.max(0.0, Math.min(c.getDouble("tokens.points-per-token", 1.0), 1000.0));

        pvpEnabled = c.getBoolean("pvp.enabled", true);
        int min = clamp(c.getInt("pvp.min-tokens", 10), 0, 1_000_000, "pvp.min-tokens");
        int max = clamp(c.getInt("pvp.max-tokens", 50), 0, 1_000_000, "pvp.max-tokens");
        if (max < min) {
            plugin.getLogger().warning("pvp.max-tokens менше за pvp.min-tokens - значення поміняно місцями");
            int t = min;
            min = max;
            max = t;
        }
        pvpMin = min;
        pvpMax = max;
        pvpVictimCooldownMs = clamp(c.getInt("pvp.per-victim-cooldown-seconds", 1800), 0, 86_400 * 30, "pvp.per-victim-cooldown-seconds") * 1000L;
        pvpMaxPerVictimDay = clamp(c.getInt("pvp.max-rewards-per-victim-per-day", 3), 1, 10_000, "pvp.max-rewards-per-victim-per-day");
        pvpMaxPerDay = clamp(c.getInt("pvp.max-rewards-per-day", 20), 1, 100_000, "pvp.max-rewards-per-day");
        pvpIgnoreSameIp = c.getBoolean("pvp.ignore-same-ip", true);
        pvpVictimMinOnlineMs = clamp(c.getInt("pvp.victim-min-online-seconds", 120), 0, 86_400, "pvp.victim-min-online-seconds") * 1000L;
        pvpRequireMode = c.getBoolean("pvp.require-known-mode", true);
        pvpNotifyRejected = c.getBoolean("pvp.notify-rejected", false);

        autoClaim = c.getBoolean("quests.auto-claim", true);
        ignoreCreative = c.getBoolean("quests.ignore-creative", true);
        ignoreSpawnerMobs = c.getBoolean("quests.ignore-spawner-mobs", true);
        ignorePlacedBlocks = c.getBoolean("quests.ignore-player-placed-blocks", true);
        flushTicks = clamp(c.getInt("quests.flush-interval-ticks", 40), 10, 1200, "quests.flush-interval-ticks");
        cycleCheckSeconds = clamp(c.getInt("quests.cycle-check-seconds", 30), 5, 3600, "quests.cycle-check-seconds");

        defaultDurationDays = clamp(c.getInt("seasons.default-duration-days", 30), 1, 3650, "seasons.default-duration-days");
        keepScheduledAhead = clamp(c.getInt("seasons.keep-scheduled-ahead", 2), 0, 20, "seasons.keep-scheduled-ahead");
        seasonCheckSeconds = clamp(c.getInt("seasons.check-interval-seconds", 20), 5, 3600, "seasons.check-interval-seconds");
        warningDays = clamp(c.getInt("seasons.timer.warning-days", 15), 1, 3650, "seasons.timer.warning-days");
        criticalDays = clamp(c.getInt("seasons.timer.critical-days", 5), 0, 3650, "seasons.timer.critical-days");
        if (criticalDays > warningDays) {
            criticalDays = warningDays;
        }
        colorNormal = c.getString("seasons.timer.color-normal", "&b");
        colorWarning = c.getString("seasons.timer.color-warning", "&e");
        colorCritical = c.getString("seasons.timer.color-critical", "&c");
        legacyImport = c.getBoolean("seasons.legacy-luckperms-import", true);

        precheckCommands = c.getBoolean("rewards.precheck-commands", true);
        vanillaLabels = new HashSet<>();
        for (String s : c.getStringList("rewards.trusted-command-labels")) {
            vanillaLabels.add(s.toLowerCase(Locale.ROOT));
        }

        sounds = c.getBoolean("gui.sounds", true);
        guiRefreshTicks = clamp(c.getInt("gui.refresh-ticks", 20), 5, 200, "gui.refresh-ticks");
        confirmTimeoutSeconds = clamp(c.getInt("confirm-timeout-seconds", 30), 5, 300, "confirm-timeout-seconds");
    }

    private int clamp(int v, int lo, int hi, String path) {
        if (v < lo || v > hi) {
            int fixed = Math.max(lo, Math.min(hi, v));
            plugin.getLogger().log(Level.WARNING, "config.yml: {0} = {1} поза діапазоном [{2}..{3}], використано {4}",
                    new Object[]{path, v, lo, hi, fixed});
            return fixed;
        }
        return v;
    }
}
