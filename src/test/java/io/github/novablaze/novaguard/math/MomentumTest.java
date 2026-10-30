package io.github.novablaze.novaguard.math;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MomentumTest {

    private static final double GRAVITY = 0.08;

    @Test
    void realLaunchSlowsDownAndIsNeverFlagged() {
        Momentum m = new Momentum();
        double vy = 2.5;   // strong TNT/slime launch, blocks per tick
        double vh = 3.0;
        m.start(vy, vh);
        for (int tick = 0; tick < 60 && m.active(); tick++) {
            vy = (vy - GRAVITY) * 0.98;
            vh = vh * 0.91;
            assertFalse(m.vertical(vy, GRAVITY), "vertical tick " + tick);
            assertFalse(m.horizontal(vh, 0.4), "horizontal tick " + tick);
        }
    }

    @Test
    void flyCheatRisingAtConstantSpeedIsCaught() {
        Momentum m = new Momentum();
        m.start(0.5, 0);
        boolean flagged = false;
        for (int tick = 0; tick < 5 && !flagged; tick++) {
            flagged = m.vertical(0.5, GRAVITY);
        }
        assertTrue(flagged);
    }

    @Test
    void speedCheatKeepingSpeedIsCaught() {
        Momentum m = new Momentum();
        m.start(0, 2.0);
        boolean flagged = false;
        for (int tick = 0; tick < 5 && !flagged; tick++) {
            flagged = m.horizontal(2.0, 0.4);
        }
        assertTrue(flagged);
    }

    @Test
    void normalAirMovementIsAlwaysAllowed() {
        Momentum m = new Momentum();
        m.start(0, 1.0);
        for (int tick = 0; tick < 20; tick++) {
            assertFalse(m.horizontal(0.35, 0.4)); // below a normal sprint-jump step
        }
    }

    @Test
    void momentumEndsAtTheTopOfTheArc() {
        Momentum m = new Momentum();
        m.start(0.3, 0);
        m.vertical(0.2, GRAVITY);
        m.vertical(-0.05, GRAVITY);
        assertFalse(m.risingPhase());
    }

    @Test
    void oneOddPacketIsForgiven() {
        Momentum m = new Momentum();
        m.start(1.0, 0);
        assertFalse(m.vertical(1.0, GRAVITY)); // single non-decelerating move (packet quirk)
        assertFalse(m.vertical(0.85, GRAVITY));
    }
}
