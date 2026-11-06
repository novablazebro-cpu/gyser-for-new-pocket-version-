package io.github.novablaze.novaguard.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class LoginRulesTest {

    @Test
    void knownLoginPluginsAreFound() {
        for (String name : new String[] {"AuthMe", "AuthMeReloaded", "nLogin", "LoginSecurity", "OpeNLogin",
            "LibreLogin", "FastLogin", "JPremium", "CatSeedLogin", "LockLogin"}) {
            assertTrue(LoginRules.looksLikeLoginPlugin(name), name);
        }
    }

    @Test
    void otherPluginsAreNot() {
        for (String name : new String[] {"NovaGuard", "Essentials", "floodgate", "Geyser-Spigot", "GSit", null}) {
            assertFalse(LoginRules.looksLikeLoginPlugin(name), String.valueOf(name));
        }
    }

    @Test
    void commandNames() {
        assertEquals("login", LoginRules.commandName("/login secret"));
        assertEquals("login", LoginRules.commandName("/AuthMe:LOGIN secret"));
        assertEquals("l", LoginRules.commandName("/l"));
        assertNull(LoginRules.commandName("/"));
        assertNull(LoginRules.commandName(null));
    }

    @Test
    void loginAndLogoutCommands() {
        assertTrue(LoginRules.isLoginCommand("/register pw pw", Set.of()));
        assertTrue(LoginRules.isLoginCommand("/l pw", Set.of()));
        assertTrue(LoginRules.isLoginCommand("/entrar pw", Set.of("entrar")));
        assertFalse(LoginRules.isLoginCommand("/home", Set.of()));
        assertFalse(LoginRules.isLoginCommand("/loginfo", Set.of()));
        assertTrue(LoginRules.isLogoutCommand("/logout"));
        assertFalse(LoginRules.isLogoutCommand("/login pw"));
    }
}
