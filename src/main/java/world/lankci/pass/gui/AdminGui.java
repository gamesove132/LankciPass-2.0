package world.lankci.pass.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import world.lankci.pass.LankciPassCore;
import world.lankci.pass.season.SeasonDef;
import world.lankci.pass.season.SeasonRun;
import world.lankci.pass.util.ItemBuilder;
import world.lankci.pass.util.Text;
import world.lankci.pass.util.TimeUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Адміністративне меню керування сезонами (/lankcipass admin). Усі небезпечні дії проходять через
 * екран підтвердження з попередженням. Працює без DeluxeMenus і PlaceholderAPI.
 */
public final class AdminGui implements Listener {
    private static final int[] SEASON_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
    private static final int CLOSE_SLOT = 49;
    private static final int INFO_SLOT = 4;

    private abstract static class Holder implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private static final class ListHolder extends Holder {
        final List<String> slotToSeason = new ArrayList<>();
    }

    private static final class ConfirmHolder extends Holder {
        Runnable action;
    }

    private final LankciPassCore plugin;

    public AdminGui(LankciPassCore plugin) {
        this.plugin = plugin;
    }

    public void open(Player p) {
        if (!p.hasPermission("lankcipass.admin.season")) {
            plugin.messages().send(p, "common.no-permission");
            return;
        }
        ListHolder h = new ListHolder();
        Inventory inv = Bukkit.createInventory(h, 54, Text.c("&8⭐ &5&lLANKCIPASS &8| &dКерування сезонами"));
        h.inv = inv;
        ItemStack filler = new ItemBuilder(Material.BLACK_STAINED_GLASS_PANE).name(" ").build();
        ItemStack accent = new ItemBuilder(Material.PURPLE_STAINED_GLASS_PANE).name(" ").build();
        for (int i = 0; i < 54; i++) {
            inv.setItem(i, i < 9 || i >= 45 ? accent : filler);
        }
        for (int s : SEASON_SLOTS) {
            inv.setItem(s, null);
        }
        SeasonRun active = plugin.seasons().activeRun();
        SeasonDef activeDef = plugin.seasons().defOf(active);
        long left = plugin.seasons().remainingMs();
        List<String> info = new ArrayList<>();
        info.add("&7Активний сезон: " + (activeDef == null ? "&cнемає" : activeDef.coloredName()));
        if (active != null) {
            info.add("&7Запуск: &f#" + active.runId() + " &8(" + active.progressMode() + ")");
            info.add("&7Завершення: &f" + plugin.seasons().fmt(active.endAt()));
            info.add("&7Залишилось: " + TimeUtil.timerColor(left, plugin.settings()) + TimeUtil.duration(left));
        }
        info.add("");
        info.add("&8ЛКМ - запуск (новий незалежний)");
        info.add("&8Shift+ЛКМ - запуск із повним скиданням");
        info.add("&8ПКМ - відновити старий запуск");
        info.add("&8Shift+ПКМ - архівувати");
        info.add("&8СКМ - додати в чергу");
        inv.setItem(INFO_SLOT, new ItemBuilder(Material.CLOCK).name("&d&lСезони LankciPass").lore(info).build());

        int idx = 0;
        for (SeasonDef d : plugin.seasons().allDefs()) {
            if (idx >= SEASON_SLOTS.length) {
                break;
            }
            String state = plugin.seasons().stateOf(d);
            List<String> lore = new ArrayList<>();
            lore.add("&7Номер: &f" + d.number() + " &8| &7id: &f" + d.id());
            if (!d.theme().isBlank()) {
                lore.add("&7Тема: &f" + d.theme());
            }
            lore.add("&7Статус: " + stateLabel(state));
            lore.add("&7Рівнів: &f" + d.levels().size() + " &8| &7Тривалість: &f" + d.durationDays() + " дн.");
            lore.add("&7Запусків в історії: &f" + plugin.seasons().runsOf(d.id()).size());
            for (SeasonRun r : plugin.seasons().runsOf(d.id())) {
                if (r.status() == world.lankci.pass.season.RunStatus.SCHEDULED) {
                    lore.add("&7Початок за планом: &f" + plugin.seasons().fmt(r.startAt()));
                }
            }
            lore.add("");
            lore.add("&eЛКМ &7- запустити (новий)");
            lore.add("&eShift+ЛКМ &7- запустити зі скиданням");
            lore.add("&eПКМ &7- відновити");
            lore.add("&eShift+ПКМ &7- архівувати");
            lore.add("&eСКМ &7- у чергу");
            inv.setItem(SEASON_SLOTS[idx], new ItemBuilder(d.icon()).name(d.coloredName())
                    .lore(lore).glow("ACTIVE".equals(state)).build());
            h.slotToSeason.add(d.id());
            idx++;
        }
        inv.setItem(CLOSE_SLOT, new ItemBuilder(Material.BARRIER).name("&cЗакрити").build());
        p.openInventory(inv);
    }

    private static String stateLabel(String state) {
        return switch (state) {
            case "ACTIVE" -> "&a&lАКТИВНИЙ";
            case "SCHEDULED" -> "&e&lЗАПЛАНОВАНИЙ";
            case "ARCHIVED" -> "&8&lАРХІВ";
            case "ENDED" -> "&7Завершений";
            default -> "&7Не запускався";
        };
    }

    private void openConfirm(Player p, List<String> lines, Runnable action) {
        ConfirmHolder h = new ConfirmHolder();
        Inventory inv = Bukkit.createInventory(h, 27, Text.c("&8⚠ &c&lПідтвердження"));
        h.inv = inv;
        h.action = action;
        ItemStack filler = new ItemBuilder(Material.BLACK_STAINED_GLASS_PANE).name(" ").build();
        for (int i = 0; i < 27; i++) {
            inv.setItem(i, filler);
        }
        inv.setItem(11, new ItemBuilder(Material.LIME_CONCRETE).name("&a&lПІДТВЕРДИТИ")
                .lore("&7Виконати операцію.", "&cЦю дію складно скасувати.").build());
        inv.setItem(13, new ItemBuilder(Material.PAPER).name("&e&lПопередження").lore(lines).build());
        inv.setItem(15, new ItemBuilder(Material.RED_CONCRETE).name("&c&lСКАСУВАТИ").lore("&7Нічого не змінювати.").build());
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) {
            return;
        }
        if (!p.hasPermission("lankcipass.admin.season")) {
            p.closeInventory();
            plugin.messages().send(p, "common.no-permission");
            return;
        }
        int slot = e.getRawSlot();
        if (holder instanceof ConfirmHolder ch) {
            if (slot == 11) {
                Runnable a = ch.action;
                p.closeInventory();
                a.run();
            } else if (slot == 15) {
                p.closeInventory();
                plugin.messages().send(p, "confirm.cancelled");
            }
            return;
        }
        ListHolder lh = (ListHolder) holder;
        if (slot == CLOSE_SLOT) {
            p.closeInventory();
            return;
        }
        int idx = -1;
        for (int i = 0; i < SEASON_SLOTS.length; i++) {
            if (SEASON_SLOTS[i] == slot) {
                idx = i;
                break;
            }
        }
        if (idx < 0 || idx >= lh.slotToSeason.size()) {
            return;
        }
        String id = lh.slotToSeason.get(idx);
        SeasonDef d = plugin.seasons().def(id);
        if (d == null) {
            return;
        }
        ClickType ct = e.getClick();
        switch (ct) {
            case LEFT -> confirm(p, d, "start-new", () -> plugin.reportOp(p, plugin.seasons().start(id, false, null)));
            case SHIFT_LEFT -> confirm(p, d, "start-reset", () -> plugin.reportOp(p, plugin.seasons().start(id, true, null)));
            case RIGHT -> confirm(p, d, "restore", () -> plugin.reportOp(p, plugin.seasons().restore(id, null)));
            case SHIFT_RIGHT -> confirm(p, d, "archive", () -> plugin.reportOp(p, plugin.seasons().archive(id)));
            case MIDDLE -> {
                p.closeInventory();
                plugin.reportOp(p, plugin.seasons().schedule(id));
            }
            default -> { /* інші кліки ігноруємо */ }
        }
    }

    private void confirm(Player p, SeasonDef d, String action, Runnable run) {
        openConfirm(p, plugin.warningLines(action, d), run);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) {
            e.setCancelled(true);
        }
    }
}
