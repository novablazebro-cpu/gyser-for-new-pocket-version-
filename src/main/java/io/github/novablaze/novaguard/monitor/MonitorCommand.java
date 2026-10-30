package io.github.novablaze.novaguard.monitor;

import io.github.novablaze.novaguard.NovaGuardPlugin;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /monitor &lt;player&gt; [cracked|bedrock], /monitor stop|report &lt;player&gt;, /monitor list.
 * Works from the server console (e.g. the Pterodactyl panel console).
 */
public final class MonitorCommand implements BasicCommand {

    private static final List<String> TAGS = List.of("cracked", "bedrock");

    private final NovaGuardPlugin plugin;

    public MonitorCommand(NovaGuardPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        MonitorManager monitor = plugin.monitor();
        if (args.length == 0) {
            reply(sender, "Usage: /monitor <player> [cracked|bedrock] | /monitor stop <player> | "
                + "/monitor report <player> | /monitor list", NamedTextColor.YELLOW);
            return;
        }
        String first = args[0].toLowerCase(Locale.ROOT);
        if (first.equals("list") && args.length == 1) {
            list(sender, monitor);
            return;
        }
        if ((first.equals("stop") || first.equals("report")) && args.length == 2) {
            MonitorRecord record = monitor.find(args[1]);
            if (record == null) {
                reply(sender, args[1] + " is not being monitored.", NamedTextColor.RED);
            } else if (first.equals("stop")) {
                monitor.stop(record.uuid);
                reply(sender, "Stopped monitoring " + record.name + " (no report written).", NamedTextColor.GREEN);
            } else {
                String fileName = monitor.earlyReport(record);
                reply(sender, "Early report written: plugins/NovaGuard/reports/" + fileName, NamedTextColor.GREEN);
            }
            return;
        }
        start(sender, monitor, args);
    }

    private void start(CommandSender sender, MonitorManager monitor, String[] args) {
        String name = args[0];
        String tag = null;
        if (args.length >= 2) {
            tag = args[1].toLowerCase(Locale.ROOT);
            if (!TAGS.contains(tag)) {
                reply(sender, "The tag must be 'cracked' or 'bedrock' (or leave it out: the type is detected).",
                    NamedTextColor.RED);
                return;
            }
        }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            reply(sender, monitor.start(online.getUniqueId(), online.getName(), online, tag, sender.getName()),
                NamedTextColor.GREEN);
            return;
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached == null || cached.getName() == null) {
            reply(sender, name + " has never joined this server. Check the spelling.", NamedTextColor.RED);
            return;
        }
        reply(sender, monitor.start(cached.getUniqueId(), cached.getName(), null, tag, sender.getName()),
            NamedTextColor.GREEN);
    }

    private static void list(CommandSender sender, MonitorManager monitor) {
        if (monitor.all().isEmpty()) {
            reply(sender, "Nobody is being monitored.", NamedTextColor.YELLOW);
            return;
        }
        for (MonitorRecord record : monitor.all()) {
            String status = record.pendingKick ? "report done, kick on next join"
                : "report due " + MonitorManager.time(record.endMs);
            String type = record.bedrock == null ? "?" : record.bedrock ? "Bedrock" : "Java";
            reply(sender, record.name + " [" + type + "] - " + status + ", played "
                + (record.playtimeMs / 60000) + " min so far", NamedTextColor.AQUA);
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length <= 1) {
            String start = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            for (String word : List.of("list", "stop", "report")) {
                if (word.startsWith(start)) {
                    out.add(word);
                }
            }
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(start)) {
                    out.add(player.getName());
                }
            }
            return out;
        }
        if (args.length == 2) {
            String first = args[0].toLowerCase(Locale.ROOT);
            String start = args[1].toLowerCase(Locale.ROOT);
            if (first.equals("stop") || first.equals("report")) {
                for (MonitorRecord record : plugin.monitor().all()) {
                    if (record.name.toLowerCase(Locale.ROOT).startsWith(start)) {
                        out.add(record.name);
                    }
                }
            } else if (!first.equals("list")) {
                for (String tag : TAGS) {
                    if (tag.startsWith(start)) {
                        out.add(tag);
                    }
                }
            }
        }
        return out;
    }

    @Override
    public String permission() {
        return "novaguard.admin";
    }

    private static void reply(CommandSender sender, String text, NamedTextColor color) {
        sender.sendMessage(Component.text("[NovaGuard] ", NamedTextColor.DARK_AQUA).append(Component.text(text, color)));
    }
}
