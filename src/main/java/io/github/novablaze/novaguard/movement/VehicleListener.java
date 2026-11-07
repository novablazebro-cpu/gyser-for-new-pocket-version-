package io.github.novablaze.novaguard.movement;

import io.github.novablaze.novaguard.CheckType;
import io.github.novablaze.novaguard.NovaGuardPlugin;
import io.github.novablaze.novaguard.core.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

/**
 * BoatFly / HorseFly / EntityFly: a player-steered vehicle that stays in the air without falling. Boats,
 * minecarts, horses, camels, pigs and striders all fall when nothing holds them up; a horse jump is over in
 * well under a second.
 *
 * <p>Runs every tick over riding players instead of listening to VehicleMoveEvent: that event never fires
 * for horses, pigs or camels, and a client-steered boat's "on ground" flag is the client's own claim. The
 * ground and water under the vehicle are looked up here, from the server's blocks.
 */
public final class VehicleListener implements Runnable {

    /** Longer than any horse jump (about 0.7 s up and at the top) with lag on top. */
    private static final long MAX_AIR_MS = 1500;

    private final NovaGuardPlugin plugin;

    public VehicleListener(NovaGuardPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        if (!plugin.settings().enabled(CheckType.FLY)) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isInsideVehicle()) {
                check(player, now);
            }
        }
    }

    private void check(Player driver, long now) {
        PlayerData data = plugin.players().getIfPresent(driver.getUniqueId());
        if (data == null) {
            return;
        }
        Entity vehicle = driver.getVehicle();
        while (vehicle != null && vehicle.getVehicle() != null) {
            vehicle = vehicle.getVehicle();
        }
        double y = vehicle == null ? Double.NaN : vehicle.getLocation().getY();
        double dy = Double.isNaN(data.vehicleLastY) ? -1 : y - data.vehicleLastY;
        data.vehicleLastY = y;
        if (vehicle == null || plugin.exemptions().skipAll(driver, data, now) || now < data.graceUntilMs
            || driver.getAllowFlight() || !vehicle.hasGravity() || vehicle instanceof HappyGhast
            || dy < -0.03 || held(vehicle)) {
            data.vehicleAirSinceMs = 0; // falling, held up, or not ours to judge
            return;
        }
        if (data.vehicleAirSinceMs == 0) {
            data.vehicleAirSinceMs = now;
            return;
        }
        if (now - data.vehicleAirSinceMs < MAX_AIR_MS) {
            return;
        }
        data.vehicleAirSinceMs = 0;
        boolean enforce = plugin.violations().flag(driver, data, CheckType.FLY, 1.0,
            "steered a " + vehicle.getType().name().toLowerCase(java.util.Locale.ROOT)
                + " through the air without falling (boat/entity fly)");
        if (enforce) {
            vehicle.eject(); // the rider falls; a vehicle fly cheat cannot hold them up any more
        }
    }

    /** Ground right under the vehicle, water or lava around it, or rails under a minecart. */
    private static boolean held(Entity vehicle) {
        World world = vehicle.getWorld();
        BoundingBox box = vehicle.getBoundingBox();
        BoundingBox under = new BoundingBox(box.getMinX() + 0.001, box.getMinY() - 0.12, box.getMinZ() + 0.001,
            box.getMaxX() - 0.001, box.getMinY() + 0.02, box.getMaxZ() - 0.001);
        if (world.hasCollisionsIn(under)) {
            return true;
        }
        int x = vehicle.getLocation().getBlockX();
        int z = vehicle.getLocation().getBlockZ();
        for (int by = (int) Math.floor(box.getMinY() - 0.2); by <= (int) Math.floor(box.getMaxY()); by++) {
            Block block = world.getBlockAt(x, by, z);
            double top = Environment.fluidTop(block);
            if (!Double.isNaN(top) && box.getMinY() - 0.2 < top) {
                return true;
            }
            if (vehicle instanceof Minecart && Tag.RAILS.isTagged(block.getType())) {
                return true;
            }
        }
        return false;
    }
}
