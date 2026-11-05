package io.github.novablaze.novaguard.math;

/**
 * Decides who is to blame for a duplicated item and which copy survives. Undoing a dupe is always done
 * (exactly one copy is kept), but a player is only warned/punished when the evidence points at them alone.
 * When it could be someone else's fault, nobody is blamed.
 */
public final class DupeBlame {

    private DupeBlame() {
    }

    /**
     * @param blameScanned the scanned player gets a dupe warning
     * @param keepOwnCopy the surviving copy is the one in the player's own storage (else: the one in the container)
     * @param reason human readable, written to the alert
     */
    public record Decision(boolean blameScanned, boolean keepOwnCopy, String reason) {
    }

    /**
     * Copies of one item ID found in a single scan of one player.
     *
     * @param ownCopies copies in the player's inventory, ender chest, and shulkers/bundles inside them
     * @param containerCopies copies in the chest/barrel/... the player has open
     * @param knownOwnCopies copies the player is known to have got honestly: 1 if they already had the item at the
     *                       previous scan, plus copies they picked up or took out of containers since then
     * @param playerPutItInContainer this player put the item into that container recently (and still has a copy)
     * @param possibleRollback the item existed before the last server crash / rollback, which can create copies by itself
     * @param justRespawned the player respawned seconds ago: grave / death-chest plugins hand items back their own way
     */
    public static Decision sameScan(int ownCopies, int containerCopies, int knownOwnCopies,
                                    boolean playerPutItInContainer, boolean possibleRollback, boolean justRespawned) {
        if (possibleRollback) {
            return new Decision(false, ownCopies > 0,
                "the item existed before the last server crash/rollback, which can copy items by itself");
        }
        if (justRespawned) {
            return new Decision(false, ownCopies > 0,
                "the player just respawned (a grave / death-chest plugin may have given the items back twice)");
        }
        if (ownCopies >= 2) {
            if (knownOwnCopies >= 1 && ownCopies > knownOwnCopies) {
                // They had the item, and more copies of it appeared that did not come from anywhere.
                return new Decision(true, true, ownCopies + " copies of an item they already had");
            }
            // The copies arrived from someone else (picked up, taken from a chest, traded): that person duped.
            return new Decision(false, true, "the copies came from somewhere else (picked up / taken / traded)");
        }
        if (ownCopies == 1 && containerCopies >= 1) {
            if (playerPutItInContainer) {
                return new Decision(true, false, "they put the item into the container and still have it");
            }
            // Could be someone else's copy in a shared chest: undo, blame nobody.
            return new Decision(false, true, "one copy in their inventory, one in a container someone else may have filled");
        }
        return new Decision(false, false, "copies only inside a container (nobody can be blamed)");
    }

    /**
     * The same item ID held by two online players at once.
     *
     * @param releasedBy the player who last dropped / stored this item, or null when unknown
     * @param justRespawned players who respawned seconds ago (never blamed, see {@link #sameScan})
     * @return the player to blame (a or b), or null when nobody can be blamed
     */
    public static String crossPlayer(String a, String b, String releasedBy, boolean possibleRollback,
                                     java.util.Set<String> justRespawned) {
        if (possibleRollback || releasedBy == null || justRespawned.contains(releasedBy)) {
            return null;
        }
        // The player who gave the item away but still has it got their copy back by a rollback trick.
        if (releasedBy.equals(a)) {
            return a;
        }
        if (releasedBy.equals(b)) {
            return b;
        }
        return null;
    }
}
