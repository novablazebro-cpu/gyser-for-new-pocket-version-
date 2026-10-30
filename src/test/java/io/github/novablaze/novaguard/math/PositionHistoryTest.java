package io.github.novablaze.novaguard.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class PositionHistoryTest {

    @Test
    void emptyHistoryReturnsNull() {
        assertNull(new PositionHistory(8).closestBox(0, 0, 0, 0));
    }

    @Test
    void picksTheClosestBoxInsideTheWindow() {
        PositionHistory h = new PositionHistory(8);
        Box near = Box.ofEntity(2, 0, 0, 0.6, 1.8);
        Box far = Box.ofEntity(6, 0, 0, 0.6, 1.8);
        h.add(1000, near);
        h.add(1050, far);
        assertSame(near, h.closestBox(0, 1, 0, 900));
    }

    @Test
    void ignoresBoxesOlderThanTheWindowExceptTheNewest() {
        PositionHistory h = new PositionHistory(8);
        Box old = Box.ofEntity(1, 0, 0, 0.6, 1.8);
        Box newest = Box.ofEntity(6, 0, 0, 0.6, 1.8);
        h.add(100, old);
        h.add(200, newest);
        // Window starts after both: the newest is still used, because it is where the target is now.
        assertSame(newest, h.closestBox(0, 1, 0, 5000));
    }

    @Test
    void ringBufferOverwritesOldest() {
        PositionHistory h = new PositionHistory(2);
        Box a = Box.ofEntity(1, 0, 0, 0.6, 1.8);
        h.add(1, a);
        h.add(2, Box.ofEntity(5, 0, 0, 0.6, 1.8));
        h.add(3, Box.ofEntity(6, 0, 0, 0.6, 1.8));
        assertEquals(2, h.size());
        assertEquals(4.7, Geometry.distanceToBox(0, 1, 0, h.closestBox(0, 1, 0, 0)), 1e-9);
    }

    @Test
    void clearForgetsEverything() {
        PositionHistory h = new PositionHistory(4);
        h.add(1, Box.ofEntity(1, 0, 0, 0.6, 1.8));
        h.clear();
        assertNull(h.closestBox(0, 0, 0, 0));
    }
}
