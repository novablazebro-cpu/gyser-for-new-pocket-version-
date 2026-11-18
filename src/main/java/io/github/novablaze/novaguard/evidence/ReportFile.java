package io.github.novablaze.novaguard.evidence;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * plugins/NovaGuard/illegal-enchantments-report.txt: lines are appended on a background thread (the server never
 * waits for the disk). When the file passes {@link #MAX_BYTES} it is renamed to ...-old.txt and a new one starts,
 * so it can never fill the disk.
 */
public final class ReportFile {

    public static final String NAME = "illegal-enchantments-report.txt";
    static final long MAX_BYTES = 20L * 1024 * 1024;
    /** One line never gets longer than this (an item can carry a very long custom name). */
    static final int MAX_LINE = 600;

    private final Path file;
    private final Path old;
    private final String name;
    private final Consumer<String> warn;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "NovaGuard-evidence-report");
        thread.setDaemon(true);
        return thread;
    });

    public ReportFile(Path folder, Consumer<String> warn) {
        this(folder, NAME, warn);
    }

    /** A report with another file name (security.log uses the same safe writer). */
    public ReportFile(Path folder, String name, Consumer<String> warn) {
        this.name = name;
        this.file = folder.resolve(name);
        int dot = name.lastIndexOf('.');
        this.old = folder.resolve(dot < 0 ? name + "-old" : name.substring(0, dot) + "-old" + name.substring(dot));
        this.warn = warn;
    }

    public Path path() {
        return file;
    }

    /** Appends lines (already cleaned with {@link #clean}) in this order. */
    public void append(List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        List<String> copy = List.copyOf(lines);
        writer.execute(() -> {
            try {
                Files.createDirectories(file.getParent());
                // Never follow a link someone placed here: the report must not be written somewhere else.
                if (Files.isSymbolicLink(file)) {
                    warn.accept("Not writing " + name + ": it is a symbolic link.");
                    return;
                }
                if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) > MAX_BYTES) {
                    Files.move(file, old, StandardCopyOption.REPLACE_EXISTING);
                }
                try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)) {
                    for (String line : copy) {
                        out.write(line);
                        out.write(System.lineSeparator());
                    }
                }
            } catch (IOException | RuntimeException e) {
                warn.accept("Could not write " + name + ": " + e.getMessage());
            }
        });
    }

    /**
     * Makes text from a player or an item safe for one report line: line breaks and other control characters
     * become spaces (a custom item name cannot fake extra lines in the evidence), and the length is capped.
     */
    public static String clean(String text) {
        if (text == null) {
            return "?";
        }
        StringBuilder out = new StringBuilder(Math.min(text.length(), MAX_LINE));
        for (int i = 0; i < text.length() && out.length() < MAX_LINE; i++) {
            char c = text.charAt(i);
            boolean control = Character.isISOControl(c) || c == ' ' || c == ' '
                || Character.getType(c) == Character.FORMAT;
            out.append(control ? ' ' : c);
        }
        if (text.length() > MAX_LINE) {
            out.append("...");
        }
        return out.toString();
    }

    public void shutdown() {
        writer.shutdown();
        try {
            writer.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
