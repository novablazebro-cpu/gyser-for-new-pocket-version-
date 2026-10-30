package io.github.novablaze.novaguard.math;

import java.util.Arrays;

/**
 * "GCD" rotation analysis. A mouse moves in whole pixels and every pixel turns the camera by one fixed step
 * that depends only on the sensitivity setting: step = (s * 0.6 + 0.2)^3 * 8 * 0.15 degrees. So a human's pitch
 * changes are (almost) always whole multiples of one step. Aim assist and killaura usually compute angles
 * directly, which fit no step. Evidence only: cinematic camera, zoom mods and some trackpads also break it.
 */
public final class RotationAnalysis {

    private static final int CAPACITY = 400;
    private static final int MIN_SAMPLES = 50;
    /** A delta fits when it is within 2% of a step from a whole multiple. */
    private static final double TOLERANCE = 0.02;
    /** Rounding error of the difference of two float pitch angles, with margin. */
    private static final double FLOAT_ERROR = 3.0E-5;
    /** Step sizes possible with sensitivity 0%..200% (0.0..1.0 in options). */
    private static final double MIN_STEP = 0.2 * 0.2 * 0.2 * 1.2;
    private static final double MAX_STEP = 0.8 * 0.8 * 0.8 * 1.2;
    private static final int SMALLEST_CANDIDATES = 20;
    private static final int MAX_PIXELS = 64;

    private final double[] deltas = new double[CAPACITY];
    private int size;
    private int next;

    /** Pitch is clamped to +-90 degrees: a turn that hits the limit is cut short and is not whole mouse steps. */
    private static final float PITCH_LIMIT = 89.9f;

    /**
     * Records one pitch change. Turns touching the +-90 degree limit, zero turns and very large turns
     * (teleports) are ignored.
     */
    public void add(float fromPitch, float toPitch) {
        if (Math.abs(fromPitch) >= PITCH_LIMIT || Math.abs(toPitch) >= PITCH_LIMIT) {
            return;
        }
        addDelta(toPitch - fromPitch);
    }

    private void addDelta(double delta) {
        double d = Math.abs(delta);
        if (d < 0.001 || d > 40) {
            return;
        }
        deltas[next] = d;
        next = (next + 1) % CAPACITY;
        size = Math.min(size + 1, CAPACITY);
    }

    public int samples() {
        return size;
    }

    /**
     * Fraction (0..1) of recorded turns that are whole multiples of the best-fitting mouse step.
     * Humans score above ~0.9. Returns 1.0 while there are too few samples to judge.
     */
    public double bestFit() {
        if (size < MIN_SAMPLES) {
            return 1.0;
        }
        double[] sorted = Arrays.copyOf(deltas, size);
        Arrays.sort(sorted);
        double best = 0;
        // The true step is one of the smallest turns divided by a small number of pixels.
        for (int i = 0; i < Math.min(SMALLEST_CANDIDATES, size); i++) {
            for (int pixels = 1; pixels <= MAX_PIXELS; pixels++) {
                double step = sorted[i] / pixels;
                if (step < MIN_STEP * 0.98 || step > MAX_STEP * 1.02) {
                    continue;
                }
                best = Math.max(best, fit(sorted, refine(sorted, refine(sorted, step, 64), 1024)));
            }
        }
        return best;
    }

    /**
     * Least-squares improvement of a step guess: step = sum(d * k) / sum(k^2) over turns that fit as k steps.
     * A guess taken from one small turn carries its float rounding error, and that error grows with every
     * multiple; big turns at low sensitivity (k in the hundreds) would then stop fitting for a real human.
     */
    private static double refine(double[] values, double step, int maxMultiple) {
        double sumDk = 0;
        double sumKk = 0;
        int used = 0;
        for (double value : values) {
            double whole = Math.rint(value / step);
            if (whole >= 1 && whole <= maxMultiple && Math.abs(value - whole * step) <= 0.1 * step) {
                sumDk += value * whole;
                sumKk += whole * whole;
                used++;
            }
        }
        return used >= 5 ? sumDk / sumKk : step;
    }

    private static double fit(double[] values, double step) {
        int fitting = 0;
        for (double value : values) {
            double whole = Math.rint(value / step);
            // 2% of a step, but never less than float precision of a pitch value (~1e-5 degrees, times 3).
            double tolerance = Math.max(TOLERANCE * step, FLOAT_ERROR);
            if (whole >= 1 && Math.abs(value - whole * step) <= tolerance) {
                fitting++;
            }
        }
        return (double) fitting / values.length;
    }

    /** Recorded turns, for saving. */
    public double[] values() {
        return Arrays.copyOf(deltas, size);
    }

    /** Restores saved turns (oldest first). */
    public void restore(double[] values) {
        for (double value : values) {
            addDelta(value);
        }
    }
}
