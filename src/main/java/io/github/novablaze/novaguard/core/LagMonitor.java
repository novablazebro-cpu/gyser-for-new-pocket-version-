package io.github.novablaze.novaguard.core;

import org.bukkit.Bukkit;

/** Caches the server TPS (the worse of the 1-minute average and the current tick time) once per second, so checks do not ask the server every event. */
public final class LagMonitor implements Runnable {

    private volatile double tps = 20.0;

    @Override
    public void run() {
        double[] values = Bukkit.getTPS();
        double minuteTps = values.length > 0 ? values[0] : 20.0;
        // The 1-minute average reacts slowly; the average tick time (ms per tick, 50 = healthy) reacts fast.
        double tickMs = Bukkit.getAverageTickTime();
        double tickTps = tickMs > 50.0 ? 1000.0 / tickMs : 20.0;
        tps = Math.min(20.0, Math.min(minuteTps, tickTps));
    }

    public double tps() {
        return tps;
    }
}
