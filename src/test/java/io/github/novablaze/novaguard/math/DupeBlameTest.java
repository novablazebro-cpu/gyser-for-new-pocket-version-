package io.github.novablaze.novaguard.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class DupeBlameTest {

    @Test
    void copiesOfAnItemTheyAlreadyHadAreBlamed() {
        DupeBlame.Decision d = DupeBlame.sameScan(2, 0, 1, false, false, false);
        assertTrue(d.blameScanned());
        assertTrue(d.keepOwnCopy());
    }

    @Test
    void pickingUpTwoCopiesFromADuperIsNotBlamed() {
        // Innocent buyer: had nothing, picked up two copies a duper dropped.
        assertFalse(DupeBlame.sameScan(2, 0, 2, false, false, false).blameScanned());
        // Copies arrived through a trade plugin (not tracked): still not blamed.
        assertFalse(DupeBlame.sameScan(2, 0, 0, false, false, false).blameScanned());
    }

    @Test
    void pickedUpOneThenDupedItIsBlamed() {
        assertTrue(DupeBlame.sameScan(2, 0, 1, false, false, false).blameScanned());
        assertTrue(DupeBlame.sameScan(3, 0, 2, false, false, false).blameScanned());
    }

    @Test
    void crashRollbackIsNeverBlamed() {
        DupeBlame.Decision d = DupeBlame.sameScan(5, 1, 1, true, true, false);
        assertFalse(d.blameScanned());
    }

    @Test
    void chestDupeIsBlamedOnlyWhenTheyPutItIn() {
        DupeBlame.Decision theirs = DupeBlame.sameScan(1, 1, 1, true, false, false);
        assertTrue(theirs.blameScanned());
        assertFalse(theirs.keepOwnCopy());
        DupeBlame.Decision shared = DupeBlame.sameScan(1, 1, 1, false, false, false);
        assertFalse(shared.blameScanned());
        assertTrue(shared.keepOwnCopy());
    }

    @Test
    void copiesOnlyInAContainerBlameNobody() {
        assertFalse(DupeBlame.sameScan(0, 2, 0, false, false, false).blameScanned());
    }

    @Test
    void crossPlayerBlamesWhoeverGaveItAwayAndStillHasIt() {
        assertEquals("a", DupeBlame.crossPlayer("a", "b", "a", false, Set.of()));
        assertEquals("b", DupeBlame.crossPlayer("a", "b", "b", false, Set.of()));
        assertNull(DupeBlame.crossPlayer("a", "b", null, false, Set.of()));
        assertNull(DupeBlame.crossPlayer("a", "b", "c", false, Set.of()));
        assertNull(DupeBlame.crossPlayer("a", "b", "a", true, Set.of()));
    }

    @Test
    void graveOrDeathChestRestoreRightAfterRespawnIsNotBlamed() {
        DupeBlame.Decision d = DupeBlame.sameScan(2, 0, 1, false, false, true);
        assertFalse(d.blameScanned());
        assertTrue(d.keepOwnCopy());
        assertNull(DupeBlame.crossPlayer("a", "b", "a", false, Set.of("a")));
        assertEquals("a", DupeBlame.crossPlayer("a", "b", "a", false, Set.of("b")));
    }
}
