package io.github.novablaze.novaguard.math;

/**
 * Token bucket for horizontal movement. The budget refills with real time and every move spends its distance.
 * A lag burst spends budget that was saved while packets were stuck, so laggy players do not flag;
 * speed and timer hacks spend faster than it refills.
 */
public final class SpeedBudget {

    private double budget;
    private long lastMs = -1;

    /**
     * @return how many blocks the move went over budget (0 when it was fine)
     */
    public double spend(long nowMs, double distance, double ratePerSecond, double capacity) {
        if (lastMs < 0) {
            lastMs = nowMs;
            budget = capacity;
        }
        double seconds = Math.max(0, nowMs - lastMs) / 1000.0;
        lastMs = Math.max(lastMs, nowMs);
        budget = Math.min(capacity, budget + ratePerSecond * seconds);
        budget -= distance;
        if (budget < 0) {
            double over = -budget;
            budget = 0;
            return over;
        }
        return 0.0;
    }

    /** Refills the budget. Passing {@code Double.MAX_VALUE} means "full": the next spend caps it at capacity. */
    public void reset(long nowMs, double capacity) {
        budget = capacity;
        lastMs = nowMs;
    }
}
