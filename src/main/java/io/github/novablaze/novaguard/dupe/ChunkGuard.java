package io.github.novablaze.novaguard.dupe;

import io.github.novablaze.novaguard.NovaGuardPlugin;
import io.github.novablaze.novaguard.Settings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Egg;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Item;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;

/**
 * Against chunk overloading: thousands of dropped items, XP orbs, arrows or falling blocks packed into one
 * chunk. That lags the server (a "lag machine") and can make the chunk too big to save or load (a "chunk
 * ban"); a failed save can roll the world back, which is also a dupe. Paper has no per-chunk limit for
 * these by default, so NovaGuard trims "loose" entities above {@code chunk-guard.max-loose-entities}.
 *
 * <p>Only loose entities are touched. Mobs, villagers, item frames, armour stands, minecarts, boats and
 * ender pearls (pearl stasis chambers) and thrown tridents are never removed, so builds and farms are safe.
 */
public final class ChunkGuard implements Listener, Runnable {

    private final NovaGuardPlugin plugin;

    public ChunkGuard(NovaGuardPlugin plugin) {
        this.plugin = plugin;
    }

    /** A chunk's entities were loaded: trim before an overloaded chunk can lag anyone. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoad(EntitiesLoadEvent event) {
        Settings settings = plugin.settings();
        if (!settings.chunkGuardEnabled) {
            return;
        }
        plugin.safety().run("chunk-guard", () -> {
            List<Entity> loose = new ArrayList<>();
            for (Entity entity : event.getEntities()) {
                if (isLoose(entity)) {
                    loose.add(entity);
                }
            }
            trim(event.getChunk(), loose, settings.chunkMaxLoose, "loaded");
        });
    }

    /** Regular check of loaded worlds, for chunks being filled right now. */
    @Override
    public void run() {
        Settings settings = plugin.settings();
        if (!settings.chunkGuardEnabled) {
            return;
        }
        for (World world : Bukkit.getWorlds()) {
            Map<Long, List<Entity>> byChunk = new HashMap<>();
            for (Entity entity : world.getEntities()) {
                if (isLoose(entity)) {
                    int cx = entity.getLocation().getBlockX() >> 4;
                    int cz = entity.getLocation().getBlockZ() >> 4;
                    byChunk.computeIfAbsent(Chunk.getChunkKey(cx, cz), k -> new ArrayList<>()).add(entity);
                }
            }
            for (Map.Entry<Long, List<Entity>> entry : byChunk.entrySet()) {
                if (entry.getValue().size() > settings.chunkMaxLoose) {
                    Chunk chunk = entry.getValue().get(0).getLocation().getChunk();
                    trim(chunk, entry.getValue(), settings.chunkMaxLoose, "filled");
                }
            }
        }
    }

    /**
     * Removes the loose entities above the limit.
     *
     * @return how many were removed
     */
    public int trim(Chunk chunk, List<Entity> loose, int limit, String how) {
        if (loose.size() <= limit) {
            return 0;
        }
        int removed = 0;
        for (Entity entity : loose.subList(limit, loose.size())) {
            entity.remove();
            removed++;
        }
        String where = chunk.getWorld().getName() + " chunk " + chunk.getX() + "," + chunk.getZ()
            + " (blocks " + (chunk.getX() << 4) + "," + (chunk.getZ() << 4) + ")";
        plugin.getLogger().warning("Chunk overload: " + where + " was " + how + " with " + loose.size()
            + " loose entities (items, XP, arrows...). Removed " + removed + ", kept " + limit + ".");
        return removed;
    }

    /** Things that pile up without anyone building them. */
    public static boolean isLoose(Entity entity) {
        if (entity instanceof Trident) {
            return false; // a thrown trident is a player's weapon, not clutter
        }
        return entity instanceof Item || entity instanceof ExperienceOrb || entity instanceof AbstractArrow
            || entity instanceof Snowball || entity instanceof Egg || entity instanceof FallingBlock
            || entity instanceof Fireball;
    }
}
