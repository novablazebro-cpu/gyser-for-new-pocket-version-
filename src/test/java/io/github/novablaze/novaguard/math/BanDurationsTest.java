package io.github.novablaze.novaguard.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BanDurationsTest {

    private static final long DAY = 86_400_000L;

    @Test
    void approvedRightAwayGetsTheFullBan() {
        assertEquals(Duration.ofDays(30), BanDurations.remaining(1_000, 30, 1_000));
    }

    @Test
    void approvedLateEndsWithTheAccountBan() {
        assertEquals(Duration.ofDays(1), BanDurations.remaining(0, 30, 29 * DAY));
    }

    @Test
    void neverShorterThanOneMinute() {
        assertEquals(Duration.ofMinutes(1), BanDurations.remaining(0, 30, 31 * DAY));
    }

    @Test
    void permanentStaysPermanent() {
        assertNull(BanDurations.remaining(0, 0, 5 * DAY));
    }
}
