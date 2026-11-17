package io.github.novablaze.novaguard.evidence;

import io.github.novablaze.novaguard.NovaGuardPlugin;
import io.github.novablaze.novaguard.evidence.IllegalItemInspector.Finding;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

/**
 * /ng scanplayers: reads the save file of every player who ever joined (also players who left long ago) and writes
 * each illegal item in their inventory or ender chest, with where they were, to
 * plugins/NovaGuard/illegal-enchantments-report.txt. Players online right now are checked live instead.
 *
 * <p>It only reads: no save file and no item is ever changed, nobody is warned or banned. Files are read on a
 * background thread; checking the items runs on the main thread a few milliseconds per tick, so it cannot lag the
 * server. The illegal-item rules are the same as everywhere else in NovaGuard ({@link ItemSanitizer}).
 */
public final class PlayerScanner {

    private static final long BUDGET_NANOS = 4_000_000L;
    private static final int MAX_CHAT_LINES = 20;
    private static final Pattern PLAYER_FILE =
        Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.dat$");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault());

    private final class Job {
        final CommandSender sender;
        final String startedBy;
        final long startedMs = System.currentTimeMillis();
        final BlockingQueue<PlayerFileItems.Parsed> results = new ArrayBlockingQueue<>(64);
        final List<String> chat = new ArrayList<>();
        volatile boolean cancelled;
        volatile boolean readerDone;
        final int files;
        int filesDone;
        int unreadableFiles;
        int unreadableItems;
        int playersWithItems;
        int itemsFound;

        Job(CommandSender sender, int files) {
            this.sender = sender;
            this.startedBy = sender.getName();
            this.files = files;
        }
    }

    private final NovaGuardPlugin plugin;
    private final ReportFile report;
    private final ExecutorService reader = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "NovaGuard-player-scan");
        thread.setDaemon(true);
        return thread;
    });
    private Job job;
    private BukkitTask task;

    public PlayerScanner(NovaGuardPlugin plugin, ReportFile report) {
        this.plugin = plugin;
        this.report = report;
    }

    public ReportFile report() {
        return report;
    }

    // ---------------------------------------------------------------- the command

    public void command(CommandSender sender, String[] args) {
        String arg = args.length >= 2 ? args[1] : "";
        if (arg.equalsIgnoreCase("cancel")) {
            if (job == null) {
                reply(sender, "No player scan is running.", NamedTextColor.YELLOW);
            } else {
                job.cancelled = true;
                finish(job);
            }
            return;
        }
        if (arg.equalsIgnoreCase("status")) {
            reply(sender, job == null ? "No player scan is running."
                : "Player scan: " + job.filesDone + "/" + job.files + " files, " + job.itemsFound
                    + " illegal item(s) found so far.", NamedTextColor.AQUA);
            return;
        }
        if (job != null) {
            reply(sender, "A player scan is already running (/ng scanplayers status | cancel).", NamedTextColor.YELLOW);
            return;
        }
        UUID only = null;
        if (!arg.isEmpty()) {
            OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(arg);
            if (target == null) {
                reply(sender, arg + " has never joined this server.", NamedTextColor.RED);
                return;
            }
            only = target.getUniqueId();
        }
        start(sender, only);
    }

    private void start(CommandSender sender, UUID only) {
        Path folder = playerDataFolder();
        Set<UUID> online = new HashSet<>();
        List<Player> liveTargets = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            online.add(player.getUniqueId());
            if (only == null || only.equals(player.getUniqueId())) {
                liveTargets.add(player);
            }
        }
        List<Path> files = new ArrayList<>();
        if (folder != null) {
            if (only != null) {
                Path file = folder.resolve(only + ".dat");
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !online.contains(only)) {
                    files.add(file);
                }
            } else {
                try (Stream<Path> list = Files.list(folder)) {
                    list.filter(p -> PLAYER_FILE.matcher(p.getFileName().toString()).matches())
                        .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                        .filter(p -> !online.contains(PlayerFileItems.uuidOf(p)))
                        .forEach(files::add);
                } catch (IOException e) {
                    reply(sender, "Could not list the player save folder: " + e.getMessage(), NamedTextColor.RED);
                    return;
                }
            }
        }
        Job current = new Job(sender, files.size());
        job = current;
        report.append(List.of("", "=== Player scan started " + TIME.format(Instant.now()) + " by "
            + ReportFile.clean(current.startedBy) + " (" + (only == null ? "all players" : "one player") + ", "
            + files.size() + " save file(s), " + liveTargets.size() + " online) ===",
            "PROOF = the item is in this player's own inventory or ender chest. Positions: world x y z."));
        reply(sender, "Scanning " + files.size() + " player save file(s) and " + liveTargets.size()
            + " online player(s). Nothing is changed. Report: plugins/NovaGuard/" + ReportFile.NAME,
            NamedTextColor.YELLOW);
        if (folder == null) {
            plugin.getLogger().warning("Player scan: could not find the player save folder next to the main world;"
                + " only online players are checked.");
        }

        // Online players: their save file is out of date, so look at the real inventory now.
        for (Player player : liveTargets) {
            plugin.safety().run("player-scan", () -> scanOnline(current, player));
        }

        reader.execute(() -> readFiles(current, files));
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> plugin.safety().run("player-scan", this::tick), 1L, 1L);
    }

    // ---------------------------------------------------------------- background thread: read files

    private void readFiles(Job current, List<Path> files) {
        try {
            for (Path file : files) {
                if (current.cancelled) {
                    return;
                }
                PlayerFileItems.Parsed parsed = PlayerFileItems.parse(file);
                while (!current.cancelled && !current.results.offer(parsed, 200, TimeUnit.MILLISECONDS)) {
                    // The main thread is behind: wait instead of filling the memory.
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            current.readerDone = true;
        }
    }

    // ---------------------------------------------------------------- main thread: check items

    private void tick() {
        Job current = job;
        if (current == null) {
            stopTask();
            return;
        }
        long start = System.nanoTime();
        while (System.nanoTime() - start < BUDGET_NANOS) {
            PlayerFileItems.Parsed parsed = current.results.poll();
            if (parsed == null) {
                if (current.readerDone && current.results.isEmpty()) {
                    finish(current);
                }
                return;
            }
            current.filesDone++;
            check(current, parsed);
        }
    }

    private void check(Job current, PlayerFileItems.Parsed parsed) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(parsed.uuid());
        if (parsed.error() != null) {
            current.unreadableFiles++;
            report.append(List.of("UNREADABLE " + name(player) + " (" + parsed.uuid() + "): "
                + ReportFile.clean(parsed.error())));
            return;
        }
        if (player.isOnline()) {
            return; // Joined while the scan ran: the save file is old now, and the live checks see them.
        }
        List<Finding> findings = new ArrayList<>();
        for (PlayerFileItems.FileItem fileItem : parsed.items()) {
            try {
                Finding finding = plugin.inspector().inspect(fileItem.where(),
                    ItemStack.deserializeBytes(fileItem.paperBytes()));
                if (finding != null) {
                    findings.add(finding);
                }
            } catch (RuntimeException e) {
                current.unreadableItems++;
            }
        }
        if (findings.isEmpty()) {
            return;
        }
        String where = "last position " + place(safe(player::getLocation))
            + " | respawn point " + place(safe(() -> player.getRespawnLocation(false)))
            + " | last death " + place(safe(player::getLastDeathLocation));
        write(current, player.getName(), parsed.uuid(), "PROOF (offline, file saved "
            + TIME.format(Instant.ofEpochMilli(parsed.savedMs())) + ")", where, findings);
    }

    private void scanOnline(Job current, Player player) {
        List<Finding> findings = new ArrayList<>();
        IllegalItemInspector sanitizer = plugin.inspector();
        ItemStack[] inventory = player.getInventory().getContents();
        for (int slot = 0; slot < inventory.length; slot++) {
            addIfIllegal(findings, sanitizer, "inventory slot " + slot, inventory[slot]);
        }
        ItemStack[] ender = player.getEnderChest().getContents();
        for (int slot = 0; slot < ender.length; slot++) {
            addIfIllegal(findings, sanitizer, "ender chest slot " + slot, ender[slot]);
        }
        addIfIllegal(findings, sanitizer, "cursor", player.getItemOnCursor());
        if (!findings.isEmpty()) {
            write(current, player.getName(), player.getUniqueId(), "PROOF (online now)",
                "position " + place(player.getLocation()) + " | respawn point "
                    + place(safe(() -> player.getRespawnLocation(false))), findings);
        }
    }

    private static void addIfIllegal(List<Finding> findings, IllegalItemInspector inspector, String where,
                                     ItemStack item) {
        Finding finding = inspector.inspect(where, item);
        if (finding != null) {
            findings.add(finding);
        }
    }

    private void write(Job current, String name, UUID uuid, String kind, String where, List<Finding> findings) {
        current.playersWithItems++;
        current.itemsFound += findings.size();
        List<String> lines = new ArrayList<>();
        String summary = IllegalItemInspector.summary(findings);
        lines.add(kind + " | " + ReportFile.clean(name == null ? "?" : name) + " (" + uuid + ") | " + summary
            + " | " + ReportFile.clean(where));
        for (Finding finding : findings) {
            lines.add("    - " + ReportFile.clean(finding.line()));
        }
        report.append(lines);
        current.chat.add((name == null ? uuid.toString() : name) + ": " + summary);
    }

    private void finish(Job current) {
        if (job != current) {
            return;
        }
        job = null;
        current.cancelled = true;
        current.results.clear();
        stopTask();
        long seconds = Math.max(1, (System.currentTimeMillis() - current.startedMs) / 1000);
        String summary = "Player scan " + (current.readerDone && current.filesDone >= current.files ? "finished"
            : "stopped") + " in " + seconds + " s: " + current.playersWithItems + " player(s) with "
            + current.itemsFound + " illegal item(s); " + current.filesDone + "/" + current.files + " save file(s) read"
            + (current.unreadableFiles > 0 ? ", " + current.unreadableFiles + " unreadable" : "")
            + (current.unreadableItems > 0 ? ", " + current.unreadableItems + " item(s) could not be loaded" : "")
            + ". Report: plugins/NovaGuard/" + ReportFile.NAME;
        report.append(List.of("=== " + summary + " ==="));
        plugin.getLogger().info(summary);
        CommandSender sender = current.sender;
        boolean reachable = !(sender instanceof Player p) || p.isOnline();
        if (reachable && !(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
            int shown = 0;
            for (String line : current.chat) {
                if (shown++ >= MAX_CHAT_LINES) {
                    reply(sender, "... and " + (current.chat.size() - MAX_CHAT_LINES) + " more in the report.",
                        NamedTextColor.GRAY);
                    break;
                }
                reply(sender, line, NamedTextColor.GOLD);
            }
            reply(sender, summary, NamedTextColor.GREEN);
        } else {
            current.chat.stream().limit(MAX_CHAT_LINES).forEach(line -> plugin.getLogger().info("  " + line));
        }
    }

    private void stopTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public void shutdown() {
        if (job != null) {
            job.cancelled = true;
        }
        stopTask();
        reader.shutdownNow();
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The folder with the player save files. Mojang has moved it between versions ("playerdata" next to the
     * overworld, later "players/data"), so the main world's folder and its parents are searched.
     */
    static Path playerDataFolder() {
        if (Bukkit.getWorlds().isEmpty()) {
            return null;
        }
        Path container = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        Path dir = Bukkit.getWorlds().get(0).getWorldFolder().toPath().toAbsolutePath().normalize();
        for (int up = 0; up < 4 && dir != null && dir.startsWith(container); up++, dir = dir.getParent()) {
            for (Path candidate : List.of(dir.resolve("playerdata"), dir.resolve("players").resolve("data"))) {
                if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static String place(Location location) {
        if (location == null) {
            return "unknown";
        }
        return (location.getWorld() == null ? "?" : location.getWorld().getName()) + " " + location.getBlockX() + " "
            + location.getBlockY() + " " + location.getBlockZ();
    }

    private static Location safe(java.util.function.Supplier<Location> getter) {
        try {
            return getter.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String name(OfflinePlayer player) {
        return ReportFile.clean(player.getName() == null ? "?" : player.getName());
    }

    private static void reply(CommandSender sender, String text, NamedTextColor color) {
        sender.sendMessage(Component.text("[NovaGuard] ", NamedTextColor.DARK_AQUA).append(Component.text(text, color)));
    }
}
