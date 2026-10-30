package io.github.novablaze.novaguard.combat;

import io.github.novablaze.novaguard.CheckType;
import io.github.novablaze.novaguard.NovaGuardPlugin;
import io.github.novablaze.novaguard.core.PlayerData;
import io.github.novablaze.novaguard.math.Box;
import io.github.novablaze.novaguard.math.Geometry;
import io.github.novablaze.novaguard.movement.Exemptions;
import java.util.Locale;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * Placing or breaking blocks too far away (reach scaffold, nuker). Blocks do not move, and the player's own
 * position is up to date when the packet is handled, so no lag compensation is needed beyond the tolerance.
 */
public final class BlockReachListener implements Listener {

    private final NovaGuardPlugin plugin;

    public BlockReachListener(NovaGuardPlugin plugin) {
        this.plugin = plugin;
    }

    /** Remembers the block each player last started digging by hand. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDig(BlockDamageEvent event) {
        PlayerData data = plugin.players().getIfPresent(event.getPlayer().getUniqueId());
        if (data != null) {
            data.lastDugBlock = event.getBlock().getLocation();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() == Material.SCAFFOLDING) {
            return; // Scaffolding is placed at the top or end of a column, far from the clicked block.
        }
        plugin.safety().run("block-reach",
            () -> check(event.getPlayer(), event.getBlockAgainst(), event.getBlockPlaced(), "placed", event));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        PlayerData data = plugin.players().getIfPresent(event.getPlayer().getUniqueId());
        // Only blocks the player really started digging: tree-feller / vein-miner plugins also fire break
        // events for far-away blocks, and those are not the player's reach.
        if (data == null || data.lastDugBlock == null || !data.lastDugBlock.equals(event.getBlock().getLocation())) {
            return;
        }
        plugin.safety().run("block-reach", () -> check(event.getPlayer(), event.getBlock(), event.getBlock(), "broke", event));
    }

    private void check(Player player, Block clicked, Block result, String action, Cancellable event) {
        if (!plugin.settings().enabled(CheckType.BLOCK_REACH)) {
            return;
        }
        PlayerData data = plugin.players().get(player);
        if (plugin.exemptions().skipAll(player, data, System.currentTimeMillis())) {
            return;
        }
        Location eye = player.getEyeLocation();
        double distance = Math.min(distanceTo(eye, clicked), distanceTo(eye, result));
        double limit = Exemptions.attribute(player, Attribute.BLOCK_INTERACTION_RANGE, 4.5)
            + plugin.settings().blockReachTolerance;
        if (distance <= limit) {
            return;
        }
        boolean enforce = plugin.violations().flag(player, data, CheckType.BLOCK_REACH, 1.0,
            String.format(Locale.ROOT, "%s a block %.2f away, limit %.2f", action, distance, limit));
        if (enforce) {
            event.setCancelled(true);
        }
    }

    private static double distanceTo(Location eye, Block block) {
        Box box = new Box(block.getX(), block.getY(), block.getZ(), block.getX() + 1, block.getY() + 1, block.getZ() + 1);
        return Geometry.distanceToBox(eye.getX(), eye.getY(), eye.getZ(), box);
    }
}
