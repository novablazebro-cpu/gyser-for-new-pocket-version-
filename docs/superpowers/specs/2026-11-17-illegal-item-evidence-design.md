# Illegal item evidence report (`/ng scanplayers`)

## Why
The owner wants to ban players who hold illegal enchantments, including players who already left, with evidence
strong enough to stand. A chest at some coordinates does not prove who owns it; an item in a player's own
inventory or ender chest does.

## Goals / non-goals / anti-goals
- Goal: list every illegal item in every player's inventory and ender chest (offline players too), with where
  the player was, in `plugins/NovaGuard/illegal-enchantments-report.txt`.
- Goal (added): an always-on scan of every loaded chunk (on load and on a rotation) for stashes, with exact
  coordinates, categories, the nearest survival player as a hint and CoreProtect evidence; and report-only mode
  so nothing is removed before staff have seen it.
- Anti-goals: changing any save file or item; banning or warning automatically; lag; a crafted file or item
  name breaking the plugin or faking report lines.

## Approaches considered
1. **Read the save files with a small own NBT reader, let Paper load each item (chosen).** Paper has no API for
   an offline player's inventory. Items are handed to `ItemStack.deserializeBytes` with the file's DataVersion,
   so Paper upgrades and validates them; the existing `ItemSanitizer` decides what is illegal.
2. Load the player through server internals (NMS). Breaks with every server update.
3. Only check players when they join. Never reaches players who left, which is the whole point.

## Design
- `evidence/Nbt`: NBT read/write with caps (64 MB unpacked, depth 512, lengths checked against bytes left).
- `evidence/PlayerFileItems`: walks the file, takes every item-shaped compound (independent of key names),
  skips plain items without data.
- `evidence/PlayerScanner`: `/ng scanplayers [player|status|cancel]` (novaguard.admin). Files read on a
  background thread, items checked on the main thread 4 ms per tick, bounded queue in between. Online players
  checked live. Location from Paper's OfflinePlayer API (last position, respawn point, last death).
- `evidence/ReportFile`: async append, control characters removed, 600-char lines, rotation at 20 MB, refuses
  to follow a symbolic link.
- Player save folder found by walking up from the main world (`players/data` on 26.3, `playerdata` before).

## Verification
Unit tests (`EvidenceTest`), and `/ng selftest` builds a save file with Paper's own item format and checks the
round trip inside a real Paper 26.3 server (smoke test). Not yet verified against a save file written by a real
player on 26.3.

## Addendum: always-on stash scan (1.8.0)
- Approaches: scan on chunk load only (misses items added later); manual `/ng scanhere` only (misses places staff
  do not visit); both plus a rotation of loaded chunks (chosen, by Claude after the owner handed over the choice).
- Main thread copies only items that could be illegal (budget-ms, min-tps brake); a background thread checks them,
  asks CoreProtect (reflection; API has no container transactions, so placed/opened + the `/co lookup` command),
  dedupes per container and writes the report. Staff alerts once per container per hour, click to teleport.
- Categories via `IllegalItemInspector`; creative-only list is configurable because shops/crates may sell those.
- `illegal-items.mode: report` default: the live fixer and `/cleanallexploits confirm` change nothing.
- Verified: real Paper 26.3 server, a chest with a Sharpness 10 sword, 64 barriers and a legal pickaxe: reported
  once with coordinates and categories, pickaxe not reported, sword unchanged. CoreProtect part not tested
  against a real CoreProtect install.
