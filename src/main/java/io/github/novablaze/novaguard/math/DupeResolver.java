package io.github.novablaze.novaguard.math;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Decides which copies of a duplicated item to delete ("undupe") without hurting innocent players.
 * Pure logic, unit-tested.
 *
 * <p>The rules:
 * <ol>
 *   <li><b>One owner holds several copies:</b> keep one, delete the rest. The owner still has the item they
 *       had before the dupe, so nobody loses anything real.</li>
 *   <li><b>Several owners, and one of them received the item in a normal way</b> (picked it up, or took it
 *       out of a chest): that receiver keeps it. The others gave the item away and still have it, which is
 *       only possible by duping, so their copies are deleted. This is the common "dupe and sell" case: the
 *       buyer keeps the item, the duper loses the copy.</li>
 *   <li><b>Several owners and no known receiver:</b> nobody can tell who duped, so only the extra copies
 *       inside each owner's own items are deleted, and staff are alerted.</li>
 * </ol>
 */
public final class DupeResolver {

    /** One copy of the item, owned by {@code owner}; {@code ref} is the caller's handle to delete it. */
    public record Copy<T>(UUID owner, T ref) {
    }

    /**
     * @param copies   all copies found, in priority order per owner (the first copy of an owner is kept)
     * @param receiver the player who last received this item in a normal way, or null if unknown
     */
    public record Verdict<T>(List<T> remove, boolean ownerUnclear, String reason) {
    }

    private DupeResolver() {
    }

    public static <T> Verdict<T> resolve(List<Copy<T>> copies, UUID receiver) {
        Map<UUID, List<T>> byOwner = new LinkedHashMap<>();
        for (Copy<T> copy : copies) {
            byOwner.computeIfAbsent(copy.owner(), k -> new ArrayList<>()).add(copy.ref());
        }
        List<T> remove = new ArrayList<>();
        if (byOwner.size() <= 1) {
            for (List<T> own : byOwner.values()) {
                remove.addAll(own.subList(1, own.size()));
            }
            return new Verdict<>(remove, false, "copies held by one player: kept one");
        }
        if (receiver != null && byOwner.containsKey(receiver)) {
            for (Map.Entry<UUID, List<T>> entry : byOwner.entrySet()) {
                List<T> own = entry.getValue();
                remove.addAll(entry.getKey().equals(receiver) ? own.subList(1, own.size()) : own);
            }
            return new Verdict<>(remove, false, "kept by the player who received it normally");
        }
        for (List<T> own : byOwner.values()) {
            remove.addAll(own.subList(1, own.size()));
        }
        return new Verdict<>(remove, true, "held by several players and nobody can tell who duped");
    }
}
