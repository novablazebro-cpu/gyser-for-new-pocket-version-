package io.github.novablaze.novaguard.math;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlaceGeometryTest {

    @Test
    void placingOnTopWhileStandingAboveIsVisible() {
        // Block at y=63, eye at 65.62: the top face (dy=+1) is seen.
        assertTrue(PlaceGeometry.faceVisible(0.5, 65.62, 0.5, 0, 63, 0, 0, 1, 0, 0.1));
    }

    @Test
    void placingAgainstTheBottomFaceFromAboveIsImpossible() {
        assertFalse(PlaceGeometry.faceVisible(0.5, 65.62, 0.5, 0, 63, 0, 0, -1, 0, 0.1));
    }

    @Test
    void bridgingBackwardsAgainstTheSideFaceIsVisible() {
        // Standing at x=1.7 on the new row, looking back down at the east face (dx=+1) of block x=0.
        assertTrue(PlaceGeometry.faceVisible(1.7, 64.62, 0.5, 0, 63, 0, 1, 0, 0, 0.1));
    }

    @Test
    void sideFacePointingAwayIsImpossible() {
        // Eye west of the block (x=-0.8) cannot see its east face.
        assertFalse(PlaceGeometry.faceVisible(-0.8, 64.62, 0.5, 0, 63, 0, 1, 0, 0, 0.1));
    }

    @Test
    void marginForgivesTheEyeExactlyOnThePlane() {
        assertTrue(PlaceGeometry.faceVisible(1.0, 64.62, 0.5, 0, 63, 0, 1, 0, 0, 0.1));
    }
}
