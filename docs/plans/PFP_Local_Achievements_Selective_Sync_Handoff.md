# Selective Sync — implementation handoff

**Plan:** `PFP_Local_Achievements_Selective_Sync_Implementation_Plan.md`.
**Baseline note (Task 1):** `PFP_Local_Achievements_Selective_Sync_Task1_Baseline.md`.
**Branch:** `achievement-ambience`, last commit `1e79101`. Nothing committed yet — everything below is
in the working tree.

## State in one line

Tasks 1–7 and 9–12 are written; Task 8 has its backend and tests but **no UI**; Task 13 (device
verification) has not started. Only two modules have been compiled — everything else is unverified.

## What has actually been verified

| Run | Result |
| --- | --- |
| `:core:core-data` `AchievementEligibilityDaoTest` | 7 passed |
| `:core:core-data` `Migration45To46Test` | 4 failed — `46.json` had not been exported yet. It exists now (untracked); rerun. |
| `:feature:feature-achievements` `sync.*` + `preview.*` | BUILD SUCCESSFUL |

**Not compiled at all:** `feature-xmb`, `feature-settings`, `feature-backup`, `feature-launcher`,
`app`. Their sources were edited, so expect compile errors on the first run. Treat every claim
below about those modules as "written, not proven".

Commands to finish the pass:

```bash
./gradlew :core:core-data:testDebugUnitTest --tests "com.playfieldportal.core.data.database.*"
```

```bash
./gradlew :feature:feature-launcher:testDebugUnitTest :feature:feature-backup:testDebugUnitTest
```

```bash
./gradlew :feature:feature-settings:testDebugUnitTest :feature:feature-xmb:testDebugUnitTest
```

```bash
./gradlew :app:assembleDebug
```

## Remaining work, in order

1. **Search online screen (Task 8 UI).** Approved in principle; mockup at
   https://claude.ai/artifact/XeTJ91gkpsHMngqRxoXPLm (7 artboards, 1920×1080). The user said "go
   ahead and build it" and then interrupted the turn, so **confirm before building**. Backend is
   done: `AchievementPreviewRepository` (search Steam / RA catalog, open, close) with its test.
   The screen still needs a view model, a screen, XMB navigation, and the three new visual
   elements the mockup proposes — the magnifier-over-globe tile glyph, the gold `PREVIEW` chip,
   and the amber notice strip.
2. **Destructive styling for Clear all tracked achievements.** The control exists and is confirmed,
   but is still styled as an ordinary settings row. The plan asks for a destructive mark; the user
   has not said what it should look like. Ask.
3. **Compile and run the remaining tests** (above).
4. **Device verification (Task 13)**: request counts, the Clear modal on controller and touch, a
   local-Steam launch/return, offline start.
5. **Commit.** `core/core-data/schemas/…/46.json` must go in with the migration.

## What was built, by area

### Storage and migration (Task 2)
New entities `AchievementTrackedIdentityEntity` (the confirmed-local-match ledger, keyed
`provider` + `provider_game_id`), `AchievementProviderSyncStateEntity` (per-provider schedule,
backoff, last pause, RA cohort), `AchievementMetadataCacheEntity` (Steam schema + rarity JSON).
`AchievementTrackingDao` owns them plus `clearAllAchievementRecords()`. DB is **v46**;
`MIGRATION_45_46` creates the three tables and seeds the ledger **only** from live
`provider_game_links` joined to `games` — never from a bare `account_achievement_sets` row, which
is what old account imports left behind. A link to an `is_missing` game seeds as not-present.

`AchievementCredentialsProvider` gained `autoUpdatesPausedFlow` / `autoUpdatesPaused()` /
`setAutoUpdatesPaused()` / `clearLastSyncedAt()`.

Backups now carry the ledger, sync state, sets, coins and provider links (five new zip entries,
all optional — an older archive restores as before). The achievement entities became
`@Serializable`; restore runs `BackupDao.replaceAchievementRecords` **after** games, dropping
links whose game the archive did not carry.

### Eligibility (Task 4)
`AccountAchievementSetDao.observeAccountSets` / `observeWalletCoins` and
`AccountAchievementDao.observeRarestEarned` / `observeRecentEarned` all join the ledger, so an
unconfirmed set contributes nothing anywhere. `AccountSetRow` gained `isPresent` + `lastSyncedAt`;
`observeAwaitingSync` finds present, linked games with no set and no check yet. The import-only DAO
methods (`insertIfAbsent`, `getUnsyncedSets`, `backfillIcon`) are gone. Domain gained
`GameStanding.isInstalled`, `AwaitingSyncGame`, `TrackedIdentityStatus` and
`LibraryStanding.awaitingSync`.

### Presence (Task 3)
`AchievementPresenceReconciler` derives presence from links whose game is not missing, plus
discovered Local Steam folders; present identities are confirmed or re-marked present, absent ones
are only marked absent (never deleted). A failed folder scan is "unknown" and leaves LOCAL_STEAM
presence alone. `AchievementAutoMatcher.matchUnlinked` now skips missing games.
`AchievementRepository.unlink` drops the identity when no other link points at it, leaving the
cached set stored.

### Launch return (Task 3/5)
`GameHandoffTracker` (feature-launcher) reports a return only after a confirmed hand-off —
dispatched, the launcher was covered within `HANDOFF_WINDOW_MS`, then resumed. `LaunchDispatcher`
feeds it, and `noteShortcutHandoff()` covers the two direct `LauncherShortcutRepository.launch`
paths in `XMBViewModel` and `GameDetailViewModel`. `GameSessionReturnModule` declares the
listener multibinding; feature-achievements contributes `LocalSteamReturnListener`.

### Coordinator (Task 5)
`AchievementSyncCoordinator` is the single owner of every refresh: Settings, the XMB hub, Player
Status, per-game Refresh, stale-on-open, the scheduled worker and the launch-return check.
Single-flight on the batch and per identity; `generation` + `writeLock` stop a pre-clear fetch
writing after a clear; `AchievementBackoff` is exponential with jitter honouring server hints.
Supporting types are in `SyncTypes.kt`; `AchievementSyncStore` wraps the DAO,
`AchievementSetWriter` does the transactional set replacement and reports whether earned state
actually changed. `AchievementAutoUpdateScheduler` enqueues one constrained job when PFP is used,
and cancels the retired `pfp_steam_import` unique work.

### Providers (Tasks 6–7)
RA: `recentlyPlayed`, `userProgress` (batched, halving on 414), `gameCatalog`; `hashMap` derives
from the catalog and `RaHashResolver` caches both, so search and matching share one download.
`RaCheckStrategy` runs one recent-played call plus one of seven daily cohorts.
Steam: `SteamRemoteDataSource` split into `fetchSchema` / `fetchGlobalRarity` /
`fetchPlayerAchievements`; `SteamMetadataStore` caches schema+rarity; `SteamDetailFetcher` renews
metadata only on first fetch, explicit refresh, or a viewed game older than 30 days.
`SteamCheckStrategy` compares Steam-reported playtime and still refreshes the owned cache and
local-copy ownership. Local Steam reads its progress file (`readEarned`, `mapFromCache`,
`fingerprintOf`); `LocalSteamReturnChecker` updates only on a changed fingerprint.
`EmuAchievementFile.parseOrNull` / `LocalSteamDiscovery.readProgressOrNull` separate "unreadable"
from "nothing earned".

### Retired
`RaAccountImporter`, `SteamAccountImporter`, `SteamImportWorker` and their tests are deleted, along
with `syncAllLinked` / `BatchSyncResult` and the Settings import UI state.

### UI and tray (Tasks 9–11)
`AchievementController` gained `updateInstalledAchievements`, `cancelUpdate`,
`refreshGameIfStale`, `refreshAccountEntryIfStale`, `observeIdentityStatus`,
`observeAutoUpdatesPaused`, `clearAllTrackedAchievements`. Settings was rewritten around
`AchievementMatchAndUpdate` and shows "Update installed achievements", the paused line, and the
confirmed Clear control (Cancel holds default focus). The XMB hub, Player Status, the coins page
("Refresh this game", `installed`, stale-on-open) and Tracked Games ("Not installed",
"Awaiting sync") follow. `AchievementUpdateReporter` owns every tray message; a quiet unchanged
scheduled run leaves nothing unread.

## Decisions worth remembering

- **Unlink means "this match was wrong"**: the identity leaves the ledger, the cached set stays.
- **A removed game keeps its coins** and shows "Not installed"; it is never refreshed.
- **Preview writes nothing** — `AchievementPreviewRepository` holds no DAO, ledger or coordinator
  reference, which is what its test asserts.
- **The scheduled check requires network**, so Local Steam and Vita checks do not run offline. If
  that turns out wrong on the device, split the worker's constraints per provider.
- The coordinator's progress counting is approximate where a strategy leaves identities unchecked;
  the terminal summary is exact.

## Open questions for the user

1. Build the Search online screen as mocked up? (The three new visual elements need a yes.)
2. How should the Clear control be marked destructive?
