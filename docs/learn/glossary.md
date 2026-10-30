# Glossary (learning notes for Nova)

| Term | Plain meaning | Where it shows up here |
|---|---|---|
| Plugin | Extra code a Minecraft server loads to add features. | The whole project; `plugin.yml` tells Paper how to load it. |
| Event | The server announcing "something just happened" (a player moved, hit something...). | `PlayerMoveEvent` in `movement/MovementListener.java` |
| Listener | Code that waits for events and reacts, like a doorbell you answer. | `GraceListener`, `CombatListener`, `DupeListener` |
| API | The set of methods a program offers to other programs. Paper's API is how our plugin talks to the server. | `compileOnly("io.papermc.paper:paper-api:...")` in `build.gradle.kts` |
| Deprecated | "Still works, but don't rely on it"; usually with a reason. | `Player.isOnGround()`: deprecated because the client controls it, which is exactly why NoFall reads it. |
| Ping | Round-trip time between a player and the server, in milliseconds. | Reach window and speed budget grow with ping. |
| TPS | Server ticks per second. 20 is healthy; lower means the server lags. | `core/LagMonitor.java` |
| Violation level (VL) | A score that goes up when a check fails and slowly drains back down. | `math/ViolationTracker.java` |
| Setback | Teleporting a player back to their last legit spot (a rubberband). | `setback()` in `MovementListener.java` |
| Grace period | A short time where checks are skipped because something legit just pushed the player. | `GraceListener.java` |
| False positive | Flagging an innocent player. The main enemy of every anti-cheat. | The whole research section of the spec. |
| Token bucket | A budget that refills over time; each action spends from it. Smooths out bursts. | `math/SpeedBudget.java` |
| Backtracking (lag compensation) | Judging a hit against where the target *was* when the attacker saw it. | `math/PositionHistory.java`, `CombatListener.java` |
| Ring buffer | A fixed-size list that overwrites its oldest entry when full. No memory growth. | `PositionHistory`, `ClickWindow` |
| Bounding box / hitbox | The invisible box around an entity used for collisions and hits. | `math/Box.java` |
| Raytrace | Drawing an invisible line to see if a block is in the way. | `clearLine()` in `CombatListener.java` |
| Attribute | A live number on an entity: reach, speed, gravity, jump strength... | `Exemptions.attribute(...)` |
| Data component | A piece of data on an item, e.g. a spear's `attack_range`. | `DataComponentTypes.ATTACK_RANGE` |
| PDC (persistent data container) | A hidden data pocket on an item where plugins store their own values. | Item fingerprints in `dupe/ItemFingerprints.java` |
| Reflection | Calling another plugin's code by name at runtime, so it works even if that plugin is missing. | `core/BedrockDetector.java` (Floodgate) |
| Unit test | A small program that checks one fact about your code automatically. | `src/test/java/...` (46 tests) |
| TDD (test-driven development) | Write the test first, watch it fail, then write code until it passes. | How the `math/` package was built. |
| Gradle | The build tool: downloads libraries, compiles, runs tests, packages the jar. | `build.gradle.kts`, `gradlew` |
| CI (continuous integration) | A robot that builds and tests every change automatically. | `.github/workflows/build.yml` |
| Smoke test | "Turn it on and see if smoke comes out": start the real server and check the plugin loads. | `scripts/smoke-test.sh` |
| Release | A published, downloadable version of the program. | GitHub Releases page of this repo |
| Branch | A separate line of work in git, so experiments don't disturb the main version. | `claude/novaguard-anticheat` |
| Commit | A saved snapshot of the project with a note saying what changed, like a labelled game save. | `git log` |
| Shadow mode | Running checks that only record what they see, without acting on it. | Monitored Bedrock players (`PlayerData.shadow`) |
| Standard deviation | How spread out numbers are. Humans click irregularly (high), simple autoclickers very regularly (low). | `math/RunningStats.java`, monitoring reports |
| Welford's method | A way to keep mean and spread of a stream of numbers without storing them all. | `RunningStats.add()` |
| Persistence | Saving state to disk so it survives a restart. | `monitors.yml` written by `MonitorManager` |
| Tick | One step of the server's game loop (20 per second). Packets are handled once per tick. | Why click timing is measured per second, not per millisecond |
| Momentum tracking | After a launch, allowing movement only while it slows down the way real physics demands. | `math/Momentum.java` (TNT cannons, slime launchers) |
| Ban wave | Banning detected cheaters later, all at a random time, so they cannot tell what got them caught. | `ban/BanManager.java` |
| Canary rollout / silent mode | Running new code without letting it act, to watch for problems first. | `mode: silent` in config.yml |
| Watchdog | Code that watches other code for errors and slowness. | `core/Safety.java`, `/ng health` |
| GCD (greatest common divisor) analysis | Finding the one step size all of a player's turns are multiples of. | `math/RotationAnalysis.java` |
| Least squares | Finding the value that best fits many noisy measurements at once. | `RotationAnalysis.refine()` |
| Stress test | Running code against thousands of generated cases to find rare failures. | Rotation check: 1000 humans vs 1000 cheats |
| TLS hostname verification | Checking that an encrypted connection really goes to the server you meant, not an impostor. | `notify/SmtpClient.java` |
| Webhook | A URL that posts a message into a chat (e.g. Discord) when something calls it. | `notify.discord-webhook` |
