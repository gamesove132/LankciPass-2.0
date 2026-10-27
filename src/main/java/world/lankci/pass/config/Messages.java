package world.lankci.pass.config;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import world.lankci.pass.LankciPassCore;
import world.lankci.pass.util.Text;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Повідомлення з messages.yml. Якщо ключа немає у файлі сервера - береться значення з jar. */
public final class Messages {
    private final LankciPassCore plugin;
    private YamlConfiguration cfg = new YamlConfiguration();
    private YamlConfiguration defaults = new YamlConfiguration();
    private final Set<String> warned = new HashSet<>();

    public Messages(LankciPassCore plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File f = new File(plugin.getDataFolder(), "messages.yml");
        cfg = YamlConfiguration.loadConfiguration(f);
        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Не вдалося прочитати типові повідомлення: " + e.getMessage());
        }
        warned.clear();
    }

    private String raw(String key) {
        String v = cfg.getString(key);
        if (v == null) {
            v = defaults.getString(key);
        }
        if (v == null) {
            if (warned.add(key)) {
                plugin.getLogger().warning("Відсутнє повідомлення: " + key);
            }
            return "&c[" + key + "]";
        }
        return v;
    }

    private String apply(String s, Object... kv) {
        s = s.replace("{prefix}", prefixRaw());
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s = s.replace("{" + kv[i] + "}", String.valueOf(kv[i + 1]));
        }
        return s;
    }

    private String prefixRaw() {
        String v = cfg.getString("prefix");
        if (v == null) {
            v = defaults.getString("prefix", "&d&lLANKCIPASS &8» ");
        }
        return v;
    }

    /** Рядок із &amp;-кодами. kv - пари ключ, значення для плейсхолдерів {ключ}. */
    public String get(String key, Object... kv) {
        return apply(raw(key), kv);
    }

    public List<String> list(String key, Object... kv) {
        List<String> l = cfg.isList(key) ? cfg.getStringList(key) : defaults.getStringList(key);
        if (l.isEmpty()) {
            return new ArrayList<>(List.of(get(key, kv)));
        }
        List<String> out = new ArrayList<>(l.size());
        for (String s : l) {
            out.add(apply(s, kv));
        }
        return out;
    }

    public void send(CommandSender to, String key, Object... kv) {
        to.sendMessage(Text.c(get(key, kv)));
    }

    public void sendList(CommandSender to, String key, Object... kv) {
        for (String s : list(key, kv)) {
            to.sendMessage(Text.c(s));
        }
    }
}
