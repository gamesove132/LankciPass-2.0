package world.lankci.pass.util;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import world.lankci.pass.LankciPassCore;

import java.io.File;
import java.io.IOException;

/** Безпечне читання YAML: при синтаксичній помилці повертає null (а не порожню конфігурацію). */
public final class Yaml {
    private Yaml() {
    }

    public static YamlConfiguration load(LankciPassCore plugin, File file) {
        if (!file.exists()) {
            plugin.getLogger().warning("Файл не знайдено: " + file.getName());
            return null;
        }
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.load(file);
            return y;
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().severe("Помилка YAML у " + file.getName() + ": " + e.getMessage()
                    + " - залишено попередню конфігурацію.");
            return null;
        }
    }
}
