package io.github.novablaze.novaguard.core;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;

/** Creates player data on join and drops it on quit. */
public final class PlayerDataManager {

    private final Map<UUID, PlayerData> players = new HashMap<>();
    private final BedrockDetector bedrockDetector;

    public PlayerDataManager(BedrockDetector bedrockDetector) {
        this.bedrockDetector = bedrockDetector;
    }

    public PlayerData get(Player player) {
        return players.computeIfAbsent(player.getUniqueId(),
            uuid -> {
                PlayerData data = new PlayerData(uuid, bedrockDetector.isBedrock(player), System.currentTimeMillis());
                data.resetMovement(player.getLocation(), data.joinMs);
                return data;
            });
    }

    public PlayerData getIfPresent(UUID uuid) {
        return players.get(uuid);
    }

    public void remove(UUID uuid) {
        players.remove(uuid);
    }

    public Collection<PlayerData> all() {
        return players.values();
    }
}
