package io.github.novablaze.novaguard;

import io.github.novablaze.novaguard.combat.CombatListener;
import io.github.novablaze.novaguard.core.BedrockDetector;
import io.github.novablaze.novaguard.core.LagMonitor;
import io.github.novablaze.novaguard.core.PlayerData;
import io.github.novablaze.novaguard.core.PlayerDataManager;
import io.github.novablaze.novaguard.core.ViolationManager;
import io.github.novablaze.novaguard.dupe.DupeListener;
import io.github.novablaze.novaguard.dupe.ItemFingerprints;
import io.github.novablaze.novaguard.movement.Exemptions;
import io.github.novablaze.novaguard.movement.GraceListener;
import io.github.novablaze.novaguard.movement.MovementListener;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Entry point: loads the config, creates the services and registers listeners, tasks and the command. */
public final class NovaGuardPlugin extends JavaPlugin {

    private volatile Settings settings;
    private LagMonitor lag;
    private PlayerDataManager players;
    private ViolationManager violations;
    private Exemptions exemptions;
    private ItemFingerprints fingerprints;
    private DupeListener dupeListener;
    private BukkitTask fingerprintTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = new Settings(getConfig(), getLogger());
        lag = new LagMonitor();
        players = new PlayerDataManager(new BedrockDetector(getLogger()));
        violations = new ViolationManager(this);
        exemptions = new Exemptions(this);
        fingerprints = new ItemFingerprints(this);

        MovementListener movement = new MovementListener(this);
        dupeListener = new DupeListener(this);
        PluginManager pluginManager = getServer().getPluginManager();
        pluginManager.registerEvents(new GraceListener(this), this);
        pluginManager.registerEvents(movement, this);
        pluginManager.registerEvents(new CombatListener(this), this);
        pluginManager.registerEvents(dupeListener, this);

        Bukkit.getScheduler().runTaskTimer(this, lag, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, movement, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::decayViolations, 20L, 20L);
        scheduleFingerprintScans();

        // Players already online (after /reload or a plugin manager reload).
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            players.get(player).grace(now, 5000);
        }

        registerCommand("novaguard", "NovaGuard anti-cheat", List.of("ng"), new NovaGuardCommand(this));
        getLogger().info("NovaGuard enabled: lag-safe checks and anti-dupe are active.");
    }

    @Override
    public void onDisable() {
        if (violations != null) {
            violations.shutdown();
        }
    }

    public void reloadSettings() {
        reloadConfig();
        settings = new Settings(getConfig(), getLogger());
        scheduleFingerprintScans();
    }

    private void scheduleFingerprintScans() {
        if (fingerprintTask != null) {
            fingerprintTask.cancel();
        }
        long interval = settings.fingerprintScanTicks;
        fingerprintTask = Bukkit.getScheduler().runTaskTimer(this, dupeListener, interval, interval);
    }

    private void decayViolations() {
        double amount = settings.decayPerSecond;
        players.all().removeIf(data -> Bukkit.getPlayer(data.uuid) == null);
        for (PlayerData data : players.all()) {
            data.violations.decay(amount);
        }
    }

    public Settings settings() {
        return settings;
    }

    public LagMonitor lag() {
        return lag;
    }

    public PlayerDataManager players() {
        return players;
    }

    public ViolationManager violations() {
        return violations;
    }

    public Exemptions exemptions() {
        return exemptions;
    }

    public ItemFingerprints fingerprints() {
        return fingerprints;
    }
}
