package io.github.novablaze.novaguard.math;

/**
 * Momentum left over after a grace period ends mid-air (TNT cannon, slime-piston launcher, big knockback).
 * Real momentum must slow down every tick: gravity removes ~0.08 b/tick of upward speed and air drag ~9% of
 * sideways speed. A fly or speed cheat keeps its speed, so it fails here. Two moves in a row that do not slow
 * down are needed, so one odd packet is forgiven.
 */
public final class Momentum {

    /** Minimum slowdown per tick of upward speed that is demanded (real gravity gives at least 0.078). */
    private static final double MIN_VERTICAL_SLOWDOWN = 0.03;
    /** Sideways: at most 93% of the last step plus a little player input (real air drag keeps 91%). */
    private static final double HORIZONTAL_KEEP = 0.93;
    private static final double HORIZONTAL_INPUT = 0.03;

    private boolean active;
    private boolean rising;
    private double lastDy;
    private double lastHorizontal;
    private int verticalStrikes;
    private int horizontalStrikes;

    public void start(double dy, double horizontal) {
        active = true;
        rising = dy > 0;
        lastDy = dy;
        lastHorizontal = horizontal;
        verticalStrikes = 0;
        horizontalStrikes = 0;
    }

    public void stop() {
        active = false;
        rising = false;
    }

    public boolean active() {
        return active;
    }

    /** True while the player is still moving upward on leftover momentum. */
    public boolean risingPhase() {
        return active && rising;
    }

    /** @return true when the upward movement does not slow down like real momentum (two moves in a row) */
    public boolean vertical(double dy, double gravity) {
        if (!active || !rising) {
            return false;
        }
        if (dy <= 0) {
            rising = false;
            return false;
        }
        double required = lastDy - Math.min(MIN_VERTICAL_SLOWDOWN, Math.max(0.0, gravity) * 0.5);
        lastDy = dy;
        if (dy <= required + 1.0E-9) {
            verticalStrikes = 0;
            return false;
        }
        return ++verticalStrikes >= 2;
    }

    /**
     * @param normalStep the biggest sideways step a player can make without momentum (always allowed)
     * @return true when fast sideways movement does not slow down like real momentum (two moves in a row)
     */
    public boolean horizontal(double horizontal, double normalStep) {
        if (!active) {
            return false;
        }
        double allowed = lastHorizontal * HORIZONTAL_KEEP + HORIZONTAL_INPUT;
        lastHorizontal = horizontal;
        if (horizontal <= normalStep || horizontal <= allowed) {
            horizontalStrikes = 0;
            return false;
        }
        return ++horizontalStrikes >= 2;
    }
}
