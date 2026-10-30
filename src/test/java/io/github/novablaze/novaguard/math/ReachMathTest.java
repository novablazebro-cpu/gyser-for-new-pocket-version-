package io.github.novablaze.novaguard.math;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ReachMathTest {

    @Test
    void defaultSwordLimit() {
        // 3.0 attribute + 0.3 default margin + 0.25 tolerance.
        assertEquals(3.55, ReachMath.limit(3.0, 0, 0, 0.25), 1e-9);
    }

    @Test
    void itemRangeWinsWhenLonger() {
        assertEquals(4.5 + 0.3 + 0.25, ReachMath.limit(3.0, 4.5, 0.125, 0.25), 1e-9);
    }

    @Test
    void attributeWinsWhenLonger() {
        assertEquals(6.0 + 0.3 + 0.25, ReachMath.limit(6.0, 4.5, 0.125, 0.25), 1e-9);
    }

    @Test
    void largerItemMarginIsUsed() {
        assertEquals(3.0 + 0.5 + 0.25, ReachMath.limit(3.0, 0, 0.5, 0.25), 1e-9);
    }

    @Test
    void windowGrowsWithPingAndIsCapped() {
        assertEquals(300, ReachMath.windowMs(0));
        assertEquals(450, ReachMath.windowMs(100));
        assertEquals(1000, ReachMath.windowMs(5000));
    }
}
