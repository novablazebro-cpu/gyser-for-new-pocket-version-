package io.github.novablaze.novaguard.math;

/** Reach limits and the lag-compensation window. */
public final class ReachMath {

    /** Assumed hitbox margin for items without an attack_range component (codec default, the safe choice). */
    public static final double DEFAULT_MARGIN = 0.3;
    private static final long MAX_WINDOW_MS = 1000;

    private ReachMath() {
    }

    /**
     * @param interactionRange live {@code entity_interaction_range} attribute
     * @param itemMaxReach the held item's attack_range max reach, or 0 when it has none
     * @param itemMargin the held item's attack_range hitbox margin, or 0 when it has none
     * @param tolerance config tolerance
     */
    public static double limit(double interactionRange, double itemMaxReach, double itemMargin, double tolerance) {
        return Math.max(interactionRange, itemMaxReach) + Math.max(DEFAULT_MARGIN, itemMargin) + tolerance;
    }

    /** How far back in time (ms) a target may have been when the attacker saw it. */
    public static long windowMs(long pingMs) {
        long ping = Math.max(0, pingMs);
        return Math.min(MAX_WINDOW_MS, (long) (ping * 1.5) + 300);
    }
}
