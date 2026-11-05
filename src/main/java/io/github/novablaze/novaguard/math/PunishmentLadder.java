package io.github.novablaze.novaguard.math;

import java.util.List;

/**
 * Ban length grows with every ban NovaGuard gave the same player before (e.g. 3, then 10, then 30 days).
 * An IP ban is only ever proposed to an admin once the player keeps cheating after earlier bans.
 */
public final class PunishmentLadder {

    private PunishmentLadder() {
    }

    /**
     * @param banDays ban length in days for the 1st, 2nd, 3rd... ban; the last value repeats. 0 = permanent.
     * @param previousBans how many times NovaGuard banned this player before
     */
    public static long banDays(List<Long> banDays, int previousBans) {
        if (banDays.isEmpty()) {
            return 30;
        }
        int index = Math.min(Math.max(0, previousBans), banDays.size() - 1);
        return Math.max(0, banDays.get(index));
    }

    /**
     * @param banNumber 1 for the first ban, 2 for the second...
     * @param ipFromBan the first ban number that asks an admin about an IP ban (0 = never ask)
     */
    public static boolean asksForIpBan(int banNumber, int ipFromBan) {
        return ipFromBan > 0 && banNumber >= ipFromBan;
    }

    /** "1st", "2nd", "3rd", "4th"... */
    public static String ordinal(int n) {
        int mod100 = n % 100;
        if (mod100 >= 11 && mod100 <= 13) {
            return n + "th";
        }
        return switch (n % 10) {
            case 1 -> n + "st";
            case 2 -> n + "nd";
            case 3 -> n + "rd";
            default -> n + "th";
        };
    }
}
