package io.github.novablaze.novaguard.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MassIncidentBreakerTest {

    private static final long TEN_MIN = 600_000;

    @Test
    void oneHeavyDuperDoesNotTrip() {
        MassIncidentBreaker breaker = new MassIncidentBreaker();
        for (int i = 0; i < 20; i++) {
            assertFalse(breaker.record("cheater", i * 1_000L, 3, TEN_MIN));
        }
    }

    @Test
    void threeDifferentPlayersQuicklyTrip() {
        MassIncidentBreaker breaker = new MassIncidentBreaker();
        assertFalse(breaker.record("a", 0, 3, TEN_MIN));
        assertFalse(breaker.record("b", 60_000, 3, TEN_MIN));
        assertTrue(breaker.record("c", 120_000, 3, TEN_MIN));
        assertEquals(0, breaker.windowStartMs(-1));
    }

    @Test
    void playersFarApartDoNotTrip() {
        MassIncidentBreaker breaker = new MassIncidentBreaker();
        assertFalse(breaker.record("a", 0, 3, TEN_MIN));
        assertFalse(breaker.record("b", TEN_MIN + 1, 3, TEN_MIN));
        assertFalse(breaker.record("c", 2 * TEN_MIN + 2, 3, TEN_MIN));
    }
}
