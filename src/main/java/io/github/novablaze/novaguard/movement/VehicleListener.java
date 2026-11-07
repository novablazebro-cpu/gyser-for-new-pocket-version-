package io.github.novablaze.novaguard.movement;

import io.github.novablaze.novaguard.CheckType;
import io.github.novablaze.novaguard.NovaGuardPlugin;
import io.github.novablaze.novaguard.core.PlayerData;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleMoveEvent;

/**
 * BoatFly / HorseFly / EntityFly: a player-steered vehicle that stays in the air without falling. Boats,
 * minecarts, horses, camels, pigs and striders all fall when nothing is under them; a horse jump is over in
 * well under a second. The server moves the vehicle itself when the rider steers it, so its "on ground" is
 * the server's own view, not the client's claim.
 */
public final class VehicleListener implements Listener {

    /** Longer than any horse jump (about 0.7 s up and at the top) with lag on top. */
    private static final long MAX_AIR_MS = 1500;

    private final NovaGuardPlugin plugin;

    public VehicleListener(NovaGuardPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVehicleMove(VehicleMoveEvent event) {
        plugin.safety().run("vehicle", () -> check(event));
    }

    private void check(VehicleMoveEvent event) {
        Vehicle vehicle = event.getVehicle();
        Player driver = driver(vehicle);
        if (driver == null || !plugin.settings().enabled(CheckType.FLY)) {
            return;
        }
        PlayerData data = plugin.players().getIfPresent(driver.getUniqueId());
        long now = System.currentTimeMillis();
        if (data == null || plugin.exemptions().skipAll(driver, data, now) || now < data.graceUntilMs
            || driver.getAllowFlight() || !canFall(vehicle) || held(vehicle)) {
            if (data != null) {
                data.vehicleAirSinceMs = 0;
            }
            return;
        }
        double dy = event.getTo().getY() - event.getFrom().getY();
        if (dy < -0.03) {
            data.vehicleAirSinceMs = 0; // falling, as it should
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

    /** Vehicles that have gravity. Happy ghasts fly for real. */
    private static boolean canFall(Vehicle vehicle) {
        return vehicle.hasGravity() && !(vehicle instanceof HappyGhast);
    }

    /** On the ground, in water or lava (swimming horses, striders, boats), or on rails (minecart slopes). */
    private static boolean held(Vehicle vehicle) {
        if (vehicle.isOnGround() || vehicle.isInWater() || vehicle.isInLava()) {
            return true;
        }
        if (vehicle instanceof Minecart) {
            Location at = vehicle.getLocation();
            return Tag.RAILS.isTagged(at.getBlock().getType())
                || Tag.RAILS.isTagged(at.clone().subtract(0, 1, 0).getBlock().getType());
        }
        return false;
    }

    private static Player driver(Vehicle vehicle) {
        for (Entity passenger : vehicle.getPassengers()) {
            if (passenger instanceof Player player) {
                return player;
            }
        }
        return null;
    }
}
