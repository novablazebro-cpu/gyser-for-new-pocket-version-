package io.github.novablaze.novaguard.math;

import io.github.novablaze.novaguard.CheckType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns the evidence collected while monitoring a player into a verdict.
 * Points are added per kind of evidence; lag and thin data lower the result. It never claims proof.
 */
public record MonitorVerdict(Level level, double score, List<String> reasons, List<String> caveats) {

    public enum Level {
        NO_EVIDENCE("No evidence of cheating"),
        SUSPICIOUS("Suspicious - review manually"),
        LIKELY_CHEATING("Likely cheating");

        private final String text;

        Level(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    public static final double SUSPICIOUS_SCORE = 3.0;
    public static final double LIKELY_SCORE = 6.0;
    private static final int MIN_HITS = 30;
    private static final int MIN_CPS_SECONDS = 30;
    private static final double MIN_PLAYTIME_FOR_LIKELY = 30;

    /** All numbers the verdict looks at. */
    public record Evidence(double playtimeMinutes, double averagePing, boolean bedrock,
                           long hits, long strictOverHits, long aimHits, long aimOver45,
                           long cpsSeconds, double cpsMean, double cpsStdDev,
                           int[] flags, int dupeAlerts, long rotationSamples, double rotationFit) {

        public int flags(CheckType type) {
            return flags[type.ordinal()];
        }

        public static Builder builder() {
            return new Builder();
        }

        /** Builder so tests and callers only set what they know. */
        public static final class Builder {
            private double playtimeMinutes;
            private double averagePing;
            private boolean bedrock;
            private long hits;
            private long strictOverHits;
            private long aimHits;
            private long aimOver45;
            private long cpsSeconds;
            private double cpsMean;
            private double cpsStdDev;
            private final int[] flags = new int[CheckType.values().length];
            private int dupeAlerts;
            private long rotationSamples;
            private double rotationFit = 1.0;

            public Builder playtimeMinutes(double v) { playtimeMinutes = v; return this; }
            public Builder averagePing(double v) { averagePing = v; return this; }
            public Builder bedrock(boolean v) { bedrock = v; return this; }
            public Builder hits(long v) { hits = v; return this; }
            public Builder strictOverHits(long v) { strictOverHits = v; return this; }
            public Builder aimHits(long v) { aimHits = v; return this; }
            public Builder aimOver45(long v) { aimOver45 = v; return this; }
            public Builder cpsSeconds(long v) { cpsSeconds = v; return this; }
            public Builder cpsMean(double v) { cpsMean = v; return this; }
            public Builder cpsStdDev(double v) { cpsStdDev = v; return this; }
            public Builder flags(CheckType type, int count) { flags[type.ordinal()] = count; return this; }
            public Builder dupeAlerts(int v) { dupeAlerts = v; return this; }
            public Builder rotationSamples(long v) { rotationSamples = v; return this; }
            public Builder rotationFit(double v) { rotationFit = v; return this; }

            public Evidence build() {
                return new Evidence(playtimeMinutes, averagePing, bedrock, hits, strictOverHits, aimHits, aimOver45,
                    cpsSeconds, cpsMean, cpsStdDev, flags.clone(), dupeAlerts, rotationSamples, rotationFit);
            }
        }
    }

    public static MonitorVerdict evaluate(Evidence e) {
        List<String> reasons = new ArrayList<>();
        List<String> caveats = new ArrayList<>();
        double combat = 0;
        double movement = 0;

        if (e.hits() >= MIN_HITS) {
            double rate = (double) e.strictOverHits() / e.hits();
            if (rate > 0.10) {
                combat += 3;
                reasons.add(pct(rate) + " of hits were beyond normal reach even after lag compensation");
            } else if (rate > 0.03) {
                combat += 1;
                reasons.add(pct(rate) + " of hits were slightly beyond normal reach");
            }
        }
        if (e.flags(CheckType.REACH) >= 5) {
            combat += 3;
            reasons.add(e.flags(CheckType.REACH) + " Reach flags");
        }
        if (e.aimHits() >= MIN_HITS) {
            double rate = (double) e.aimOver45() / e.aimHits();
            if (rate > 0.15) {
                combat += 2;
                reasons.add(pct(rate) + " of hits landed on targets more than 45 degrees off-screen-center");
            }
        }
        if (e.flags(CheckType.AIM) >= 3) {
            combat += 2;
            reasons.add(e.flags(CheckType.AIM) + " Aim (killaura angle) flags");
        }
        if (e.flags(CheckType.WALL_HIT) >= 3) {
            combat += 2;
            reasons.add(e.flags(CheckType.WALL_HIT) + " hits through walls");
        }
        if (e.flags(CheckType.MULTI_TARGET) >= 3) {
            combat += 2;
            reasons.add(e.flags(CheckType.MULTI_TARGET) + " Multi-target (killaura switching) flags");
        }
        if (e.cpsSeconds() >= MIN_CPS_SECONDS && e.cpsMean() >= 8 && e.cpsStdDev() < 0.6) {
            combat += 3;
            reasons.add(String.format(Locale.ROOT,
                "robot-like clicking: %.1f attacks/s with almost no variation (%.2f) over %d seconds",
                e.cpsMean(), e.cpsStdDev(), e.cpsSeconds()));
        }
        if (!e.bedrock() && e.rotationSamples() >= 200 && e.rotationFit() < 0.5) {
            combat += 2;
            reasons.add(String.format(Locale.ROOT,
                "only %.0f%% of camera turns match any mouse sensitivity (aim assist / killaura rotations)",
                e.rotationFit() * 100));
            caveats.add("Rotation evidence is weaker if the player uses the cinematic camera, a zoom mod or a trackpad.");
        }
        if (e.flags(CheckType.VELOCITY) >= 3) {
            combat += 3;
            reasons.add(e.flags(CheckType.VELOCITY) + " Velocity flags (took no knockback from hits)");
        }
        if (e.flags(CheckType.BLOCK_REACH) >= 3) {
            combat += 2;
            reasons.add(e.flags(CheckType.BLOCK_REACH) + " BlockReach flags (placing/breaking too far away)");
        }
        if (e.flags(CheckType.AUTOCLICKER) >= 2) {
            combat += 2;
            reasons.add(e.flags(CheckType.AUTOCLICKER) + " AutoClicker flags");
        }

        int speed = e.flags(CheckType.SPEED);
        if (speed >= 30) {
            movement += 3;
            reasons.add(speed + " Speed flags");
        } else if (speed >= 10) {
            movement += 2;
            reasons.add(speed + " Speed flags");
        }
        int fly = e.flags(CheckType.FLY);
        if (fly >= 15) {
            movement += 3;
            reasons.add(fly + " Fly flags");
        } else if (fly >= 5) {
            movement += 2;
            reasons.add(fly + " Fly flags");
        }
        if (e.flags(CheckType.NOFALL) >= 2) {
            movement += 3;
            reasons.add(e.flags(CheckType.NOFALL) + " NoFall flags (lied about touching the ground)");
        }
        if (e.bedrock()) {
            movement /= 2;
            caveats.add("Bedrock player: Geyser's translation makes movement look stranger than it is,"
                + " so movement evidence counts half.");
        }

        double score = combat + movement;
        if (e.dupeAlerts() > 0) {
            score += 2;
            reasons.add(e.dupeAlerts() + " dupe alerts");
        }
        if (e.averagePing() > 250) {
            score -= 2;
            caveats.add(String.format(Locale.ROOT,
                "Average ping %.0f ms: high ping explains some long hits and movement flags.", e.averagePing()));
        }
        score = Math.max(0, score);

        Level level = score >= LIKELY_SCORE ? Level.LIKELY_CHEATING
            : score >= SUSPICIOUS_SCORE ? Level.SUSPICIOUS : Level.NO_EVIDENCE;
        if (e.playtimeMinutes() < MIN_PLAYTIME_FOR_LIKELY) {
            caveats.add(String.format(Locale.ROOT,
                "Only %.0f minutes of playtime were observed: too little to call it likely.", e.playtimeMinutes()));
            if (level == Level.LIKELY_CHEATING) {
                level = Level.SUSPICIOUS;
            }
        }
        if (e.hits() < MIN_HITS) {
            caveats.add("Fewer than " + MIN_HITS + " hits were seen, so combat statistics are not meaningful.");
        }
        return new MonitorVerdict(level, score, List.copyOf(reasons), List.copyOf(caveats));
    }

    private static String pct(double rate) {
        return String.format(Locale.ROOT, "%.1f%%", rate * 100);
    }
}
