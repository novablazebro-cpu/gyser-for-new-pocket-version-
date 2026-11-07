package io.github.novablaze.novaguard.combat;

import io.github.novablaze.novaguard.CheckType;
import io.github.novablaze.novaguard.NovaGuardPlugin;
import io.github.novablaze.novaguard.Settings;
import io.github.novablaze.novaguard.core.PlayerData;
import io.github.novablaze.novaguard.math.Box;
import io.github.novablaze.novaguard.math.PlaceGeometry;
import java.util.Locale;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.util.BoundingBox;

/**
 * Building cheats found in most cheat clients (Meteor, Wurst, LiquidBounce...):
 * <ul>
 *   <li><b>FastBreak / Nuker</b>: more blocks broken per second than one per tick, for several seconds.</li>
 *   <li><b>FastPlace / Tower</b>: more blocks placed per second than the right-click delay allows.</li>
 *   <li><b>Scaffold</b>: a block placed against a face that points away from the player, which a real
 *       player cannot click.</li>
 * </ul>
 * The place/break itself is cancelled when the check enforces, so the cheat gains nothing.
 */
public final class BuildListener implements Listener {

    /** Rounding, sneaking and the small eye movement inside one tick. */
    private static final double FACE_MARGIN = 0.1;

    private final NovaGuardPlugin plugin;

    public BuildListener(NovaGuardPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        plugin.safety().run("fast-break", () -> checkBreak(event));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        plugin.safety().run("fast-place", () -> checkPlace(event));
    }

    private void checkBreak(BlockBreakEvent event) {
        Settings settings = plugin.settings();
        Player player = event.getPlayer();
        PlayerData data = plugin.players().getIfPresent(player.getUniqueId());
        if (!settings.enabled(CheckType.FAST_BREAK) || data == null
            || plugin.exemptions().skipAll(player, data, System.currentTimeMillis())) {
            return;
        }
        // Only blocks the player really dug: tree-feller / vein-miner plugins break many blocks at once.
        if (data.lastDugBlock == null || !data.lastDugBlock.equals(event.getBlock().getLocation())) {
            return;
        }
        int strikes = data.breaks.record(System.currentTimeMillis(), settings.fastBreakMax);
        if (strikes >= settings.fastBreakSeconds
            && plugin.violations().flag(player, data, CheckType.FAST_BREAK, 1.0,
                "more than " + settings.fastBreakMax + " blocks/second for " + strikes + "s")) {
            event.setCancelled(true);
        }
    }

    private void checkPlace(BlockPlaceEvent event) {
        Settings settings = plugin.settings();
        Player player = event.getPlayer();
        PlayerData data = plugin.players().getIfPresent(player.getUniqueId());
        long now = System.currentTimeMillis();
        if (data == null || plugin.exemptions().skipAll(player, data, now)) {
            return;
        }
        if (settings.enabled(CheckType.FAST_PLACE)) {
            int strikes = data.places.record(now, settings.fastPlaceMax);
            if (strikes >= settings.fastPlaceSeconds
                && plugin.violations().flag(player, data, CheckType.FAST_PLACE, 1.0,
                    "more than " + settings.fastPlaceMax + " blocks/second for " + strikes + "s")) {
                event.setCancelled(true);
                return;
            }
        }
        if (settings.enabled(CheckType.SCAFFOLD) && impossibleFace(player, event)) {
            if (++data.scaffoldBuffer >= settings.scaffoldBuffer
                && plugin.violations().flag(player, data, CheckType.SCAFFOLD, 1.0,
                    "placed against the " + face(event) + " face, which faces away from the player")) {
                event.setCancelled(true);
            }
        } else if (data.scaffoldBuffer > 0) {
            data.scaffoldBuffer--;
        }
    }

    private static boolean impossibleFace(Player player, BlockPlaceEvent event) {
        Block against = event.getBlockAgainst();
        Block placed = event.getBlockPlaced();
        int dx = placed.getX() - against.getX();
        int dy = placed.getY() - against.getY();
        int dz = placed.getZ() - against.getZ();
        // Only a real neighbour: replacing grass or snow in place, or scaffolding columns, is not a face click.
        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) != 1 || placed.getType() == Material.SCAFFOLDING) {
            return false;
        }
        Location eye = player.getEyeLocation();
        return !PlaceGeometry.faceVisible(eye.getX(), eye.getY(), eye.getZ(), shape(against), dx, dy, dz, FACE_MARGIN);
    }

    /** The clicked block's real outline (a slab's top is at y + 0.5), or the full cube when it has none. */
    private static Box shape(Block block) {
        BoundingBox box = block.getBoundingBox();
        if (box.getVolume() <= 0) {
            return new Box(block.getX(), block.getY(), block.getZ(), block.getX() + 1, block.getY() + 1, block.getZ() + 1);
        }
        return new Box(box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ());
    }

    private static String face(BlockPlaceEvent event) {
        return event.getBlockAgainst().getFace(event.getBlockPlaced()) == null ? "?"
            : event.getBlockAgainst().getFace(event.getBlockPlaced()).name().toLowerCase(Locale.ROOT);
    }
}
