package io.github.novablaze.novaguard.core;

import io.github.novablaze.novaguard.CheckType;
import io.github.novablaze.novaguard.math.ClickWindow;
import io.github.novablaze.novaguard.math.Momentum;
import io.github.novablaze.novaguard.math.PositionHistory;
import io.github.novablaze.novaguard.math.SpeedBudget;
import io.github.novablaze.novaguard.math.TargetWindow;
import io.github.novablaze.novaguard.math.ViolationTracker;
import java.util.UUID;
import org.bukkit.Location;

/** Everything NovaGuard remembers about one online player. Only touched on the main server thread. */
public final class PlayerData {

    public final UUID uuid;
    public final boolean bedrock;
    public final long joinMs;
    public boolean alertsEnabled = true;
    /** Watched by /monitor: extra statistics are recorded. */
    public boolean monitored;
    /**
     * Shadow mode: a monitored Bedrock player. Checks run and record what they see,
     * but never set back, cancel, damage, alert or kick.
     */
    public boolean shadow;

    public final ViolationTracker violations = new ViolationTracker();
    public final long[] lastAlertMs = new long[CheckType.values().length];
    public final long[] lastContextMs = new long[CheckType.values().length];
    /** Recent moves, written next to flags (log-context). */
    public final MoveTrace trace = new MoveTrace();
    /** Lenient anti-knockback tracking. */
    public final io.github.novablaze.novaguard.math.VelocityTracker velocity =
        new io.github.novablaze.novaguard.math.VelocityTracker();
    public long velocityStartMs;
    /** Soft mitigation: the next damage to this entity id from this player is reduced. */
    public int softHitTarget = -1;
    /** Block this player last started digging (BlockReach only judges blocks broken by hand). */
    public Location lastDugBlock;
    public long softHitUntilMs;

    /** Hitboxes of the last ~2 seconds, used when this player is the target of a hit. */
    public final PositionHistory history = new PositionHistory(40);
    /** When this player last teleported; hits on them are not reach-checked right after. */
    public long teleportedAtMs;

    // Movement
    public Location lastGood;
    public long lastMoveMs;
    public int burstMoves;
    public long graceUntilMs;
    public long lagSpikeUntilMs;
    public long selfTeleportUntilMs;
    public long manualExemptUntilMs;
    public final SpeedBudget speedBudget = new SpeedBudget();
    /** Leftover launch momentum after a grace period ended mid-air. */
    public final Momentum momentum = new Momentum();
    /** The previous move was skipped (grace, exemption): the next checked move may start momentum tracking. */
    public boolean afterSkip;
    public long lastIceMs;
    public boolean airborne;
    public double groundY;
    public long hoverCheckpointMs;
    public double hoverCheckpointY;
    public double bounceAllowance;
    public long bounceUntilMs;
    public double fallDistance;
    public int groundSpoofs;

    // Combat
    public final ClickWindow clicks = new ClickWindow();
    public final TargetWindow targets = new TargetWindow();
    public int cpsStrikes;
    public long lastCpsStrikeMs;
    public double reachBuffer;
    public int aimBuffer;
    public int wallBuffer;
    public double previousItemReach;
    public double previousItemMargin;
    public long previousItemUntilMs;

    public PlayerData(UUID uuid, boolean bedrock, long joinMs) {
        this.uuid = uuid;
        this.bedrock = bedrock;
        this.joinMs = joinMs;
    }

    /** Pause movement checks until at least {@code nowMs + durationMs}. */
    public void grace(long nowMs, long durationMs) {
        graceUntilMs = Math.max(graceUntilMs, nowMs + durationMs);
    }

    /** Forget movement state so checks restart cleanly from {@code location}. */
    public void resetMovement(Location location, long nowMs) {
        airborne = false;
        groundY = location.getY();
        hoverCheckpointMs = nowMs;
        hoverCheckpointY = location.getY();
        fallDistance = 0;
        groundSpoofs = 0;
        lastGood = location.clone();
    }
}
