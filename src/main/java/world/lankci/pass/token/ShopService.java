package world.lankci.pass.token;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import world.lankci.pass.LankciPassCore;
import world.lankci.pass.util.Text;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Магазин жетонів (shop.yml). Покупка: атомарне списання, потім команди; якщо жодна не виконалась - повернення. */
public final class ShopService {
    public record Item(String id, String name, long price, Material icon, List<String> lore, List<String> commands) {
    }

    private final LankciPassCore plugin;
    private volatile List<Item> items = List.of();

    public ShopService(LankciPassCore plugin) {
        this.plugin = plugin;
    }

    public boolean load() {
        YamlConfiguration y = world.lankci.pass.util.Yaml.load(plugin, new File(plugin.getDataFolder(), "shop.yml"));
        if (y == null) {
            return false;
        }
        List<Item> list = new ArrayList<>();
        ConfigurationSection s = y.getConfigurationSection("items");
        if (s != null) {
            for (String id : s.getKeys(false)) {
                ConfigurationSection i = s.getConfigurationSection(id);
                if (i == null) {
                    continue;
                }
                long price = i.getLong("price", -1);
                if (price < 1 || price > 1_000_000_000L) {
                    plugin.getLogger().warning("shop.yml: " + id + ".price має бути 1..1000000000 - товар пропущено");
                    continue;
                }
                List<String> cmds = new ArrayList<>(i.getStringList("commands"));
                cmds.removeIf(c -> c == null || c.isBlank());
                if (cmds.isEmpty()) {
                    plugin.getLogger().warning("shop.yml: " + id + " не має commands - товар пропущено");
                    continue;
                }
                Material icon = Material.matchMaterial(i.getString("icon", "CHEST"));
                if (icon == null || !icon.isItem()) {
                    plugin.getLogger().warning("shop.yml: " + id + ".icon невідомий - використано CHEST");
                    icon = Material.CHEST;
                }
                list.add(new Item(id, i.getString("name", id), price, icon, new ArrayList<>(i.getStringList("lore")), cmds));
                if (list.size() >= 14) {
                    plugin.getLogger().warning("shop.yml: максимум 14 товарів у меню - решту пропущено");
                    break;
                }
            }
        }
        items = List.copyOf(list);
        return true;
    }

    public List<Item> items() {
        return items;
    }

    /** index з 1. */
    public Item get(int index) {
        List<Item> l = items;
        return index >= 1 && index <= l.size() ? l.get(index - 1) : null;
    }

    public List<String> validate() {
        List<String> out = new ArrayList<>();
        if (!plugin.settings().precheckCommands) {
            return out;
        }
        for (Item it : items) {
            String bad = plugin.rewards().firstUnknownCommand(it.commands());
            if (bad != null) {
                out.add("shop.yml: " + it.id() + " - невідома команда: " + bad);
            }
        }
        return out;
    }

    public void buy(Player player, int index) {
        Item it = get(index);
        if (it == null) {
            plugin.messages().send(player, "shop.no-item");
            return;
        }
        if (plugin.settings().precheckCommands && plugin.rewards().firstUnknownCommand(it.commands()) != null) {
            plugin.messages().send(player, "shop.bad-config");
            plugin.getLogger().severe("Товар магазину '" + it.id() + "' має невідому команду - покупку заблоковано.");
            return;
        }
        UUID uuid = player.getUniqueId();
        plugin.tokens().take(uuid, it.price(), "SHOP:" + it.id()).whenComplete((ok, ex) -> plugin.sync(() -> {
            Player p = plugin.getServer().getPlayer(uuid);
            if (ex != null) {
                if (p != null) {
                    plugin.messages().send(p, "shop.error");
                }
                return;
            }
            if (!Boolean.TRUE.equals(ok)) {
                if (p != null) {
                    plugin.messages().send(p, "shop.no-funds", "price", Text.num(it.price()));
                }
                return;
            }
            if (p == null) {
                // гравець вийшов: повертаємо жетони, нагороду не видаємо
                plugin.tokens().credit(uuid, it.price(), "SHOP_REFUND:" + it.id(), false);
                return;
            }
            int done = plugin.rewards().runCommands(p, it.commands(), Map.of("item", it.id()), "shop " + it.id());
            if (done == 0) {
                plugin.tokens().credit(uuid, it.price(), "SHOP_REFUND:" + it.id(), false);
                plugin.messages().send(p, "shop.refunded");
                return;
            }
            plugin.messages().send(p, "shop.bought", "name", it.name(), "price", Text.num(it.price()));
        }));
    }
}
