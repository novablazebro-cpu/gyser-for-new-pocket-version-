package io.github.novablaze.novaguard;

import io.github.novablaze.novaguard.ban.BanManager;
import io.github.novablaze.novaguard.combat.BlockReachListener;
import io.github.novablaze.novaguard.combat.CombatListener;
import io.github.novablaze.novaguard.core.AltTracker;
import io.github.novablaze.novaguard.core.Notifier;
import io.github.novablaze.novaguard.core.Safety;
import io.github.novablaze.novaguard.math.FlagRateStats;
import io.github.novablaze.novaguard.core.BedrockDetector;
import io.github.novablaze.novaguard.core.LagMonitor;
import io.github.novablaze.novaguard.core.PlayerData;
import io.github.novablaze.novaguard.core.PlayerDataManager;
import io.github.novablaze.novaguard.core.ViolationManager;
import io.github.novablaze.novaguard.dupe.DupeListener;
import io.github.novablaze.novaguard.dupe.ItemFingerprints;
import io.github.novablaze.novaguard.monitor.MonitorCommand;
import io.github.novablaze.novaguard.monitor.MonitorManager;
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
    private MonitorManager monitor;
    private Safety safety;
    private Notifier notifier;
    private AltTracker alts;
    private BanManager bans;
    private FlagRateStats flagStats;
    private DupeListener dupeListener;
    private BukkitTask fingerprintTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = new Settings(getConfig(), getLogger());
        safety = new Safety(this);
        notifier = new Notifier(this);
        flagStats = new FlagRateStats(System.currentTimeMillis());
        alts = new AltTracker(this);
        lag = new LagMonitor();
        players = new PlayerDataManager(new BedrockDetector(getLogger()));
        violations = new ViolationManager(this);
        exemptions = new Exemptions(this);
        fingerprints = new ItemFingerprints(this);
        monitor = new MonitorManager(this);
        bans = new BanManager(this);

        MovementListener movement = new MovementListener(this);
        dupeListener = new DupeListener(this);
        PluginManager pluginManager = getServer().getPluginManager();
        pluginManager.registerEvents(new GraceListener(this), this);
        pluginManager.registerEvents(movement, this);
        pluginManager.registerEvents(new CombatListener(this), this);
        pluginManager.registerEvents(new BlockReachListener(this), this);
        pluginManager.registerEvents(dupeListener, this);
        pluginManager.registerEvents(bans, this);

        Bukkit.getScheduler().runTaskTimer(this, lag, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, movement, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::decayViolations, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, () -> safety.run("monitor", monitor), 1200L, 1200L);
        Bukkit.getScheduler().runTaskTimer(this, () -> safety.run("ban-waves", bans), 1200L, 1200L);
        Bukkit.getScheduler().runTaskTimer(this, () -> alts.save(), 6000L, 6000L);
        scheduleFingerprintScans();

        // Players already online (after /reload or a plugin manager reload).
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerData data = players.get(player);
            data.grace(now, 5000);
            monitor.onJoin(player, data);
            alts.onJoin(player);
        }

        registerCommand("novaguard", "NovaGuard anti-cheat", List.of("ng"), new NovaGuardCommand(this));
        registerCommand("monitor", "Watch a player for a few days and write a cheat report", List.of("ngmonitor"),
            new MonitorCommand(this));
        getLogger().info("NovaGuard enabled: lag-safe checks and anti-dupe are active.");
    }

    @Override
    public void onDisable() {
        if (monitor != null) {
            monitor.shutdown();
        }
        if (bans != null) {
            bans.save();
        }
        if (alts != null) {
            alts.save();
        }
        if (notifier != null) {
            notifier.shutdown();
        }
        if (violations != null) {
            violations.shutdown();
        }
    }

    public void reloadSettings() {
        reloadConfig();
        settings = new Settings(getConfig(), getLogger());
        safety.reset();
        scheduleFingerprintScans();
    }

    private void scheduleFingerprintScans() {
        if (fingerprintTask != null) {
            fingerprintTask.cancel();
        }
        long interval = settings.fingerprintScanTicks;
        fingerprintTask = Bukkit.getScheduler().runTaskTimer(this, () -> safety.run("anti-dupe", dupeListener),
            interval, interval);
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

    public Safety safety() {
        return safety;
    }

    public Notifier notifier() {
        return notifier;
    }

    public AltTracker alts() {
        return alts;
    }

    public BanManager bans() {
        return bans;
    }

    public FlagRateStats flagStats() {
        return flagStats;
    }

    public MonitorManager monitor() {
        return monitor;
    }

    public ItemFingerprints fingerprints() {
        return fingerprints;
    }
}
