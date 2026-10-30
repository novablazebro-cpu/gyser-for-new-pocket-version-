package io.github.novablaze.novaguard.core;

import java.util.Locale;

/** The last moves of a player, written next to a flag so a false positive can be understood later. */
public final class MoveTrace {

    private static final int CAPACITY = 40;

    private final long[] times = new long[CAPACITY];
    private final double[] x = new double[CAPACITY];
    private final double[] y = new double[CAPACITY];
    private final double[] z = new double[CAPACITY];
    private final boolean[] clientGround = new boolean[CAPACITY];
    private int next;
    private int size;

    public void add(long timeMs, double px, double py, double pz, boolean onGround) {
        times[next] = timeMs;
        x[next] = px;
        y[next] = py;
        z[next] = pz;
        clientGround[next] = onGround;
        next = (next + 1) % CAPACITY;
        size = Math.min(size + 1, CAPACITY);
    }

    /** The last {@code count} moves as "+ms dx dy dz G/A" (G = client says on ground, A = in air). */
    public String describe(long nowMs, int count) {
        StringBuilder out = new StringBuilder();
        int shown = Math.min(count, size - 1);
        for (int i = shown; i >= 1; i--) {
            int current = (next - i + CAPACITY) % CAPACITY;
            int previous = (current - 1 + CAPACITY) % CAPACITY;
            out.append(String.format(Locale.ROOT, " [-%dms d=%.2f,%.3f,%.2f %s]", nowMs - times[current],
                x[current] - x[previous], y[current] - y[previous], z[current] - z[previous],
                clientGround[current] ? "G" : "A"));
        }
        return out.toString();
    }
}
