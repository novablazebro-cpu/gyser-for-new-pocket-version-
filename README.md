# NovaGuard

Lightweight, lag-safe anti-cheat and anti-dupe for **Paper 26.3** servers, built for offline-mode (cracked)
servers that also run Geyser/Floodgate.

- **No lag:** no packet library and no physics re-simulation. Each check is a few math steps per event.
- **No false bans:** NovaGuard never bans. It blocks the cheat (rubberband or cancelled hit) and alerts staff.
  It kicks only at very high violation levels, and never while the player or the server is lagging.
- **Modern combat aware:** spears (4.5-block reach read from the item itself), mace, lunge, wind charges and
  attribute swapping are all legit and are not punished.
- **Bedrock players** (Floodgate) are skipped by every anti-cheat check. Anti-dupe still protects everyone.

## Install

1. Download `NovaGuard.jar` from the latest
   [release](https://github.com/novablazebro-cpu/gyser-for-new-pocket-version-/releases/latest).
2. Put it in your server's `plugins` folder and restart (do not use `/reload`).
3. Remove other anti-cheats (Grim etc.) first: two anti-cheats fight each other's setbacks.
4. Watch the alerts for a few days before raising any `kick-vl` values.

## Checks

| Check | Catches | Action |
|---|---|---|
| Speed | speed hacks, blatant timer | rubberband |
| Fly | flying, hovering, high jumps | rubberband |
| NoFall | skipping fall damage | fall damage re-applied |
| Reach | hitting from too far (lag-compensated) | hit cancelled |
| Aim | hitting targets outside your view (killaura) | hit cancelled |
| WallHit | hitting through solid blocks | hit cancelled |
| MultiTarget | switching between many targets very fast | hit cancelled |
| AutoClicker | extreme attack speed | extra hits cancelled |
| AntiDupe | overstacked items, entity-chest dupes, duplicated valuables | trimmed / blocked / alert |

## Commands and permissions

| Command | Permission | What it does |
|---|---|---|
| `/novaguard alerts` (`/ng alerts`) | `novaguard.alerts` | Turn alerts on/off for yourself |
| `/novaguard info <player>` | `novaguard.admin` | Ping, Java/Bedrock and current violation levels |
| `/novaguard exempt <player> <seconds>` | `novaguard.admin` | Skip all checks for a player for a while |
| `/novaguard reload` | `novaguard.admin` | Reload `config.yml` |

### Monitoring a suspect (`/monitor`)

Type these in the server console (e.g. the Pterodactyl panel) or in game with `novaguard.admin`:

| Command | What it does |
|---|---|
| `monitor <player> [cracked\|bedrock]` | Watch the player for 3 days. The tag is optional: NovaGuard detects Java/Bedrock itself and warns if your tag disagrees. Bedrock names usually start with `.` (Floodgate prefix). |
| `monitor report <player>` | Write a report now (the watch keeps running) |
| `monitor stop <player>` | Stop watching, no report |
| `monitor list` | Who is watched and when each report is due |

While watched, NovaGuard records every hit's lag-compensated reach and aim angle, attacks per second and how
regular they are, every flag and near miss, ping, playtime and client brand. After 3 days (counted even while
they are offline or the server restarts) a report is written to `plugins/NovaGuard/reports/` and announced to
the console and online staff, with a verdict: **no evidence / suspicious / likely cheating**.

- A **Java** player with a "likely cheating" verdict is kicked once (on their next join if offline). Never banned.
- **Bedrock** players are watched in *shadow mode*: checks record what they would have flagged but never act,
  and the report only recommends. Settings: `monitor:` in `config.yml`.

The report is evidence, not proof: spectate the player before acting.

`novaguard.bypass` skips every anti-cheat check (not given to anyone by default, not even ops).

All flags are written to `plugins/NovaGuard/violations.log`.

## Set `allow-flight=true` in server.properties

The famous "Flying is not enabled on this server" kick (after TNT, pistons, slime launchers, lag...) is
**vanilla Minecraft's own** crude check, active when `allow-flight=false`. NovaGuard checks flying itself,
with lag compensation and launch-momentum tracking, so set `allow-flight=true`. This does not let anyone fly;
it only turns off vanilla's kick.

## Recommended Paper settings against dupes

Paper already blocks most dupes. Check these are still at their safe defaults in `config/paper-global.yml`:

```yaml
unsupported-settings:
  allow-piston-duplication: false
  allow-headless-pistons: false
  allow-permanent-block-break-exploits: false
```

## What NovaGuard does not do

It catches blatant cheats. Subtle ones (3.2-block reach, smooth aim assist, small timer) need a packet-level
anti-cheat, which costs a lot of CPU. For X-ray, enable Paper's built-in anti-xray in the world config.

## Building

GitHub Actions builds every push with Java 25 against `paper-api:26.3`, runs the unit tests, boots a real
Paper 26.3 server with the plugin as a smoke test, and publishes a release. Locally: `./gradlew build`
(needs Java 25).

Design and research: `docs/superpowers/specs/2026-10-30-novaguard-anticheat-design.md`.
