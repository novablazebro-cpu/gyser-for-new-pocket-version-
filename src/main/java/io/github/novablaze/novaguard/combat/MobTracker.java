package io.github.novablaze.novaguard.combat;

import io.github.novablaze.novaguard.math.Box;
import io.github.novablaze.novaguard.math.PositionHistory;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.entity.Entity;
import org.bukkit.util.BoundingBox;

/**
 * Remembers where mobs were during the last ~2 seconds, but only mobs a player recently attacked, so reach on
 * mobs is lag-compensated like reach on players. Mob AI often moves a mob without giving it a velocity, so a
 * guess from the velocity alone is not good enough.
 */
public final class MobTracker implements Runnable {

    private static final long KEEP_MS = 10_000;
    private static final int MAX_TRACKED = 256;

    private static final class Tracked {
        final Entity entity;
        final PositionHistory history = new PositionHistory(40);
        long lastHitMs;

        Tracked(Entity entity) {
            this.entity = entity;
        }
    }

    /** Insertion order = oldest first, so the oldest is dropped when the limit is reached. */
    private final Map<Integer, Tracked> tracked = new LinkedHashMap<>();

    /** A player attacked this mob: record it from now on (and right now). */
    public void noteHit(Entity mob, long nowMs) {
        Tracked entry = tracked.get(mob.getEntityId());
        if (entry == null) {
            if (tracked.size() >= MAX_TRACKED) {
                Iterator<Integer> oldest = tracked.keySet().iterator();
                oldest.next();
                oldest.remove();
            }
            entry = new Tracked(mob);
            entry.history.add(nowMs, toBox(mob.getBoundingBox()));
            tracked.put(mob.getEntityId(), entry);
        }
        entry.lastHitMs = nowMs;
    }

    /** Where the mob was recently, or null when it is not tracked yet (first hit). */
    public PositionHistory history(Entity mob) {
        Tracked entry = tracked.get(mob.getEntityId());
        return entry == null || entry.history.size() < 2 ? null : entry.history;
    }

    /** Every tick: one box per tracked mob. */
    @Override
    public void run() {
        long now = System.currentTimeMillis();
        Iterator<Tracked> it = tracked.values().iterator();
        while (it.hasNext()) {
            Tracked entry = it.next();
            if (!entry.entity.isValid() || now - entry.lastHitMs > KEEP_MS) {
                it.remove();
            } else {
                entry.history.add(now, toBox(entry.entity.getBoundingBox()));
            }
        }
    }

    private static Box toBox(BoundingBox box) {
        return new Box(box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ());
    }
}
