package io.github.novablaze.novaguard.math;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Value of one player's items over the last minutes. Only an alert for staff is ever based on this: trades, shops
 * and lucky mining trips also make a player richer fast.
 *
 * <p>The value already includes what the player moved into or out of containers (net), so emptying their own
 * chest does not count as a gain; a chest dupe (items in the chest AND in the inventory) does.
 */
public final class WealthWindow {

    /** {@code gain} = value now minus the lowest value inside the window; {@code pickedUp} = the part picked up. */
    public record Change(long gain, long pickedUp) {
    }

    private record Sample(long timeMs, long value, long pickedTotal) {
    }

    private final Deque<Sample> samples = new ArrayDeque<>();

    /**
     * @param value value now (inventory + ender chest + net value stored in containers)
     * @param pickedTotal everything this player picked up so far (only grows)
     */
    public Change record(long nowMs, long value, long pickedTotal, long windowMs) {
        while (!samples.isEmpty() && nowMs - samples.peekFirst().timeMs() > windowMs) {
            samples.removeFirst();
        }
        Sample lowest = null;
        for (Sample sample : samples) {
            if (lowest == null || sample.value() < lowest.value()) {
                lowest = sample;
            }
        }
        samples.addLast(new Sample(nowMs, value, pickedTotal));
        if (lowest == null) {
            return new Change(0, 0);
        }
        long gain = Math.max(0, value - lowest.value());
        long picked = Math.min(gain, Math.max(0, pickedTotal - lowest.pickedTotal()));
        return new Change(gain, picked);
    }

    /** After an alert: start counting again from the current value. */
    public void restart() {
        Sample last = samples.peekLast();
        samples.clear();
        if (last != null) {
            samples.addLast(last);
        }
    }
}
