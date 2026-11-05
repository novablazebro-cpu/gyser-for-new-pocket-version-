# ADR-002: Architecture improvements

**Status:** Accepted
**Date:** 2026-11-05
**Deciders:** server owner (Nova)

## Context (evaluation of the current design)
| Area | Finding | Severity |
|---|---|---|
| Persistence | 6 YAML stores (`bans.yml`, `ip-approvals.yml`, `monitors.yml`, `offenses.yml`, `state.yml`, `alts.yml`) each call `yaml.save(file)` directly. A crash or the panel's Kill button during a save leaves a **half-written file**: queued bans, offenses or monitors are lost. | High |
| `ItemFingerprints` | ~700 lines doing five jobs: ID tagging, illegal-item scanning, same-scan blame, cross-player blame, give-away tracking. Hard to review; helpers (`isRealStorage`, `isCreative`, ID read/write) are reached from other classes through it. | Medium |
| Plugin class | Service locator with ~20 getters; every component takes the whole plugin. Acceptable at this size (one developer, one process), and changing it would touch every file for little gain. | Low |
| Main-thread work | All game work is on the main thread (correct for Bukkit); file writes for logs are already async. Bulk work had no time budget (new cleanup job needs one). | Medium |
| Safety | `Safety.run` wraps every listener and task, switches failing parts off: good, keep. | - |

## Decision
1. **Atomic file writes** for every store: write `name.yml.tmp`, then move it over `name.yml` in one step
   (`YamlFiles.save`). A crash leaves either the old or the new file, never a broken one.
2. **Split item identity out of `ItemFingerprints`** into `items/ItemIds` (read/write/strip the hidden ID and birth
   time, eligibility) and `items/Inventories` (what counts as real storage, creative check, names). The scanner
   keeps the blame logic only.
3. **Time-budgeted work queue** (`core/WorkQueue`) for bulk jobs, used by the cleanup (ADR-001).
4. Keep the service locator and the main-thread model.

## Options Considered
- **Dependency-injection framework (e.g. Guice):** adds a dependency and boilerplate; no benefit at this size. Rejected.
- **Database (SQLite) instead of YAML:** atomic by design, but a new dependency and harder for admins to read/edit.
  YAML + atomic move gives the safety without that. Rejected for now; revisit beyond a few thousand players.
- **Full rewrite into modules/services:** high risk without live-player testing. Rejected; incremental changes only.

## Consequences
- Easier: stores survive crashes; item logic is testable and reusable by the cleanup job.
- Harder: nothing notable.
- Revisit: SQLite if `alts.yml` / `offenses.yml` grow very large.

## Action Items
1. [x] `store/YamlFiles.save` (atomic) used by all six stores
2. [x] `items/ItemIds`, `items/Inventories` extracted
3. [x] `core/WorkQueue` with a per-tick nanosecond budget
