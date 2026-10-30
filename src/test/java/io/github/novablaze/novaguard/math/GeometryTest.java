package io.github.novablaze.novaguard.math;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GeometryTest {

    private final Box box = new Box(0, 0, 0, 1, 2, 1);

    @Test
    void pointInsideBoxHasZeroDistance() {
        assertEquals(0.0, Geometry.distanceToBox(0.5, 1, 0.5, box), 1e-9);
    }

    @Test
    void distanceIsMeasuredToTheNearestFace() {
        assertEquals(3.0, Geometry.distanceToBox(4, 1, 0.5, box), 1e-9);
    }

    @Test
    void distanceToCornerUsesAllAxes() {
        assertEquals(Math.sqrt(3), Geometry.distanceToBox(-1, -1, -1, box), 1e-9);
    }

    @Test
    void angleBetweenSameDirectionIsZero() {
        assertEquals(0.0, Geometry.angleDegrees(1, 0, 0, 5, 0, 0), 1e-6);
    }

    @Test
    void angleBetweenOppositeDirectionsIs180() {
        assertEquals(180.0, Geometry.angleDegrees(1, 0, 0, -1, 0, 0), 1e-6);
    }

    @Test
    void angleWithZeroVectorIsZero() {
        assertEquals(0.0, Geometry.angleDegrees(0, 0, 0, 1, 0, 0), 1e-9);
    }
}
