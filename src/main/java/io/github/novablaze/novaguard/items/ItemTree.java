package io.github.novablaze.novaguard.items;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.BundleContents;
import io.papermc.paper.datacomponent.item.ItemContainerContents;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.inventory.ItemStack;

/** Reads and rewrites the items stored inside shulker boxes (and other container items) and bundles. */
public final class ItemTree {

    /** Shulkers inside bundles inside shulkers... are read this deep. */
    public static final int MAX_DEPTH = 3;

    private ItemTree() {
    }

    /**
     * The items inside {@code holder}, as a mutable copy. For container items the list index is the slot
     * (empty slots are empty stacks); for bundles it is the bundle order. Null when the item holds nothing.
     */
    public static List<ItemStack> children(ItemStack holder) {
        if (holder == null || holder.isEmpty()) {
            return null;
        }
        ItemContainerContents container = holder.getData(DataComponentTypes.CONTAINER);
        if (container != null) {
            return new ArrayList<>(container.contents());
        }
        BundleContents bundle = holder.getData(DataComponentTypes.BUNDLE_CONTENTS);
        if (bundle != null) {
            return new ArrayList<>(bundle.contents());
        }
        return null;
    }

    /**
     * A copy of {@code holder} with new contents. A null entry means "remove this item": container slots become
     * empty (other slots keep their position), bundle entries are dropped.
     */
    public static ItemStack withChildren(ItemStack holder, List<ItemStack> children) {
        ItemStack copy = holder.clone();
        if (holder.getData(DataComponentTypes.CONTAINER) != null) {
            List<ItemStack> slots = new ArrayList<>(children.size());
            for (ItemStack child : children) {
                slots.add(child == null ? ItemStack.empty() : child);
            }
            copy.setData(DataComponentTypes.CONTAINER, ItemContainerContents.containerContents(slots));
        } else if (holder.getData(DataComponentTypes.BUNDLE_CONTENTS) != null) {
            List<ItemStack> kept = new ArrayList<>(children.size());
            for (ItemStack child : children) {
                if (child != null && !child.isEmpty()) {
                    kept.add(child);
                }
            }
            copy.setData(DataComponentTypes.BUNDLE_CONTENTS, BundleContents.bundleContents(kept));
        }
        return copy;
    }
}
