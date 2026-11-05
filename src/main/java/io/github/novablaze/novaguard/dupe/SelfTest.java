package io.github.novablaze.novaguard.dupe;

import io.github.novablaze.novaguard.NovaGuardPlugin;
import io.github.novablaze.novaguard.items.ItemSanitizer;
import io.github.novablaze.novaguard.items.ItemTree;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.BundleContents;
import io.papermc.paper.datacomponent.item.ItemContainerContents;
import io.github.novablaze.novaguard.ban.BanManager;
import io.github.novablaze.novaguard.cleanup.WorldCleaner;
import io.papermc.paper.ban.BanListType;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.ban.IpBanList;
import org.bukkit.ban.ProfileBanList;
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
        check("Staff copies (creative clones) are kept and get new IDs instead of being removed", this::staffKeep);
        check("Creative clones lose their hidden ID (also inside shulkers)", this::stripIds);
        check("Unban really lifts an account ban and an IP ban (fake test account)", this::unbanWorks);
        if (plugin.settings().illegalEnabled) {
            check("/cleanallexploits: preview changes nothing, confirm fixes only the illegal item", this::cleanup);
        }
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

    private String staffKeep() {
        ItemStack elytra = tagged(ItemStack.of(Material.ELYTRA));
        Inventory inventory = Bukkit.createInventory(null, 27);
        inventory.setItem(0, elytra);
        inventory.setItem(1, elytra.clone());
        inventory.setItem(4, shulker(List.of(elytra.clone())));
        List<ItemFingerprints.Found> found = fingerprints.collectForTest(inventory);
        if (found.size() != 3) {
            return "found " + found.size() + " copies instead of 3";
        }
        fingerprints.renewForTest(new HashSet<>(found.subList(1, 3)));
        List<ItemFingerprints.Found> after = fingerprints.collectForTest(inventory);
        if (inventory.getItem(1) == null || inventory.getItem(1).getType() != Material.ELYTRA) {
            return "the second elytra was removed instead of kept";
        }
        List<ItemStack> inside = ItemTree.children(inventory.getItem(4));
        if (inside == null || inside.isEmpty() || inside.get(0).getType() != Material.ELYTRA) {
            return "the elytra inside the shulker was removed instead of kept";
        }
        java.util.Set<String> distinct = new HashSet<>();
        after.forEach(f -> distinct.add(f.id()));
        return distinct.size() == after.size() ? null : "copies still share an ID after renewing";
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
        ItemStack stripped = fingerprints.ids().strip(box);
        Inventory inventory = Bukkit.createInventory(null, 9);
        inventory.setItem(0, stripped);
        inventory.setItem(1, fingerprints.ids().strip(elytra));
        int left = fingerprints.collectForTest(inventory).size();
        return left == 0 ? null : left + " IDs left";
    }

    /** Bans a made-up account and a documentation-only IP (203.0.113.x), lifts both, and cleans up. */
    private String unbanWorks() {
        UUID fake = new UUID(0x4E6F7661L, UUID.randomUUID().getLeastSignificantBits());
        ProfileBanList profiles = Bukkit.getBanList(BanListType.PROFILE);
        IpBanList ips = Bukkit.getBanList(BanListType.IP);
        PlayerProfile profile = Bukkit.createProfile(fake, "NgSelfTest");
        InetAddress address;
        try {
            address = InetAddress.getByName("203.0.113.77");
        } catch (java.net.UnknownHostException e) {
            return "could not build the test IP: " + e;
        }
        try {
            profiles.addBan(profile, "NovaGuard self-test", Duration.ofMinutes(1), "NovaGuard");
            ips.addBan(address, "NovaGuard self-test", Duration.ofMinutes(1), "NovaGuard");
            if (!profiles.isBanned(profile) || !ips.isBanned(address)) {
                return "the test bans were not applied";
            }
            BanManager.pardonAccount(fake, "NgSelfTest");
            ips.pardon(address);
            if (profiles.isBanned(profile)) {
                return "the account is still banned after unban";
            }
            return ips.isBanned(address) ? "the IP is still banned after unban" : null;
        } finally {
            profiles.pardon(profile);
            ips.pardon(address);
        }
    }

    private String cleanup() {
        ItemStack sword = ItemStack.of(Material.NETHERITE_SWORD);
        sword.addUnsafeEnchantment(Enchantment.SHARPNESS, 10);
        sword.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
        ItemStack mace = ItemStack.of(Material.MACE);
        mace.addUnsafeEnchantment(Enchantment.BREACH, Enchantment.BREACH.getMaxLevel());
        mace.addUnsafeEnchantment(Enchantment.WIND_BURST, Enchantment.WIND_BURST.getMaxLevel());
        Inventory inventory = Bukkit.createInventory(null, 9);
        inventory.setItem(0, sword);
        inventory.setItem(4, mace.clone());

        WorldCleaner cleaner = plugin.cleaner();
        int previewed = cleaner.cleanInventory(inventory, "test", WorldCleaner.newReport(false), null);
        if (previewed != 1) {
            return "preview found " + previewed + " illegal items instead of 1";
        }
        if (inventory.getItem(0).getEnchantmentLevel(Enchantment.SHARPNESS) != 10) {
            return "the preview changed an item";
        }
        int fixed = cleaner.cleanInventory(inventory, "test", WorldCleaner.newReport(true), null);
        ItemStack after = inventory.getItem(0);
        if (fixed != 1 || after.getEnchantmentLevel(Enchantment.SHARPNESS) != Enchantment.SHARPNESS.getMaxLevel()) {
            return "the sword was not fixed (fixed=" + fixed + ")";
        }
        if (after.getEnchantmentLevel(Enchantment.UNBREAKING) != 3) {
            return "a valid enchantment (Unbreaking 3) was removed";
        }
        if (!inventory.getItem(4).equals(mace)) {
            return "the legal mace was changed";
        }
        return null;
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
