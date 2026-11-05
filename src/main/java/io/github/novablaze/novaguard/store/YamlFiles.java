package io.github.novablaze.novaguard.store;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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

    /** Makes the rename itself durable (Linux); not possible on every system, so failures are ignored. */
    private static void syncDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | RuntimeException ignored) {
            // Windows cannot open a directory like this: the file itself is already on disk.
        }
    }

    /** @return true when saved */
    public static boolean save(YamlConfiguration yaml, File file, Logger logger) {
        Path target = file.toPath();
        Path temp = target.resolveSibling(file.getName() + ".tmp");
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
            byte[] bytes = yaml.saveToString().getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                // On disk before the rename: otherwise a power cut can leave the renamed file empty.
                channel.force(true);
            }
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            syncDirectory(target.toAbsolutePath().getParent());
            return true;
        } catch (IOException e) {
            logger.warning("Could not save " + file.getName() + ": " + e.getMessage());
            return false;
        }
    }
}
