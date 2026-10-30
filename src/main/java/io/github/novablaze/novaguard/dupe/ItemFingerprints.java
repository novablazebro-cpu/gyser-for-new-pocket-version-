package io.github.novablaze.novaguard.dupe;

import io.github.novablaze.novaguard.NovaGuardPlugin;
import io.github.novablaze.novaguard.Settings;
import io.github.novablaze.novaguard.math.Duplicates;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.BundleContents;
import io.papermc.paper.datacomponent.item.ItemContainerContents;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Gives valuable unstackable items a hidden random ID. A duplicated item carries the same ID as the
 * original, so two items with one ID existing at the same moment prove a dupe.
 */
public final class ItemFingerprints {

    /** One tagged item that was found, and where. {@code inventory} is null for items inside a shulker/bundle. */
    private record Found(String id, Inventory inventory, int slot, String where) {
    }

    private final NovaGuardPlugin plugin;
    private final NamespacedKey key;
    /** Item ID -> the player whose storage it was last seen in. */
    private final Map<String, UUID> lastHolder = new HashMap<>();

    public ItemFingerprints(NovaGuardPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "uid");
    }

    /**
     * Tags new valuable items and looks for duplicates in the player's inventory, ender chest and
     * (optionally) an open storage container.
     */
    public void scan(Player player, Inventory openContainer) {
        Settings settings = plugin.settings();
        if (!settings.dupeEnabled || !settings.fingerprintsEnabled || !player.isOnline() || isCreative(player)) {
            return;
        }
        // Replacing an item that is being used (trident charge, shield, elytra flight) would interrupt it.
        boolean tagNew = !player.isHandRaised() && !player.isGliding();
        List<Found> found = new ArrayList<>();
        collect(player.getInventory(), "inventory", found, tagNew);
        Inventory enderChest = player.getEnderChest();
        collect(enderChest, "ender chest", found, true);
        if (openContainer != null && openContainer != enderChest && isStorage(openContainer)) {
            collect(openContainer, "open " + openContainer.getType().name().toLowerCase(java.util.Locale.ROOT), found, true);
        }

        List<String> ids = new ArrayList<>(found.size());
        for (Found f : found) {
            ids.add(f.id());
        }
        Set<String> repeated = Duplicates.find(ids);
        for (String id : repeated) {
            reportSameScan(player, id, found, settings.removeDuplicates);
        }

        UUID self = player.getUniqueId();
        Set<String> checked = new HashSet<>();
        for (Found f : found) {
            if (!checked.add(f.id())) {
                continue;
            }
            UUID previous = lastHolder.put(f.id(), self);
            if (previous == null || previous.equals(self) || repeated.contains(f.id())) {
                continue;
            }
            Player other = Bukkit.getPlayer(previous);
            if (other != null && other.isOnline() && !isCreative(other) && holds(other, f.id())) {
                plugin.violations().dupeAlert(player.getName(), "has the same " + itemName(f)
                    + " as " + other.getName() + " (item ID " + shortId(f.id()) + ")");
            }
        }
    }

    /** Drops what we remember about a player who left, so memory stays small. */
    public void forget(UUID player) {
        lastHolder.values().removeIf(player::equals);
    }

    private void reportSameScan(Player player, String id, List<Found> found, boolean remove) {
        List<String> places = new ArrayList<>();
        boolean first = true;
        int removed = 0;
        for (Found f : found) {
            if (!f.id().equals(id)) {
                continue;
            }
            places.add(f.where());
            if (first) {
                first = false;
            } else if (remove && f.inventory() != null) {
                f.inventory().setItem(f.slot(), null);
                removed++;
            }
        }
        String detail = places.size() + " copies of one item (ID " + shortId(id) + ") in " + String.join(", ", places);
        if (removed > 0) {
            detail += ", removed " + removed;
        }
        plugin.violations().dupeAlert(player.getName(), detail);
    }

    private boolean holds(Player player, String id) {
        List<Found> found = new ArrayList<>();
        collect(player.getInventory(), "inventory", found, false);
        collect(player.getEnderChest(), "ender chest", found, false);
        for (Found f : found) {
            if (f.id().equals(id)) {
                return true;
            }
        }
        return false;
    }

    private void collect(Inventory inventory, String where, List<Found> out, boolean tagNew) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.isEmpty()) {
                continue;
            }
            String id = readId(item);
            if (id == null && tagNew && eligible(item)) {
                String newId = UUID.randomUUID().toString();
                item.editPersistentDataContainer(pdc -> pdc.set(key, PersistentDataType.STRING, newId));
                inventory.setItem(slot, item);
                id = newId;
            }
            if (id != null) {
                out.add(new Found(id, inventory, slot, where));
            }
            collectNested(item, where, out);
        }
    }

    /** Items inside shulker boxes and bundles are read (never re-tagged). */
    private void collectNested(ItemStack holder, String where, List<Found> out) {
        List<ItemStack> inner = null;
        ItemContainerContents container = holder.getData(DataComponentTypes.CONTAINER);
        if (container != null) {
            inner = container.contents();
        } else {
            BundleContents bundle = holder.getData(DataComponentTypes.BUNDLE_CONTENTS);
            if (bundle != null) {
                inner = bundle.contents();
            }
        }
        if (inner == null) {
            return;
        }
        String place = where + " (inside " + holder.getType().name().toLowerCase(java.util.Locale.ROOT) + ")";
        for (ItemStack item : inner) {
            if (item == null || item.isEmpty()) {
                continue;
            }
            String id = readId(item);
            if (id != null) {
                out.add(new Found(id, null, -1, place));
            }
        }
    }

    private String readId(ItemStack item) {
        return item.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    private boolean eligible(ItemStack item) {
        return item.getMaxStackSize() == 1 && plugin.settings().fingerprintMaterials.contains(item.getType());
    }

    /** Real block storage. Plugin menus, anvils, crafting views and other players' inventories are excluded. */
    public static boolean isStorage(Inventory inventory) {
        InventoryType type = inventory.getType();
        return type == InventoryType.CHEST || type == InventoryType.BARREL || type == InventoryType.SHULKER_BOX
            || type == InventoryType.HOPPER || type == InventoryType.DISPENSER || type == InventoryType.DROPPER;
    }

    private static boolean isCreative(Player player) {
        return player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR;
    }

    private static String itemName(Found found) {
        return "item in " + found.where();
    }

    private static String shortId(String id) {
        return id.length() > 8 ? id.substring(0, 8) : id;
    }
}
