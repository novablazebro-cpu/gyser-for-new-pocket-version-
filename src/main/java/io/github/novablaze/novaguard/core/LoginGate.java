package io.github.novablaze.novaguard.core;

import io.github.novablaze.novaguard.NovaGuardPlugin;
import io.github.novablaze.novaguard.Settings;
import io.github.novablaze.novaguard.math.LoginRules;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;

/**
 * Login plugins (AuthMe, nLogin, LoginSecurity, LibreLogin, JPremium...) freeze cracked players until they type
 * /login. A frozen player can hang in the air (they left while jumping or falling), which looks exactly like a fly
 * hack. While a player is not logged in, NovaGuard checks nothing on them.
 *
 * <p>How "logged in" is known:
 * <ol>
 *   <li>The login plugin's own API, when NovaGuard knows it (AuthMe, nLogin, LoginSecurity, LibreLogin). Exact,
 *       including automatic logins (FastLogin premium players, sessions).</li>
 *   <li>Any other login plugin: {@value #AFTER_COMMAND_MS} ms after the player typed /login, /register...</li>
 *   <li>Always: {@code login-plugins.max-wait-seconds} after joining, so a broken API can never switch the
 *       anti-cheat off for a player.</li>
 * </ol>
 * After logging in the player gets a short grace: most login plugins teleport them back to where they left.
 * Without a login plugin this class does nothing.
 */
public final class LoginGate implements Listener {

    /** Time the login plugin gets to finish the login (and teleport the player) after the command. */
    static final long AFTER_COMMAND_MS = 3000;
    private static final long GRACE_AFTER_LOGIN_MS = 3000;
    private static final long POLL_MS = 250;

    /** Asks a login plugin whether a player is logged in. */
    @FunctionalInterface
    private interface Probe {
        boolean loggedIn(Player player) throws ReflectiveOperationException;
    }

    private static final class Wait {
        final long deadlineMs;
        long releaseAtMs = Long.MAX_VALUE;
        long lastPollMs;

        Wait(long deadlineMs) {
            this.deadlineMs = deadlineMs;
        }
    }

    private final NovaGuardPlugin plugin;
    private final Map<UUID, Wait> waits = new HashMap<>();
    private List<String> found = List.of();
    private Probe probe;
    private String probeName = "";

    public LoginGate(NovaGuardPlugin plugin) {
        this.plugin = plugin;
    }

    /** Looks for login plugins. Called after the server started and whenever a plugin is enabled/disabled. */
    public void detect() {
        List<String> names = new ArrayList<>();
        for (Plugin other : Bukkit.getPluginManager().getPlugins()) {
            if (other.isEnabled() && other != plugin && LoginRules.looksLikeLoginPlugin(other.getName())) {
                names.add(other.getName());
            }
        }
        probe = null;
        probeName = "";
        for (String name : names) {
            Probe p = probeFor(Bukkit.getPluginManager().getPlugin(name));
            if (p != null) {
                probe = p;
                probeName = name;
                break;
            }
        }
        if (!names.equals(found)) {
            found = List.copyOf(names);
            if (names.isEmpty()) {
                plugin.getLogger().info("No login plugin found: login protection is off.");
            } else {
                plugin.getLogger().info("Login plugin(s) found: " + String.join(", ", names) + ". Players are not"
                    + " checked until they have logged in (" + (probe != null ? "asked " + probeName + " directly"
                    : "after /login or /register, or " + plugin.settings().loginMaxWaitMs / 1000 + " s") + ").");
            }
        }
        if (!active()) {
            waits.clear();
        }
    }

    /** True while the player has not logged in yet (nothing is checked on them). */
    public boolean waiting(Player player, PlayerData data, long now) {
        if (waits.isEmpty()) {
            return false;
        }
        Wait wait = waits.get(player.getUniqueId());
        if (wait == null) {
            return false;
        }
        if (now >= wait.releaseAtMs || now >= wait.deadlineMs || polledLoggedIn(player, wait, now)) {
            waits.remove(player.getUniqueId());
            // The login plugin may still teleport the player back to where they left: start fresh from here.
            data.resetMovement(player.getLocation(), now);
            data.speedBudget.fill(now);
            data.grace(now, GRACE_AFTER_LOGIN_MS + player.getPing());
            return false;
        }
        return true;
    }

    public int waitingCount() {
        return waits.size();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        if (active()) {
            start(event.getPlayer(), System.currentTimeMillis());
        }
    }

    /** Not ignoreCancelled: some login plugins cancel their own command after handling it. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!active()) {
            return;
        }
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        if (LoginRules.isLogoutCommand(event.getMessage())) {
            start(player, now);
            return;
        }
        Wait wait = waits.get(player.getUniqueId());
        // With an API the plugin itself says when the login worked (a wrong password changes nothing).
        if (wait != null && probe == null && LoginRules.isLoginCommand(event.getMessage(),
            plugin.settings().loginExtraCommands)) {
            wait.releaseAtMs = Math.min(wait.releaseAtMs, now + AFTER_COMMAND_MS);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        waits.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(PluginEnableEvent event) {
        if (LoginRules.looksLikeLoginPlugin(event.getPlugin().getName())) {
            detect();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin() != plugin && LoginRules.looksLikeLoginPlugin(event.getPlugin().getName())) {
            // The plugin is still listed as enabled during this event: look again one tick later.
            Bukkit.getScheduler().runTask(plugin, this::detect);
        }
    }

    private boolean active() {
        Settings settings = plugin.settings();
        return settings.loginEnabled && !found.isEmpty();
    }

    private void start(Player player, long now) {
        waits.put(player.getUniqueId(), new Wait(now + plugin.settings().loginMaxWaitMs));
    }

    private boolean polledLoggedIn(Player player, Wait wait, long now) {
        Probe p = probe;
        if (p == null || now - wait.lastPollMs < POLL_MS) {
            return false;
        }
        wait.lastPollMs = now;
        try {
            return p.loggedIn(player);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            plugin.getLogger().warning("Could not ask " + probeName + " if players are logged in (" + e
                + "). Using /login and /register commands instead.");
            probe = null;
            return false;
        }
    }

    /** The API of known login plugins, found by reflection so NovaGuard runs without them. */
    private Probe probeFor(Plugin other) {
        if (other == null) {
            return null;
        }
        ClassLoader loader = other.getClass().getClassLoader();
        String name = other.getName().toLowerCase(java.util.Locale.ROOT);
        try {
            if (name.startsWith("authme")) {
                // fr.xephi.authme.api.v3.AuthMeApi.getInstance().isAuthenticated(Player)
                Class<?> api = Class.forName("fr.xephi.authme.api.v3.AuthMeApi", true, loader);
                Method instance = api.getMethod("getInstance");
                Method check = api.getMethod("isAuthenticated", Player.class);
                return player -> {
                    Object target = instance.invoke(null);
                    return target != null && (Boolean) check.invoke(target, player);
                };
            }
            if (name.equals("nlogin")) {
                // com.nickuc.login.api.nLoginAPI.getApi().isAuthenticated(...)
                Class<?> api = Class.forName("com.nickuc.login.api.nLoginAPI", true, loader);
                Method instance = api.getMethod("getApi");
                return byPlayerUuidOrName(instance, null, "isAuthenticated");
            }
            if (name.equals("loginsecurity")) {
                // LoginSecurity.getSessionManager().getPlayerSession(Player).isAuthorized()
                Class<?> main = Class.forName("com.lenis0012.bukkit.loginsecurity.LoginSecurity", true, loader);
                Method sessions = main.getMethod("getSessionManager");
                Method session = sessions.getReturnType().getMethod("getPlayerSession", Player.class);
                Method authorized = session.getReturnType().getMethod("isAuthorized");
                return player -> {
                    Object s = session.invoke(sessions.invoke(null), player);
                    return s != null && (Boolean) authorized.invoke(s);
                };
            }
            if (name.equals("librelogin")) {
                // ((LibreLoginProvider) plugin).getLibreLogin().getAuthorizationProvider().isAuthorized(Player)
                Method libre = other.getClass().getMethod("getLibreLogin");
                Method provider = libre.getReturnType().getMethod("getAuthorizationProvider");
                Method authorized = findMethod(provider.getReturnType(), "isAuthorized");
                if (authorized == null) {
                    return null;
                }
                return player -> (Boolean) authorized.invoke(provider.invoke(libre.invoke(other)), player);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            plugin.getLogger().info("Could not use the API of " + other.getName() + " (" + e.getClass().getSimpleName()
                + "): players count as logged in after /login or /register instead.");
        }
        return null;
    }

    /** API methods that take a Player, a UUID or a name, whichever this plugin version has. */
    private static Probe byPlayerUuidOrName(Method instance, Object fixedTarget, String methodName)
        throws NoSuchMethodException {
        Class<?> type = instance.getReturnType();
        for (Class<?> arg : new Class<?>[] {Player.class, UUID.class, String.class}) {
            try {
                Method check = type.getMethod(methodName, arg);
                return player -> {
                    Object target = fixedTarget != null ? fixedTarget : instance.invoke(null);
                    Object key = arg == Player.class ? player : arg == UUID.class ? player.getUniqueId() : player.getName();
                    return target != null && (Boolean) check.invoke(target, key);
                };
            } catch (NoSuchMethodException ignored) {
                // Try the next argument type.
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + methodName);
    }

    private static Method findMethod(Class<?> type, String methodName) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(methodName) && method.getParameterCount() == 1
                && method.getParameterTypes()[0].isAssignableFrom(Player.class)
                && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)) {
                return method;
            }
        }
        return null;
    }
}
