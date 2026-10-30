package io.github.novablaze.novaguard.math;

/** Small 3D helpers used by the combat checks. */
public final class Geometry {

    private Geometry() {
    }

    /** Shortest distance from a point to a box (0 if the point is inside). */
    public static double distanceToBox(double px, double py, double pz, Box box) {
        double dx = px - clamp(px, box.minX(), box.maxX());
        double dy = py - clamp(py, box.minY(), box.maxY());
        double dz = pz - clamp(pz, box.minZ(), box.maxZ());
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** The point of the box closest to (px, py, pz), as {x, y, z}. */
    public static double[] closestPoint(double px, double py, double pz, Box box) {
        return new double[] {
            clamp(px, box.minX(), box.maxX()),
            clamp(py, box.minY(), box.maxY()),
            clamp(pz, box.minZ(), box.maxZ())
        };
    }

    /** Angle in degrees between two directions. Returns 0 if either direction has no length. */
    public static double angleDegrees(double ax, double ay, double az, double bx, double by, double bz) {
        double lengths = Math.sqrt(ax * ax + ay * ay + az * az) * Math.sqrt(bx * bx + by * by + bz * bz);
        if (lengths < 1.0E-9) {
            return 0.0;
        }
        double cos = (ax * bx + ay * by + az * bz) / lengths;
        return Math.toDegrees(Math.acos(clamp(cos, -1.0, 1.0)));
    }

    static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
