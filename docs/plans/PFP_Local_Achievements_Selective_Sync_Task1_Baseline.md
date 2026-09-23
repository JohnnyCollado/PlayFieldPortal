# Selective Sync — Task 1 baseline note

**Branch:** `achievement-ambience` at `1e79101` (2026-09-23). **Database:** v45, `exportSchema = true`,
Room 2.7 driver-based `MigrationTestHelper` under Robolectric (`MigrationTestSupport.kt`), so the new
migration is v45 → v46 and gets a `Migration45To46Test`.

## Active entry points (before this change)

| Operation | Callers | Path |
| --- | --- | --- |
| Auto-match | Settings ▸ Achievements `autoMatch()`, XMB hub `autoMatchFromHub()` | `AchievementAutoMatcher.matchUnlinked()` walks every `observeGamesOnly()` game, missing ones included |
| Sync All | Settings `launchSyncAll()`, XMB hub `syncAllCoinsFromHub()`, Player Status `syncAll()` | `AchievementRepository.syncAllLinked()` — links + live local Steam folders + **every account-only set**; also prunes LOCAL_STEAM sets whose folder vanished |
| Per-game refresh | `ShibaCoinsViewModel.sync()` | `syncGameById()` / `syncAccountEntry()` → `syncEntry()` (fetch + non-transactional delete/insert) |
| RA account import | `AchievementsSettingsViewModel.importRaHistory()` (no screen button) | `RaAccountImporter` → `userCompletionProgress()` walk |
| Steam account import | `AchievementsSettingsViewModel.importSteamLibrary()` → `SteamImportWorker` (no screen button) | `SteamAccountImporter` probes every owned game; also refreshes `steam_owned_games` + `LocalSteamOwnership.refreshAll()` |
| Local Steam scan | matcher, Sync All, `LocalSteamSource.fetch()` | `LocalSteamDiscovery.scan()` / `findByAppId()`; each fetch re-requests schema + rarity + hidden descriptions |
| Launch hand-off | `LaunchDispatcher.launch()` (emulator/package launches); **bypass:** `LauncherShortcutRepository.launch()` in `XMBViewModel` (x2) and `GameDetailViewModel` | `MainActivity.onStop/onResume` → `LaunchDispatcher.onHostStopped/onHostResumed` |

## Tray integration

The in-app tray is `core-ui`'s `BackgroundTaskCenter` (singleton): `start/progress/complete/fail` for
tasks with progress, `report(id, label, message, severity, kind, action)` for one-shot outcomes,
`cancel(id)` to drop a task silently. Each settled task writes one `notifications` row keyed
`task:<id>` (a repeat replaces the row), mirrors to the Android shade, and plays
`MenuSound.NOTIFICATION`. Category is `NotificationKind.ACHIEVEMENT` / `TaskKind.ACHIEVEMENT`;
destinations are `NotificationAction.OpenCategory("achievements")` and
`OpenSettingsScreen("settings_achievements_credentials")`. Existing ids: `achievement_match`,
`achievement_sync`. There is no second tray store to add.

## Unlink semantics (Task 3 edge)

`unlink(gameId)` deletes every provider link for the game; it backs **Change Match** — the user is
declaring the match wrong. The selective-sync behavior: the game's cached set is left in place, but
its identity leaves the confirmed ledger when no other link or present local folder still points at
it, so a wrong match stops counting and stops syncing. Nothing re-links it until the user matches
again (auto-match only runs on an explicit action).

## Backups

Backups currently carry no achievement tables at all (no sets, coins or links). Task 2 adds the
ledger, provider sync state, sets and coins as optional archive entries; an older archive without
them restores exactly as before.
