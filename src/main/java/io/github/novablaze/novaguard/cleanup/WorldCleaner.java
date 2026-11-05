package io.github.novablaze.novaguard.cleanup;

import io.github.novablaze.novaguard.NovaGuardPlugin;
import io.github.novablaze.novaguard.core.WorkQueue;
import io.github.novablaze.novaguard.items.Inventories;
import io.github.novablaze.novaguard.items.ItemSanitizer;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm")
        .withZone(ZoneId.systemDefault());

    /** What one run found or changed. */
    public static final class Report {
        final boolean apply;
        int items;
        int places;
        int dropped;
        final List<String> lines = new ArrayList<>();

        Report(boolean apply) {
            this.apply = apply;
        }

        void add(String where, String item, List<String> changes) {
            items++;
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
            this.report = new Report(apply);
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
    /** Chunks loaded / players joined after a cleanup: logged, never announced in chat. */
    private final Report background = new Report(true);
    private Job job;

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
        }
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        List<Chunk> chunks = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            chunks.addAll(List.of(world.getLoadedChunks()));
        }
        Job current = new Job(apply, startedBy, players.size(), chunks.size());
        job = current;
        for (Player player : players) {
            queue.add(() -> jobStep(current, () -> {
                if (player.isOnline()) {
                    cleanPlayer(player, current.report, epoch);
                }
                current.players++;
            }));
        }
        for (Chunk chunk : chunks) {
            queue.add(() -> jobStep(current, () -> {
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
            }));
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
            return "No cleanup is running." + (epoch > 0 ? " Last cleanup: " + time(epoch)
                + ". Since then, " + background.items + " illegal item(s) were fixed in chunks/players used again." : "");
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
        queue.add(() -> plugin.safety().run("cleanup", () -> {
            if (chunk.isLoaded() && stamp(chunk.getPersistentDataContainer(), entityStampKey) < epoch) {
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
        plugin.safety().run("cleanup", queue::runTick);
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

    private void cleanPlayer(Player player, Report report, long epoch) {
        String owner = player.getName() + "'s ";
        int before = report.items;
        List<String> changes = new ArrayList<>();
        cleanInventory(player.getInventory(), owner + "inventory", report, changes);
        cleanInventory(player.getEnderChest(), owner + "ender chest", report, changes);
        ItemStack cursor = player.getItemOnCursor();
        ItemStack fixed = fix(cursor, owner + "cursor", report, changes);
        if (fixed != null && report.apply) {
            player.setItemOnCursor(fixed);
        }
        Inventory top = player.getOpenInventory().getTopInventory();
        if (Inventories.isRealStorage(top)) {
            cleanInventory(top, owner + "open " + Inventories.name(top), report, changes);
        }
        if (report.items > before) {
            report.places++;
            if (report.apply) {
                player.sendMessage(MiniMessage.miniMessage().deserialize(plugin.settings().illegalFixedMessage,
                    Placeholder.unparsed("changes", shorten(String.join("; ", changes)))));
            }
        }
        if (report.apply) {
            player.getPersistentDataContainer().set(stampKey, PersistentDataType.LONG, epoch);
        }
    }

    private void cleanBlocks(Chunk chunk, Report report, long epoch) {
        for (BlockState state : chunk.getTileEntities(false)) {
            if (state instanceof BlockInventoryHolder holder) {
                // A double chest is two block entities that both show the whole chest: clean each half once.
                Inventory inventory = state instanceof Chest chest ? chest.getBlockInventory() : holder.getInventory();
                cleanInventory(inventory, Inventories.where(state.getLocation()) + " "
                    + state.getType().name().toLowerCase(Locale.ROOT), report, null);
            }
        }
        if (report.apply) {
            chunk.getPersistentDataContainer().set(stampKey, PersistentDataType.LONG, epoch);
        }
    }

    private void cleanEntities(List<Entity> entities, Chunk chunk, Report report, long epoch) {
        for (Entity entity : entities) {
            if (entity.isValid() && !(entity instanceof Player)) {
                cleanEntity(entity, report);
            }
        }
        if (report.apply) {
            chunk.getPersistentDataContainer().set(entityStampKey, PersistentDataType.LONG, epoch);
        }
    }

    private void cleanEntity(Entity entity, Report report) {
        String where = Inventories.where(entity.getLocation()) + " " + entity.getType().name().toLowerCase(Locale.ROOT);
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
            cleanInventory(holder.getInventory(), where, report, null);
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
                ItemStack fixed = fix(equipment.getItem(slot), where + " " + slot.name().toLowerCase(Locale.ROOT),
                    report, null);
                if (fixed != null && report.apply) {
                    equipment.setItem(slot, fixed);
                }
            }
        }
    }

    /** Cleans one inventory. Public for the self-test. @return items fixed (or found, in a preview) */
    public int cleanInventory(Inventory inventory, String where, Report report, List<String> changesOut) {
        int found = 0;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack fixed = fix(inventory.getItem(slot), where, report, changesOut);
            if (fixed != null) {
                found++;
                if (report.apply) {
                    inventory.setItem(slot, fixed);
                }
            }
        }
        return found;
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
        return new Report(apply);
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
            .append("Nobody was warned or banned for this.").append(nl).append(nl);
        report.lines.forEach(line -> text.append(line).append(nl));
        if (report.dropped > 0) {
            text.append("... and ").append(report.dropped).append(" more (list cut at ").append(MAX_REPORT_LINES)
                .append(" lines)").append(nl);
        }
        File folder = new File(plugin.getDataFolder(), "reports");
        String name = "cleanup-" + FILE_DATE.format(Instant.ofEpochMilli(current.startedMs))
            + (report.apply ? "" : "-preview") + ".txt";
        try {
            Files.createDirectories(folder.toPath());
            Files.writeString(new File(folder, name).toPath(), text.toString(), StandardCharsets.UTF_8);
            return name;
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
