package io.github.novablaze.novaguard.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SpeedBudgetTest {

    @Test
    void normalSprintingNeverOvershoots() {
        SpeedBudget budget = new SpeedBudget();
        long t = 0;
        for (int i = 0; i < 200; i++) {
            t += 50;
            assertEquals(0.0, budget.spend(t, 0.28, 7.0, 9.0), 1e-9);
        }
    }

    @Test
    void doubleSpeedIsCaughtWithinASecondAndAHalf() {
        SpeedBudget budget = new SpeedBudget();
        long t = 0;
        double over = 0;
        for (int i = 0; i < 30 && over == 0; i++) {
            t += 50;
            over = budget.spend(t, 0.7, 7.0, 9.0);
        }
        assertTrue(over > 0, "14 b/s against a 7 b/s budget must overshoot");
    }

    @Test
    void lagBurstIsCoveredBySavedBudget() {
        SpeedBudget budget = new SpeedBudget();
        budget.spend(0, 0, 7.0, 9.0);
        // 1 second with no packets, then 20 moves arrive at once (same millisecond).
        double total = 0;
        for (int i = 0; i < 20; i++) {
            total += budget.spend(1000, 0.28, 7.0, 9.0);
        }
        assertEquals(0.0, total, 1e-9);
    }

    @Test
    void timerHackSpendsFasterThanRealTime() {
        SpeedBudget budget = new SpeedBudget();
        long t = 0;
        double over = 0;
        // Timer: 40 moves per real second at full sprint distance.
        for (int i = 0; i < 200 && over == 0; i++) {
            t += 25;
            over = budget.spend(t, 0.33, 7.0, 9.0);
        }
        assertTrue(over > 0);
    }

    @Test
    void resetRefillsTheBudget() {
        SpeedBudget budget = new SpeedBudget();
        budget.spend(0, 0, 7.0, 9.0);
        budget.spend(0, 9.0, 7.0, 9.0);
        budget.fill(0);
        assertEquals(0.0, budget.spend(0, 8.9, 7.0, 9.0), 1e-9);
    }

    @Test
    void fillIsCappedAtCapacityOnNextSpend() {
        SpeedBudget budget = new SpeedBudget();
        budget.fill(0);
        // Capacity is 9: spending 9.5 at once must overshoot by 0.5, not be swallowed by MAX_VALUE.
        assertEquals(0.5, budget.spend(0, 9.5, 7.0, 9.0), 1e-9);
    }

    @Test
    void emptyBudgetMeansASpeedHackerFlagsAgainQuickly() {
        SpeedBudget budget = new SpeedBudget();
        budget.empty(0);
        // 2x speed right after a setback: overshoots within a few moves instead of after ~5 seconds.
        double over = 0;
        long t = 0;
        for (int i = 0; i < 6 && over == 0; i++) {
            t += 50;
            over = budget.spend(t, 0.7, 7.0, 9.0);
        }
        assertTrue(over > 0);
    }

    @Test
    void emptyBudgetDoesNotPunishNormalWalking() {
        SpeedBudget budget = new SpeedBudget();
        budget.empty(0);
        long t = 0;
        for (int i = 0; i < 100; i++) {
            t += 50;
            assertEquals(0.0, budget.spend(t, 0.28, 7.0, 9.0), 1e-9);
        }
    }
}
