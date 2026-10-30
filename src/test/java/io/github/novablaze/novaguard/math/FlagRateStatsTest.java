package io.github.novablaze.novaguard.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.novablaze.novaguard.CheckType;
import org.junit.jupiter.api.Test;

class FlagRateStatsTest {

    private static final long HOUR = 3_600_000L;

    @Test
    void countsLastHourAndDay() {
        FlagRateStats stats = new FlagRateStats(0);
        stats.record(CheckType.FLY, 10 * HOUR);
        stats.record(CheckType.FLY, 10 * HOUR + 5);
        stats.record(CheckType.FLY, 5 * HOUR);
        assertEquals(2, stats.lastHour(CheckType.FLY, 10 * HOUR + 10));
        assertEquals(3, stats.lastDay(CheckType.FLY, 10 * HOUR + 10));
    }

    @Test
    void oldHoursAreForgotten() {
        FlagRateStats stats = new FlagRateStats(0);
        stats.record(CheckType.FLY, 0);
        assertEquals(0, stats.lastDay(CheckType.FLY, 30 * HOUR));
    }

    @Test
    void spikeNeedsHistoryAndAJump() {
        FlagRateStats stats = new FlagRateStats(0);
        for (int h = 0; h < 10; h++) {
            stats.record(CheckType.SPEED, h * HOUR);
        }
        long now = 10 * HOUR;
        for (int i = 0; i < 30; i++) {
            stats.record(CheckType.SPEED, now + i);
        }
        assertTrue(stats.spike(CheckType.SPEED, now + 100));
        assertFalse(stats.spike(CheckType.FLY, now + 100));
    }

    @Test
    void noSpikeRightAfterStartup() {
        FlagRateStats stats = new FlagRateStats(0);
        for (int i = 0; i < 50; i++) {
            stats.record(CheckType.SPEED, i);
        }
        assertFalse(stats.spike(CheckType.SPEED, 100));
    }
}
