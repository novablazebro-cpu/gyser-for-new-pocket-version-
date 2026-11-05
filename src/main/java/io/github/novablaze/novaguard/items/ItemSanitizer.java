package io.github.novablaze.novaguard.items;

import io.github.novablaze.novaguard.NovaGuardPlugin;
import io.github.novablaze.novaguard.Settings;
import io.github.novablaze.novaguard.math.EnchantRules;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.List;
import java.util.Map;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Fixes items that cannot exist in survival: enchantments above their maximum level (Sharpness 10), enchantments
 * that exclude each other (Density + Breach on one mace), enchantments on items that cannot carry them (Knockback
 * on a stick), and the Unbreakable flag. All limits are read from the server's enchantment registry, so they are
 * always exactly vanilla's (Breach + Wind Burst on a mace is legal and stays).
 */
public final class ItemSanitizer {

    /**
     * @param item the fixed copy (or the original when nothing changed)
     * @param selfChanged the item itself was illegal (not only something stored inside it)
     */
    public record Result(ItemStack item, List<String> changes, boolean selfChanged) {
        public boolean changed() {
            return !changes.isEmpty();
        }
    }

    private static final Result NOTHING = new Result(null, List.of(), false);

    private final NovaGuardPlugin plugin;
    /** Item types that could not be fixed (warned once each). */
    private final Set<String> warnedUnstable = new HashSet<>();

    public ItemSanitizer(NovaGuardPlugin plugin) {
        this.plugin = plugin;
    }

    public Result sanitize(ItemStack item) {
        Settings settings = plugin.settings();
        if (!settings.illegalEnabled || item == null || item.isEmpty()) {
            return new Result(item, List.of(), false);
        }
        Result result = sanitize(item, 0, settings);
        if (result == NOTHING) {
            return new Result(item, List.of(), false);
        }
        // The fixed item must be clean. If fixing it again still changes something, the server refused one of the
        // changes: leave the item alone instead of "fixing" it forever (endless alerts, a pickup that never ends).
        if (sanitize(result.item(), 0, settings) != NOTHING) {
            if (warnedUnstable.add(item.getType().name())) {
                plugin.getLogger().warning("Could not fix an illegal " + item.getType().name().toLowerCase(Locale.ROOT)
                    + " (" + String.join(", ", result.changes()) + "): the server kept the change. It is left alone."
                    + " Please report this (shown once per item type).");
            }
            return new Result(item, List.of(), false);
        }
        return result;
    }


    private Result sanitize(ItemStack item, int depth, Settings settings) {
        List<String> changes = new ArrayList<>();
        ItemStack out = item;
        boolean selfChanged = false;
        // Items made by other plugins (custom enchant / crate / custom item plugins) are theirs to manage.
        if (!(settings.illegalSkipPluginItems && hasForeignData(item)) && item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            boolean changed = false;
            if (meta instanceof EnchantmentStorageMeta book && book.hasStoredEnchants()) {
                Map<Enchantment, Integer> stored = book.getStoredEnchants();
                EnchantRules.Fix fix = rules(stored, item, true);
                if (fix.changed()) {
                    stored.keySet().forEach(book::removeStoredEnchant);
                    apply(stored, fix, (e, level) -> book.addStoredEnchant(e, level, true));
                    changes.addAll(fix.changes());
                    changed = true;
                }
            }
            Map<Enchantment, Integer> enchants = meta.getEnchants();
            if (!enchants.isEmpty()) {
                EnchantRules.Fix fix = rules(enchants, item, false);
                if (fix.changed()) {
                    enchants.keySet().forEach(meta::removeEnchant);
                    apply(enchants, fix, (e, level) -> meta.addEnchant(e, level, true));
                    changes.addAll(fix.changes());
                    changed = true;
                }
            }
            if (settings.illegalRemoveUnbreakable && meta.isUnbreakable()) {
                meta.setUnbreakable(false);
                changes.add("Unbreakable removed");
                changed = true;
            }
            if (settings.illegalRemoveAttributes && meta.hasAttributeModifiers()) {
                meta.setAttributeModifiers(null);
                changes.add("custom attributes removed");
                changed = true;
            }
            if (changed) {
                out = item.clone();
                out.setItemMeta(meta);
                selfChanged = true;
            }
        }
        if (depth < ItemTree.MAX_DEPTH) {
            List<ItemStack> children = ItemTree.children(out);
            if (children != null) {
                boolean childChanged = false;
                for (int i = 0; i < children.size(); i++) {
                    ItemStack child = children.get(i);
                    if (child == null || child.isEmpty()) {
                        continue;
                    }
                    Result r = sanitize(child, depth + 1, settings);
                    if (r != NOTHING) {
                        children.set(i, r.item());
                        r.changes().forEach(c -> changes.add(c + " (inside "
                            + item.getType().name().toLowerCase(Locale.ROOT) + ")"));
                        childChanged = true;
                    }
                }
                if (childChanged) {
                    out = ItemTree.withChildren(out, children);
                }
            }
        }
        return changes.isEmpty() ? NOTHING : new Result(out, List.copyOf(changes), selfChanged);
    }

    private static EnchantRules.Fix rules(Map<Enchantment, Integer> enchants, ItemStack item, boolean book) {
        Map<String, Enchantment> byKey = new HashMap<>();
        List<EnchantRules.Enchant> list = new ArrayList<>();
        for (Map.Entry<Enchantment, Integer> e : enchants.entrySet()) {
            Enchantment enchantment = e.getKey();
            String key = enchantment.getKey().asString();
            byKey.put(key, enchantment);
            list.add(new EnchantRules.Enchant(key, e.getValue(), enchantment.getMaxLevel(),
                book || enchantment.canEnchantItem(item)));
        }
        return EnchantRules.fix(list, (a, b) -> byKey.get(a).conflictsWith(byKey.get(b)), book);
    }

    private static void apply(Map<Enchantment, Integer> original, EnchantRules.Fix fix,
                              BiConsumer<Enchantment, Integer> add) {
        for (Enchantment enchantment : original.keySet()) {
            Integer level = fix.keep().get(enchantment.getKey().asString());
            if (level != null) {
                add.accept(enchantment, level);
            }
        }
    }

    /** True when another plugin stored its own data on the item. */
    public boolean hasForeignData(ItemStack item) {
        String own = plugin.getName().toLowerCase(Locale.ROOT);
        for (NamespacedKey key : item.getPersistentDataContainer().getKeys()) {
            if (!key.getNamespace().equals(own)) {
                return true;
            }
        }
        return false;
    }
}
