package io.github.novablaze.novaguard.core;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Detects Bedrock players that join through Geyser + Floodgate.
 * Uses the Floodgate API through reflection (so NovaGuard also works without Floodgate installed),
 * and falls back to Floodgate's UUID format (the first 64 bits are zero).
 */
public final class BedrockDetector {

    private Object floodgateApi;
    private Method isFloodgatePlayer;

    public BedrockDetector(Logger logger) {
        Plugin floodgate = Bukkit.getPluginManager().getPlugin("floodgate");
        if (floodgate == null) {
            return;
        }
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi", true,
                floodgate.getClass().getClassLoader());
            floodgateApi = apiClass.getMethod("getInstance").invoke(null);
            isFloodgatePlayer = apiClass.getMethod("isFloodgatePlayer", UUID.class);
            logger.info("Floodgate found: Bedrock players are skipped by the anti-cheat checks.");
        } catch (ReflectiveOperationException | LinkageError e) {
            floodgateApi = null;
            isFloodgatePlayer = null;
            logger.warning("Floodgate found but its API could not be used (" + e + "). Using the UUID fallback.");
        }
    }

    public boolean isBedrock(Player player) {
        UUID uuid = player.getUniqueId();
        if (floodgateApi != null) {
            try {
                if ((Boolean) isFloodgatePlayer.invoke(floodgateApi, uuid)) {
                    return true;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Fall through to the UUID check.
            }
        }
        return uuid.getMostSignificantBits() == 0L;
    }
}
