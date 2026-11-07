package io.github.novablaze.novaguard.core;

import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Is this really the account owner? On an offline-mode ("cracked") server anyone can join with any name and
 * play until the login plugin kicks them. Checks still block whatever such a session does, but nothing it
 * does may count as evidence against the real owner (ban waves, monitor reports), or anyone could frame
 * another player by joining with their name.
 */
public final class Trust {

    private static Method authenticated;
    private static Object authApi;
    private static boolean authLookedUp;

    private Trust() {
    }

    /**
     * True when the player is logged in: always on online-mode servers; with AuthMe, its own answer; on other
     * offline-mode servers, after the player has been online for {@code minOnlineSeconds} (login plugins kick
     * players who do not log in well before that).
     */
    public static boolean loggedIn(Player player, PlayerData data, long minOnlineSeconds, long nowMs) {
        if (Bukkit.getOnlineMode()) {
            return true;
        }
        Boolean authMe = authMe(player);
        if (authMe != null) {
            return authMe;
        }
        return nowMs - data.joinMs >= minOnlineSeconds * 1000L;
    }

    /** AuthMe's answer, or null without AuthMe. Uses reflection so NovaGuard does not depend on AuthMe. */
    private static Boolean authMe(Player player) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("AuthMe");
        if (plugin == null || !plugin.isEnabled()) {
            return null;
        }
        try {
            if (!authLookedUp) {
                authLookedUp = true;
                Class<?> api = Class.forName("fr.xephi.authme.api.v3.AuthMeApi", true, plugin.getClass().getClassLoader());
                authApi = api.getMethod("getInstance").invoke(null);
                authenticated = api.getMethod("isAuthenticated", Player.class);
            }
            return authenticated == null ? null : (Boolean) authenticated.invoke(authApi, player);
        } catch (ReflectiveOperationException | RuntimeException e) {
            authenticated = null;
            return null;
        }
    }
}
