package io.github.novablaze.novaguard.math;

import java.util.Locale;
import java.util.Set;

/** Pure rules for recognising login plugins and their commands (no server needed, unit tested). */
public final class LoginRules {

    /** Commands that log a player in (most login plugins use these names or aliases). */
    public static final Set<String> LOGIN_COMMANDS = Set.of(
        "login", "l", "log", "register", "reg", "premium", "2fa", "totp", "captcha", "pin", "unregister");
    public static final Set<String> LOGOUT_COMMANDS = Set.of("logout");
    /** Contain "login"/"auth" but are no login plugin. */
    private static final Set<String> NOT_LOGIN = Set.of("novaguard", "authlib", "authlibinjector");

    private LoginRules() {
    }

    /** True for plugins whose name says they log players in (AuthMe, nLogin, LoginSecurity, LibreLogin...). */
    public static boolean looksLikeLoginPlugin(String pluginName) {
        if (pluginName == null) {
            return false;
        }
        String name = pluginName.toLowerCase(Locale.ROOT);
        if (NOT_LOGIN.contains(name)) {
            return false;
        }
        return name.contains("login") || name.contains("auth") || name.equals("jpremium");
    }

    /**
     * Command name of a chat command, lower case and without namespace ({@code "/AuthMe:login pw"} gives
     * {@code "login"}), or {@code null} for an empty command.
     */
    public static String commandName(String message) {
        if (message == null) {
            return null;
        }
        String text = message.strip();
        if (text.startsWith("/")) {
            text = text.substring(1);
        }
        int space = text.indexOf(' ');
        String name = (space < 0 ? text : text.substring(0, space)).toLowerCase(Locale.ROOT);
        int colon = name.indexOf(':');
        if (colon >= 0) {
            name = name.substring(colon + 1);
        }
        return name.isEmpty() ? null : name;
    }

    public static boolean isLoginCommand(String message, Set<String> extra) {
        String name = commandName(message);
        return name != null && (LOGIN_COMMANDS.contains(name) || extra.contains(name));
    }

    public static boolean isLogoutCommand(String message) {
        String name = commandName(message);
        return name != null && LOGOUT_COMMANDS.contains(name);
    }
}
