package io.github.novablaze.novaguard.math;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RunningStatsTest {

    @Test
    void emptyStatsAreZero() {
        RunningStats s = new RunningStats();
        assertEquals(0, s.count());
        assertEquals(0.0, s.mean(), 1e-9);
        assertEquals(0.0, s.stdDev(), 1e-9);
        assertEquals(0.0, s.max(), 1e-9);
    }

    @Test
    void meanMaxAndStdDev() {
        RunningStats s = new RunningStats();
        for (double v : new double[] {2, 4, 4, 4, 5, 5, 7, 9}) {
            s.add(v);
        }
        assertEquals(8, s.count());
        assertEquals(5.0, s.mean(), 1e-9);
        assertEquals(2.0, s.stdDev(), 1e-9); // population standard deviation
        assertEquals(9.0, s.max(), 1e-9);
    }

    @Test
    void restoredStatsContinueCorrectly() {
        RunningStats a = new RunningStats();
        a.add(1);
        a.add(3);
        RunningStats b = RunningStats.restore(a.count(), a.mean(), a.m2(), a.max());
        b.add(5);
        assertEquals(3.0, b.mean(), 1e-9);
        assertEquals(5.0, b.max(), 1e-9);
    }
}
