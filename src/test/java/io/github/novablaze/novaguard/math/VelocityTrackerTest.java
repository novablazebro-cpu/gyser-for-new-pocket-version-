package io.github.novablaze.novaguard.math;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VelocityTrackerTest {

    /** One knockback: returns true if this sample counted as "no knockback taken". */
    private static boolean hit(VelocityTracker tracker, double takenFraction) {
        tracker.start(0, 1, 0, 0.4, 500);
        double v = 0.4 * takenFraction;
        for (int tick = 0; tick < 8; tick++) {
            tracker.addMove(v, 0);
            v *= 0.546;
        }
        return tracker.finish();
    }

    @Test
    void legitKnockbackIsNeverSuspicious() {
        VelocityTracker tracker = new VelocityTracker();
        for (int i = 0; i < 10; i++) {
            assertFalse(hit(tracker, 1.0));
        }
    }

    @Test
    void zeroKnockbackThreeTimesInARowIsFlagged() {
        VelocityTracker tracker = new VelocityTracker();
        assertFalse(hit(tracker, 0));
        assertFalse(hit(tracker, 0));
        assertTrue(hit(tracker, 0));
    }

    @Test
    void oneLegitHitResetsTheStreak() {
        VelocityTracker tracker = new VelocityTracker();
        hit(tracker, 0);
        hit(tracker, 0);
        hit(tracker, 1.0);
        assertFalse(hit(tracker, 0));
    }

    @Test
    void movingSidewaysDoesNotCount() {
        VelocityTracker tracker = new VelocityTracker();
        tracker.start(0, 1, 0, 0.4, 500);
        tracker.addMove(0, 2.0);
        tracker.finish();
        tracker.start(0, 1, 0, 0.4, 500);
        tracker.addMove(0, 2.0);
        tracker.finish();
        tracker.start(0, 1, 0, 0.4, 500);
        tracker.addMove(0, 2.0);
        assertTrue(tracker.finish());
    }

    @Test
    void dueOnlyAfterDeadline() {
        VelocityTracker tracker = new VelocityTracker();
        tracker.start(1000, 1, 0, 0.4, 500);
        assertFalse(tracker.isDue(1200));
        assertTrue(tracker.isDue(1600));
    }

    @Test
    void holdingForwardIntoTheAttackerIsStillLegit() {
        VelocityTracker tracker = new VelocityTracker();
        for (int i = 0; i < 5; i++) {
            tracker.start(0, 1, 0, 0.4, 500);
            // Pushed back for a few ticks, then the player's own sprint wins and they move forward again.
            for (double step : new double[] {0.4, 0.15, 0.0, -0.15, -0.25, -0.28, -0.28, -0.28}) {
                tracker.addMove(step, 0);
            }
            assertFalse(tracker.finish(), "hit " + i);
        }
    }
}
