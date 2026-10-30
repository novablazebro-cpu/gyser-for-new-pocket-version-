package io.github.novablaze.novaguard.movement;

import io.github.novablaze.novaguard.NovaGuardPlugin;
import io.github.novablaze.novaguard.Settings;
import io.github.novablaze.novaguard.core.PlayerData;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.GameMode;
import org.bukkit.attribute.Attributable;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

/** Decides when a player is not checked at all. Shared by movement and combat checks. */
public final class Exemptions {

    /** Attributes added in 26.x that change physics in ways the movement checks do not model. */
    private static final Attribute[] CUSTOM_PHYSICS = {
        Attribute.FRICTION_MODIFIER, Attribute.AIR_DRAG_MODIFIER, Attribute.BOUNCINESS
    };

    private final NovaGuardPlugin plugin;
    /** Attribute is a registry type, not an enum, so a HashMap is used. */
    private final Map<Attribute, Double> playerDefaults = new HashMap<>();

    public Exemptions(NovaGuardPlugin plugin) {
        this.plugin = plugin;
        Attributable defaults = null;
        try {
            defaults = EntityType.PLAYER.getDefaultAttributes();
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Could not read player default attributes, using registry defaults: " + e);
        }
        for (Attribute attribute : CUSTOM_PHYSICS) {
            AttributeInstance instance = defaults != null ? defaults.getAttribute(attribute) : null;
            playerDefaults.put(attribute, instance != null ? instance.getValue() : attribute.getDefaultValue());
        }
    }

    /** Common skips: Bedrock (unless monitored in shadow mode), bypass permission, creative/spectator, dead, manual /novaguard exempt. */
    public boolean skipAll(Player player, PlayerData data, long now) {
        if ((data.bedrock && !data.shadow) || now < data.manualExemptUntilMs || player.isDead()) {
            return true;
        }
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
            return true;
        }
        return player.hasPermission("novaguard.bypass");
    }

    /** Extra skips for movement checks: flight, gliding, vehicles, lag, grace periods, custom physics. */
    public boolean skipMovement(Player player, PlayerData data, long now) {
        if (skipAll(player, data, now)) {
            return true;
        }
        Settings settings = plugin.settings();
        if (player.getAllowFlight() || player.isFlying() || player.isGliding() || player.isRiptiding()
            || player.isInsideVehicle() || player.isSleeping()) {
            return true;
        }
        if (now < data.graceUntilMs) {
            return true;
        }
        if (plugin.lag().tps() < settings.minTpsForMovement || player.getPing() > settings.maxPingForMovement) {
            return true;
        }
        return hasCustomPhysics(player);
    }

    private boolean hasCustomPhysics(Player player) {
        for (Attribute attribute : CUSTOM_PHYSICS) {
            AttributeInstance instance = player.getAttribute(attribute);
            Double normal = playerDefaults.get(attribute);
            if (instance != null && normal != null && Math.abs(instance.getValue() - normal) > 1.0E-4) {
                return true;
            }
        }
        return false;
    }

    /** Live attribute value, or {@code fallback} when the player does not have that attribute. */
    public static double attribute(Player player, Attribute attribute, double fallback) {
        AttributeInstance instance = player.getAttribute(attribute);
        return instance != null ? instance.getValue() : fallback;
    }
}
