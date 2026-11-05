package io.github.novablaze.novaguard.math;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Safety fuse for the anti-dupe punishment. When many different players "dupe" within a few minutes, the
 * cause is almost never a group of cheaters: it is a server rollback (crash, restored backup) or another plugin
 * copying items. Then nobody should be warned or banned.
 */
public final class MassIncidentBreaker {

    private record Incident(String player, long timeMs) {
    }

    private final Deque<Incident> recent = new ArrayDeque<>();

    /**
     * Records a dupe incident of {@code player}.
     *
     * @return true when {@code players} or more different players had an incident within {@code windowMs}
     */
    public boolean record(String player, long nowMs, int players, long windowMs) {
        recent.addLast(new Incident(player, nowMs));
        while (!recent.isEmpty() && nowMs - recent.peekFirst().timeMs() > windowMs) {
            recent.removeFirst();
        }
        Set<String> distinct = new HashSet<>();
        for (Incident incident : recent) {
            distinct.add(incident.player());
        }
        return players > 0 && distinct.size() >= players;
    }

    /** Start time of the oldest incident still inside the window (strikes from then on are refunded). */
    public long windowStartMs(long fallbackMs) {
        return recent.isEmpty() ? fallbackMs : recent.peekFirst().timeMs();
    }

    public void clear() {
        recent.clear();
    }
}
