package io.github.novablaze.novaguard.core;

import io.github.novablaze.novaguard.CheckType;
import io.github.novablaze.novaguard.NovaGuardPlugin;
import io.github.novablaze.novaguard.Settings;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerKickEvent;

/** Adds VL, sends alerts, writes the log file and decides about kicks. */
public final class ViolationManager {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final NovaGuardPlugin plugin;
    private final ExecutorService fileWriter = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "NovaGuard-log");
        thread.setDaemon(true);
        return thread;
    });
    private final File logFile;

    public ViolationManager(NovaGuardPlugin plugin) {
        this.plugin = plugin;
        this.logFile = new File(plugin.getDataFolder(), "violations.log");
    }

    /**
     * Records a failed check.
     *
     * @param amount VL to add
     * @param detail short human readable reason, e.g. "reach 4.91 > 3.55"
     */
    public void flag(Player player, PlayerData data, CheckType type, double amount, String detail) {
        Settings settings = plugin.settings();
        double vl = data.violations.add(type, amount);
        long now = System.currentTimeMillis();
        int ping = player.getPing();
        String line = String.format(Locale.ROOT, "%s failed %s (%s) VL %.1f ping %dms tps %.1f",
            player.getName(), type.displayName(), detail, vl, ping, plugin.lag().tps());

        log(line);
        if (now - data.lastAlertMs[type.ordinal()] >= settings.alertCooldownMs) {
            data.lastAlertMs[type.ordinal()] = now;
            alert(player.getName(), type, detail, vl, ping);
            if (settings.alertConsole) {
                plugin.getLogger().info(line);
            }
        }

        double kickVl = settings.check(type).kickVl();
        if (kickVl > 0 && vl >= kickVl && kickAllowed(player, data, now)) {
            data.violations.reset(type);
            String reason = type.displayName();
            log(player.getName() + " was kicked for " + reason + " (VL " + String.format(Locale.ROOT, "%.1f", vl) + ")");
            Component message = MiniMessage.miniMessage().deserialize(settings.kickMessage,
                Placeholder.unparsed("check", reason));
            // Kick on the next tick: kicking inside some events (like a move) is unsafe.
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    player.kick(message, PlayerKickEvent.Cause.PLUGIN);
                }
            });
        }
    }

    /** Kicks only happen when lag cannot be the reason for the violations. */
    private boolean kickAllowed(Player player, PlayerData data, long now) {
        Settings settings = plugin.settings();
        return settings.kickEnabled
            && player.getPing() <= settings.kickMaxPing
            && plugin.lag().tps() >= settings.kickMinTps
            && now - data.joinMs >= settings.kickJoinGraceMs
            && now >= data.lagSpikeUntilMs;
    }

    /** Anti-dupe alert: not tied to a check VL, never kicks. */
    public void dupeAlert(String playerName, String detail) {
        String line = playerName + " dupe alert: " + detail;
        log(line);
        alert(playerName, CheckType.DUPE, detail, -1, -1);
        if (plugin.settings().alertConsole) {
            plugin.getLogger().warning(line);
        }
    }

    private void alert(String playerName, CheckType type, String detail, double vl, int ping) {
        Component message = Component.text("[NovaGuard] ", NamedTextColor.DARK_AQUA)
            .append(Component.text(playerName, NamedTextColor.WHITE))
            .append(Component.text(" failed ", NamedTextColor.GRAY))
            .append(Component.text(type.displayName(), NamedTextColor.RED))
            .append(Component.text(" (" + detail + ")", NamedTextColor.GRAY));
        if (vl >= 0) {
            message = message.append(Component.text(String.format(Locale.ROOT, " VL %.1f, %dms", vl, ping),
                NamedTextColor.YELLOW));
        }
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (!staff.hasPermission("novaguard.alerts")) {
                continue;
            }
            PlayerData staffData = plugin.players().getIfPresent(staff.getUniqueId());
            if (staffData == null || staffData.alertsEnabled) {
                staff.sendMessage(message);
            }
        }
    }

    private void log(String line) {
        if (!plugin.settings().logToFile) {
            return;
        }
        String entry = "[" + LocalDateTime.now().format(TIME) + "] " + line + System.lineSeparator();
        fileWriter.execute(() -> {
            try {
                Files.createDirectories(logFile.getParentFile().toPath());
                try (BufferedWriter writer = Files.newBufferedWriter(logFile.toPath(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    writer.write(entry);
                }
            } catch (IOException e) {
                plugin.getLogger().warning("Could not write violations.log: " + e.getMessage());
            }
        });
    }

    public void shutdown() {
        fileWriter.shutdown();
        try {
            fileWriter.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
