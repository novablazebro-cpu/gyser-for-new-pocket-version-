package io.github.novablaze.novaguard.dupe;

import io.github.novablaze.novaguard.NovaGuardPlugin;
import io.github.novablaze.novaguard.Settings;
import io.github.novablaze.novaguard.items.Inventories;
import io.github.novablaze.novaguard.items.ItemSanitizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
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
import org.bukkit.GameMode;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
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
        plugin.safety().run("anti-dupe",
            () -> guardEntityContainer(event.getWhoClicked(), event.getView().getTopInventory(), event));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        plugin.safety().run("anti-dupe",
            () -> guardEntityContainer(event.getWhoClicked(), event.getView().getTopInventory(), event));
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
            "used the inventory of a " + entity.getType().name().toLowerCase(Locale.ROOT)
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

    /**
     * Items on the ground are fixed before they reach the inventory: overstacked stacks are trimmed and illegal
     * enchantments removed. The player picking it up is never blamed (it may be loot from a killed cheater).
     * The pickup is cancelled once and the fixed item is picked up on the next tick.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Settings settings = plugin.settings();
        if (!settings.dupeEnabled && !settings.illegalEnabled) {
            return;
        }
        plugin.safety().run("anti-dupe", () -> {
            Item item = event.getItem();
            ItemStack stack = item.getItemStack();
            if (settings.dupeEnabled && settings.fixOverstacked && stack.getAmount() > stack.getMaxStackSize()) {
                int original = stack.getAmount();
                stack.setAmount(stack.getMaxStackSize());
                item.setItemStack(stack);
                plugin.violations().dupeAlert(player.getName(), "picked up an overstacked " + typeName(stack)
                    + " (" + original + " > " + stack.getMaxStackSize() + ", trimmed)");
            }
            if (Inventories.isCreative(player)) {
                return;
            }
            ItemSanitizer.Result result = plugin.fingerprints().sanitizer().sanitize(item.getItemStack());
            if (result.changed()) {
                event.setCancelled(true);
                item.setItemStack(result.item());
                String text = typeName(stack) + ": " + String.join(", ", result.changes());
                plugin.violations().dupeAlert(player.getName(), "picked up an illegal item, fixed before it reached"
                    + " the inventory (not blamed): " + text);
                player.sendMessage(MiniMessage.miniMessage().deserialize(settings.illegalFixedMessage,
                    Placeholder.unparsed("changes", text)));
            }
        });
    }

    /** Counts the pickup as an honest way for a copy to arrive (see ItemFingerprints). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickupDone(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            plugin.safety().run("anti-dupe", () -> {
                ItemStack picked = event.getItem().getItemStack();
                plugin.fingerprints().onPickup(player, picked);
                plugin.wealth().onPickup(player, picked, event.getItem().getThrower());
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        plugin.safety().run("anti-dupe", () -> {
            ItemStack dropped = event.getItemDrop().getItemStack();
            plugin.fingerprints().onDrop(event.getPlayer(), dropped);
            plugin.wealth().onDrop(event.getPlayer(), dropped);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        plugin.fingerprints().onRespawn(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        plugin.wealth().onDeath(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.wealth().reset(event.getPlayer().getUniqueId());
    }

    /** Breaking a container drops its contents: for wealth alerts this is not a gain when picked up again. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreakContainer(BlockBreakEvent event) {
        if (event.getBlock().getState(false) instanceof org.bukkit.block.Container container) {
            plugin.safety().run("anti-dupe", () -> plugin.wealth().onBreakContainer(event.getPlayer(), container.getInventory()));
        }
    }

    /**
     * Creative players can clone any item (middle click). A clone keeps the hidden ID, so it would look like a
     * dupe later: items coming out of the creative inventory lose their ID and get a new one.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreative(InventoryCreativeEvent event) {
        plugin.safety().run("anti-dupe", () -> {
            ItemStack cursor = event.getCursor();
            ItemStack stripped = plugin.fingerprints().ids().strip(cursor);
            if (stripped != cursor) {
                event.setCursor(stripped);
            }
        });
    }

    /** Leaving creative: items made in creative are fixed silently (creative players may make anything). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        // Creative items are not wealth: start counting again in the new game mode.
        plugin.wealth().reset(player.getUniqueId());
        GameMode from = player.getGameMode();
        GameMode to = event.getNewGameMode();
        boolean leavingCreative = (from == GameMode.CREATIVE || from == GameMode.SPECTATOR)
            && to != GameMode.CREATIVE && to != GameMode.SPECTATOR;
        if (leavingCreative && plugin.settings().illegalEnabled) {
            Bukkit.getScheduler().runTask(plugin, () -> plugin.safety().run("anti-dupe", () -> {
                if (player.isOnline()) {
                    plugin.fingerprints().sanitizeSilently(player);
                }
            }));
        }
    }

    /** Taking an item off an armor stand puts it straight into the hand (no pickup): fix it, blame nobody. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        Player player = event.getPlayer();
        if (plugin.settings().illegalEnabled) {
            Bukkit.getScheduler().runTask(plugin, () -> plugin.safety().run("anti-dupe", () -> {
                if (player.isOnline() && !Inventories.isCreative(player)) {
                    plugin.fingerprints().sanitizeSilently(player);
                }
            }));
        }
    }

    /** Anvils (and plugins that change anvil results) can never output an illegal item. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnvil(PrepareAnvilEvent event) {
        if (!plugin.settings().illegalEnabled || event.getResult() == null) {
            return;
        }
        plugin.safety().run("anti-dupe", () -> {
            ItemSanitizer.Result result = plugin.fingerprints().sanitizer().sanitize(event.getResult());
            if (result.changed()) {
                event.setResult(result.item());
            }
        });
    }

    /** Taking an illegal item out of a chest: it is fixed on the click, the player is not blamed. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStorageClick(InventoryClickEvent event) {
        if (!plugin.settings().illegalEnabled || event instanceof InventoryCreativeEvent
            || event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getView().getTopInventory())
            || !Inventories.isRealStorage(event.getClickedInventory())) {
            return;
        }
        plugin.safety().run("anti-dupe", () -> {
            ItemStack current = event.getCurrentItem();
            if (current == null || current.isEmpty()) {
                return;
            }
            ItemSanitizer.Result result = plugin.fingerprints().sanitizer().sanitize(current);
            if (result.changed()) {
                event.setCurrentItem(result.item());
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Inventory top = event.getInventory();
        if (!Inventories.isRealStorage(top)) {
            return;
        }
        plugin.safety().run("anti-dupe", () -> {
            fixOverstacked(player, top, "opened container");
            if (!Inventories.isCreative(player)) {
                List<String> fixes = plugin.fingerprints().sanitizeInventory(top);
                if (!fixes.isEmpty()) {
                    plugin.violations().dupeAlert(player.getName(), "opened a container with illegal item(s), fixed"
                        + " (not blamed): " + String.join("; ", fixes));
                }
            }
            plugin.fingerprints().snapshotOpen(player, top);
            plugin.wealth().onOpen(player, top);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Inventory top = event.getInventory();
        // Next tick: items on the cursor and in crafting slots have been returned by then.
        Bukkit.getScheduler().runTask(plugin, () -> plugin.safety().run("anti-dupe", () -> {
            if (!player.isOnline()) {
                return;
            }
            fixOverstacked(player, player.getInventory(), "inventory");
            if (Inventories.isRealStorage(top)) {
                fixOverstacked(player, top, "closed container");
            }
            // First what went in and out of the container (honest moves), then the scan that judges.
            plugin.fingerprints().recordPlacement(player, top);
            plugin.wealth().onClose(player, top);
            plugin.fingerprints().scan(player, top);
        }));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.safety().run("anti-dupe", () -> {
            if (player.isOnline()) {
                fixOverstacked(player, player.getInventory(), "inventory");
                fixOverstacked(player, player.getEnderChest(), "ender chest");
                plugin.fingerprints().scan(player, null);
            }
        }), 40L);
    }

    /** Periodic scan of every online player, spread over ticks (2 players per tick) to avoid a lag spike. */
    @Override
    public void run() {
        int index = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            long delay = 1 + index++ / 2;
            Bukkit.getScheduler().runTaskLater(plugin,
                () -> plugin.safety().run("anti-dupe", () -> plugin.fingerprints().scan(player, null)), delay);
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
        return item.getType().name().toLowerCase(Locale.ROOT);
    }
}
