package io.github.novablaze.novaguard.evidence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvidenceTest {

    private static Map<String, Object> item(String id, int slot, boolean withData) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("count", 1);
        item.put("Slot", (byte) slot);
        if (withData) {
            Map<String, Object> enchants = new LinkedHashMap<>();
            enchants.put("minecraft:sharpness", 10);
            Map<String, Object> components = new LinkedHashMap<>();
            components.put("minecraft:enchantments", enchants);
            item.put("components", components);
        }
        return item;
    }

    private static Map<String, Object> playerFile() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("DataVersion", 4999);
        root.put("Pos", new Nbt.ListTag(Nbt.DOUBLE, new ArrayList<>(List.of(1.5, 64.0, -20.25))));
        root.put("Inventory", new Nbt.ListTag(Nbt.COMPOUND,
            new ArrayList<>(List.of(item("minecraft:diamond_sword", 3, true), item("minecraft:stick", 4, false),
                item("minecraft:diamond_helmet", 103, true)))));
        root.put("EnderItems", new Nbt.ListTag(Nbt.COMPOUND, new ArrayList<>(List.of(item("minecraft:book", 12, true)))));
        Map<String, Object> equipment = new LinkedHashMap<>();
        Map<String, Object> offhand = item("minecraft:shield", 0, true);
        offhand.remove("Slot");
        equipment.put("offhand", offhand);
        root.put("equipment", equipment);
        // A parrot on the shoulder has an id but is not an item.
        Map<String, Object> parrot = new LinkedHashMap<>();
        parrot.put("id", "minecraft:parrot");
        parrot.put("Health", 6.0f);
        root.put("ShoulderEntityLeft", parrot);
        root.put("Brain", new Nbt.ListTag(Nbt.END, new ArrayList<>()));
        root.put("ints", new int[] {1, 2, 3});
        root.put("longs", new long[] {4L});
        root.put("bytes", new byte[] {5});
        return root;
    }

    @Test
    void roundTripKeepsEveryTag() throws IOException {
        Map<String, Object> root = playerFile();
        Map<String, Object> back = Nbt.readCompressed(Nbt.writeCompressed(root), 1 << 20);
        assertEquals(root.keySet(), back.keySet());
        assertArrayEquals(Nbt.write(root), Nbt.write(back));
    }

    @Test
    void findsItemsWithDataAndNamesTheirPlace() throws IOException {
        List<PlayerFileItems.FileItem> items = PlayerFileItems.items(playerFile(), true);
        List<String> where = items.stream().map(PlayerFileItems.FileItem::where).toList();
        assertEquals(List.of("inventory slot 3 (hotbar)", "inventory slot 103 (armor)", "ender chest slot 12",
            "worn/held: offhand"), where);
    }

    @Test
    void plainItemsAreSkippedOnlyWhenAsked() throws IOException {
        assertEquals(5, PlayerFileItems.items(playerFile(), false).size());
    }

    @Test
    void itemBytesHaveNoSlotButTheFileVersion() throws IOException {
        PlayerFileItems.FileItem first = PlayerFileItems.items(playerFile(), true).get(0);
        Map<String, Object> item = Nbt.readCompressed(first.paperBytes(), 1 << 20);
        assertFalse(item.containsKey("Slot"));
        assertEquals(4999, item.get("DataVersion"));
        assertEquals("minecraft:diamond_sword", item.get("id"));
    }

    @Test
    void fileWithoutVersionIsRefused() {
        Map<String, Object> root = playerFile();
        root.remove("DataVersion");
        assertThrows(Nbt.NbtException.class, () -> PlayerFileItems.items(root, true));
    }

    @Test
    void lyingListLengthIsRefusedWithoutAllocating() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(Nbt.COMPOUND);
            out.writeUTF("");
            out.writeByte(Nbt.INT_ARRAY);
            out.writeUTF("huge");
            out.writeInt(Integer.MAX_VALUE);
        }
        assertThrows(Nbt.NbtException.class, () -> Nbt.read(bytes.toByteArray()));
    }

    @Test
    void zipBombIsRefused() throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("zeros", new byte[5_000_000]);
        byte[] packed = Nbt.writeCompressed(root);
        assertTrue(packed.length < 100_000);
        assertThrows(Nbt.NbtException.class, () -> Nbt.readCompressed(packed, 1_000_000));
    }

    @Test
    void deepNestingIsRefused() throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(raw))) {
            out.writeByte(Nbt.COMPOUND);
            out.writeUTF("");
            for (int i = 0; i < 2000; i++) {
                out.writeByte(Nbt.COMPOUND);
                out.writeUTF("a");
            }
        }
        assertThrows(Nbt.NbtException.class, () -> Nbt.readCompressed(raw.toByteArray(), 1 << 20));
    }

    @Test
    void truncatedFileIsRefused() throws IOException {
        byte[] whole = Nbt.write(playerFile());
        byte[] cut = java.util.Arrays.copyOf(whole, whole.length / 2);
        assertThrows(IOException.class, () -> Nbt.read(cut));
    }

    @Test
    void reportLinesCannotBeFaked() {
        assertEquals("Sword  PROOF | admin", ReportFile.clean("Sword\n\rPROOF | admin"));
        assertEquals("a b", ReportFile.clean("a b"));
        assertTrue(ReportFile.clean("x".repeat(5000)).length() <= ReportFile.MAX_LINE + 3);
        assertEquals("?", ReportFile.clean(null));
    }

    @Test
    void parseReadsARealFileAndReportsBrokenOnes(@TempDir Path dir) throws IOException {
        Path good = dir.resolve("12345678-1234-1234-1234-123456789abc.dat");
        Files.write(good, Nbt.writeCompressed(playerFile()));
        PlayerFileItems.Parsed parsed = PlayerFileItems.parse(good);
        assertEquals(null, parsed.error());
        assertEquals(4, parsed.items().size());
        assertEquals("12345678-1234-1234-1234-123456789abc", parsed.uuid().toString());

        Path bad = dir.resolve("12345678-1234-1234-1234-123456789abd.dat");
        Files.write(bad, new byte[] {1, 2, 3});
        assertTrue(PlayerFileItems.parse(bad).error() != null);
    }

    @Test
    void reportFileAppendsInOrder(@TempDir Path dir) throws IOException {
        ReportFile report = new ReportFile(dir, message -> { throw new AssertionError(message); });
        report.append(List.of("one", "two"));
        report.append(List.of("three"));
        report.shutdown();
        assertEquals(List.of("one", "two", "three"), Files.readAllLines(dir.resolve(ReportFile.NAME)));
    }
}
