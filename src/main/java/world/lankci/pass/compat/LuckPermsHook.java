package world.lankci.pass.compat;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.PermissionNode;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Лише ЧИТАЄ старі права pass.N.done (будь-який контекст/світ) для одноразового імпорту у SQLite.
 * LuckPerms не є базою прогресу - нічого в ньому не змінюється. Клас завантажується лише коли LuckPerms присутній.
 */
public final class LuckPermsHook {
    private final LuckPerms api;

    public LuckPermsHook() {
        this.api = LuckPermsProvider.get();
    }

    /** Номери рівнів, для яких у гравця є pass.N.done = true. */
    public CompletableFuture<Set<Integer>> legacyLevels(UUID uuid, int maxLevel) {
        return api.getUserManager().loadUser(uuid).thenApply(this_ -> collect(this_, maxLevel));
    }

    private Set<Integer> collect(User user, int maxLevel) {
        Set<Integer> out = new HashSet<>();
        for (PermissionNode n : user.getNodes(NodeType.PERMISSION)) {
            if (!n.getValue()) {
                continue;
            }
            String k = n.getPermission();
            if (k.startsWith("pass.") && k.endsWith(".done")) {
                String mid = k.substring(5, k.length() - 5);
                try {
                    int lvl = Integer.parseInt(mid);
                    if (lvl >= 1 && lvl <= maxLevel) {
                        out.add(lvl);
                    }
                } catch (NumberFormatException ignored) {
                    // інше право, не стосується LankciPass
                }
            }
        }
        return out;
    }
}
