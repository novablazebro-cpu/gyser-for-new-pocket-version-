package io.github.novablaze.novaguard.evidence;

import io.github.novablaze.novaguard.NovaGuardPlugin;
import io.github.novablaze.novaguard.Settings;
import io.github.novablaze.novaguard.items.ItemSanitizer;
import io.github.novablaze.novaguard.items.ItemTree;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.PotionContents;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;

/**
 * Says what is illegal about an item, sorted into categories, without ever changing it. Used by every evidence
 * scan (/ng scanplayers, the chunk scan, /ng scanhere). Thread-safe: it only reads the item it is given (the
 * chunk scan hands it copies on a background thread).
 */
public final class IllegalItemInspector {

    public enum Category {
        ILLEGAL_ENCHANT("ILLEGAL ENCHANT"),
        UNBREAKABLE("UNBREAKABLE"),
        ILLEGAL_POTION("ILLEGAL POTION"),
        CREATIVE_ONLY("CREATIVE-ONLY ITEM"),
        OVERSTACKED("OVERSTACKED"),
        CUSTOM_ATTRIBUTES("CUSTOM ATTRIBUTES");

        public final String label;

        Category(String label) {
            this.label = label;
        }
    }

    /** One thing that is wrong with an item. */
    public record Problem(Category category, String detail) {
        @Override
        public String toString() {
            return "[" + category.label + "] " + detail;
        }
    }

    /** One illegal item: where it is, what it is, everything wrong with it. */
    public record Finding(String where, String item, List<Problem> problems) {
        public String line() {
            List<String> parts = new ArrayList<>();
            problems.forEach(p -> parts.add(p.toString()));
            return where + ": " + item + ": " + String.join(", ", parts);
        }
    }

    private final NovaGuardPlugin plugin;

    public IllegalItemInspector(NovaGuardPlugin plugin) {
        this.plugin = plugin;
    }

    /** @return the finding, or null when the item is legal (or empty). The item is never changed. */
    public Finding inspect(String where, ItemStack item) {
        if (item == null || item.isEmpty()) {
            return null;
        }
        List<Problem> problems = new ArrayList<>();
        // Enchantments, Unbreakable, attributes: the fixer's own rules, also inside shulkers and bundles.
        ItemSanitizer.Result fix = plugin.fingerprints().sanitizer().sanitizeAlways(item);
        for (String change : fix.changes()) {
            problems.add(new Problem(categoryOf(change), change));
        }
        typeChecks(item, "", 0, plugin.settings(), problems);
        return problems.isEmpty() ? null : new Finding(where, describe(item), List.copyOf(problems));
    }

    /** Potion, creative-only and stack size checks, for the item and the items inside it. */
    private void typeChecks(ItemStack item, String inside, int depth, Settings settings, List<Problem> problems) {
        if (settings.illegalSkipPluginItems && plugin.fingerprints().sanitizer().hasForeignData(item)) {
            return; // Another plugin's item (crates, custom items): theirs to manage, never evidence.
        }
        String name = item.getType().name().toLowerCase(Locale.ROOT);
        if (settings.creativeOnly.contains(item.getType())) {
            problems.add(new Problem(Category.CREATIVE_ONLY, name + " x" + item.getAmount()
                + " cannot be obtained in survival" + inside));
        }
        if (item.getAmount() > item.getMaxStackSize()) {
            problems.add(new Problem(Category.OVERSTACKED, item.getAmount() + " in one stack, the most is "
                + item.getMaxStackSize() + inside));
        }
        PotionContents potion = item.getData(DataComponentTypes.POTION_CONTENTS);
        if (potion != null && !potion.customEffects().isEmpty()) {
            List<String> effects = new ArrayList<>();
            for (PotionEffect effect : potion.customEffects()) {
                effects.add(effect.getType().getKey().getKey() + " " + (effect.getAmplifier() + 1)
                    + (effect.isInfinite() ? " forever" : " for " + effect.getDuration() / 20 + " s"));
            }
            problems.add(new Problem(Category.ILLEGAL_POTION, "custom effects (brewing cannot make these): "
                + String.join(", ", effects) + inside));
        }
        if (depth < ItemTree.MAX_DEPTH) {
            List<ItemStack> children = ItemTree.children(item);
            if (children != null) {
                for (ItemStack child : children) {
                    if (child != null && !child.isEmpty()) {
                        typeChecks(child, " (inside " + name + ")", depth + 1, settings, problems);
                    }
                }
            }
        }
    }

    /** The sanitizer's change texts: "Unbreakable removed", "custom attributes removed", else enchantments. */
    static Category categoryOf(String change) {
        if (change.startsWith("Unbreakable removed")) {
            return Category.UNBREAKABLE;
        }
        if (change.startsWith("custom attributes removed")) {
            return Category.CUSTOM_ATTRIBUTES;
        }
        return Category.ILLEGAL_ENCHANT;
    }

    /** "herobrine: 3 [ILLEGAL ENCHANT], 1 [CREATIVE-ONLY ITEM]" */
    public static String summary(List<Finding> findings) {
        Map<Category, Integer> counts = new EnumMap<>(Category.class);
        for (Finding finding : findings) {
            finding.problems().forEach(p -> counts.merge(p.category(), 1, Integer::sum));
        }
        List<String> parts = new ArrayList<>();
        counts.forEach((category, count) -> parts.add(count + " [" + category.label + "]"));
        return String.join(", ", parts);
    }

    /** True when an item could be illegal at all: cheap, so the chunk scan only copies these. */
    public boolean worthCopying(ItemStack item) {
        if (item == null || item.isEmpty()) {
            return false;
        }
        return item.hasItemMeta() || plugin.settings().creativeOnly.contains(item.getType())
            || item.getAmount() > item.getMaxStackSize();
    }

    static String describe(ItemStack item) {
        StringBuilder text = new StringBuilder(item.getType().name().toLowerCase(Locale.ROOT));
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            Component name = meta.displayName();
            if (name != null) {
                text.append(" \"").append(PlainTextComponentSerializer.plainText().serialize(name)).append('"');
            }
        }
        if (item.getAmount() > 1) {
            text.append(" x").append(item.getAmount());
        }
        return text.toString();
    }
}
