package io.github.novablaze.novaguard.dupe;

import io.github.novablaze.novaguard.NovaGuardPlugin;
import io.github.novablaze.novaguard.Settings;
import io.github.novablaze.novaguard.items.ItemTree;
import io.github.novablaze.novaguard.math.WealthWindow;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Stackable items (diamonds, netherite...) cannot carry a hidden ID, so a duped stack can never be told apart from
 * a real one. Instead staff get an ALERT when a player becomes much richer within a few minutes. Nothing automatic
 * ever happens because of it: trades, shops and big mining trips look the same. Items a player moves between their
 * own chests and their inventory do not count as a gain.
 */
public final class WealthAlerts {

    private static final long ALERT_COOLDOWN_MS = 30 * 60_000L;
    /** Picking up your own death drops is not getting richer. */
    private static final long AFTER_DEATH_MS = 10 * 60_000L;

    private final NovaGuardPlugin plugin;
    private final Map<UUID, WealthWindow> windows = new HashMap<>();
    /** Net value this player put into containers (minus what they took out). */
    private final Map<UUID, Long> stored = new HashMap<>();
    private final Map<UUID, Long> openValue = new HashMap<>();
    private final Map<UUID, Long> pickedUp = new HashMap<>();
    private final Map<UUID, Long> lastAlertMs = new HashMap<>();
    private final Map<UUID, Long> diedAtMs = new HashMap<>();

    public WealthAlerts(NovaGuardPlugin plugin) {
        this.plugin = plugin;
    }

    public void onOpen(Player player, Inventory top) {
        if (enabled() && ItemFingerprints.isRealStorage(top)) {
            openValue.put(player.getUniqueId(), value(top));
        }
    }

    public void onClose(Player player, Inventory top) {
        Long before = openValue.remove(player.getUniqueId());
        if (before != null && ItemFingerprints.isRealStorage(top)) {
            stored.merge(player.getUniqueId(), value(top) - before, Long::sum);
        }
    }

    /** Dropped items still belong to the player until somebody picks them up. */
    public void onDrop(Player player, ItemStack item) {
        if (enabled()) {
            stored.merge(player.getUniqueId(), value(item, 0), Long::sum);
        }
    }

    /** @param thrower who dropped the item (null: a mob, a death, a broken block) */
    public void onPickup(Player player, ItemStack item, UUID thrower) {
        if (!enabled()) {
            return;
        }
        long value = value(item, 0);
        if (value <= 0) {
            return;
        }
        if (player.getUniqueId().equals(thrower)) {
            stored.merge(player.getUniqueId(), -value, Long::sum);
        } else {
            pickedUp.merge(player.getUniqueId(), value, Long::sum);
        }
    }

    /** Breaking a container (e.g. your own shulker box) drops what you stored in it: that is not a gain. */
    public void onBreakContainer(Player player, Inventory contents) {
        if (enabled()) {
            stored.merge(player.getUniqueId(), -value(contents), Long::sum);
        }
    }

    public void onDeath(Player player) {
        diedAtMs.put(player.getUniqueId(), System.currentTimeMillis());
    }

    /** Called after every scan of the player. */
    public void sample(Player player) {
        Settings settings = plugin.settings();
        if (!enabled()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long value = value(player.getInventory()) + value(player.getEnderChest()) + stored.getOrDefault(uuid, 0L);
        WealthWindow window = windows.computeIfAbsent(uuid, k -> new WealthWindow());
        WealthWindow.Change change = window.record(now, value, pickedUp.getOrDefault(uuid, 0L), settings.wealthWindowMs);
        if (change.gain() < settings.wealthThreshold || now - lastAlertMs.getOrDefault(uuid, 0L) < ALERT_COOLDOWN_MS
            || now - diedAtMs.getOrDefault(uuid, 0L) < AFTER_DEATH_MS) {
            return;
        }
        lastAlertMs.put(uuid, now);
        window.restart();
        plugin.violations().staffNote(player.getName(), "became much richer: +" + change.gain() + " value (threshold "
            + settings.wealthThreshold + ") within " + settings.wealthWindowMs / 60_000 + " minutes"
            + (change.pickedUp() > 0 ? ", " + change.pickedUp() + " of it picked up from the ground (ask who dropped it)" : "")
            + ". Could be a trade, a shop or a duped stack: spectate before acting. Nothing automatic happens.");
    }

    /** Creative players, players who left: start from zero next time. */
    public void reset(UUID uuid) {
        windows.remove(uuid);
        stored.remove(uuid);
        openValue.remove(uuid);
        pickedUp.remove(uuid);
        diedAtMs.remove(uuid);
    }

    private boolean enabled() {
        return plugin.settings().wealthEnabled && !plugin.settings().wealthValues.isEmpty();
    }

    private long value(Inventory inventory) {
        long total = 0;
        for (ItemStack item : inventory.getContents()) {
            total += value(item, 0);
        }
        return total;
    }

    private long value(ItemStack item, int depth) {
        if (item == null || item.isEmpty()) {
            return 0;
        }
        long total = (long) plugin.settings().wealthValues.getOrDefault(item.getType(), 0) * item.getAmount();
        if (depth < ItemTree.MAX_DEPTH) {
            List<ItemStack> inside = ItemTree.children(item);
            if (inside != null) {
                for (ItemStack child : inside) {
                    total += value(child, depth + 1);
                }
            }
        }
        return total;
    }
}
