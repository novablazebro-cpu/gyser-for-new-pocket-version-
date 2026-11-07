package io.github.novablaze.novaguard.math;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SustainedRateTest {

    /** Records {@code perSecond} actions spread over one second starting at {@code startMs}; returns strikes. */
    private static int second(SustainedRate rate, long startMs, int perSecond, int max) {
        int strikes = 0;
        for (int i = 0; i < perSecond; i++) {
            strikes = rate.record(startMs + i * (1000L / perSecond), max);
        }
        return strikes;
    }

    @Test
    void normalRateNeverStrikes() {
        SustainedRate rate = new SustainedRate();
        for (int s = 0; s < 10; s++) {
            assertEquals(0, second(rate, s * 1000L, 10, 15));
        }
    }

    @Test
    void oneLagBurstIsOnlyOneStrike() {
        SustainedRate rate = new SustainedRate();
        for (int i = 0; i < 30; i++) {
            rate.record(5000, 15); // 30 queued packets arrive at once
        }
        assertEquals(1, rate.strikes());
        assertEquals(0, second(rate, 8000, 10, 15)); // back to normal: strikes reset
    }

    @Test
    void sustainedCheatRateBuildsStrikes() {
        SustainedRate rate = new SustainedRate();
        second(rate, 0, 40, 15);
        second(rate, 1000, 40, 15);
        assertEquals(3, second(rate, 2000, 40, 15));
    }
}
