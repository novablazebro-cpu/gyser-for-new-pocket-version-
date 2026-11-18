package io.github.novablaze.novaguard.security;

import io.github.novablaze.novaguard.NovaGuardPlugin;
import io.github.novablaze.novaguard.Settings;
import io.github.novablaze.novaguard.evidence.ReportFile;
import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.CommandMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerCommandEvent;

/**
 * Season 5 lockdown. The attackers of season 4 joined under other names (offline mode), got operator, planted
 * command blocks that re-whitelisted their alts, and gave themselves items. This closes each of those doors:
 * <ul>
 *   <li>op guard: only UUIDs in {@code lockdown.owners} (a file nobody can edit from the game) may be operator;</li>
 *   <li>owner-only commands (op, whitelist, lp, ...) are refused for everyone else, also inside /execute ... run;</li>
 *   <li>command blocks run nothing (or nothing dangerous); every attempt is logged with coordinates;</li>
 *   <li>only owners may use creative or place command blocks;</li>
 *   <li>non-owners holding operator-level permissions through a permissions plugin are reported;</li>
 *   <li>at start, offline mode without protection is reported loudly.</li>
 * </ul>
 * Everything goes to plugins/NovaGuard/security.log. Runs on the main thread only.
 */
public final class Lockdown implements Listener, Runnable {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long BLOCK_LOG_COOLDOWN_MS = 10 * 60_000L;
    private static final Set<Material> COMMAND_BLOCKS = Set.of(Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK,
        Material.REPEATING_COMMAND_BLOCK, Material.COMMAND_BLOCK_MINECART);
    /** Permissions only an operator normally has: holding one without op means a permissions plugin gave it. */
    private static final List<String> OP_LEVEL_PERMISSIONS = List.of("minecraft.command.op",
        "minecraft.command.whitelist", "minecraft.command.give", "minecraft.command.gamemode",
        "luckperms.user.permission.set");

    private final NovaGuardPlugin plugin;
    private final ReportFile log;
    private final Map<String, Long> lastBlockLog = new HashMap<>();
    private final Set<UUID> permissionWarned = new HashSet<>();
    private String modeVerdict = "not checked yet";
    private int blockedCommandBlocks;
    private int blockedPlayerCommands;
    private int removedOps;
    private int creativeStopped;

    public Lockdown(NovaGuardPlugin plugin) {
        this.plugin = plugin;
        this.log = new ReportFile(plugin.getDataFolder().toPath(), "security.log", plugin.getLogger()::warning);
    }

    private Settings s() {
        return plugin.settings();
    }

    public boolean isOwner(UUID uuid) {
        return s().lockdownOwners.contains(uuid);
    }

    /** An owner who is online and, if a login plugin is in use, has logged in. */
    private boolean trusted(Player player) {
        return isOwner(player.getUniqueId())
            && !plugin.login().waiting(player, plugin.players().get(player), System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ start

    public void start() {
        if (!s().lockdownEnabled) {
            plugin.getLogger().warning("Lockdown is OFF (lockdown.enabled: false): operator, command blocks and"
                + " creative are not protected.");
            return;
        }
        checkOnlineMode();
        if (s().lockdownOwners.isEmpty()) {
            plugin.getLogger().warning("Lockdown: lockdown.owners is empty, so only the console is trusted: nobody"
                + " can be operator or use creative. Add your UUID to lockdown.owners in config.yml and /ng reload.");
        } else {
            plugin.getLogger().info("Lockdown on: " + s().lockdownOwners.size() + " owner(s), command blocks: "
                + s().lockdownCommandBlocks + ".");
        }
        run();
    }

    /** Offline mode lets anyone join under any name, including an owner's. Say clearly how safe this setup is. */
    private void checkOnlineMode() {
        if (Bukkit.getOnlineMode()) {
            modeVerdict = "online mode: account names are checked by Mojang (good)";
            plugin.getLogger().info("Lockdown: " + modeVerdict + ".");
            return;
        }
        File root = Bukkit.getWorldContainer();
        boolean velocity = YamlConfiguration.loadConfiguration(new File(root, "config/paper-global.yml"))
            .getBoolean("proxies.velocity.enabled", false);
        boolean bungee = YamlConfiguration.loadConfiguration(new File(root, "spigot.yml"))
            .getBoolean("settings.bungeecord", false);
        boolean auth = false;
        for (String name : List.of("AuthMe", "nLogin", "LoginSecurity", "LibreLogin", "JPremium", "OpeNLogin")) {
            auth |= Bukkit.getPluginManager().getPlugin(name) != null;
        }
        if (velocity) {
            modeVerdict = "offline mode behind Velocity: safe ONLY if this server's port is closed to the internet"
                + " (only the proxy may connect) and the proxy itself is in online mode or has a login plugin";
            plugin.getLogger().warning("Lockdown: " + modeVerdict + ".");
        } else if (bungee) {
            modeVerdict = "offline mode behind BungeeCord: anyone who can reach this server's port directly can"
                + " fake any player, owners included. Close the port to the internet or switch to Velocity"
                + " modern forwarding";
            plugin.getLogger().severe("Lockdown: " + modeVerdict + ".");
        } else if (auth) {
            modeVerdict = "offline mode with a login plugin: names are protected only by passwords; owners are"
                + " not trusted until they have logged in";
            plugin.getLogger().warning("Lockdown: " + modeVerdict + ".");
        } else {
            modeVerdict = "OFFLINE MODE WITHOUT ANY LOGIN: anyone can join with any name, including an owner's,"
                + " and get everything an owner has. Set online-mode=true (Floodgate still lets Bedrock players in)"
                + " or install a login plugin";
            for (int i = 0; i < 3; i++) {
                plugin.getLogger().severe("Lockdown: " + modeVerdict + ".");
            }
            record("UNSAFE SETUP | " + modeVerdict);
        }
    }

    // ------------------------------------------------------------------ op guard (every 5 s and on join)

    @Override
    public void run() {
        if (!s().lockdownEnabled) {
            return;
        }
        if (s().lockdownOpGuard) {
            for (OfflinePlayer op : new ArrayList<>(Bukkit.getOperators())) {
                if (!isOwner(op.getUniqueId())) {
                    op.setOp(false);
                    removedOps++;
                    String name = op.getName() == null ? op.getUniqueId().toString() : op.getName();
                    alert("OPERATOR REMOVED | " + name + " (" + op.getUniqueId() + ") was operator but is not in"
                        + " lockdown.owners", null);
                }
            }
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            checkPlayer(player);
        }
    }

    private void checkPlayer(Player player) {
        if (isOwner(player.getUniqueId())) {
            return;
        }
        if (s().lockdownCreativeGuard && player.getGameMode() == GameMode.CREATIVE) {
            player.setGameMode(GameMode.SURVIVAL);
            creativeStopped++;
            alert("CREATIVE STOPPED | " + player.getName() + " was in creative at " + where(player.getLocation())
                + " and was put in survival", player.getLocation());
        }
        if (s().lockdownPermissionWatch && !player.isOp() && !permissionWarned.contains(player.getUniqueId())) {
            List<String> held = new ArrayList<>();
            for (String permission : OP_LEVEL_PERMISSIONS) {
                if (player.hasPermission(permission)) {
                    held.add(permission);
                }
            }
            if (!held.isEmpty()) {
                permissionWarned.add(player.getUniqueId());
                alert("PERMISSION BACKDOOR? | " + player.getName() + " (" + player.getUniqueId() + ") is not an owner"
                    + " but has " + String.join(", ", held) + " (given by a permissions plugin; check /lp user "
                    + player.getName() + " permission info). Owner-only commands stay blocked for them.", null);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (s().lockdownEnabled) {
            Player player = event.getPlayer();
            if (s().lockdownOpGuard && player.isOp() && !isOwner(player.getUniqueId())) {
                player.setOp(false);
                removedOps++;
                alert("OPERATOR REMOVED | " + player.getName() + " (" + player.getUniqueId() + ") joined as operator"
                    + " but is not in lockdown.owners (IP " + ip(player) + ")", null);
            }
            checkPlayer(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        permissionWarned.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ commands

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (!s().lockdownEnabled) {
            return;
        }
        Player player = event.getPlayer();
        if (trusted(player)) {
            return;
        }
        String blocked = firstMatch(event.getMessage(), s().lockdownOwnerOnly);
        if (blocked != null) {
            event.setCancelled(true);
            blockedPlayerCommands++;
            player.sendMessage(Component.text("Only the server owner can use /" + blocked + ".", NamedTextColor.RED));
            alert("OWNER-ONLY COMMAND REFUSED | " + player.getName() + " (" + player.getUniqueId() + ", IP "
                + ip(player) + ") tried: " + event.getMessage(), null);
        }
    }

    /** Console, RCON and command blocks. Command blocks and command minecarts arrive here. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onServerCommand(ServerCommandEvent event) {
        if (!s().lockdownEnabled) {
            return;
        }
        CommandSender sender = event.getSender();
        Location at;
        String kind;
        if (sender instanceof BlockCommandSender block) {
            at = block.getBlock().getLocation();
            kind = block.getBlock().getType().name().toLowerCase(java.util.Locale.ROOT);
        } else if (sender instanceof CommandMinecart cart) {
            at = cart.getLocation();
            kind = "command minecart";
        } else {
            return; // the real console: always trusted
        }
        String policy = s().lockdownCommandBlocks;
        if (policy.equals("allow")) {
            return;
        }
        String reason = policy.equals("deny-all") ? "all command blocks are off"
            : firstMatch(event.getCommand(), s().lockdownCommandBlockDangerous);
        if (reason == null) {
            return;
        }
        event.setCancelled(true);
        blockedCommandBlocks++;
        String key = at.getWorld().getName() + " " + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ();
        long now = System.currentTimeMillis();
        Long last = lastBlockLog.get(key);
        if (last == null || now - last >= BLOCK_LOG_COOLDOWN_MS) {
            if (lastBlockLog.size() > 10_000) {
                lastBlockLog.clear();
            }
            lastBlockLog.put(key, now);
            alert("COMMAND BLOCK BLOCKED | " + kind + " at " + key + " tried: " + event.getCommand() + " ("
                + (policy.equals("deny-all") ? reason : "dangerous: /" + reason) + "). Placed by:"
                + " /co lookup radius:1 action:+block include:" + (sender instanceof CommandMinecart
                ? "command_block_minecart" : kind), at);
        }
    }

    /** The first command the line would run that is in the list, after resolving plugin aliases. */
    private static String firstMatch(String commandLine, Set<String> list) {
        for (String root : CommandRoots.of(commandLine)) {
            if (list.contains(root)) {
                return root;
            }
            Command command = Bukkit.getCommandMap().getCommand(root);
            if (command != null) {
                String name = command.getName().toLowerCase(java.util.Locale.ROOT);
                if (list.contains(name)) {
                    return name;
                }
                for (String alias : command.getAliases()) {
                    if (list.contains(alias.toLowerCase(java.util.Locale.ROOT))) {
                        return alias.toLowerCase(java.util.Locale.ROOT);
                    }
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ creative and command-block placing

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        if (s().lockdownEnabled && s().lockdownCreativeGuard && event.getNewGameMode() == GameMode.CREATIVE
            && !trusted(event.getPlayer())) {
            event.setCancelled(true);
            creativeStopped++;
            Player player = event.getPlayer();
            alert("CREATIVE REFUSED | " + player.getName() + " (" + player.getUniqueId() + ") was about to be put in"
                + " creative at " + where(player.getLocation()), player.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (s().lockdownEnabled && COMMAND_BLOCKS.contains(event.getBlockPlaced().getType())
            && !trusted(event.getPlayer())) {
            event.setCancelled(true);
            Player player = event.getPlayer();
            alert("COMMAND BLOCK PLACE REFUSED | " + player.getName() + " (" + player.getUniqueId() + ") at "
                + where(event.getBlockPlaced().getLocation()), event.getBlockPlaced().getLocation());
        }
    }

    // ------------------------------------------------------------------ reporting

    private void alert(String text, Location at) {
        record(text);
        plugin.getLogger().warning("[lockdown] " + ReportFile.clean(text));
        plugin.notifier().send("NovaGuard lockdown", ReportFile.clean(text));
        Component message = Component.text("[NovaGuard lockdown] ", NamedTextColor.DARK_RED)
            .append(Component.text(ReportFile.clean(text), NamedTextColor.RED));
        if (at != null && at.getWorld() != null) {
            message = message.append(Component.text(" [teleport]", NamedTextColor.AQUA)
                .clickEvent(ClickEvent.suggestCommand("/execute in " + at.getWorld().getKey() + " run tp @s "
                    + at.getBlockX() + " " + (at.getBlockY() + 1) + " " + at.getBlockZ())));
        }
        // Owners only: an attacker can give themselves novaguard.admin, but cannot add themselves to the owners.
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isOwner(player.getUniqueId())) {
                player.sendMessage(message);
            }
        }
    }

    private void record(String text) {
        log.append(List.of("[" + LocalDateTime.now().format(TIME) + "] " + ReportFile.clean(text)));
    }

    private static String where(Location at) {
        return at.getWorld().getName() + " " + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ();
    }

    private static String ip(Player player) {
        return player.getAddress() == null ? "?" : player.getAddress().getAddress().getHostAddress();
    }

    /** For /ng lockdown. */
    public List<String> status(CommandSender viewer) {
        List<String> lines = new ArrayList<>();
        Settings settings = s();
        lines.add("Lockdown: " + (settings.lockdownEnabled ? "ON" : "OFF") + " | command blocks: "
            + settings.lockdownCommandBlocks + " | op guard: " + settings.lockdownOpGuard + " | creative guard: "
            + settings.lockdownCreativeGuard);
        lines.add("Login safety: " + modeVerdict);
        List<String> names = new ArrayList<>();
        for (UUID uuid : settings.lockdownOwners) {
            OfflinePlayer owner = Bukkit.getOfflinePlayer(uuid);
            names.add((owner.getName() == null ? "?" : owner.getName()) + " (" + uuid + ")");
        }
        lines.add("Owners: " + (names.isEmpty() ? "none, only the console" : String.join(", ", names)));
        lines.add("Since start: " + removedOps + " operator(s) removed, " + blockedCommandBlocks
            + " command-block command(s) blocked, " + blockedPlayerCommands + " owner-only command(s) refused, "
            + creativeStopped + " creative attempt(s) stopped. Details: plugins/NovaGuard/security.log");
        if (viewer instanceof Player player) {
            lines.add("Your UUID: " + player.getUniqueId() + (isOwner(player.getUniqueId()) ? " (owner)"
                : " (not an owner: to become one, add it to lockdown.owners in config.yml)"));
        }
        return lines;
    }

    public void shutdown() {
        log.shutdown();
    }
}
