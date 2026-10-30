package io.github.novablaze.novaguard.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.novablaze.novaguard.CheckType;
import org.junit.jupiter.api.Test;

class MonitorVerdictTest {

    private static MonitorVerdict.Evidence.Builder clean() {
        return MonitorVerdict.Evidence.builder().playtimeMinutes(300).averagePing(60)
            .hits(400).strictOverHits(2).aimHits(400).aimOver45(5)
            .cpsSeconds(100).cpsMean(7).cpsStdDev(2.2);
    }

    @Test
    void cleanPlayerHasNoEvidence() {
        MonitorVerdict v = MonitorVerdict.evaluate(clean().build());
        assertEquals(MonitorVerdict.Level.NO_EVIDENCE, v.level());
    }

    @Test
    void frequentLongHitsAndReachFlagsAreLikelyCheating() {
        MonitorVerdict v = MonitorVerdict.evaluate(clean().strictOverHits(80).flags(CheckType.REACH, 12).build());
        assertEquals(MonitorVerdict.Level.LIKELY_CHEATING, v.level());
        assertFalse(v.reasons().isEmpty());
    }

    @Test
    void robotClickingIsSuspicious() {
        MonitorVerdict v = MonitorVerdict.evaluate(clean().cpsMean(12).cpsStdDev(0.3).build());
        assertEquals(MonitorVerdict.Level.SUSPICIOUS, v.level());
    }

    @Test
    void highPingLowersTheScore() {
        MonitorVerdict normal = MonitorVerdict.evaluate(clean().strictOverHits(80).build());
        MonitorVerdict laggy = MonitorVerdict.evaluate(clean().strictOverHits(80).averagePing(400).build());
        assertTrue(laggy.score() < normal.score());
        assertFalse(laggy.caveats().isEmpty());
    }

    @Test
    void littlePlaytimeNeverGivesLikely() {
        MonitorVerdict v = MonitorVerdict.evaluate(clean().playtimeMinutes(10)
            .strictOverHits(80).flags(CheckType.REACH, 12).flags(CheckType.FLY, 30).build());
        assertEquals(MonitorVerdict.Level.SUSPICIOUS, v.level());
    }

    @Test
    void fewHitsDoNotCountAsReachEvidence() {
        MonitorVerdict v = MonitorVerdict.evaluate(clean().hits(10).aimHits(10).strictOverHits(5).build());
        assertEquals(MonitorVerdict.Level.NO_EVIDENCE, v.level());
    }

    @Test
    void bedrockMovementCountsHalf() {
        MonitorVerdict java = MonitorVerdict.evaluate(clean().flags(CheckType.FLY, 20).build());
        MonitorVerdict bedrock = MonitorVerdict.evaluate(clean().flags(CheckType.FLY, 20).bedrock(true).build());
        assertTrue(bedrock.score() < java.score());
    }

    @Test
    void noFallIsStrongEvidence() {
        MonitorVerdict v = MonitorVerdict.evaluate(clean().flags(CheckType.NOFALL, 3).build());
        assertEquals(MonitorVerdict.Level.SUSPICIOUS, v.level());
    }

    @Test
    void aimThatFitsNoMouseSensitivityIsSuspicious() {
        MonitorVerdict v = MonitorVerdict.evaluate(clean().rotationSamples(300).rotationFit(0.2).build());
        assertTrue(v.score() >= 2);
        assertTrue(v.caveats().stream().anyMatch(c -> c.contains("cinematic")));
    }

    @Test
    void humanRotationAddsNothing() {
        MonitorVerdict v = MonitorVerdict.evaluate(clean().rotationSamples(300).rotationFit(0.95).build());
        assertEquals(MonitorVerdict.Level.NO_EVIDENCE, v.level());
    }

    @Test
    void bedrockRotationIsIgnored() {
        MonitorVerdict v = MonitorVerdict.evaluate(clean().bedrock(true).rotationSamples(300).rotationFit(0.1).build());
        assertEquals(MonitorVerdict.Level.NO_EVIDENCE, v.level());
    }

    @Test
    void noKnockbackFlagsCount() {
        MonitorVerdict v = MonitorVerdict.evaluate(clean().flags(CheckType.VELOCITY, 3).build());
        assertEquals(MonitorVerdict.Level.SUSPICIOUS, v.level());
    }
}
