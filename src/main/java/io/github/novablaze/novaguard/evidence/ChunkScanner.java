package io.github.novablaze.novaguard.evidence;

import io.github.novablaze.novaguard.NovaGuardPlugin;
import io.github.novablaze.novaguard.Settings;
import io.github.novablaze.novaguard.core.PlayerData;
import io.github.novablaze.novaguard.evidence.IllegalItemInspector.Finding;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Lectern;
import org.bukkit.block.Jukebox;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

/**
 * The always-on evidence scan. Every chunk that loads (wherever a player goes, also staff in spectator) and,
 * every few minutes, every chunk that stays loaded is searched for illegal items: chests, barrels, shulker boxes,
 * hoppers, furnaces, item frames, armor stands, mobs, items on the ground, minecarts, donkeys... Online players'
 * own inventories are checked on the same rotation.
 *
 * <p>Nothing is ever changed. Each find goes to illegal-enchantments-report.txt with the exact block, slot,
 * category, the nearest player (a hint) and CoreProtect's record of who placed and opened the container; staff
 * get a clickable chat alert.
 *
 * <p>Lag-safe: the main thread only copies the few items that could be illegal (a fixed budget of milliseconds
 * per tick, paused while TPS is low). Checking them, CoreProtect lookups and writing the report run on a
 * background thread, which is what the spare CPU cores are good for.
 */
public final class ChunkScanner implements Listener {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault());
    /** Most chunks waiting for a scan; more (an elytra flight through new land) are dropped and counted. */
    private static final int MAX_PENDING = 50_000;
    /** Containers remembered so an unchanged stash is not reported again on every rescan. */
    private static final int MAX_REMEMBERED = 100_000;
    /** Background work waiting; if the background thread falls this far behind, the main thread waits a tick. */
    private static final int MAX_BACKLOG = 2_000;

    record ChunkRef(UUID world, int x, int z) {
    }

    /** A copied container: everything the background thread needs, so it never touches the world. */
    record Snapshot(String kind, String worldName, UUID worldId, int x, int y, int z, boolean playerOwned,
                    String owner, UUID ownerId, List<String> slots, List<ItemStack> items, String nearest,
                    UUID requester) {
        String key() {
            if (playerOwned) {
                return "player:" + ownerId; // A player walks around: their inventory is one place.
            }
            return worldId + ":" + x + ":" + y + ":" + z + ":" + kind;
        }

        String place() {
            return worldName + " " + x + " " + y + " " + z;
        }
    }

    private final NovaGuardPlugin plugin;
    private final IllegalItemInspector inspector;
    private final ReportFile report;
    private final Set<ChunkRef> pending = new LinkedHashSet<>();
    private final Map<ChunkRef, Long> lastScanned = new HashMap<>();
    /** Requests from /ng scanhere: chunks to scan right away, for this staff member. */
    private final Map<ChunkRef, UUID> requested = new LinkedHashMap<>();
    private final ThreadPoolExecutor checker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<>(), r -> {
            Thread thread = new Thread(r, "NovaGuard-evidence-scan");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
    /** Background thread only: container key -> what was last reported there. */
    private final Map<String, String> reported = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_REMEMBERED;
        }
    };
    /** Main thread only: container key -> when staff were last alerted. */
    private final Map<String, Long> lastAlert = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > MAX_REMEMBERED;
        }
    };
    private volatile CoreProtectEvidence coreProtect;
    private boolean coreProtectChecked;
    private BukkitTask task;
    private long ticks;
    private int dropped;
    private long chunksScanned;
    private volatile long stashesFound;

    public ChunkScanner(NovaGuardPlugin plugin, IllegalItemInspector inspector, ReportFile report) {
        this.plugin = plugin;
        this.inspector = inspector;
        this.report = report;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> plugin.safety().run("chunk-scan", this::tick), 40L, 1L);
        // Chunks that are loaded already (spawn, players online after /reload).
        Bukkit.getScheduler().runTask(plugin, this::queueLoaded);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
        }
        checker.shutdownNow();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!plugin.settings().chunkScanEnabled || event.isNewChunk()) {
            return; // A chunk generated just now cannot hold anybody's items.
        }
        queue(new ChunkRef(event.getWorld().getUID(), event.getChunk().getX(), event.getChunk().getZ()));
    }

    private void queue(ChunkRef ref) {
        if (pending.size() >= MAX_PENDING) {
            dropped++;
            return;
        }
        pending.add(ref);
    }

    // ---------------------------------------------------------------- /ng scanhere

    public void scanHere(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            reply(sender, "Only a player can use scanhere (it scans around you).", NamedTextColor.RED);
            return;
        }
        int radius = 4;
        if (args.length >= 2) {
            try {
                radius = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                reply(sender, "Usage: /ng scanhere [radius in chunks, 1-12]", NamedTextColor.RED);
                return;
            }
        }
        radius = Math.max(0, Math.min(12, radius));
        Chunk center = player.getLocation().getChunk();
        World world = player.getWorld();
        int count = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (world.isChunkLoaded(center.getX() + dx, center.getZ() + dz)) {
                    requested.put(new ChunkRef(world.getUID(), center.getX() + dx, center.getZ() + dz),
                        player.getUniqueId());
                    count++;
                }
            }
        }
        reply(sender, "Scanning " + count + " loaded chunk(s) around you (radius " + radius + "). Every illegal item"
            + " found is shown here; nothing is changed.", NamedTextColor.YELLOW);
        UUID who = player.getUniqueId();
        int total = count;
        // Tell them when it is done, after the background thread has checked everything queued before.
        Bukkit.getScheduler().runTaskLater(plugin, () -> waitForScanHere(who, total, 0), 5L);
    }

    private void waitForScanHere(UUID who, int total, int tries) {
        if (requested.containsValue(who) && tries < 1200) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> waitForScanHere(who, total, tries + 1), 5L);
            return;
        }
        checker.execute(() -> Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(who);
            if (player != null) {
                reply(player, "Scan of " + total + " chunk(s) finished.", NamedTextColor.GREEN);
            }
        }));
    }

    public String status() {
        return "Chunk scan: " + (plugin.settings().chunkScanEnabled ? "on" : "off") + ", " + chunksScanned
            + " chunk scans since start, " + pending.size() + " waiting, " + stashesFound + " stash report(s)"
            + (dropped > 0 ? ", " + dropped + " skipped (queue full, they are scanned on the next rotation)" : "")
            + (coreProtect != null ? ", CoreProtect evidence on" : ", no CoreProtect evidence") + ".";
    }

    // ---------------------------------------------------------------- main thread: copy

    private void tick() {
        Settings settings = plugin.settings();
        if (!coreProtectChecked) {
            coreProtectChecked = true;
            coreProtect = settings.coreProtectEvidence ? CoreProtectEvidence.connect(plugin.getLogger()) : null;
        }
        ticks++;
        if (!settings.chunkScanEnabled && requested.isEmpty()) {
            return;
        }
        if (settings.chunkScanEnabled && ticks % 1200 == 0) {
            queueLoaded();
            queuePlayers();
        }
        if (plugin.lag().tps() < settings.chunkScanMinTps && requested.isEmpty()) {
            return; // The server is struggling: wait, the scan never adds to lag.
        }
        if (checker.getQueue().size() > MAX_BACKLOG) {
            return; // Let the background thread catch up first.
        }
        long start = System.nanoTime();
        long budget = settings.chunkScanBudgetNanos;
        while (System.nanoTime() - start < budget) {
            ChunkRef ref;
            UUID requester = null;
            if (!requested.isEmpty()) {
                Iterator<Map.Entry<ChunkRef, UUID>> it = requested.entrySet().iterator();
                Map.Entry<ChunkRef, UUID> next = it.next();
                it.remove();
                ref = next.getKey();
                requester = next.getValue();
            } else if (!pending.isEmpty()) {
                Iterator<ChunkRef> it = pending.iterator();
                ref = it.next();
                it.remove();
            } else {
                return;
            }
            scanChunk(ref, requester);
        }
    }

    /** Every loaded chunk not scanned within rescan-minutes goes back in the queue. */
    private void queueLoaded() {
        long now = System.currentTimeMillis();
        long rescan = plugin.settings().chunkRescanMs;
        Set<ChunkRef> loaded = new java.util.HashSet<>();
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                ChunkRef ref = new ChunkRef(world.getUID(), chunk.getX(), chunk.getZ());
                loaded.add(ref);
                Long last = lastScanned.get(ref);
                if (last == null || now - last >= rescan) {
                    queue(ref);
                }
            }
        }
        // Forget chunks that are no longer loaded (they are scanned again when they load).
        lastScanned.keySet().retainAll(loaded);
    }

    /** Online players' own inventories: the strongest evidence there is. */
    private void queuePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            // Staff building in creative carry barriers and spawn eggs on purpose: never evidence.
            GameMode mode = player.getGameMode();
            if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR || player.hasPermission("novaguard.admin")) {
                continue;
            }
            snapshotPlayer(player, null);
        }
    }

    private void scanChunk(ChunkRef ref, UUID requester) {
        World world = Bukkit.getWorld(ref.world());
        if (world == null || !world.isChunkLoaded(ref.x(), ref.z())) {
            return;
        }
        Chunk chunk = world.getChunkAt(ref.x(), ref.z());
        lastScanned.put(ref, System.currentTimeMillis());
        chunksScanned++;
        for (BlockState state : chunk.getTileEntities(false)) {
            try {
                Inventory inventory = null;
                if (state instanceof Chest chest) {
                    inventory = chest.getBlockInventory(); // Each half of a double chest once.
                } else if (state instanceof BlockInventoryHolder holder) {
                    inventory = holder.getInventory();
                }
                if (inventory != null) {
                    snapshot(lower(state.getType().name()), state.getLocation(), inventory.getContents(), null, null,
                        requester);
                } else if (state instanceof Jukebox jukebox) {
                    snapshot("jukebox", state.getLocation(), new ItemStack[] {jukebox.getRecord()}, null, null,
                        requester);
                } else if (state instanceof Lectern lectern) {
                    snapshot("lectern", state.getLocation(), lectern.getInventory().getContents(), null, null,
                        requester);
                }
            } catch (RuntimeException e) {
                // One odd block (another plugin's custom block) never stops the scan.
            }
        }
        if (!chunk.isEntitiesLoaded()) {
            return;
        }
        for (Entity entity : chunk.getEntities()) {
            if (!entity.isValid() || entity instanceof Player) {
                continue;
            }
            try {
                snapshotEntity(entity, requester);
            } catch (RuntimeException e) {
                // Same: skip it, keep going.
            }
        }
    }

    private void snapshotEntity(Entity entity, UUID requester) {
        String kind = lower(entity.getType().name());
        if (entity instanceof Item item) {
            snapshot("item on the ground", entity.getLocation(), new ItemStack[] {item.getItemStack()}, null, null,
                requester);
            return;
        }
        if (entity instanceof ItemFrame frame) {
            snapshot(kind, entity.getLocation(), new ItemStack[] {frame.getItem()}, null, null, requester);
            return;
        }
        List<ItemStack> items = new ArrayList<>();
        if (entity instanceof InventoryHolder holder) {
            items.addAll(java.util.Arrays.asList(holder.getInventory().getContents()));
        }
        if (entity instanceof LivingEntity living && !(entity instanceof AbstractHorse)) {
            EntityEquipment equipment = living.getEquipment();
            if (equipment != null) {
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    if (living.canUseEquipmentSlot(slot)) {
                        items.add(equipment.getItem(slot));
                    }
                }
            }
        }
        if (!items.isEmpty()) {
            snapshot(kind, entity.getLocation(), items.toArray(new ItemStack[0]), null, null, requester);
        }
    }

    /** A player's inventory and ender chest, checked as their own (PROOF), with where they stand. */
    void snapshotPlayer(Player player, UUID requester) {
        ItemStack[] inventory = player.getInventory().getContents();
        ItemStack[] ender = player.getEnderChest().getContents();
        List<String> slots = new ArrayList<>();
        List<ItemStack> copies = new ArrayList<>();
        for (int i = 0; i < inventory.length; i++) {
            copyIfWorth(inventory[i], "inventory slot " + i, slots, copies);
        }
        for (int i = 0; i < ender.length; i++) {
            copyIfWorth(ender[i], "ender chest slot " + i, slots, copies);
        }
        copyIfWorth(player.getItemOnCursor(), "cursor", slots, copies);
        if (copies.isEmpty()) {
            return;
        }
        Location at = player.getLocation();
        submit(new Snapshot("player inventory", at.getWorld().getName(), at.getWorld().getUID(), at.getBlockX(),
            at.getBlockY(), at.getBlockZ(), true, player.getName(), player.getUniqueId(), slots, copies, null,
            requester));
    }

    private void snapshot(String kind, Location at, ItemStack[] contents, String owner, UUID ownerId,
                          UUID requester) {
        List<String> slots = new ArrayList<>();
        List<ItemStack> copies = new ArrayList<>();
        for (int i = 0; i < contents.length; i++) {
            copyIfWorth(contents[i], "slot " + i, slots, copies);
        }
        if (copies.isEmpty()) {
            return;
        }
        submit(new Snapshot(kind, at.getWorld().getName(), at.getWorld().getUID(), at.getBlockX(), at.getBlockY(),
            at.getBlockZ(), false, owner, ownerId, slots, copies, nearestPlayer(at), requester));
    }

    /** Only items that could be illegal are copied (most items are plain and cost nothing). */
    private void copyIfWorth(ItemStack item, String slot, List<String> slots, List<ItemStack> copies) {
        if (inspector.worthCopying(item)) {
            slots.add(slot);
            copies.add(item.clone());
        }
    }

    /** The nearest player in survival/adventure (never staff in spectator or creative): a hint, not proof. */
    private String nearestPlayer(Location at) {
        int radius = plugin.settings().chunkScanNearestRadius;
        if (radius <= 0) {
            return null;
        }
        Player best = null;
        double bestDistance = (double) radius * radius;
        for (Player player : at.getWorld().getPlayers()) {
            GameMode mode = player.getGameMode();
            if (mode == GameMode.SPECTATOR || mode == GameMode.CREATIVE) {
                continue;
            }
            double distance = player.getLocation().distanceSquared(at);
            if (distance <= bestDistance) {
                best = player;
                bestDistance = distance;
            }
        }
        return best == null ? null : best.getName() + " (" + (int) Math.sqrt(bestDistance) + " blocks away)";
    }

    private void submit(Snapshot snapshot) {
        checker.execute(() -> {
            try {
                check(snapshot);
            } catch (RuntimeException | LinkageError e) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Evidence scan skipped one container", e);
            }
        });
    }

    // ---------------------------------------------------------------- background thread: check and report

    private void check(Snapshot snapshot) {
        List<Finding> findings = new ArrayList<>();
        for (int i = 0; i < snapshot.items().size(); i++) {
            Finding finding = inspector.inspect(snapshot.slots().get(i), snapshot.items().get(i));
            if (finding != null) {
                findings.add(finding);
            }
        }
        String key = snapshot.key();
        if (findings.isEmpty()) {
            reported.remove(key); // Emptied or cleaned: report it again if something illegal comes back.
            return;
        }
        StringBuilder fingerprint = new StringBuilder();
        findings.forEach(f -> fingerprint.append(f.line()).append('\n'));
        boolean changed = !fingerprint.toString().equals(reported.get(key));
        if (!changed && snapshot.requester() == null) {
            return; // Same stash, same items: already in the report.
        }
        reported.put(key, fingerprint.toString());
        String summary = IllegalItemInspector.summary(findings);
        List<String> lines = new ArrayList<>();
        if (changed) {
            List<String> evidence = new ArrayList<>();
            CoreProtectEvidence cp = coreProtect;
            World world = Bukkit.getWorld(snapshot.worldId());
            if (!snapshot.playerOwned() && cp != null && world != null) {
                evidence.addAll(cp.evidence(world, snapshot.x(), snapshot.y(), snapshot.z(),
                    plugin.settings().coreProtectDays));
            }
            stashesFound++;
            String head = snapshot.playerOwned()
                ? "PROOF (online, own inventory) | " + snapshot.owner() + " (" + snapshot.ownerId() + ") | "
                    + summary + " | standing at " + snapshot.place()
                : "STASH | " + snapshot.kind() + " at " + snapshot.place() + " | " + summary
                    + " | nearest player: " + (snapshot.nearest() == null ? "nobody in survival nearby"
                        : snapshot.nearest() + " (hint, not proof)");
            lines.add("[" + TIME.format(Instant.now()) + "] " + ReportFile.clean(head));
            for (Finding finding : findings) {
                lines.add("    - " + ReportFile.clean(finding.line()));
            }
            for (String line : evidence) {
                lines.add("    * " + ReportFile.clean(line));
            }
            if (!snapshot.playerOwned()) {
                lines.add("    * At the container: /co lookup radius:2 action:container  (who put what in or took"
                    + " it out)");
            }
            report.append(lines);
        }
        String alertText = (snapshot.playerOwned() ? snapshot.owner() + " carries " : snapshot.kind() + " at "
            + snapshot.place() + ": ") + summary;
        String firstItem = findings.get(0).line();
        boolean newFind = changed;
        Bukkit.getScheduler().runTask(plugin, () -> alert(snapshot, alertText, firstItem, newFind));
    }

    // ---------------------------------------------------------------- main thread: tell staff

    private void alert(Snapshot snapshot, String text, String firstItem, boolean newFind) {
        Component tp = Component.text(" [teleport]", NamedTextColor.AQUA)
            .clickEvent(ClickEvent.suggestCommand("/tp @s " + snapshot.x() + " " + (snapshot.y() + 1) + " "
                + snapshot.z()))
            .hoverEvent(HoverEvent.showText(Component.text(ReportFile.clean(firstItem))));
        Component message = Component.text("[NovaGuard] ", NamedTextColor.DARK_AQUA)
            .append(Component.text("Illegal items: ", NamedTextColor.RED))
            .append(Component.text(ReportFile.clean(text), NamedTextColor.GOLD))
            .append(tp);
        if (snapshot.requester() != null) {
            Player requester = Bukkit.getPlayer(snapshot.requester());
            if (requester != null) {
                requester.sendMessage(message);
            }
        }
        Settings settings = plugin.settings();
        if (!newFind || !settings.chunkScanAlerts) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastAlert.get(snapshot.key());
        if (last != null && now - last < settings.chunkScanAlertCooldownMs) {
            return;
        }
        lastAlert.put(snapshot.key(), now);
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (!staff.hasPermission("novaguard.alerts") || staff.getUniqueId().equals(snapshot.requester())) {
                continue;
            }
            PlayerData data = plugin.players().getIfPresent(staff.getUniqueId());
            if (data == null || data.alertsEnabled) {
                staff.sendMessage(message);
            }
        }
    }

    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static void reply(CommandSender sender, String text, NamedTextColor color) {
        sender.sendMessage(Component.text("[NovaGuard] ", NamedTextColor.DARK_AQUA).append(Component.text(text, color)));
    }
}
