package world.lankci.pass.compat;

import world.lankci.pass.LankciPassCore;
import world.lankci.pass.papi.LankciPassExpansion;

/** Ізолює реєстрацію розширення, щоб клас PlaceholderAPI не завантажувався, коли плагіна немає. */
public final class PapiHook {
    private LankciPassExpansion expansion;

    public boolean register(LankciPassCore plugin) {
        expansion = new LankciPassExpansion(plugin);
        return expansion.register();
    }

    public void unregister() {
        if (expansion != null) {
            try {
                expansion.unregister();
            } catch (Throwable ignored) {
                // PlaceholderAPI уже вимкнено
            }
            expansion = null;
        }
    }
}
