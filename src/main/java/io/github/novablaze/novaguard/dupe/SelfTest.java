package io.github.novablaze.novaguard.dupe;

import io.github.novablaze.novaguard.NovaGuardPlugin;
import io.github.novablaze.novaguard.items.ItemSanitizer;
import io.github.novablaze.novaguard.items.ItemTree;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.BundleContents;
import io.papermc.paper.datacomponent.item.ItemContainerContents;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

/**
 * /ng selftest: runs the anti-dupe and illegal-item logic on test items inside the real server (no player needed)
 * and prints PASS/FAIL per case. It proves that fixing and undoing work on this exact server version.
 */
final class SelfTest {

    private final NovaGuardPlugin plugin;
    private final ItemFingerprints fingerprints;
    private final ItemSanitizer sanitizer;
    private final List<String> lines = new ArrayList<>();
    private int failures;

    SelfTest(NovaGuardPlugin plugin) {
        this.plugin = plugin;
        this.fingerprints = plugin.fingerprints();
        this.sanitizer = fingerprints.sanitizer();
    }

    static List<String> run(NovaGuardPlugin plugin) {
        SelfTest test = new SelfTest(plugin);
        test.all();
        return test.lines;
    }

    private void all() {
        if (!plugin.settings().illegalEnabled) {
            lines.add("illegal-items.enabled is false: the illegal item cases are skipped.");
        } else {
            check("Sharpness 10 sword becomes Sharpness " + Enchantment.SHARPNESS.getMaxLevel(), this::sharpness);
            check("Breach + Wind Burst mace is legal and untouched", this::breachWindBurst);
            check("Density + Breach mace loses one of them", this::densityBreach);
            check("Knockback 1000 stick loses the enchantment", this::knockbackStick);
            check("Enchanted book with Sharpness 10 becomes Sharpness " + Enchantment.SHARPNESS.getMaxLevel(), this::book);
            check("Illegal sword inside a shulker box is fixed", this::nestedSanitize);
        }
        check("Duplicated items inside a shulker box are removed, the original stays", this::shulkerUndo);
        check("Duplicated item inside a bundle is removed", this::bundleUndo);
        check("Creative clones lose their hidden ID (also inside shulkers)", this::stripIds);
        lines.add(failures == 0 ? "Self-test passed (" + lines.size() + " cases)."
            : "Self-test FAILED: " + failures + " case(s). Please report the lines above.");
    }

    private interface Case {
        String run();
    }

    /** A case returns null when it passed, otherwise what went wrong. */
    private void check(String name, Case body) {
        String problem;
        try {
            problem = body.run();
        } catch (RuntimeException | LinkageError e) {
            problem = "error " + e;
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Self-test case failed: " + name, e);
        }
        if (problem == null) {
            lines.add("PASS " + name);
        } else {
            failures++;
            lines.add("FAIL " + name + ": " + problem);
        }
    }

    private String sharpness() {
        ItemStack sword = ItemStack.of(Material.NETHERITE_SWORD);
        sword.addUnsafeEnchantment(Enchantment.SHARPNESS, 10);
        ItemStack fixed = sanitizer.sanitize(sword).item();
        int level = fixed.getEnchantmentLevel(Enchantment.SHARPNESS);
        return level == Enchantment.SHARPNESS.getMaxLevel() ? null : "level is " + level;
    }

    private String breachWindBurst() {
        ItemStack mace = ItemStack.of(Material.MACE);
        mace.addUnsafeEnchantment(Enchantment.BREACH, Enchantment.BREACH.getMaxLevel());
        mace.addUnsafeEnchantment(Enchantment.WIND_BURST, Enchantment.WIND_BURST.getMaxLevel());
        ItemSanitizer.Result result = sanitizer.sanitize(mace);
        return result.changed() ? "it was changed: " + result.changes() : null;
    }

    private String densityBreach() {
        ItemStack mace = ItemStack.of(Material.MACE);
        mace.addUnsafeEnchantment(Enchantment.DENSITY, 5);
        mace.addUnsafeEnchantment(Enchantment.BREACH, 4);
        ItemStack fixed = sanitizer.sanitize(mace).item();
        boolean density = fixed.getEnchantmentLevel(Enchantment.DENSITY) > 0;
        boolean breach = fixed.getEnchantmentLevel(Enchantment.BREACH) > 0;
        return density != breach ? null : "density=" + density + " breach=" + breach;
    }

    private String knockbackStick() {
        ItemStack stick = ItemStack.of(Material.STICK);
        stick.addUnsafeEnchantment(Enchantment.KNOCKBACK, 1000);
        ItemStack fixed = sanitizer.sanitize(stick).item();
        return fixed.getEnchantments().isEmpty() ? null : "still has " + fixed.getEnchantments();
    }

    private String book() {
        ItemStack book = ItemStack.of(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) book.getItemMeta();
        meta.addStoredEnchant(Enchantment.SHARPNESS, 10, true);
        book.setItemMeta(meta);
        ItemStack fixed = sanitizer.sanitize(book).item();
        int level = ((EnchantmentStorageMeta) fixed.getItemMeta()).getStoredEnchantLevel(Enchantment.SHARPNESS);
        return level == Enchantment.SHARPNESS.getMaxLevel() ? null : "level is " + level;
    }

    private String nestedSanitize() {
        ItemStack sword = ItemStack.of(Material.DIAMOND_SWORD);
        sword.addUnsafeEnchantment(Enchantment.SHARPNESS, 10);
        ItemStack box = shulker(List.of(ItemStack.empty(), sword));
        ItemStack fixed = sanitizer.sanitize(box).item();
        List<ItemStack> inside = ItemTree.children(fixed);
        if (inside == null || inside.size() < 2) {
            return "contents lost: " + inside;
        }
        int level = inside.get(1).getEnchantmentLevel(Enchantment.SHARPNESS);
        return level == Enchantment.SHARPNESS.getMaxLevel() ? null : "inner level is " + level;
    }

    private String shulkerUndo() {
        ItemStack elytra = tagged(ItemStack.of(Material.ELYTRA));
        Inventory inventory = Bukkit.createInventory(null, 27);
        inventory.setItem(0, elytra);
        inventory.setItem(4, shulker(List.of(ItemStack.of(Material.DIRT), elytra.clone(), ItemStack.empty(),
            elytra.clone())));
        List<ItemFingerprints.Found> found = fingerprints.collectForTest(inventory);
        if (found.size() != 3) {
            return "found " + found.size() + " copies instead of 3";
        }
        Set<ItemFingerprints.Found> extra = new HashSet<>(found.subList(1, 3));
        fingerprints.remove(extra);
        List<ItemFingerprints.Found> after = fingerprints.collectForTest(inventory);
        List<ItemStack> inside = ItemTree.children(inventory.getItem(4));
        if (after.size() != 1 || after.get(0).path().length != 1) {
            return "after undo " + after.size() + " copies are left";
        }
        if (inside == null || inside.isEmpty() || inside.get(0).getType() != Material.DIRT) {
            return "the other items in the shulker were damaged: " + inside;
        }
        return null;
    }

    private String bundleUndo() {
        ItemStack totem = tagged(ItemStack.of(Material.TOTEM_OF_UNDYING));
        ItemStack bundle = ItemStack.of(Material.BUNDLE);
        bundle.setData(DataComponentTypes.BUNDLE_CONTENTS, BundleContents.bundleContents(List.of(totem.clone())));
        Inventory inventory = Bukkit.createInventory(null, 9);
        inventory.setItem(0, totem);
        inventory.setItem(2, bundle);
        List<ItemFingerprints.Found> found = fingerprints.collectForTest(inventory);
        if (found.size() != 2) {
            return "found " + found.size() + " copies instead of 2";
        }
        fingerprints.remove(Set.of(found.get(1)));
        List<ItemFingerprints.Found> after = fingerprints.collectForTest(inventory);
        ItemStack bundleAfter = inventory.getItem(2);
        if (bundleAfter == null || bundleAfter.getType() != Material.BUNDLE) {
            return "the bundle itself was removed";
        }
        return after.size() == 1 && after.get(0).path().length == 1 ? null : after.size() + " copies left";
    }

    private String stripIds() {
        ItemStack elytra = tagged(ItemStack.of(Material.ELYTRA));
        ItemStack box = shulker(List.of(elytra));
        ItemStack stripped = fingerprints.stripIds(box);
        Inventory inventory = Bukkit.createInventory(null, 9);
        inventory.setItem(0, stripped);
        inventory.setItem(1, fingerprints.stripIds(elytra));
        int left = fingerprints.collectForTest(inventory).size();
        return left == 0 ? null : left + " IDs left";
    }

    private ItemStack tagged(ItemStack item) {
        return fingerprints.taggedForTest(item, UUID.randomUUID().toString());
    }

    private static ItemStack shulker(List<ItemStack> contents) {
        ItemStack box = ItemStack.of(Material.SHULKER_BOX);
        box.setData(DataComponentTypes.CONTAINER, ItemContainerContents.containerContents(contents));
        return box;
    }
}
