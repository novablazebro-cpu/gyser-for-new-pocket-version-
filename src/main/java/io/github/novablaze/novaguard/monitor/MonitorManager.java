package io.github.novablaze.novaguard.monitor;

import io.github.novablaze.novaguard.CheckType;
import io.github.novablaze.novaguard.NovaGuardPlugin;
import io.github.novablaze.novaguard.Settings;
import io.github.novablaze.novaguard.core.BedrockDetector;
import io.github.novablaze.novaguard.core.PlayerData;
import io.github.novablaze.novaguard.math.MonitorVerdict;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerKickEvent;

/**
 * /monitor: watches chosen players for a few days, records detailed statistics and writes a report.
 * Runs once a minute; all hooks are a single map lookup when nobody is monitored.
 */
public final class MonitorManager implements Runnable {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm")
        .withZone(ZoneId.systemDefault());
    private static final int SAVE_EVERY_MINUTES = 5;

    private final NovaGuardPlugin plugin;
    private final File file;
    private final File reportsFolder;
    private final Map<UUID, MonitorRecord> records = new HashMap<>();
    private int minutesSinceSave;

    public MonitorManager(NovaGuardPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "monitors.yml");
        this.reportsFolder = new File(plugin.getDataFolder(), "reports");
        load();
    }

    // ---------------------------------------------------------------- commands

    /** @return a message for the command sender */
    public String start(UUID uuid, String name, Player online, String claimedTag, String startedBy) {
        MonitorRecord existing = records.get(uuid);
        if (existing != null) {
            return name + " is already monitored; the report is due " + DATE.format(Instant.ofEpochMilli(existing.endMs)) + ".";
        }
        long now = System.currentTimeMillis();
        MonitorRecord record = new MonitorRecord(uuid, name);
        record.claimedTag = claimedTag;
        record.startedBy = startedBy;
        record.startMs = now;
        record.endMs = now + plugin.settings().monitorDurationHours * 3_600_000L;
        records.put(uuid, record);

        String typeNote;
        if (online != null) {
            PlayerData data = plugin.players().get(online);
            record.bedrock = data.bedrock;
            beginSession(record, online, data, now);
            typeNote = data.bedrock ? "Bedrock (Floodgate)" : "Java (cracked/offline)";
        } else {
            record.bedrock = BedrockDetector.isFloodgateUuid(uuid);
            typeNote = (record.bedrock ? "Bedrock" : "Java (cracked/offline)") + ", currently offline";
        }
        save();

        String message = "Monitoring " + name + " [" + typeNote + "] until "
            + DATE.format(Instant.ofEpochMilli(record.endMs)) + ".";
        if (claimedTag != null && Boolean.TRUE.equals(record.bedrock) != claimedTag.equals("bedrock")) {
            message += " Note: you tagged them '" + claimedTag + "' but NovaGuard detects them as "
                + (record.bedrock ? "Bedrock" : "Java (cracked)") + "; the detected type is used.";
        }
        if (Boolean.TRUE.equals(record.bedrock)) {
            message += " Bedrock players are watched in shadow mode: recorded, never punished.";
        }
        return message;
    }

    public boolean stop(UUID uuid) {
        MonitorRecord record = records.remove(uuid);
        if (record == null) {
            return false;
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            PlayerData data = plugin.players().get(player);
            data.monitored = false;
            data.shadow = false;
        }
        save();
        return true;
    }

    public Collection<MonitorRecord> all() {
        return records.values();
    }

    public MonitorRecord find(String name) {
        for (MonitorRecord record : records.values()) {
            if (record.name.equalsIgnoreCase(name)) {
                return record;
            }
        }
        return null;
    }

    /** Writes a report now without ending the watch. Returns the report file name. */
    public String earlyReport(MonitorRecord record) {
        updatePlaytime(record, System.currentTimeMillis());
        return writeReport(record, false).getName();
    }

    // ---------------------------------------------------------------- hooks from checks

    public void recordFlag(UUID uuid, CheckType type) {
        MonitorRecord record = records.get(uuid);
        if (record != null) {
            record.flags[type.ordinal()]++;
        }
    }

    public void recordNearMiss(UUID uuid, CheckType type) {
        MonitorRecord record = records.get(uuid);
        if (record != null) {
            record.nearMisses[type.ordinal()]++;
        }
    }

    public void recordAttack(UUID uuid, long nowMs) {
        MonitorRecord record = records.get(uuid);
        if (record != null) {
            record.recordAttack(nowMs);
        }
    }

    /**
     * @param distance lag-compensated distance from the eye to the target
     * @param strictLimit real reach + hitbox margin, without NovaGuard's tolerance
     * @param angle degrees between look direction and target, or NaN when too close to measure
     */
    public void recordHit(UUID uuid, double distance, double strictLimit, double angle) {
        MonitorRecord record = records.get(uuid);
        if (record == null) {
            return;
        }
        record.reach.add(distance);
        if (distance > strictLimit) {
            record.strictOverHits++;
        }
        if (!Double.isNaN(angle)) {
            record.angle.add(angle);
            if (angle > 45) {
                record.aimOver45++;
            }
        }
    }

    public void recordDupe(String playerName) {
        MonitorRecord record = find(playerName);
        if (record != null) {
            record.dupeAlerts++;
        }
    }

    // ---------------------------------------------------------------- join / quit

    public void onJoin(Player player, PlayerData data) {
        MonitorRecord record = records.get(player.getUniqueId());
        if (record == null) {
            return;
        }
        record.name = player.getName();
        record.bedrock = data.bedrock;
        if (record.pendingKick) {
            records.remove(player.getUniqueId());
            save();
            Bukkit.getScheduler().runTaskLater(plugin, () -> kick(player), 60L);
            return;
        }
        beginSession(record, player, data, System.currentTimeMillis());
    }

    public void onQuit(Player player) {
        MonitorRecord record = records.get(player.getUniqueId());
        if (record != null) {
            updatePlaytime(record, System.currentTimeMillis());
            record.lastSeenMs = 0;
            record.flushSecond();
        }
    }

    private void beginSession(MonitorRecord record, Player player, PlayerData data, long now) {
        record.sessions++;
        record.lastSeenMs = now;
        data.monitored = true;
        data.shadow = data.bedrock;
        // The client sends its brand shortly after joining.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                record.addBrand(player.getClientBrandName());
            }
        }, 100L);
    }

    // ---------------------------------------------------------------- once a minute

    @Override
    public void run() {
        long now = System.currentTimeMillis();
        for (MonitorRecord record : new ArrayList<>(records.values())) {
            Player player = Bukkit.getPlayer(record.uuid);
            if (player != null && record.lastSeenMs > 0) {
                updatePlaytime(record, now);
                record.ping.add(player.getPing());
                if (now / 1000 != record.currentSecond) {
                    record.flushSecond();
                }
            }
            if (!record.pendingKick && now >= record.endMs) {
                finish(record);
            }
        }
        if (++minutesSinceSave >= SAVE_EVERY_MINUTES) {
            minutesSinceSave = 0;
            save();
        }
    }

    private static void updatePlaytime(MonitorRecord record, long now) {
        if (record.lastSeenMs > 0) {
            record.playtimeMs += Math.max(0, now - record.lastSeenMs);
            record.lastSeenMs = now;
        }
    }

    private void finish(MonitorRecord record) {
        updatePlaytime(record, System.currentTimeMillis());
        record.flushSecond();
        MonitorVerdict verdict = MonitorVerdict.evaluate(record.evidence());
        boolean kick = verdict.level() == MonitorVerdict.Level.LIKELY_CHEATING
            && plugin.settings().monitorKickOnLikely && !Boolean.TRUE.equals(record.bedrock);
        Player player = Bukkit.getPlayer(record.uuid);
        if (kick && player == null) {
            record.pendingKick = true;
        }
        writeReport(record, true);
        Player online = Bukkit.getPlayer(record.uuid);
        if (online != null) {
            PlayerData data = plugin.players().get(online);
            data.monitored = false;
            data.shadow = false;
        }
        if (!record.pendingKick) {
            records.remove(record.uuid);
        }
        if (kick && player != null) {
            kick(player);
        }
        save();
    }

    private void kick(Player player) {
        if (!player.isOnline()) {
            return;
        }
        Settings settings = plugin.settings();
        player.kick(MiniMessage.miniMessage().deserialize(settings.monitorKickMessage), PlayerKickEvent.Cause.PLUGIN);
        plugin.getLogger().warning(player.getName() + " was kicked after a monitoring report: likely cheating.");
    }

    // ---------------------------------------------------------------- report

    private File writeReport(MonitorRecord record, boolean finalReport) {
        MonitorVerdict verdict = MonitorVerdict.evaluate(record.evidence());
        String text = ReportWriter.write(record, verdict, finalReport, plugin.settings().monitorKickOnLikely);
        // Floodgate names start with "." by default, which would make a hidden file: keep names plain.
        String safeName = record.name.replaceAll("[^A-Za-z0-9_-]", "_");
        File out = new File(reportsFolder, safeName + "-" + FILE_DATE.format(Instant.now())
            + (finalReport ? "" : "-early") + ".txt");
        try {
            Files.createDirectories(reportsFolder.toPath());
            Files.writeString(out.toPath(), text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not write report " + out.getName() + ": " + e.getMessage());
        }
        announce(record, verdict, out, finalReport);
        return out;
    }

    private void announce(MonitorRecord record, MonitorVerdict verdict, File out, boolean finalReport) {
        NamedTextColor color = switch (verdict.level()) {
            case LIKELY_CHEATING -> NamedTextColor.RED;
            case SUSPICIOUS -> NamedTextColor.GOLD;
            case NO_EVIDENCE -> NamedTextColor.GREEN;
        };
        String header = (finalReport ? "Monitoring report" : "Early monitoring report") + " for " + record.name + ": ";
        Component message = Component.text("[NovaGuard] ", NamedTextColor.DARK_AQUA)
            .append(Component.text(header, NamedTextColor.WHITE))
            .append(Component.text(verdict.level().text(), color))
            .append(Component.text(String.format(Locale.ROOT, " (score %.1f). File: plugins/NovaGuard/reports/%s",
                verdict.score(), out.getName()), NamedTextColor.GRAY));
        Bukkit.getConsoleSender().sendMessage(message);
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("novaguard.admin")) {
                staff.sendMessage(message);
            }
        }
    }

    // ---------------------------------------------------------------- storage

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(false)) {
            ConfigurationSection section = yaml.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            try {
                UUID uuid = UUID.fromString(key);
                records.put(uuid, MonitorRecord.load(uuid, section));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Skipping bad entry in monitors.yml: " + key);
            }
        }
        if (!records.isEmpty()) {
            plugin.getLogger().info("Resumed monitoring of " + records.size() + " player(s).");
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (MonitorRecord record : records.values()) {
            record.save(yaml.createSection(record.uuid.toString()));
        }
        try {
            Files.createDirectories(file.getParentFile().toPath());
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save monitors.yml: " + e.getMessage());
        }
    }

    /** Called on shutdown: count playtime up to now so nothing is lost. */
    public void shutdown() {
        long now = System.currentTimeMillis();
        for (MonitorRecord record : records.values()) {
            updatePlaytime(record, now);
            record.flushSecond();
        }
        save();
    }

    static String time(long epochMs) {
        return DATE.format(Instant.ofEpochMilli(epochMs));
    }

    static List<String> sorted(Collection<String> values) {
        List<String> list = new ArrayList<>(values);
        list.sort(String::compareToIgnoreCase);
        return list;
    }
}
