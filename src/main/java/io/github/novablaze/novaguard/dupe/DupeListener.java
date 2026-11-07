package io.github.novablaze.novaguard.dupe;

import io.github.novablaze.novaguard.NovaGuardPlugin;
import io.github.novablaze.novaguard.Settings;
import java.util.ArrayList;
import java.io.File;
import java.util.List;
import java.util.function.Function;
import io.github.novablaze.novaguard.math.Box;
import io.github.novablaze.novaguard.math.Geometry;
import io.github.novablaze.novaguard.movement.Exemptions;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
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
 *       inventories (chest, shulker, barrel) that were broken, replaced or are far away cannot be clicked,
 *       and hoppers cannot pick up an item entity that was already removed.</li>
 *   <li>Impossible items are repaired: overstacked stacks are trimmed.</li>
 *   <li>Unknown dupes are still caught afterwards: valuable items carry a hidden ID
 *       ({@link ItemFingerprints}), and two copies of one ID prove a dupe whatever the method was.</li>
 * </ol>
 */
public final class DupeListener implements Listener, Runnable {

    private static final double MAX_CONTAINER_DISTANCE_SQUARED = 8 * 8;

    private final NovaGuardPlugin plugin;
    /** Player -> the storage block(s) behind their open window. */
    private final java.util.Map<java.util.UUID, List<BlockMark>> openBlocks = new java.util.HashMap<>();

    public DupeListener(NovaGuardPlugin plugin) {
        this.plugin = plugin;
    }

    // ---- Containers that no longer exist (entity: donkey, llama, chest boat, minecart; block: chest, shulker...) ----

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        plugin.safety().run("anti-dupe",
            () -> guardContainer(event.getWhoClicked(), event.getView().getTopInventory(), event));
    }

    /**
     * Who put which item into a chest, and who took it out. Taking out an item that ANOTHER player put in is a
     * handover: the undupe keeps the receiver's copy if the giver still has one.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTake(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(event.getWhoClicked() instanceof Player player) || !ItemFingerprints.isStorage(top)) {
            return;
        }
        plugin.safety().run("anti-dupe", () -> {
            ItemFingerprints fingerprints = plugin.fingerprints();
            Inventory clicked = event.getClickedInventory();
            if (clicked == top) {
                ItemStack taken = event.getCurrentItem();
                fingerprints.received(player, taken, fingerprints.depositor(taken));
                fingerprints.deposited(player, event.getCursor()); // placing the cursor item into the chest
                if (event.getHotbarButton() >= 0) {
                    fingerprints.deposited(player, player.getInventory().getItem(event.getHotbarButton()));
                }
            } else if (clicked != null && event.isShiftClick()) {
                fingerprints.deposited(player, event.getCurrentItem()); // shift-click from the own inventory
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeposit(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (event.getWhoClicked() instanceof Player player && ItemFingerprints.isStorage(top)
            && event.getRawSlots().stream().anyMatch(slot -> slot < top.getSize())) {
            plugin.safety().run("anti-dupe", () -> plugin.fingerprints().deposited(player, event.getOldCursor()));
        }
    }

    /** Picking up an item another player threw is a handover; picking up one's own item is not. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickedUp(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            plugin.safety().run("anti-dupe", () -> plugin.fingerprints().received(player,
                event.getItem().getItemStack(), event.getItem().getThrower()));
        }
    }

    /** Remembers the chest/barrel/shulker block behind an open window: the holder alone cannot show it broke. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpenBlock(InventoryOpenEvent event) {
        InventoryHolder holder = event.getInventory().getHolder(false);
        List<BlockMark> marks = new ArrayList<>();
        if (holder instanceof DoubleChest chest) {
            if (chest.getLeftSide(false) instanceof BlockState left) {
                marks.add(BlockMark.of(left));
            }
            if (chest.getRightSide(false) instanceof BlockState right) {
                marks.add(BlockMark.of(right));
            }
        } else if (holder instanceof BlockState state) {
            marks.add(BlockMark.of(state));
        }
        if (marks.isEmpty()) {
            openBlocks.remove(event.getPlayer().getUniqueId());
        } else {
            openBlocks.put(event.getPlayer().getUniqueId(), marks);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCloseBlock(InventoryCloseEvent event) {
        openBlocks.remove(event.getPlayer().getUniqueId());
    }

    /** One storage block as it was when its window opened. */
    private record BlockMark(World world, int x, int y, int z, Material type) {
        static BlockMark of(BlockState state) {
            return new BlockMark(state.getWorld(), state.getX(), state.getY(), state.getZ(), state.getType());
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
        if (settings.blockContainerGuard && guardBlockContainer(viewer, event)) {
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
    private boolean guardBlockContainer(HumanEntity viewer, Cancellable event) {
        List<BlockMark> marks = openBlocks.get(viewer.getUniqueId());
        if (marks == null) {
            return false; // not a block window (or opened before NovaGuard started)
        }
        Location eye = viewer.getEyeLocation();
        // Vanilla keeps a container open while the eye is within block reach + 4 blocks of the block.
        double reach = viewer instanceof Player player
            ? Exemptions.attribute(player, Attribute.BLOCK_INTERACTION_RANGE, 4.5) : 4.5;
        double maxDistance = reach + 4.0;
        for (BlockMark mark : marks) {
            Block block = mark.world().getBlockAt(mark.x(), mark.y(), mark.z());
            double distance = mark.world() != viewer.getWorld() ? Double.MAX_VALUE
                : Geometry.distanceToBox(eye.getX(), eye.getY(), eye.getZ(),
                    new Box(mark.x(), mark.y(), mark.z(), mark.x() + 1, mark.y() + 1, mark.z() + 1));
            boolean broken = block.getType() != mark.type() || !(block.getState(false) instanceof Container)
                || distance > maxDistance;
            if (broken) {
                event.setCancelled(true);
                openBlocks.remove(viewer.getUniqueId());
                Bukkit.getScheduler().runTask(plugin, () -> viewer.closeInventory());
                plugin.violations().dupeAlert(viewer.getName(), "used the inventory of a "
                    + mark.type().name().toLowerCase(java.util.Locale.ROOT)
                    + " that is broken, replaced or far away (blocked)");
                return true;
            }
        }
        return false;
    }

    // No InventoryMoveItemEvent listener on purpose: Paper skips building that event for every hopper
    // transfer while nobody listens, and vanilla hoppers already ignore dead entities when they look for a
    // container. Listening would cost TPS on every hopper on the server for nothing.

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
        plugin.fingerprints().cleanup();
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
