package io.github.novablaze.novaguard.ban;

import io.github.novablaze.novaguard.CheckType;
import io.github.novablaze.novaguard.NovaGuardPlugin;
import io.github.novablaze.novaguard.Settings;
import io.github.novablaze.novaguard.core.AltTracker;
import io.github.novablaze.novaguard.core.BedrockDetector;
import io.github.novablaze.novaguard.core.PlayerData;
import io.github.novablaze.novaguard.math.MonitorVerdict;
import io.github.novablaze.novaguard.monitor.MonitorManager;
import io.papermc.paper.ban.BanListType;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerKickEvent;

/**
 * Ban waves: suspects are watched silently, confirmed cheaters are queued, and the ban happens later at a
 * random time, so a cheater cannot tell which action got them caught. Staff are notified and can cancel.
 */
public final class BanManager implements Runnable {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault());
    private static final long DAY_MS = 86_400_000L;

    /** One player waiting for the next ban wave. */
    public static final class Pending {
        public final UUID uuid;
        public final String name;
        public final String reason;
        public final String report;
        public final long queuedMs;
        public final long executeMs;

        Pending(UUID uuid, String name, String reason, String report, long queuedMs, long executeMs) {
            this.uuid = uuid;
            this.name = name;
            this.reason = reason;
            this.report = report;
            this.queuedMs = queuedMs;
            this.executeMs = executeMs;
        }
    }

    private final NovaGuardPlugin plugin;
    private final File file;
    private final File history;
    private final Map<UUID, Pending> pending = new LinkedHashMap<>();
    /** Last time a "new suspect" notification was sent per player, so staff are not spammed. */
    private final Map<UUID, Long> suspectNotified = new HashMap<>();

    public BanManager(NovaGuardPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "bans.yml");
        this.history = new File(plugin.getDataFolder(), "bans-history.log");
        load();
    }

    // ---------------------------------------------------------------- suspects

    /**
     * Called when a check reaches its suspect VL. Starts a silent watch (if none) and counts a strong
     * detection for this session. The player sees nothing.
     */
    public void suspect(Player player, PlayerData data, CheckType type, double vl) {
        Settings settings = plugin.settings();
        if (!settings.banWaves || data.bedrock || pending.containsKey(player.getUniqueId())) {
            return;
        }
        MonitorManager monitor = plugin.monitor();
        if (monitor.get(player.getUniqueId()) == null) {
            monitor.start(player.getUniqueId(), player.getName(), player, null, "NovaGuard (automatic suspect)");
            plugin.getLogger().info(player.getName() + " is now a silent ban-wave suspect (" + type.displayName()
                + " VL " + Math.round(vl) + ").");
        }
        monitor.recordStrong(player.getUniqueId());
        long now = System.currentTimeMillis();
        Long last = suspectNotified.get(player.getUniqueId());
        if (settings.notifySuspect && (last == null || now - last > DAY_MS)) {
            suspectNotified.put(player.getUniqueId(), now);
            plugin.notifier().send("NovaGuard: new suspect " + player.getName(),
                player.getName() + " reached " + type.displayName() + " VL " + Math.round(vl)
                    + " and is now watched silently. Nothing happens to them yet; a ban needs confirmation in "
                    + settings.banMinSessions + " separate sessions.");
        }
    }

    // ---------------------------------------------------------------- queue

    public void queue(UUID uuid, String name, MonitorVerdict verdict, String reportFile) {
        if (pending.containsKey(uuid)) {
            return;
        }
        Settings settings = plugin.settings();
        long now = System.currentTimeMillis();
        long delay = settings.banDelayMinMs
            + (settings.banDelayMaxMs > settings.banDelayMinMs
            ? ThreadLocalRandom.current().nextLong(settings.banDelayMaxMs - settings.banDelayMinMs) : 0);
        String reason = verdict.reasons().isEmpty() ? "confirmed cheating" : String.join("; ", verdict.reasons());
        Pending entry = new Pending(uuid, name, reason, reportFile, now, now + delay);
        pending.put(uuid, entry);
        save();
        historyLine("QUEUED " + name + " for " + DATE.format(Instant.ofEpochMilli(entry.executeMs)) + ": " + reason);
        staffMessage("[NovaGuard] " + name + " is confirmed and queued for the ban wave on "
            + DATE.format(Instant.ofEpochMilli(entry.executeMs)) + ". Cancel: /ng bans cancel " + name);
        if (settings.notifyQueued) {
            plugin.notifier().send("NovaGuard: " + name + " queued for a ban",
                name + " was confirmed as cheating and will be banned on "
                    + DATE.format(Instant.ofEpochMilli(entry.executeMs)) + " (server time).\n"
                    + "Evidence: " + reason + "\n"
                    + "Full report: plugins/NovaGuard/reports/" + reportFile + "\n"
                    + banScopeText(uuid)
                    + "To stop it, type in the console: ng bans cancel " + name);
        }
    }

    public boolean cancel(String name) {
        Pending entry = find(name);
        if (entry == null) {
            return false;
        }
        pending.remove(entry.uuid);
        save();
        historyLine("CANCELLED " + entry.name);
        return true;
    }

    public boolean executeNow(String name) {
        Pending entry = find(name);
        if (entry == null) {
            return false;
        }
        execute(entry);
        return true;
    }

    public Collection<Pending> pending() {
        return pending.values();
    }

    private Pending find(String name) {
        for (Pending entry : pending.values()) {
            if (entry.name.equalsIgnoreCase(name)) {
                return entry;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- the wave

    /** Once a minute: run every ban whose time has come. */
    @Override
    public void run() {
        long now = System.currentTimeMillis();
        for (Pending entry : new ArrayList<>(pending.values())) {
            if (now >= entry.executeMs) {
                execute(entry);
            }
        }
    }

    private void execute(Pending entry) {
        Settings settings = plugin.settings();
        pending.remove(entry.uuid);
        save();
        Duration duration = settings.banDurationDays > 0 ? Duration.ofDays(settings.banDurationDays) : null;
        String reason = settings.banReason;
        List<String> done = new ArrayList<>();

        banAccount(entry.uuid, reason, duration);
        done.add("account " + entry.name);

        AltTracker alts = plugin.alts();
        if (settings.banIp) {
            for (String ip : alts.ipsOf(entry.uuid)) {
                if (alts.usedByBedrock(ip)) {
                    // A Bedrock player shares this IP: Bedrock players are never banned automatically.
                    done.add("IP " + ip + " skipped (also used by a Bedrock account)");
                    continue;
                }
                try {
                    InetAddress address = InetAddress.getByName(ip);
                    if (!AltTracker.isPublic(address)) {
                        continue;
                    }
                    Bukkit.getBanList(BanListType.IP).addBan(address, reason, duration, "NovaGuard");
                    kickAddress(address, reason);
                    done.add("IP " + ip);
                } catch (UnknownHostException e) {
                    plugin.getLogger().warning("Could not ban IP " + ip + ": " + e.getMessage());
                }
            }
        }
        if (settings.banAlts) {
            for (UUID alt : alts.altsOf(entry.uuid)) {
                if (BedrockDetector.isFloodgateUuid(alt)) {
                    continue; // Bedrock accounts are never banned automatically.
                }
                banAccount(alt, reason + " (alt account of " + entry.name + ")", duration);
                done.add("alt " + alts.nameOf(alt));
            }
        }

        String summary = entry.name + " banned " + (duration == null ? "permanently" : "for "
            + settings.banDurationDays + " days") + ": " + String.join(", ", done);
        historyLine("BANNED " + summary + " | evidence: " + entry.reason);
        plugin.getLogger().warning("Ban wave: " + summary);
        staffMessage("[NovaGuard] Ban wave: " + summary + ". Report: plugins/NovaGuard/reports/" + entry.report);
        if (settings.notifyBanned) {
            plugin.notifier().send("NovaGuard: " + entry.name + " banned", summary + "\nEvidence: " + entry.reason
                + "\nUndo: pardon " + entry.name + (settings.banIp ? " and pardon-ip <address>" : ""));
        }
    }

    private void banAccount(UUID uuid, String reason, Duration duration) {
        Bukkit.getOfflinePlayer(uuid).ban(reason, duration, "NovaGuard");
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            online.kick(Component.text("You are banned: " + reason, NamedTextColor.RED), PlayerKickEvent.Cause.BANNED);
        }
    }

    private void kickAddress(InetAddress address, String reason) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            InetSocketAddress socket = player.getAddress();
            if (socket != null && address.equals(socket.getAddress())) {
                player.kick(Component.text("You are banned: " + reason, NamedTextColor.RED), PlayerKickEvent.Cause.IP_BANNED);
            }
        }
    }

    /** Tells staff in the notification exactly who else would be hit, so a shared Wi-Fi can be noticed. */
    private String banScopeText(UUID uuid) {
        Settings settings = plugin.settings();
        AltTracker alts = plugin.alts();
        StringBuilder text = new StringBuilder("The ban will cover: this account");
        if (settings.banIp && !alts.ipsOf(uuid).isEmpty()) {
            text.append(", ").append(alts.ipsOf(uuid).size()).append(" IP address(es)");
        }
        if (settings.banAlts && !alts.altsOf(uuid).isEmpty()) {
            List<String> names = new ArrayList<>();
            alts.altsOf(uuid).stream().filter(alt -> !BedrockDetector.isFloodgateUuid(alt))
                .forEach(alt -> names.add(alts.nameOf(alt)));
            text.append(", linked accounts: ").append(String.join(", ", names))
                .append(" (same IP: could be family on the same Wi-Fi!)");
        }
        return text.append(".\n").toString();
    }

    private void staffMessage(String text) {
        Component message = Component.text(text, NamedTextColor.GOLD);
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
            ConfigurationSection s = yaml.getConfigurationSection(key);
            if (s == null) {
                continue;
            }
            try {
                UUID uuid = UUID.fromString(key);
                pending.put(uuid, new Pending(uuid, s.getString("name", key), s.getString("reason", ""),
                    s.getString("report", ""), s.getLong("queued"), s.getLong("execute")));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Skipping bad entry in bans.yml: " + key);
            }
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Pending entry : pending.values()) {
            ConfigurationSection s = yaml.createSection(entry.uuid.toString());
            s.set("name", entry.name);
            s.set("reason", entry.reason);
            s.set("report", entry.report);
            s.set("queued", entry.queuedMs);
            s.set("execute", entry.executeMs);
        }
        try {
            Files.createDirectories(file.getParentFile().toPath());
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save bans.yml: " + e.getMessage());
        }
    }

    private void historyLine(String text) {
        String line = "[" + DATE.format(Instant.now()) + "] " + text + System.lineSeparator();
        try {
            Files.createDirectories(history.getParentFile().toPath());
            Files.writeString(history.toPath(), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not write bans-history.log: " + e.getMessage());
        }
    }

    public static String time(long epochMs) {
        return DATE.format(Instant.ofEpochMilli(epochMs));
    }
}
