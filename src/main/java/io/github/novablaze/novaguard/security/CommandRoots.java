package io.github.novablaze.novaguard.security;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Finds every command a command line would really run. "/execute as @a run op alt1" runs "execute" AND "op", so
 * checking only the first word would let a backdoor hide behind /execute. Pure text work: safe to unit test.
 */
public final class CommandRoots {

    private CommandRoots() {
    }

    /** "minecraft:op Bob" -> [op]; "execute as @a run whitelist add x" -> [execute, whitelist]. */
    public static Set<String> of(String commandLine) {
        Set<String> roots = new LinkedHashSet<>();
        if (commandLine == null) {
            return roots;
        }
        String[] words = commandLine.trim().split("\\s+");
        boolean next = true;
        for (String raw : words) {
            String word = raw.toLowerCase(Locale.ROOT);
            if (next) {
                while (word.startsWith("/")) {
                    word = word.substring(1);
                }
                int colon = word.indexOf(':');
                if (colon >= 0) {
                    word = word.substring(colon + 1); // minecraft:op, luckperms:lp
                }
                if (!word.isEmpty()) {
                    roots.add(word);
                }
                next = false;
            } else if (word.equals("run")) {
                next = true; // /execute ... run <command>
            }
        }
        return roots;
    }
}
