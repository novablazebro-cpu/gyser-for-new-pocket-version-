package io.github.novablaze.novaguard.monitor;

import io.github.novablaze.novaguard.CheckType;
import io.github.novablaze.novaguard.math.MonitorVerdict;
import java.util.Locale;

/** Builds the plain-text monitoring report. */
final class ReportWriter {

    private ReportWriter() {
    }

    /** @param action what NovaGuard did automatically (final reports), or null for an early report */
    static String write(MonitorRecord r, MonitorVerdict v, boolean finalReport, String action) {
        StringBuilder out = new StringBuilder();
        String type = r.bedrock == null ? "unknown (not seen online yet)"
            : r.bedrock ? "Bedrock (Geyser/Floodgate)" : "Java (cracked/offline)";
        line(out, "NovaGuard monitoring report" + (finalReport ? "" : " (EARLY - watch still running)"));
        line(out, "==========================================================");
        line(out, "Player:    " + r.name + " (" + r.uuid + ")");
        line(out, "Type:      " + type + (r.claimedTag != null ? "   [tagged by staff as: " + r.claimedTag + "]" : ""));
        line(out, "Watched:   " + MonitorManager.time(r.startMs) + "  ->  " + MonitorManager.time(r.endMs)
            + "   (started by " + r.startedBy + ")");
        line(out, String.format(Locale.ROOT, "Playtime:  %s in %d session(s)", duration(r.playtimeMs), r.sessions));
        line(out, String.format(Locale.ROOT, "Ping:      average %.0f ms, highest %.0f ms (%d samples)",
            r.ping.mean(), r.ping.max(), r.ping.count()));
        line(out, "Client:    " + (r.clientBrands.isEmpty() ? "unknown"
            : String.join(", ", MonitorManager.sorted(r.clientBrands)))
            + "   (reported by the client itself, easy to fake: informational only)");
        line(out, "");
        line(out, String.format(Locale.ROOT, "VERDICT:   %s   (score %.1f; suspicious from %.0f, likely from %.0f)",
            v.level().text().toUpperCase(Locale.ROOT), v.score(), MonitorVerdict.SUSPICIOUS_SCORE,
            MonitorVerdict.LIKELY_SCORE));
        line(out, "");
        line(out, "Evidence:");
        if (v.reasons().isEmpty()) {
            line(out, "  - nothing unusual was found");
        }
        v.reasons().forEach(reason -> line(out, "  - " + reason));
        if (!v.caveats().isEmpty()) {
            line(out, "");
            line(out, "Keep in mind:");
            v.caveats().forEach(caveat -> line(out, "  - " + caveat));
        }

        line(out, "");
        line(out, "Statistics");
        line(out, "----------");
        line(out, String.format(Locale.ROOT,
            "Combat:    %d checked hits, average distance %.2f, longest %.2f blocks (lag-compensated)",
            r.reach.count(), r.reach.mean(), r.reach.max()));
        line(out, String.format(Locale.ROOT,
            "           %d hits beyond the player's real reach even after lag compensation (%s)",
            r.strictOverHits, percent(r.strictOverHits, r.reach.count())));
        line(out, String.format(Locale.ROOT,
            "Aim:       average %.1f degrees off target, %d hits more than 45 degrees off (%s)",
            r.angle.mean(), r.aimOver45, percent(r.aimOver45, r.angle.count())));
        line(out, String.format(Locale.ROOT,
            "Clicking:  %d fighting seconds, average %.1f attacks/s, variation %.2f, peak %.0f",
            r.cps.count(), r.cps.mean(), r.cps.stdDev(), r.cps.max()));
        line(out, "           (humans vary a lot from second to second; variation below 0.6 at 8+ attacks/s looks robotic)");
        line(out, "Flags (near misses = suspicious moments below the alert threshold):");
        for (CheckType check : CheckType.values()) {
            if (check == CheckType.DUPE) {
                continue;
            }
            line(out, String.format(Locale.ROOT, "  %-12s %5d flags   %5d near misses", check.displayName(),
                r.flags[check.ordinal()], r.nearMisses[check.ordinal()]));
        }
        line(out, "Dupe alerts: " + r.dupeAlerts);
        line(out, String.format(Locale.ROOT,
            "Rotation:  %d camera turns, %.0f%% match a mouse sensitivity (humans: usually 90%%+; Bedrock ignored)",
            r.rotation.samples(), r.rotation.bestFit() * 100));
        line(out, "Strong detections in " + r.strongSessions + " of " + r.sessions + " session(s)");
        line(out, "");

        if (finalReport && action != null) {
            line(out, "Automatic action: " + action);
        }
        line(out, "This report is evidence, not proof. Spectate the player before taking action.");
        return out.toString();
    }

    private static void line(StringBuilder out, String text) {
        out.append(text).append(System.lineSeparator());
    }

    private static String percent(long part, long whole) {
        return whole == 0 ? "-" : String.format(Locale.ROOT, "%.1f%%", 100.0 * part / whole);
    }

    private static String duration(long ms) {
        long minutes = ms / 60000;
        return (minutes / 60) + "h " + (minutes % 60) + "m";
    }
}
