package io.github.novablaze.novaguard.dupe;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Many dupes are bugs in the server itself (TNT dupers, entity UUID clones, packet spam), and Paper already
 * fixes them, unless an admin switched the fix off. This class reads Paper's settings and lists every
 * setting that re-opens a known dupe. Pure logic: the caller passes a lookup from setting path to value.
 */
public final class ConfigRisks {

    private ConfigRisks() {
    }

    /** Settings from {@code config/paper-global.yml}. */
    public static List<String> global(Function<String, Object> get) {
        List<String> risks = new ArrayList<>();
        flagTrue(get, "unsupported-settings.allow-piston-duplication",
            "TNT, carpet and rail duplicators work", risks);
        flagTrue(get, "unsupported-settings.allow-headless-pistons",
            "headless pistons can break bedrock and move blocks that should not move", risks);
        flagTrue(get, "unsupported-settings.allow-permanent-block-break-exploits",
            "end portal frames, bedrock and other unbreakable blocks can be broken and collected", risks);
        flagTrue(get, "unsupported-settings.allow-unsafe-end-portal-teleportation",
            "entities and items going through end portals can be duplicated", risks);
        Object rate = get.apply("packet-limiter.all-packets.max-packet-rate");
        if (rate instanceof Number n && n.doubleValue() <= 0) {
            risks.add("packet-limiter.all-packets.max-packet-rate is " + n
                + ": packet spam is unlimited, which many inventory-desync dupes need");
        }
        return risks;
    }

    /** Settings from {@code config/paper-world-defaults.yml} or one world's own override file. */
    public static List<String> world(Function<String, Object> get) {
        List<String> risks = new ArrayList<>();
        Object mode = get.apply("entities.spawning.duplicate-uuid.mode");
        if (mode != null) {
            String value = mode.toString().toUpperCase(Locale.ROOT);
            if (value.equals("NOTHING") || value.equals("WARN")) {
                risks.add("entities.spawning.duplicate-uuid.mode is " + value
                    + ": a cloned entity (a donkey or chest minecart full of items) stays in the world;"
                    + " use SAFE_REGEN");
            }
        }
        return risks;
    }

    private static void flagTrue(Function<String, Object> get, String path, String why, List<String> out) {
        if (Boolean.TRUE.equals(get.apply(path))) {
            out.add(path + " is true: " + why);
        }
    }
}
