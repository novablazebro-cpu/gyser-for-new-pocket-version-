package io.github.novablaze.novaguard;

/**
 * Every check NovaGuard runs. The config key is the section name under {@code checks:} in config.yml.
 */
public enum CheckType {
    SPEED("speed", "Speed"),
    FLY("fly", "Fly"),
    NOFALL("nofall", "NoFall"),
    REACH("reach", "Reach"),
    AIM("aim", "Aim"),
    WALL_HIT("wall-hit", "WallHit"),
    MULTI_TARGET("multi-target", "MultiTarget"),
    AUTOCLICKER("autoclicker", "AutoClicker"),
    BLOCK_REACH("block-reach", "BlockReach"),
    VELOCITY("velocity", "Velocity"),
    DUPE("dupe", "AntiDupe");

    private final String configKey;
    private final String displayName;

    CheckType(String configKey, String displayName) {
        this.configKey = configKey;
        this.displayName = displayName;
    }

    public String configKey() {
        return configKey;
    }

    public String displayName() {
        return displayName;
    }
}
