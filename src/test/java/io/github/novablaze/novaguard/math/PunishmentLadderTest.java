package io.github.novablaze.novaguard.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PunishmentLadderTest {

    private static final List<Long> LADDER = List.of(3L, 10L, 30L);

    @Test
    void banLengthGrowsAndStaysAtTheLastStep() {
        assertEquals(3, PunishmentLadder.banDays(LADDER, 0));
        assertEquals(10, PunishmentLadder.banDays(LADDER, 1));
        assertEquals(30, PunishmentLadder.banDays(LADDER, 2));
        assertEquals(30, PunishmentLadder.banDays(LADDER, 9));
    }

    @Test
    void emptyLadderFallsBackTo30Days() {
        assertEquals(30, PunishmentLadder.banDays(List.of(), 0));
    }

    @Test
    void ipBanIsOnlyAskedForRepeatOffenders() {
        assertFalse(PunishmentLadder.asksForIpBan(1, 2));
        assertTrue(PunishmentLadder.asksForIpBan(2, 2));
        assertTrue(PunishmentLadder.asksForIpBan(5, 2));
        assertFalse(PunishmentLadder.asksForIpBan(5, 0));
    }

    @Test
    void ordinals() {
        assertEquals("1st", PunishmentLadder.ordinal(1));
        assertEquals("2nd", PunishmentLadder.ordinal(2));
        assertEquals("3rd", PunishmentLadder.ordinal(3));
        assertEquals("4th", PunishmentLadder.ordinal(4));
        assertEquals("11th", PunishmentLadder.ordinal(11));
        assertEquals("22nd", PunishmentLadder.ordinal(22));
    }
}
