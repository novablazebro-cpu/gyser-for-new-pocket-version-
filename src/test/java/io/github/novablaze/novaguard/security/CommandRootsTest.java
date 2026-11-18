package io.github.novablaze.novaguard.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class CommandRootsTest {

    private static List<String> roots(String line) {
        return List.copyOf(CommandRoots.of(line));
    }

    @Test
    void plainCommand() {
        assertEquals(List.of("op"), roots("/op Herobrine"));
        assertEquals(List.of("whitelist"), roots("whitelist add alt1"));
    }

    @Test
    void namespaceIsIgnored() {
        assertEquals(List.of("op"), roots("/minecraft:op Herobrine"));
        assertEquals(List.of("lp"), roots("luckperms:lp user x permission set *"));
    }

    @Test
    void executeCannotHideTheRealCommand() {
        assertEquals(List.of("execute", "whitelist"), roots("execute as @a at @s run whitelist add alt1"));
        assertEquals(List.of("execute", "op"), roots("/execute if entity @p run execute run op alt2"));
    }

    @Test
    void caseAndSpacesDoNotMatter() {
        assertEquals(List.of("op"), roots("   /OP    Herobrine  "));
        assertEquals(List.of(), roots("   "));
        assertEquals(List.of(), roots(null));
    }
}
