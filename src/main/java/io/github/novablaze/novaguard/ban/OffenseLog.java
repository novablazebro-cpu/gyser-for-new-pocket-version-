package io.github.novablaze.novaguard.ban;

import io.github.novablaze.novaguard.NovaGuardPlugin;
import io.github.novablaze.novaguard.math.StrikeList;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Per player: dupe / illegal-item warnings (strikes), every ban NovaGuard gave, and the IPs / alt accounts that
 * were banned together with them (so a wrong ban can be undone completely). Stored in offenses.yml, so the ban
 * ladder (3 days, then 10, then 30) and the IP-ban question survive restarts.
 */
public final class OffenseLog {

    private static final long DAY_MS = 86_400_000L;

    /** One ban NovaGuard gave. {@code days} 0 = permanent. */
    public record Ban(long timeMs, long days, String kind) {
    }

    private static final class Entry {
        String name;
        StrikeList strikes = new StrikeList();
        final List<Ban> bans = new ArrayList<>();
        /** "ip:1.2.3.4" or "alt:&lt;uuid&gt;": banned together with this player. */
        final List<String> linked = new ArrayList<>();

        boolean isEmpty() {
            return strikes.count() == 0 && bans.isEmpty() && linked.isEmpty();
        }
    }

    private final NovaGuardPlugin plugin;
    private final File file;
    private final Map<UUID, Entry> entries = new HashMap<>();

    public OffenseLog(NovaGuardPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "offenses.yml");
        load();
    }

    private Entry entry(UUID uuid, String name) {
        Entry entry = entries.computeIfAbsent(uuid, k -> new Entry());
        if (name != null) {
            entry.name = name;
        }
        return entry;
    }

    // ---------------------------------------------------------------- strikes

    /** @return true when a new strike was counted (false inside the cooldown of the previous strike) */
    public boolean addStrike(UUID uuid, String name, long nowMs, long cooldownMs) {
        boolean added = entry(uuid, name).strikes.add(nowMs, cooldownMs);
        if (added) {
            save();
        }
        return added;
    }

    public int strikes(UUID uuid) {
        Entry entry = entries.get(uuid);
        return entry == null ? 0 : entry.strikes.count();
    }

    public void clearStrikes(UUID uuid) {
        Entry entry = entries.get(uuid);
        if (entry != null && entry.strikes.count() > 0) {
            entry.strikes.clear();
            save();
        }
    }

    /** Takes back strikes given since {@code sinceMs}. @return players (uuid -> refunded count) */
    public Map<UUID, Integer> refundSince(long sinceMs) {
        Map<UUID, Integer> refunded = new HashMap<>();
        for (Map.Entry<UUID, Entry> e : entries.entrySet()) {
            int n = e.getValue().strikes.refundSince(sinceMs);
            if (n > 0) {
                refunded.put(e.getKey(), n);
            }
        }
        if (!refunded.isEmpty()) {
            save();
        }
        return refunded;
    }

    // ---------------------------------------------------------------- bans

    public void recordBan(UUID uuid, String name, long nowMs, long days, String kind) {
        entry(uuid, name).bans.add(new Ban(nowMs, days, kind));
        save();
    }

    /** Remembers an IP or alt account that was banned because of this player. */
    public void recordLinked(UUID uuid, String linked) {
        Entry entry = entry(uuid, null);
        if (!entry.linked.contains(linked)) {
            entry.linked.add(linked);
            save();
        }
    }

    /**
     * Undoes the player's last NovaGuard ban in the history (a wrong ban must not make the next one longer).
     *
     * @return the IPs / alts that were banned with them (now forgotten), or null when there was no ban
     */
    public List<String> removeLastBan(UUID uuid) {
        Entry entry = entries.get(uuid);
        if (entry == null || entry.bans.isEmpty()) {
            return null;
        }
        entry.bans.remove(entry.bans.size() - 1);
        List<String> linked = List.copyOf(entry.linked);
        entry.linked.clear();
        entry.strikes.clear();
        save();
        return linked;
    }

    /** NovaGuard bans of this player in the last {@code rememberDays} days (0 = all time). */
    public int banCount(UUID uuid, long rememberDays, long nowMs) {
        Entry entry = entries.get(uuid);
        if (entry == null) {
            return 0;
        }
        long since = rememberDays <= 0 ? Long.MIN_VALUE : nowMs - rememberDays * DAY_MS;
        int count = 0;
        for (Ban ban : entry.bans) {
            if (ban.timeMs() >= since) {
                count++;
            }
        }
        return count;
    }

    public List<Ban> bans(UUID uuid) {
        Entry entry = entries.get(uuid);
        return entry == null ? List.of() : List.copyOf(entry.bans);
    }

    // ---------------------------------------------------------------- storage

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(false)) {
            ConfigurationSection s = yaml.getConfigurationSection(key);
            if (s == null) {
                continue;
            }
            try {
                UUID uuid = UUID.fromString(key);
                Entry entry = new Entry();
                entry.name = s.getString("name", key);
                entry.strikes = StrikeList.of(s.getLongList("strikes"));
                for (String line : s.getStringList("bans")) {
                    String[] parts = line.split(";", 3);
                    if (parts.length == 3) {
                        entry.bans.add(new Ban(Long.parseLong(parts[0]), Long.parseLong(parts[1]), parts[2]));
                    }
                }
                entry.linked.addAll(s.getStringList("linked"));
                entries.put(uuid, entry);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Skipping bad entry in offenses.yml: " + key);
            }
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Entry> e : entries.entrySet()) {
            Entry entry = e.getValue();
            if (entry.isEmpty()) {
                continue;
            }
            ConfigurationSection s = yaml.createSection(e.getKey().toString());
            s.set("name", entry.name);
            s.set("strikes", entry.strikes.times());
            List<String> bans = new ArrayList<>();
            for (Ban ban : entry.bans) {
                bans.add(ban.timeMs() + ";" + ban.days() + ";" + ban.kind());
            }
            s.set("bans", bans);
            s.set("linked", entry.linked);
        }
        try {
            Files.createDirectories(file.getParentFile().toPath());
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save offenses.yml: " + e.getMessage());
        }
    }
}
