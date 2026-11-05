package io.github.novablaze.novaguard.items;

import io.github.novablaze.novaguard.NovaGuardPlugin;
import io.github.novablaze.novaguard.Settings;
import java.util.List;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * The hidden ID and birth time of valuable items (stored in the item's persistent data as novaguard:uid and
 * novaguard:born). A duplicated item carries the same ID as the original; the birth time tells whether the item
 * existed before a crash/rollback.
 */
public final class ItemIds {

    private final NovaGuardPlugin plugin;
    private final NamespacedKey key;
    private final NamespacedKey bornKey;

    public ItemIds(NovaGuardPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "uid");
        this.bornKey = new NamespacedKey(plugin, "born");
    }

    /** The ID, or null when the item has none. */
    public String read(ItemStack item) {
        return item.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    /** When the ID was given (0 for items tagged by older NovaGuard versions). */
    public long born(ItemStack item) {
        Long born = item.getPersistentDataContainer().get(bornKey, PersistentDataType.LONG);
        return born == null ? 0 : born;
    }

    /** A copy of the item with this ID, born now. */
    public ItemStack withId(ItemStack item, String id, long nowMs) {
        ItemStack copy = item.clone();
        copy.editPersistentDataContainer(pdc -> {
            pdc.set(key, PersistentDataType.STRING, id);
            pdc.set(bornKey, PersistentDataType.LONG, nowMs);
        });
        return copy;
    }

    /**
     * A copy without IDs (also inside shulkers/bundles), or the same object when there was nothing to strip.
     * Used for items that come out of the creative inventory: creative players can clone anything, and a clone is
     * not a dupe.
     */
    public ItemStack strip(ItemStack item) {
        return strip(item, 0);
    }

    private ItemStack strip(ItemStack item, int depth) {
        if (item == null || item.isEmpty()) {
            return item;
        }
        ItemStack out = item;
        if (read(item) != null || item.getPersistentDataContainer().has(bornKey)) {
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
                    ItemStack stripped = strip(child, depth + 1);
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

    /** Valuable unstackable items get an ID; items of other plugins never do (it could break their checks). */
    public boolean eligible(ItemStack item, ItemSanitizer sanitizer) {
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
}
