package io.github.novablaze.novaguard.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class StrikeListTest {

    @Test
    void strikesInsideTheCooldownCountOnce() {
        StrikeList strikes = new StrikeList();
        assertTrue(strikes.add(1_000, 60_000));
        assertFalse(strikes.add(30_000, 60_000));
        assertTrue(strikes.add(61_000, 60_000));
        assertEquals(2, strikes.count());
    }

    @Test
    void sixSeparateDupesAreSixStrikes() {
        StrikeList strikes = new StrikeList();
        for (int i = 0; i < 6; i++) {
            strikes.add(i * 120_000L, 60_000);
        }
        assertEquals(6, strikes.count());
    }

    @Test
    void refundTakesBackOnlyRecentStrikes() {
        StrikeList strikes = StrikeList.of(List.of(100L, 5_000L, 9_000L));
        assertEquals(2, strikes.refundSince(5_000));
        assertEquals(List.of(100L), strikes.times());
    }
}
