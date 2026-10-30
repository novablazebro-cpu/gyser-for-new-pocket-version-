package io.github.novablaze.novaguard.dupe;

import io.github.novablaze.novaguard.NovaGuardPlugin;
import io.github.novablaze.novaguard.Settings;
import java.util.ArrayList;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** Anti-dupe: overstacked items, entity-container dupes, and fingerprint scans. */
public final class DupeListener implements Listener, Runnable {

    private static final double MAX_CONTAINER_DISTANCE_SQUARED = 8 * 8;

    private final NovaGuardPlugin plugin;

    public DupeListener(NovaGuardPlugin plugin) {
        this.plugin = plugin;
    }

    // ---- Entity containers (donkey, llama, mule, chest boat, chest/hopper minecart) ----

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        guardEntityContainer(event.getWhoClicked(), event.getView().getTopInventory(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        guardEntityContainer(event.getWhoClicked(), event.getView().getTopInventory(), event);
    }

    private void guardEntityContainer(HumanEntity viewer, Inventory top, Cancellable event) {
        Settings settings = plugin.settings();
        if (!settings.dupeEnabled || !settings.entityContainerGuard) {
            return;
        }
        InventoryHolder holder = top.getHolder(false);
        if (!(holder instanceof Entity entity) || holder instanceof HumanEntity) {
            return;
        }
        boolean broken = !entity.isValid() || entity.isDead() || entity.getWorld() != viewer.getWorld()
            || entity.getLocation().distanceSquared(viewer.getLocation()) > MAX_CONTAINER_DISTANCE_SQUARED;
        if (!broken) {
            return;
        }
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> viewer.closeInventory());
        plugin.violations().dupeAlert(viewer.getName(),
            "used the inventory of a " + entity.getType().name().toLowerCase(java.util.Locale.ROOT)
                + " that is dead, gone or far away (blocked)");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityTeleport(EntityTeleportEvent event) {
        closeViewers(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        closeViewers(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        closeViewers(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        closeViewers(event.getVehicle());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Settings settings = plugin.settings();
        if (!settings.dupeEnabled || !settings.entityContainerGuard) {
            return;
        }
        Player player = event.getPlayer();
        InventoryHolder holder = player.getOpenInventory().getTopInventory().getHolder(false);
        if (holder instanceof Entity && !(holder instanceof HumanEntity)) {
            player.closeInventory();
        }
    }

    private void closeViewers(Entity entity) {
        Settings settings = plugin.settings();
        if (!settings.dupeEnabled || !settings.entityContainerGuard
            || !(entity instanceof InventoryHolder holder) || entity instanceof HumanEntity) {
            return;
        }
        for (HumanEntity viewer : new ArrayList<>(holder.getInventory().getViewers())) {
            viewer.closeInventory();
        }
    }

    // ---- Overstacked items + fingerprint scans ----

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Settings settings = plugin.settings();
        if (!settings.dupeEnabled || !settings.fixOverstacked) {
            return;
        }
        Item item = event.getItem();
        ItemStack stack = item.getItemStack();
        if (stack.getAmount() > stack.getMaxStackSize()) {
            int original = stack.getAmount();
            stack.setAmount(stack.getMaxStackSize());
            item.setItemStack(stack);
            plugin.violations().dupeAlert(player.getName(), "picked up an overstacked " + typeName(stack)
                + " (" + original + " > " + stack.getMaxStackSize() + ", trimmed)");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && ItemFingerprints.isStorage(event.getInventory())) {
            fixOverstacked(player, event.getInventory(), "opened container");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Inventory top = event.getInventory();
        // Next tick: items on the cursor and in crafting slots have been returned by then.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            fixOverstacked(player, player.getInventory(), "inventory");
            if (ItemFingerprints.isStorage(top)) {
                fixOverstacked(player, top, "closed container");
            }
            plugin.fingerprints().scan(player, top);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                fixOverstacked(player, player.getInventory(), "inventory");
                fixOverstacked(player, player.getEnderChest(), "ender chest");
                plugin.fingerprints().scan(player, null);
            }
        }, 40L);
    }

    /** Periodic scan of every online player, spread over ticks (2 players per tick) to avoid a lag spike. */
    @Override
    public void run() {
        int index = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            long delay = 1 + index++ / 2;
            Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.fingerprints().scan(player, null), delay);
        }
    }

    private void fixOverstacked(Player player, Inventory inventory, String where) {
        Settings settings = plugin.settings();
        if (!settings.dupeEnabled || !settings.fixOverstacked || inventory == null) {
            return;
        }
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.isEmpty() || item.getAmount() <= item.getMaxStackSize()) {
                continue;
            }
            int original = item.getAmount();
            item.setAmount(item.getMaxStackSize());
            inventory.setItem(slot, item);
            plugin.violations().dupeAlert(player.getName(), "overstacked " + typeName(item) + " in " + where
                + " (" + original + " > " + item.getMaxStackSize() + ", trimmed)");
        }
    }

    private static String typeName(ItemStack item) {
        return item.getType().name().toLowerCase(java.util.Locale.ROOT);
    }
}
