package io.github.novablaze.novaguard.math;

/** Geometry for block placing. Pure math, unit-tested. */
public final class PlaceGeometry {

    private PlaceGeometry() {
    }

    /**
     * True when the eye is on the outside of the face that was clicked. A real player can only click a face
     * they can see; scaffold cheats often place against a face that points away from them (for example the
     * bottom of a block they stand above).
     *
     * @param box      the clicked block's real shape in world coordinates (a slab's top is at y + 0.5)
     * @param dx dy dz the direction of the face: from the clicked block to the new block (one of them is +-1)
     * @param margin   tolerance in blocks for rounding and pose changes
     */
    public static boolean faceVisible(double eyeX, double eyeY, double eyeZ, Box box,
                                      int dx, int dy, int dz, double margin) {
        return side(eyeX, box.minX(), box.maxX(), dx, margin)
            && side(eyeY, box.minY(), box.maxY(), dy, margin)
            && side(eyeZ, box.minZ(), box.maxZ(), dz, margin);
    }

    private static boolean side(double eye, double min, double max, int direction, double margin) {
        if (direction > 0) {
            return eye > max - margin;
        }
        if (direction < 0) {
            return eye < min + margin;
        }
        return true;
    }
}
