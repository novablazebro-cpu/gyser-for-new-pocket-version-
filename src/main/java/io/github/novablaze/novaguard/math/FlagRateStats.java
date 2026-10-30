package io.github.novablaze.novaguard.math;

import io.github.novablaze.novaguard.CheckType;

/**
 * Flags per check per hour for the last 24 hours. A sudden jump (after a server update, a new plugin...)
 * usually means a new false positive, not ten times more cheaters.
 */
public final class FlagRateStats {

    private static final long HOUR = 3_600_000L;
    private static final int HOURS = 24;
    private static final int SPIKE_MIN_FLAGS = 20;
    private static final double SPIKE_FACTOR = 5.0;

    private final long startMs;
    private final long[][] hourIds = new long[CheckType.values().length][HOURS];
    private final int[][] counts = new int[CheckType.values().length][HOURS];

    public FlagRateStats(long startMs) {
        this.startMs = startMs;
        for (long[] row : hourIds) {
            java.util.Arrays.fill(row, -1);
        }
    }

    public void record(CheckType type, long nowMs) {
        long hour = nowMs / HOUR;
        int slot = (int) (hour % HOURS);
        int t = type.ordinal();
        if (hourIds[t][slot] != hour) {
            hourIds[t][slot] = hour;
            counts[t][slot] = 0;
        }
        counts[t][slot]++;
    }

    public int lastHour(CheckType type, long nowMs) {
        long hour = nowMs / HOUR;
        int slot = (int) (hour % HOURS);
        return hourIds[type.ordinal()][slot] == hour ? counts[type.ordinal()][slot] : 0;
    }

    public int lastDay(CheckType type, long nowMs) {
        long hour = nowMs / HOUR;
        int total = 0;
        for (int slot = 0; slot < HOURS; slot++) {
            long id = hourIds[type.ordinal()][slot];
            if (id > hour - HOURS && id <= hour) {
                total += counts[type.ordinal()][slot];
            }
        }
        return total;
    }

    /** True when this hour has many more flags than the hourly average before it (needs 3+ hours of history). */
    public boolean spike(CheckType type, long nowMs) {
        if (nowMs - startMs < 3 * HOUR) {
            return false;
        }
        int current = lastHour(type, nowMs);
        if (current < SPIKE_MIN_FLAGS) {
            return false;
        }
        long knownHours = Math.min(HOURS - 1, (nowMs - startMs) / HOUR);
        double average = (double) (lastDay(type, nowMs) - current) / Math.max(1, knownHours);
        return current > SPIKE_FACTOR * Math.max(1.0, average);
    }
}
