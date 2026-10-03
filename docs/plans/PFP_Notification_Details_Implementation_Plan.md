# Play Field Portal — Notification Details, Results and Stop: Implementation Plan

Adds two new depths to the notification panel, a way to stop running work from it, and takes every
PFP notification out of the Android shade except music playback.

- **Detailed Notes (Type 1).** Confirm on the row opens a **Notes sheet** that explains what
  happened, why, and what to do, with an error code.
- **List (Type 2).** Confirm opens a **Results sheet**: every item a scan, import, sync or restore
  touched, failures first, filtered with L1/R1.
- **Stop.** Running rows that can be stopped become focusable; Confirm asks before stopping.
- **Launcher only.** Notifications live in the panel. The shade keeps only the music playback
  notification, which Android requires.

**Status: implemented (2026-10-01), awaiting device verification (§12 manual checks).** Every
design decision is settled (§1) and the art is approved (§7).

**Where the code departs from this plan:**
- **Some rows stay Simple because the producer only has counts:** a single card's successful scan,
  the Vita and Windows scans, artwork relink, and auto-match's unmatched games. Their failures are
  still Notes with codes. Giving them Results needs per-item data the producers don't collect yet.
- **Artwork export lists only its failures.** An export copies thousands of files; the successes are
  counted in the body.
- **Launch preflight failures** default to `LN-9001`; only "no emulator assigned" is tagged
  `LN-1001`. The others arrive as free text from the resolver and aren't classified yet.
- **Worker stops are always recorded.** A WorkManager cancel, from the panel or from Settings'
  own Cancel, settles as a quiet stopped row. Coroutine producers use `settleCancelled`, which drops
  the row when the cancellation wasn't a user stop.
- **Media scans stop one folder at a time**: a stop cancels that folder's child job and the pass
  carries on with the next folder.
- **The shortcut modal** also appears whenever the XMB is free and a request is waiting, not only
  on resume, because the runtime-registered receiver can queue one while PFP is in front.
- **Tests:** there is no `XMBViewModel` harness, so the panel's decisions live in pure functions
  (`panelConfirm`, `NotificationPanelState.reconcile`, `panelSelection`) tested in
  `NotificationPanelLogicTest`. The shortcut queue's rules are tested through `PendingShortcutQueue`.

- Mockups: [Notification Details Mockups](https://claude.ai/artifact/3m5RaP6S2zNu3ykeMmwrqq), seven
  1920×1080 artboards drawn over Odin3 screenshots.
- Error codes: [PFP_Notification_Error_Codes.md](PFP_Notification_Error_Codes.md).
- Builds on [PFP_Notification_Panel_Implementation_Plan.md](PFP_Notification_Panel_Implementation_Plan.md).

---

## 1. Settled decisions

| # | Decision |
|---|---|
| D1 | A row can be **Simple** (today), **Notes** or **Results**. The depth is set by whether the row carries a `NotificationDetail`. |
| D2 | Confirm on a Notes or Results row **opens the sheet**. The row's `NotificationAction` moves into the sheet as the ✕ button ("Go to Game", "Open Memory Card"). Confirm on a Simple row works as it does today. |
| D3 | Confirm on **any** row **marks it read**. It never deletes it. Detail rows are marked read as the sheet opens, so they can be reopened until retention or Clear All removes them. |
| D4 | History rows stay **single-line**. The title must make sense on its own, and `settle()` stops writing `"$title — $message"`. |
| D5 | Sony-style error codes `AA-BNNN` appear on WARNING/ERROR Notes sheets and on individual Results items. The registry is approved (see the codes doc). |
| D6 | Running rows with a stop handler are focusable. ✕ opens a confirm with **Keep Running** focused. A stopped task records a WARNING row that is **already read and silent**, with a Results list of what got done. |
| D7 | **Stoppable:** card, Vita, PC, ROM Root and media scans; artwork scrape; metadata update; relink; export; import; storefront sync; achievement update and auto-match; Windows batch match. **Not stoppable:** backup restore, internal artwork migration, Goldberg convert. |
| D8 | The shade is **launcher-free** except `MusicPlaybackService`'s MediaStyle foreground notification. |
| D9 | The legacy INSTALL_SHORTCUT Add/Ignore confirmation moves into the launcher as a confirm modal with **Ignore** focused, plus a tray row. |
| D10 | `POST_NOTIFICATIONS` and its startup request go away. Media-session notifications are exempt from that permission. |

---

## 2. What exists today

These are the facts the plan relies on. Line numbers are as of commit `5960678c`.

### 2.1 Model and storage

- **`PfpNotification`** ([PfpNotification.kt:12](../../core/core-domain/src/main/kotlin/com/playfieldportal/core/domain/model/PfpNotification.kt)) has an opaque `payload: String?` that **no producer writes**. This is where detail goes: the `notifications` table already has the column, so **no Room migration is needed** (DB stays at v54).
- **`NotificationRepository.post(...)`** ([NotificationRepository.kt:35](../../core/core-domain/src/main/kotlin/com/playfieldportal/core/domain/repository/NotificationRepository.kt)) replaces any existing row with the same `sourceKey` and resets it to unread. It can't post a row as read yet.
- **`NotificationAction`** ([PfpNotification.kt:64-116](../../core/core-domain/src/main/kotlin/com/playfieldportal/core/domain/model/PfpNotification.kt)) is stored as a `typeKey`/`arg` pair.
- **Serialization:** `kotlinx.serialization` is already applied in `core-domain`, `core-data` and `feature-xmb`.

### 2.2 `BackgroundTaskCenter`

[BackgroundTaskCenter.kt](../../core/core-ui/src/main/kotlin/com/playfieldportal/core/ui/notification/BackgroundTaskCenter.kt) is the one place tasks start, update and settle:

- **API:** `start` / `progress` / `complete` / `fail` / `report` / `cancel`.
- **`settle()`** (:166-202):
  - writes `title = "$title — $message"` and `body = message`;
  - mirrors the row to the shade through `BackgroundTaskNotifier` when `mirrorToShade` is on;
  - plays `MenuSound.NOTIFICATION`.
- **It holds no way to stop work.** `cancel(id)` only drops the row.

### 2.3 Panel

- **Rows:** `NotificationPanel.kt`. The `HistoryRow` title is `maxLines = 1`, and `body` is never drawn.
- **Cursor:** skips running rows (`NotificationRows.kt:64-89`).
- **Opening a row:** XMBVM `activateNotificationRow` (:8445) marks the row read and runs its action. `None` and `OpenUrl` fall back to `showNotificationText`, which opens an `InfoDialogState` that `shellModalSpec` turns into a `PfpModalSpec.Notice`. That Notice is today's only way to see a row's body.
- **Input:** the panel captures input at XMBVM:6093-6107. HOME toggles it at :6081-6087.

### 2.4 Modals

- **`PfpModalSpec`** ([PfpModalHost.kt:41-84](../../core/core-ui/src/main/kotlin/com/playfieldportal/core/ui/components/PfpModalHost.kt)) has three variants: Confirm, Notice and TextEntry.
- **`rememberPfpModalHost`** scrolls Confirm and Notice by 96dp on UP/DOWN.
- **The XMB shell** hosts it at `XMBShell.kt:1450-1471`, and presses are forwarded through `forwardToShellModal` (XMBVM:10455).
- **Scaffold values** (`PfpModals.kt:222-226, 529-603`): scrim `0xCC000000`, card `0xFF15151F`, card width 440dp, radius 16, padding 24, title 19sp Light, rule at 30% white, body 15/22sp at 70%.

### 2.5 The shade today

| Source | Channel | Fate |
|---|---|---|
| `BackgroundTaskNotifier` (core-ui) | `pfp_background_tasks` | **Delete** |
| `WindowsSetupNotifications` (app/pin) | `pfp_windows_library_setup` | **Delete.** Tray row plus the existing `showWindowsSetupPrompt` |
| `InstallShortcutReceiver` (app/receiver) | `pfp_shortcut_requests` | **Replace** with an in-launcher modal (§9.3) |
| `MusicPlaybackService` (feature-xmb) | `pfp_music_playback` | **Keep** (foreground service, `mediaPlayback`) |

---

## 3. Scope

**In scope**
- The detail model and its codec.
- The error-code registry.
- Center API changes, including stop.
- Panel row affordances, cursor and routing.
- The Notes and Results sheets.
- The Stop confirm.
- Removing the shade and its permission.
- The shortcut-request modal.
- Moving every producer listed in §10 onto the new API.

**Out of scope**
- Per-row delete. `repo.delete` exists but has no UI, and that stays true.
- `OpenUrl` handling.
- `LibraryScanner` per-file progress (still the later change from the panel plan's §4.3).
- RSS and download feeds.
- Notifications on screens other than the XMB shell. Settings screens open the same sheets only if the user opens the panel.

---

## 4. Domain model (`core:core-domain`)

### 4.1 `NotificationDetail`

The new file `model/NotificationDetail.kt`:

```kotlin
@Serializable
sealed interface NotificationDetail {

    /** Type 1. Sections render in order; [code] and the help copy come from [PfpErrorCode]. */
    @Serializable @SerialName("notes")
    data class Notes(
        val summary: String? = null,                 // "What happened"
        val sections: List<NoteSection> = emptyList(),
        val facts: List<NoteFact> = emptyList(),     // key/value box ("Emulator: DuckStation")
        val code: String? = null,                    // PfpErrorCode.id, e.g. "LN-4003"
        val diagnostic: String? = null,              // collapsed, monospace, copied by △
    ) : NotificationDetail

    /** Type 2. Groups are fixed buckets; the sheet orders and filters by them. */
    @Serializable @SerialName("results")
    data class Results(
        val summary: String? = null,
        val items: List<ResultItem>,
        val truncated: Int = 0,                      // "+N more" row when > MAX_ITEMS
    ) : NotificationDetail
}

@Serializable data class NoteSection(val heading: String, val body: String)
@Serializable data class NoteFact(val label: String, val value: String)

@Serializable
data class ResultItem(
    val primary: String,                 // "PSP", "Crash Bandicoot (USA).chd"
    val outcome: ResultOutcome,          // FAILED, SKIPPED, DONE
    val badge: String? = null,           // "+31", "Failed"; null → outcome's label
    val reason: String? = null,          // shown in the detail pane
    val code: String? = null,            // "SC-2001"
    val path: String? = null,            // monospace box in the detail pane
    val action: DetailAction? = null,    // ✕ on the focused item
)

@Serializable enum class ResultOutcome { FAILED, SKIPPED, DONE }

/** NotificationAction in a JSON-safe shape, reusing its existing typeKey/arg encoding. */
@Serializable data class DetailAction(val typeKey: String, val arg: String? = null)
```

**Rules:**
- **Filter tabs:** `ResultOutcome` gives the three buckets. The tabs are All / Failed / Skipped / Added. "Added" is the user-facing label for `DONE` on scans; each producer chooses the label through `ResultsLabels` (§4.4).
- **Item cap:** `Results` holds at most `MAX_ITEMS = 500`. Producers call `NotificationDetail.results(items)`, which sorts failures first, cuts the list to the cap and sets `truncated`.
- **Size cap:** the encoded payload must stay under `MAX_PAYLOAD_BYTES = 64 * 1024`. Past that, the codec drops `diagnostic` first, then trims `items` further and increases `truncated`. This keeps a 200-row history from growing past a few MB.
- **Conversion:** `DetailAction` ⇄ `NotificationAction` goes through the existing `typeKey`/`arg` mapping. Any change to that mapping must be made in both places.

### 4.2 `PfpErrorCode`

The new file `model/PfpErrorCode.kt` is one enum that mirrors the codes doc exactly:

```kotlin
enum class PfpErrorCode(
    val id: String,              // "LN-4003"
    val title: String,           // "The emulator never came to the front"
    val why: String?,            // default "Why" section
    val whatYouCanDo: String,    // default "What you can do" section
) { LN_1001(...), LN_1002(...), /* … */ SY_9001(...) }
```

- **Lookup:** `PfpErrorCode.fromId(id)` falls back to `SY_9001`.
- **Help text:** `Notes` copies `why`/`whatYouCanDo` from the code unless the producer passes its own sections. Help text is written once per code.
- **Registry test:** a unit test parses `docs/plans/PFP_Notification_Error_Codes.md` if it can be found from the test working directory. Otherwise it checks a list hardcoded in the test. Either way it confirms:
  - every doc code exists in the enum, and the enum has none the doc lacks;
  - ids are unique and match `^[A-Z]{2}-[12349]\d{3}$`.

### 4.3 Codec

The new file `model/NotificationDetailCodec.kt`:
- **`encode(detail): String`** applies the size rules above.
- **`decode(payload: String?): NotificationDetail?`** returns `null` on blank or invalid JSON. It never throws, because old rows have `payload = null`.
- **JSON settings:** `ignoreUnknownKeys = true` and `classDiscriminator = "t"`.

Add a derived property to `PfpNotification`:

```kotlin
val detail: NotificationDetail? by lazy { NotificationDetailCodec.decode(payload) }
val depth: NotificationDepth get() = when (detail) { is Notes -> NOTES; is Results -> RESULTS; null -> SIMPLE }
```

### 4.4 `ResultsLabels`

`ResultsLabels(done = "Added", failed = "Failed", skipped = "Skipped")` is stored on `Results` with a scan default. Artwork uses "Updated", and a restore uses "Restored".

---

## 5. Repository and `BackgroundTaskCenter`

### 5.1 `NotificationRepository`

- **`post(...)`** gains `read: Boolean = false`. When true, the row is inserted with `readAt = createdAt`. The replace-by-`sourceKey` path respects it too.
- **`NotificationSettings.mirrorToShade`** is removed. So are the `notifications_mirror_to_shade` preference key and its reader in `NotificationPreferences`. A stale DataStore key is harmless, and the backup format doesn't depend on it.

### 5.2 Center API

```kotlin
fun start(task: BackgroundTaskInfo, onStop: (() -> Unit)? = null)
fun start(id, label, kind, current = null, total = null, onStop: (() -> Unit)? = null)

fun complete(id, message = null, action = None, detail: NotificationDetail? = null)
fun fail(id, message, action = None, detail: NotificationDetail? = null)
fun report(id, label, message = null, severity = INFO, kind = SYSTEM, action = None,
           detail: NotificationDetail? = null)

/** The user asked to stop. Freezes the row as "Stopping…" and calls the producer's handler. */
fun requestStop(id: String): Boolean           // false if not running or not stoppable

/** The producer finished stopping. Records a read, silent WARNING row. */
fun stopped(id: String, message: String? = null, action = None, detail: NotificationDetail? = null)

fun cancel(id: String)                          // unchanged: drop with no row
```

**`BackgroundTaskInfo`** gains:
- `stoppable: Boolean`, set by the center when `onStop != null`;
- `stopping: Boolean`.

**Handlers:** `stopHandles: MutableMap<String, () -> Unit>` lives beside `tasks`, under the same synchronization. Settling or cancelling removes the handle.

**`requestStop`** does the following:
1. Sets `stopping = true` and publishes it. The panel freezes the bar and greys the row.
2. Calls the handle on the caller's thread, and catches and logs anything it throws.
3. Is idempotent. A second request while the task is stopping is a no-op.

**Ticks while stopping:** `progress()` ticks for a task that is stopping are ignored, so the bar stays frozen.

**`settle()` changes:**
- **Title and body:** `title = label` (or the trimmed task label) and `body = message`. They are no longer joined (D4).
- **Detail:** `payload = detail?.let(NotificationDetailCodec::encode)`.
- **Shade:** the `shade` field, `BackgroundTaskNotifier` and every `shade.*` call are deleted.
- **`stopped()`** settles with `severity = WARNING` and `read = true`, and **doesn't** call `menuSound.play`, because the user just stopped the task themselves.

**Titles that need rewording:** removing the `" — message"` join changes what some producers' rows say. Each producer's title has to make sense alone ("Scan All Memory Cards finished", not "Scanning…"). §10 lists the new title for each producer.

### 5.3 Scope for stoppable work

Some `LibraryManagerViewModel` tasks run in `viewModelScope`, so they die when Settings closes and leave a running row with nothing behind it. Fix this by adding a qualifier:

- **Binding:** `@TaskScope CoroutineScope` (SupervisorJob + Dispatchers.Default), bound in a core-ui `TaskScopeModule`.
- **Users:** `LibraryManagerViewModel` scans (Vita :642, console :702, PC :909, batch match :973, ROM Root :1168) launch into it and keep the `Job` for `onStop`. The XMBVM's scan, scrape and metadata functions already outlive screens; they also keep their `Job`.
- **Precedent:** `RescanApplicationScope` already exists in feature-library and shows the pattern.

### 5.4 How each kind of producer stops

| Producer kind | `onStop` | Where `stopped()` is called |
|---|---|---|
| Coroutine (VM or `@TaskScope`) | `{ job.cancel() }` | `catch (e: CancellationException) { tasks.stopped(id, partialMessage, detail = partial); throw e }` |
| `CoroutineWorker` | `{ workManager.cancelUniqueWork(NAME) }` | Same catch inside `doWork()`. WorkManager cancels the coroutine. |
| Non-stoppable (D7) | `null` | n/a |

**Prompt stopping:** scanners and workers call `ensureActive()` (or `yield()`) between files and games, so a stop takes effect within one item. Every loop listed in §10 gets that check.

---

## 6. Panel (`feature-xmb`)

### 6.1 Rows (approved art)

Rows follow artboards 1 and 2 of the mockup.

| Element | Spec |
|---|---|
| **Notes mark** | A 14dp page-with-lines outline at 62% white, 8dp before the unread dot. Only on `depth == NOTES`. |
| **Tally chip** | Height 18dp, horizontal padding 7dp, radius 9dp, fill white 8%, 1dp border at 16% white (read rows: 6% / 12%). One segment per non-zero FAILED/SKIPPED bucket: a 6dp dot (FAILED `#E0574B`, SKIPPED `#D69A3A`) plus the count in 11sp at 88% white (read: 70%). DONE isn't shown. A Results row with no failures or skips shows no chip. |
| **Running, focused** | The same 14% white highlight and 6dp radius as a history row, with padding 5dp × 6dp. |
| **Running, stopping** | The label gets " — Stopping…". Text drops to 55%, the count to 45%, the ring to 22%, and the bar fill to 40% with no animation. |
| **Running, not stoppable** | Unchanged from today, and not focusable. |

These go in a new `ui/NotificationRowParts.kt` (`NotesMark`, `TallyChip`), so `NotificationPanel.kt` stays a layout file. Colours go in `NotificationIcons.kt` next to `severityColor`, as `resultOutcomeColor(outcome)`.

### 6.2 Cursor

`buildNotificationRows` / `moveCursor` (`NotificationRows.kt`) treat a `Running` row as selectable when `task.stoppable && !task.stopping`. Headers and Empty rows stay unselectable. If the task under the cursor settles, the cursor clamps to the nearest selectable row, using today's clamp logic.

### 6.3 Routing on Confirm

`activateNotificationRow(index)` becomes:

```
Running(stoppable)    → open Stop confirm (§8)
History(depth=SIMPLE) → markRead; runNotificationAction(action)   // unchanged
History(depth=NOTES)  → markRead; open NotesSheet(notificationId)
History(depth=RESULTS)→ markRead; open ResultsSheet(notificationId)
```

Two existing fallbacks are removed by this:
- **`showNotificationText`** (the `InfoDialogState` fallback) is no longer used for history rows. A Simple row with action `None` and a non-blank `body` is upgraded by the decoder to a one-section `Notes`, so no row is left without a way to read it.
- **`LaunchOutcomeRecorder` rows** stop needing that fallback; they become real Notes rows (§10).

### 6.4 Hint pill

`NotificationPanelHint(selection)` takes a `PanelSelection` (`None`, `History`, `Running`):

| Selection | Pill |
|---|---|
| History | ✕ Open · △ Options · ○ Close |
| Running (stoppable) | ✕ **Stop** · △ Options · ○ Close |
| None | △ Options · ○ Close |

### 6.5 State

```kotlin
data class NotificationPanelState(
    val cursor: Int,
    val sheet: NotificationSheetState? = null,   // NEW
    val stopConfirm: StopConfirmState? = null,   // NEW
)

sealed interface NotificationSheetState {
    val notificationId: Long
    data class Notes(override val notificationId: Long, val scroll: Int = 0, val diagnosticOpen: Boolean = false) : NotificationSheetState
    data class Results(override val notificationId: Long, val filter: ResultFilter, val cursor: Int = 0) : NotificationSheetState
}
data class StopConfirmState(val taskId: String, val label: String, val doneLabel: String?, val focus: PfpModalFocus = CANCEL)
```

**Why the sheets live inside the panel state:** BACK from a sheet returns to the panel with the cursor where it was, which is how Sony layers it. `hasBlockingOverlay` already covers the panel, so it needs no change.

---

## 7. The sheets

These are new `PfpModalSpec` variants, so the shell's one modal host draws them and forwards input to them. Two new composables go in `core-ui/components/PfpDetailSheets.kt`, reusing `PfpModalScaffold`'s scrim, surface and hint bar. The scaffold gets a `width` parameter that defaults to 440dp.

**Art used by both sheets (approved):**
- **Severity stripe:** 4dp wide, full height, on the card's left edge, clipped by its 16dp radius, in `severityColor`. The left padding grows to 28dp to make room.
- **Code chip:** Roboto Mono 11sp/500, 0.4sp letter spacing, 85% white, padding 2×6dp, radius 4dp, fill white 8%, 1dp border at 14%.

### 7.1 Notes sheet (`PfpModalSpec.Notes`): artboard 4

- **Card:** 600dp wide, height capped at the screen height minus 24dp. The padding is 22 / 24 / 0 / 28dp.
- **Header:** a `KindRing` (28dp, severity colour) on the left. Beside it, the title (19sp Light, 92%) above a meta line: the code chip, then "Kind · Today, 2:22 AM" in 12sp at 55%. A 1dp rule at 30% sits below.
- **Body (scrolls):** sections with a 13sp/500 heading at 92% and 14/21sp body at 70%, 14dp apart. The facts box is a 2-column grid (120dp label column at 50%, value at 82%, 12sp) with padding 10×12dp, radius 8dp and fill white 4%. Last comes the "Diagnostic details" disclosure: a chevron and 12sp at 55%. Expanded, it shows a monospace block.
- **Scrollbar:** a 3dp track at 8% with the thumb at 40%. It only appears when the content overflows.
- **Hint bar:** ✕ *action label* (only when the action isn't `None`) · △ Copy Details · ○ Close. The action labels come from a `NotificationAction.label()` extension: "Go to Game", "Open Memory Card", "Open Settings", "Open Category".

**Input**

| Button | Effect |
|---|---|
| UP / DOWN | Scroll 96dp (the existing behaviour) |
| ✕ | Run the action: close the sheet, close the panel, navigate |
| △ | Copy the title, code, sections, facts and diagnostic as plain text to the clipboard, through the existing XMBVM:1941 copy path. Shows a 1.2s "Copied" pill above the hint bar. |
| RIGHT / LEFT | Open / close the diagnostic |
| ○ | Back to the panel |

### 7.2 Results sheet (`PfpModalSpec.Results`): artboards 5 and 6

- **Card:** 720 × 380dp with the same padding as Notes.
- **Header:** ring, title, and timestamp on the right (12sp at 55%).
- **Summary chips:** one per non-zero bucket. Each is 22dp tall, radius 11, padding 0×10dp, filled with the outcome colour at 14% and bordered at 45%. It holds a 7dp dot and "*n* *label*" in 12sp at 90%. DONE uses `#3FA27A`.
- **Filter tabs:** an L1 badge, then All · Failed · Skipped · *DoneLabel*, then an R1 badge. The badges are 10sp/700 with a 1dp border at 45% and radius 4. The selected tab is 13sp/500 at 95% with a 2dp underline at 90%; the others are 13sp at 55%. A 1dp rule at 18% sits below. **Tabs with zero items are skipped** when cycling.
- **Split:**
  - **Left:** a 390dp list. Rows have an 8dp outcome dot, the primary text in 13sp, and the badge on the right in 12sp at 60%. The focused row gets the 14% white highlight.
  - **Divider:** 1dp at 12%.
  - **Right pane** (18dp left padding): the name in 16sp/500; the outcome dot, label and code chip; the reason in 13/20sp at 70%; the path in a monospace box (padding 6×8dp, radius 6, fill 5%); then "What you can do" (12sp/500), defaulting to the code's `whatYouCanDo`.
  - **Truncation:** when `truncated > 0`, the last row reads "+*n* more", isn't selectable, and is at 55%.
- **Default filter:** **Failed** if anything failed, otherwise **All**.
- **Hint bar:** ✕ *item action label* (only when the focused item has an action) · △ Copy List · L1 R1 Filter · ○ Close.

**Input**

| Button | Effect |
|---|---|
| UP / DOWN | Move the list cursor, which clamps at both ends |
| L1 / R1 | `PREV_CATEGORY` / `NEXT_CATEGORY` cycle the filter; the cursor resets to 0 |
| ✕ | Run the focused item's action. With none, run the notification's action. With neither, do nothing. |
| △ | Copy the current filter as plain text, "outcome · primary · code · reason", one item per line |
| ○ | Back to the panel |

---

## 8. Stop confirm: artboards 2 and 3

- **Modal:** `PfpModalSpec.Confirm` exactly as it is today.
- **Title:** `Stop "<label without …>"?`
- **Message:**
  - with a count: "*current* of *total* *unit* are done. *keepLine*";
  - without one: just *keepLine*.
  - *keepLine* comes from the producer through `BackgroundTaskInfo.stopNote` (a new optional field), e.g. "Games found so far are kept, and the rest can be scanned later."
- **Buttons:** **Keep Running** (cancel, focused by default) and **Stop** (confirm).
- **On Stop:** `backgroundTasks.requestStop(taskId)`, then close the confirm and return to the panel. The row now shows *Stopping…* and isn't focusable.
- **If the task settles while the confirm is open:** close the confirm and move the cursor as in §6.2.

---

## 9. Shade removal

### 9.1 Background tasks
- Delete `BackgroundTaskNotifier.kt` and its uses in the center (§5.2).
- Delete the "Also Show in Android Shade" row from `NotificationSettingsScreen.kt` and its view-model wiring.
- Remove `mirrorToShade` from `NotificationSettings` and `NotificationPreferences`.

### 9.2 Windows Library setup
- Delete `app/.../pin/WindowsSetupNotifications.kt`.
- `PinShortcutActivity.kt:94` and `ShortcutConfirmReceiver.kt:65` call `backgroundTasks.report("windows_setup", "Finish setting up your Windows Library", severity = INFO, action = OpenSettingsScreen(<windows-setup route id>))` instead. The `showWindowsSetupPrompt` one-time dialog (XMBVM:925, :1983) stays as the first-time prompt.
- **To confirm while implementing:** the exact route id for Windows Library setup.

### 9.3 Shortcut requests (artboard 7)

- **Store:** the new `PendingShortcutRequestStore` (core-data, DataStore JSON list, at most 10, oldest dropped first). Each entry holds `id` (the `intentUri.hashCode()` hex), `name`, `intentUri`, `hostPackage`, `hostLabel` and `requestedAt`.
- **`InstallShortcutReceiver`:** after `ShortcutIntentSanitizer`, it enqueues the request and calls `report("shortcut:<id>", "<hostLabel> wants to add a shortcut: <name>", severity = INFO, kind = SYSTEM, action = NotificationAction.ReviewShortcut(id))`. It no longer posts a shade notification and no longer checks the permission.
- **New action:** `NotificationAction.ReviewShortcut(requestId)` with typeKey `review_shortcut`. Selecting the row opens the modal for that request. If the request is gone, it shows a Notice: "This request was already handled."
- **On resume:** XMBVM `onHostResumed` (or the existing resume hook) checks the queue and, with no other blocking overlay, shows the modal for the oldest request:
  - title "Add Shortcut?";
  - message `<hostLabel> wants to add a shortcut for "<name>" to PlayFieldPortal. Only add it if you just asked <hostLabel> to.`;
  - buttons **Ignore** (focused) / **Add**.
- **Add:** runs `ShortcutConfirmReceiver`'s import logic, moved into a shared `ShortcutRequestResolver` in feature-launcher so the receiver and the modal share it. Then the request is dequeued and the tray row re-reported as `"Added <name>"` (SUCCESS, same sourceKey, read).
- **Ignore:** dequeues the request and re-reports the row as `"Ignored <name>"` (read, INFO).
- **Security:** the gate is still a deliberate user choice inside PFP's own UI. Another app can write to neither the store nor the modal. `ShortcutConfirmReceiver` stays unexported until every caller has moved, then is deleted with its PendingIntent extras.

### 9.4 Permission
- Remove `POST_NOTIFICATIONS` from `app/src/main/AndroidManifest.xml:37`. Keep `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PLAYBACK`.
- Delete `requestNotificationPermissionIfNeeded()` and its launcher from `MainActivity.kt:246-258`. **Call `xmbViewModel.onStartupPermissionsSettled()` unconditionally** in the same place, or the boot sequence waits forever.
- `MusicPlaybackService` needs no change. MediaStyle notifications for a media session are exempt from the runtime permission.

---

## 10. Producers

Every producer moves onto the new API, grouped by area. Each entry gives the new title, the depth, what the detail holds, whether it can be stopped, and the codes it uses.

**Scans**
- **XMBVM `scanCard`** (:8146), title "*Card* scan finished". **Results:** DONE "N new" and the files marked missing. A failure becomes **Notes** using SC-9001, SC-1001 or SC-1002 from `ScanStatus`. Stoppable.
- **XMBVM `scanAllCards`** (:8213), title "Scan All Memory Cards finished". **Results:** one item per card, carrying `ScanStatus`, the code and an OpenMemoryCard action. Stoppable.
- **Windows scan** (XMBVM and LibraryManagerVM :909), title "Windows Games scan finished". **Results:** each `PcGameScanner` note becomes an item. Stoppable.
- **LibraryManagerVM `scanVitaGames`** (:642), title "PS Vita scan finished". **Results.** Stoppable.
- **LibraryManagerVM `scanConsole`** (:702), title "*Platform* scan finished". **Results.** Stoppable. **Fixes a bug:** FAILED and SKIPPED currently report SUCCESS.
- **LibraryManagerVM `scanRomRoot`** (:1168), title "ROM Root scan finished". **Results:** per-system results, with skipped folders tagged SC-2003. Stoppable.
- **`WizardMediaScanRunner`** music, photos and video, title "*Media* scan finished". Stays **Simple** when it succeeds; failures become **Notes** with MD-9001 or MD-2001. Stoppable.

**Artwork and metadata**
- **XMBVM `scrapeMissingArtwork`** (:8252), title "Artwork fetch finished". **Results:** one item per game (Updated, Failed with AR-3001, AR-3002 or AR-3003). A crash becomes **Notes** with AR-9001 and keeps the exception message. Stoppable.
- **XMBVM `updateMetadata`** (:8288), title "Metadata update finished". Same as artwork fetch. Stoppable.
- **`GameArtworkFetchRunner`**, title "Artwork updated for *Game*". Stays **Simple**; failures become **Notes** with AR-3001, AR-3003 or AR-9001. Not stoppable (only one game).
- **`MetadataScrapeWorker`**, title "Metadata scrape finished". **Results.** Stoppable (worker).
- **`ArtworkRelinkWorker`**, title "Artwork relink finished". **Results:** unmatched files as SKIPPED. Stoppable (worker).
- **`ArtworkExportWorker`**, title "Artwork export finished". **Results:** failed copies tagged AR-2002. Stoppable (worker).
- **`InternalArtworkMigrationWorker`**, title "Artwork move finished". **Results:** AR-2002. **Not stoppable.**
- **`ArtworkImportExecutor`**, title "Artwork import finished". **Results:** `errors` and `unknownSystemFolders` (AR-2003). Stoppable (worker). **Moves from a direct `post` to `report`**, so the row now plays the chime.
- **`StorefrontMetadataSync`**, title "Storefront sync finished". **Results.** Stoppable.

**Achievements**
- **`AchievementUpdateReporter.finished`**, title "Achievements updated". **Results:** failed games tagged AC-3004. Stoppable.
- **Achievement pauses**, title "Achievement update paused". **Notes:** AC-3001, AC-3002 or AC-3003, with the provider as a fact.
- **Auto-match**, title "Auto-match finished". **Results:** unmatched games tagged AC-1001. Stoppable.
- **Batch match** (XMBVM :1714, LibraryManagerVM :970), title "Windows achievements match finished". **Results.** Stoppable.
- **Goldberg convert** (XMBVM :1740, LibraryManagerVM :996), title "Goldberg convert finished". **Results.** **Not stoppable.**

**Backup**
- **`BackupManager` backup**, title "Backup created" / "Backup failed". **Simple** when it succeeds; failures become **Notes** with BK-2001.
- **`BackupManager` restore**, title "Restore finished". **Results:** `refusals` tagged BK-1001. **Not stoppable.**

**Launch and shortcuts**
- **`LaunchOutcomeRecorder`**, title "Couldn't launch *Game*". **Notes:** LN-4001, LN-4002, LN-4003 or LN-9001. The facts are emulator, platform and the recent-failure line; `diagnostic` is `buildDiagnostic()`. **Moves from a direct `post` to `report`.**
- **XMBVM shortcut and favourite errors** (:9944-10010). **Notes** with SY-9001 and the exception message as the diagnostic.

**Simple rows that stay Simple** apart from losing the " — message" join: settings and system `announce()` rows (InitialSetup, AchievementsSettings, Themes, EmulatorAssignment, Logs) and the copy-diagnostic report.

**Launch-code mapping:** `LaunchDispatcher` (:122/:125/:128 and the watchdog) passes a `PfpErrorCode` into `outcomeFor(...)`, so the recorder doesn't parse message strings. `LaunchOutcome` gains `errorCode: String?`. It isn't persisted to `launch_outcomes`; the in-memory value is all the recorder needs.

---

## 11. Tests (written first)

All tests are JVM unit tests, using the modules' existing styles (JUnit4 + Truth / MockK + Turbine).

**`core-domain`**
- **`NotificationDetailCodecTest`:**
  - Notes and Results each survive encode then decode unchanged;
  - `decode(null)`, `decode("")` and `decode("not json")` all return null;
  - unknown keys are ignored;
  - 600 items are cut to 500 with `truncated = 100` and failures first;
  - a 200 KB diagnostic is dropped to meet the 64 KB cap, and items are trimmed after that.
- **`PfpErrorCodeTest`:** the registry matches the doc; ids are unique and well-formed; `fromId` falls back to `SY_9001`.
- **`PfpNotificationDepthTest`:** `depth` matches the payload. A Simple row with action `None` and a body decodes to a one-section Notes.
- **`DetailActionTest`:** every `NotificationAction` subtype converts to `DetailAction` and back unchanged.

**`core-ui`**
- **`BackgroundTaskCenterTest`** (extends the existing file):
  - `settle` writes separate title and body, never `"title — message"`;
  - `complete(detail = …)` writes the encoded payload;
  - `requestStop` on a stoppable task sets `stopping`, calls the handle once, and returns true; on a non-stoppable or unknown task it returns false; a second call is a no-op;
  - progress ticks while stopping are ignored;
  - `stopped()` posts a WARNING row with `read = true` and plays **no** sound;
  - `cancel()` and settle both remove the handle;
  - no shade interaction remains (the constructor no longer takes a notifier).
- **`PfpModalNavTest`** (extends): Notes scroll, ✕, △ and ○; Results UP/DOWN clamp, L1/R1 cycling that skips empty tabs, ✕ falling back from the item action to the notification action to nothing, and ○.

**`core-data`**
- **`NotificationRepositoryImplTest`:** `post(read = true)` sets `readAt`, including on the replace-by-sourceKey path.
- **`PendingShortcutRequestStoreTest`:** enqueue and dequeue in order, the cap of 10 drops the oldest, and the same id replaces the entry.

**`feature-xmb`**
- **`NotificationRowsTest`** (extends): a stoppable running row is selectable; one that is stopping or not stoppable isn't; the cursor clamps when the row under it settles.
- **`XMBViewModelNotificationTest`** (new, or the existing VM test suite):
  - Confirm on a Notes or Results row marks it read and opens the matching sheet;
  - Confirm on a Simple row runs its action as before;
  - BACK from a sheet returns to the panel with the cursor where it was;
  - Confirm on a stoppable running row opens the Stop confirm with Keep Running focused;
  - Stop calls `requestStop`;
  - the shortcut queue shows the modal on resume when nothing else is open; Ignore dequeues and re-reports the row as read.
- **`ShellModalSpecTest`** (extends): the Notes and Results specs map from `NotificationSheetState`.

**`feature-launcher`**
- **`LaunchOutcomeRecorderTest`:** a failure goes through `report` with a Notes detail and the right code; success posts nothing.
- **`LaunchDispatcherTest`** (extends): each failure path carries its `PfpErrorCode`.

**`feature-settings`**
- **`LibraryManagerViewModelTest`:**
  - `scanConsole` reports FAILED and SKIPPED as failures, not SUCCESS;
  - scans run on `@TaskScope` and keep going after the ViewModel is cleared;
  - stopping a scan records `stopped()` with the partial Results.

**`app`**
- **`MainActivity`:** covered manually (§12, phase 6). There's no unit-test seam.

---

## 12. Phases

Each phase builds and passes its tests before the next one starts. Per the usual workflow, you run the Gradle commands.

**Phase 1: Model, codes, codec.** §4 and the codes doc.

```bash
./gradlew :core:core-domain:testDebugUnitTest
```

**Phase 2: Repository, center and task scope.** §5: `post(read)`, the new API, stop handles, the title/body split, the shade notifier deleted, `@TaskScope`, and the `scanConsole` fix.

```bash
./gradlew :core:core-data:testDebugUnitTest
```

```bash
./gradlew :core:core-ui:testDebugUnitTest
```

```bash
./gradlew :feature:feature-settings:testDebugUnitTest
```

**Phase 3: Panel rows, cursor, routing and Stop confirm.** §6 and §8.

```bash
./gradlew :feature:feature-xmb:testDebugUnitTest
```

**Phase 4: Notes and Results sheets.** §7 and the `PfpModalSpec` variants.

```bash
./gradlew :core:core-ui:testDebugUnitTest
```

```bash
./gradlew :feature:feature-xmb:testDebugUnitTest
```

**Phase 5: Producers.** §10, in four batches, testing each module as it changes: (a) scans, (b) launch, (c) artwork, (d) achievements and backup.

```bash
./gradlew :feature:feature-launcher:testDebugUnitTest
```

```bash
./gradlew :feature:feature-artwork:testDebugUnitTest
```

```bash
./gradlew :feature:feature-achievements:testDebugUnitTest
```

```bash
./gradlew :feature:feature-library:testDebugUnitTest
```

**Phase 6: Shade removal and shortcut modal.** §9.

```bash
./gradlew :app:testFullDebugUnitTest
```

```bash
./gradlew installFullDebug
```

**Manual check on the device (Thor/Odin3):**
1. Fresh install: no notification-permission prompt, and boot finishes.
2. Scan All with one card pointing at a missing folder: a Results row with a chip; the sheet opens on Failed and shows `SC-2002`.
3. Start a ROM Root scan, open the panel, focus the running row, then ✕ → Stop: the row shows *Stopping…*, then a read WARNING row appears with no chime.
4. Uninstall the emulator assigned to a game, then launch it: a Notes row with `LN-4001`; △ copies the details.
5. Request a shortcut from Chrome, return Home: the modal appears with Ignore focused.
6. Music playback still shows its media notification. Nothing else from PFP appears in the shade.

**Phase 7: Docs.** Mark this plan implemented and add a CHANGELOG entry.

---

## 13. Risks

| Risk | Mitigation |
|---|---|
| Large payloads make the 200-row history heavy | The 500-item and 64 KB caps are applied in the codec, and the codec test covers them |
| A producer forgets `ensureActive()`, so Stop seems to hang | The *Stopping…* state is visible; each §10 loop gets the check; the VM tests cover the scans |
| Moving scans to `@TaskScope` leaks work after the user leaves | That's the intent (the work belongs to the tray now), and Stop is the way to end it |
| Rewording titles changes rows users are used to | §10 lists every new title; old rows keep their stored titles |
| Removing `POST_NOTIFICATIONS` stalls the boot sequence | `onStartupPermissionsSettled()` is called unconditionally, and manual check 1 covers it |
| Shortcut requests are lost when PFP isn't resumed for a long time | The queue persists (DataStore) and the tray row stays; the cap of 10 drops the oldest |
| `DetailAction` and `NotificationAction` encodings drift apart | They share one mapping function, and `DetailActionTest` round-trips every subtype |
