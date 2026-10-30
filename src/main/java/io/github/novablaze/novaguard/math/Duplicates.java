package io.github.novablaze.novaguard.math;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** Finds ids that appear more than once. */
public final class Duplicates {

    private Duplicates() {
    }

    public static Set<String> find(Collection<String> ids) {
        Set<String> seen = new HashSet<>();
        Set<String> repeated = new HashSet<>();
        for (String id : ids) {
            if (!seen.add(id)) {
                repeated.add(id);
            }
        }
        return repeated;
    }
}
