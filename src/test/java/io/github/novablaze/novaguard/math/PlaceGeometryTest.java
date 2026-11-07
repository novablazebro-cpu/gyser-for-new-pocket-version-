package io.github.novablaze.novaguard.math;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlaceGeometryTest {

    /** A full block at 0,63,0. */
    private static final Box CUBE = new Box(0, 63, 0, 1, 64, 1);

    @Test
    void placingOnTopWhileStandingAboveIsVisible() {
        assertTrue(PlaceGeometry.faceVisible(0.5, 65.62, 0.5, CUBE, 0, 1, 0, 0.1));
    }

    @Test
    void placingAgainstTheBottomFaceFromAboveIsImpossible() {
        assertFalse(PlaceGeometry.faceVisible(0.5, 65.62, 0.5, CUBE, 0, -1, 0, 0.1));
    }

    @Test
    void bridgingBackwardsAgainstTheSideFaceIsVisible() {
        assertTrue(PlaceGeometry.faceVisible(1.7, 64.62, 0.5, CUBE, 1, 0, 0, 0.1));
    }

    @Test
    void sideFacePointingAwayIsImpossible() {
        assertFalse(PlaceGeometry.faceVisible(-0.8, 64.62, 0.5, CUBE, 1, 0, 0, 0.1));
    }

    @Test
    void marginForgivesTheEyeExactlyOnThePlane() {
        assertTrue(PlaceGeometry.faceVisible(1.0, 64.62, 0.5, CUBE, 1, 0, 0, 0.1));
    }

    @Test
    void topOfASlabOneLevelUpIsVisible() {
        // Feet at y=64, eye at 65.62; bottom slab at y=65 has its top at 65.5: visible.
        Box slab = new Box(0, 65, 0, 1, 65.5, 1);
        assertTrue(PlaceGeometry.faceVisible(1.3, 65.62, 0.5, slab, 0, 1, 0, 0.1));
        // A full block there would hide its top (65 + 1 = 66 > eye).
        assertFalse(PlaceGeometry.faceVisible(1.3, 65.62, 0.5, new Box(0, 65, 0, 1, 66, 1), 0, 1, 0, 0.1));
    }
}
