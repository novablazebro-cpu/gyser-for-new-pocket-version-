package io.github.novablaze.novaguard.core;

import io.github.novablaze.novaguard.NovaGuardPlugin;
import io.github.novablaze.novaguard.store.YamlFiles;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Remembers server crashes and rollbacks. A crash (or the panel's "Kill" button, or a restored backup) saves the
 * world and the players at different moments, which copies items by itself. Copies of items that existed before
 * such a moment are still removed, but nobody is warned or banned for them.
 *
 * <p>Crash detection: a marker file is created when NovaGuard starts and deleted on a clean shutdown. If the file
 * is still there on the next start, the server did not shut down cleanly.
 */
public final class ServerState {

    private final NovaGuardPlugin plugin;
    private final File file;
    private final File marker;
    /** Last crash / rollback (0 = none known). */
    private long rollbackMs;
    private String rollbackReason = "";
    /** Dupe warnings are paused until then (mass-dupe fuse). */
    private long strikesPausedUntilMs;
    /** Last /cleanallexploits (0 = never): chunks and players stamped before it are cleaned when next used. */
    private long cleanEpochMs;

    public ServerState(NovaGuardPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "state.yml");
        this.marker = new File(plugin.getDataFolder(), "running.lock");
        load();
    }

    /** Called on enable: detects an unclean shutdown, then creates the marker for this run. */
    public void start(long nowMs) {
        if (marker.exists()) {
            markRollback(nowMs, "the server did not shut down cleanly (crash or kill)");
            plugin.getLogger().warning("The server did not shut down cleanly last time. Duplicated items from before"
                + " now are still removed, but nobody is warned or banned for them (a crash can copy items).");
        }
        try {
            Files.createDirectories(marker.getParentFile().toPath());
            Files.writeString(marker.toPath(), Long.toString(nowMs));
        } catch (IOException e) {
            plugin.getLogger().warning("Could not write running.lock (crash detection off): " + e.getMessage());
        }
    }

    /** Called on a clean disable. */
    public void stop() {
        try {
            Files.deleteIfExists(marker.toPath());
        } catch (IOException e) {
            plugin.getLogger().warning("Could not delete running.lock: " + e.getMessage());
        }
    }

    public void markRollback(long nowMs, String reason) {
        rollbackMs = nowMs;
        rollbackReason = reason;
        save();
    }

    /**
     * True when a copy of an item born at {@code bornMs} may have been created by the last crash/rollback.
     * Items without a birth time (tagged by older NovaGuard versions) count as born before it.
     */
    public boolean possibleRollback(long bornMs, long nowMs, long graceMs) {
        return rollbackMs > 0 && nowMs - rollbackMs < graceMs && bornMs <= rollbackMs;
    }

    public long rollbackMs() {
        return rollbackMs;
    }

    public String rollbackReason() {
        return rollbackReason;
    }

    public long cleanEpochMs() {
        return cleanEpochMs;
    }

    public void setCleanEpoch(long epochMs) {
        cleanEpochMs = epochMs;
        save();
    }

    public long strikesPausedUntilMs() {
        return strikesPausedUntilMs;
    }

    public void pauseStrikes(long untilMs) {
        strikesPausedUntilMs = untilMs;
        save();
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        rollbackMs = yaml.getLong("rollback-time");
        rollbackReason = yaml.getString("rollback-reason", "");
        strikesPausedUntilMs = yaml.getLong("strikes-paused-until");
        cleanEpochMs = yaml.getLong("clean-epoch");
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("rollback-time", rollbackMs);
        yaml.set("rollback-reason", rollbackReason);
        yaml.set("strikes-paused-until", strikesPausedUntilMs);
        yaml.set("clean-epoch", cleanEpochMs);
        YamlFiles.save(yaml, file, plugin.getLogger());
    }
}
