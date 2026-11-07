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
import org.bukkit.event.entity.EntityDamageEvent;
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
        plugin.safety().run("movement", () -> handleMove(event));
    }

    @SuppressWarnings("deprecation") // isOnGround(): the client's claim, recorded for context on purpose
    private void handleMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location from = event.getFrom();
        Location to = event.getTo();
        PlayerData data = plugin.players().get(player);
        Settings settings = plugin.settings();
        long now = System.currentTimeMillis();

        trackLag(data, settings, now);
        data.history.add(now, Box.ofEntity(to.getX(), to.getY(), to.getZ(), player.getWidth(), player.getHeight()));
        data.trace.add(now, to.getX(), to.getY(), to.getZ(), player.isOnGround());
        trackKnockback(player, data, to.getX() - from.getX(), to.getZ() - from.getZ(), now);
        if (data.monitored && now >= data.graceUntilMs && plugin.violations().countsAsEvidence(player, data, now)) {
            plugin.monitor().recordRotation(player.getUniqueId(), from.getPitch(), to.getPitch());
        }

        if (from.getWorld() != to.getWorld()) {
            data.resetMovement(to, now);
            return;
        }
        if (player.isGliding()) {
            checkElytra(player, data, settings, from, to, now);
        }
        if (plugin.exemptions().skipMovement(player, data, now)) {
            data.resetMovement(to, now);
            data.speedBudget.fill(now);
            data.momentum.stop();
            data.afterSkip = true;
            return;
        }
        // Probe only for checked players: exempt ones (Bedrock, creative, flying...) cost nothing extra.
        Environment.Probe env = Environment.probe(player, to);
        if (env.onIce()) {
            data.lastIceMs = now;
        }
        double dy = to.getY() - from.getY();
        double horizontal = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());
        if (env.grounded() || env.supported() || player.hasPotionEffect(PotionEffectType.LEVITATION)) {
            // Levitation lifts at a steady speed by design: it is not leftover launch momentum.
            data.momentum.stop();
        } else if (data.afterSkip) {
            // Grace ended while still in the air (TNT cannon, slime launcher): track the leftover momentum.
            data.momentum.start(dy, horizontal);
        }
        data.afterSkip = false;

        boolean speedFailed = settings.enabled(CheckType.SPEED) && checkSpeed(player, data, settings, horizontal, now);
        boolean flyFailed = checkFly(player, data, settings, to, dy, env, now);
        checkNoFall(player, data, settings, dy, env);

        boolean enforce = speedFailed && plugin.violations().enforces(data, CheckType.SPEED)
            || flyFailed && plugin.violations().enforces(data, CheckType.FLY);
        if (enforce) {
            setback(event, data, now);
        } else if (speedFailed || flyFailed) {
            // Silent or shadow mode: no rubberband. Only the check that failed restarts its tracking, so one
            // cheat is counted once instead of on every move, while the other checks keep their state (a
            // silent Speed check must not wipe the Fly and NoFall tracking).
            if (speedFailed) {
                data.speedBudget.empty(now);
            }
            if (flyFailed) {
                data.groundY = to.getY();
                data.hoverCheckpointMs = now;
                data.hoverCheckpointY = to.getY();
                data.momentum.stop();
            }
        } else if (env.grounded() || env.supported()) {
            data.lastGood = to.clone();
        }
    }

    /**
     * ElytraFly: an elytra only glides. Without a firework boost it cannot hover (it stalls and drops), and
     * it cannot fly level for long without losing speed (holding height costs speed, and air drag takes
     * 1% per tick). Both are physics, so a skilled pilot never trips them.
     */
    private void checkElytra(Player player, PlayerData data, Settings settings, Location from, Location to, long now) {
        if (!settings.enabled(CheckType.FLY) || plugin.exemptions().skipAll(player, data, now)
            || player.getAllowFlight() || player.isRiptiding() || now < data.graceUntilMs
            || now < data.elytraBoostUntilMs || plugin.lag().tps() < settings.minTpsForMovement
            || Environment.probe(player, to).supported() || Environment.platformNearby(player)) {
            data.elytraStallMoves = 0;
            data.elytraLevelMoves = 0;
            return;
        }
        double dy = to.getY() - from.getY();
        double speed = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());
        boolean ceiling = player.getWorld().hasCollisionsIn(player.getBoundingBox().shift(0, 0.1, 0));
        // Hovering: almost no forward speed and not sinking.
        data.elytraStallMoves = speed < 0.15 && dy >= -0.02 && !ceiling ? data.elytraStallMoves + 1 : 0;
        // Level flight that does not lose speed.
        if (Math.abs(dy) <= 0.02 && speed >= 0.15 && !ceiling) {
            if (data.elytraLevelMoves++ == 0) {
                data.elytraLevelStartSpeed = speed;
            }
        } else {
            data.elytraLevelMoves = 0;
        }
        String detail = null;
        if (data.elytraStallMoves >= 20) {
            detail = "hovered with an elytra without a firework";
        } else if (data.elytraLevelMoves >= 40 && speed >= data.elytraLevelStartSpeed * 0.9) {
            detail = String.format(Locale.ROOT, "flew level with an elytra for 2s at %.2f blocks/tick without slowing down",
                speed);
        }
        if (detail == null) {
            return;
        }
        data.elytraStallMoves = 0;
        data.elytraLevelMoves = 0;
        if (plugin.violations().flag(player, data, CheckType.FLY, 1.0, detail)) {
            player.setGliding(false); // the elytra closes and the player falls
        }
    }

    /** A firework rocket used while gliding: the boost lasts up to about 2.5 seconds (flight duration 3). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBoost(com.destroystokyo.paper.event.player.PlayerElytraBoostEvent event) {
        PlayerData data = plugin.players().getIfPresent(event.getPlayer().getUniqueId());
        if (data != null) {
            data.elytraBoostUntilMs = System.currentTimeMillis() + 3500 + event.getPlayer().getPing();
        }
    }

    /** Anti-knockback: collects movement after a hit and judges it once the lag window is over. */
    private void trackKnockback(Player player, PlayerData data, double dx, double dz, long now) {
        if (!data.velocity.active()) {
            return;
        }
        data.velocity.addMove(dx, dz);
        if (data.velocity.isDue(now) && data.velocity.finish()) {
            plugin.violations().flag(player, data, CheckType.VELOCITY, 1.0,
                "took no knockback from 3 hits in a row");
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

    private boolean checkSpeed(Player player, PlayerData data, Settings settings, double distance, long now) {
        if (player.hasPotionEffect(PotionEffectType.DOLPHINS_GRACE)) {
            data.speedBudget.fill(now);
            return false;
        }
        double attribute = Exemptions.attribute(player, Attribute.MOVEMENT_SPEED, 0.1);
        boolean ice = now - data.lastIceMs < 2000;
        double rate = MovementMath.allowedRate(attribute, player.isSprinting(), player.getWalkSpeed(), ice,
            settings.speedMultiplier, settings.speedIceMultiplier);
        if (usingKineticWeapon(player)) {
            rate += KINETIC_EXTRA_RATE;
        }
        if (data.momentum.active()) {
            double normalStep = rate / 20.0;
            if (data.momentum.horizontal(distance, normalStep)) {
                plugin.violations().flag(player, data, CheckType.SPEED, 1.0,
                    String.format(Locale.ROOT, "kept %.2f blocks/tick in the air without slowing down", distance));
                return true;
            }
            if (distance > normalStep) {
                // Fast but slowing down like real launch momentum: legit, and not charged to the budget.
                data.speedBudget.fill(now);
                return false;
            }
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

    private boolean checkFly(Player player, PlayerData data, Settings settings, Location to, double dy,
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

        if (data.momentum.risingPhase()) {
            double gravity = Exemptions.attribute(player, Attribute.GRAVITY, 0.08);
            if (data.momentum.vertical(dy, gravity) && !Environment.platformNearby(player)) {
                plugin.violations().flag(player, data, CheckType.FLY, 1.0,
                    String.format(Locale.ROOT, "kept rising %.2f blocks/tick without slowing down", dy));
                return true;
            }
            // Still coasting up (or just reached the top): measure jumps and hovering from here.
            data.groundY = to.getY();
            data.hoverCheckpointMs = now;
            data.hoverCheckpointY = to.getY();
            return false;
        }

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
            data.noGroundMoves = 0;
            return;
        }
        if (!env.grounded()) {
            data.noGroundMoves = 0; // only grounded moves in a row count as a denied landing
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
        double safe = Exemptions.attribute(player, Attribute.SAFE_FALL_DISTANCE, 3.0);
        double multiplier = Exemptions.attribute(player, Attribute.FALL_DAMAGE_MULTIPLIER, 1.0);
        double damage = Math.ceil((data.fallDistance - safe) * multiplier);
        String lie = null;
        if (!player.isOnGround() && damage >= 1.0 && !env.softLanding()) {
            // The server sees the player land, but the client says it is still in the air. Vanilla only deals
            // fall damage when the client claims ground, so a "NoGround" client never takes any. A real
            // client touches down within a move or two: give it three before calling it a lie.
            if (++data.noGroundMoves < 3) {
                return; // keep the fall distance until the client admits the landing
            }
            lie = String.format(Locale.ROOT, "landed after %.1f blocks but never claimed ground", data.fallDistance);
        } else if (data.groundSpoofs >= settings.noFallMinSpoofs && damage >= 1.0 && !env.softLanding()) {
            lie = String.format(Locale.ROOT, "fell %.1f blocks while claiming ground %d time(s)",
                data.fallDistance, data.groundSpoofs);
        }
        data.fallDistance = 0;
        data.groundSpoofs = 0;
        data.noGroundMoves = 0;
        if (lie == null || !settings.enabled(CheckType.NOFALL)
            || !plugin.violations().flag(player, data, CheckType.NOFALL, 1.0, lie)) {
            return;
        }
        // Damage on the next tick, outside the move event. Armor and Feather Falling still apply.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && !player.isDead()) {
                player.damage(damage, DamageSource.builder(DamageType.FALL).build());
            }
        });
    }

    /**
     * Vanilla dealt fall damage itself (the client told the truth at some point): the fall is paid for, so
     * NoGround / spoof tracking starts over. This also stops double damage after a ghost block.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFallDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            PlayerData data = plugin.players().getIfPresent(player.getUniqueId());
            if (data != null) {
                data.fallDistance = 0;
                data.groundSpoofs = 0;
                data.noGroundMoves = 0;
            }
        }
    }

    private static void setback(PlayerMoveEvent event, PlayerData data, long now) {
        Location from = event.getFrom();
        Location target = data.lastGood != null && data.lastGood.getWorld() == from.getWorld()
            && data.lastGood.distanceSquared(from) < 64 * 64 ? data.lastGood.clone() : from.clone();
        target.setYaw(event.getTo().getYaw());
        target.setPitch(event.getTo().getPitch());
        data.selfTeleportUntilMs = now + 1000;
        event.setTo(target);
        data.resetMovement(target, now);
        data.momentum.stop(); // the player is back on a known spot: old launch momentum no longer applies
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
        plugin.safety().run("hover-task", this::checkStandingStill);
    }

    private void checkStandingStill() {
        Settings settings = plugin.settings();
        boolean fly = settings.enabled(CheckType.FLY);
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerData data = plugin.players().getIfPresent(player.getUniqueId());
            if (data != null && data.velocity.isDue(now)) {
                boolean noMoveSinceHit = data.lastMoveMs < data.velocityStartMs;
                boolean wasActive = data.velocityStartMs - data.lastMoveMs < 2000 && player.getPing() < 300;
                if (noMoveSinceHit && !wasActive) {
                    data.velocity.cancel(); // Idle or lagging before the hit: cannot judge.
                } else {
                    // Knockback always moves a player; staying put after a hit counts as "no knockback".
                    trackKnockback(player, data, 0, 0, now);
                }
            }
            if (!fly || data == null || !data.airborne) {
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
                "hovering while standing still (no setback: could also be a frozen connection)", false);
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
