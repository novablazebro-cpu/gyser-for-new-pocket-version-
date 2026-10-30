package io.github.novablaze.novaguard.math;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.novablaze.novaguard.CheckType;
import org.junit.jupiter.api.Test;

class ViolationTrackerTest {

    @Test
    void addsAndReads() {
        ViolationTracker t = new ViolationTracker();
        t.add(CheckType.REACH, 2);
        assertEquals(3.5, t.add(CheckType.REACH, 1.5), 1e-9);
        assertEquals(0.0, t.get(CheckType.SPEED), 1e-9);
    }

    @Test
    void decayNeverGoesNegative() {
        ViolationTracker t = new ViolationTracker();
        t.add(CheckType.FLY, 1);
        t.decay(5);
        assertEquals(0.0, t.get(CheckType.FLY), 1e-9);
    }

    @Test
    void resetClearsOneCheck() {
        ViolationTracker t = new ViolationTracker();
        t.add(CheckType.FLY, 4);
        t.add(CheckType.SPEED, 4);
        t.reset(CheckType.FLY);
        assertEquals(0.0, t.get(CheckType.FLY), 1e-9);
        assertEquals(4.0, t.get(CheckType.SPEED), 1e-9);
    }
}
