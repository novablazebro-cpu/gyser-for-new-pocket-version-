package io.github.novablaze.novaguard;

import io.github.novablaze.novaguard.core.PlayerData;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /novaguard (alias /ng): reload, alerts, info, exempt. */
public final class NovaGuardCommand implements BasicCommand {

    private static final List<String> SUBCOMMANDS =
        List.of("alerts", "info", "exempt", "reload", "health", "stats", "alts", "bans", "notifytest");

    private final NovaGuardPlugin plugin;

    public NovaGuardCommand(NovaGuardPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "alerts" -> toggleAlerts(sender);
            case "reload" -> {
                if (requireAdmin(sender)) {
                    plugin.reloadSettings();
                    reply(sender, "Config reloaded.", NamedTextColor.GREEN);
                }
            }
            case "info" -> {
                if (requireAdmin(sender)) {
                    info(sender, args);
                }
            }
            case "exempt" -> {
                if (requireAdmin(sender)) {
                    exempt(sender, args);
                }
            }
            case "health" -> {
                if (requireAdmin(sender)) {
                    plugin.safety().report().forEach(line -> reply(sender, line, NamedTextColor.AQUA));
                }
            }
            case "stats" -> {
                if (requireAdmin(sender)) {
                    stats(sender);
                }
            }
            case "alts" -> {
                if (requireAdmin(sender)) {
                    alts(sender, args);
                }
            }
            case "bans" -> {
                if (requireAdmin(sender)) {
                    bans(sender, args);
                }
            }
            case "notifytest" -> {
                if (requireAdmin(sender)) {
                    plugin.notifier().test();
                    reply(sender, "Test notification sent (check Discord/email, and the console for errors).",
                        NamedTextColor.GREEN);
                }
            }
            default -> reply(sender, "Usage: /novaguard <alerts | info <player> | exempt <player> <seconds> | reload"
                + " | health | stats | alts <player> | bans [cancel|now <player>] | notifytest>", NamedTextColor.YELLOW);
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            String start = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(start)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("bans")) {
            return List.of("cancel", "now");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("bans")) {
            List<String> names = new ArrayList<>();
            plugin.bans().pending().forEach(entry -> names.add(entry.name));
            return names;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("info") || args[0].equalsIgnoreCase("exempt")
            || args[0].equalsIgnoreCase("alts"))) {
            List<String> names = new ArrayList<>();
            String start = args[1].toLowerCase(Locale.ROOT);
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(start)) {
                    names.add(player.getName());
                }
            }
            return names;
        }
        return List.of();
    }

    @Override
    public String permission() {
        return "novaguard.alerts";
    }

    private void stats(CommandSender sender) {
        long now = System.currentTimeMillis();
        reply(sender, "Flags in the last hour / last 24 hours:", NamedTextColor.YELLOW);
        for (CheckType type : CheckType.values()) {
            if (type == CheckType.DUPE) {
                continue;
            }
            String mode = plugin.settings().check(type).silent() ? " [silent]" : "";
            reply(sender, type.displayName() + mode + ": " + plugin.flagStats().lastHour(type, now) + " / "
                + plugin.flagStats().lastDay(type, now), NamedTextColor.AQUA);
        }
    }

    private void alts(CommandSender sender, String[] args) {
        if (args.length < 2) {
            reply(sender, "Usage: /novaguard alts <player>", NamedTextColor.RED);
            return;
        }
        org.bukkit.OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[1]);
        if (target == null) {
            reply(sender, args[1] + " has never joined.", NamedTextColor.RED);
            return;
        }
        java.util.Set<java.util.UUID> alts = plugin.alts().altsOf(target.getUniqueId());
        if (alts.isEmpty()) {
            reply(sender, "No other accounts share an IP with " + args[1] + ".", NamedTextColor.GREEN);
            return;
        }
        List<String> names = new ArrayList<>();
        alts.forEach(alt -> names.add(plugin.alts().nameOf(alt)));
        reply(sender, args[1] + " shares an IP with: " + String.join(", ", names), NamedTextColor.GOLD);
    }

    private void bans(CommandSender sender, String[] args) {
        if (args.length >= 3 && args[1].equalsIgnoreCase("cancel")) {
            reply(sender, plugin.bans().cancel(args[2]) ? "Cancelled the queued ban of " + args[2] + "."
                : args[2] + " is not queued.", NamedTextColor.GREEN);
            return;
        }
        if (args.length >= 3 && args[1].equalsIgnoreCase("now")) {
            reply(sender, plugin.bans().executeNow(args[2]) ? "Banned " + args[2] + " now."
                : args[2] + " is not queued.", NamedTextColor.GREEN);
            return;
        }
        if (plugin.bans().pending().isEmpty()) {
            reply(sender, "No bans are queued.", NamedTextColor.GREEN);
            return;
        }
        for (io.github.novablaze.novaguard.ban.BanManager.Pending entry : plugin.bans().pending()) {
            reply(sender, entry.name + " - banned " + io.github.novablaze.novaguard.ban.BanManager.time(entry.executeMs)
                + " - " + entry.reason, NamedTextColor.GOLD);
        }
        reply(sender, "Cancel one: /novaguard bans cancel <player>   Ban now: /novaguard bans now <player>",
            NamedTextColor.GRAY);
    }

    private void toggleAlerts(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            reply(sender, "Only players can toggle alerts.", NamedTextColor.RED);
            return;
        }
        PlayerData data = plugin.players().get(player);
        data.alertsEnabled = !data.alertsEnabled;
        reply(sender, "Alerts " + (data.alertsEnabled ? "on" : "off") + ".", NamedTextColor.GREEN);
    }

    private void info(CommandSender sender, String[] args) {
        Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : null;
        if (target == null) {
            reply(sender, "Player not online.", NamedTextColor.RED);
            return;
        }
        PlayerData data = plugin.players().get(target);
        StringBuilder text = new StringBuilder(target.getName())
            .append(": ping ").append(target.getPing()).append("ms")
            .append(String.format(Locale.ROOT, ", server TPS %.1f", plugin.lag().tps()))
            .append(data.bedrock ? ", Bedrock (checks skipped)" : ", Java");
        for (CheckType type : CheckType.values()) {
            double vl = data.violations.get(type);
            if (vl > 0) {
                text.append(String.format(Locale.ROOT, ", %s %.1f", type.displayName(), vl));
            }
        }
        reply(sender, text.toString(), NamedTextColor.AQUA);
    }

    private void exempt(CommandSender sender, String[] args) {
        Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : null;
        if (target == null || args.length < 3) {
            reply(sender, "Usage: /novaguard exempt <player> <seconds>", NamedTextColor.RED);
            return;
        }
        long seconds;
        try {
            seconds = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            reply(sender, "Seconds must be a whole number.", NamedTextColor.RED);
            return;
        }
        seconds = Math.max(0, Math.min(seconds, 86_400));
        plugin.players().get(target).manualExemptUntilMs = System.currentTimeMillis() + seconds * 1000L;
        reply(sender, target.getName() + " is exempt for " + seconds + " seconds.", NamedTextColor.GREEN);
    }

    private static boolean requireAdmin(CommandSender sender) {
        if (sender.hasPermission("novaguard.admin")) {
            return true;
        }
        reply(sender, "You need novaguard.admin for that.", NamedTextColor.RED);
        return false;
    }

    private static void reply(CommandSender sender, String text, NamedTextColor color) {
        sender.sendMessage(Component.text("[NovaGuard] ", NamedTextColor.DARK_AQUA).append(Component.text(text, color)));
    }
}
