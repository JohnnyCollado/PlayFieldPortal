# Session context: Artwork Manager hardening (C16), Merge 4

Written 2026-09-15 at the end of a planning session. Branch `artwork-revisions`, clean at `8569f33`
apart from this file and the two doc updates described below.

**This file is a pointer, not a second record.** The plan
[`artwork-manager-hardening-plan.md`](artwork-manager-hardening-plan.md) is the record, and its
Execution Task Index is the status. If this file and the plan ever disagree, the plan wins; if the
plan and the repository disagree, the repository wins (`PLANNING_WORKFLOW.md` §6, §12). Delete this
file once Merge 4 lands — the plan's own sections will then tell the whole story.

## Where the work stands

Everything through Merge 4's first half is done: `0.1`–`0.6`, `1.1`–`1.4`, `2.1`–`2.4`, `3.1`–`3.2`,
`5.0`–`5.2`, `L.1`–`L.6`, `M.0`–`M.6`.

**Start here: task `5.3`, then `5.4`.** Both were specced against the tree on 2026-09-15 and are
under "Merge 4" in the plan, in the same shape as the 5.1/5.2 specs (what the tree has, decisions,
scope, do-not-change, acceptance, budget, stop condition). Neither has an open question left —
5.4's one blocking decision was closed this session (see below). After them: crop, `6.1`–`6.3`.

Not available to pick up: `4.3` and `4.4` wait on plan C17, which has not started; `7.1` is blocked
on plan B2's typed-reasons slice.

## What this session changed

Documentation only — **no production code was touched, and nothing was staged or committed.**

- `docs/plans/artwork-manager-hardening-plan.md`: rows `5.2`, `5.3` and `5.4` corrected; the index's
  closing paragraph brought up to date; a new "Landed: 5.1 and 5.2" section; new "Task 5.3" and
  "Task 5.4" specs.
- `docs/plans/README.md`: the `C16` row carries the same.

## The four findings that reshaped 5.3 and 5.4

Read these before the specs — they are why the specs no longer match what the index rows originally
promised. Each is verified against the tree, not inferred.

1. **5.2 shipped wider than planned.** A multi-asset tab is a checklist, not a pick list: stored
   assets start checked, A unchecks one for removal or picks a new one, and one confirmed Apply
   deletes then queues. `StudioLibraryAssets.holds` already marks held tiles ADDED and unpickable —
   which is the duplicate detection 5.3 was going to write, already done for the multi-asset tabs.
   5.3 therefore shrank to the single-art tabs, where `applyCandidate`
   (`ArtworkStudioViewModel.kt:1854`) still applies with no check at all.
2. **`findByChecksum` has no data.** Nothing in the repository ever writes
   `artwork_records.checksum` — the column exists in the 41→42 DDL and the DAO query only, and the
   entity comment assigns the job to a background Verify/Scan that does not exist. 5.3 drops that
   leg under a stated assumption; byte-identical files served under two URLs stay undetected.
3. **There is no free-space API anywhere in the repository**, the portable library is a SAF tree
   where free space is not reliably readable, and `StudioArt` carries no size before download. So
   5.4's "storage warning" is count-based against the 100-asset cap — which matters, because
   `nextSortOrder` currently clamps at `MAX_SORT_ORDER` and silently overwrites position 99 instead
   of refusing.
4. **Reorder must not rename files** (this closed 5.4's only blocking decision, on the user's
   correction mid-session). Relink derives position from the filename on purpose
   (`ArtworkImportManager.kt:405`), and the Windows PC export leans on the same property directly: a
   `.pfpgame` records each asset's `portableName` and re-import claims records back by exact name
   (`PcGameImportPlanner.kt:203-226`). Renaming to express an order would break those claims for
   every exported Windows game. So `reorderAssets` stays row-only, and a Relink restoring file order
   is documented in the manager rather than fought.

## Still unconsumed

Shipped, tested, and called by nothing outside their own tests — do not assume any of them is wired
up: `ArtworkRecordDao.findByProviderAssetId`, `findByOriginUrl`, `findByChecksum`, and
`RoutingArtworkStore.reorderAssets` (5.4 is what finally consumes the last one).

## Working constraints for this repo

- **Never run a Gradle build unless asked.** The user runs them and pastes results.
- **Don't run `./gradlew --stop`** — it kills the daemon the user's own terminal is using.
- **Don't drive the device.** Ask the user to navigate or reproduce; take screenshots only once they
  say they are ready.
- **No commits without approval.** Stage and propose; the user triggers every commit.
- The user's Android Studio checkout may be a different worktree — check `git worktree list` before
  assuming a file's state.
- Stale red in Android Studio is not real; the Gradle CLI is the truth.
- Android SDK platform-tools: `C:\Users\johnn\AppData\Local\Android\Sdk\platform-tools\adb.exe`

## Verification, when the time comes

The Studio's unit tests live in `ArtworkStudioViewModelTest`, `StudioSearchTest` and
`StudioGridCapacityTest` (`:feature:feature-xmb`), and the store's in `:feature:feature-artwork`.
The plan's "Verification Strategy" section is the fuller list. Note its standing warning: there was
zero pre-existing coverage for `ArtworkStudioViewModel`, the crop math and `ArtworkRecordDao`, so
tests in this area are net-new rather than extensions of a harness — budget accordingly.

Work one bounded task per helper, in dependency order, within `PLANNING_WORKFLOW.md` §4's change
budget: 2–4 existing files modified, 1–2 new files, 1 test file, no new dependencies without
approval. If a task is blocked, stop and report what was attempted, what blocked it, which file
caused it and what decision is needed.
