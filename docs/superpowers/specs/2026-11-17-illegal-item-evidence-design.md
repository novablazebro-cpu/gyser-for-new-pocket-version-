# Illegal item evidence report (`/ng scanplayers`)

## Why
The owner wants to ban players who hold illegal enchantments, including players who already left, with evidence
strong enough to stand. A chest at some coordinates does not prove who owns it; an item in a player's own
inventory or ender chest does.

## Goals / non-goals / anti-goals
- Goal: list every illegal item in every player's inventory and ender chest (offline players too), with where
  the player was, in `plugins/NovaGuard/illegal-enchantments-report.txt`.
- Non-goal (later): scanning chests in chunks as players load them (approach A in the conversation), with
  location hints. Not in this change.
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
