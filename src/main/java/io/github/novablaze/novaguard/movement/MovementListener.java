package io.github.novablaze.novaguard.movement;

import io.github.novablaze.novaguard.CheckType;
import io.github.novablaze.novaguard.NovaGuardPlugin;
import io.github.novablaze.novaguard.Settings;
import io.github.novablaze.novaguard.core.PlayerData;
import io.github.novablaze.novaguard.math.Box;
import io.github.novablaze.novaguard.math.JumpPhysics;
import io.github.novablaze.novaguard.math.MovementMath;
import io.papermc.paper.datacomponent.DataComponentTypes;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Speed, Fly and NoFall. Runs on every PlayerMoveEvent, so everything here is a handful of arithmetic steps. */
public final class MovementListener implements Listener, Runnable {

    /** Kinetic weapons (spears) push the player forward while charging: 0.38 blocks/tick in 26.3 data. */
    private static final double KINETIC_EXTRA_RATE = 0.38 * 20.0;

    private final NovaGuardPlugin plugin;

    public MovementListener(NovaGuardPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location from = event.getFrom();
        Location to = event.getTo();
        PlayerData data = plugin.players().get(player);
        Settings settings = plugin.settings();
        long now = System.currentTimeMillis();

        trackLag(data, settings, now);
        data.history.add(now, Box.ofEntity(to.getX(), to.getY(), to.getZ(), player.getWidth(), player.getHeight()));

        if (from.getWorld() != to.getWorld()) {
            data.resetMovement(to, now);
            return;
        }
        if (plugin.exemptions().skipMovement(player, data, now)) {
            data.resetMovement(to, now);
            data.speedBudget.fill(now);
            return;
        }
        // Probe only for checked players: exempt ones (Bedrock, creative, flying...) cost nothing extra.
        Environment.Probe env = Environment.probe(player, to);
        if (env.onIce()) {
            data.lastIceMs = now;
        }

        boolean failed = false;
        if (settings.enabled(CheckType.SPEED)) {
            failed = checkSpeed(player, data, settings, from, to, now);
        }
        failed |= checkFly(player, data, settings, to, env, now);
        checkNoFall(player, data, settings, to.getY() - from.getY(), env);

        if (failed) {
            setback(event, player, data, now);
        } else if (env.grounded() || env.supported()) {
            data.lastGood = to.clone();
        }
    }

    /**
     * Packets that were stuck arrive together: several moves only milliseconds apart = lag spike.
     * A lag spike never skips checks (a timer hack looks similar); it only blocks kicks for a moment.
     */
    private static void trackLag(PlayerData data, Settings settings, long now) {
        if (data.lastMoveMs > 0 && now - data.lastMoveMs < settings.burstIntervalMs) {
            if (++data.burstMoves >= settings.burstMoves) {
                data.lagSpikeUntilMs = now + settings.spikeGraceMs;
            }
        } else {
            data.burstMoves = 0;
        }
        data.lastMoveMs = now;
    }

    private boolean checkSpeed(Player player, PlayerData data, Settings settings, Location from, Location to,
                               long now) {
        if (player.hasPotionEffect(PotionEffectType.DOLPHINS_GRACE)) {
            data.speedBudget.fill(now);
            return false;
        }
        double distance = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());
        double attribute = Exemptions.attribute(player, Attribute.MOVEMENT_SPEED, 0.1);
        boolean ice = now - data.lastIceMs < 2000;
        double rate = MovementMath.allowedRate(attribute, player.isSprinting(), player.getWalkSpeed(), ice,
            settings.speedMultiplier, settings.speedIceMultiplier);
        if (usingKineticWeapon(player)) {
            rate += KINETIC_EXTRA_RATE;
        }
        // The bucket holds ~1 s + 1.5x ping of movement, so stuck packets that arrive together are absorbed.
        double capacity = MovementMath.capacity(rate, player.getPing());
        double over = data.speedBudget.spend(now, distance, rate, capacity);
        if (over > settings.speedMinOvershoot && !Environment.platformNearby(player)) {
            plugin.violations().flag(player, data, CheckType.SPEED, 1.0,
                String.format(Locale.ROOT, "%.2f blocks over, limit %.1f b/s", over, rate));
            return true;
        }
        return false;
    }

    private boolean checkFly(Player player, PlayerData data, Settings settings, Location to,
                             Environment.Probe env, long now) {
        if (env.grounded() || env.supported()) {
            if (data.airborne && env.bouncy() && data.fallDistance > 0.5) {
                // A slime block or bed can throw the player back up about as high as they fell.
                data.bounceAllowance = data.fallDistance;
                data.bounceUntilMs = now + 3000;
            }
            data.airborne = false;
            data.groundY = to.getY();
            data.hoverCheckpointMs = now;
            data.hoverCheckpointY = to.getY();
            return false;
        }
        if (!data.airborne) {
            data.airborne = true;
            data.hoverCheckpointMs = now;
            data.hoverCheckpointY = to.getY();
        }
        if (!settings.enabled(CheckType.FLY)) {
            return false;
        }
        boolean levitating = player.hasPotionEffect(PotionEffectType.LEVITATION);
        boolean slowFalling = player.hasPotionEffect(PotionEffectType.SLOW_FALLING);

        double rise = to.getY() - data.groundY;
        double allowed = maxJumpHeight(player) + settings.flyJumpMargin
            + (now < data.bounceUntilMs ? data.bounceAllowance + 0.5 : 0.0);
        if (!levitating && rise > allowed && !Environment.platformNearby(player)) {
            plugin.violations().flag(player, data, CheckType.FLY, 1.0,
                String.format(Locale.ROOT, "rose %.2f blocks, max %.2f", rise, allowed));
            return true;
        }

        // A Jump Boost V jump stays up ~23 ticks and a slime bounce even longer: wait at least that long.
        long hoverWindow = Math.max(settings.flyHoverMs, jumpAirTimeMs(player) + 250);
        if (now < data.bounceUntilMs) {
            data.hoverCheckpointMs = now;
            data.hoverCheckpointY = to.getY();
        } else if (now - data.hoverCheckpointMs >= hoverWindow) {
            boolean hovering = to.getY() > data.hoverCheckpointY - settings.flyHoverMinDrop;
            data.hoverCheckpointMs = now;
            data.hoverCheckpointY = to.getY();
            if (hovering && !levitating && !slowFalling && !Environment.platformNearby(player)) {
                plugin.violations().flag(player, data, CheckType.FLY, 1.0, "hovering in the air");
                return true;
            }
        }
        return false;
    }

    /**
     * Tracks the fall on the server's side; if the client lied about touching ground, re-apply fall damage.
     * isOnGround() is deprecated because it is the client's claim - which is exactly what we compare against.
     */
    @SuppressWarnings("deprecation")
    private void checkNoFall(Player player, PlayerData data, Settings settings, double dy, Environment.Probe env) {
        if (env.supported() || player.hasPotionEffect(PotionEffectType.SLOW_FALLING)
            || player.hasPotionEffect(PotionEffectType.LEVITATION)) {
            // Vanilla gives no fall damage here either.
            data.fallDistance = 0;
            data.groundSpoofs = 0;
            return;
        }
        if (!env.grounded()) {
            if (dy < 0) {
                data.fallDistance -= dy;
                if (player.isOnGround()) {
                    if (Environment.platformNearby(player)) {
                        // Standing on a boat, minecart or happy ghast that moves down: a real ground.
                        data.fallDistance = 0;
                        data.groundSpoofs = 0;
                    } else {
                        data.groundSpoofs++;
                    }
                }
            }
            return;
        }
        if (settings.enabled(CheckType.NOFALL) && data.groundSpoofs >= settings.noFallMinSpoofs && !env.softLanding()) {
            double safe = Exemptions.attribute(player, Attribute.SAFE_FALL_DISTANCE, 3.0);
            double multiplier = Exemptions.attribute(player, Attribute.FALL_DAMAGE_MULTIPLIER, 1.0);
            double damage = Math.ceil((data.fallDistance - safe) * multiplier);
            if (damage >= 1.0) {
                plugin.violations().flag(player, data, CheckType.NOFALL, 1.0,
                    String.format(Locale.ROOT, "fell %.1f blocks while claiming ground", data.fallDistance));
                if (data.shadow) {
                    data.fallDistance = 0;
                    data.groundSpoofs = 0;
                    return;
                }
                // Damage on the next tick, outside the move event. Armor and Feather Falling still apply.
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline() && !player.isDead()) {
                        player.damage(damage, DamageSource.builder(DamageType.FALL).build());
                    }
                });
            }
        }
        data.fallDistance = 0;
        data.groundSpoofs = 0;
    }

    private void setback(PlayerMoveEvent event, Player player, PlayerData data, long now) {
        if (data.shadow) {
            // Shadow mode: no rubberband, but tracking restarts exactly as after a real setback.
            data.resetMovement(event.getTo(), now);
            data.speedBudget.empty(now);
            return;
        }
        Location from = event.getFrom();
        Location target = data.lastGood != null && data.lastGood.getWorld() == from.getWorld()
            && data.lastGood.distanceSquared(from) < 64 * 64 ? data.lastGood.clone() : from.clone();
        target.setYaw(event.getTo().getYaw());
        target.setPitch(event.getTo().getPitch());
        data.selfTeleportUntilMs = now + 1000;
        event.setTo(target);
        data.resetMovement(target, now);
        // Empty, not full: otherwise every setback would hand a speed hacker a fresh second of free movement.
        data.speedBudget.empty(now);
    }

    /**
     * Stationary hovering sends no move events, so it is looked for once per second.
     * A frozen connection looks exactly the same from the server, so this only alerts staff; it never
     * teleports anyone, and gives up after 10 seconds without packets (the player is probably timing out).
     */
    @Override
    public void run() {
        Settings settings = plugin.settings();
        if (!settings.enabled(CheckType.FLY)) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerData data = plugin.players().getIfPresent(player.getUniqueId());
            if (data == null || !data.airborne) {
                continue;
            }
            long silentMs = now - data.lastMoveMs;
            if (silentMs < settings.flyHoverMs * 2 || silentMs > 10_000) {
                continue;
            }
            Location location = player.getLocation();
            if (plugin.exemptions().skipMovement(player, data, now)) {
                data.resetMovement(location, now);
                continue;
            }
            Environment.Probe env = Environment.probe(player, location);
            if (env.grounded() || env.supported() || player.hasPotionEffect(PotionEffectType.LEVITATION)
                || player.hasPotionEffect(PotionEffectType.SLOW_FALLING) || Environment.platformNearby(player)) {
                data.airborne = false;
                continue;
            }
            plugin.violations().flag(player, data, CheckType.FLY, 0.5,
                "hovering while standing still (no setback: could also be a frozen connection)");
            // Re-arm without touching lastGood: the hover spot must never become a "legit" setback position.
            data.airborne = false;
        }
    }

    private static double maxJumpHeight(Player player) {
        return JumpPhysics.maxJumpHeight(jumpVelocity(player), Exemptions.attribute(player, Attribute.GRAVITY, 0.08));
    }

    private static long jumpAirTimeMs(Player player) {
        return 50L * JumpPhysics.airTimeTicks(jumpVelocity(player), Exemptions.attribute(player, Attribute.GRAVITY, 0.08));
    }

    private static double jumpVelocity(Player player) {
        double strength = Exemptions.attribute(player, Attribute.JUMP_STRENGTH, 0.42);
        PotionEffect boost = player.getPotionEffect(PotionEffectType.JUMP_BOOST);
        return JumpPhysics.jumpVelocity(strength, boost == null ? -1 : boost.getAmplifier());
    }

    private static boolean usingKineticWeapon(Player player) {
        if (!player.isHandRaised()) {
            return false;
        }
        ItemStack active = player.getActiveItem();
        return !active.isEmpty() && active.hasData(DataComponentTypes.KINETIC_WEAPON);
    }
}
