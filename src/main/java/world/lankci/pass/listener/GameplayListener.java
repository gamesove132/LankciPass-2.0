package world.lankci.pass.listener;

import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import world.lankci.pass.LankciPassCore;
import world.lankci.pass.quest.QuestType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Усі ігрові події, що впливають на жетони та квести. Режим визначається світом, де сталася подія. */
public final class GameplayListener implements Listener {
    private record BlockKey(UUID world, int x, int y, int z) {
        static BlockKey of(Block b) {
            return new BlockKey(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ());
        }
    }

    private static final int CACHE_LIMIT = 50_000;

    private final LankciPassCore plugin;
    private final NamespacedKey spawnerKey;
    /** Блоки, поставлені гравцями (щоб поставити-зламати не рахувалось як квест). */
    private final Map<BlockKey, Boolean> placed = lru(CACHE_LIMIT);
    /** Смерті, які вже зараховано (захист від дубльованих подій). */
    private final Map<UUID, Boolean> processedDeaths = lru(4_000);

    public GameplayListener(LankciPassCore plugin) {
        this.plugin = plugin;
        this.spawnerKey = new NamespacedKey(plugin, "spawner");
    }

    private static <K> Map<K, Boolean> lru(int cap) {
        return new LinkedHashMap<>(256, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, Boolean> eldest) {
                return size() > cap;
            }
        };
    }

    // ------------------------------------------------------------------ гравці

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        plugin.pvp().onJoin(e.getPlayer().getUniqueId());
        plugin.players().onJoin(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        plugin.pvp().onQuit(id);
        plugin.viewModes().remove(id);
        plugin.questService().flush().whenComplete((r, ex) -> plugin.sync(() -> {
            if (plugin.getServer().getPlayer(id) == null) {
                plugin.cache().remove(id);
            }
        }));
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent e) {
        plugin.modes().bind(e.getWorld());
    }

    // ------------------------------------------------------------------ вбивства

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER) {
            e.getEntity().getPersistentDataContainer().set(spawnerKey, PersistentDataType.BYTE, (byte) 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMobDeath(EntityDeathEvent e) {
        LivingEntity dead = e.getEntity();
        if (dead instanceof Player) {
            return;
        }
        Player killer = dead.getKiller();
        if (killer == null || !processedDeaths.isEmpty() && processedDeaths.containsKey(dead.getUniqueId())) {
            return;
        }
        processedDeaths.put(dead.getUniqueId(), Boolean.TRUE);
        if (plugin.settings().ignoreCreative && killer.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        if (plugin.settings().ignoreSpawnerMobs
                && dead.getPersistentDataContainer().has(spawnerKey, PersistentDataType.BYTE)) {
            return;
        }
        String mode = plugin.modes().modeOf(dead.getWorld());
        if (mode == null) {
            return;
        }
        plugin.questService().event(killer, mode, QuestType.KILL_MOB, dead.getType(), null, 1);
        plugin.questService().event(killer, mode, QuestType.KILL_UNIQUE_MOB, dead.getType(), null, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        if (killer != null) {
            plugin.pvp().handleDeath(victim, killer);
        }
    }

    // ------------------------------------------------------------------ блоки, риболовля, крафт

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (plugin.settings().ignorePlacedBlocks) {
            placed.put(BlockKey.of(e.getBlockPlaced()), Boolean.TRUE);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlock();
        boolean wasPlaced = placed.remove(BlockKey.of(b)) != null;
        if (wasPlaced && plugin.settings().ignorePlacedBlocks) {
            return;
        }
        if (plugin.settings().ignoreCreative && p.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        String mode = plugin.modes().modeOf(b.getWorld());
        if (mode != null) {
            plugin.questService().event(p, mode, QuestType.BREAK_BLOCK, null, b.getType(), 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            return;
        }
        Entity caught = e.getCaught();
        String mode = plugin.modes().modeOf(e.getPlayer().getWorld());
        if (mode == null) {
            return;
        }
        org.bukkit.Material mat = null;
        if (caught instanceof org.bukkit.entity.Item item) {
            mat = item.getItemStack().getType();
        }
        plugin.questService().event(e.getPlayer(), mode, QuestType.FISH, null, mat, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        ItemStack result = e.getRecipe().getResult();
        String mode = plugin.modes().modeOf(p.getWorld());
        if (mode == null || result.getType().isAir()) {
            return;
        }
        // shift-клік рахується як одне крафтування - це занижує, але виключає накрутку
        plugin.questService().event(p, mode, QuestType.CRAFT_ITEM, null, result.getType(), Math.max(1, result.getAmount()));
    }
}
