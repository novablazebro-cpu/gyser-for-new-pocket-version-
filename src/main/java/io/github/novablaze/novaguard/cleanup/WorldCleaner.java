package io.github.novablaze.novaguard.cleanup;

import io.github.novablaze.novaguard.NovaGuardPlugin;
import io.github.novablaze.novaguard.core.WorkQueue;
import io.github.novablaze.novaguard.items.Inventories;
import io.github.novablaze.novaguard.items.ItemSanitizer;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * /cleanallexploits: removes illegal enchantments from every item in the game and keeps the valid ones
 * (see docs/adr/ADR-001-clean-all-exploits.md).
 *
 * <ul>
 *   <li>Now: every online player and every loaded chunk (containers, item frames, armor stands, mobs' gear,
 *       items on the ground, chest boats/minecarts, donkeys...), 2 ms per tick so the server never lags.</li>
 *   <li>Later: chunks that were not loaded are cleaned when they next load, offline players when they next join
 *       (before any dupe scan, so nothing they owned before the cleanup is blamed on them).</li>
 * </ul>
 * A cleanup never warns, watches or bans anybody. Every change is written to a report file.
 */
public final class WorldCleaner implements Listener, Runnable {

    private static final long BUDGET_NANOS = 2_000_000L;
    private static final int MAX_REPORT_LINES = 3_000;
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
        .withZone(ZoneId.systemDefault());

    /** What one run found or changed. */
    public static final class Report {
        final boolean apply;
        /** Background cleanups only count: their lines already go to violations.log. */
        final boolean keepLines;
        int items;
        int places;
        int dropped;
        /** Blocks/entities that threw an error and were skipped (they are cleaned when next used). */
        int errors;
        final List<String> lines = new ArrayList<>();

        Report(boolean apply, boolean keepLines) {
            this.apply = apply;
            this.keepLines = keepLines;
        }

        void add(String where, String item, List<String> changes) {
            items++;
            if (!keepLines) {
                return;
            }
            if (lines.size() < MAX_REPORT_LINES) {
                lines.add(where + "  " + item + ": " + String.join(", ", changes));
            } else {
                dropped++;
            }
        }

        public int items() {
            return items;
        }
    }

    /** A cleanup started by an admin. */
    private final class Job {
        final Report report;
        final String startedBy;
        final long startedMs = System.currentTimeMillis();
        final int playersTotal;
        final int chunksTotal;
        int players;
        int chunks;
        int pending;
        boolean cancelled;

        Job(boolean apply, String startedBy, int playersTotal, int chunksTotal) {
            this.report = new Report(apply, true);
            this.startedBy = startedBy;
            this.playersTotal = playersTotal;
            this.chunksTotal = chunksTotal;
            this.pending = playersTotal + chunksTotal;
        }
    }

    private final NovaGuardPlugin plugin;
    /** Stamps: players, chunk blocks, chunk entities (entities load separately from blocks since 1.17). */
    private final NamespacedKey stampKey;
    private final NamespacedKey entityStampKey;
    private final WorkQueue queue = new WorkQueue(BUDGET_NANOS);
    /** Chunks loaded / players joined after the last cleanup (since the server started): counted and logged. */
    private Report background = new Report(true, false);
    private Job job;

    /** A queued step of a job, so a cancelled job's steps can be dropped from the queue. */
    private record JobStep(Job owner, Runnable body) implements Runnable {
        @Override
        public void run() {
            body.run();
        }
    }

    public WorldCleaner(NovaGuardPlugin plugin) {
        this.plugin = plugin;
        this.stampKey = new NamespacedKey(plugin, "cleaned");
        this.entityStampKey = new NamespacedKey(plugin, "cleaned_entities");
    }

    // ---------------------------------------------------------------- the admin command

    /** @return the message for the admin */
    public String start(String startedBy, boolean apply) {
        if (job != null) {
            return "A cleanup is already running: " + status();
        }
        if (!plugin.settings().illegalEnabled) {
            return "illegal-items.enabled is false in config.yml: nothing would be fixed. Turn it on first.";
        }
        long epoch = System.currentTimeMillis();
        if (apply) {
            plugin.serverState().setCleanEpoch(epoch);
            background = new Report(true, false);
        }
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        List<Chunk> chunks = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            chunks.addAll(List.of(world.getLoadedChunks()));
        }
        Job current = new Job(apply, startedBy, players.size(), chunks.size());
        job = current;
        for (Player player : players) {
            queue.add(new JobStep(current, () -> jobStep(current, () -> {
                if (player.isOnline()) {
                    cleanPlayer(player, current.report, epoch);
                }
                current.players++;
            })));
        }
        for (Chunk chunk : chunks) {
            queue.add(new JobStep(current, () -> jobStep(current, () -> {
                if (chunk.isLoaded()) {
                    int before = current.report.items;
                    cleanBlocks(chunk, current.report, epoch);
                    if (chunk.isEntitiesLoaded()) {
                        cleanEntities(List.of(chunk.getEntities()), chunk, current.report, epoch);
                    }
                    if (current.report.items > before) {
                        current.report.places++;
                    }
                }
                current.chunks++;
            })));
        }
        String what = apply ? "Cleaning" : "Previewing (nothing is changed)";
        plugin.getLogger().warning(what + " illegal items: " + players.size() + " online players and " + chunks.size()
            + " loaded chunks, started by " + startedBy + ".");
        return what + " " + players.size() + " online players and " + chunks.size() + " loaded chunks. This runs in"
            + " the background without lag; you get a message when it is done."
            + (apply ? " Chunks that are not loaded and offline players are cleaned when they are next used." : "");
    }

    public String status() {
        Job current = job;
        if (current == null) {
            long epoch = plugin.serverState().cleanEpochMs();
            return "No cleanup is running." + (epoch > 0 ? " Last cleanup: " + time(epoch) + ". Since the server"
                + " started, " + background.items + " more illegal item(s) were fixed in chunks that loaded and players"
                + " who joined (details in violations.log)." : "");
        }
        return String.format(Locale.ROOT, "%s: %d/%d players, %d/%d chunks, %d illegal item(s) %s so far.",
            current.report.apply ? "cleaning" : "preview", current.players, current.playersTotal, current.chunks,
            current.chunksTotal, current.report.items, current.report.apply ? "fixed" : "found");
    }

    public String cancel() {
        Job current = job;
        if (current == null) {
            return "No cleanup is running.";
        }
        current.cancelled = true;
        queue.removeIf(task -> task instanceof JobStep step && step.owner() == current);
        finish(current);
        return "Cleanup stopped. What was not reached yet is cleaned when it is next used"
            + (current.report.apply ? "." : " (preview: nothing was changed).");
    }

    // ---------------------------------------------------------------- later: chunk loads and joins

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        long epoch = plugin.serverState().cleanEpochMs();
        if (epoch <= 0 || event.isNewChunk()) {
            return; // New chunks are generated by the server: they cannot hold illegal items.
        }
        Chunk chunk = event.getChunk();
        if (stamp(chunk.getPersistentDataContainer(), stampKey) >= epoch) {
            return;
        }
        // Not inside the load event: on a later tick, within the time budget.
        queue.add(() -> plugin.safety().run("cleanup", () -> {
            if (chunk.isLoaded() && stamp(chunk.getPersistentDataContainer(), stampKey) < epoch) {
                cleanBlocks(chunk, background, epoch);
            }
        }));
    }

    /** Item frames, armor stands, mobs: they load after the chunk's blocks. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        long epoch = plugin.serverState().cleanEpochMs();
        Chunk chunk = event.getChunk();
        if (epoch <= 0 || event.getEntities().isEmpty()
            || stamp(chunk.getPersistentDataContainer(), entityStampKey) >= epoch) {
            return;
        }
        List<Entity> entities = new ArrayList<>(event.getEntities());
        // No second stamp check: these are the entities of THIS load (an earlier queued task may have stamped the
        // chunk while it skipped entities that were gone).
        queue.add(() -> plugin.safety().run("cleanup", () -> {
            if (chunk.isLoaded()) {
                cleanEntities(entities, chunk, background, epoch);
            }
        }));
    }

    /** Before anything else on join: the dupe scan must never blame items that are older than the cleanup. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        long epoch = plugin.serverState().cleanEpochMs();
        Player player = event.getPlayer();
        if (epoch > 0 && stamp(player.getPersistentDataContainer(), stampKey) < epoch) {
            plugin.safety().run("cleanup", () -> cleanPlayer(player, background, epoch));
        }
    }

    // ---------------------------------------------------------------- every tick

    @Override
    public void run() {
        // Own area name: if single items keep failing ("cleanup" switched off), the queue still drains and jobs end.
        plugin.safety().run("cleanup-queue", queue::runTick);
        Job current = job;
        if (current != null && current.pending <= 0) {
            finish(current);
        }
    }

    private void jobStep(Job owner, Runnable body) {
        owner.pending--;
        if (!owner.cancelled) {
            plugin.safety().run("cleanup", body);
        }
    }

    // ---------------------------------------------------------------- cleaning

    /**
     * The player's own inventory, ender chest and cursor. An open chest is not cleaned here: the chunk pass does it
     * (otherwise a preview would count it twice). Creative players are skipped like everywhere else, and so are
     * items in use (an elytra while gliding): those players are cleaned again a few seconds later.
     */
    private void cleanPlayer(Player player, Report report, long epoch) {
        if (Inventories.isCreative(player)) {
            return; // Not stamped: cleaned when they join again in survival (and on leaving creative, see DupeListener).
        }
        String owner = player.getName() + "'s ";
        int before = report.items;
        List<String> changes = new ArrayList<>();
        Set<Integer> busy = Inventories.busySlots(player);
        int skippedBusy = cleanInventory(player.getInventory(), owner + "inventory", report, changes, busy);
        cleanInventory(player.getEnderChest(), owner + "ender chest", report, changes, Set.of());
        ItemStack fixed = fix(player.getItemOnCursor(), owner + "cursor", report, changes);
        if (fixed != null && report.apply) {
            player.setItemOnCursor(fixed);
        }
        if (report.items > before) {
            report.places++;
            if (report.apply) {
                player.sendMessage(MiniMessage.miniMessage().deserialize(plugin.settings().illegalFixedMessage,
                    Placeholder.unparsed("changes", shorten(String.join("; ", changes)))));
            }
        }
        if (!report.apply) {
            return;
        }
        if (skippedBusy > 0) {
            // Try again in 5 seconds, when the elytra / shield / bow is probably no longer in use.
            Bukkit.getScheduler().runTaskLater(plugin, () -> queue.add(() -> plugin.safety().run("cleanup", () -> {
                if (player.isOnline() && stamp(player.getPersistentDataContainer(), stampKey) < epoch) {
                    cleanPlayer(player, background, epoch);
                }
            })), 100L);
            return;
        }
        player.getPersistentDataContainer().set(stampKey, PersistentDataType.LONG, epoch);
    }

    private void cleanBlocks(Chunk chunk, Report report, long epoch) {
        for (BlockState state : chunk.getTileEntities(false)) {
            if (!(state instanceof BlockInventoryHolder holder)) {
                continue;
            }
            try {
                // A double chest is two block entities that both show the whole chest: clean each half once.
                Inventory inventory = state instanceof Chest chest ? chest.getBlockInventory() : holder.getInventory();
                cleanInventory(inventory, Inventories.where(state.getLocation()) + " " + Inventories.lower(state.getType()),
                    report, null, Set.of());
            } catch (RuntimeException e) {
                skipped(report, "block " + Inventories.lower(state.getType()) + " at " + Inventories.where(state.getLocation()), e);
            }
        }
        if (report.apply) {
            chunk.getPersistentDataContainer().set(stampKey, PersistentDataType.LONG, epoch);
        }
    }

    private void cleanEntities(List<Entity> entities, Chunk chunk, Report report, long epoch) {
        for (Entity entity : entities) {
            if (!entity.isValid() || entity instanceof Player) {
                continue;
            }
            try {
                cleanEntity(entity, report);
            } catch (RuntimeException e) {
                skipped(report, Inventories.lower(entity.getType()) + " at " + Inventories.where(entity.getLocation()), e);
            }
        }
        if (report.apply) {
            chunk.getPersistentDataContainer().set(entityStampKey, PersistentDataType.LONG, epoch);
        }
    }

    /**
     * Note: an entity that walks from a chunk not processed yet into one already processed is not cleaned by this
     * run; its items are still fixed the moment they are used (dropped and picked up, taken off an armor stand,
     * taken out of a minecart), by the normal listeners.
     */
    private void cleanEntity(Entity entity, Report report) {
        String where = Inventories.where(entity.getLocation()) + " " + Inventories.lower(entity.getType());
        if (entity instanceof Item item) {
            ItemStack fixed = fix(item.getItemStack(), where, report, null);
            if (fixed != null && report.apply) {
                item.setItemStack(fixed);
            }
            return;
        }
        if (entity instanceof ItemFrame frame) {
            ItemStack fixed = fix(frame.getItem(), where, report, null);
            if (fixed != null && report.apply) {
                frame.setItem(fixed, false);
            }
            return;
        }
        if (entity instanceof InventoryHolder holder) {
            // Chest boats, minecarts, donkeys, llamas, villagers...
            cleanInventory(holder.getInventory(), where, report, null, Set.of());
        }
        if (entity instanceof LivingEntity living && !(entity instanceof AbstractHorse)) {
            // Armor stands, mobs holding or wearing items. (Horses keep saddle/armor in their inventory, done above.)
            EntityEquipment equipment = living.getEquipment();
            if (equipment == null) {
                return;
            }
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                if (!living.canUseEquipmentSlot(slot)) {
                    continue;
                }
                ItemStack fixed = fix(equipment.getItem(slot), where + " " + Inventories.lower(slot), report, null);
                if (fixed != null && report.apply) {
                    equipment.setItem(slot, fixed);
                }
            }
        }
    }

    /** Cleans one inventory (used by the self-test). @return items fixed (or found, in a preview) */
    public int cleanInventory(Inventory inventory, String where, Report report, List<String> changesOut) {
        int before = report.items;
        cleanInventory(inventory, where, report, changesOut, Set.of());
        return report.items - before;
    }

    /** @return how many illegal items were left alone because their slot is in use */
    private int cleanInventory(Inventory inventory, String where, Report report, List<String> changesOut,
                               Set<Integer> busy) {
        int skipped = 0;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (busy.contains(slot)) {
                if (item != null && !item.isEmpty() && plugin.fingerprints().sanitizer().sanitize(item).changed()) {
                    skipped++;
                }
                continue;
            }
            ItemStack fixed = fix(item, where, report, changesOut);
            if (fixed != null && report.apply) {
                inventory.setItem(slot, fixed);
            }
        }
        return skipped;
    }

    private void skipped(Report report, String what, RuntimeException e) {
        if (report.errors++ == 0) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Cleanup skipped " + what
                + " (it is cleaned when next used). Further errors in this run are only counted.", e);
        }
    }

    /** @return the fixed item, or null when the item is fine (or empty) */
    private ItemStack fix(ItemStack item, String where, Report report, List<String> changesOut) {
        if (item == null || item.isEmpty()) {
            return null;
        }
        ItemSanitizer.Result result = plugin.fingerprints().sanitizer().sanitize(item);
        if (!result.changed()) {
            return null;
        }
        report.add(where, Inventories.name(item), result.changes());
        if (changesOut != null) {
            changesOut.add(Inventories.name(item) + ": " + String.join(", ", result.changes()));
        }
        if (report == background) {
            plugin.violations().logLine("cleanup (chunk loaded / player joined): " + where + "  "
                + Inventories.name(item) + ": " + String.join(", ", result.changes()));
        }
        return result.item();
    }

    /** For the self-test: a report that is not part of any job. */
    public static Report newReport(boolean apply) {
        return new Report(apply, true);
    }

    // ---------------------------------------------------------------- the end

    private void finish(Job current) {
        if (job != current) {
            return;
        }
        job = null;
        Report report = current.report;
        long seconds = Math.max(1, (System.currentTimeMillis() - current.startedMs) / 1000);
        String file = writeReport(current, seconds);
        String summary = (report.apply ? "Cleanup " : "Cleanup preview ") + (current.cancelled ? "stopped" : "finished")
            + " in " + seconds + " s: " + report.items + " illegal item(s) " + (report.apply ? "fixed" : "found")
            + " in " + report.places + " place(s) (" + current.players + " players, " + current.chunks + " chunks)."
            + (file == null ? "" : " Report: plugins/NovaGuard/reports/" + file)
            + (report.apply ? "" : " Nothing was changed. To clean for real: /cleanallexploits confirm");
        plugin.getLogger().warning(summary);
        Component message = Component.text("[NovaGuard] " + summary, NamedTextColor.GOLD);
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("novaguard.admin")) {
                staff.sendMessage(message);
            }
        }
        if (report.apply) {
            plugin.notifier().send("NovaGuard: cleanup done", summary);
        }
    }

    private String writeReport(Job current, long seconds) {
        Report report = current.report;
        StringBuilder text = new StringBuilder();
        String nl = System.lineSeparator();
        text.append("NovaGuard cleanup ").append(report.apply ? "" : "PREVIEW (nothing was changed) ").append(nl)
            .append("Started by ").append(current.startedBy).append(" at ").append(time(current.startedMs))
            .append(", took ").append(seconds).append(" s").append(current.cancelled ? " (stopped early)" : "").append(nl)
            .append("Players: ").append(current.players).append("/").append(current.playersTotal)
            .append(", loaded chunks: ").append(current.chunks).append("/").append(current.chunksTotal).append(nl)
            .append("Illegal items ").append(report.apply ? "fixed: " : "found: ").append(report.items).append(nl)
            .append("Only impossible enchantments / Unbreakable were removed; every valid enchantment stayed.").append(nl)
            .append("Nobody was warned or banned for this.").append(nl)
            .append(report.errors > 0 ? report.errors + " block(s)/entity(s) could not be checked (error in the console);"
                + " they are cleaned when next used." + nl : "").append(nl);
        report.lines.forEach(line -> text.append(line).append(nl));
        if (report.dropped > 0) {
            text.append("... and ").append(report.dropped).append(" more (list cut at ").append(MAX_REPORT_LINES)
                .append(" lines)").append(nl);
        }
        File folder = new File(plugin.getDataFolder(), "reports");
        String base = "cleanup-" + FILE_DATE.format(Instant.ofEpochMilli(current.startedMs))
            + (report.apply ? "" : "-preview");
        try {
            Files.createDirectories(folder.toPath());
            // Never overwrite an earlier report: it is the record of what was changed.
            for (int n = 1; ; n++) {
                String name = base + (n == 1 ? "" : "-" + n) + ".txt";
                try {
                    Files.writeString(new File(folder, name).toPath(), text.toString(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    return name;
                } catch (java.nio.file.FileAlreadyExistsException exists) {
                    // Try the next name.
                }
            }
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("Could not write the cleanup report: " + e.getMessage());
            return null;
        }
    }

    private static long stamp(PersistentDataContainer pdc, NamespacedKey key) {
        Long value = pdc.get(key, PersistentDataType.LONG);
        return value == null ? 0 : value;
    }

    private static String time(long ms) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms));
    }

    private static String shorten(String text) {
        return text.length() > 220 ? text.substring(0, 217) + "..." : text;
    }
}
