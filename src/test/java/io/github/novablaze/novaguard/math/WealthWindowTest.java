package io.github.novablaze.novaguard.math;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WealthWindowTest {

    private static final long TEN_MIN = 600_000;

    @Test
    void firstSampleIsTheBaseline() {
        assertEquals(new WealthWindow.Change(0, 0), new WealthWindow().record(0, 5_000, 0, TEN_MIN));
    }

    @Test
    void gainIsMeasuredFromTheLowestPointInTheWindow() {
        WealthWindow w = new WealthWindow();
        w.record(0, 100, 0, TEN_MIN);
        w.record(60_000, 50, 0, TEN_MIN);
        assertEquals(650, w.record(120_000, 700, 0, TEN_MIN).gain());
    }

    @Test
    void oldSamplesFallOut() {
        WealthWindow w = new WealthWindow();
        w.record(0, 0, 0, TEN_MIN);
        w.record(TEN_MIN + 1, 1_000, 0, TEN_MIN);
        assertEquals(0, w.record(TEN_MIN + 2, 1_000, 0, TEN_MIN).gain());
    }

    @Test
    void pickedUpPartIsReportedButNeverMoreThanTheGain() {
        WealthWindow w = new WealthWindow();
        w.record(0, 100, 10, TEN_MIN);
        WealthWindow.Change change = w.record(60_000, 400, 1_000, TEN_MIN);
        assertEquals(300, change.gain());
        assertEquals(300, change.pickedUp());
    }

    @Test
    void restartForgetsTheOldLowPoint() {
        WealthWindow w = new WealthWindow();
        w.record(0, 0, 0, TEN_MIN);
        w.record(60_000, 1_000, 0, TEN_MIN);
        w.restart();
        assertEquals(0, w.record(120_000, 1_000, 0, TEN_MIN).gain());
    }
}
