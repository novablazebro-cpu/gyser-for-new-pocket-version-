package io.github.novablaze.novaguard.math;

import io.github.novablaze.novaguard.CheckType;

/** Violation levels (VL) of one player, one number per check. */
public final class ViolationTracker {

    private final double[] levels = new double[CheckType.values().length];

    public double add(CheckType type, double amount) {
        levels[type.ordinal()] += amount;
        return levels[type.ordinal()];
    }

    public double get(CheckType type) {
        return levels[type.ordinal()];
    }

    public void reset(CheckType type) {
        levels[type.ordinal()] = 0;
    }

    /** Lowers every level by {@code amount}, never below zero. */
    public void decay(double amount) {
        for (int i = 0; i < levels.length; i++) {
            levels[i] = Math.max(0, levels[i] - amount);
        }
    }
}
