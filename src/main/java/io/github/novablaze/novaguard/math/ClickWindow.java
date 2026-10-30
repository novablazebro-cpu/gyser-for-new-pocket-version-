package io.github.novablaze.novaguard.math;

/** Counts attacks in the last second. Fixed memory: at most CAPACITY timestamps. */
public final class ClickWindow {

    public static final int CAPACITY = 64;
    private static final long WINDOW_MS = 1000;

    private final long[] times = new long[CAPACITY];
    private int start;
    private int size;

    /** Records a click and returns the clicks in the last second (including this one). */
    public int record(long nowMs) {
        while (size > 0 && nowMs - times[start] >= WINDOW_MS) {
            start = (start + 1) % CAPACITY;
            size--;
        }
        if (size == CAPACITY) {
            start = (start + 1) % CAPACITY;
            size--;
        }
        times[(start + size) % CAPACITY] = nowMs;
        size++;
        return size;
    }
}
