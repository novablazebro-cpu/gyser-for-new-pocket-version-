package io.github.novablaze.novaguard.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.Test;

class EnchantRulesTest {

    /** Vanilla mace rules: density, breach, smite and bane exclude each other; wind burst fits with all. */
    private static final Set<String> MACE_EXCLUSIVE = Set.of("minecraft:density", "minecraft:breach",
        "minecraft:smite", "minecraft:bane_of_arthropods");
    private static final BiPredicate<String, String> MACE = (a, b) ->
        !a.equals(b) && MACE_EXCLUSIVE.contains(a) && MACE_EXCLUSIVE.contains(b);
    private static final BiPredicate<String, String> NONE = (a, b) -> false;

    private static EnchantRules.Enchant e(String key, int level, int max) {
        return new EnchantRules.Enchant("minecraft:" + key, level, max, true);
    }

    @Test
    void legalItemIsUntouched() {
        EnchantRules.Fix fix = EnchantRules.fix(List.of(e("sharpness", 5, 5), e("unbreaking", 3, 3)), NONE, false);
        assertFalse(fix.changed());
        assertEquals(Map.of("minecraft:sharpness", 5, "minecraft:unbreaking", 3), fix.keep());
    }

    @Test
    void sharpnessTenIsLoweredToMax() {
        EnchantRules.Fix fix = EnchantRules.fix(List.of(e("sharpness", 10, 5)), NONE, false);
        assertTrue(fix.changed());
        assertEquals(5, fix.keep().get("minecraft:sharpness"));
        assertEquals(List.of("Sharpness 10 -> 5"), fix.changes());
    }

    @Test
    void breachWithWindBurstIsLegal() {
        EnchantRules.Fix fix = EnchantRules.fix(List.of(e("breach", 4, 4), e("wind_burst", 3, 3)), MACE, false);
        assertFalse(fix.changed());
    }

    @Test
    void densityWithBreachKeepsTheHigherOne() {
        EnchantRules.Fix fix = EnchantRules.fix(List.of(e("breach", 4, 4), e("density", 5, 5), e("wind_burst", 3, 3)),
            MACE, false);
        assertTrue(fix.changed());
        assertEquals(Map.of("minecraft:density", 5, "minecraft:wind_burst", 3), fix.keep());
    }

    @Test
    void conflictTieIsDecidedByNameSoItIsStable() {
        EnchantRules.Fix a = EnchantRules.fix(List.of(e("density", 4, 5), e("breach", 4, 4)), MACE, false);
        EnchantRules.Fix b = EnchantRules.fix(List.of(e("breach", 4, 4), e("density", 4, 5)), MACE, false);
        assertEquals(a.keep(), b.keep());
        assertEquals(1, a.keep().size());
    }

    @Test
    void overMaxLevelsAreComparedAfterClamping() {
        // Breach 100 is really Breach 4 after the fix, so Density 5 wins.
        EnchantRules.Fix fix = EnchantRules.fix(List.of(e("breach", 100, 4), e("density", 5, 5)), MACE, false);
        assertEquals(Map.of("minecraft:density", 5), fix.keep());
    }

    @Test
    void unsupportedEnchantIsRemovedButNotOnBooks() {
        EnchantRules.Enchant knockbackOnStick = new EnchantRules.Enchant("minecraft:knockback", 1000, 2, false);
        assertTrue(EnchantRules.fix(List.of(knockbackOnStick), NONE, false).keep().isEmpty());
        EnchantRules.Fix book = EnchantRules.fix(List.of(knockbackOnStick), NONE, true);
        assertEquals(2, book.keep().get("minecraft:knockback"));
    }

    @Test
    void zeroOrNegativeLevelsAreRemoved() {
        EnchantRules.Fix fix = EnchantRules.fix(List.of(e("sharpness", 0, 5), e("looting", -3, 3)), NONE, false);
        assertTrue(fix.keep().isEmpty());
        assertEquals(2, fix.changes().size());
    }

    @Test
    void prettyNames() {
        assertEquals("Fire Aspect", EnchantRules.pretty("minecraft:fire_aspect"));
        assertEquals("Sharpness", EnchantRules.pretty("sharpness"));
        assertEquals("myplugin:Lifesteal", EnchantRules.pretty("myplugin:lifesteal"));
    }
}
