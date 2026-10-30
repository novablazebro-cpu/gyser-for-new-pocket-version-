package io.github.novablaze.novaguard.movement;

import io.github.novablaze.novaguard.CheckType;
import io.github.novablaze.novaguard.NovaGuardPlugin;
import io.github.novablaze.novaguard.core.PlayerData;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.AttackRange;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.papermc.paper.event.entity.EntityLungeEvent;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.AbstractWindCharge;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/**
 * Gives short "grace" periods after every legit way of moving faster or higher than normal,
 * and keeps player data in sync with join, quit, teleport and respawn.
 */
public final class GraceListener implements Listener {

    private final NovaGuardPlugin plugin;

    public GraceListener(NovaGuardPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerData data = plugin.players().get(player);
        long now = System.currentTimeMillis();
        data.resetMovement(player.getLocation(), now);
        data.grace(now, 5000);
        plugin.alts().onJoin(player);
        plugin.monitor().onJoin(player, data);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.monitor().onQuit(event.getPlayer());
        plugin.players().remove(event.getPlayer().getUniqueId());
        plugin.fingerprints().forget(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        PlayerData data = plugin.players().get(player);
        long now = System.currentTimeMillis();
        Location to = event.getTo();
        data.resetMovement(to, now);
        if (now < data.selfTeleportUntilMs) {
            // Our own setback: no grace and the speed budget stays empty, so cheating earns nothing.
            data.selfTeleportUntilMs = 0;
            return;
        }
        data.speedBudget.fill(now);
        data.teleportedAtMs = now;
        data.history.clear();
        data.grace(now, 1000 + player.getPing());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        reset(event.getPlayer(), event.getRespawnLocation(), 3000);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        reset(event.getPlayer(), event.getPlayer().getLocation(), 3000);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        grace(event.getPlayer(), 3000);
    }

    /** Every knockback: hits, sweeps, shields, explosions, wind charges, mace smashes on others. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKnockback(EntityKnockbackEvent event) {
        if (event.getEntity() instanceof Player player) {
            graceForVelocity(player, event.getKnockback());
            startKnockbackCheck(player, event);
        }
    }

    /** Anti-knockback (Velocity): only for melee hits, and only where knockback can actually move the player. */
    private void startKnockbackCheck(Player player, EntityKnockbackEvent event) {
        EntityKnockbackEvent.Cause cause = event.getCause();
        if (!plugin.settings().enabled(CheckType.VELOCITY)
            || cause != EntityKnockbackEvent.Cause.ENTITY_ATTACK && cause != EntityKnockbackEvent.Cause.SWEEP_ATTACK) {
            return;
        }
        PlayerData data = plugin.players().getIfPresent(player.getUniqueId());
        long now = System.currentTimeMillis();
        if (data == null || plugin.exemptions().skipAll(player, data, now) || player.isInsideVehicle()
            || player.isGliding() || player.isFlying()
            || Exemptions.attribute(player, Attribute.KNOCKBACK_RESISTANCE, 0) >= 0.5) {
            return;
        }
        Vector push = event.getKnockback();
        double horizontal = Math.hypot(push.getX(), push.getZ());
        if (horizontal < 0.15) {
            return;
        }
        // A wall right behind the player stops knockback for real: no judgement possible.
        org.bukkit.util.BoundingBox behind = player.getBoundingBox()
            .shift(push.getX() / horizontal * 0.5, 0.05, push.getZ() / horizontal * 0.5);
        if (player.getWorld().hasCollisionsIn(behind) || Environment.probe(player, player.getLocation()).supported()) {
            return;
        }
        long window = Math.min(1500, 400 + (long) (player.getPing() * 1.5));
        data.velocityStartMs = now;
        data.velocity.start(now, push.getX(), push.getZ(), horizontal, window);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        graceForVelocity(event.getPlayer(), event.getVelocity());
    }

    /** Only damage that pushes the player (fire or hunger damage does not move anyone). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && pushes(event.getCause())) {
            grace(player, 500 + player.getPing());
        }
        // A mace smash (and Wind Burst) launches the attacker upward.
        if (event instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager() instanceof Player attacker
            && event.getDamageSource().getDamageType() == DamageType.MACE_SMASH) {
            grace(attacker, 3000 + attacker.getPing());
        }
    }

    /** Wind charges launch every nearby player, including the thrower. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (event.getEntity() instanceof AbstractWindCharge windCharge) {
            for (Entity entity : windCharge.getWorld().getNearbyEntities(windCharge.getLocation(), 5, 5, 5)) {
                if (entity instanceof Player player) {
                    grace(player, 2500 + player.getPing());
                }
            }
        }
    }

    private static boolean pushes(EntityDamageEvent.DamageCause cause) {
        return switch (cause) {
            case ENTITY_ATTACK, ENTITY_SWEEP_ATTACK, PROJECTILE, ENTITY_EXPLOSION, BLOCK_EXPLOSION, THORNS,
                 SONIC_BOOM, CONTACT, LIGHTNING -> true;
            default -> false;
        };
    }

    /** Spear Lunge: up to 1.374 blocks/tick (Lunge III) forward. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLunge(EntityLungeEvent event) {
        if (event.getEntity() instanceof Player player) {
            grace(player, 1200 + 400L * Math.max(1, event.getLungePower()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRiptide(PlayerRiptideEvent event) {
        grace(event.getPlayer(), 3000);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent event) {
        if (event.getEntity() instanceof Player player) {
            grace(player, 2500);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlightToggle(PlayerToggleFlightEvent event) {
        grace(event.getPlayer(), 2500);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDismount(EntityDismountEvent event) {
        if (event.getEntity() instanceof Player player) {
            grace(player, 2000);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMount(EntityMountEvent event) {
        if (event.getEntity() instanceof Player player) {
            grace(player, 1000);
        }
    }

    /** Jump Boost, Levitation, Slow Falling, Speed... starting or ending changes the limits mid-air. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEffect(EntityPotionEffectEvent event) {
        if (event.getEntity() instanceof Player player) {
            grace(player, 1500);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        graceNear(event.getBlock(), event.getBlocks());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        graceNear(event.getBlock(), event.getBlocks());
    }

    /**
     * Remembers the reach of the item the player switched away from, so attribute swapping
     * (switching items in the same tick as attacking) is never punished.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        PlayerData data = plugin.players().get(player);
        ItemStack previous = player.getInventory().getItem(event.getPreviousSlot());
        AttackRange range = previous == null || previous.isEmpty() ? null : previous.getData(DataComponentTypes.ATTACK_RANGE);
        data.previousItemReach = range != null ? range.maxReach() : 0;
        data.previousItemMargin = range != null ? range.hitboxMargin() : 0;
        data.previousItemUntilMs = System.currentTimeMillis() + 250;
    }

    /**
     * Pistons move players directly (no velocity event). A piston moving slime or honey blocks is a launcher,
     * so players nearby get a longer grace; momentum tracking covers whatever flight is left after it.
     */
    private void graceNear(Block piston, List<Block> moved) {
        boolean launcher = false;
        for (Block block : moved) {
            Material type = block.getType();
            if (type == Material.SLIME_BLOCK || type == Material.HONEY_BLOCK) {
                launcher = true;
                break;
            }
        }
        double radius = launcher ? 5.0 : 3.5;
        long duration = launcher ? 3000 : 1500;
        Location center = piston.getLocation().add(0.5, 0.5, 0.5);
        for (Entity entity : piston.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (entity instanceof Player player) {
                grace(player, duration + player.getPing());
            }
        }
    }

    private void graceForVelocity(Player player, Vector velocity) {
        // Stronger pushes fly longer: 0.6 s + ping + 1.5 s per block/tick of velocity, at most 5 s.
        long duration = 600 + player.getPing() + (long) (velocity.length() * 1500);
        grace(player, Math.min(5000, duration));
    }

    private void grace(Player player, long durationMs) {
        PlayerData data = plugin.players().getIfPresent(player.getUniqueId());
        if (data != null) {
            data.grace(System.currentTimeMillis(), durationMs);
        }
    }

    private void reset(Player player, Location location, long graceMs) {
        PlayerData data = plugin.players().get(player);
        long now = System.currentTimeMillis();
        data.resetMovement(location, now);
        data.speedBudget.fill(now);
        data.history.clear();
        data.teleportedAtMs = now;
        data.grace(now, graceMs);
    }
}
