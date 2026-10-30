package io.github.novablaze.novaguard.combat;

import io.github.novablaze.novaguard.CheckType;
import io.github.novablaze.novaguard.NovaGuardPlugin;
import io.github.novablaze.novaguard.Settings;
import io.github.novablaze.novaguard.core.PlayerData;
import io.github.novablaze.novaguard.math.Box;
import io.github.novablaze.novaguard.math.Geometry;
import io.github.novablaze.novaguard.math.ReachMath;
import io.github.novablaze.novaguard.movement.Exemptions;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.AttackRange;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.util.Locale;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.ComplexEntityPart;
import org.bukkit.entity.ComplexLivingEntity;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * Combat checks. PrePlayerAttackEntityEvent fires for client attacks AND for server-computed spear stabs,
 * so spear hits are recognised by the held item and skipped (the server picks spear targets itself).
 */
public final class CombatListener implements Listener {

    private final NovaGuardPlugin plugin;

    public CombatListener(NovaGuardPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        Player player = event.getPlayer();
        PlayerData data = plugin.players().get(player);
        plugin.safety().run("combat", () -> check(event, player, data));
        if (data.shadow) {
            // Shadow mode (monitored Bedrock player): everything is recorded, nothing is blocked.
            // ignoreCancelled = true means the event was not cancelled before us, so undoing is safe.
            event.setCancelled(false);
        }
    }

    private void check(PrePlayerAttackEntityEvent event, Player player, PlayerData data) {
        Entity target = event.getAttacked();
        long now = System.currentTimeMillis();
        if (plugin.exemptions().skipAll(player, data, now) || player.isRiptiding() || serverComputedHit(player)) {
            return;
        }
        Settings settings = plugin.settings();
        if (data.monitored) {
            plugin.monitor().recordAttack(player.getUniqueId(), now);
        }

        if (settings.enabled(CheckType.AUTOCLICKER) && tooManyClicks(player, data, settings, now)) {
            block(event, data, CheckType.AUTOCLICKER);
            return;
        }
        if (settings.enabled(CheckType.MULTI_TARGET)) {
            int distinct = data.targets.record(target.getEntityId(), now, settings.multiWindowMs);
            if (distinct > settings.multiMaxTargets) {
                plugin.violations().flag(player, data, CheckType.MULTI_TARGET, 1.0,
                    distinct + " targets in " + settings.multiWindowMs + "ms");
                block(event, data, CheckType.MULTI_TARGET);
                return;
            }
        }
        if (target instanceof ComplexLivingEntity || target instanceof ComplexEntityPart
            || target.getWorld() != player.getWorld()) {
            return; // Ender dragon parts have odd hitboxes.
        }
        if (target instanceof LivingEntity living && (living.isGliding() || living.isRiptiding())) {
            return; // Elytra and riptide targets move too fast for any position-based check.
        }
        PlayerData targetData = target instanceof Player targetPlayer
            ? plugin.players().getIfPresent(targetPlayer.getUniqueId()) : null;
        int ping = player.getPing();
        if (targetData != null && now - targetData.teleportedAtMs < 1000L + ping) {
            return;
        }

        Location eye = player.getEyeLocation();
        long window = lagWindow(ping);
        Box seen = targetBox(target, targetData, eye, now, window);
        double distance = Geometry.distanceToBox(eye.getX(), eye.getY(), eye.getZ(), seen);
        double strictLimit = reachLimit(player, data, 0.0, now);
        double angle = angleToTarget(eye, seen, distance);
        if (data.monitored) {
            plugin.monitor().recordHit(player.getUniqueId(), distance, strictLimit, angle);
        }

        if (settings.enabled(CheckType.REACH)) {
            if (failsReach(player, data, distance, strictLimit + settings.reachTolerance)) {
                block(event, data, CheckType.REACH);
                return;
            }
            if (distance > strictLimit && settings.reachSoftDamage < 1.0
                && plugin.violations().enforces(data, CheckType.REACH)) {
                // Soft mitigation: slightly beyond real reach (probably lag) still hits, just weaker.
                data.softHitTarget = target.getEntityId();
                data.softHitUntilMs = now + 200;
            }
        }
        if (settings.enabled(CheckType.AIM) && failsAim(player, data, settings, angle)) {
            block(event, data, CheckType.AIM);
            return;
        }
        if (settings.enabled(CheckType.WALL_HIT) && failsWall(player, data, settings, eye, target, seen, distance)) {
            block(event, data, CheckType.WALL_HIT);
        }
    }

    /** Cancels the hit unless the check is in silent mode (or the player is in shadow mode). */
    private void block(PrePlayerAttackEntityEvent event, PlayerData data, CheckType type) {
        if (plugin.violations().enforces(data, type)) {
            event.setCancelled(true);
        }
    }

    /** Applies the reduced damage of a soft-mitigated hit. Runs right after the attack in the same tick. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        PlayerData data = plugin.players().getIfPresent(player.getUniqueId());
        if (data == null || data.softHitTarget != event.getEntity().getEntityId()) {
            return;
        }
        if (System.currentTimeMillis() <= data.softHitUntilMs) {
            event.setDamage(event.getDamage() * plugin.settings().reachSoftDamage);
        }
        data.softHitTarget = -1;
    }

    /** Spear jabs and charges are decided by the server, so a client cannot fake their reach. */
    private static boolean serverComputedHit(Player player) {
        if (isSpearLike(player.getInventory().getItemInMainHand())) {
            return true;
        }
        return player.isHandRaised() && isSpearLike(player.getActiveItem());
    }

    private static boolean isSpearLike(ItemStack item) {
        return item != null && !item.isEmpty()
            && (item.hasData(DataComponentTypes.PIERCING_WEAPON) || item.hasData(DataComponentTypes.KINETIC_WEAPON));
    }

    private boolean tooManyClicks(Player player, PlayerData data, Settings settings, long now) {
        int cps = data.clicks.record(now);
        if (cps <= settings.maxCps) {
            if (now - data.lastCpsStrikeMs > 1500) {
                data.cpsStrikes = 0;
            }
            return false;
        }
        if (now - data.lastCpsStrikeMs >= 1000) {
            data.lastCpsStrikeMs = now;
            if (++data.cpsStrikes >= settings.cpsSeconds) {
                plugin.violations().flag(player, data, CheckType.AUTOCLICKER, 1.0,
                    cps + " attacks/second for " + data.cpsStrikes + "s");
            }
        }
        return true;
    }

    /** How far back the attacker's screen may lag: ping-based, plus extra while the server itself lags. */
    private long lagWindow(int ping) {
        long window = ReachMath.windowMs(ping);
        double tps = plugin.lag().tps();
        if (tps < 19.0) {
            window += (long) ((20.0 - tps) * 50.0);
        }
        return Math.min(1500, window);
    }

    /**
     * For players: the closest hitbox they had within the lag window (backtracking).
     * For mobs: the current hitbox grown by how far the mob can move within the window.
     */
    private static Box targetBox(Entity target, PlayerData targetData, Location eye, long now, long window) {
        if (targetData != null && targetData.history.size() > 0) {
            Box best = targetData.history.closestBox(eye.getX(), eye.getY(), eye.getZ(), now - window);
            Box current = toBox(target.getBoundingBox());
            double bestDistance = Geometry.distanceToBox(eye.getX(), eye.getY(), eye.getZ(), best);
            double currentDistance = Geometry.distanceToBox(eye.getX(), eye.getY(), eye.getZ(), current);
            return currentDistance < bestDistance ? current : best;
        }
        double speedPerSecond = target.getVelocity().length() * 20.0;
        double grow = Math.min(2.0, speedPerSecond * window / 1000.0);
        return toBox(target.getBoundingBox()).expand(grow);
    }

    /** Real reach of the held (or just switched-away) item + hitbox margin + {@code tolerance}. */
    private static double reachLimit(Player player, PlayerData data, double tolerance, long now) {
        double interaction = Exemptions.attribute(player, Attribute.ENTITY_INTERACTION_RANGE, 3.0);
        ItemStack hand = player.getInventory().getItemInMainHand();
        AttackRange range = hand.isEmpty() ? null : hand.getData(DataComponentTypes.ATTACK_RANGE);
        double itemReach = range != null ? range.maxReach() : 0;
        double itemMargin = range != null ? range.hitboxMargin() : 0;
        if (now < data.previousItemUntilMs) {
            itemReach = Math.max(itemReach, data.previousItemReach);
            itemMargin = Math.max(itemMargin, data.previousItemMargin);
        }
        return ReachMath.limit(interaction, itemReach, itemMargin, tolerance);
    }

    private boolean failsReach(Player player, PlayerData data, double distance, double limit) {
        if (distance <= limit) {
            data.reachBuffer = Math.max(0, data.reachBuffer - 0.25);
            return false;
        }
        data.reachBuffer += 1;
        if (data.reachBuffer >= 2) {
            plugin.violations().flag(player, data, CheckType.REACH, 1.0,
                String.format(Locale.ROOT, "%.2f blocks, limit %.2f", distance, limit));
        } else if (data.monitored) {
            plugin.monitor().recordNearMiss(player.getUniqueId(), CheckType.REACH);
        }
        return true;
    }

    /** Degrees between the look direction and the target, or NaN when the target is too close to measure. */
    private static double angleToTarget(Location eye, Box seen, double distance) {
        if (distance < 1.0) {
            return Double.NaN; // Too close: any small move changes the angle a lot.
        }
        double[] point = Geometry.closestPoint(eye.getX(), eye.getY(), eye.getZ(), seen.expand(0.1));
        Vector look = eye.getDirection();
        return Geometry.angleDegrees(look.getX(), look.getY(), look.getZ(),
            point[0] - eye.getX(), point[1] - eye.getY(), point[2] - eye.getZ());
    }

    private boolean failsAim(Player player, PlayerData data, Settings settings, double angle) {
        if (Double.isNaN(angle) || angle <= settings.aimMaxAngle) {
            data.aimBuffer = 0;
            return false;
        }
        if (++data.aimBuffer < settings.aimBuffer) {
            if (data.monitored) {
                plugin.monitor().recordNearMiss(player.getUniqueId(), CheckType.AIM);
            }
            return false;
        }
        plugin.violations().flag(player, data, CheckType.AIM, 1.0,
            String.format(Locale.ROOT, "%.0f degrees off target", angle));
        return true;
    }

    private boolean failsWall(Player player, PlayerData data, Settings settings, Location eye, Entity target,
                              Box seen, double distance) {
        if (distance < 0.8) {
            return false;
        }
        Box current = toBox(target.getBoundingBox());
        if (visible(eye, current) || visible(eye, seen)) {
            data.wallBuffer = Math.max(0, data.wallBuffer - 1);
            return false;
        }
        if (++data.wallBuffer < settings.wallBuffer) {
            if (data.monitored) {
                plugin.monitor().recordNearMiss(player.getUniqueId(), CheckType.WALL_HIT);
            }
            return false;
        }
        plugin.violations().flag(player, data, CheckType.WALL_HIT, 1.0, "hit through a solid block");
        return true;
    }

    /** True when at least one of: closest point, center, top of the box can be seen from the eye. */
    private static boolean visible(Location eye, Box box) {
        double[] closest = Geometry.closestPoint(eye.getX(), eye.getY(), eye.getZ(), box);
        return clearLine(eye, closest[0], closest[1], closest[2])
            || clearLine(eye, box.centerX(), box.centerY(), box.centerZ())
            || clearLine(eye, box.centerX(), box.maxY() - 0.1, box.centerZ());
    }

    private static boolean clearLine(Location eye, double x, double y, double z) {
        Vector direction = new Vector(x - eye.getX(), y - eye.getY(), z - eye.getZ());
        double length = direction.length();
        if (length < 0.3) {
            return true;
        }
        World world = eye.getWorld();
        // Collision shapes, passable blocks (grass, flowers) ignored: hitting through grass is legit.
        return world.rayTraceBlocks(eye, direction.multiply(1.0 / length), length - 0.05,
            FluidCollisionMode.NEVER, true) == null;
    }

    private static Box toBox(BoundingBox box) {
        return new Box(box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ());
    }
}
