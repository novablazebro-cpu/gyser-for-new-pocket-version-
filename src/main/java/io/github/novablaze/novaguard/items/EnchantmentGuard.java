package io.github.novablaze.novaguard.items;

import io.github.novablaze.novaguard.NovaGuardPlugin;
import io.github.novablaze.novaguard.Settings;
import io.github.novablaze.novaguard.dupe.ItemFingerprints;
import io.github.novablaze.novaguard.items.EnchantmentRules.Entry;
import io.github.novablaze.novaguard.items.EnchantmentRules.Fix;
import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.BundleContents;
import io.papermc.paper.datacomponent.item.ItemContainerContents;
import io.papermc.paper.datacomponent.item.ItemEnchantments;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Finds and fixes items with illegal enchantments: levels above the maximum (Sharpness 6, Sharpness 10),
 * enchantments that do not belong on the item (Sharpness on a pickaxe) and conflicting pairs
 * (Sharpness + Smite).
 *
 * <p>How it knows what is legal: it asks Minecraft itself. Every enchantment in the server's registry knows
 * its maximum level ({@link Enchantment#getMaxLevel()}), which items it may go on
 * ({@link Enchantment#canEnchantItem(ItemStack)}, the same list an anvil uses) and which enchantments it
 * conflicts with ({@link Enchantment#conflictsWith(Enchantment)}). So there is no hand-written table that
 * can go out of date when Minecraft adds an item or a datapack adds an enchantment.
 *
 * <p>The decision itself is made by {@link EnchantmentRules}; this class only reads and writes items.
 */
public final class EnchantmentGuard implements Listener, Runnable {

    /** Shulker in a bundle in a shulker...: vanilla allows little nesting, so a few levels cover it. */
    private static final int MAX_DEPTH = 3;
    public static final String BYPASS_PERMISSION = "novaguard.enchant.bypass";

    private final NovaGuardPlugin plugin;

    public EnchantmentGuard(NovaGuardPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------------- when to look

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Two seconds later, like the anti-dupe scan, so the join itself stays fast.
        Bukkit.getScheduler().runTaskLater(plugin, () -> guarded(() -> {
            if (player.isOnline()) {
                scanInventory(player, player.getInventory(), "inventory");
                scanInventory(player, player.getEnderChest(), "ender chest");
            }
        }), 40L);
    }

    /** A chest, barrel, shulker... is fixed when opened, before the player can take an illegal item out. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && ItemFingerprints.isStorage(event.getInventory())) {
            guarded(() -> scanInventory(player, event.getInventory(), "opened container"));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Inventory top = event.getInventory();
        // Next tick: the cursor item and crafting grid have been given back by then.
        Bukkit.getScheduler().runTask(plugin, () -> guarded(() -> {
            if (player.isOnline()) {
                scanInventory(player, player.getInventory(), "inventory");
                if (ItemFingerprints.isStorage(top)) {
                    scanInventory(player, top, "closed container");
                }
            }
        }));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        guarded(() -> {
            if (!active(player)) {
                return;
            }
            Item entity = event.getItem();
            ItemStack stack = entity.getItemStack();
            List<String> report = new ArrayList<>();
            if (fix(stack, report, 0)) {
                entity.setItemStack(stack);
                alert(player, stack, "picked up", report);
            }
        });
    }

    /** Switching to a hotbar slot: the weapon is fixed before it can be used. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        guarded(() -> fixSlot(player, player.getInventory(), event.getNewSlot(), "hotbar"));
    }

    /**
     * The last line of defence between scans: a hit with an illegal weapon is cancelled and the weapon fixed,
     * and a player hit while wearing illegal armour has the armour fixed.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        guarded(() -> {
            if (event.getDamager() instanceof Player attacker && active(attacker)) {
                int slot = attacker.getInventory().getHeldItemSlot();
                if (fixSlot(attacker, attacker.getInventory(), slot, "main hand")) {
                    event.setCancelled(true);
                }
            }
            if (event.getEntity() instanceof Player victim && active(victim)) {
                fixArmour(victim);
            }
        });
    }

    /** Regular scan of everyone online, two players per tick so a full server never lags. */
    @Override
    public void run() {
        if (!plugin.settings().enchantGuardEnabled) {
            return;
        }
        int index = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            long delay = 1 + index++ / 2;
            Bukkit.getScheduler().runTaskLater(plugin, () -> guarded(() -> {
                if (player.isOnline()) {
                    scanInventory(player, player.getInventory(), "inventory");
                    scanInventory(player, player.getEnderChest(), "ender chest");
                }
            }), delay);
        }
    }

    // ---------------------------------------------------------------- scanning

    /** Fixes every illegal item in one inventory. Returns how many items were changed. */
    public int scanInventory(Player owner, Inventory inventory, String where) {
        if (inventory == null || !active(owner)) {
            return 0;
        }
        int changed = 0;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (fixSlot(owner, inventory, slot, where)) {
                changed++;
            }
        }
        return changed;
    }

    private boolean fixSlot(Player owner, Inventory inventory, int slot, String where) {
        if (!active(owner)) {
            return false;
        }
        ItemStack item = inventory.getItem(slot);
        List<String> report = new ArrayList<>();
        if (!fix(item, report, 0)) {
            return false;
        }
        inventory.setItem(slot, item);
        alert(owner, item, where, report);
        return true;
    }

    private void fixArmour(Player player) {
        EntityEquipment equipment = player.getEquipment();
        for (EquipmentSlot slot : new EquipmentSlot[] {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFF_HAND}) {
            ItemStack item = equipment.getItem(slot);
            List<String> report = new ArrayList<>();
            if (fix(item, report, 0)) {
                equipment.setItem(slot, item);
                alert(player, item, "worn " + slot.name().toLowerCase(Locale.ROOT).replace('_', ' '), report);
            }
        }
    }

    // ---------------------------------------------------------------- fixing one item

    /**
     * Fixes one item in place, including items inside a shulker box or bundle.
     *
     * @param report receives one line per fix, e.g. "sharpness 10 removed (level 10 > max 5)"
     * @return true when the item was changed
     */
    boolean fix(ItemStack item, List<String> report, int depth) {
        if (item == null || item.isEmpty() || depth > MAX_DEPTH) {
            return false;
        }
        // Enchanted books keep their enchantments as "stored" ones; any enchantment may sit on a book.
        boolean changed = fixComponent(item, DataComponentTypes.ENCHANTMENTS, false, report);
        changed |= fixComponent(item, DataComponentTypes.STORED_ENCHANTMENTS, true, report);
        changed |= fixContainer(item, report, depth);
        changed |= fixBundle(item, report, depth);
        return changed;
    }

    private boolean fixComponent(ItemStack item, DataComponentType.Valued<ItemEnchantments> type, boolean stored,
                                 List<String> report) {
        ItemEnchantments data = item.getData(type);
        if (data == null || data.enchantments().isEmpty()) {
            return false;
        }
        Map<String, Enchantment> byKey = new HashMap<>();
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<Enchantment, Integer> e : data.enchantments().entrySet()) {
            Enchantment enchantment = e.getKey();
            String key = key(enchantment);
            byKey.put(key, enchantment);
            boolean fits = stored || item.getType() == Material.ENCHANTED_BOOK || enchantment.canEnchantItem(item);
            entries.add(new Entry(key, e.getValue(), enchantment.getMaxLevel(), fits));
        }
        List<Fix> fixes = EnchantmentRules.judge(entries,
            (a, b) -> byKey.get(a).conflictsWith(byKey.get(b)), plugin.settings().enchantPolicy);
        if (fixes.isEmpty()) {
            return false;
        }
        Map<Enchantment, Integer> result = new LinkedHashMap<>(data.enchantments());
        for (Fix fix : fixes) {
            Enchantment enchantment = byKey.get(fix.key());
            String name = EnchantmentRules.shortName(fix.key()) + " " + data.enchantments().get(enchantment);
            if (fix.removes()) {
                result.remove(enchantment);
                report.add(name + " removed (" + fix.reason() + ")");
            } else {
                result.put(enchantment, fix.newLevel());
                report.add(name + " -> " + fix.newLevel() + " (" + fix.reason() + ")");
            }
        }
        if (result.isEmpty()) {
            item.unsetData(type);
        } else {
            item.setData(type, ItemEnchantments.itemEnchantments(result));
        }
        return true;
    }

    private boolean fixContainer(ItemStack holder, List<String> report, int depth) {
        ItemContainerContents container = holder.getData(DataComponentTypes.CONTAINER);
        if (container == null) {
            return false;
        }
        List<ItemStack> inner = new ArrayList<>(container.contents());
        if (!fixAll(inner, report, depth, holder)) {
            return false;
        }
        holder.setData(DataComponentTypes.CONTAINER, ItemContainerContents.containerContents(inner));
        return true;
    }

    private boolean fixBundle(ItemStack holder, List<String> report, int depth) {
        BundleContents bundle = holder.getData(DataComponentTypes.BUNDLE_CONTENTS);
        if (bundle == null) {
            return false;
        }
        List<ItemStack> inner = new ArrayList<>(bundle.contents());
        if (!fixAll(inner, report, depth, holder)) {
            return false;
        }
        holder.setData(DataComponentTypes.BUNDLE_CONTENTS, BundleContents.bundleContents(inner));
        return true;
    }

    private boolean fixAll(List<ItemStack> items, List<String> report, int depth, ItemStack holder) {
        boolean changed = false;
        String inside = "inside " + typeName(holder) + ": ";
        for (int i = 0; i < items.size(); i++) {
            ItemStack copy = items.get(i) == null ? null : items.get(i).clone();
            List<String> innerReport = new ArrayList<>();
            if (fix(copy, innerReport, depth + 1)) {
                items.set(i, copy);
                innerReport.forEach(line -> report.add(inside + typeName(copy) + " " + line));
                changed = true;
            }
        }
        return changed;
    }

    // ---------------------------------------------------------------- helpers

    /** Only players who can hold items in survival are checked; the bypass permission is for event staff. */
    private boolean active(Player player) {
        return plugin.settings().enchantGuardEnabled && player.isOnline()
            && !player.hasPermission(BYPASS_PERMISSION);
    }

    private void alert(Player player, ItemStack item, String where, List<String> report) {
        plugin.violations().illegalItemAlert(player.getName(),
            typeName(item) + " in " + where + ": " + String.join("; ", report));
    }

    private void guarded(Runnable task) {
        plugin.safety().run("illegal-enchant", task);
    }

    @SuppressWarnings("deprecation") // Keyed#getKey is the stable way to read a registry key on every Paper version.
    private static String key(Enchantment enchantment) {
        return enchantment.getKey().toString();
    }

    private static String typeName(ItemStack item) {
        return item.getType().name().toLowerCase(Locale.ROOT);
    }
}
