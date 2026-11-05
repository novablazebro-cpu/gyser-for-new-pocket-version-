package io.github.novablaze.novaguard.dupe;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The recent life of every tagged item: who it was given to, who dropped it, which chest it went into, which
 * copy was removed. "ng item &lt;id&gt;" shows it, so staff can see WHO duped an item, not only who holds a copy.
 * Kept in memory only (the last items seen since the server started), so it costs no disk and no lag.
 */
public final class ItemHistory {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault());
    private static final int EVENTS_PER_ITEM = 15;
    private static final int MAX_ITEMS = 5_000;

    /** Access order: items nobody touched for the longest time are forgotten first. */
    private final Map<String, Deque<String>> events = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Deque<String>> eldest) {
            return size() > MAX_ITEMS;
        }
    };

    public void add(String id, String text) {
        Deque<String> list = events.computeIfAbsent(id, k -> new ArrayDeque<>());
        if (list.size() >= EVENTS_PER_ITEM) {
            list.removeFirst();
        }
        list.addLast(TIME.format(Instant.now()) + "  " + text);
    }

    /** The full ID of the first item whose ID starts with {@code prefix} (alerts show the first 8 characters). */
    public String findId(String prefix) {
        if (prefix.length() < 4) {
            return null;
        }
        for (String id : events.keySet()) {
            if (id.startsWith(prefix)) {
                return id;
            }
        }
        return null;
    }

    public List<String> of(String id) {
        Deque<String> list = events.get(id);
        return list == null ? List.of() : List.copyOf(list);
    }
}
