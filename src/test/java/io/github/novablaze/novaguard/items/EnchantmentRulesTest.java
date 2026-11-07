package io.github.novablaze.novaguard.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.novablaze.novaguard.items.EnchantmentRules.Entry;
import io.github.novablaze.novaguard.items.EnchantmentRules.Fix;
import io.github.novablaze.novaguard.items.EnchantmentRules.OverLevel;
import io.github.novablaze.novaguard.items.EnchantmentRules.Policy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.Test;

class EnchantmentRulesTest {

    private static final String SHARPNESS = "minecraft:sharpness";
    private static final String SMITE = "minecraft:smite";
    private static final String UNBREAKING = "minecraft:unbreaking";
    private static final String MENDING = "minecraft:mending";
    private static final String INFINITY = "minecraft:infinity";

    /** The two vanilla conflict groups these tests use: damage enchantments, and mending/infinity. */
    private static final BiPredicate<String, String> CONFLICTS = (a, b) -> {
        Set<String> damage = Set.of(SHARPNESS, SMITE);
        Set<String> bow = Set.of(MENDING, INFINITY);
        return !a.equals(b) && (damage.contains(a) && damage.contains(b) || bow.contains(a) && bow.contains(b));
    };

    private static final Policy REMOVE = new Policy(OverLevel.REMOVE, true, true, Map.of());
    private static final Policy CAP = new Policy(OverLevel.CAP, true, true, Map.of());

    @Test
    void legalItemNeedsNoFix() {
        List<Fix> fixes = EnchantmentRules.judge(List.of(
            new Entry(SHARPNESS, 5, 5, true), new Entry(UNBREAKING, 3, 3, true)), CONFLICTS, REMOVE);
        assertTrue(fixes.isEmpty());
    }

    @Test
    void sharpnessTenIsRemoved() {
        List<Fix> fixes = EnchantmentRules.judge(List.of(new Entry(SHARPNESS, 10, 5, true)), CONFLICTS, REMOVE);
        assertEquals(List.of(new Fix(SHARPNESS, 0, "level 10 > max 5")), fixes);
    }

    @Test
    void sharpnessSixIsRemovedToo() {
        List<Fix> fixes = EnchantmentRules.judge(List.of(new Entry(SHARPNESS, 6, 5, true)), CONFLICTS, REMOVE);
        assertEquals(1, fixes.size());
        assertTrue(fixes.get(0).removes());
    }

    @Test
    void capModeLowersInsteadOfRemoving() {
        List<Fix> fixes = EnchantmentRules.judge(List.of(new Entry(SHARPNESS, 10, 5, true)), CONFLICTS, CAP);
        assertEquals(List.of(new Fix(SHARPNESS, 5, "level 10 > max 5, lowered")), fixes);
    }

    @Test
    void zeroAndNegativeLevelsAreRemoved() {
        List<Fix> fixes = EnchantmentRules.judge(List.of(
            new Entry(SHARPNESS, 0, 5, true), new Entry(UNBREAKING, -3, 3, true)), CONFLICTS, CAP);
        assertEquals(2, fixes.size());
        assertTrue(fixes.stream().allMatch(Fix::removes));
    }

    @Test
    void enchantmentOnWrongItemIsRemoved() {
        // e.g. Sharpness on a pickaxe: level is fine, item is not.
        List<Fix> fixes = EnchantmentRules.judge(List.of(new Entry(SHARPNESS, 3, 5, false)), CONFLICTS, REMOVE);
        assertEquals(List.of(new Fix(SHARPNESS, 0, "does not fit this item")), fixes);
    }

    @Test
    void wrongItemCheckCanBeSwitchedOff() {
        Policy policy = new Policy(OverLevel.REMOVE, false, true, Map.of());
        assertTrue(EnchantmentRules.judge(List.of(new Entry(SHARPNESS, 3, 5, false)), CONFLICTS, policy).isEmpty());
    }

    @Test
    void conflictKeepsTheStrongerEnchantment() {
        List<Fix> fixes = EnchantmentRules.judge(List.of(
            new Entry(SMITE, 2, 5, true), new Entry(SHARPNESS, 4, 5, true)), CONFLICTS, REMOVE);
        assertEquals(List.of(new Fix(SMITE, 0, "conflicts with sharpness")), fixes);
    }

    @Test
    void conflictTieIsDecidedByNameSoItIsAlwaysTheSame() {
        List<Fix> fixes = EnchantmentRules.judge(List.of(
            new Entry(MENDING, 1, 1, true), new Entry(INFINITY, 1, 1, true)), CONFLICTS, REMOVE);
        // "infinity" sorts before "mending", so infinity is kept.
        assertEquals(List.of(new Fix(MENDING, 0, "conflicts with infinity")), fixes);
    }

    @Test
    void cappedEnchantmentThatAlsoConflictsIsRemovedOnce() {
        List<Fix> fixes = EnchantmentRules.judge(List.of(
            new Entry(SHARPNESS, 9, 5, true), new Entry(SMITE, 10, 5, true)), CONFLICTS, CAP);
        // smite 10 sorts first and is capped to 5; sharpness 9 is then capped and conflicts: one removal.
        assertEquals(List.of(new Fix(SMITE, 5, "level 10 > max 5, lowered"),
            new Fix(SHARPNESS, 0, "conflicts with smite")), fixes);
    }

    @Test
    void illegalEnchantmentDoesNotCauseAConflictRemoval() {
        // Sharpness 10 is removed for its level, so the legal Smite 3 must stay.
        List<Fix> fixes = EnchantmentRules.judge(List.of(
            new Entry(SHARPNESS, 10, 5, true), new Entry(SMITE, 3, 5, true)), CONFLICTS, REMOVE);
        assertEquals(List.of(new Fix(SHARPNESS, 0, "level 10 > max 5")), fixes);
    }

    @Test
    void configOverrideRaisesTheMaximum() {
        Policy policy = new Policy(OverLevel.REMOVE, true, true, Map.of(SHARPNESS, 7));
        assertTrue(EnchantmentRules.judge(List.of(new Entry(SHARPNESS, 7, 5, true)), CONFLICTS, policy).isEmpty());
        assertEquals(1, EnchantmentRules.judge(List.of(new Entry(SHARPNESS, 8, 5, true)), CONFLICTS, policy).size());
    }

    @Test
    void overLevelCheckCanBeSwitchedOff() {
        Policy policy = new Policy(null, true, true, Map.of());
        assertTrue(EnchantmentRules.judge(List.of(new Entry(SHARPNESS, 255, 5, true)), CONFLICTS, policy).isEmpty());
    }

    @Test
    void shortNameDropsOnlyTheMinecraftNamespace() {
        assertEquals("sharpness", EnchantmentRules.shortName(SHARPNESS));
        assertEquals("mypack:lifesteal", EnchantmentRules.shortName("mypack:lifesteal"));
    }
}
