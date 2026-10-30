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

`novaguard.bypass` skips every anti-cheat check (not given to anyone by default, not even ops).

All flags are written to `plugins/NovaGuard/violations.log`.

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
