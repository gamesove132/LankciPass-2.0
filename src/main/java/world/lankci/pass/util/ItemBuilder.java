package world.lankci.pass.util;

import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Зручний будівельник предметів для GUI. */
public final class ItemBuilder {
    private final Material material;
    private String name = " ";
    private final List<String> lore = new ArrayList<>();
    private boolean glow;
    private int amount = 1;
    private OfflinePlayer head;

    public ItemBuilder(Material material) {
        this.material = material == null ? Material.STONE : material;
    }

    public ItemBuilder name(String name) {
        this.name = name;
        return this;
    }

    public ItemBuilder lore(String... lines) {
        lore.addAll(Arrays.asList(lines));
        return this;
    }

    public ItemBuilder lore(List<String> lines) {
        lore.addAll(lines);
        return this;
    }

    public ItemBuilder glow(boolean glow) {
        this.glow = glow;
        return this;
    }

    public ItemBuilder amount(int amount) {
        this.amount = Math.max(1, Math.min(64, amount));
        return this;
    }

    public ItemBuilder head(OfflinePlayer player) {
        this.head = player;
        return this;
    }

    public ItemStack build() {
        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(Text.c(name));
        if (!lore.isEmpty()) {
            meta.lore(Text.lines(lore));
        }
        if (glow) {
            meta.setEnchantmentGlintOverride(true);
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP, ItemFlag.HIDE_ENCHANTS);
        if (head != null && meta instanceof SkullMeta skull) {
            skull.setOwningPlayer(head);
        }
        item.setItemMeta(meta);
        return item;
    }
}
