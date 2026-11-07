package io.github.novablaze.novaguard.dupe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigRisksTest {

    @Test
    void paperDefaultsAreSafe() {
        Map<String, Object> defaults = Map.of(
            "unsupported-settings.allow-piston-duplication", false,
            "unsupported-settings.allow-headless-pistons", false,
            "unsupported-settings.allow-permanent-block-break-exploits", false,
            "unsupported-settings.allow-unsafe-end-portal-teleportation", false,
            "packet-limiter.all-packets.max-packet-rate", 500.0);
        assertTrue(ConfigRisks.global(defaults::get).isEmpty());
        assertTrue(ConfigRisks.world(Map.<String, Object>of("entities.spawning.duplicate-uuid.mode", "SAFE_REGEN")::get)
            .isEmpty());
    }

    @Test
    void pistonDuplicationIsReported() {
        List<String> risks = ConfigRisks.global(
            Map.<String, Object>of("unsupported-settings.allow-piston-duplication", true)::get);
        assertEquals(1, risks.size());
        assertTrue(risks.get(0).contains("TNT"));
    }

    @Test
    void disabledPacketLimiterIsReported() {
        assertEquals(1, ConfigRisks.global(
            Map.<String, Object>of("packet-limiter.all-packets.max-packet-rate", -1.0)::get).size());
    }

    @Test
    void duplicateUuidNothingIsReported() {
        assertEquals(1, ConfigRisks.world(
            Map.<String, Object>of("entities.spawning.duplicate-uuid.mode", "nothing")::get).size());
        assertEquals(1, ConfigRisks.world(
            Map.<String, Object>of("entities.spawning.duplicate-uuid.mode", "WARN")::get).size());
    }

    @Test
    void missingSettingsAreNotRisks() {
        assertTrue(ConfigRisks.global(path -> null).isEmpty());
        assertTrue(ConfigRisks.world(path -> null).isEmpty());
    }
}
