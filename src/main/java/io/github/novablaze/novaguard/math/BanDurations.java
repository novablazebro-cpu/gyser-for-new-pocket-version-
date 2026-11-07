package io.github.novablaze.novaguard.math;

import java.time.Duration;

/** Ban length arithmetic, kept free of Minecraft classes so it can be unit-tested. */
public final class BanDurations {

    private static final long DAY_MS = 86_400_000L;
    /** Shortest ban we hand to the server: a ban that is already over would do nothing. */
    private static final long MIN_MS = 60_000L;

    private BanDurations() {
    }

    /**
     * How long an IP ban approved now should last, so that it ends together with the account ban that
     * started at {@code bannedMs}. Without this, approving on day 29 of a 30-day ban would IP-ban for 30 more
     * days, long after the account itself is free again.
     *
     * @return the remaining time (at least one minute), or null for a permanent ban ({@code durationDays <= 0})
     */
    public static Duration remaining(long bannedMs, long durationDays, long nowMs) {
        if (durationDays <= 0) {
            return null;
        }
        long left = bannedMs + durationDays * DAY_MS - nowMs;
        return Duration.ofMillis(Math.max(MIN_MS, left));
    }
}
