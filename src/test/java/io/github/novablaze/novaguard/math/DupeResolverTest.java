package io.github.novablaze.novaguard.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.novablaze.novaguard.math.DupeResolver.Copy;
import io.github.novablaze.novaguard.math.DupeResolver.Verdict;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DupeResolverTest {

    private static final UUID DUPER = UUID.randomUUID();
    private static final UUID BUYER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    @Test
    void singleCopyIsNeverTouched() {
        Verdict<String> v = DupeResolver.resolve(List.of(new Copy<>(DUPER, "a")), null);
        assertTrue(v.remove().isEmpty());
    }

    @Test
    void oneOwnerKeepsExactlyOneCopy() {
        Verdict<String> v = DupeResolver.resolve(List.of(
            new Copy<>(DUPER, "inventory"), new Copy<>(DUPER, "ender chest"), new Copy<>(DUPER, "shulker")), null);
        assertEquals(List.of("ender chest", "shulker"), v.remove());
        assertFalse(v.ownerUnclear());
    }

    @Test
    void buyerWhoReceivedItKeepsItAndDuperLosesTheCopy() {
        Verdict<String> v = DupeResolver.resolve(List.of(
            new Copy<>(DUPER, "duper sword"), new Copy<>(BUYER, "buyer sword")), BUYER);
        assertEquals(List.of("duper sword"), v.remove());
        assertFalse(v.ownerUnclear());
    }

    @Test
    void duperLosesEveryCopyWhenTheBuyerReceivedIt() {
        Verdict<String> v = DupeResolver.resolve(List.of(
            new Copy<>(DUPER, "d1"), new Copy<>(DUPER, "d2"), new Copy<>(BUYER, "b1")), BUYER);
        assertEquals(List.of("d1", "d2"), v.remove());
    }

    @Test
    void unknownReceiverNeverTakesAPlayersOnlyCopy() {
        Verdict<String> v = DupeResolver.resolve(List.of(
            new Copy<>(DUPER, "d1"), new Copy<>(DUPER, "d2"), new Copy<>(OTHER, "o1")), null);
        assertEquals(List.of("d2"), v.remove());
        assertTrue(v.ownerUnclear());
    }

    @Test
    void receiverWhoIsNotAmongTheOwnersCountsAsUnknown() {
        Verdict<String> v = DupeResolver.resolve(List.of(
            new Copy<>(DUPER, "d1"), new Copy<>(OTHER, "o1")), BUYER);
        assertTrue(v.remove().isEmpty());
        assertTrue(v.ownerUnclear());
    }
}
