package io.github.novablaze.novaguard.math;

/** Vanilla jump math: the client moves up by its velocity, then velocity = (velocity - gravity) * 0.98 each tick. */
public final class JumpPhysics {

    private static final double AIR_DRAG = 0.98;
    private static final int MAX_TICKS = 400;

    private JumpPhysics() {
    }

    /**
     * Initial upward velocity of a jump.
     *
     * @param jumpStrength value of the {@code jump_strength} attribute (0.42 by default)
     * @param jumpBoostAmplifier amplifier of Jump Boost, or -1 when the player has none
     */
    public static double jumpVelocity(double jumpStrength, int jumpBoostAmplifier) {
        return jumpStrength + 0.1 * (jumpBoostAmplifier + 1);
    }

    /** Highest point a jump with this start velocity reaches, in blocks above the take-off point. */
    public static double maxJumpHeight(double velocity, double gravity) {
        double height = 0.0;
        double v = velocity;
        for (int tick = 0; tick < MAX_TICKS && v > 0; tick++) {
            height += v;
            v = (v - gravity) * AIR_DRAG;
        }
        return height;
    }
}
