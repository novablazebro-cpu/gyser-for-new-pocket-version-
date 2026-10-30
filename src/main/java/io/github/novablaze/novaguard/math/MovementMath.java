package io.github.novablaze.novaguard.math;

/** Converts a player's live movement attributes into an allowed horizontal speed in blocks per second. */
public final class MovementMath {

    /** Blocks per second per point of the movement_speed attribute (0.1 = 4.317 b/s walking). */
    public static final double BLOCKS_PER_SECOND_PER_ATTRIBUTE = 43.17;
    private static final double SPRINT_FACTOR = 1.3;
    private static final double DEFAULT_WALK_SPEED = 0.2;
    private static final long MAX_PING_FOR_CAPACITY = 2000;

    private MovementMath() {
    }

    /**
     * @param movementSpeed live {@code movement_speed} attribute value (already includes Speed, Soul Speed, sprint)
     * @param sprinting whether the server thinks the player sprints (then the sprint bonus is already in the value)
     * @param walkSpeed Bukkit walk speed (0.2 default, changed by EssentialsX /speed)
     * @param onIce player touched ice recently
     * @param multiplier config slack for sprint-jumping and head-hitters
     * @param iceMultiplier config slack for ice
     */
    public static double allowedRate(double movementSpeed, boolean sprinting, double walkSpeed, boolean onIce,
                                     double multiplier, double iceMultiplier) {
        double rate = movementSpeed * (sprinting ? 1.0 : SPRINT_FACTOR) * BLOCKS_PER_SECOND_PER_ATTRIBUTE;
        rate *= Math.max(1.0, walkSpeed / DEFAULT_WALK_SPEED);
        rate *= multiplier;
        if (onIce) {
            rate *= iceMultiplier;
        }
        return rate;
    }

    /** How many blocks of movement may be "saved up": one second plus extra for ping, plus a flat cushion. */
    public static double capacity(double ratePerSecond, long pingMs) {
        double pingSeconds = Math.min(Math.max(pingMs, 0), MAX_PING_FOR_CAPACITY) / 1000.0;
        return ratePerSecond * (1.0 + pingSeconds * 1.5) + 1.5;
    }
}
