package io.github.novablaze.novaguard.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class RotationAnalysisTest {

    /** Turns pitch the way the client does: a float angle changed by whole mouse steps. */
    private static void humanTurns(RotationAnalysis analysis, double sensitivity, int samples, long seed) {
        humanTurns(analysis, sensitivity, samples, seed, 40);
    }

    private static void humanTurns(RotationAnalysis analysis, double sensitivity, int samples, long seed, int maxPixels) {
        Random random = new Random(seed);
        double f = sensitivity * 0.6 + 0.2;
        double step = f * f * f * 8.0 * 0.15;
        float pitch = 0f;
        for (int i = 0; i < samples; i++) {
            int pixels = (random.nextInt(maxPixels) + 1) * (random.nextBoolean() ? 1 : -1);
            float next = (float) Math.max(-90, Math.min(90, pitch + pixels * step));
            analysis.add(pitch, next);
            pitch = next;
        }
    }

    @Test
    void humanMouseMatchesASensitivity() {
        RotationAnalysis analysis = new RotationAnalysis();
        humanTurns(analysis, 0.5, 300, 1);
        assertTrue(analysis.bestFit() > 0.9, "fit " + analysis.bestFit());
    }

    @Test
    void humanWithOddSensitivityMatchesToo() {
        RotationAnalysis analysis = new RotationAnalysis();
        humanTurns(analysis, 0.137, 300, 2);
        assertTrue(analysis.bestFit() > 0.9, "fit " + analysis.bestFit());
    }

    @Test
    void smoothedAimDoesNotMatchAnySensitivity() {
        RotationAnalysis analysis = new RotationAnalysis();
        Random random = new Random(3);
        for (int i = 0; i < 300; i++) {
            analysis.add(0f, (float) (random.nextDouble() * 8 + 0.05));
        }
        assertTrue(analysis.bestFit() < 0.5, "fit " + analysis.bestFit());
    }

    @Test
    void ignoresZeroHugeAndClampedTurns() {
        RotationAnalysis analysis = new RotationAnalysis();
        analysis.add(10f, 10f);
        analysis.add(-60f, 60f);
        analysis.add(80f, 90f);
        assertEquals(0, analysis.samples());
    }

    @Test
    void tooFewSamplesGivesFullFit() {
        RotationAnalysis analysis = new RotationAnalysis();
        analysis.add(0f, 1.234f);
        assertEquals(1.0, analysis.bestFit(), 1e-9);
    }

    @Test
    void lowSensitivityHumanWithBigTurnsStillMatches() {
        // Lowest sensitivity (step ~0.0096 degrees) and turns of up to 1000 pixels (~10 degrees):
        // float rounding must not make a human look like a cheater.
        RotationAnalysis analysis = new RotationAnalysis();
        humanTurns(analysis, 0.0, 300, 4, 1000);
        assertTrue(analysis.bestFit() > 0.9, "fit " + analysis.bestFit());
    }
}
