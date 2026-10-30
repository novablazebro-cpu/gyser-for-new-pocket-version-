package io.github.novablaze.novaguard.core;

import io.github.novablaze.novaguard.NovaGuardPlugin;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * Defensive wrapper around every part of NovaGuard:
 * an error in one part is logged once (with stack trace) instead of on every event, a part that keeps failing
 * switches itself off until /ng reload, and slow parts are reported so NovaGuard can never silently lag the server.
 */
public final class Safety {

    private static final long MINUTE_NANOS = 60_000_000_000L;

    private static final class Area {
        long calls;
        long totalNanos;
        long maxNanos;
        int errors;
        int windowErrors;
        long windowStart;
        boolean disabled;
        boolean warnedSlow;
    }

    private final NovaGuardPlugin plugin;
    private final Map<String, Area> areas = new LinkedHashMap<>();

    public Safety(NovaGuardPlugin plugin) {
        this.plugin = plugin;
    }

    /** Runs {@code body} as part of {@code name}, timing it and containing any error. */
    public void run(String name, Runnable body) {
        Area area = areas.computeIfAbsent(name, n -> new Area());
        if (area.disabled) {
            return;
        }
        long start = System.nanoTime();
        try {
            body.run();
        } catch (RuntimeException | LinkageError e) {
            failed(name, area, start, e);
        } finally {
            long took = System.nanoTime() - start;
            area.calls++;
            area.totalNanos += took;
            area.maxNanos = Math.max(area.maxNanos, took);
            if (took > plugin.settings().watchdogSlowNanos && !area.warnedSlow) {
                area.warnedSlow = true;
                plugin.getLogger().warning(String.format(Locale.ROOT,
                    "Watchdog: '%s' took %.1f ms for one event. Please report this (shown once).", name, took / 1e6));
            }
        }
    }

    private void failed(String name, Area area, long now, Throwable error) {
        area.errors++;
        if (area.errors == 1) {
            plugin.getLogger().log(Level.SEVERE, "Error in NovaGuard part '" + name
                + "' (further errors of this part are counted, not printed):", error);
        }
        if (now - area.windowStart > MINUTE_NANOS) {
            area.windowStart = now;
            area.windowErrors = 0;
        }
        if (++area.windowErrors >= plugin.settings().watchdogMaxErrors) {
            area.disabled = true;
            plugin.getLogger().severe("NovaGuard part '" + name + "' failed " + area.windowErrors
                + " times in a minute and was switched off. Fix the cause, then use /ng reload.");
        }
    }

    /** Switches every part back on (used by /ng reload). */
    public void reset() {
        for (Area area : areas.values()) {
            area.disabled = false;
            area.errors = 0;
            area.windowErrors = 0;
            area.warnedSlow = false;
        }
    }

    /** Human readable timing table for /ng health. */
    public List<String> report() {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Area> entry : areas.entrySet()) {
            Area a = entry.getValue();
            double average = a.calls == 0 ? 0 : a.totalNanos / 1e3 / a.calls;
            lines.add(String.format(Locale.ROOT, "%s: %s, %d events, avg %.1f us, max %.2f ms, %d errors",
                entry.getKey(), a.disabled ? "SWITCHED OFF" : "ok", a.calls, average, a.maxNanos / 1e6, a.errors));
        }
        return lines;
    }
}
