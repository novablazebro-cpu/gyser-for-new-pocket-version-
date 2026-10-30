# NovaGuard implementation plan

Spec: `docs/superpowers/specs/2026-10-30-novaguard-anticheat-design.md`

1. **Build setup**: Gradle 9.8.2 wrapper, `paper-api:26.3.build.+` (compileOnly), Java 25 toolchain, JUnit 5.
2. **Pure math (TDD, no Bukkit types)** in `math/`:
   `Box`, `Geometry`, `JumpPhysics`, `MovementMath`, `ReachMath`, `SpeedBudget`, `PositionHistory`,
   `ClickWindow`, `TargetWindow`, `ViolationTracker`, `Duplicates`. Each gets unit tests first.
3. **Core**: `NovaGuardPlugin`, `Settings`, `CheckType`, `PlayerData` + `PlayerDataManager`,
   `LagMonitor`, `BedrockDetector`, `ViolationManager` (alerts, kick rules, async file log).
4. **Movement**: `Environment` (ground/liquid/climbable probe), `MovementListener` (speed, fly, nofall,
   setbacks), stationary-hover task.
5. **Exemptions**: `GraceListener` (knockback, velocity, lunge, riptide, glide, teleports, vehicles,
   pistons, effects, game mode, flight toggles, join/respawn/world change, held-item memo).
6. **Combat**: `CombatListener` (autoclicker, multi-target, reach with backtracking, aim, wall-hit).
7. **Anti-dupe**: `OverstackGuard`, `EntityContainerGuard`, `ItemFingerprints` + `DupeListener`.
8. **Command** `/novaguard` (`/ng`): reload, alerts, info, exempt.
9. **Verify locally**: unit tests with JUnit console; compile everything against the Paper 26.3 API source.
10. **CI** (`.github/workflows/build.yml`): Java 25, Gradle build + tests, boot real Paper 26.3 with the
    plugin (smoke test), publish a GitHub Release with `NovaGuard.jar`.
11. **Self-review** for bugs, fix, re-verify, push, give the download link.
