package io.github.novablaze.novaguard.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DuplicatesTest {

    @Test
    void findsRepeatedIds() {
        assertEquals(Set.of("b"), Duplicates.find(List.of("a", "b", "c", "b")));
    }

    @Test
    void noRepeatsMeansEmpty() {
        assertTrue(Duplicates.find(List.of("a", "b")).isEmpty());
    }
}
