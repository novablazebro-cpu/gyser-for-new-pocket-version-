package io.github.novablaze.novaguard.store;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Crash-safe saving for NovaGuard's data files. The new content is written to "name.yml.tmp" first and then moved
 * over "name.yml" in one step, so a crash (or the panel's Kill button) in the middle of a save leaves either the
 * old or the new file, never a half-written one that loses queued bans or warnings.
 */
public final class YamlFiles {

    private YamlFiles() {
    }

    /** @return true when saved */
    public static boolean save(YamlConfiguration yaml, File file, Logger logger) {
        Path target = file.toPath();
        Path temp = target.resolveSibling(file.getName() + ".tmp");
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
            Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            logger.warning("Could not save " + file.getName() + ": " + e.getMessage());
            return false;
        }
    }
}
