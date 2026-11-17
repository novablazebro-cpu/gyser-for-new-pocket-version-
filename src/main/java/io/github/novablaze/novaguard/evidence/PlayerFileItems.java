package io.github.novablaze.novaguard.evidence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.UUID;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds the items in a player save file (already read with {@link Nbt}) and turns each one into the bytes Paper's
 * {@code ItemStack.deserializeBytes} reads. Paper then upgrades the item from the file's game version and checks it
 * with the server's own rules, so NovaGuard never has to understand the item format itself.
 *
 * <p>Instead of trusting fixed key names (Mojang has moved armor and off-hand between versions), the whole file is
 * walked and every compound that looks like an item stack (a text "id" and a number "count") is taken. Items inside
 * an item (a shulker box) are not taken separately: the server finds those when it checks the outer item.
 */
public final class PlayerFileItems {

    /** One item from the file. {@code where} is readable: "inventory slot 3 (hotbar)", "ender chest slot 12". */
    public record FileItem(String where, byte[] paperBytes) {
    }

    /** A save file after reading: its items, or why it could not be read. */
    public record Parsed(UUID uuid, long savedMs, List<FileItem> items, String error) {
    }

    /** Real player files are a few KB; anything this big is not a normal player file. */
    static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
    static final long MAX_UNPACKED_BYTES = 64L * 1024 * 1024;

    /** Items a file can hold: inventory 41 + ender chest 27 + some extra; more means something is wrong. */
    static final int MAX_ITEMS = 512;

    private PlayerFileItems() {
    }

    /** Reads one save file. Never throws: a broken file comes back with {@code error} set. */
    public static Parsed parse(Path file) {
        UUID uuid = uuidOf(file);
        try {
            long size = Files.size(file);
            long saved = Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis();
            if (size > MAX_FILE_BYTES) {
                return new Parsed(uuid, saved, List.of(), "file is " + size / 1024 + " KB, too big for a player file");
            }
            byte[] bytes = Files.readAllBytes(file);
            List<FileItem> items =
                items(Nbt.readCompressed(bytes, MAX_UNPACKED_BYTES), true);
            return new Parsed(uuid, saved, items, null);
        } catch (IOException | RuntimeException e) {
            return new Parsed(uuid, 0, List.of(), e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    static UUID uuidOf(Path file) {
        String name = file.getFileName().toString();
        try {
            return UUID.fromString(name.substring(0, name.length() - ".dat".length()));
        } catch (RuntimeException e) {
            return new UUID(0, 0);
        }
    }

    /**
     * @param onlyWithData true = skip plain items (only "id" and "count"): they cannot carry an enchantment.
     */
    public static List<FileItem> items(Map<String, Object> root, boolean onlyWithData) throws IOException {
        Object version = root.get("DataVersion");
        if (!(version instanceof Integer dataVersion)) {
            throw new Nbt.NbtException("no DataVersion in the file");
        }
        List<FileItem> out = new ArrayList<>();
        walk(root, "", out, dataVersion, onlyWithData, 0);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void walk(Object value, String path, List<FileItem> out, int dataVersion, boolean onlyWithData,
                             int depth) throws IOException {
        if (depth > Nbt.MAX_DEPTH || out.size() >= MAX_ITEMS) {
            return;
        }
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> map = (Map<String, Object>) raw;
            if (isItem(map)) {
                if (!onlyWithData || hasData(map)) {
                    out.add(new FileItem(describe(path, map), toPaperBytes(map, dataVersion)));
                }
                return;
            }
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                walk(entry.getValue(), path.isEmpty() ? entry.getKey() : path + "." + entry.getKey(), out,
                    dataVersion, onlyWithData, depth + 1);
            }
        } else if (value instanceof Nbt.ListTag list) {
            for (int i = 0; i < list.values().size(); i++) {
                walk(list.values().get(i), path + "[" + i + "]", out, dataVersion, onlyWithData, depth + 1);
            }
        }
    }

    /**
     * An item stack: a text id plus a count ("count" since 1.20.5, "Count" before), item components, or an
     * inventory slot number. Entities (a parrot on the shoulder) have an id too, but none of the others.
     */
    static boolean isItem(Map<String, Object> map) {
        return map.get("id") instanceof String
            && (map.get("count") instanceof Number || map.get("Count") instanceof Number
                || map.get("components") instanceof Map || map.get("Slot") instanceof Number);
    }

    /** Anything beyond id, count and slot: components (1.20.5+) or tag (older). */
    static boolean hasData(Map<String, Object> map) {
        for (String key : map.keySet()) {
            if (!key.equals("id") && !key.equals("count") && !key.equals("Count") && !key.equals("Slot")) {
                return true;
            }
        }
        return false;
    }

    /** The item without its slot number, with the file's game version, packed the way Paper serializes items. */
    static byte[] toPaperBytes(Map<String, Object> item, int dataVersion) throws IOException {
        Map<String, Object> copy = new LinkedHashMap<>(item);
        copy.remove("Slot");
        copy.put("DataVersion", dataVersion);
        return Nbt.writeCompressed(copy);
    }

    /** "Inventory[3]" with Slot 3 becomes "inventory slot 3 (hotbar)"; unknown places keep their path. */
    static String describe(String path, Map<String, Object> item) {
        Integer slot = item.get("Slot") instanceof Number n ? n.intValue() : null;
        String top = path.contains("[") ? path.substring(0, path.indexOf('[')) : path;
        String lower = top.toLowerCase(Locale.ROOT);
        if (lower.equals("inventory") && slot != null) {
            return "inventory slot " + slot + inventoryArea(slot);
        }
        if (lower.equals("enderitems") && slot != null) {
            return "ender chest slot " + slot;
        }
        if (lower.startsWith("equipment.")) {
            return "worn/held: " + lower.substring("equipment.".length());
        }
        return path + (slot != null ? " slot " + slot : "");
    }

    private static String inventoryArea(int slot) {
        if (slot >= 0 && slot <= 8) {
            return " (hotbar)";
        }
        if (slot >= 100 && slot <= 103) {
            return " (armor)";
        }
        if (slot == -106) {
            return " (off hand)";
        }
        return "";
    }
}
