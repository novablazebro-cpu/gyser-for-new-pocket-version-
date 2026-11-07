package io.github.novablaze.novaguard.math;

/**
 * Counts actions in the last second and how many seconds in a row they were above a limit. A single fast
 * second is ignored (lag delivers queued packets in one burst); only a rate that stays too high counts.
 */
public final class SustainedRate {

    private final ClickWindow window = new ClickWindow();
    private int strikes;
    private long lastStrikeMs = Long.MIN_VALUE / 2;

    /**
     * Records one action.
     *
     * @return how many seconds in a row the rate was above {@code max}, 0 when this action was within it
     */
    public int record(long nowMs, int max) {
        int rate = window.record(nowMs);
        if (rate <= max) {
            if (nowMs - lastStrikeMs > 1500) {
                strikes = 0;
            }
            return 0;
        }
        if (nowMs - lastStrikeMs >= 1000) {
            lastStrikeMs = nowMs;
            strikes++;
        }
        return strikes;
    }

    /** Seconds in a row above the limit, as of the last {@link #record}. */
    public int strikes() {
        return strikes;
    }
}
