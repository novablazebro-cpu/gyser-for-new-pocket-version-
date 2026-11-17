package io.github.novablaze.novaguard.evidence;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * A small, read-mostly reader and writer for Minecraft's NBT file format (the format of player save files).
 * Paper has no API to read the inventory of an offline player, so NovaGuard reads the save file itself.
 *
 * <p>Built to be safe on any file: it never trusts a length written in the file. The unpacked size is capped
 * (a "zip bomb" cannot fill the memory), nesting depth is capped, and every list/array length is checked against
 * the bytes that are really left before anything is allocated.
 *
 * <p>Values in Java: Byte, Short, Integer, Long, Float, Double, byte[], String, {@link ListTag}, Map (a compound,
 * keeps its key order), int[], long[]. Writing turns them back into exactly the same tags.
 */
public final class Nbt {

    /** A list tag: its element type is kept so an empty list is written back identically. */
    public record ListTag(byte elementType, List<Object> values) {
    }

    public static final class NbtException extends IOException {
        public NbtException(String message) {
            super(message);
        }
    }

    static final byte END = 0, BYTE = 1, SHORT = 2, INT = 3, LONG = 4, FLOAT = 5, DOUBLE = 6, BYTE_ARRAY = 7,
        STRING = 8, LIST = 9, COMPOUND = 10, INT_ARRAY = 11, LONG_ARRAY = 12;

    /** Minecraft itself refuses deeper files. */
    static final int MAX_DEPTH = 512;

    private Nbt() {
    }

    /** Reads a gzip-compressed file body (player .dat files are gzip). The root must be a compound. */
    public static Map<String, Object> readCompressed(byte[] data, long maxUnpackedBytes) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return readRoot(new Limited(in, maxUnpackedBytes));
        }
    }

    /** Reads an uncompressed body. */
    public static Map<String, Object> read(byte[] data) throws IOException {
        return readRoot(new Limited(new ByteArrayInputStream(data), data.length));
    }

    private static Map<String, Object> readRoot(Limited limited) throws IOException {
        DataInputStream in = new DataInputStream(limited);
        byte type = in.readByte();
        if (type != COMPOUND) {
            throw new NbtException("root is not a compound (type " + type + ")");
        }
        in.readUTF(); // root name, usually empty
        return readCompound(in, limited, 0);
    }

    @SuppressWarnings("unchecked")
    private static Object readPayload(byte type, DataInputStream in, Limited limit, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new NbtException("nested too deep");
        }
        return switch (type) {
            case BYTE -> in.readByte();
            case SHORT -> in.readShort();
            case INT -> in.readInt();
            case LONG -> in.readLong();
            case FLOAT -> in.readFloat();
            case DOUBLE -> in.readDouble();
            case BYTE_ARRAY -> {
                int length = checkedLength(in.readInt(), 1, limit);
                byte[] bytes = new byte[length];
                in.readFully(bytes);
                yield bytes;
            }
            case STRING -> in.readUTF();
            case LIST -> {
                byte elementType = in.readByte();
                int length = in.readInt();
                if (elementType == END) {
                    if (length > 0) {
                        throw new NbtException("list of END tags with elements");
                    }
                    yield new ListTag(END, new ArrayList<>());
                }
                // Even the smallest element takes a byte (a compound at least its END byte).
                checkedLength(length, 1, limit);
                List<Object> values = new ArrayList<>(Math.min(length, 64));
                for (int i = 0; i < length; i++) {
                    values.add(readPayload(elementType, in, limit, depth + 1));
                }
                yield new ListTag(elementType, values);
            }
            case COMPOUND -> readCompound(in, limit, depth + 1);
            case INT_ARRAY -> {
                int length = checkedLength(in.readInt(), 4, limit);
                int[] ints = new int[length];
                for (int i = 0; i < length; i++) {
                    ints[i] = in.readInt();
                }
                yield ints;
            }
            case LONG_ARRAY -> {
                int length = checkedLength(in.readInt(), 8, limit);
                long[] longs = new long[length];
                for (int i = 0; i < length; i++) {
                    longs[i] = in.readLong();
                }
                yield longs;
            }
            default -> throw new NbtException("unknown tag type " + type);
        };
    }

    private static Map<String, Object> readCompound(DataInputStream in, Limited limit, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new NbtException("nested too deep");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        while (true) {
            byte type = in.readByte();
            if (type == END) {
                return map;
            }
            String name = in.readUTF();
            map.put(name, readPayload(type, in, limit, depth + 1));
        }
    }

    /** A length from the file is only believed if that many elements can still fit in the bytes that are left. */
    private static int checkedLength(int length, int bytesPerElement, Limited limit) throws NbtException {
        if (length < 0 || (long) length * bytesPerElement > limit.remaining()) {
            throw new NbtException("impossible length " + length);
        }
        return length;
    }

    // ---------------------------------------------------------------- writing

    public static byte[] writeCompressed(Map<String, Object> root) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            writeRoot(root, out);
        }
        return bytes.toByteArray();
    }

    public static byte[] write(Map<String, Object> root) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            writeRoot(root, out);
        }
        return bytes.toByteArray();
    }

    private static void writeRoot(Map<String, Object> root, DataOutputStream out) throws IOException {
        out.writeByte(COMPOUND);
        out.writeUTF("");
        writeCompound(root, out);
    }

    @SuppressWarnings("unchecked")
    private static void writeCompound(Map<String, Object> map, DataOutputStream out) throws IOException {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            out.writeByte(typeOf(entry.getValue()));
            out.writeUTF(entry.getKey());
            writePayload(entry.getValue(), out);
        }
        out.writeByte(END);
    }

    @SuppressWarnings("unchecked")
    private static void writePayload(Object value, DataOutputStream out) throws IOException {
        switch (value) {
            case Byte b -> out.writeByte(b);
            case Short s -> out.writeShort(s);
            case Integer i -> out.writeInt(i);
            case Long l -> out.writeLong(l);
            case Float f -> out.writeFloat(f);
            case Double d -> out.writeDouble(d);
            case byte[] bytes -> {
                out.writeInt(bytes.length);
                out.write(bytes);
            }
            case String s -> out.writeUTF(s);
            case ListTag list -> {
                out.writeByte(list.elementType());
                out.writeInt(list.values().size());
                for (Object element : list.values()) {
                    writePayload(element, out);
                }
            }
            case Map<?, ?> map -> writeCompound((Map<String, Object>) map, out);
            case int[] ints -> {
                out.writeInt(ints.length);
                for (int i : ints) {
                    out.writeInt(i);
                }
            }
            case long[] longs -> {
                out.writeInt(longs.length);
                for (long l : longs) {
                    out.writeLong(l);
                }
            }
            default -> throw new NbtException("cannot write " + value.getClass().getSimpleName());
        }
    }

    static byte typeOf(Object value) throws NbtException {
        return switch (value) {
            case Byte b -> BYTE;
            case Short s -> SHORT;
            case Integer i -> INT;
            case Long l -> LONG;
            case Float f -> FLOAT;
            case Double d -> DOUBLE;
            case byte[] bytes -> BYTE_ARRAY;
            case String s -> STRING;
            case ListTag list -> LIST;
            case Map<?, ?> map -> COMPOUND;
            case int[] ints -> INT_ARRAY;
            case long[] longs -> LONG_ARRAY;
            default -> throw new NbtException("cannot write " + value.getClass().getSimpleName());
        };
    }

    /** Stops reading after a fixed number of bytes, so a tiny packed file cannot unpack into gigabytes. */
    private static final class Limited extends InputStream {
        private final InputStream in;
        private long remaining;

        Limited(InputStream in, long max) {
            this.in = in;
            this.remaining = max;
        }

        long remaining() {
            return remaining;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                throw new NbtException("file unpacks to more than the allowed size");
            }
            int b = in.read();
            if (b < 0) {
                throw new EOFException();
            }
            remaining--;
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (remaining <= 0) {
                throw new NbtException("file unpacks to more than the allowed size");
            }
            int n = in.read(buffer, offset, (int) Math.min(length, remaining));
            if (n > 0) {
                remaining -= n;
            }
            return n;
        }
    }
}
