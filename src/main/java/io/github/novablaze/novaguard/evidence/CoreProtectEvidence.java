package io.github.novablaze.novaguard.evidence;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;

/**
 * Asks CoreProtect (when it is installed) who placed and who opened the block an illegal item was found in.
 * CoreProtect's plugin API only answers block questions (placed / removed / interaction), not which items went in
 * or out of a chest, so each stash also gets the in-game command that shows that.
 *
 * <p>Reached by reflection: NovaGuard does not need CoreProtect to build or to run. Database lookups are slow, so
 * this is only ever called from the evidence background thread, never from the main thread.
 */
final class CoreProtectEvidence {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault());
    private static final int ACTION_PLACED = 1;
    private static final int ACTION_INTERACTION = 2;

    private final Object api;
    private final Method blockLookup;
    private final Method parseResult;
    private final Logger logger;
    private boolean broken;

    private CoreProtectEvidence(Object api, Method blockLookup, Method parseResult, Logger logger) {
        this.api = api;
        this.blockLookup = blockLookup;
        this.parseResult = parseResult;
        this.logger = logger;
    }

    /** @return null when CoreProtect is missing, disabled, or has an API this code does not understand. */
    static CoreProtectEvidence connect(Logger logger) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("CoreProtect");
        if (plugin == null || !plugin.isEnabled()) {
            return null;
        }
        try {
            Object api = plugin.getClass().getMethod("getAPI").invoke(plugin);
            if (api == null || !Boolean.TRUE.equals(api.getClass().getMethod("isEnabled").invoke(api))) {
                logger.warning("CoreProtect is installed but its API is switched off (api-enabled in its config):"
                    + " stashes get no CoreProtect evidence.");
                return null;
            }
            Method lookup = api.getClass().getMethod("blockLookup", Block.class, int.class);
            Method parse = api.getClass().getMethod("parseResult", String[].class);
            logger.info("CoreProtect found: stashes get who placed and who opened the container.");
            return new CoreProtectEvidence(api, lookup, parse, logger);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            logger.warning("CoreProtect is installed but its API could not be used (" + e
                + "): stashes get no CoreProtect evidence.");
            return null;
        }
    }

    /** Evidence lines for the block at x y z (background thread only). Never throws. */
    List<String> evidence(World world, int x, int y, int z, int days) {
        if (broken) {
            return List.of();
        }
        try {
            Block block = world.getBlockAt(x, y, z);
            Object raw = blockLookup.invoke(api, block, days * 86_400);
            if (!(raw instanceof List<?> rows)) {
                return List.of("CoreProtect: no history for this block in the last " + days + " days");
            }
            String placedBy = null;
            long placedAt = 0;
            Map<String, int[]> openedCount = new LinkedHashMap<>();
            Map<String, Long> openedLast = new LinkedHashMap<>();
            for (Object row : rows) {
                if (!(row instanceof String[] fields)) {
                    continue;
                }
                Object parsed = parseResult.invoke(api, (Object) fields);
                int action = ((Number) call(parsed, "getActionId")).intValue();
                String player = String.valueOf(call(parsed, "getPlayer"));
                long time = toMillis(((Number) call(parsed, "getTimestamp")).longValue());
                if (action == ACTION_PLACED && time >= placedAt) {
                    placedBy = player;
                    placedAt = time;
                } else if (action == ACTION_INTERACTION) {
                    openedCount.computeIfAbsent(player, p -> new int[1])[0]++;
                    openedLast.merge(player, time, Math::max);
                }
            }
            List<String> lines = new ArrayList<>();
            lines.add(placedBy == null ? "CoreProtect: no record of who placed this block (older than " + days
                + " days, or placed before CoreProtect)"
                : "CoreProtect: placed by " + placedBy + " on " + DAY.format(Instant.ofEpochMilli(placedAt)));
            if (!openedCount.isEmpty()) {
                List<String> who = new ArrayList<>();
                openedCount.entrySet().stream()
                    .sorted((a, b) -> b.getValue()[0] - a.getValue()[0])
                    .limit(6)
                    .forEach(e -> who.add(e.getKey() + " " + e.getValue()[0] + "x (last "
                        + DAY.format(Instant.ofEpochMilli(openedLast.get(e.getKey()))) + ")"));
                lines.add("CoreProtect: opened/used by " + String.join(", ", who));
            }
            return lines;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            broken = true;
            logger.warning("CoreProtect lookups failed and are switched off until restart: " + e);
            return List.of();
        }
    }

    private static Object call(Object target, String method) throws ReflectiveOperationException {
        return target.getClass().getMethod(method).invoke(target);
    }

    /** CoreProtect versions differ: some give seconds, some milliseconds. */
    static long toMillis(long timestamp) {
        return timestamp < 100_000_000_000L ? timestamp * 1000L : timestamp;
    }
}
