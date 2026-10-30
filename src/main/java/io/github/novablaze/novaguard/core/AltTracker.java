package io.github.novablaze.novaguard.core;

import io.github.novablaze.novaguard.NovaGuardPlugin;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 * Remembers which accounts joined from which IP address, to find alt accounts (a banned cracked player can
 * rejoin with a new name). Loopback and private network addresses are never stored: behind a proxy or on a
 * LAN every player shares them, and linking or banning them would hit everybody.
 */
public final class AltTracker {

    private final NovaGuardPlugin plugin;
    private final File file;
    private final Map<String, Set<UUID>> accountsByIp = new HashMap<>();
    private final Map<UUID, Set<String>> ipsByAccount = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private boolean dirty;

    public AltTracker(NovaGuardPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "alts.yml");
        load();
    }

    public void onJoin(Player player) {
        if (!plugin.settings().altsEnabled) {
            return;
        }
        InetSocketAddress socket = player.getAddress();
        if (socket == null || socket.getAddress() == null || !isPublic(socket.getAddress())) {
            return;
        }
        String ip = socket.getAddress().getHostAddress();
        UUID uuid = player.getUniqueId();
        String previousName = names.put(uuid, player.getName());
        boolean added = accountsByIp.computeIfAbsent(ip, k -> new LinkedHashSet<>()).add(uuid);
        added |= ipsByAccount.computeIfAbsent(uuid, k -> new LinkedHashSet<>()).add(ip);
        dirty |= added || !player.getName().equals(previousName);
    }

    /** Public IPs this account used, excluding shared ones (more accounts than the configured limit). */
    public Set<String> ipsOf(UUID uuid) {
        Set<String> result = new LinkedHashSet<>();
        for (String ip : ipsByAccount.getOrDefault(uuid, Set.of())) {
            if (!isShared(ip)) {
                result.add(ip);
            }
        }
        return result;
    }

    /** Other accounts that used one of this account's (non-shared) IPs. */
    public Set<UUID> altsOf(UUID uuid) {
        Set<UUID> result = new LinkedHashSet<>();
        for (String ip : ipsOf(uuid)) {
            result.addAll(accountsByIp.getOrDefault(ip, Set.of()));
        }
        result.remove(uuid);
        return result;
    }

    /** True when a Bedrock (Floodgate) account has joined from this IP. */
    public boolean usedByBedrock(String ip) {
        for (UUID account : accountsByIp.getOrDefault(ip, Set.of())) {
            if (BedrockDetector.isFloodgateUuid(account)) {
                return true;
            }
        }
        return false;
    }

    public String nameOf(UUID uuid) {
        return names.getOrDefault(uuid, uuid.toString());
    }

    public boolean isShared(String ip) {
        return accountsByIp.getOrDefault(ip, Set.of()).size() > plugin.settings().altsMaxAccountsPerIp;
    }

    public static boolean isPublic(InetAddress address) {
        return !(address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
            || address.isAnyLocalAddress() || isCarrierNat(address));
    }

    /** 100.64.0.0/10 is used by mobile carriers and tunnels (shared by many people). */
    private static boolean isCarrierNat(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4 && (bytes[0] & 0xff) == 100 && (bytes[1] & 0xc0) == 64;
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection accounts = yaml.getConfigurationSection("accounts");
        if (accounts == null) {
            return;
        }
        for (String key : accounts.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                names.put(uuid, accounts.getString(key + ".name", key));
                for (String ip : accounts.getStringList(key + ".ips")) {
                    ipsByAccount.computeIfAbsent(uuid, k -> new LinkedHashSet<>()).add(ip);
                    accountsByIp.computeIfAbsent(ip, k -> new LinkedHashSet<>()).add(uuid);
                }
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Skipping bad entry in alts.yml: " + key);
            }
        }
    }

    public void save() {
        if (!dirty) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Set<String>> entry : ipsByAccount.entrySet()) {
            String key = "accounts." + entry.getKey();
            yaml.set(key + ".name", names.get(entry.getKey()));
            yaml.set(key + ".ips", new java.util.ArrayList<>(entry.getValue()));
        }
        try {
            yaml.save(file);
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save alts.yml: " + e.getMessage());
        }
    }
}
