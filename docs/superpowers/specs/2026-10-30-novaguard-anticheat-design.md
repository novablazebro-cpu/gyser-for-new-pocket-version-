# NovaGuard — lag-safe anti-cheat + anti-dupe for Paper 26.3

Status: approved in chat on 2026-10-30.

## Goal

A lightweight anti-cheat for an offline-mode (cracked) Paper 26.3 server with Geyser/Floodgate.
It must never cost the server noticeable CPU (Grim lagged it), and it must not punish legit play:
spears, maces, attribute swapping, lunge, wind charges, and players with bad connections.

## Server context

- Paper 26.3, Java 25, offline mode, AuthMe + FastLogin, ViaVersion/ViaBackwards, Geyser + Floodgate,
  EssentialsX (`/fly`, `/speed`, `/tp`), GSit (sitting = riding an entity), LifeStealZ (PvP focus).
- Bedrock players (Floodgate) are skipped by every anti-cheat check (owner's choice).
  Anti-dupe still covers everyone, because dupes hurt the economy no matter who does them.

## Approach

Event-based checks with explicit lag budgets (approach A). No packet library, no physics re-simulation.
Each check is a few arithmetic operations per Bukkit event.

## Punishment ladder (owner's choice)

1. Every failed check is *blocked*: movement is set back (rubberband), a hit is cancelled.
2. Staff with `novaguard.alerts` get a rate-limited alert. Everything is logged to `violations.log`.
3. A kick happens only when a check's violation level (VL) reaches its `kick-vl`, AND the player's
   ping is under `kick.max-ping`, AND server TPS is at least `kick.min-tps`, AND the player joined more
   than `kick.join-grace-seconds` ago, AND no lag spike is active. No bans, ever.
4. VL decays every second, so isolated flags fade away.

## Lag safety

- Ping (`Player#getPing`) widens reach windows and the speed budget.
- A gap of more than `lag.packet-gap-ms` between movement events = lag spike: movement checks pause
  for `lag.spike-grace-ms`.
- TPS below `lag.min-tps-for-movement` pauses movement checks.
- Join, respawn, world change and teleport give a grace period.

## Checks

| Check | Event | Logic | On fail |
|---|---|---|---|
| Speed | PlayerMoveEvent | Token bucket: budget refills at the allowed rate (from live `movement_speed`, walk speed, ice), capacity scales with ping; each move spends its horizontal distance. Catches speed and blatant timer. | setback |
| Fly | PlayerMoveEvent + 1 s task | Server-side ground test (`World#hasCollisionsIn`). Ascent above simulated max jump height (from `jump_strength`, Jump Boost, `gravity`) + margin; or airborne for 1 s without falling 0.5 blocks (hover/glide). | setback |
| NoFall | PlayerMoveEvent | Client says "on ground" while the server sees air during a fall. On real landing, fall damage is re-applied from the tracked fall distance (`safe_fall_distance`, `fall_damage_multiplier`). | damage |
| Reach | PrePlayerAttackEntityEvent | Backtracking: target positions of the last second are kept; the hit passes if any position inside the window `ping × 1.5 + 300 ms` is within the limit. Limit = max(`entity_interaction_range`, item `attack_range.max_reach`) + max(hitbox margin, 0.3) + tolerance. | cancel hit |
| Aim | PrePlayerAttackEntityEvent | Angle between look direction and the target exceeds `max-angle` for several hits in a row. | cancel hit |
| Wall-hit | PrePlayerAttackEntityEvent | Raytraces to the target's closest point, center and top are all blocked by solid blocks. | cancel hit |
| Multi-target | PrePlayerAttackEntityEvent | More than `max-targets` distinct entities attacked within `window-ms`. | cancel hit |
| Autoclicker | PrePlayerAttackEntityEvent | Attacks per second above `max-cps` for several seconds in a row. | cancel hit |

Hotbar switches are never checked (attribute swapping is legit). After a switch, the reach limit uses the
best of the old and new item for 250 ms.

## Research: legit reach above 3.2 blocks in 26.3 (verified from vanilla 26.3 data + Paper source)

- Spears (all tiers): `attack_range` max 4.5, min 2.0, hitbox margin 0.125; creative 6.5.
- Creative: 5.0 for normal items.
- `entity_interaction_range` can be changed by commands or plugins (up to 64).
- Default hitbox margin for non-spear items: 0.3 (from the codec default; not confirmed for the item-less path).
- Lag: the server measures against the target's current position, the client saw it about
  `ping + ~150 ms` earlier. A sprinting target at 300 ms ping gives about 5.5 blocks.
- No potion effect, enchantment or other vanilla item changes reach.
- Vanilla already accepts attacks up to reach + 3.0 (`misc.client-interaction-leniency-distance`).
- Server-computed hits, which the client cannot stretch, are skipped: spear stabs (`PIERCING_WEAPON`,
  `KINETIC_WEAPON`), riptide spin hits, sweep hits.
- New in 26.x: `friction_modifier`, `air_drag_modifier`, `bounciness` attributes. If a player has a
  non-default value, movement checks are skipped for them.

Legit movement boosts handled with exemptions/grace: knockback (`EntityKnockbackEvent`, all causes,
including explosions and wind charges), `PlayerVelocityEvent`, lunge (`EntityLungeEvent`, up to 27 b/s),
riptide, elytra and glide end, vehicles (horse, nautilus, happy ghast, boats), happy ghast and boat
platforms, slime/bed/bounciness bounces, levitation, slow falling, dolphin's grace, bubble columns,
liquids, climbables, cobweb, powder snow, scaffolding, pistons, teleports and ender pearls,
EssentialsX `/fly` and `/speed`, creative/spectator, GSit chairs.

## Anti-dupe

1. Overstacked items (amount > max stack size) are clamped and logged.
2. Entity containers (donkey, llama, mule, chest boat, chest minecart, hopper minecart): clicks are
   cancelled and the inventory closed when the entity is dead, invalid, in another world or farther than
   8 blocks. Viewers are closed when such an entity teleports or dies.
3. Item fingerprints: unstackable valuable items get a random ID in their persistent data container.
   Two items with the same ID at the same time (same scan, or verified in two online players' storage)
   = duplicate: alert + log. Optional removal of the extra copy is off by default.
   Creative players are not scanned.

## Testing

- Unit tests (JUnit 5) for the pure math: jump height, token bucket, VL decay, point-to-box distance,
  angles, position history, click window, ID duplicate finder.
- Compile check against the real Paper 26.3 API source.
- GitHub Actions: build with Java 25 + real `paper-api:26.3.build.+`, run tests, boot a real Paper 26.3
  server with the plugin to prove it enables cleanly, then publish a GitHub Release.

## Out of scope

Packet-level prediction, subtle cheats (small reach, smooth aim, small timer), X-ray (use Paper's
built-in anti-xray), bans.

## Addendum 2026-10-30: player monitoring (approved in chat)

- `/monitor <player> [cracked|bedrock]` (console or `novaguard.admin`), plus `stop`, `report`, `list`.
  The tag is optional; the detected type wins and a mismatch is reported.
- Duration: 72 real hours (`monitor.duration-hours`), persisted in `monitors.yml` across restarts.
- Recorded: lag-compensated reach per hit and the share beyond the strict limit (no tolerance), aim angle per hit
  and share above 45 degrees, attacks per second for seconds with 5+ attacks (mean and variation; exact click
  intervals are not measurable because Paper handles packets once per tick), all flags and near misses, ping,
  playtime, sessions, client brand, dupe alerts.
- Bedrock players run in shadow mode: checks execute and record, but never set back, cancel, damage, alert or kick.
- Verdict (`MonitorVerdict`): points per kind of evidence; Bedrock movement counts half; average ping above
  250 ms subtracts 2; under 30 minutes of playtime caps the verdict at "suspicious". Suspicious from 3, likely from 6.
- Report: `plugins/NovaGuard/reports/<name>-<date>.txt`, announced to console and staff.
- "Likely cheating" kicks a Java player once (on next join if offline), never Bedrock, never a ban (owner's choice).

## Addendum 2026-10-30 (2): reliability package and ban waves (approved in chat)

- Tier 1: flag context in violations.log (`log-context`), per-check `mode: enforce|silent`, `Safety` wrapper
  (errors logged once, a part failing 20x/min switches off until /ng reload, slow-event warning), BlockReach
  (hand-dug blocks only, scaffolding skipped, nearer of clicked/placed block).
- Rotation (GCD) analysis as monitor evidence: step estimated from small turns / 1..64 pixels, refined by
  least squares, turns touching the +-90 pitch limit ignored. Stress test: 1000 humans all fit 100%, best cheat 10%.
- Velocity (lenient anti-KB): peak displacement along the knockback after melee hits; three "no knockback" hits in
  a row flag. Partial anti-KB is not detectable without packet timing.
- Soft mitigation: hits beyond real reach but inside the tolerance deal `soft-damage-multiplier` damage.
- Flag statistics per hour (`/ng stats`) and spike warnings; alt tracking by public IP (`alts.yml`).
- Ban waves (owner's choices): suspects are blocked silently; confirmation = strong detections in
  `min-sessions` (2) sessions + "likely cheating"; random delay 24-72 h; 30-day ban of name, IPs and linked alts;
  Bedrock accounts and IPs used by Bedrock accounts excluded; kicks suppressed for checks that feed ban waves.
- Notifications: Discord webhook and SMTPS email (hostname-verified TLS, connect timeout) for suspect, queued,
  banned and spike events.
