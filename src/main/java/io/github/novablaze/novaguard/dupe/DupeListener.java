package io.github.novablaze.novaguard.dupe;

import io.github.novablaze.novaguard.NovaGuardPlugin;
import io.github.novablaze.novaguard.Settings;
import java.util.ArrayList;
import java.io.File;
import java.util.List;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.configuration.file.YamlConfiguration;
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
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * Anti-dupe, in layers (no single layer can stop every dupe, together they close the known kinds):
 * <ol>
 *   <li>Paper's own fixes stay on: {@link #auditPaperConfig()} warns when a setting re-opens a dupe.</li>
 *   <li>Containers that no longer exist: entity inventories (donkey, chest boat, minecart) and block
 *       inventories (chest, shulker, barrel) that were broken, moved or are far away cannot be clicked,
 *       and hoppers cannot move items into or out of a dead entity.</li>
 *   <li>Impossible items are repaired: overstacked stacks are trimmed.</li>
 *   <li>Unknown dupes are still caught afterwards: valuable items carry a hidden ID
 *       ({@link ItemFingerprints}), and two copies of one ID prove a dupe whatever the method was.</li>
 * </ol>
 */
public final class DupeListener implements Listener, Runnable {

    private static final double MAX_CONTAINER_DISTANCE_SQUARED = 8 * 8;

    private final NovaGuardPlugin plugin;

    public DupeListener(NovaGuardPlugin plugin) {
        this.plugin = plugin;
    }

    // ---- Containers that no longer exist (entity: donkey, llama, chest boat, minecart; block: chest, shulker...) ----

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        plugin.safety().run("anti-dupe",
            () -> guardContainer(event.getWhoClicked(), event.getView().getTopInventory(), event));
    }

    /** Taking an item out of a chest is a normal way to receive it: the undupe keeps that player's copy. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTake(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (event.getWhoClicked() instanceof Player player && event.getClickedInventory() == top
            && ItemFingerprints.isStorage(top)) {
            plugin.safety().run("anti-dupe", () -> plugin.fingerprints().received(player, event.getCurrentItem()));
        }
    }

    /** Picking an item up from the ground is a normal way to receive it. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickedUp(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            plugin.safety().run("anti-dupe", () -> plugin.fingerprints().received(player, event.getItem().getItemStack()));
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        plugin.safety().run("anti-dupe",
            () -> guardContainer(event.getWhoClicked(), event.getView().getTopInventory(), event));
    }

    private void guardContainer(HumanEntity viewer, Inventory top, Cancellable event) {
        Settings settings = plugin.settings();
        if (!settings.dupeEnabled) {
            return;
        }
        InventoryHolder holder = top.getHolder(false);
        if (settings.blockContainerGuard && guardBlockContainer(viewer, holder, event)) {
            return;
        }
        if (!settings.entityContainerGuard) {
            return;
        }
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

    /**
     * A chest, barrel or shulker box whose block is gone (broken, exploded, pushed, replaced) or far away
     * while its window is still open. Clicking it anyway is how "break the chest while it is open" and
     * remote-container dupes work. Plugin menus have no block holder and are never touched.
     *
     * @return true when the click was blocked
     */
    private boolean guardBlockContainer(HumanEntity viewer, InventoryHolder holder, Cancellable event) {
        BlockState[] halves;
        if (holder instanceof DoubleChest chest
            && chest.getLeftSide(false) instanceof BlockState left
            && chest.getRightSide(false) instanceof BlockState right) {
            halves = new BlockState[] {left, right};
        } else if (holder instanceof BlockState state) {
            halves = new BlockState[] {state};
        } else {
            return false;
        }
        for (BlockState state : halves) {
            Block block = state.getBlock();
            boolean broken = block.getWorld() != viewer.getWorld()
                || block.getType() != state.getType()
                || !(block.getState(false) instanceof Container)
                || block.getLocation().add(0.5, 0.5, 0.5).distanceSquared(viewer.getLocation())
                    > MAX_CONTAINER_DISTANCE_SQUARED;
            if (broken) {
                event.setCancelled(true);
                Bukkit.getScheduler().runTask(plugin, () -> viewer.closeInventory());
                plugin.violations().dupeAlert(viewer.getName(), "used the inventory of a "
                    + state.getType().name().toLowerCase(java.util.Locale.ROOT)
                    + " that is broken, moved or far away (blocked)");
                return true;
            }
        }
        return false;
    }

    /** Hoppers and hopper minecarts may not move items into or out of an entity that is dead or removed. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        Settings settings = plugin.settings();
        if (settings.dupeEnabled && settings.entityContainerGuard
            && (deadEntity(event.getSource()) || deadEntity(event.getDestination()))) {
            event.setCancelled(true);
        }
    }

    /** A hopper may not pick up an item entity that was already removed (picked up or merged elsewhere). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent event) {
        Settings settings = plugin.settings();
        if (settings.dupeEnabled && settings.entityContainerGuard
            && (!event.getItem().isValid() || deadEntity(event.getInventory()))) {
            event.setCancelled(true);
        }
    }

    private static boolean deadEntity(Inventory inventory) {
        return inventory.getHolder(false) instanceof Entity entity && !(entity instanceof HumanEntity)
            && (!entity.isValid() || entity.isDead());
    }

    // ---- Paper settings ----

    /**
     * Warns in red (once, at startup) about every Paper setting that switches off one of Paper's own dupe
     * fixes. NovaGuard cannot patch the server, so the honest thing is to tell the admin.
     *
     * @return how many risky settings were found
     */
    public int auditPaperConfig() {
        int found = report("config/paper-global.yml", new File("config", "paper-global.yml"), true);
        found += report("config/paper-world-defaults.yml", new File("config", "paper-world-defaults.yml"), false);
        for (World world : Bukkit.getWorlds()) {
            File own = new File(world.getWorldFolder(), "paper-world.yml");
            found += report(world.getName() + "/paper-world.yml", own, false);
        }
        if (found == 0) {
            plugin.getLogger().info("Paper's built-in dupe fixes are all switched on.");
        }
        return found;
    }

    private int report(String name, File file, boolean global) {
        if (!file.isFile()) {
            return 0;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        Function<String, Object> get = yaml::get;
        List<String> risks = global ? ConfigRisks.global(get) : ConfigRisks.world(get);
        for (String risk : risks) {
            plugin.getLogger().severe("DUPE RISK in " + name + ": " + risk);
        }
        return risks.size();
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
        Bukkit.getScheduler().runTask(plugin, () -> plugin.safety().run("anti-dupe", () -> {
            if (!player.isOnline()) {
                return;
            }
            fixOverstacked(player, player.getInventory(), "inventory");
            if (ItemFingerprints.isStorage(top)) {
                fixOverstacked(player, top, "closed container");
            }
            plugin.fingerprints().scan(player, top);
        }));
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
        return item.getType().name().toLowerCase(java.util.Locale.ROOT);
    }
}
