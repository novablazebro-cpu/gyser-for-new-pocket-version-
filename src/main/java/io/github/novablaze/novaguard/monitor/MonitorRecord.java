package io.github.novablaze.novaguard.monitor;

import io.github.novablaze.novaguard.CheckType;
import io.github.novablaze.novaguard.math.MonitorVerdict;
import io.github.novablaze.novaguard.math.RotationAnalysis;
import io.github.novablaze.novaguard.math.RunningStats;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;

/** Everything collected about one monitored player. Saved to monitors.yml so it survives restarts. */
public final class MonitorRecord {

    private static final int MAX_BRANDS = 5;

    public final UUID uuid;
    public String name;
    /** What the staff member typed (cracked/bedrock), or null. */
    public String claimedTag;
    /** Detected type; null until the player has been seen online. */
    public Boolean bedrock;
    public String startedBy;
    public long startMs;
    public long endMs;
    public boolean pendingKick;

    public long playtimeMs;
    public int sessions;
    public long lastSeenMs;
    public final Set<String> clientBrands = new LinkedHashSet<>();

    public RunningStats ping = new RunningStats();
    public RunningStats reach = new RunningStats();
    public RunningStats angle = new RunningStats();
    public RunningStats cps = new RunningStats();
    public long strictOverHits;
    public long aimOver45;
    public final int[] flags = new int[CheckType.values().length];
    public final int[] nearMisses = new int[CheckType.values().length];
    public int dupeAlerts;
    /** Sessions in which a check reached its suspect VL (ban-wave confirmation needs several). */
    public int strongSessions;
    public int lastStrongSession;
    public final RotationAnalysis rotation = new RotationAnalysis();

    // Not saved: the attack counter of the current second.
    long currentSecond = -1;
    int attacksThisSecond;

    public MonitorRecord(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    /** Count attacks per second; seconds with at least 5 attacks are fighting seconds. */
    void recordAttack(long nowMs) {
        long second = nowMs / 1000;
        if (second != currentSecond) {
            flushSecond();
            currentSecond = second;
        }
        attacksThisSecond++;
    }

    void flushSecond() {
        if (attacksThisSecond >= 5) {
            cps.add(attacksThisSecond);
        }
        attacksThisSecond = 0;
    }

    void addBrand(String brand) {
        if (brand != null && !brand.isBlank() && clientBrands.size() < MAX_BRANDS) {
            clientBrands.add(brand.length() > 40 ? brand.substring(0, 40) : brand);
        }
    }

    MonitorVerdict.Evidence evidence() {
        MonitorVerdict.Evidence.Builder builder = MonitorVerdict.Evidence.builder()
            .playtimeMinutes(playtimeMs / 60000.0)
            .averagePing(ping.mean())
            .bedrock(Boolean.TRUE.equals(bedrock))
            .hits(reach.count())
            .strictOverHits(strictOverHits)
            .aimHits(angle.count())
            .aimOver45(aimOver45)
            .cpsSeconds(cps.count())
            .cpsMean(cps.mean())
            .cpsStdDev(cps.stdDev())
            .dupeAlerts(dupeAlerts)
            .rotationSamples(rotation.samples())
            .rotationFit(rotation.bestFit());
        for (CheckType type : CheckType.values()) {
            builder.flags(type, flags[type.ordinal()]);
        }
        return builder.build();
    }

    void save(ConfigurationSection s) {
        s.set("name", name);
        s.set("claimed-tag", claimedTag);
        s.set("bedrock", bedrock);
        s.set("started-by", startedBy);
        s.set("start", startMs);
        s.set("end", endMs);
        s.set("pending-kick", pendingKick);
        s.set("playtime-ms", playtimeMs);
        s.set("sessions", sessions);
        s.set("client-brands", List.copyOf(clientBrands));
        saveStats(s, "ping", ping);
        saveStats(s, "reach", reach);
        saveStats(s, "angle", angle);
        saveStats(s, "cps", cps);
        s.set("strict-over-hits", strictOverHits);
        s.set("aim-over-45", aimOver45);
        s.set("dupe-alerts", dupeAlerts);
        s.set("strong-sessions", strongSessions);
        s.set("last-strong-session", lastStrongSession);
        java.util.List<Double> turns = new java.util.ArrayList<>();
        for (double turn : rotation.values()) {
            turns.add(turn);
        }
        s.set("rotation", turns);
        for (CheckType type : CheckType.values()) {
            s.set("flags." + type.name(), flags[type.ordinal()]);
            s.set("near-misses." + type.name(), nearMisses[type.ordinal()]);
        }
    }

    static MonitorRecord load(UUID uuid, ConfigurationSection s) {
        MonitorRecord r = new MonitorRecord(uuid, s.getString("name", "unknown"));
        r.claimedTag = s.getString("claimed-tag");
        r.bedrock = s.isBoolean("bedrock") ? s.getBoolean("bedrock") : null;
        r.startedBy = s.getString("started-by", "console");
        r.startMs = s.getLong("start");
        r.endMs = s.getLong("end");
        r.pendingKick = s.getBoolean("pending-kick");
        r.playtimeMs = s.getLong("playtime-ms");
        r.sessions = s.getInt("sessions");
        s.getStringList("client-brands").forEach(r::addBrand);
        r.ping = loadStats(s, "ping");
        r.reach = loadStats(s, "reach");
        r.angle = loadStats(s, "angle");
        r.cps = loadStats(s, "cps");
        r.strictOverHits = s.getLong("strict-over-hits");
        r.aimOver45 = s.getLong("aim-over-45");
        r.dupeAlerts = s.getInt("dupe-alerts");
        r.strongSessions = s.getInt("strong-sessions");
        r.lastStrongSession = s.getInt("last-strong-session");
        r.rotation.restore(s.getDoubleList("rotation").stream().mapToDouble(Double::doubleValue).toArray());
        for (CheckType type : CheckType.values()) {
            r.flags[type.ordinal()] = s.getInt("flags." + type.name());
            r.nearMisses[type.ordinal()] = s.getInt("near-misses." + type.name());
        }
        return r;
    }

    private static void saveStats(ConfigurationSection s, String key, RunningStats stats) {
        s.set(key + ".count", stats.count());
        s.set(key + ".mean", stats.mean());
        s.set(key + ".m2", stats.m2());
        s.set(key + ".max", stats.max());
    }

    private static RunningStats loadStats(ConfigurationSection s, String key) {
        return RunningStats.restore(s.getLong(key + ".count"), s.getDouble(key + ".mean"),
            s.getDouble(key + ".m2"), s.getDouble(key + ".max"));
    }
}
