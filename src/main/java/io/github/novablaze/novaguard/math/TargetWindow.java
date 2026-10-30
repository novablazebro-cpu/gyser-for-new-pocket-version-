package io.github.novablaze.novaguard.math;

/** Remembers recently attacked entity ids, to count how many different targets were hit in a short window. */
public final class TargetWindow {

    private static final int CAPACITY = 16;

    private final int[] ids = new int[CAPACITY];
    private final long[] times = new long[CAPACITY];
    private int next;

    /** Records an attack and returns the number of distinct targets attacked within {@code windowMs}. */
    public int record(int entityId, long nowMs, long windowMs) {
        ids[next] = entityId;
        times[next] = nowMs;
        next = (next + 1) % CAPACITY;
        int[] seen = new int[CAPACITY];
        int distinct = 0;
        for (int i = 0; i < CAPACITY; i++) {
            if (times[i] == 0 && ids[i] == 0) {
                continue;
            }
            if (nowMs - times[i] > windowMs) {
                continue;
            }
            boolean known = false;
            for (int j = 0; j < distinct; j++) {
                if (seen[j] == ids[i]) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                seen[distinct++] = ids[i];
            }
        }
        return distinct;
    }
}
