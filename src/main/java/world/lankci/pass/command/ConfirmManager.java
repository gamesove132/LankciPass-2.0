package world.lankci.pass.command;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import world.lankci.pass.LankciPassCore;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Підтвердження небезпечних операцій: показати попередження, зберегти дію, виконати після /lankcipass confirm. */
public final class ConfirmManager {
    private record Pending(List<String> warning, Runnable action, long expiresAt) {
    }

    private final LankciPassCore plugin;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public ConfirmManager(LankciPassCore plugin) {
        this.plugin = plugin;
    }

    private static String key(CommandSender s) {
        return s instanceof Player p ? p.getUniqueId().toString() : "console";
    }

    /** Запам'ятовує дію та показує попередження з інструкцією підтвердження. */
    public void request(CommandSender sender, List<String> warning, Runnable action) {
        long ttl = plugin.settings().confirmTimeoutSeconds * 1000L;
        pending.put(key(sender), new Pending(warning, action, System.currentTimeMillis() + ttl));
        plugin.messages().send(sender, "confirm.header");
        for (String l : warning) {
            sender.sendMessage(world.lankci.pass.util.Text.c(l));
        }
        plugin.messages().send(sender, "confirm.how", "seconds", plugin.settings().confirmTimeoutSeconds);
    }

    /** /lankcipass confirm */
    public void confirm(CommandSender sender) {
        Pending p = pending.remove(key(sender));
        if (p == null || p.expiresAt() < System.currentTimeMillis()) {
            plugin.messages().send(sender, "confirm.none");
            return;
        }
        p.action().run();
    }

    /** /lankcipass cancel */
    public void cancel(CommandSender sender) {
        if (pending.remove(key(sender)) != null) {
            plugin.messages().send(sender, "confirm.cancelled");
        } else {
            plugin.messages().send(sender, "confirm.none");
        }
    }

    public void clear() {
        pending.clear();
    }
}
