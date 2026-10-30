package io.github.novablaze.novaguard.math;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ClickWindowTest {

    @Test
    void countsClicksInTheLastSecond() {
        ClickWindow w = new ClickWindow();
        int cps = 0;
        for (int i = 0; i < 10; i++) {
            cps = w.record(i * 100L);
        }
        assertEquals(10, cps);
    }

    @Test
    void oldClicksFallOut() {
        ClickWindow w = new ClickWindow();
        for (int i = 0; i < 10; i++) {
            w.record(i * 10L);
        }
        assertEquals(1, w.record(5000));
    }

    @Test
    void neverExceedsCapacity() {
        ClickWindow w = new ClickWindow();
        int cps = 0;
        for (int i = 0; i < 500; i++) {
            cps = w.record(0);
        }
        assertEquals(ClickWindow.CAPACITY, cps);
    }
}
