package io.github.novablaze.novaguard;

import io.github.novablaze.novaguard.ban.BanManager;
import io.github.novablaze.novaguard.ban.OffenseLog;
import io.github.novablaze.novaguard.monitor.MonitorManager;
import io.github.novablaze.novaguard.monitor.MonitorRecord;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * The anti-dupe and ban-undo commands of /novaguard: dupe, item, unban, review, selftest.
 * Kept apart from {@link NovaGuardCommand} so each file stays readable.
 */
final class ItemCommands {

    /** How a reply is sent (NovaGuardCommand's prefix and colors). */
    interface Reply {
        void send(CommandSender sender, String text, NamedTextColor color);
    }

    private final NovaGuardPlugin plugin;
    private final Reply reply;

    ItemCommands(NovaGuardPlugin plugin, Reply reply) {
        this.plugin = plugin;
        this.reply = reply;
    }

    // ---------------------------------------------------------------- ng dupe

    /** /ng dupe &lt;player&gt; | pardon &lt;player&gt; | rollback | resume */
    void dupe(CommandSender sender, String[] args) {
        if (args.length < 2) {
            reply.send(sender, "Usage: /novaguard dupe <player> | dupe pardon <player> | dupe rollback | dupe resume",
                NamedTextColor.YELLOW);
            return;
        }
        long now = System.currentTimeMillis();
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (sub.equals("rollback") && args.length == 2) {
            plugin.serverState().markRollback(now, "marked by " + sender.getName());
            reply.send(sender, "OK: copies of items that existed before now are still removed, but nobody is warned"
                + " for them for the next " + plugin.settings().crashGraceMs / 3_600_000 + " hours. Use this after"
                + " restoring a world or player-data backup.", NamedTextColor.GREEN);
            return;
        }
        if (sub.equals("resume") && args.length == 2) {
            plugin.serverState().pauseStrikes(0);
            reply.send(sender, "Dupe warnings are active again.", NamedTextColor.GREEN);
            return;
        }
        boolean pardon = sub.equals("pardon");
        if (pardon && args.length < 3) {
            reply.send(sender, "Usage: /novaguard dupe pardon <player>", NamedTextColor.RED);
            return;
        }
        OfflinePlayer target = known(sender, pardon ? args[2] : args[1]);
        if (target == null) {
            return;
        }
        UUID uuid = target.getUniqueId();
        String name = target.getName();
        if (pardon) {
            plugin.offenses().clearStrikes(uuid);
            plugin.monitor().pardonItems(uuid);
            reply.send(sender, "Cleared the dupe warnings of " + name + ". Their earlier incidents no longer count"
                + " towards a ban.", NamedTextColor.GREEN);
            return;
        }
        showPlayer(sender, uuid, name, now);
    }

    private void showPlayer(CommandSender sender, UUID uuid, String name, long now) {
        Settings settings = plugin.settings();
        reply.send(sender, name + ": " + plugin.offenses().strikes(uuid) + "/" + settings.maxWarnings
            + " dupe warnings (more = ban). Played " + BanManager.playtimeHours(uuid) + " h. Next ban would be: "
            + plugin.bans().describeNextBan(uuid) + "." + (settings.punishSilent ? " [punishments in test mode]" : ""),
            NamedTextColor.AQUA);
        for (OffenseLog.Ban ban : plugin.offenses().bans(uuid)) {
            reply.send(sender, "  banned " + BanManager.time(ban.timeMs()) + " for "
                + (ban.days() == 0 ? "ever" : ban.days() + " days") + " (" + ban.kind() + ")", NamedTextColor.GRAY);
        }
        MonitorRecord record = plugin.monitor().get(uuid);
        if (record != null && record.itemWatch) {
            reply.send(sender, "  on the suspect list until " + BanManager.time(record.endMs) + ", "
                + record.strongItemIncidents() + " certain incident(s) (2 or more = ban when the watch ends)",
                NamedTextColor.GOLD);
            List<MonitorRecord.ItemEvent> events = record.itemEvents;
            for (MonitorRecord.ItemEvent event : events.subList(Math.max(0, events.size() - 5), events.size())) {
                reply.send(sender, "  " + BanManager.time(event.timeMs())
                    + (event.strong() ? " [certain] " : " [unknown origin] ") + event.text(), NamedTextColor.GRAY);
            }
        }
        if (now < plugin.serverState().strikesPausedUntilMs()) {
            reply.send(sender, "Dupe warnings are PAUSED until " + BanManager.time(plugin.serverState()
                .strikesPausedUntilMs()) + " (mass dupe). Resume: /ng dupe resume", NamedTextColor.RED);
        }
    }

    // ---------------------------------------------------------------- ng item

    /** /ng item [id]: the life of one item (held item when no ID is given). */
    void item(CommandSender sender, String[] args) {
        String id;
        if (args.length >= 2) {
            id = plugin.fingerprints().history().findId(args[1]);
            if (id == null) {
                reply.send(sender, "No item with an ID starting with '" + args[1] + "' was seen since the server started"
                    + " (give at least 4 characters, as shown in the alert).", NamedTextColor.RED);
                return;
            }
        } else if (sender instanceof Player player) {
            id = plugin.fingerprints().idOf(player.getInventory().getItemInMainHand());
            if (id == null) {
                reply.send(sender, "The item in your hand has no NovaGuard ID.", NamedTextColor.RED);
                return;
            }
        } else {
            reply.send(sender, "Usage: /novaguard item <id>   (the 8-character ID from a dupe alert)", NamedTextColor.YELLOW);
            return;
        }
        List<String> lines = plugin.fingerprints().history().of(id);
        reply.send(sender, "Item " + id + (lines.isEmpty() ? ": nothing recorded since the server started." : ":"),
            NamedTextColor.AQUA);
        lines.forEach(line -> reply.send(sender, "  " + line, NamedTextColor.GRAY));
    }

    // ---------------------------------------------------------------- ng unban

    /** /ng unban &lt;player&gt;: undoes a wrong ban completely, including its place in the ban ladder. */
    void unban(CommandSender sender, String[] args) {
        if (args.length < 2) {
            reply.send(sender, "Usage: /novaguard unban <player>   (also lifts the IPs/alts banned with them and"
                + " removes the ban from the history, so the next ban is not longer because of it)",
                NamedTextColor.YELLOW);
            return;
        }
        OfflinePlayer target = known(sender, args[1]);
        if (target != null) {
            reply.send(sender, "Unbanned: " + plugin.bans().unban(target.getUniqueId(), target.getName(),
                sender.getName()), NamedTextColor.GREEN);
        }
    }

    // ---------------------------------------------------------------- ng review

    /** /ng review: suspects, most suspicious first. */
    void review(CommandSender sender) {
        List<MonitorManager.ReviewEntry> list = plugin.monitor().reviewList();
        if (list.isEmpty()) {
            reply.send(sender, "Nobody is on the suspect list.", NamedTextColor.GREEN);
            return;
        }
        reply.send(sender, "Suspects, most suspicious first (spectate the top ones):", NamedTextColor.YELLOW);
        int shown = 0;
        for (MonitorManager.ReviewEntry entry : list) {
            if (++shown > 10) {
                reply.send(sender, "... and " + (list.size() - 10) + " more (/monitor list)", NamedTextColor.GRAY);
                break;
            }
            NamedTextColor color = entry.score() >= 6 || entry.certainItemIncidents() >= 2 ? NamedTextColor.RED
                : entry.score() >= 3 || entry.certainItemIncidents() == 1 ? NamedTextColor.GOLD : NamedTextColor.GREEN;
            reply.send(sender, String.format(Locale.ROOT, "%d. %s%s - %s (score %.1f), %d certain dupe(s), watched until %s",
                shown, entry.name(), entry.online() ? " [online]" : "", entry.level(), entry.score(),
                entry.certainItemIncidents(), BanManager.time(entry.endMs())), color);
            if (!entry.reasons().isEmpty()) {
                reply.send(sender, "     " + String.join("; ", entry.reasons()), NamedTextColor.GRAY);
            }
        }
    }

    // ---------------------------------------------------------------- ng selftest

    void selftest(CommandSender sender) {
        for (String line : plugin.fingerprints().selfTest()) {
            reply.send(sender, line, line.startsWith("FAIL") || line.contains("FAILED")
                ? NamedTextColor.RED : NamedTextColor.GREEN);
        }
    }

    // ---------------------------------------------------------------- helpers

    private OfflinePlayer known(CommandSender sender, String name) {
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(name);
        if (target == null || target.getName() == null) {
            reply.send(sender, name + " has never joined.", NamedTextColor.RED);
            return null;
        }
        return target;
    }
}
