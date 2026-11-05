package io.github.novablaze.novaguard.dupe;

import io.github.novablaze.novaguard.NovaGuardPlugin;
import io.github.novablaze.novaguard.Settings;
import io.github.novablaze.novaguard.core.PlayerData;
import io.github.novablaze.novaguard.math.MassIncidentBreaker;
import io.github.novablaze.novaguard.math.StrikeList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * What happens to a player after a dupe or an illegal item was undone.
 *
 * <ul>
 *   <li><b>strong</b> evidence (the copy or the illegal change can only have come from this player): a warning
 *       "n/5", the player goes on the suspect list (watched 3 days), more than 5 warnings = ban at once, and a
 *       repeat during the watch = ban when the watch ends.</li>
 *   <li><b>weak</b> evidence (an illegal item of unknown origin): suspect list and a staff alert only, never a
 *       warning or a ban by itself.</li>
 * </ul>
 * Safety: staff (novaguard.dupe.exempt), server crashes/rollbacks and mass "dupes" by many players at once
 * (a rollback or another plugin copying items) never lead to warnings.
 */
public final class ItemPunisher {

    private final NovaGuardPlugin plugin;
    private final MassIncidentBreaker breaker = new MassIncidentBreaker();
    /** Test mode only: the warnings that would have been given (memory only, never used for a ban). */
    private final Map<UUID, StrikeList> testStrikes = new HashMap<>();

    public ItemPunisher(NovaGuardPlugin plugin) {
        this.plugin = plugin;
    }

    public void strong(Player player, String kind, String detail) {
        Settings settings = plugin.settings();
        String name = player.getName();
        plugin.violations().dupeAlert(name, kind + ": " + detail);
        if (!settings.punishEnabled) {
            return;
        }
        if (player.hasPermission("novaguard.dupe.exempt") || player.hasPermission("novaguard.bypass")) {
            plugin.violations().dupeAlert(name, "is exempt (novaguard.dupe.exempt): no warning");
            return;
        }
        PlayerData data = plugin.players().get(player);
        if (data.bedrock && !settings.punishBedrock) {
            plugin.monitor().itemIncident(player, kind + " (Bedrock, not punished): " + detail, false);
            return;
        }
        long now = System.currentTimeMillis();
        if (now < plugin.serverState().strikesPausedUntilMs()) {
            plugin.violations().dupeAlert(name, "dupe warnings are paused (mass dupe / rollback): no warning");
            plugin.monitor().itemIncident(player, kind + " (warnings paused): " + detail, false);
            return;
        }
        if (breaker.record(player.getUniqueId().toString(), now, settings.massDupePlayers, settings.massDupeWindowMs)) {
            trip(now);
            plugin.monitor().itemIncident(player, kind + " (part of a mass dupe, not punished): " + detail, false);
            return;
        }

        if (settings.punishSilent) {
            testMode(player, kind, detail, now);
            return;
        }
        boolean counted = plugin.offenses().addStrike(player.getUniqueId(), name, now, settings.strikeCooldownMs);
        plugin.monitor().itemIncident(player, kind + ": " + detail, counted);
        if (!counted) {
            // Same exploit seen again within the cooldown: undone, but it is still one warning.
            return;
        }
        int strikes = plugin.offenses().strikes(player.getUniqueId());
        if (strikes > settings.maxWarnings) {
            plugin.monitor().itemBan(player.getUniqueId(), strikes + " dupe/illegal-item warnings (more than "
                + settings.maxWarnings + ")");
            return;
        }
        player.sendMessage(MiniMessage.miniMessage().deserialize(settings.dupeWarnMessage,
            Placeholder.unparsed("count", Integer.toString(strikes)),
            Placeholder.unparsed("max", Integer.toString(settings.maxWarnings))));
        plugin.getLogger().warning(name + " got dupe warning " + strikes + "/" + settings.maxWarnings + " (" + kind + ")");
    }

    /** What would have happened, written down so the admin can check it before switching to enforce. */
    private void testMode(Player player, String kind, String detail, long now) {
        Settings settings = plugin.settings();
        StrikeList strikes = testStrikes.computeIfAbsent(player.getUniqueId(), k -> new StrikeList());
        boolean counted = strikes.add(now, settings.strikeCooldownMs);
        plugin.monitor().itemIncident(player, "[test mode] " + kind + ": " + detail, false);
        if (counted) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(settings.dupeRemovedMessage));
        }
        if (!counted) {
            return;
        }
        int n = strikes.count();
        String would = n > settings.maxWarnings
            ? "would BAN " + player.getName() + " (" + plugin.bans().describeNextBan(player.getUniqueId()) + ")"
            : "would give " + player.getName() + " warning " + n + "/" + settings.maxWarnings;
        plugin.violations().staffNote(player.getName(), "[test mode] " + would + " for " + kind
            + ". Nothing was done to the player (anti-dupe.punish.mode: silent).");
    }

    public void weak(Player player, String kind, String detail) {
        plugin.violations().dupeAlert(player.getName(), kind + " of unknown origin (watched, not warned): " + detail);
        if (plugin.settings().punishEnabled && plugin.settings().illegalSuspect
            && !player.hasPermission("novaguard.dupe.exempt") && !player.hasPermission("novaguard.bypass")) {
            plugin.monitor().itemIncident(player, kind + " (unknown origin): " + detail, false);
        }
    }

    /** Many different players "duped" at once: almost surely a rollback. Take the warnings back and pause. */
    private void trip(long now) {
        Settings settings = plugin.settings();
        long since = breaker.windowStartMs(now);
        breaker.clear();
        plugin.serverState().pauseStrikes(now + settings.massDupePauseMs);
        plugin.serverState().markRollback(now, "mass dupe: " + settings.massDupePlayers + "+ players within "
            + settings.massDupeWindowMs / 60_000 + " minutes");
        Map<UUID, Integer> refunded = plugin.offenses().refundSince(since);
        plugin.monitor().refundStrongSince(since);
        for (UUID uuid : refunded.keySet()) {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) {
                online.sendMessage(Component.text("[NovaGuard] Your last dupe warning was taken back: the server"
                    + " found a problem that was not your fault.", NamedTextColor.GREEN));
            }
        }
        String text = settings.massDupePlayers + " or more players had duplicated items within "
            + settings.massDupeWindowMs / 60_000 + " minutes. That is almost always a server rollback or another plugin"
            + " copying items, not cheaters. Duplicates are still removed, but dupe warnings are paused for "
            + settings.massDupePauseMs / 3_600_000 + " hours and " + refunded.size() + " player(s) got their warnings back."
            + " Resume early with: ng dupe resume";
        plugin.getLogger().severe("NovaGuard: " + text);
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("novaguard.admin")) {
                staff.sendMessage(Component.text("[NovaGuard] " + text, NamedTextColor.RED));
            }
        }
        plugin.notifier().send("NovaGuard: dupe warnings paused", text);
    }
}
