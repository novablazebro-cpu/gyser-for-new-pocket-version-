package io.github.novablaze.novaguard.math;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * Decides which enchantments of one item are impossible in survival and how to fix them.
 * The limits (max level, supported items, conflicts) come from the server's own enchantment registry,
 * so vanilla rules and datapack enchantments are always followed exactly; nothing is hard-coded here.
 */
public final class EnchantRules {

    private EnchantRules() {
    }

    /**
     * One enchantment on the item.
     *
     * @param supported whether this item type can carry the enchantment at all (ignored for books)
     */
    public record Enchant(String key, int level, int maxLevel, boolean supported) {
    }

    /** @param keep enchantment key -> level that stays on the item; {@code changes} is empty when nothing is wrong */
    public record Fix(Map<String, Integer> keep, List<String> changes) {
        public boolean changed() {
            return !changes.isEmpty();
        }
    }

    /**
     * @param conflicts true when two enchantment keys cannot be on one item together
     * @param book enchanted book: any enchantment may be stored, but levels and conflicts still apply
     */
    public static Fix fix(List<Enchant> enchants, BiPredicate<String, String> conflicts, boolean book) {
        List<String> changes = new ArrayList<>();
        // Higher levels are kept first when two enchantments conflict; ties are decided by name (stable).
        List<Enchant> order = new ArrayList<>(enchants);
        order.sort(Comparator.comparingInt((Enchant e) -> Math.min(e.level(), e.maxLevel())).reversed()
            .thenComparing(Enchant::key));
        Map<String, Integer> kept = new LinkedHashMap<>();
        for (Enchant e : order) {
            String name = pretty(e.key());
            if (e.level() < 1) {
                changes.add(name + " " + e.level() + " removed (impossible level)");
                continue;
            }
            if (!book && !e.supported()) {
                changes.add(name + " " + e.level() + " removed (cannot be on this item)");
                continue;
            }
            String clash = null;
            for (String other : kept.keySet()) {
                if (conflicts.test(e.key(), other)) {
                    clash = other;
                    break;
                }
            }
            if (clash != null) {
                changes.add(name + " " + e.level() + " removed (cannot be together with " + pretty(clash) + ")");
                continue;
            }
            int level = e.level();
            if (level > e.maxLevel()) {
                changes.add(name + " " + level + " -> " + e.maxLevel());
                level = e.maxLevel();
            }
            kept.put(e.key(), level);
        }
        // Keep the item's original order for the enchantments that stay.
        Map<String, Integer> keep = new LinkedHashMap<>();
        for (Enchant e : enchants) {
            Integer level = kept.get(e.key());
            if (level != null) {
                keep.put(e.key(), level);
            }
        }
        return new Fix(keep, List.copyOf(changes));
    }

    /** "minecraft:fire_aspect" -> "Fire Aspect" (other namespaces are kept: "myplugin:foo" -> "myplugin:Foo"). */
    public static String pretty(String key) {
        StringBuilder out = new StringBuilder();
        int colon = key.indexOf(':');
        if (colon >= 0) {
            String namespace = key.substring(0, colon);
            key = key.substring(colon + 1);
            if (!namespace.equals("minecraft")) {
                out.append(namespace).append(':');
            }
        }
        int start = out.length();
        for (String part : key.split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            if (out.length() > start) {
                out.append(' ');
            }
            out.append(part.substring(0, 1).toUpperCase(Locale.ROOT)).append(part.substring(1));
        }
        return out.toString();
    }
}
