package io.github.novablaze.novaguard.math;

/** Ring buffer of a player's recent hitboxes, used to judge hits against where a target was. */
public final class PositionHistory {

    private final long[] times;
    private final Box[] boxes;
    private int next;
    private int size;

    public PositionHistory(int capacity) {
        this.times = new long[capacity];
        this.boxes = new Box[capacity];
    }

    public void add(long timeMs, Box box) {
        times[next] = timeMs;
        boxes[next] = box;
        next = (next + 1) % boxes.length;
        size = Math.min(size + 1, boxes.length);
    }

    public int size() {
        return size;
    }

    public void clear() {
        java.util.Arrays.fill(boxes, null);
        size = 0;
        next = 0;
    }

    /**
     * The recorded box closest to the point, considering boxes recorded at or after {@code fromMs}
     * plus always the newest one (where the target is now). Null when nothing is recorded.
     */
    public Box closestBox(double px, double py, double pz, long fromMs) {
        if (size == 0) {
            return null;
        }
        int newest = (next - 1 + boxes.length) % boxes.length;
        Box best = boxes[newest];
        double bestDistance = Geometry.distanceToBox(px, py, pz, best);
        for (int i = 0; i < size; i++) {
            int index = (newest - i + boxes.length) % boxes.length;
            if (times[index] < fromMs) {
                continue;
            }
            double distance = Geometry.distanceToBox(px, py, pz, boxes[index]);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = boxes[index];
            }
        }
        return best;
    }
}
