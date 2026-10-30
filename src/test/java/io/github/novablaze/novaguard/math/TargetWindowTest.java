package io.github.novablaze.novaguard.math;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TargetWindowTest {

    @Test
    void sameTargetCountsOnce() {
        TargetWindow w = new TargetWindow();
        w.record(1, 0, 250);
        w.record(1, 10, 250);
        assertEquals(1, w.record(1, 20, 250));
    }

    @Test
    void distinctTargetsInsideWindowAreCounted() {
        TargetWindow w = new TargetWindow();
        w.record(1, 0, 250);
        w.record(2, 50, 250);
        assertEquals(3, w.record(3, 100, 250));
    }

    @Test
    void targetsOutsideWindowAreForgotten() {
        TargetWindow w = new TargetWindow();
        w.record(1, 0, 250);
        w.record(2, 50, 250);
        assertEquals(1, w.record(3, 1000, 250));
    }
}
