package io.github.novablaze.novaguard;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * A read-only snapshot of config.yml. /novaguard reload builds a new snapshot and swaps it in,
 * so no check ever sees a half-loaded config.
 */
public final class Settings {

    public record CheckSettings(boolean enabled, double kickVl) {
    }

    private final Map<CheckType, CheckSettings> checks = new EnumMap<>(CheckType.class);

    public final long alertCooldownMs;
    public final boolean alertConsole;
    public final boolean logToFile;
    public final double decayPerSecond;

    public final int burstMoves;
    public final long burstIntervalMs;
    public final long spikeGraceMs;
    public final double minTpsForMovement;
    public final int maxPingForMovement;

    public final boolean kickEnabled;
    public final int kickMaxPing;
    public final double kickMinTps;
    public final long kickJoinGraceMs;
    public final String kickMessage;

    public final double speedMultiplier;
    public final double speedIceMultiplier;
    public final double speedMinOvershoot;

    public final double flyJumpMargin;
    public final long flyHoverMs;
    public final double flyHoverMinDrop;

    public final int noFallMinSpoofs;

    public final double reachTolerance;
    public final double aimMaxAngle;
    public final int aimBuffer;
    public final int wallBuffer;
    public final int multiMaxTargets;
    public final long multiWindowMs;
    public final int maxCps;
    public final int cpsSeconds;

    public final long monitorDurationHours;
    public final boolean monitorKickOnLikely;
    public final String monitorKickMessage;

    public final boolean dupeEnabled;
    public final boolean fixOverstacked;
    public final boolean entityContainerGuard;
    public final boolean fingerprintsEnabled;
    public final boolean removeDuplicates;
    public final long fingerprintScanTicks;
    public final Set<Material> fingerprintMaterials;

    public Settings(FileConfiguration c, Logger logger) {
        alertCooldownMs = Math.max(0, c.getLong("alerts.cooldown-ms", 1500));
        alertConsole = c.getBoolean("alerts.console", true);
        logToFile = c.getBoolean("log-to-file", true);
        decayPerSecond = Math.max(0, c.getDouble("violations.decay-per-second", 0.5));

        burstMoves = Math.max(2, c.getInt("lag.burst-moves", 3));
        burstIntervalMs = Math.max(1, c.getLong("lag.burst-interval-ms", 10));
        spikeGraceMs = Math.max(0, c.getLong("lag.spike-grace-ms", 1500));
        minTpsForMovement = c.getDouble("lag.min-tps-for-movement", 18.0);
        maxPingForMovement = c.getInt("lag.max-ping-for-movement", 1000);

        kickEnabled = c.getBoolean("kick.enabled", true);
        kickMaxPing = c.getInt("kick.max-ping", 400);
        kickMinTps = c.getDouble("kick.min-tps", 17.0);
        kickJoinGraceMs = Math.max(0, c.getLong("kick.join-grace-seconds", 10)) * 1000L;
        kickMessage = c.getString("kick.message", "<red>Kicked by NovaGuard (<check>).</red>");

        for (CheckType type : CheckType.values()) {
            String path = "checks." + type.configKey();
            checks.put(type, new CheckSettings(c.getBoolean(path + ".enabled", true),
                Math.max(0, c.getDouble(path + ".kick-vl", 0))));
        }

        speedMultiplier = Math.max(1.0, c.getDouble("checks.speed.multiplier", 1.6));
        speedIceMultiplier = Math.max(1.0, c.getDouble("checks.speed.ice-multiplier", 3.0));
        speedMinOvershoot = Math.max(0, c.getDouble("checks.speed.min-overshoot", 0.3));

        flyJumpMargin = Math.max(0, c.getDouble("checks.fly.jump-margin", 0.6));
        flyHoverMs = Math.max(500, c.getLong("checks.fly.hover-ms", 1000));
        flyHoverMinDrop = Math.max(0, c.getDouble("checks.fly.hover-min-drop", 0.5));

        noFallMinSpoofs = Math.max(1, c.getInt("checks.nofall.min-spoofs", 3));

        reachTolerance = Math.max(0, c.getDouble("checks.reach.tolerance", 0.25));
        aimMaxAngle = Math.min(180, Math.max(10, c.getDouble("checks.aim.max-angle", 70)));
        aimBuffer = Math.max(1, c.getInt("checks.aim.buffer", 3));
        wallBuffer = Math.max(1, c.getInt("checks.wall-hit.buffer", 3));
        multiMaxTargets = Math.max(1, c.getInt("checks.multi-target.max-targets", 2));
        multiWindowMs = Math.max(50, c.getLong("checks.multi-target.window-ms", 250));
        // ClickWindow can count at most 64 attacks per second; a higher limit would silently disable the check.
        maxCps = Math.min(60, Math.max(5, c.getInt("checks.autoclicker.max-cps", 22)));
        cpsSeconds = Math.max(1, c.getInt("checks.autoclicker.seconds", 3));

        monitorDurationHours = Math.max(1, c.getLong("monitor.duration-hours", 72));
        monitorKickOnLikely = c.getBoolean("monitor.kick-on-likely", true);
        monitorKickMessage = c.getString("monitor.kick-message",
            "<red>NovaGuard: a 3-day review found likely cheating.</red>");

        dupeEnabled = c.getBoolean("anti-dupe.enabled", true);
        fixOverstacked = c.getBoolean("anti-dupe.fix-overstacked", true);
        entityContainerGuard = c.getBoolean("anti-dupe.entity-container-guard", true);
        fingerprintsEnabled = c.getBoolean("anti-dupe.fingerprints.enabled", true);
        removeDuplicates = c.getBoolean("anti-dupe.fingerprints.remove-duplicates", false);
        fingerprintScanTicks = Math.max(10, c.getLong("anti-dupe.fingerprints.scan-interval-seconds", 60)) * 20L;
        fingerprintMaterials = EnumSet.noneOf(Material.class);
        for (String name : c.getStringList("anti-dupe.fingerprints.materials")) {
            Material material = Material.matchMaterial(name.toUpperCase(Locale.ROOT));
            if (material == null) {
                logger.warning("Unknown material in anti-dupe.fingerprints.materials: " + name);
            } else {
                fingerprintMaterials.add(material);
            }
        }
        ConfigurationSection unused = c.getConfigurationSection("checks");
        if (unused == null) {
            logger.warning("config.yml has no 'checks' section, using defaults.");
        }
    }

    public CheckSettings check(CheckType type) {
        return checks.get(type);
    }

    public boolean enabled(CheckType type) {
        return checks.get(type).enabled();
    }
}
