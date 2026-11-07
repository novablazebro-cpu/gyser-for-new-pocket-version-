package io.github.novablaze.novaguard.dupe;

import io.github.novablaze.novaguard.NovaGuardPlugin;
import io.github.novablaze.novaguard.Settings;
import io.github.novablaze.novaguard.math.DupeResolver;
import io.github.novablaze.novaguard.math.DupeResolver.Copy;
import io.github.novablaze.novaguard.math.DupeResolver.Verdict;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.BundleContents;
import io.papermc.paper.datacomponent.item.ItemContainerContents;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
 * original, so two items with one ID at the same moment prove a dupe, whatever method made it. The extra
 * copies are then deleted ("undupe") following {@link DupeResolver}, which never takes a player's only copy
 * unless that player gave the item away and kept it anyway.
 */
public final class ItemFingerprints {

    /** Safety limit for one scan: each undupe is followed by a fresh look, so nested items stay correct. */
    private static final int MAX_FIXES_PER_SCAN = 32;

    /**
     * One tagged item that was found.
     *
     * @param inventory where the item (or the shulker/bundle holding it) is
     * @param slot      the slot in that inventory
     * @param nested    -1 for an item directly in the slot, else its index inside the shulker/bundle there
     */
    private record Found(String id, UUID owner, Inventory inventory, int slot, int nested, String where) {
        boolean topLevel() {
            return nested < 0;
        }
    }

    private final NovaGuardPlugin plugin;
    private final NamespacedKey key;
    /** Item ID -> the player whose storage it was last seen in. */
    private final Map<String, UUID> lastHolder = new HashMap<>();
    /** Item ID -> the player who last received it normally (picked it up, took it out of a chest). */
    private final Map<String, UUID> receivedBy = new HashMap<>();
    /** Item IDs already alerted without a fix, so an unclear case is reported once, not on every scan. */
    private final Set<String> reported = new HashSet<>();

    public ItemFingerprints(NovaGuardPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "uid");
    }

    // ---------------------------------------------------------------- normal transfers

    /** A player received an item in a normal way. Remembers it for every tagged item, also inside shulkers. */
    public void received(Player player, ItemStack item) {
        if (item == null || item.isEmpty()) {
            return;
        }
        String id = readId(item);
        if (id != null) {
            receivedBy.put(id, player.getUniqueId());
        }
        for (ItemStack inner : nestedItems(item)) {
            String innerId = readId(inner);
            if (innerId != null) {
                receivedBy.put(innerId, player.getUniqueId());
            }
        }
    }

    // ---------------------------------------------------------------- scan

    /**
     * Tags new valuable items and undupes copies in the player's inventory, ender chest, an open storage
     * container, and in the inventory of another online player who held the same item before.
     */
    public void scan(Player player, Inventory openContainer) {
        Settings settings = plugin.settings();
        if (!settings.dupeEnabled || !settings.fingerprintsEnabled || !player.isOnline() || isCreative(player)) {
            return;
        }
        // Replacing an item that is being used (trident charge, shield, elytra flight) would interrupt it.
        boolean tagNew = !player.isHandRaised() && !player.isGliding();
        List<Found> own = collectOwn(player, openContainer, tagNew);
        // Who held each item before this player? Looked up before this scan records the new holder.
        Map<String, Player> before = new HashMap<>();
        for (Found f : own) {
            if (!before.containsKey(f.id())) {
                before.put(f.id(), previousHolder(player, f.id()));
            }
        }
        before.keySet().forEach(id -> lastHolder.put(id, player.getUniqueId()));

        for (int fixes = 0; fixes < MAX_FIXES_PER_SCAN; fixes++) {
            if (!undupeOne(player.getName(), own, before, settings.removeDuplicates)) {
                return;
            }
            own = collectOwn(player, openContainer, false); // the inventory changed: look again
        }
    }

    /** Drops what we remember about a player who left, so memory stays small. */
    public void forget(UUID player) {
        lastHolder.values().removeIf(player::equals);
    }

    /** Finds one duplicated ID and fixes it. Returns true when something was deleted. */
    private boolean undupeOne(String playerName, List<Found> own, Map<String, Player> before, boolean remove) {
        Map<String, List<Found>> byId = new LinkedHashMap<>();
        for (Found f : own) {
            byId.computeIfAbsent(f.id(), k -> new ArrayList<>()).add(f);
        }
        Map<UUID, List<Found>> othersCache = new HashMap<>();
        for (Map.Entry<String, List<Found>> entry : byId.entrySet()) {
            String id = entry.getKey();
            List<Found> copies = new ArrayList<>(entry.getValue());
            Player other = before.get(id);
            if (other != null && other.isOnline()) {
                List<Found> theirs = othersCache.computeIfAbsent(other.getUniqueId(),
                    k -> collectOwn(other, null, false));
                theirs.stream().filter(f -> f.id().equals(id)).forEach(copies::add);
            }
            if (copies.size() < 2) {
                continue;
            }
            // Keep a loose item before one inside a shulker: deleting a shulker would delete its contents too.
            copies.sort(Comparator.comparing(f -> !f.topLevel()));
            List<Copy<Found>> input = new ArrayList<>();
            copies.forEach(f -> input.add(new Copy<>(f.owner(), f)));
            Verdict<Found> verdict = DupeResolver.resolve(input, receivedBy.get(id));

            String places = describe(copies);
            if (!remove || verdict.remove().isEmpty()) {
                if (reported.add(id)) {
                    plugin.violations().dupeAlert(playerName, copies.size() + " copies of one item (ID "
                        + shortId(id) + ") in " + places + (remove ? " - not deleted: " + verdict.reason() : ""));
                }
                continue;
            }
            int deleted = 0;
            for (Found f : verdict.remove()) {
                if (delete(f)) {
                    deleted++;
                }
            }
            plugin.violations().dupeAlert(playerName, copies.size() + " copies of one item (ID "
                + shortId(id) + ") in " + places + ": undupe deleted " + deleted + " (" + verdict.reason() + ")"
                + (verdict.ownerUnclear() ? ". Staff: check who duped" : ""));
            if (deleted > 0) {
                return true;
            }
        }
        return false;
    }

    /** Another online survival player who held this item before, if any. */
    private Player previousHolder(Player self, String id) {
        UUID previous = lastHolder.get(id);
        if (previous == null || previous.equals(self.getUniqueId())) {
            return null;
        }
        Player other = Bukkit.getPlayer(previous);
        return other != null && other.isOnline() && !isCreative(other) ? other : null;
    }

    // ---------------------------------------------------------------- deleting one copy

    private boolean delete(Found f) {
        ItemStack inSlot = f.inventory().getItem(f.slot());
        if (inSlot == null || inSlot.isEmpty()) {
            return false;
        }
        if (f.topLevel()) {
            if (!f.id().equals(readId(inSlot))) {
                return false; // the slot changed since the scan: never delete something else
            }
            f.inventory().setItem(f.slot(), null);
            return true;
        }
        ItemContainerContents container = inSlot.getData(DataComponentTypes.CONTAINER);
        if (container != null) {
            List<ItemStack> inner = new ArrayList<>(container.contents());
            if (!matches(inner, f)) {
                return false;
            }
            inner.set(f.nested(), ItemStack.empty()); // keep the other items in their shulker slots
            inSlot.setData(DataComponentTypes.CONTAINER, ItemContainerContents.containerContents(inner));
            f.inventory().setItem(f.slot(), inSlot);
            return true;
        }
        BundleContents bundle = inSlot.getData(DataComponentTypes.BUNDLE_CONTENTS);
        if (bundle != null) {
            List<ItemStack> inner = new ArrayList<>(bundle.contents());
            if (!matches(inner, f)) {
                return false;
            }
            inner.remove(f.nested());
            inSlot.setData(DataComponentTypes.BUNDLE_CONTENTS, BundleContents.bundleContents(inner));
            f.inventory().setItem(f.slot(), inSlot);
            return true;
        }
        return false;
    }

    private boolean matches(List<ItemStack> inner, Found f) {
        return f.nested() < inner.size() && f.id().equals(readId(inner.get(f.nested())));
    }

    // ---------------------------------------------------------------- collecting

    private List<Found> collectOwn(Player player, Inventory openContainer, boolean tagNew) {
        List<Found> found = new ArrayList<>();
        UUID owner = player.getUniqueId();
        collect(owner, player.getInventory(), "inventory of " + player.getName(), found, tagNew);
        Inventory enderChest = player.getEnderChest();
        collect(owner, enderChest, "ender chest of " + player.getName(), found, true);
        if (openContainer != null && openContainer != enderChest && isStorage(openContainer)) {
            collect(owner, openContainer, "open " + openContainer.getType().name().toLowerCase(Locale.ROOT)
                + " (" + player.getName() + ")", found, true);
        }
        return found;
    }

    private void collect(UUID owner, Inventory inventory, String where, List<Found> out, boolean tagNew) {
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
                out.add(new Found(id, owner, inventory, slot, -1, where));
            }
            List<ItemStack> inner = nestedItems(item);
            String place = where + " (inside " + item.getType().name().toLowerCase(Locale.ROOT) + ")";
            for (int i = 0; i < inner.size(); i++) {
                String innerId = readId(inner.get(i));
                if (innerId != null) {
                    out.add(new Found(innerId, owner, inventory, slot, i, place));
                }
            }
        }
    }

    /** Items inside a shulker box or bundle (read only, never re-tagged), keeping their positions. */
    private static List<ItemStack> nestedItems(ItemStack holder) {
        ItemContainerContents container = holder.getData(DataComponentTypes.CONTAINER);
        if (container != null) {
            return container.contents();
        }
        BundleContents bundle = holder.getData(DataComponentTypes.BUNDLE_CONTENTS);
        return bundle != null ? bundle.contents() : List.of();
    }

    private String readId(ItemStack item) {
        if (item == null || item.isEmpty()) {
            return null;
        }
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

    private static String describe(List<Found> copies) {
        List<String> places = new ArrayList<>();
        copies.stream().map(Found::where).filter(Objects::nonNull).forEach(places::add);
        return String.join(", ", places);
    }

    private static String shortId(String id) {
        return id.length() > 8 ? id.substring(0, 8) : id;
    }
}
