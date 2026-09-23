# Play Field Portal — Local Achievements and Selective Sync Implementation Plan

**Status:** Final implementation plan — approved for Claude handoff (2026-09-23).  
**Repository baseline reviewed:** `main` at `5670966` (2026-09-23). Recheck the branch Claude implements against before editing.  
**Primary modules:** `feature/feature-achievements`, `feature/feature-xmb`, `feature/feature-settings`, `core/core-data`.  
**Related plans:** `docs/plans/PFP_Achievements_Screen_Design.md`, `docs/plans/PFP_Player_Status_Redesign_Implementation_Plan.md`, `docs/plans/PFP_Achievements_Game_Page_Implementation_Plan.md`.

## 1. Goal and product contract

The Achievements hub should represent games recognized on this PFP system: games currently present and matched to a provider, plus games that were previously present and matched but have since been removed. Do not import an entire RetroAchievements history or probe every title in a Steam account. Automatic network refreshes cover **currently present, matched games only**. Previously matched, removed games retain their cached achievement sets and visible history, without background API calls.

Users can explicitly search for a game that is not present, choose a provider result, and view its available achievements. That result is a **temporary preview**, not a tracked game: opening or refreshing it must not add it to Tracked Games, Shiba wallet, rarest/recent feeds, match history, or a future sync queue. It gains tracked status only through a later confirmed match to a locally present game. The existing local Search row remains a filter; give provider search an explicit separate action.

Add a **Clear all tracked achievements** button in the Achievements section of Settings. It opens a confirmation modal and, only after confirmation, removes all achievement records stored by PFP. Currently present games remain in the library with their provider matches so the user can resync them; removed games lose their cached achievement history and cannot be repopulated until they are present and resynced. The reset must never delete external ROMs, Steam data, game saves, or achievement files in game folders.

“Recognized” means a confirmed provider match for a locally present game, not proof that the user launched it. Do not base eligibility on PFP's `totalPlayTimeMillis` or `lastPlayedAt`: the current `recordPlaySession()` method has no production caller. Steam's own reported playtime may be used as a **change hint for matched Steam games**, never as proof of installation or as the sole freshness rule.

| State | Tracked/Player Status | Automatic refresh | Manual action |
| --- | --- | --- | --- |
| Present + confirmed provider match | Include | Yes, subject to freshness rules | Refresh this game |
| Previously present + confirmed match, now removed/missing | Include, labeled “Not installed” | No | View cached achievements; no implicit refresh |
| Present but unlinked | Untracked | No achievement detail request | Auto-match or manual link |
| Never locally matched, including old account import | Exclude from counts and lists | No | Explicit provider search preview |
| Provider search result, no local game | Preview only | No | View; close preview without persisting |

## 2. Existing implementation and reuse

- `AchievementAutoMatcher.matchUnlinked()` walks `GameRepository.observeGamesOnly()` and links RA ROMs by content hash or Windows games by Steam identity/title. Restrict the candidate list to actually present games, using the established `isMissing` flag plus provider-specific availability checks; do not equate `contentType = GAME` with installed.
- `provider_game_links` relates a game row to a provider ID and cascade-deletes on game deletion. `account_achievement_sets` and per-coin rows survive independently. That surviving set does **not** prove a former local match: account imports create identical-looking orphan sets.
- `RaAccountImporter` walks all account completion progress. `SteamAccountImporter` gets all owned games then probes candidates. Settings retains methods/worker state for both imports, though the current Settings screen does not expose their import buttons. Remove or explicitly disable these entry points in the new flow; do not accidentally invoke them from setup, workers, or later UI.
- `AchievementRepository.syncAllLinked()` currently refreshes links, live local Steam folders, and **every account-only set**. This account-only sweep must be replaced. The local Steam discovery path should keep its current ability to represent recognized folders with no library game row.
- `AccountAchievementSetDao.observeAccountSets()`, wallet SQL, rarest/recent coin queries, `ShibaLibraryViewModel`, and `PlayerStatusViewModel` currently read all stored account sets. Apply eligibility consistently at the data/query boundary, not only as a screen filter.
- `SteamAppListResolver.search()` already provides query-based Steam candidates. RA's existing per-console game-list/hash infrastructure can be reused for a cached RA title picker; verify exact catalog behavior before implementation. `RaRemoteDataSource.fetch()` and `SteamRemoteDataSource.fetch()` supply detail but the repository's `syncEntry()` persists it; preview must call a read-only path.
- `BackgroundTaskNotifier` currently posts quiet Android system notifications; the in-app notification tray is being built separately. Integrate with the actual tray interface on Claude's implementation branch, retaining system notification behavior only where that branch requires it. Do not invent a second tray store.

## 3. Eligibility and persistence rules

Add an explicit, durable **confirmed-local-match ledger**, keyed by `(provider, providerGameId)` rather than volatile `gameId`. Store a display title, first/last matched timestamps and a current-presence status or derive current presence by reconciling live links and discovered local Steam folders. Preserve a distinct game/link association for multiple local copies mapping to the same provider identity. A successful automatic or manual match for a present game writes the ledger even before a coin fetch succeeds. A provider search preview never does.

On game removal, `isMissing`, or loss of a local Steam folder, keep the confirmed identity and its cached set. On reappearance, reconnect by provider identity without duplicating the set or changing its historic status. For a deliberate **Unlink** action, stop automatic updates of that game, leave prior cached coins alone until the product's existing unlink behavior is resolved, and avoid relinking it silently in the same pass; document the existing semantics before coding this edge. Deduplicate batch work by `(provider, providerGameId)`.

Migration from the current database must preserve all rows and user progress. Seed the confirmed ledger **only** from live valid provider links and verified present local Steam folders at migration/reconciliation time. Do not infer a former match from a bare `account_achievement_sets` row, since old RA/Steam imports created those too. Unverifiable old orphan imports remain stored but are excluded from tracked views, wallet, feeds, and sync. Do not perform destructive migration or purge them in this task. Backups and restores must include the new ledger and any new sync metadata.

## 4. Efficient refresh policy

**Implementation defaults; make scheduling values constants and document them.** Trigger one unique, constrained check when PFP is used / the library is reconciled, provided that the provider has not been checked in the preceding 24 hours. Do not wake the device every day solely to poll providers. Use network-required and reasonable battery constraints; use a single-flight mutex/unique-work policy so Settings Sync All, Player Status Sync All, per-game Refresh, and automatic work cannot duplicate the same provider ID concurrently. Keep the existing provider pacing (currently about 1.1 seconds between requests), obey server retry hints where available, and use exponential backoff with jitter for transient failures. No requests if there are no currently present matched IDs for a provider. Persist check timestamps and failure retry times. A provider disconnect leaves cached data visible without retry storms.

For a per-game achievements page, “stale on open” means its full detail was last checked **more than 24 hours ago**. Show cached rows immediately, and perform at most one check for that provider identity per 24 hours unless the user explicitly selects Refresh. Opening the browser list or merely moving focus across rows never triggers per-game requests.

Steam implementation uses only publicly documented Web API methods, the user's own key, and the existing public `https://api.steampowered.com` host. Do not rely on xPaw-listed undocumented `IPlayerService/GetAchievementsProgress`, client endpoints, or partner-only methods. Valve's [API terms](https://steamcommunity.com/dev/apiterms) set a 100,000-call/day ceiling but that ceiling is **not** an operational request budget; minimize calls, disclose stored Steam data, and keep the key confidential. Confirm any proposed API method against [Valve's Web API reference](https://partner.steamgames.com/doc/webapi/IPlayerService) before adding it.

### RetroAchievements

1. For first-time present matches, fetch full per-game details once.
2. On an eligible routine check, call documented `GetUserRecentlyPlayedGames` once (request up to 50), intersect provider game IDs with **present matched RA IDs**, and compare softcore/hardcore counts, scores, and achievable totals with cached lightweight snapshots. Recent entries that changed get full details. This recent list is capped, so it is a fast signal rather than complete coverage.
3. Also partition **all present matched RA IDs** into seven stable daily cohorts. On each eligible day, use documented `GetUserProgress` for that day's cohort, split into URL-safe bounded batches (start with at most 50 IDs; measure URL length; reduce on URI-too-long responses). Compare the same progress summary; fetch full details only for changed IDs. Record completed cohort dates and resume missed cohorts on subsequent PFP use without firing seven cohorts on one day. For 1,000 installed RA identities this is about 20 targeted summary requests per seven active days, plus at most one recent-games request per active day. Never include historic removed games.
4. Fetch `GetGameInfoAndUserProgress` only for changed games, newly recognized games, an explicit per-game Refresh, or when that specific game's page opens with stale details. Counts alone can miss an achievement-list edit with unchanged totals, so page-open refresh is the fallback. Do not schedule a full-detail sweep of all installed games.
5. RA hash-map/catalog requests are match-time work, cached per console. Do not fetch console catalogs on each background refresh or each typed search character.

### Steam

1. Use **Steam-reported** `playtime_forever` from one `GetOwnedGames` check, compare only present matched Steam app IDs with the saved playtime at last successful detail fetch. Prefer a filtered app-ID request if the current Retrofit definition and response semantics support it; otherwise one owned-games response is acceptable, but never probe every owned title. Verify `include_played_free_games` and privacy behavior before changing the client.
2. Fetch achievement details for newly recognized games and games with changed Steam playtime. Cache schema and global rarity separately from player unlocks so a routine changed-playtime sync can refresh player unlocks without automatically requesting schema and rarity every time. Refresh schema/rarity on first fetch, explicit per-game refresh, or when an actively viewed game's metadata is older than 30 days; maintain correct hidden-achievement behavior. Do **not** rotate a full-detail expiry check through every installed game.
3. Steam playtime is an imperfect signal: achievements can change without a playtime increase. Offer **Refresh this game** and a stale-on-open check for the specific game the user is viewing. If Game Details are private or `GetOwnedGames` fails, retain cached results, show a clear status, and limit automatic fallback to games the user explicitly opens or refreshes. Never interpret a private/empty response as an empty installed library.
4. The old `steam_owned_games` cache may still serve local Steam ownership classification. Do not delete that table just because the account-wide achievement importer is retired.

### Local Steam and other local providers

For a LOCAL_STEAM Windows game **launched through PFP**, remember the game ID and LOCAL_STEAM app ID at dispatch. When PFP returns to the foreground after a confirmed handoff, check that game's local GSE/Goldberg progress file. Compare the parsed earned-state fingerprint with the last cached state; update PFP's coins only if it changed. This is a local read and should make **zero Steam API requests** when a previously cached schema and rarity are available. A missing/unreadable progress file is an unknown state, never proof that earned achievements were lost. Debounce rapid resume callbacks and coordinate with any sync in flight.

PFP already has `MainActivity.onResume()`/`onStop()` and `LaunchDispatcher` outcomes with launch and return times, so this does **not** require implementing PFP-measured playtime. Some Windows shortcut launch paths call `LauncherShortcutRepository.launch()` directly, bypassing `LaunchDispatcher`; route them through an equivalent launch/return marker before claiming this covers all local games. Resuming from an unrelated activity must not refresh achievements. Games started outside PFP have no launch marker; an occasional local-only file check or explicit Refresh covers them.

The current `LocalSteamSource.fetch()` rereads the local progress file **and** requests Steam schema and global rarity each time (plus possible hidden-description enrichment). Separate locally cached schema/rarity from earned-state reads before connecting the resume trigger. Fetch Steam metadata only for a new app ID, missing/stale metadata being actively viewed, or explicit repair. For providers without a reliable cheap change signal (including VITA_TROPHY if supported by the active branch), refresh only their present matched entries on a conservative interval and expose manual per-game refresh. Steam account playtime is never a prerequisite for local emulated games.

### Priority and safety

Priority: explicit per-game refresh > newly matched game > changed recent-summary/playtime > RA-only rotating **summary** catch-up > stale-on-open detail. UI reads cached Room rows immediately. Never block navigation on a background sync. A missing match, `NotFound`, temporary network failure, 401/403, 429, or cancellation must not delete cached earned achievements. Use a transaction when replacing a set and its per-coin rows so an interrupted request does not expose an empty set. Suppress overlapping runs and count a provider identity once even when multiple games link to it.

## 5. Itemized implementation tasks for Claude

Each task should be a separate, reviewable change. Before Task 1, Claude should inspect the implementation branch for the notification tray and any newer achievement code, then adjust file paths and migration version to the actual HEAD. Do not implement downstream tasks against a guessed tray API.

### Task 1 — Confirm baseline and data ownership

**Files:** `AchievementRepository.kt`, `AchievementController.kt`, `AchievementAutoMatcher.kt`, `RaAccountImporter.kt`, `SteamAccountImporter.kt`, `SteamImportWorker.kt`, `PFPDatabase.kt`, `BackupManager.kt`, current tray code.

- Map every entry point for import, sync, auto-match, per-game refresh, and local Steam scan.
- Identify the current tray's event model and existing notification strings; reuse its IDs, severity and deduplication rules.
- Record the actual database version and whether Room schema export/migration tests are required.

**Done when:** a short implementation note identifies the exact active entry points and the tray integration method. No behavior changes yet.

### Task 2 — Add confirmed-local-match storage and migration

**Files:** new ledger entity/DAO, `PFPDatabase.kt`, `DatabaseModule.kt`, exported Room schema, backup/restore paths.

- Create provider-keyed durable match identity and status metadata; preserve distinct copies where needed.
- Seed from verified live links only; reconcile discovered local Steam folder identities after storage is available.
- Do not label unlinked account-imported sets as historical matches.
- Include ledger and sync metadata in backup/restore and verify old backups can still load.

**Done when:** migration preserves coins, only verifiable local matches enter the ledger, and reinstall/relink maps to one provider set.

### Task 3 — Reconcile presence and matching

**Files:** `AchievementAutoMatcher.kt`, link/unlink paths, local Steam discovery/reconcile, game scan hooks.

- Match only locally present, eligible games; avoid repeated match requests for `isMissing` games.
- Mark matched IDs present; mark absent IDs historical without deleting earned data.
- Preserve formerly matched local Steam folders when their folder disappears; change `syncAllLinked()`'s current pruning rule.
- Record the LOCAL_STEAM game/app identity for PFP-initiated Windows launches across both `LaunchDispatcher` and direct shortcut paths; trigger only after a confirmed foreground handoff and return, not on every `onResume()`.
- Define behavior for manual unlink and provider identity changes without stale active links.

**Done when:** add → match → remove → reinstall keeps one history entry, and a library of uninstalled account games creates no match queue.

### Task 4 — Apply eligibility to every achievement projection

**Files:** `AccountAchievementSetDao.kt`, `AccountAchievementDao.kt`, `AchievementRepository.kt`, `LibraryStanding` mapping, wallet/recent/rarest SQL, related ViewModels.

- Restrict tracked rows, wallet, recent/rarest feeds, status counts, and sync queues to confirmed ledger identities.
- Include historical confirmed IDs in display and wallet, with “Not installed” / offline status; exclude them from automatic refresh.
- Keep Untracked limited to present eligible library games, with current match-note behavior.

**Done when:** old orphan account imports contribute zero to the visible wallet/counts and historical matched games still contribute their cached earned coins.

### Task 5 — Build a single selective sync coordinator

**Files:** `AchievementRepository.kt`, `AchievementController.kt`, new coordinator/worker, Settings and Player Status sync callers.

- Replace all-sets `syncAllLinked()` behavior with a deduplicated snapshot of currently present confirmed identities, including present local Steam folders.
- Route explicit sync, automatic checks, and game refresh through one queue/lock and return counts for checked, updated, unchanged, skipped, failed.
- Persist per-provider and per-ID check times, detail times, retry state, and enough comparison snapshots; cancel cleanly.
- Coordinate a local-file-only return check for the one launched LOCAL_STEAM game, including duplicate resume suppression; this check does not consume a Steam Web API request.
- Stop invoking account-wide RA and Steam importer loops; retire their UI/worker entry points safely.

**Done when:** Sync All never walks historic or preview-only entries, never probes every owned Steam game, and two simultaneous triggers perform one fetch per identity.

### Task 6 — RA summary-first checks

**Files:** `RaRemoteDataSource.kt`, RA client adapter, new summary model/DAO, coordinator.

- Add one recent-played check on eligible active days and a stable seven-cohort `GetUserProgress` catch-up for all present matched IDs; compare counts/scores, including hardcore. Do not catch up seven missed cohorts in one session.
- Fetch full details only on first match, changed summary, explicit refresh, or stale-on-open for that game; any slow rotation uses batched summaries, never full detail for every installed game.
- Handle partial batch responses, invalid IDs, overly long URLs, cancellation, credential errors and retryable failures without clearing old rows.

**Done when:** an unchanged day uses one recent-games call plus that day's bounded summary cohort and makes no per-game detail requests; all installed IDs eventually receive a summary check across seven active days.

### Task 7 — Steam playtime-first checks and tiered cache

**Files:** `SteamRemoteDataSource.kt`, `SteamWebApi.kt`, `SteamOwnedGamesDao.kt`, coordinator, `SteamAccountImporter.kt` retirement.

- Read Steam playtime once per scheduled check, filtered to current matched IDs where possible.
- Update a current game's player achievements only if first seen, playtime changed, explicitly refreshed, or that **specific game** is opened with stale data. Do not schedule a full-detail sweep through all installed games.
- Cache schema/rarity separately and use them on the documented `GetPlayerAchievements` path; refresh metadata when first seen, explicitly refreshed, or stale on that game's page. Keep privacy/empty/error results separate from real no-achievement responses.
- Use only Valve-documented `GetOwnedGames`, `GetPlayerAchievements`, `GetSchemaForGame`, and `GetGlobalAchievementPercentagesForApp` on the public host. Do not test or adopt the undocumented batched achievements method for this implementation.
- Preserve Steam ownership classification and handle local Steam separately.
- Split `LocalSteamSource.fetch()` so a return-triggered earned-state update reads local progress and cached schema/rarity, without repeating its present schema, rarity, or hidden-description network calls.

**Done when:** an unchanged installed Steam library does not make per-game achievement requests, while one changed app ID updates only that app's player data.

### Task 8 — Add explicit provider search preview

**Files:** `ShibaLibraryViewModel.kt`, `ShibaLibraryScreen.kt`, `ShibaCoinsViewModel.kt` or a dedicated preview state/screen, controller/provider search adapters.

- Retain the current pinned Search row as a **local tracked/untracked filter**. Add “Search online” as an explicit control accessible by touch and controller; let the user choose Steam or RA (and RA platform when needed).
- Steam: reuse `SteamAppListResolver.search()` with debounce, cancellation, minimum query length and capped results. RA: reuse or cache an appropriate game title catalog by console; do not fetch a full console list per keystroke. Show title/platform/provider to avoid wrong-game selection.
- Fetch preview details without calling the persistent `syncEntry()` path. Show available achievements and earned state where credentials permit, with a clear “Preview · Not installed” label. Closing preview must discard it; repeated open during the same session may reuse an in-memory result.
- Provide empty, error, credentials and offline states. Do not show an arbitrary remote search result as a confirmed match.

**Done when:** searching and opening an absent game changes no achievement, wallet, ledger or sync database rows.

### Task 9 — Refresh entry points and user status

**Files:** `ShibaCoinsViewModel.kt`, `ShibaLibraryViewModel.kt`, `PlayerStatusViewModel.kt`, `AchievementsSettingsViewModel.kt` and screens.

- Rename Sync All UI copy to “Update installed achievements”; auto-match still precedes a refresh of newly matched games.
- Expose “Refresh this game” for present matched entries; historical entries show cached date and “Not installed.” Previews use “Refresh preview” and stay temporary.
- Surface last checked, last updated, and failed/queued states without presenting an unchanged check as newly earned achievements.

**Done when:** touch/controller flows and Settings/Player Status initiate the same selective coordinator, and historical games cannot enter a background batch.

### Task 10 — Add Clear all tracked achievements control and confirmation

**Files:** `AchievementsSettingsScreen.kt`, `AchievementsSettingsViewModel.kt`, achievement reset coordinator/repository, DAOs and sync scheduling state.

- Add **Clear all tracked achievements** under the existing Achievements settings section, separated from the Update Achievements actions and visually marked destructive. Keep it accessible by touch and controller. Do not make it a one-press action or place it inside Sync All.
- Show a modal with this exact intent (adjust line wrapping to the UI):

  **Title:** `Clear all tracked achievements?`  
  **Body:** `This will remove all achievements recorded in Play Field Portal, including earned progress and records for games that are no longer installed. Your games and provider connections will stay. Games will have to be resynced to show their achievements again.`  
  **Buttons:** `Cancel` (default focus) and `Clear achievements` (destructive). Back/outside dismisses without modifying data.

- Confirmation cancels/awaits any active achievement work and prevents new jobs from starting until the reset transaction finishes. Delete all PFP achievement set summaries and per-coin rows across providers, including hidden legacy account imports and historical entries, plus summary snapshots, sync bookmarks and achievement-only match notes. Clear the confirmed-local-match **history ledger** so removed games no longer appear as previously matched. Retain current library game rows, provider credentials, and their valid provider links; rebuild the confirmed ledger from currently present valid links/local Steam folders only when a user-initiated resync runs. Do not clear the Steam-owned cache if local ownership classification uses it, but invalidate its achievement sync bookmarks.
- Reset wallet, recent/rarest feeds, tracked count, and last-achievement-sync presentation immediately after commit. Show the current matched games as awaiting sync rather than falsely showing 0% earned or an empty successful set. Historical removed games disappear because their recorded achievements are gone.
- Pause scheduled achievement refresh after clear until the user explicitly selects **Update installed achievements** or refreshes an individual present game. Show `Updates paused until you resync` in Settings. A confirmed manual refresh repopulates the ledger/set for eligible present games and re-enables the normal schedule. A provider search preview remains nonpersistent.
- Treat the destructive reset as an atomic operation coordinated with sync; a cancelled work item cannot write an old fetch back after the clear. Avoid claiming records were removed if the transaction fails. Existing backups continue to contain whatever was saved at backup time; the confirmation copy need not promise deletion from backups.

**Done when:** Cancel changes nothing; Confirm clears all PFP-recorded achievement data and holds automatic resync; only an explicit resync restores currently present matched games. No external game or Steam data is altered.

### Task 11 — Integrate notification tray messages

**Files:** current tray's event/publisher/store components on Claude's branch; `BackgroundTaskNotifier`/worker integration if needed.

Use **one stable task ID** for an achievement update, update its running progress rather than stacking messages, and create one terminal summary. Tray events should be persistent/dismissible according to the tray's existing policy; quiet routine checks with no changes should not create an unread item. If system background notifications remain required, mirror the same task ID and summary rather than posting conflicting outcomes. Do not create one notification per game or per earned coin during a batch.

| Event | Tray message / copy | Trigger and behavior |
| --- | --- | --- |
| Initial / explicit sync running | **Updating installed achievements** · `Checking {done} of {total} games` | Show progress for user-initiated update or work lasting long enough to be noticeable; use indeterminate state until total known. |
| New matches from auto-match | **Games recognized** · `{count} installed games matched. Updating their achievements.` | One grouped message per auto-match run, only when count > 0; avoid a second separate success message if the sync summary follows immediately. |
| Updates found | **Achievements updated** · `{updated} games updated · {unchanged} unchanged` | One terminal summary if data/progress actually changed; tapping opens Tracked Games or Player Status using the tray's normal navigation. |
| No changes after user request | **Achievements are up to date** · `{checked} installed games checked` | Explicit manual Sync All only; quiet scheduled check creates no unread tray item. |
| Partial failure | **Some achievements couldn't update** · `{failed} of {total} games need another try` | Keep cached progress; provide retry action if the tray supports actions; show provider/reason in task detail without exposing API keys. |
| Offline / credentials / private Steam details | **Achievement update paused** · `Connect to the internet`, `Reconnect {provider}`, or `Steam Game Details aren't available` | Emit once per distinct actionable condition, dedupe recurring scheduled attempts, route to existing settings; never report an empty account as success. |
| User cancels a manual batch | **Achievement update stopped** · `{checked} games checked; saved progress kept` | Only if a visible user-initiated job was cancelled. |
| Newly installed game initial detail ready | **Achievements ready for {game}** | Optional single-game message only after an explicit match, when user leaves that screen before completion; suppress if the user is watching it. |
| User confirms achievement reset | **Achievement records cleared** · `Installed games need to be resynced to show achievements again` | One dismissible result after the reset transaction commits. Do not post on Cancel or failure; if reset fails, show **Couldn’t clear achievements** with a retry path. |

The tray also needs task metadata: task ID, category `Achievements`, source (manual/auto), progress, result counts, timestamp, destination, and whether it should alert or silently update. Map these to the actual tray model discovered in Task 1. The current system notifier uses `running`, `complete`, and `failed`; preserve its quiet channel behavior where applicable. No message should claim “new achievements unlocked” merely because fresh cached data was fetched.

**Done when:** one manual run produces at most one running item and one terminal result; scheduled unchanged runs do not fill the tray; failure copy is actionable and credentials are never logged or displayed.

### Task 12 — Migration and regression tests

**Tests:** Room migration, repository/DAO eligibility, RA targeted batching, Steam playtime comparison, concurrent coordinator, preview, ViewModel/notification state.

- Fixture: old database with linked present RA/Steam sets, orphan imported sets, missing games, a vanished local Steam folder, shared provider IDs, and earned coins. Verify preserved rows and correctly scoped wallet, feeds and queue.
- Provider fakes: unchanged RA recent/cohort summaries → no full fetch; changed hardcore summary → one full fetch; stable cohorts cover all present RA identities over seven active days without burst catch-up; unchanged Steam playtime → no player call; changed one app ID → only it refreshes; schema expiry does not fetch schema for every title simultaneously.
- Failure/cancellation: partial batch, private Steam Game Details, 401/403/429, network loss, concurrent manual/background jobs; cached coins remain intact.
- Preview: search → open → close makes no ledger, set, coin, wallet or job writes. Reinstall/duplicate-copy transitions keep one provider identity.
- Local Windows return: a confirmed PFP launch and foreground return checks only the launched game; unchanged local progress causes no Steam calls, coin rewrite, or unread tray item; changed progress updates PFP from cached metadata. Rejected/never-foregrounded and unrelated resume do nothing. A missing file does not wipe earned coins. Test the shortcut paths as well as `LaunchDispatcher`.
- Tray: manual, automatic, partial failure and cancellation messages follow the table without duplicates.
- Reset: Cancel leaves every row and job unchanged; Confirm during an in-flight fetch cancels/joins it, clears sets/coins across providers and old imports, resets wallet/feeds/timestamps, preserves games/credentials/current links, removes historical entries, prevents late writes and pauses automatic work. Explicit manual resync rebuilds current entries only; device backups remain intact.

**Done when:** focused tests and a migration test pass on the actual branch; no assertion depends on account-wide import behavior.

### Task 13 — Device verification and documentation

- Test with a small RA + Steam installed library and a Steam account owning far more games than are installed. Capture endpoint counts and confirm no network work for removed/historical games or search previews after they close.
- Confirm native Android games, missing ROMs, multi-disc entries, Windows shortcuts, local Steam folder discovery, and offline startup. Verify controller and touch search flows.
- Test the destructive Settings modal with controller and touch, confirm default focus/cancel behavior, clear during a running update, confirm no immediate automatic repopulation, then manually resync one installed game and check the tray message.
- Update architecture/user docs and remove misleading “Import whole history/library” and “Sync All Games” wording. Do not remove legacy data as a cleanup shortcut.

**Done when:** device behavior and call counts match Section 7, and on-screen/tray copy matches real eligibility.

## 6. Recommended implementation phases and Claude handoff

1. **Foundation:** Tasks 1–3. Commit/submit storage and match-presence changes independently; inspect migration fixture before UI changes.
2. **Correct scope:** Tasks 4–5. Ensure every display and batch query uses one eligibility rule.
3. **Provider optimization:** Tasks 6–7. Keep exact request-count tests alongside each provider.
4. **User flow and reset:** Tasks 8–11. Integrate with the notification tray version on the working branch; coordinate the destructive reset with all background work.
5. **Verification:** Tasks 12–13. Run focused tests after each changed area and a final device pass.

For each task, Claude should state the files changed, the observed behavior, tests run, and any remaining risk before starting the next task. Stop if the actual branch contradicts a core eligibility rule or if a destructive migration would be needed. Do not broaden the scope into local PFP playtime tracking.

## 7. Acceptance criteria and request budget examples

- With 40 present matched games and 1,000 Steam-owned games, routine update scopes only the 40 present identities. No full Steam-library achievement import or RA completion-history walk occurs.
- With unchanged progress on an eligible active day: RA uses one recent-games request plus bounded `GetUserProgress` batches for one-seventh of installed matched RA IDs; Steam uses one owned/playtime check. No per-game detail requests occur for unchanged games the user has not opened. The exact RA request count depends on URL-safe batch splitting and provider availability.
- Returning from a LOCAL_STEAM game launched through PFP checks only its local achievement file. With cached schema/rarity, the return path makes zero Steam API calls whether local unlocks changed or not.
- A newly matched game fetches full details once; another game linked to the same provider ID shares that set and request.
- A removed game keeps its cached achievements and contributes to historical wallet/display, but generates zero automatic provider calls.
- A provider-search preview never changes tracked count, wallet, history or sync queue.
- Scheduled unchanged checks remain quiet in the tray. Manual checks provide a visible result; failures preserve data and show actionable, deduplicated messages.
- No production code uses PFP-measured playtime as the Steam change detector.
- The Achievements settings Clear control always requires confirmation. Confirm removes every PFP-recorded achievement and earned tally, does not touch games/provider credentials/external files, and does not automatically refill data until the user explicitly resyncs.

## 8. Verification commands (adjust to the implementation branch)

```bash
./gradlew :core:core-data:testDebugUnitTest
./gradlew :feature:feature-achievements:testDebugUnitTest
./gradlew :feature:feature-xmb:testDebugUnitTest
./gradlew :feature:feature-settings:testDebugUnitTest
./gradlew :app:assembleDebug
```

Run the targeted new test classes first. Do not treat a network-dependent live account check as a unit test. Record actual API request counts from a local fake/interceptor and verify a real device with the user's configured providers separately.

## 9. Claude handoff

This is the approved plan for implementation. Begin with Task 1 against Claude's current repository branch, reconcile any newer notification-tray code, and complete tasks in the specified phases. Keep each change reviewable and report provider request counts, migration results, and device checks before calling the implementation complete.
