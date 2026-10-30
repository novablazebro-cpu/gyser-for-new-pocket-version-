package io.github.novablaze.novaguard.math;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BoxTest {

    @Test
    void playerBoxIsCenteredOnFeet() {
        Box b = Box.ofEntity(10, 64, 10, 0.6, 1.8);
        assertEquals(9.7, b.minX(), 1e-9);
        assertEquals(10.3, b.maxX(), 1e-9);
        assertEquals(65.8, b.maxY(), 1e-9);
    }

    @Test
    void expandGrowsEveryFace() {
        Box b = new Box(0, 0, 0, 1, 1, 1).expand(0.5);
        assertEquals(-0.5, b.minY(), 1e-9);
        assertEquals(1.5, b.maxZ(), 1e-9);
    }

    @Test
    void centerIsMiddle() {
        Box b = new Box(0, 0, 0, 2, 4, 6);
        assertEquals(1, b.centerX(), 1e-9);
        assertEquals(2, b.centerY(), 1e-9);
        assertEquals(3, b.centerZ(), 1e-9);
    }
}
