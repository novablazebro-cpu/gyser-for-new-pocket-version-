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
     * @param ax ay az  the block that was clicked
     * @param dx dy dz  the direction of the face: from the clicked block to the new block (one of them is +-1)
     * @param margin    tolerance in blocks for rounding and pose changes
     */
    public static boolean faceVisible(double eyeX, double eyeY, double eyeZ, int ax, int ay, int az,
                                      int dx, int dy, int dz, double margin) {
        return side(eyeX, ax, dx, margin) && side(eyeY, ay, dy, margin) && side(eyeZ, az, dz, margin);
    }

    private static boolean side(double eye, int block, int direction, double margin) {
        if (direction > 0) {
            return eye > block + 1 - margin;
        }
        if (direction < 0) {
            return eye < block + margin;
        }
        return true;
    }
}
