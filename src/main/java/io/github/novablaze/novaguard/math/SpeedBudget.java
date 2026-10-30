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

    /** Full budget (capped to the real capacity on the next spend). Used after grace periods and teleports. */
    public void fill(long nowMs) {
        budget = Double.MAX_VALUE;
        lastMs = nowMs;
    }

    /** Empty budget. Used after a setback, so a speed hacker does not get a fresh second of free movement. */
    public void empty(long nowMs) {
        budget = 0;
        lastMs = nowMs;
    }
}
