package io.github.novablaze.novaguard.cleanup;

import io.github.novablaze.novaguard.NovaGuardPlugin;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

/**
 * /cleanallexploits [preview | confirm | status | cancel] (also /ng cleanall ...). Cleaning changes items of
 * everybody, so it needs "confirm"; "preview" shows what would change without changing anything.
 */
public final class CleanCommand implements BasicCommand {

    private static final List<String> OPTIONS = List.of("preview", "confirm", "status", "cancel");

    private final NovaGuardPlugin plugin;

    public CleanCommand(NovaGuardPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        handle(source.getSender(), args);
    }

    /** Shared with /ng cleanall. */
    public void handle(CommandSender sender, String[] args) {
        WorldCleaner cleaner = plugin.cleaner();
        String option = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (option) {
            case "preview" -> reply(sender, cleaner.start(sender.getName(), false), NamedTextColor.YELLOW);
            case "confirm" -> reply(sender, cleaner.start(sender.getName(), true), NamedTextColor.GREEN);
            case "status" -> reply(sender, cleaner.status(), NamedTextColor.AQUA);
            case "cancel" -> reply(sender, cleaner.cancel(), NamedTextColor.YELLOW);
            default -> {
                reply(sender, "Removes every enchantment that is impossible in survival (Sharpness 10, Density +"
                    + " Breach...) from every item in the game: players, ender chests, chests, item frames, armor"
                    + " stands, mobs, items on the ground. Valid enchantments stay. Nobody is warned or banned.",
                    NamedTextColor.YELLOW);
                reply(sender, "/cleanallexploits preview  = only count, change nothing (recommended first)",
                    NamedTextColor.GRAY);
                reply(sender, "/cleanallexploits confirm  = clean for real (report in plugins/NovaGuard/reports/)",
                    NamedTextColor.GRAY);
                reply(sender, "/cleanallexploits status | cancel", NamedTextColor.GRAY);
            }
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        String start = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        return args.length <= 1 ? OPTIONS.stream().filter(o -> o.startsWith(start)).toList() : List.of();
    }

    @Override
    public String permission() {
        return "novaguard.admin";
    }

    private static void reply(CommandSender sender, String text, NamedTextColor color) {
        sender.sendMessage(Component.text("[NovaGuard] ", NamedTextColor.DARK_AQUA).append(Component.text(text, color)));
    }
}
