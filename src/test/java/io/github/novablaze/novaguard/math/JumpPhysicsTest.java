package io.github.novablaze.novaguard.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JumpPhysicsTest {

    @Test
    void vanillaJumpReachesAboutOnePointTwoFiveBlocks() {
        // Known vanilla value: a normal jump peaks at ~1.2522 blocks.
        assertEquals(1.2522, JumpPhysics.maxJumpHeight(0.42, 0.08), 0.001);
    }

    @Test
    void jumpBoostTwoGoesHigher() {
        double boosted = JumpPhysics.maxJumpHeight(JumpPhysics.jumpVelocity(0.42, 2), 0.08);
        assertTrue(boosted > 2.0, "Jump Boost II should clear 2 blocks, got " + boosted);
    }

    @Test
    void noJumpBoostMeansBaseVelocity() {
        assertEquals(0.42, JumpPhysics.jumpVelocity(0.42, -1), 1e-9);
        assertEquals(0.52, JumpPhysics.jumpVelocity(0.42, 0), 1e-9);
    }

    @Test
    void zeroGravityIsCappedInsteadOfLoopingForever() {
        assertTrue(JumpPhysics.maxJumpHeight(0.42, 0.0) < 100);
    }

    @Test
    void normalJumpIsInTheAirAboutTwelveTicks() {
        int ticks = JumpPhysics.airTimeTicks(0.42, 0.08);
        assertTrue(ticks >= 11 && ticks <= 13, "got " + ticks);
    }

    @Test
    void jumpBoostFiveStaysUpLongerThanOneSecond() {
        assertTrue(JumpPhysics.airTimeTicks(JumpPhysics.jumpVelocity(0.42, 4), 0.08) > 20);
    }

    @Test
    void airTimeIsCappedForZeroGravity() {
        assertTrue(JumpPhysics.airTimeTicks(0.42, 0.0) <= 400);
    }

    @Test
    void noFallCorrectionNeverKills() {
        assertEquals(10.0, JumpPhysics.nonLethalDamage(10.0, 20.0), 1e-9);
        assertEquals(18.0, JumpPhysics.nonLethalDamage(50.0, 20.0), 1e-9);
        assertEquals(0.0, JumpPhysics.nonLethalDamage(5.0, 2.0), 1e-9);
        assertEquals(0.0, JumpPhysics.nonLethalDamage(5.0, 1.0), 1e-9);
    }
}
