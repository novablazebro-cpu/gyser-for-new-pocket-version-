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
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;

/**
 * Ban waves: suspects are watched silently, confirmed cheaters are queued, and the ban happens later at a
 * random time, so a cheater cannot tell which action got them caught. Staff are notified and can cancel.
 */
public final class BanManager implements Runnable, Listener {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault());
    private static final long DAY_MS = 86_400_000L;
    /** How often the red console alert repeats while an IP ban waits for approval. */
    private static final long REMIND_MS = 30 * 60_000L;

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

    /**
     * An IP / alt ban that waits for an admin. It holds exactly what the admin was shown, so approving
     * bans those addresses and accounts and nothing that was added later.
     */
    public static final class Approval {
        public final UUID uuid;
        public final String name;
        public final String reason;
        public final long requestedMs;
        public final List<String> ips;
        public final List<UUID> alts;

        Approval(UUID uuid, String name, String reason, long requestedMs, List<String> ips, List<UUID> alts) {
            this.uuid = uuid;
            this.name = name;
            this.reason = reason;
            this.requestedMs = requestedMs;
            this.ips = ips;
            this.alts = alts;
        }
    }

    private final NovaGuardPlugin plugin;
    private final File file;
    private final File approvalsFile;
    private final Map<UUID, Approval> approvals = new LinkedHashMap<>();
    private long lastReminderMs;
    private final File history;
    private final Map<UUID, Pending> pending = new LinkedHashMap<>();
    /** Last time a "new suspect" notification was sent per player, so staff are not spammed. */
    private final Map<UUID, Long> suspectNotified = new HashMap<>();

    public BanManager(NovaGuardPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "bans.yml");
        this.history = new File(plugin.getDataFolder(), "bans-history.log");
        this.approvalsFile = new File(plugin.getDataFolder(), "ip-approvals.yml");
        load();
        loadApprovals();
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
        expireApprovals(now);
        if (!approvals.isEmpty() && now - lastReminderMs >= REMIND_MS) {
            lastReminderMs = now;
            approvals.values().forEach(this::redAlert);
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

        List<String> ips = settings.banIp ? banIps(entry.uuid, done) : List.of();
        List<UUID> altAccounts = settings.banAlts ? banAlts(entry.uuid) : List.of();
        if (settings.ipBanApproval && (!ips.isEmpty() || !altAccounts.isEmpty())) {
            // An IP ban can hit innocent people on the same Wi-Fi: only an admin may approve it.
            Approval approval = new Approval(entry.uuid, entry.name, entry.reason, System.currentTimeMillis(),
                ips, altAccounts);
            approvals.put(entry.uuid, approval);
            saveApprovals();
            done.add("IP/alt ban WAITING FOR ADMIN APPROVAL (/ng approve " + entry.name + ")");
            historyLine("APPROVAL NEEDED " + entry.name + ": " + scope(approval));
            redAlert(approval);
            lastReminderMs = System.currentTimeMillis();
        } else {
            applyIpAndAlts(entry.name, ips, altAccounts, reason, duration, done);
        }

        String summary = entry.name + " banned " + (duration == null ? "permanently" : "for "
            + settings.banDurationDays + " days") + ": " + String.join(", ", done);
        historyLine("BANNED " + summary + " | evidence: " + entry.reason);
        plugin.getLogger().warning("Ban wave: " + summary);
        staffMessage("[NovaGuard] Ban wave: " + summary + ". Report: plugins/NovaGuard/reports/" + entry.report);
        if (settings.notifyBanned) {
            plugin.notifier().send("NovaGuard: " + entry.name + " banned", summary + "\nEvidence: " + entry.reason
                + "\nUndo: pardon " + entry.name + (settings.banIp ? " and pardon-ip <address>" : "")
                + (approvals.containsKey(entry.uuid) ? "\nThe IP ban waits for you: type 'ng approve "
                + entry.name + "' or 'ng deny " + entry.name + "' in the console." : ""));
        }
    }

    /** The cheater's public IPs that may be banned. Skipped ones are explained in {@code done}. */
    private List<String> banIps(UUID uuid, List<String> done) {
        AltTracker alts = plugin.alts();
        List<String> out = new ArrayList<>();
        for (String ip : alts.ipsOf(uuid)) {
            if (alts.usedByBedrock(ip)) {
                // A Bedrock player shares this IP: Bedrock players are never banned automatically.
                done.add("IP " + ip + " skipped (also used by a Bedrock account)");
                continue;
            }
            try {
                if (AltTracker.isPublic(InetAddress.getByName(ip))) {
                    out.add(ip);
                }
            } catch (UnknownHostException e) {
                plugin.getLogger().warning("Bad IP in alts.yml: " + ip);
            }
        }
        return out;
    }

    /** Java accounts linked to the cheater by IP (Bedrock accounts are never banned automatically). */
    private List<UUID> banAlts(UUID uuid) {
        List<UUID> out = new ArrayList<>();
        for (UUID alt : plugin.alts().altsOf(uuid)) {
            if (!BedrockDetector.isFloodgateUuid(alt)) {
                out.add(alt);
            }
        }
        return out;
    }

    private void applyIpAndAlts(String name, List<String> ips, List<UUID> altAccounts, String reason,
                                Duration duration, List<String> done) {
        for (String ip : ips) {
            try {
                InetAddress address = InetAddress.getByName(ip);
                Bukkit.getBanList(BanListType.IP).addBan(address, reason, duration, "NovaGuard");
                kickAddress(address, reason);
                done.add("IP " + ip);
            } catch (UnknownHostException e) {
                plugin.getLogger().warning("Could not ban IP " + ip + ": " + e.getMessage());
            }
        }
        for (UUID alt : altAccounts) {
            banAccount(alt, reason + " (alt account of " + name + ")", duration);
            done.add("alt " + plugin.alts().nameOf(alt));
        }
    }

    // ---------------------------------------------------------------- IP ban approval

    /** Admin said yes: ban exactly the IPs and alts that were shown in the alert. */
    public String approve(String name, String admin) {
        Approval approval = findApproval(name);
        if (approval == null) {
            return null;
        }
        approvals.remove(approval.uuid);
        saveApprovals();
        Settings settings = plugin.settings();
        Duration duration = settings.banDurationDays > 0 ? Duration.ofDays(settings.banDurationDays) : null;
        List<String> done = new ArrayList<>();
        applyIpAndAlts(approval.name, approval.ips, approval.alts, settings.banReason, duration, done);
        String summary = String.join(", ", done);
        historyLine("APPROVED by " + admin + " for " + approval.name + ": " + summary);
        plugin.getLogger().warning("IP ban approved by " + admin + " for " + approval.name + ": " + summary);
        return summary.isEmpty() ? "nothing left to ban" : summary;
    }

    /** Admin said no: only the cheater's own account stays banned. */
    public boolean deny(String name, String admin) {
        Approval approval = findApproval(name);
        if (approval == null) {
            return false;
        }
        approvals.remove(approval.uuid);
        saveApprovals();
        historyLine("DENIED by " + admin + " for " + approval.name + " (only the account stays banned)");
        return true;
    }

    public Collection<Approval> approvals() {
        return approvals.values();
    }

    private Approval findApproval(String name) {
        for (Approval approval : approvals.values()) {
            if (approval.name.equalsIgnoreCase(name)) {
                return approval;
            }
        }
        return null;
    }

    /** Once the account ban is over, an IP ban for it makes no sense any more. */
    private void expireApprovals(long now) {
        long days = plugin.settings().banDurationDays;
        if (days <= 0) {
            return;
        }
        boolean changed = approvals.values().removeIf(approval -> {
            boolean old = now - approval.requestedMs >= days * DAY_MS;
            if (old) {
                historyLine("EXPIRED IP approval for " + approval.name + " (nobody approved it in " + days + " days)");
            }
            return old;
        });
        if (changed) {
            saveApprovals();
        }
    }

    /** What an approval would ban, written so an admin can spot a shared Wi-Fi. */
    public String scope(Approval approval) {
        List<String> names = new ArrayList<>();
        approval.alts.forEach(alt -> names.add(plugin.alts().nameOf(alt)));
        return approval.ips.size() + " IP address(es) " + approval.ips
            + (names.isEmpty() ? ", no other accounts" : ", other accounts: " + String.join(", ", names));
    }

    /** Red lines in the console (SEVERE log level) and for online admins. */
    private void redAlert(Approval approval) {
        String[] lines = {
            "=================== NOVAGUARD: IP BAN NEEDS APPROVAL ===================",
            approval.name + " is banned (their own account). Should their IP be banned too?",
            "It would ban: " + scope(approval),
            "Evidence: " + approval.reason,
            "Check first: are the other accounts family or friends on the same Wi-Fi?",
            "Approve: ng approve " + approval.name + "    Refuse: ng deny " + approval.name,
            "========================================================================"};
        for (String line : lines) {
            plugin.getLogger().severe(line);
        }
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("novaguard.admin")) {
                for (String line : lines) {
                    staff.sendMessage(Component.text(line, NamedTextColor.RED));
                }
            }
        }
    }

    /** Admins who join see waiting approvals straight away. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!approvals.isEmpty() && player.hasPermission("novaguard.admin")) {
            player.sendMessage(Component.text("[NovaGuard] " + approvals.size()
                + " IP ban(s) wait for your approval. Type /ng bans to see them.", NamedTextColor.RED));
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
            if (settings.ipBanApproval) {
                text.append(" (only after an admin approves it)");
            }
        }
        // An IP ban locks out every account on that IP, so list them even when alts are not banned by name.
        if ((settings.banAlts || settings.banIp) && !alts.altsOf(uuid).isEmpty()) {
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

    private void loadApprovals() {
        if (!approvalsFile.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(approvalsFile);
        for (String key : yaml.getKeys(false)) {
            ConfigurationSection s = yaml.getConfigurationSection(key);
            if (s == null) {
                continue;
            }
            try {
                UUID uuid = UUID.fromString(key);
                List<UUID> altList = new ArrayList<>();
                for (String alt : s.getStringList("alts")) {
                    altList.add(UUID.fromString(alt));
                }
                approvals.put(uuid, new Approval(uuid, s.getString("name", key), s.getString("reason", ""),
                    s.getLong("requested"), new ArrayList<>(s.getStringList("ips")), altList));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Skipping bad entry in ip-approvals.yml: " + key);
            }
        }
    }

    public void saveApprovals() {
        if (approvals.isEmpty()) {
            try {
                Files.deleteIfExists(approvalsFile.toPath());
            } catch (IOException e) {
                plugin.getLogger().warning("Could not delete ip-approvals.yml: " + e.getMessage());
            }
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        for (Approval approval : approvals.values()) {
            ConfigurationSection s = yaml.createSection(approval.uuid.toString());
            s.set("name", approval.name);
            s.set("reason", approval.reason);
            s.set("requested", approval.requestedMs);
            s.set("ips", approval.ips);
            s.set("alts", approval.alts.stream().map(UUID::toString).toList());
        }
        try {
            Files.createDirectories(approvalsFile.getParentFile().toPath());
            yaml.save(approvalsFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save ip-approvals.yml: " + e.getMessage());
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
