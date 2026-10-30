package io.github.novablaze.novaguard.math;

/**
 * Lenient anti-knockback check. After a hit, the player must actually move some distance in the knockback
 * direction before the deadline. Catches "0% knockback" hacks; partial-knockback hacks need packet-level
 * timing and are out of reach. Three suspicious hits in a row are needed.
 */
public final class VelocityTracker {

    /** Real knockback moves a player ~2.2x the first-tick push on the ground; we only demand a fraction. */
    private static final double EXPECTED_FACTOR = 1.5;
    private static final double MIN_TAKEN = 0.1;
    private static final int STRIKES_TO_FLAG = 3;

    private boolean active;
    private long deadlineMs;
    private double dirX;
    private double dirZ;
    private double expected;
    private double moved;
    /** Furthest the player got pushed back. Players holding W toward the attacker come forward again later. */
    private double peak;
    private int strikes;

    /**
     * @param pushPerTick horizontal knockback strength in blocks per tick
     * @param windowMs how long the player has to show the knockback (ping-based)
     */
    public void start(long nowMs, double directionX, double directionZ, double pushPerTick, long windowMs) {
        double length = Math.hypot(directionX, directionZ);
        if (length < 1.0E-6 || pushPerTick <= 0) {
            active = false;
            return;
        }
        dirX = directionX / length;
        dirZ = directionZ / length;
        expected = pushPerTick * EXPECTED_FACTOR;
        moved = 0;
        peak = 0;
        deadlineMs = nowMs + windowMs;
        active = true;
    }

    public boolean active() {
        return active;
    }

    public boolean isDue(long nowMs) {
        return active && nowMs >= deadlineMs;
    }

    public void addMove(double dx, double dz) {
        if (active) {
            moved += dx * dirX + dz * dirZ;
            peak = Math.max(peak, moved);
        }
    }

    public void cancel() {
        active = false;
    }

    /** Ends the current sample. @return true when this was the third "no knockback" in a row */
    public boolean finish() {
        if (!active) {
            return false;
        }
        active = false;
        if (peak >= expected * MIN_TAKEN) {
            strikes = 0;
            return false;
        }
        if (++strikes >= STRIKES_TO_FLAG) {
            strikes = 0;
            return true;
        }
        return false;
    }
}
