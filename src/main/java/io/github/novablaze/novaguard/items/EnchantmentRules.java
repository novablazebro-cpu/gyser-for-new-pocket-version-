package io.github.novablaze.novaguard.items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * Decides which enchantments on one item are illegal. Pure logic with no Minecraft classes, so it can be
 * unit-tested: the caller describes each enchantment (its level, its vanilla maximum, whether it fits the
 * item) and how enchantments conflict, and gets back the list of fixes to apply.
 *
 * <p>An enchantment is illegal when:
 * <ol>
 *   <li>its level is above the maximum (Sharpness 6 or 10 when the maximum is 5), or 0 / negative;</li>
 *   <li>it does not fit the item (Sharpness on a pickaxe, Mending on a stick);</li>
 *   <li>it conflicts with another enchantment on the same item (Sharpness + Smite, Mending + Infinity).</li>
 * </ol>
 */
public final class EnchantmentRules {

    /** What to do with an enchantment whose level is too high. */
    public enum OverLevel {
        /** Remove the enchantment completely. */
        REMOVE,
        /** Lower it to the maximum (Sharpness 10 becomes Sharpness 5). */
        CAP
    }

    /**
     * One enchantment found on an item.
     *
     * @param key      its id, e.g. {@code minecraft:sharpness}
     * @param level    the level on the item
     * @param maxLevel the highest legal level (from Minecraft's registry or a config override)
     * @param fitsItem whether this enchantment may be on this kind of item at all
     */
    public record Entry(String key, int level, int maxLevel, boolean fitsItem) {
    }

    /**
     * One change to make.
     *
     * @param key      the enchantment to change
     * @param newLevel the level to set, or 0 to remove the enchantment
     * @param reason   a short explanation for staff, e.g. "level 10 > max 5"
     */
    public record Fix(String key, int newLevel, String reason) {
        public boolean removes() {
            return newLevel == 0;
        }
    }

    /**
     * Which rules are switched on.
     *
     * @param overLevel      what to do with too-high levels, or null to allow any level
     * @param checkWrongItem remove enchantments that do not fit the item
     * @param checkConflicts remove the weaker of two conflicting enchantments
     * @param maxOverrides   per-enchantment maximums that replace the vanilla ones (key → max level)
     */
    public record Policy(OverLevel overLevel, boolean checkWrongItem, boolean checkConflicts,
                         Map<String, Integer> maxOverrides) {
        public Policy {
            maxOverrides = Map.copyOf(maxOverrides);
        }
    }

    private EnchantmentRules() {
    }

    /**
     * Returns the fixes for one item, empty when every enchantment is legal. The result is always the same
     * for the same input (enchantments are checked in a fixed order), so the same item is fixed the same way
     * on every server restart.
     *
     * @param conflicts tells whether two enchantment keys conflict with each other
     */
    public static List<Fix> judge(List<Entry> entries, BiPredicate<String, String> conflicts, Policy policy) {
        List<Fix> fixes = new ArrayList<>();
        // Stronger enchantments first, so on a conflict the stronger one is kept.
        List<Entry> ordered = new ArrayList<>(entries);
        ordered.sort(Comparator.comparingInt(Entry::level).reversed().thenComparing(Entry::key));

        List<Entry> kept = new ArrayList<>();
        for (Entry entry : ordered) {
            int max = policy.maxOverrides().getOrDefault(entry.key(), entry.maxLevel());
            if (entry.level() <= 0) {
                fixes.add(new Fix(entry.key(), 0, "impossible level " + entry.level()));
                continue;
            }
            if (policy.checkWrongItem() && !entry.fitsItem()) {
                fixes.add(new Fix(entry.key(), 0, "does not fit this item"));
                continue;
            }
            int level = entry.level();
            if (policy.overLevel() != null && level > max) {
                if (policy.overLevel() == OverLevel.REMOVE || max <= 0) {
                    fixes.add(new Fix(entry.key(), 0, "level " + level + " > max " + max));
                    continue;
                }
                fixes.add(new Fix(entry.key(), max, "level " + level + " > max " + max + ", lowered"));
                level = max;
            }
            if (policy.checkConflicts()) {
                Entry clash = firstConflict(entry, kept, conflicts);
                if (clash != null) {
                    // A capped enchantment that is then removed: replace the cap fix with a removal.
                    fixes.removeIf(fix -> fix.key().equals(entry.key()));
                    fixes.add(new Fix(entry.key(), 0, "conflicts with " + shortName(clash.key())));
                    continue;
                }
            }
            kept.add(new Entry(entry.key(), level, max, entry.fitsItem()));
        }
        return fixes;
    }

    private static Entry firstConflict(Entry entry, List<Entry> kept, BiPredicate<String, String> conflicts) {
        for (Entry other : kept) {
            if (conflicts.test(entry.key(), other.key())) {
                return other;
            }
        }
        return null;
    }

    /** {@code minecraft:sharpness} → {@code sharpness}. Other namespaces are kept, so datapack ones stay clear. */
    public static String shortName(String key) {
        return key.startsWith("minecraft:") ? key.substring("minecraft:".length()) : key;
    }
}
