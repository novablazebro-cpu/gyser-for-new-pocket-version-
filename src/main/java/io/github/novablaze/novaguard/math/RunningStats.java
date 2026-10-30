package io.github.novablaze.novaguard.math;

/**
 * Count, mean, standard deviation and maximum of a stream of numbers without storing them
 * (Welford's method: constant memory, numerically stable).
 */
public final class RunningStats {

    private long count;
    private double mean;
    private double m2;
    private double max;

    public static RunningStats restore(long count, double mean, double m2, double max) {
        RunningStats stats = new RunningStats();
        stats.count = Math.max(0, count);
        stats.mean = mean;
        stats.m2 = Math.max(0, m2);
        stats.max = max;
        return stats;
    }

    public void add(double value) {
        count++;
        double delta = value - mean;
        mean += delta / count;
        m2 += delta * (value - mean);
        max = count == 1 ? value : Math.max(max, value);
    }

    public long count() {
        return count;
    }

    public double mean() {
        return count == 0 ? 0.0 : mean;
    }

    /** Population standard deviation: how spread out the values are. */
    public double stdDev() {
        return count == 0 ? 0.0 : Math.sqrt(m2 / count);
    }

    public double max() {
        return count == 0 ? 0.0 : max;
    }

    /** Internal sum of squared differences, only for saving and restoring. */
    public double m2() {
        return m2;
    }
}
