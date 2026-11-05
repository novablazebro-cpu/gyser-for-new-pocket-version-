package io.github.novablaze.novaguard.math;

import java.util.ArrayList;
import java.util.List;

/**
 * Dupe / illegal-item warnings ("strikes") of one player. One dupe exploit often shows up in several scans a
 * few seconds apart, so strikes closer together than the cooldown count as one.
 */
public final class StrikeList {

    private final List<Long> times = new ArrayList<>();

    /** @return true when a new strike was counted, false when it fell inside the cooldown of the last one */
    public boolean add(long nowMs, long cooldownMs) {
        if (!times.isEmpty() && nowMs - times.get(times.size() - 1) < cooldownMs) {
            return false;
        }
        times.add(nowMs);
        return true;
    }

    public int count() {
        return times.size();
    }

    /** Takes back every strike given at or after {@code sinceMs} (used when a dupe wave was a server rollback). */
    public int refundSince(long sinceMs) {
        int before = times.size();
        times.removeIf(t -> t >= sinceMs);
        return before - times.size();
    }

    public void clear() {
        times.clear();
    }

    public List<Long> times() {
        return List.copyOf(times);
    }

    public static StrikeList of(List<Long> times) {
        StrikeList list = new StrikeList();
        times.stream().sorted().forEach(list.times::add);
        return list;
    }
}
