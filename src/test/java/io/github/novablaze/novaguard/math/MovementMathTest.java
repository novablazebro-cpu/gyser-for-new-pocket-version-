package io.github.novablaze.novaguard.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MovementMathTest {

    @Test
    void walkingAttributeAssumesPossibleSprint() {
        // 0.1 attribute * 1.3 sprint * 43.17 * multiplier 1.0 = 5.61 b/s.
        assertEquals(5.612, MovementMath.allowedRate(0.1, false, 0.2, false, 1.0, 3.0), 0.01);
    }

    @Test
    void sprintModifierIsNotCountedTwice() {
        double walking = MovementMath.allowedRate(0.1, false, 0.2, false, 1.0, 3.0);
        double sprinting = MovementMath.allowedRate(0.13, true, 0.2, false, 1.0, 3.0);
        assertEquals(walking, sprinting, 0.01);
    }

    @Test
    void essentialsSpeedRaisesTheLimit() {
        double normal = MovementMath.allowedRate(0.1, false, 0.2, false, 1.0, 3.0);
        double fast = MovementMath.allowedRate(0.1, false, 0.6, false, 1.0, 3.0);
        assertEquals(normal * 3, fast, 0.01);
    }

    @Test
    void iceMultiplies() {
        double normal = MovementMath.allowedRate(0.1, false, 0.2, false, 1.0, 3.0);
        double ice = MovementMath.allowedRate(0.1, false, 0.2, true, 1.0, 3.0);
        assertEquals(normal * 3, ice, 0.01);
    }

    @Test
    void capacityGrowsWithPing() {
        assertTrue(MovementMath.capacity(7.0, 300) > MovementMath.capacity(7.0, 20));
    }

    @Test
    void capacityPingIsCapped() {
        assertEquals(MovementMath.capacity(7.0, 2000), MovementMath.capacity(7.0, 99999), 1e-9);
    }
}
