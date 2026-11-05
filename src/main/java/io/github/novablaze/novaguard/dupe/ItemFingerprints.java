package io.github.novablaze.novaguard.dupe;

import io.github.novablaze.novaguard.NovaGuardPlugin;
import io.github.novablaze.novaguard.Settings;
import io.github.novablaze.novaguard.core.PlayerData;
import io.github.novablaze.novaguard.items.ItemSanitizer;
import io.github.novablaze.novaguard.items.ItemTree;
import io.github.novablaze.novaguard.math.DupeBlame;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Gives valuable unstackable items a hidden random ID and birth time. A duplicated item carries the same ID as the
 * original, so two items with one ID existing at the same moment prove a dupe. The extra copy is removed at once
 * (exactly one copy always survives), and the player is warned only when the evidence points at them alone.
 *
 * <p>Every scan also fixes illegal items (see {@link ItemSanitizer}).
 */
public final class ItemFingerprints {

    /** One tagged item. {@code path[0]} is the slot, further entries are positions inside shulkers/bundles. */
    record Found(String id, long born, Inventory inventory, int[] path, boolean own, String where, String item) {
    }

    /** Who last dropped or stored an item away (they should not have it any more). */
    private record Release(UUID player, long timeMs) {
    }

    /**
     * How long "X gave this item away" counts as evidence. Rollback dupes (drop, then crash/kick before the save)
     * happen within minutes; older records could blame the wrong player.
     */
    private static final long RELEASE_TTL_MS = 30 * 60_000L;
    private static final int OFFHAND_SLOT = 40;
    private static final int CHEST_SLOT = 38;

    private final NovaGuardPlugin plugin;
    private final NamespacedKey key;
    private final NamespacedKey bornKey;
    private final NamespacedKey baselineKey;
    private final ItemSanitizer sanitizer;
    /** Item ID -> the player whose storage it was last seen in. */
    private final Map<String, UUID> lastHolder = new HashMap<>();
    private final Map<String, Release> released = new HashMap<>();
    /** Item IDs (with copy counts) in the container a player opened, to see what they put in or took out. */
    private final Map<UUID, Map<String, Integer>> openSnapshot = new HashMap<>();
    /** Item IDs in each player's own storage at their previous scan. */
    private final Map<UUID, Set<String>> lastOwnIds = new HashMap<>();
    /** Copies that came into a player's storage from outside (pickups, containers) since their previous scan. */
    private final Map<UUID, Map<String, Integer>> arrivals = new HashMap<>();

    public ItemFingerprints(NovaGuardPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "uid");
        this.bornKey = new NamespacedKey(plugin, "born");
        this.baselineKey = new NamespacedKey(plugin, "baseline");
        this.sanitizer = new ItemSanitizer(plugin);
    }

    public ItemSanitizer sanitizer() {
        return sanitizer;
    }

    // ---------------------------------------------------------------- the scan

    /**
     * Fixes illegal items, tags new valuable items, undoes duplicates in the player's inventory, ender chest and
     * (optionally) an open container, and compares with other online players.
     */
    public void scan(Player player, Inventory openContainer) {
        Settings settings = plugin.settings();
        if (!settings.dupeEnabled && !settings.illegalEnabled || !player.isOnline() || isCreative(player)) {
            return;
        }
        long now = System.currentTimeMillis();
        Inventory inventory = player.getInventory();
        Inventory enderChest = player.getEnderChest();
        Set<Integer> busy = busySlots(player);
        boolean container = openContainer != null && isRealStorage(openContainer);

        // 1. Illegal items.
        boolean baseline = player.getPersistentDataContainer().has(baselineKey, PersistentDataType.BYTE);
        List<String> fixes = new ArrayList<>();
        List<String> provable = new ArrayList<>();
        sanitizeOwn(inventory, busy, fixes, provable);
        sanitizeOwn(enderChest, Set.of(), fixes, provable);
        if (container) {
            List<String> containerFixes = sanitizeInventory(openContainer);
            if (!containerFixes.isEmpty()) {
                plugin.violations().dupeAlert(player.getName(), "illegal item(s) in an opened "
                    + typeName(openContainer) + " were fixed (not blamed on " + player.getName() + "): "
                    + String.join("; ", containerFixes));
            }
        }

        // 2. Fingerprints.
        if (settings.dupeEnabled && settings.fingerprintsEnabled) {
            PlayerData data = plugin.players().getIfPresent(player.getUniqueId());
            if (data != null && now < data.manualExemptUntilMs) {
                // /ng exempt (e.g. after restoring an inventory with a rollback plugin): the restored items
                // get new IDs, so they no longer count as copies of the items that still exist elsewhere.
                retag(inventory, busy, now);
                retag(enderChest, Set.of(), now);
            } else {
                List<Found> found = new ArrayList<>();
                collect(inventory, "inventory", true, found, busy, true, now);
                collect(enderChest, "ender chest", true, found, Set.of(), true, now);
                if (container) {
                    collect(openContainer, "open " + typeName(openContainer), false, found, Set.of(), true, now);
                }
                Set<Found> removed = sameScan(player, found, now);
                crossPlayer(player, found, removed, now);
                Set<String> ownIds = new HashSet<>();
                for (Found f : found) {
                    if (f.own() && !removed.contains(f)) {
                        ownIds.add(f.id());
                    }
                }
                lastOwnIds.put(player.getUniqueId(), ownIds);
                arrivals.remove(player.getUniqueId());
            }
        }

        // 3. Tell the player and decide about blame for illegal items.
        if (!fixes.isEmpty()) {
            tell(player, MiniMessage.miniMessage().deserialize(settings.illegalFixedMessage,
                Placeholder.unparsed("changes", shorten(String.join("; ", fixes)))));
            if (!provable.isEmpty()) {
                // A tagged item was legal when NovaGuard last checked it and became illegal in this player's own
                // inventory. Every other way in (pickup, chests, anvils) is fixed before it arrives.
                plugin.itemPunisher().strong(player, "illegal item", String.join("; ", provable));
            } else if (baseline) {
                // Where it came from is unknown (a trade plugin, a crate, a kit...): watch, never ban for this alone.
                plugin.itemPunisher().weak(player, "illegal item", String.join("; ", fixes));
            } else {
                plugin.violations().dupeAlert(player.getName(), "illegal item(s) from before NovaGuard checked this"
                    + " player were fixed (not blamed): " + String.join("; ", fixes));
            }
        }
        if (!baseline) {
            player.getPersistentDataContainer().set(baselineKey, PersistentDataType.BYTE, (byte) 1);
        }
    }

    /** Fixes illegal items of a player who cannot be blamed (just left creative, took from an armor stand). */
    public void sanitizeSilently(Player player) {
        List<String> fixes = new ArrayList<>();
        fixes.addAll(sanitizeInventory(player.getInventory(), busySlots(player)));
        fixes.addAll(sanitizeInventory(player.getEnderChest()));
        if (!fixes.isEmpty()) {
            plugin.violations().dupeAlert(player.getName(), "illegal item(s) fixed (not blamed): "
                + String.join("; ", fixes));
            tell(player, MiniMessage.miniMessage().deserialize(plugin.settings().illegalFixedMessage,
                Placeholder.unparsed("changes", shorten(String.join("; ", fixes)))));
        }
    }

    public List<String> sanitizeInventory(Inventory inventory) {
        return sanitizeInventory(inventory, Set.of());
    }

    private List<String> sanitizeInventory(Inventory inventory, Set<Integer> busy) {
        List<String> fixes = new ArrayList<>();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.isEmpty() || busy.contains(slot)) {
                continue;
            }
            ItemSanitizer.Result result = sanitizer.sanitize(item);
            if (result.changed()) {
                inventory.setItem(slot, result.item());
                fixes.add(typeName(item) + ": " + String.join(", ", result.changes()));
            }
        }
        return fixes;
    }

    private void sanitizeOwn(Inventory inventory, Set<Integer> busy, List<String> fixes, List<String> provable) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.isEmpty() || busy.contains(slot)) {
                continue;
            }
            ItemSanitizer.Result result = sanitizer.sanitize(item);
            if (!result.changed()) {
                continue;
            }
            // Only items tagged by this version have a birth time: they were checked (legal) when tagged.
            boolean checkedBefore = readId(item) != null && readBorn(item) > 0;
            inventory.setItem(slot, result.item());
            String text = typeName(item) + ": " + String.join(", ", result.changes());
            fixes.add(text);
            if (checkedBefore && result.selfChanged()) {
                provable.add(text);
            }
        }
    }

    // ---------------------------------------------------------------- dupes in one scan

    /** Undoes copies of one ID inside this scan. @return the copies that were removed */
    private Set<Found> sameScan(Player player, List<Found> found, long now) {
        Settings settings = plugin.settings();
        Map<String, List<Found>> byId = new LinkedHashMap<>();
        for (Found f : found) {
            byId.computeIfAbsent(f.id(), k -> new ArrayList<>()).add(f);
        }
        Set<String> hadBefore = lastOwnIds.getOrDefault(player.getUniqueId(), Set.of());
        Map<String, Integer> arrived = arrivals.getOrDefault(player.getUniqueId(), Map.of());
        Set<Found> toRemove = new HashSet<>();
        List<String> blamed = new ArrayList<>();
        boolean ownCopyRemovedWithoutBlame = false;
        for (Map.Entry<String, List<Found>> entry : byId.entrySet()) {
            List<Found> copies = entry.getValue();
            if (copies.size() < 2) {
                continue;
            }
            int own = 0;
            for (Found f : copies) {
                if (f.own()) {
                    own++;
                }
            }
            int inContainer = copies.size() - own;
            Release release = release(entry.getKey(), now);
            boolean putItIn = inContainer > 0 && release != null && release.player().equals(player.getUniqueId());
            boolean rollback = plugin.serverState().possibleRollback(copies.get(0).born(), now, settings.crashGraceMs);
            int known = (hadBefore.contains(entry.getKey()) ? 1 : 0) + arrived.getOrDefault(entry.getKey(), 0);
            DupeBlame.Decision decision = DupeBlame.sameScan(own, inContainer, known, putItIn, rollback);

            Found keep = null;
            for (Found f : copies) {
                if (f.own() == decision.keepOwnCopy()) {
                    keep = f;
                    break;
                }
            }
            if (keep == null) {
                keep = copies.get(0);
            }
            List<String> places = new ArrayList<>();
            for (Found f : copies) {
                places.add(f.where());
                if (f != keep) {
                    toRemove.add(f);
                    ownCopyRemovedWithoutBlame |= f.own() && !decision.blameScanned();
                }
            }
            String text = copies.size() + " copies of one " + copies.get(0).item() + " (ID " + shortId(entry.getKey())
                + ") in " + String.join(", ", places);
            if (!settings.undoDupes) {
                plugin.violations().dupeAlert(player.getName(), text + " (undo-dupes is off: nothing removed)");
                continue;
            }
            text += ", " + (copies.size() - 1) + " removed: " + decision.reason();
            if (decision.blameScanned()) {
                blamed.add(text);
            } else {
                plugin.violations().dupeAlert(player.getName(), text + " (nobody warned)");
            }
        }
        if (!settings.undoDupes) {
            return Set.of();
        }
        remove(toRemove);
        if (ownCopyRemovedWithoutBlame) {
            tell(player, MiniMessage.miniMessage().deserialize(settings.dupeRemovedMessage));
        }
        if (!blamed.isEmpty()) {
            plugin.itemPunisher().strong(player, "dupe", String.join("; ", blamed));
        }
        return toRemove;
    }

    // ---------------------------------------------------------------- dupes across two players

    private void crossPlayer(Player player, List<Found> found, Set<Found> removed, long now) {
        Settings settings = plugin.settings();
        UUID self = player.getUniqueId();
        Set<String> checked = new HashSet<>();
        for (Found mine : found) {
            if (!mine.own() || removed.contains(mine) || !checked.add(mine.id())) {
                continue;
            }
            UUID previous = lastHolder.put(mine.id(), self);
            if (previous == null || previous.equals(self)) {
                continue;
            }
            Player other = Bukkit.getPlayer(previous);
            if (other == null || !other.isOnline() || isCreative(other) || exempt(other, now)) {
                continue;
            }
            List<Found> theirs = new ArrayList<>();
            for (Found f : collectOwn(other, now)) {
                if (f.id().equals(mine.id())) {
                    theirs.add(f);
                }
            }
            if (theirs.isEmpty()) {
                continue;
            }
            String text = "has the same " + mine.item() + " as " + other.getName() + " (ID " + shortId(mine.id()) + ")";
            if (!settings.undoDupes) {
                plugin.violations().dupeAlert(player.getName(), text + " (undo-dupes is off: nothing removed)");
                continue;
            }
            Release release = release(mine.id(), now);
            boolean rollback = plugin.serverState().possibleRollback(mine.born(), now, settings.crashGraceMs);
            String culprit = DupeBlame.crossPlayer(self.toString(), previous.toString(),
                release == null ? null : release.player().toString(), rollback);
            if (culprit != null && culprit.equals(previous.toString())) {
                remove(new HashSet<>(theirs));
                plugin.itemPunisher().strong(other, "dupe", "still had a " + mine.item() + " (ID " + shortId(mine.id())
                    + ") after giving it away; " + player.getName() + " has it now. Their copy was removed.");
            } else {
                remove(Set.of(mine));
                lastHolder.put(mine.id(), previous);
                if (culprit != null) {
                    plugin.itemPunisher().strong(player, "dupe", "still had a " + mine.item() + " (ID "
                        + shortId(mine.id()) + ") after giving it away; " + other.getName()
                        + " has it now. Their copy was removed.");
                } else {
                    plugin.violations().dupeAlert(player.getName(), text + ": the copy of " + player.getName()
                        + " was removed. It is unclear who duped it, so nobody was warned"
                        + (rollback ? " (the item existed before the last crash/rollback)." : "."));
                    tell(player, MiniMessage.miniMessage().deserialize(settings.dupeRemovedMessage));
                }
            }
        }
    }

    /** Copies in a player's own inventory and ender chest (read only: nothing is tagged). */
    private List<Found> collectOwn(Player player, long now) {
        List<Found> found = new ArrayList<>();
        collect(player.getInventory(), "inventory", true, found, Set.of(), false, now);
        collect(player.getEnderChest(), "ender chest", true, found, Set.of(), false, now);
        return found;
    }

    // ---------------------------------------------------------------- give-away tracking

    /** A player dropped an item: if they still have it later while someone else has it too, they duped it. */
    public void onDrop(Player player, ItemStack dropped) {
        String id = readId(dropped);
        if (id != null) {
            released.put(id, new Release(player.getUniqueId(), System.currentTimeMillis()));
        }
    }

    /** A player picked up an item: an honest way for a copy to arrive in their inventory. */
    public void onPickup(Player player, ItemStack picked) {
        String id = readId(picked);
        if (id != null) {
            arrived(player.getUniqueId(), id, 1);
        }
    }

    /** Remembers which tagged items were in a container when a player opened it. */
    public void snapshotOpen(Player player, Inventory top) {
        if (isRealStorage(top)) {
            openSnapshot.put(player.getUniqueId(), idsIn(top));
        }
    }

    /**
     * Compares the container with what was in it at opening: items that appeared were put there by this player,
     * items that disappeared were (most likely) taken by them.
     */
    public void recordPlacement(Player player, Inventory top) {
        Map<String, Integer> before = openSnapshot.remove(player.getUniqueId());
        if (before == null || !isRealStorage(top)) {
            return;
        }
        long now = System.currentTimeMillis();
        Map<String, Integer> after = idsIn(top);
        for (Map.Entry<String, Integer> e : after.entrySet()) {
            if (e.getValue() > before.getOrDefault(e.getKey(), 0)) {
                released.put(e.getKey(), new Release(player.getUniqueId(), now));
            }
        }
        for (Map.Entry<String, Integer> e : before.entrySet()) {
            int taken = e.getValue() - after.getOrDefault(e.getKey(), 0);
            if (taken > 0) {
                arrived(player.getUniqueId(), e.getKey(), taken);
            }
        }
    }

    /** An honest arrival. If this player gave the item away before, they got it back: that is no evidence any more. */
    private void arrived(UUID player, String id, int copies) {
        arrivals.computeIfAbsent(player, k -> new HashMap<>()).merge(id, copies, Integer::sum);
        Release release = released.get(id);
        if (release != null && release.player().equals(player)) {
            released.remove(id);
        }
    }

    private Map<String, Integer> idsIn(Inventory inventory) {
        List<Found> found = new ArrayList<>();
        collect(inventory, "", false, found, Set.of(), false, 0);
        Map<String, Integer> ids = new HashMap<>();
        found.forEach(f -> ids.merge(f.id(), 1, Integer::sum));
        return ids;
    }

    private Release release(String id, long now) {
        Release release = released.get(id);
        return release != null && now - release.timeMs() < RELEASE_TTL_MS ? release : null;
    }

    /** Once in a while: forget old give-aways so memory stays small. */
    public void cleanup(long now) {
        released.values().removeIf(release -> now - release.timeMs() >= RELEASE_TTL_MS);
    }

    /**
     * Drops what we remember about a player who left. Give-aways and the last-scan IDs are kept: rollback dupes
     * show up after a rejoin.
     */
    public void forget(UUID player) {
        lastHolder.values().removeIf(player::equals);
        openSnapshot.remove(player);
    }

    // ---------------------------------------------------------------- tagging

    private void collect(Inventory inventory, String where, boolean own, List<Found> out, Set<Integer> busy,
                         boolean tagNew, long now) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.isEmpty()) {
                continue;
            }
            String id = readId(item);
            if (id == null && tagNew && plugin.settings().fingerprintsEnabled && !busy.contains(slot)
                && eligible(item)) {
                item = tagged(item, UUID.randomUUID().toString(), now);
                inventory.setItem(slot, item);
                id = readId(item);
            }
            int[] path = {slot};
            if (id != null) {
                out.add(new Found(id, readBorn(item), inventory, path, own, where, typeName(item)));
            }
            collectNested(item, inventory, path, where, own, out, 1);
        }
    }

    /** Items inside shulker boxes and bundles are read (never re-tagged). */
    private void collectNested(ItemStack holder, Inventory inventory, int[] path, String where, boolean own,
                               List<Found> out, int depth) {
        if (depth > ItemTree.MAX_DEPTH) {
            return;
        }
        List<ItemStack> inner = ItemTree.children(holder);
        if (inner == null) {
            return;
        }
        String place = where + " (inside " + typeName(holder) + ")";
        for (int i = 0; i < inner.size(); i++) {
            ItemStack item = inner.get(i);
            if (item == null || item.isEmpty()) {
                continue;
            }
            int[] childPath = Arrays.copyOf(path, path.length + 1);
            childPath[path.length] = i;
            String id = readId(item);
            if (id != null) {
                out.add(new Found(id, readBorn(item), inventory, childPath, own, place, typeName(item)));
            }
            collectNested(item, inventory, childPath, place, own, out, depth + 1);
        }
    }

    /** Gives tagged items in the player's storage new IDs (manual exemption). */
    private void retag(Inventory inventory, Set<Integer> busy, long now) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && !item.isEmpty() && !busy.contains(slot) && readId(item) != null) {
                inventory.setItem(slot, tagged(item, UUID.randomUUID().toString(), now));
            }
        }
    }

    private ItemStack tagged(ItemStack item, String id, long now) {
        ItemStack copy = item.clone();
        copy.editPersistentDataContainer(pdc -> {
            pdc.set(key, PersistentDataType.STRING, id);
            pdc.set(bornKey, PersistentDataType.LONG, now);
        });
        return copy;
    }

    /**
     * A copy without NovaGuard IDs (also inside shulkers/bundles). Used for items that come out of the creative
     * inventory: creative players can clone any item, and a clone is not a dupe.
     */
    public ItemStack stripIds(ItemStack item) {
        return stripIds(item, 0);
    }

    private ItemStack stripIds(ItemStack item, int depth) {
        if (item == null || item.isEmpty()) {
            return item;
        }
        ItemStack out = item;
        if (readId(item) != null || item.getPersistentDataContainer().has(bornKey)) {
            out = item.clone();
            out.editPersistentDataContainer(pdc -> {
                pdc.remove(key);
                pdc.remove(bornKey);
            });
        }
        if (depth < ItemTree.MAX_DEPTH) {
            List<ItemStack> children = ItemTree.children(out);
            if (children != null) {
                boolean changed = false;
                for (int i = 0; i < children.size(); i++) {
                    ItemStack child = children.get(i);
                    ItemStack stripped = stripIds(child, depth + 1);
                    if (stripped != child) {
                        children.set(i, stripped);
                        changed = true;
                    }
                }
                if (changed) {
                    out = ItemTree.withChildren(out, children);
                }
            }
        }
        return out;
    }

    String readId(ItemStack item) {
        return item.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    long readBorn(ItemStack item) {
        Long born = item.getPersistentDataContainer().get(bornKey, PersistentDataType.LONG);
        return born == null ? 0 : born;
    }

    private boolean eligible(ItemStack item) {
        if (item.getMaxStackSize() != 1 || sanitizer.hasForeignData(item)) {
            return false;
        }
        Settings settings = plugin.settings();
        if (settings.fingerprintMaterials.contains(item.getType())) {
            return true;
        }
        if (!settings.tagEnchanted) {
            return false;
        }
        return !item.getEnchantments().isEmpty()
            || item.getItemMeta() instanceof EnchantmentStorageMeta book && book.hasStoredEnchants();
    }

    // ---------------------------------------------------------------- removing copies

    /**
     * Removes the given copies. Deeper and later positions go first, so removing an item from a bundle never
     * shifts the position of another copy that still has to be removed.
     */
    void remove(Set<Found> copies) {
        List<Found> order = new ArrayList<>(copies);
        order.sort(Comparator.comparingInt((Found f) -> System.identityHashCode(f.inventory()))
            .thenComparing(Found::path, (a, b) -> Arrays.compare(b, a)));
        for (Found f : order) {
            ItemStack top = f.inventory().getItem(f.path()[0]);
            if (top == null || top.isEmpty()) {
                continue;
            }
            if (f.path().length == 1) {
                f.inventory().setItem(f.path()[0], null);
            } else {
                ItemStack updated = removeAt(top, f.path(), 1);
                if (updated != null) {
                    f.inventory().setItem(f.path()[0], updated);
                }
            }
        }
    }

    private static ItemStack removeAt(ItemStack holder, int[] path, int index) {
        List<ItemStack> children = ItemTree.children(holder);
        if (children == null || path[index] >= children.size()) {
            return null;
        }
        if (index == path.length - 1) {
            children.set(path[index], null);
        } else {
            ItemStack child = children.get(path[index]);
            ItemStack updated = child == null ? null : removeAt(child, path, index + 1);
            if (updated == null) {
                return null;
            }
            children.set(path[index], updated);
        }
        return ItemTree.withChildren(holder, children);
    }

    // ---------------------------------------------------------------- self-test

    /** /ng selftest: runs the fixing and undoing logic on test items in this server. */
    public List<String> selfTest() {
        return SelfTest.run(plugin);
    }

    List<Found> collectForTest(Inventory inventory) {
        List<Found> found = new ArrayList<>();
        collect(inventory, "test", true, found, Set.of(), false, 0);
        return found;
    }

    ItemStack taggedForTest(ItemStack item, String id) {
        return tagged(item, id, System.currentTimeMillis());
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Real block/entity storage. Plugin menus (shops, auction houses, kit previews) are excluded: they often show
     * copies of real items, which must never count as dupes.
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

    /** Slots that must not be replaced right now: replacing an item in use interrupts it. */
    private static Set<Integer> busySlots(Player player) {
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

    private boolean exempt(Player player, long now) {
        PlayerData data = plugin.players().getIfPresent(player.getUniqueId());
        return data != null && now < data.manualExemptUntilMs;
    }

    private static boolean isCreative(Player player) {
        return player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR;
    }

    private static void tell(Player player, net.kyori.adventure.text.Component message) {
        if (player.isOnline()) {
            player.sendMessage(message);
        }
    }

    private static String typeName(ItemStack item) {
        return item.getType().name().toLowerCase(Locale.ROOT);
    }

    private static String typeName(Inventory inventory) {
        return inventory.getType().name().toLowerCase(Locale.ROOT);
    }

    private static String shorten(String text) {
        return text.length() > 220 ? text.substring(0, 217) + "..." : text;
    }

    private static String shortId(String id) {
        return id.length() > 8 ? id.substring(0, 8) : id;
    }
}
