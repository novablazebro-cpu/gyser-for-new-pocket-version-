package io.github.novablaze.novaguard.ban;

import io.github.novablaze.novaguard.CheckType;
import io.github.novablaze.novaguard.NovaGuardPlugin;
import io.github.novablaze.novaguard.Settings;
import io.github.novablaze.novaguard.core.AltTracker;
import io.github.novablaze.novaguard.core.BedrockDetector;
import io.github.novablaze.novaguard.core.PlayerData;
import io.github.novablaze.novaguard.math.MonitorVerdict;
import io.github.novablaze.novaguard.math.PunishmentLadder;
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
import org.bukkit.ban.ProfileBanList;
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
    public static final String KIND_CHEATING = "cheating";

    /** One player waiting for the next ban wave. */
    public static final class Pending {
        public final UUID uuid;
        public final String name;
        public final String reason;
        public final String report;
        public final long queuedMs;
        public final long executeMs;
        /** "cheating" (ban wave) or "dupe/illegal items". */
        public final String kind;
        /** Waits for an admin (long-time player): never runs by itself. */
        public final boolean review;

        Pending(UUID uuid, String name, String reason, String report, long queuedMs, long executeMs, String kind,
                boolean review) {
            this.uuid = uuid;
            this.name = name;
            this.reason = reason;
            this.report = report;
            this.queuedMs = queuedMs;
            this.executeMs = executeMs;
            this.kind = kind;
            this.review = review;
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
        /** Length of the account ban (and of the IP ban if approved); 0 = permanent. */
        public final long days;
        /** 2 = the player's 2nd NovaGuard ban, etc. */
        public final int banNumber;

        Approval(UUID uuid, String name, String reason, long requestedMs, List<String> ips, List<UUID> alts,
                 long days, int banNumber) {
            this.uuid = uuid;
            this.name = name;
            this.reason = reason;
            this.requestedMs = requestedMs;
            this.ips = ips;
            this.alts = alts;
            this.days = days;
            this.banNumber = banNumber;
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
        Pending entry = new Pending(uuid, name, reason, reportFile, now, now + delay, KIND_CHEATING, false);
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
            if (!entry.review && now >= entry.executeMs) {
                execute(entry);
            }
        }
        expireApprovals(now);
        if (now - lastReminderMs >= REMIND_MS && (!approvals.isEmpty() || hasReviews())) {
            lastReminderMs = now;
            approvals.values().forEach(this::redAlert);
            pending.values().stream().filter(p -> p.review).forEach(this::reviewAlert);
        }
    }

    private void execute(Pending entry) {
        pending.remove(entry.uuid);
        save();
        boolean cheating = KIND_CHEATING.equals(entry.kind);
        executeBan(entry.uuid, entry.name, entry.reason, entry.report, entry.kind,
            cheating ? plugin.settings().banReason : plugin.settings().dupeBanReason);
    }

    private boolean hasReviews() {
        for (Pending entry : pending.values()) {
            if (entry.review) {
                return true;
            }
        }
        return false;
    }

    /** Long-time players are not banned by a machine alone: an admin confirms. */
    private void reviewAlert(Pending entry) {
        String[] lines = {
            "================= NOVAGUARD: BAN NEEDS YOUR DECISION =================",
            entry.name + " has played for " + playtimeHours(entry.uuid) + " hours, so they are not banned automatically.",
            "Evidence: " + entry.reason,
            entry.report.isEmpty() ? "" : "Report: plugins/NovaGuard/reports/" + entry.report,
            "Ban (" + describeNextBan(entry.uuid) + "): ng bans now " + entry.name
                + "    Do not ban: ng bans cancel " + entry.name,
            "======================================================================"};
        for (String line : lines) {
            if (!line.isEmpty()) {
                plugin.getLogger().severe(line);
            }
        }
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("novaguard.admin")) {
                for (String line : lines) {
                    if (!line.isEmpty()) {
                        staff.sendMessage(Component.text(line, NamedTextColor.RED));
                    }
                }
            }
        }
    }

    /** Hours on this server from the vanilla statistic (0 when unknown). */
    public static long playtimeHours(UUID uuid) {
        try {
            return Bukkit.getOfflinePlayer(uuid).getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE) / 72_000L;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * Bans a player now for dupes / illegal items (the player was warned before, so no hidden ban wave).
     * Runs on the next tick: the caller may be in the middle of scanning this player's inventory.
     */
    public void banNow(UUID uuid, String name, String evidence, String report, String kind) {
        Bukkit.getScheduler().runTask(plugin, () -> plugin.safety().run("ban-waves", () -> {
            Pending queued = pending.remove(uuid);
            if (queued != null) {
                save();
                historyLine("QUEUED ban of " + name + " replaced by an immediate ban (" + kind + ")");
            }
            long reviewHours = plugin.settings().reviewAboveHours;
            if (reviewHours > 0 && playtimeHours(uuid) >= reviewHours) {
                long now = System.currentTimeMillis();
                Pending review = new Pending(uuid, name, evidence, report == null ? "" : report, now, Long.MAX_VALUE,
                    kind, true);
                pending.put(uuid, review);
                save();
                historyLine("REVIEW NEEDED for " + name + " (" + playtimeHours(uuid) + " h played): " + evidence);
                reviewAlert(review);
                lastReminderMs = now;
                plugin.notifier().send("NovaGuard: ban of " + name + " needs your decision",
                    name + " (long-time player) would be banned for: " + evidence
                        + "\nBan: ng bans now " + name + "   Do not ban: ng bans cancel " + name);
                return;
            }
            executeBan(uuid, name, evidence, report == null ? "" : report, kind, plugin.settings().dupeBanReason);
        }));
    }

    /** "1st ban, 3 days" for the next ban of this player (used in reports). */
    public String describeNextBan(UUID uuid) {
        Settings settings = plugin.settings();
        int previous = plugin.offenses().banCount(uuid, settings.rememberBansDays, System.currentTimeMillis());
        long days = PunishmentLadder.banDays(settings.banDays, previous);
        return PunishmentLadder.ordinal(previous + 1) + " ban, " + (days == 0 ? "permanent" : days + " days");
    }

    /**
     * One ban. Its length follows the ladder (3, 10, 30 days by default) by how often NovaGuard banned this
     * player before. From the configured ban number on, an admin is asked whether the IP should be banned too.
     */
    private void executeBan(UUID uuid, String name, String evidence, String report, String kind, String publicReason) {
        Settings settings = plugin.settings();
        long now = System.currentTimeMillis();
        int previous = plugin.offenses().banCount(uuid, settings.rememberBansDays, now);
        int banNumber = previous + 1;
        long days = PunishmentLadder.banDays(settings.banDays, previous);
        Duration duration = days > 0 ? Duration.ofDays(days) : null;
        String length = days > 0 ? days + " days" : "permanently";
        String reason = publicReason + (days > 0 ? " - " + days + " days" : "");
        List<String> done = new ArrayList<>();

        banAccount(uuid, reason, duration);
        plugin.offenses().recordBan(uuid, name, now, days, kind);
        plugin.offenses().clearStrikes(uuid);
        done.add("account " + name);

        boolean askIp = settings.banIp && PunishmentLadder.asksForIpBan(banNumber, settings.ipBanFromBan);
        List<String> ips = askIp ? banIps(uuid, done) : List.of();
        List<UUID> altAccounts = askIp && settings.banAlts ? banAlts(uuid) : List.of();
        if (settings.ipBanApproval && (!ips.isEmpty() || !altAccounts.isEmpty())) {
            // An IP ban can hit innocent people on the same Wi-Fi: only an admin may approve it.
            Approval approval = new Approval(uuid, name, evidence, now, ips, altAccounts, days, banNumber);
            approvals.put(uuid, approval);
            saveApprovals();
            done.add("IP/alt ban WAITING FOR ADMIN APPROVAL (/ng approve " + name + ")");
            historyLine("APPROVAL NEEDED " + name + ": " + scope(approval));
            redAlert(approval);
            lastReminderMs = now;
        } else {
            applyIpAndAlts(uuid, name, ips, altAccounts, reason, duration, done);
        }

        String summary = name + " banned " + (days > 0 ? "for " + length : length) + " ("
            + PunishmentLadder.ordinal(banNumber) + " NovaGuard ban, " + kind + "): " + String.join(", ", done);
        historyLine("BANNED " + summary + " | evidence: " + evidence);
        plugin.getLogger().warning("Ban: " + summary);
        staffMessage("[NovaGuard] Ban: " + summary + (report.isEmpty() ? "" : ". Report: plugins/NovaGuard/reports/" + report));
        if (settings.notifyBanned) {
            plugin.notifier().send("NovaGuard: " + name + " banned", summary + "\nEvidence: " + evidence
                + (report.isEmpty() ? "" : "\nReport: plugins/NovaGuard/reports/" + report)
                + "\nUndo: pardon " + name
                + (approvals.containsKey(uuid) ? "\nThe IP ban waits for you: type 'ng approve "
                + name + "' or 'ng deny " + name + "' in the console." : ""));
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

    private void applyIpAndAlts(UUID owner, String name, List<String> ips, List<UUID> altAccounts, String reason,
                                Duration duration, List<String> done) {
        for (String ip : ips) {
            try {
                InetAddress address = InetAddress.getByName(ip);
                Bukkit.getBanList(BanListType.IP).addBan(address, reason, duration, "NovaGuard");
                plugin.offenses().recordLinked(owner, "ip:" + ip);
                kickAddress(address, reason);
                done.add("IP " + ip);
            } catch (UnknownHostException e) {
                plugin.getLogger().warning("Could not ban IP " + ip + ": " + e.getMessage());
            }
        }
        for (UUID alt : altAccounts) {
            banAccount(alt, reason + " (alt account of " + name + ")", duration);
            plugin.offenses().recordLinked(owner, "alt:" + alt);
            done.add("alt " + plugin.alts().nameOf(alt));
        }
    }

    // ---------------------------------------------------------------- undo

    /**
     * Undoes a wrong ban completely: the account, every IP and alt banned with it, the waiting IP question and
     * queued bans are lifted, and the ban is taken out of the history so the next ban is not longer because of it.
     *
     * @return what was undone, for the admin
     */
    public String unban(UUID uuid, String name, String admin) {
        List<String> done = new ArrayList<>();
        pardonAccount(uuid, name);
        done.add("account " + name);
        List<String> linked = plugin.offenses().removeLastBan(uuid);
        for (String entry : linked == null ? List.<String>of() : linked) {
            if (entry.startsWith("ip:")) {
                String ip = entry.substring(3);
                try {
                    Bukkit.getBanList(BanListType.IP).pardon(InetAddress.getByName(ip));
                    done.add("IP " + ip);
                } catch (UnknownHostException e) {
                    plugin.getLogger().warning("Could not unban IP " + ip + ": " + e.getMessage());
                }
            } else if (entry.startsWith("alt:")) {
                try {
                    UUID alt = UUID.fromString(entry.substring(4));
                    pardonAccount(alt, plugin.alts().nameOf(alt));
                    done.add("alt " + plugin.alts().nameOf(alt));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Bad alt entry in offenses.yml: " + entry);
                }
            }
        }
        if (pending.remove(uuid) != null) {
            save();
            done.add("queued ban cancelled");
        }
        if (approvals.remove(uuid) != null) {
            saveApprovals();
            done.add("IP-ban question removed");
        }
        plugin.monitor().pardonItems(uuid);
        String summary = String.join(", ", done) + (linked == null ? " (no NovaGuard ban was on record)"
            : "; the ban was removed from the history, so the next ban starts lower again");
        historyLine("UNBANNED by " + admin + ": " + name + ": " + summary);
        plugin.getLogger().warning("Unban by " + admin + ": " + name + ": " + summary);
        return summary;
    }

    /** Lifts an account ban by UUID (BanList.pardon, as the Paper docs recommend). */
    public static void pardonAccount(UUID uuid, String name) {
        ProfileBanList profiles = Bukkit.getBanList(BanListType.PROFILE);
        profiles.pardon(Bukkit.createProfile(uuid, name));
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
        Duration duration = approval.days > 0 ? Duration.ofDays(approval.days) : null;
        List<String> done = new ArrayList<>();
        applyIpAndAlts(approval.uuid, approval.name, approval.ips, approval.alts, settings.banReason
            + (approval.days > 0 ? " - " + approval.days + " days" : ""), duration, done);
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
        boolean changed = approvals.values().removeIf(approval -> {
            boolean old = approval.days > 0 && now - approval.requestedMs >= approval.days * DAY_MS;
            if (old) {
                historyLine("EXPIRED IP approval for " + approval.name + " (nobody approved it in "
                    + approval.days + " days)");
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
        int before = approval.banNumber - 1;
        String[] lines = {
            "=================== NOVAGUARD: IP BAN NEEDS APPROVAL ===================",
            before > 0
                ? approval.name + " keeps cheating/duping after being banned " + before + " time(s) before. This is"
                    + " their " + PunishmentLadder.ordinal(approval.banNumber) + " ban ("
                    + (approval.days > 0 ? approval.days + " days" : "permanent") + ", their own account is banned)."
                : approval.name + " is banned (their own account).",
            "Should their IP be banned too?",
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
        int banNumber = plugin.offenses().banCount(uuid, settings.rememberBansDays, System.currentTimeMillis()) + 1;
        boolean askIp = settings.banIp && PunishmentLadder.asksForIpBan(banNumber, settings.ipBanFromBan);
        StringBuilder text = new StringBuilder("The ban will cover: this account (" + describeNextBan(uuid) + ")");
        if (askIp && !alts.ipsOf(uuid).isEmpty()) {
            text.append(", ").append(alts.ipsOf(uuid).size()).append(" IP address(es)");
            if (settings.ipBanApproval) {
                text.append(" (only after an admin approves it)");
            }
        }
        // An IP ban locks out every account on that IP, so list them even when alts are not banned by name.
        if (askIp && (settings.banAlts || settings.banIp) && !alts.altsOf(uuid).isEmpty()) {
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
                    s.getString("report", ""), s.getLong("queued"), s.getLong("execute"),
                    s.getString("kind", KIND_CHEATING), s.getBoolean("review")));
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
            s.set("kind", entry.kind);
            s.set("review", entry.review);
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
                    s.getLong("requested"), new ArrayList<>(s.getStringList("ips")), altList,
                    s.getLong("days", 30), s.getInt("ban-number", 1)));
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
            s.set("days", approval.days);
            s.set("ban-number", approval.banNumber);
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
