package io.github.novablaze.novaguard.items;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** Small shared facts about inventories and players, used by the anti-dupe, illegal-item and cleanup code. */
public final class Inventories {

    private static final int OFFHAND_SLOT = 40;
    private static final int CHEST_SLOT = 38;

    private Inventories() {
    }

    /**
     * Real block/entity storage. Plugin menus (shops, auction houses, kit previews) are excluded: they often show
     * copies of real items, which must never count as dupes or be changed.
     */
    public static boolean isRealStorage(Inventory inventory) {
        if (inventory == null || inventory.getType() == InventoryType.ENDER_CHEST
            || inventory.getType() == InventoryType.PLAYER || inventory.getType() == InventoryType.CRAFTING
            || inventory.getType() == InventoryType.CREATIVE) {
            return false;
        }
        InventoryHolder holder = inventory.getHolder(false);
        return holder instanceof Container || holder instanceof DoubleChest
            || holder instanceof Entity && !(holder instanceof HumanEntity);
    }

    /** Creative and spectator players may make anything: they are never scanned or blamed. */
    public static boolean isCreative(Player player) {
        return player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR;
    }

    /** Player inventory slots that must not be replaced right now: replacing an item in use interrupts it. */
    public static Set<Integer> busySlots(Player player) {
        Set<Integer> busy = new HashSet<>();
        if (player.isHandRaised()) {
            busy.add(player.getInventory().getHeldItemSlot());
            busy.add(OFFHAND_SLOT);
        }
        if (player.isGliding()) {
            busy.add(CHEST_SLOT);
        }
        return busy;
    }

    public static String name(ItemStack item) {
        return item.getType().name().toLowerCase(Locale.ROOT);
    }

    public static String name(Inventory inventory) {
        return inventory.getType().name().toLowerCase(Locale.ROOT);
    }

    /** "world 10 64 -20" */
    public static String where(Location location) {
        return (location.getWorld() == null ? "?" : location.getWorld().getName()) + " " + location.getBlockX() + " "
            + location.getBlockY() + " " + location.getBlockZ();
    }
}
