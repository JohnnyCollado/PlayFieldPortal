# Play Field Portal — Notification Panel: Implementation Plan

A Vita-style panel that drops from the status strip, holding **what is running now** (with progress)
and **what already happened** (clearable history): file scans, artwork downloads, scraping,
achievement matching and syncing, launch errors — and the seam a later RSS / direct-download
channel posts into.

**Status: implemented.** Phases 0–4 and 6 are in the tree. Phase 5 (the nine hand-drawn glyphs and
the themeable `status_notifications` slot) is held at the art gate in §5 — the panel ships with
Material and existing-drawable stand-ins behind one `notificationGlyph` table, so swapping in the
real art is a single-file change. `LibraryScanner` progress (§4.3) remains the later change it was
always planned to be; file scans show an honest indeterminate bar today.

> **Shape.** Not a crossbar category. The panel is an overlay anchored under
> `XmbPspStatusStrip` — the PS Vita notification list, which slides down over the home screen and
> leaves the status bar visible above it. It opens two ways and no others: **START** on a
> controller, or a **button leading the status strip's right group**. No gestures, no edge bands,
> no long-press.

---

## 1. Prior art: this was built once and removed

`BackgroundTaskTray.kt` was deleted in commit `f6ad773e` — *"Removed the in-app task tray;
background work now posts to the Android notification bar."* Its ghost is still in the code:
`GamepadAction`'s legacy-name table maps the retired `OPEN_TASK_TRAY` to `CHANGE_SORT`
([GamepadAction.kt:53](core/core-domain/src/main/kotlin/com/playfieldportal/core/domain/model/GamepadAction.kt#L53)),
commented *"outlived the task tray itself, which was removed."*

That tray also showed progress, so the difference has to be stated precisely rather than waved at:

| The removed tray | This panel |
|---|---|
| Progress **and nothing else** — when the scan ended, the row vanished and left no trace | Progress **and** the durable record of what that work did |
| Nothing persisted; state was a transient `List<BackgroundTaskInfo>` in the ViewModel | Running work stays transient (correctly); outcomes become Room rows the user clears |
| `Color.Black.copy(alpha = 0.88f)` hardcoded — off-theme against every color scheme | Draws from `LocalPFPColors`, like the `PspContextMenuOverlay` that replaced it |
| Rows were inert — nothing to select, nowhere to go | Rows mark read and carry an action (open that Memory Card, that game, that settings screen) |
| Full-bleed black bar, no way in but a button that was later repurposed | Inset panel, Vita proportions, on START and a permanent strip button |
| Duplicated the shade exactly | Adds what the shade cannot do on a HOME-screen device: history, actions, and a clear |

**The lesson, stated accurately:** the tray did not die because progress is worthless — it died
because progress *alone* is worthless once the shade already shows it. Progress earns its place
here by sitting on top of a history that outlives it.

---

## 2. What exists today

### 2.1 The status strip

[`XmbPspStatusStrip`](feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/XmbStatusStrip.kt)
is a single `Row` aligned `TopCenter` inside the shell's root `Box`
([XMBShell.kt:703](feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/XMBShell.kt#L703)).
Its own layout comment reserves a slot that was never implemented:

```
Layout:  DATE  ┊  TIME  [bg-task badge]          [BT] [WiFi] [Signal] [Bat] %
```

**This plan does not use that slot.** The button leads the *right* group instead (§4.6); the
comment should be corrected rather than left describing a slot nothing fills.

The two groups are not interchangeable, and the right one is the stricter contract:

| | Left group | Right group |
|---|---|---|
| Contents | date ┊ time ┊ sort chip | controller · BT · Wi-Fi · signal · battery · % |
| Arrangement | `spacedBy(6.dp)`, text-led | `spacedBy(7.dp)`, icon-led |
| Visibility | always | each icon conditional on that hardware being present/active |
| Theming | none — the sort chip is not a slot | drawable-backed icons **are** themeable slots (`status_bluetooth`, `status_battery_*` in `IconSlots.ALL`), via `StatusIcon(slotKey = …)` |

Battery already pairs an icon with adjacent text (`[Bat] 95%`), so icon-plus-count is the group's
existing idiom rather than a new one.

### 2.2 How overlays work

Everything layered over the XMB is a nullable field on `XMBUiState` OR'd into
`hasBlockingOverlay` ([XMBViewModel.kt:939](feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/viewmodel/XMBViewModel.kt#L939)),
which the gamepad dispatcher checks as a final guard:

```kotlin
// Defensive net: the main XMB navigation below must NEVER run while any overlay,
// menu, or modal dialog is on screen. Each case above returns for its own handling;
// this guards against a future overlay being added without its own branch.
if (state.hasBlockingOverlay) return
```

That comment is written for exactly this case. The panel gets its own branch above the net *and*
its field in `hasBlockingOverlay`; omitting the second half means the D-pad drives the crossbar
behind an open panel.

### 2.3 START is already bound, and already does nothing here

`KEYCODE_BUTTON_START` maps to `GamepadAction.HOME`
([GamepadBinding.kt:60](core/core-domain/src/main/kotlin/com/playfieldportal/core/domain/model/GamepadBinding.kt#L60)),
and on the main XMB that action is dead
([XMBViewModel.kt:5538](feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/viewmodel/XMBViewModel.kt#L5538)):

```kotlin
// Start button no longer restarts / shows the boot screen.
GamepadAction.HOME          -> Unit
```

Opening the panel is **one line replacing that `Unit`**. No new `GamepadAction`, no new binding,
no remapping-screen change, no migration of saved layouts.

- **`HOME` is not dead everywhere.** It is Confirm/Apply in the app picker, game picker, music
  track picker and Artwork Studio — its settings label is literally `"Start (Confirm in pickers)"`
  ([GamepadBinding.kt:124](core/core-domain/src/main/kotlin/com/playfieldportal/core/domain/model/GamepadBinding.kt#L124)).
  Those branches return before the main-XMB `when` is reached, so they are untouched; the label
  becomes `"Start (Notifications · Confirm in pickers)"`.
- **Do not reuse the name `OPEN_TASK_TRAY`.** It is live in `LEGACY_ACTION_NAMES` pointing at
  `CHANGE_SORT`; reintroducing it as a real constant would rebind sort on every saved layout.

### 2.4 The background-task plumbing — and the counts it discards

Every background task funnels through four private functions in `XMBViewModel`
([:7036](feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/viewmodel/XMBViewModel.kt#L7036))
into `BackgroundTaskNotifier` → the Android shade, and stops there. On a HOME-screen device the
user may never pull the shade down, and on API 33+ without `POST_NOTIFICATIONS` `post()` silently
no-ops.

**The progress data already exists, at full fidelity, and is thrown away.** `BackgroundTaskInfo`
carries `progress: Float?`, so the call sites compute a fraction and discard the operands:

```kotlin
// XMBViewModel.kt:6965 and :6987
onProgress = { p -> updateBackgroundTask(taskId, p.current.toFloat() / p.total.coerceAtLeast(1)) }
```

What each producer actually reports today:

| Task kind | Source | Counts available? |
|---|---|---|
| **Artwork downloads** | `ArtworkRepository.ScrapeProgress(current, total, ok, fail, title)` | **Yes — richest.** Also per-item ok/fail tallies and the title being fetched |
| **Scraping (metadata)** | `MetadataRepository.fetchMissingMetadata(onProgress: (current, total))` | **Yes** |
| **Achievement auto-matching** | `AchievementAutoMatcher.onProgress(index, unlinked.size)` | **Yes — but not wired to the notifier at all today** |
| **Achievement syncing** | `AchievementRepository.onProgress(…)`, three phases summed into one total | **Yes — but not wired today** |
| **File scanning** | `LibraryScanner` | **No.** [LibraryScanner.kt:80](feature/feature-library/src/main/kotlin/com/playfieldportal/feature/library/scanner/LibraryScanner.kt#L80): *"completion only, never per platform or on progress"* |
| **File downloads** | — | Future (RSS), §8 |

So four of the five asked-for bars are free once the counts stop being discarded; two of those four
need their existing callbacks routed to the notifier; and file scanning is the one real gap (§4.3).

### 2.5 Constraints

| Constraint | From | Consequence |
|---|---|---|
| Features never depend on features | ARCHITECTURE.md | The post() seam lives in `core-domain` / `core-data` |
| No background polling, no watcher | ADR-0002 | Every row traces to an event that already fires; retention runs on the write path |
| Never destructive migration | ARCHITECTURE.md | New table only, `MIGRATION_44_45` (DB is at v44) |
| `LibraryScanner` owns ROM-survey policy | ADR-0001 | Progress instrumentation is a change *inside* the scanner, never a second walker beside it |
| Tested by default | Design principles | Pure builders + repo policies get JVM tests (§9) |
| `XMBViewModel` is 9,648 lines | measured | New logic in its own files |

---

## 3. Scope

**In scope** — the panel with both sections; its status-strip button and START shortcut; a durable
store with dedupe and a retention cap; one `NotificationRepository` seam; widening
`BackgroundTaskInfo` so progress counts survive; routing the existing producers in; read / act /
clear; the icon set; and the payload + action columns RSS will need.

**Out of scope** — the RSS channel itself, feed parsing, any download engine (§8 defines only the
seams); replacing the shade (it keeps working as a mirror); per-notification Android channels or
heads-up alerts; a crossbar category; changing *what* a scan does.

---

## 4. The panel

### 4.1 Two sections

```
┌─ status strip stays visible ────────────────────────────────────────────┐
│ 07/08/2026 ┊ 3:36 AM ┊ Sort: Title      [🔔3][🎮][BT][WiFi][Bat] 95%   │
│                                          ▲ the button, or START         │
├─────────────────────────────────────────────────────────────────────────┤
│   ╭───────────────────────────────────────────────────────────────╮     │
│   │  RUNNING                                                      │     │
│   │  (◔)  Artwork — Dreamcast                          14 / 56    │     │
│   │       ▓▓▓▓▓▓▓▓░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░               │     │
│ · │  (◕)  Scanning PlayStation                       1,204 files  │     │
│ ● │       ░░░▓▓▓▓▓▓░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░  (indet.)     │     │
│ · │  ───────────────────────────────────────────────────────────   │     │
│ · │  EARLIER                                                      │     │
│   │  (◕)  Scan failed: PlayStation                   2 Hours Ago  │     │
│   │  (◔)  Artwork scraped — 14 new files             6 Hours Ago  │     │
│   │  (◑)  Shiba Coins: 3 new                       9/21 4:20 PM   │     │
│   ╰──────────────────────────────────────────────────────╴ (…) ╴─╯     │
└─────────────────────────────────────────────────────────────────────────┘
  ▲ scroll dots                                              ▲ Options
```

**RUNNING** is live and transient. **EARLIER** is the durable, clearable history. The split is not
cosmetic — it is the difference between in-memory state and a Room row (§4.2).

Rows carry a circular kind icon, one ellipsized line, and either a progress bar (running) or a
timestamp (history) — relative while recent, absolute once older, the Vita's own rule.

### 4.2 Why running work is NOT a database row

A running task is in-flight process state, not a fact about the library. If it were persisted:

- a crash or force-stop mid-scan would leave a phantom "Scanning…" row at 40% forever, with
  nothing left alive to finish or fail it;
- `clearAll()` would have to decide whether clearing history also cancels work, which is two
  unrelated ideas sharing one button;
- every progress tick would be a database write — a scrape of 800 games is 800 writes for data
  that is worthless one second later.

So `RUNNING` reads from the in-memory task map that already exists, and a task writes to Room
exactly once, when it settles. **Clear All empties the history and leaves running work alone**;
those tasks post their own fresh rows when they finish. That answers the retention question
directly: the user can always empty the tray, and doing so can never break a scan.

### 4.3 File scanning has no progress to show yet

`LibraryScanner` reports completion only. Two honest options, and the plan takes the first:

1. **Indeterminate bar now** (phase 3). The row reads `Scanning PlayStation` with a moving
   indeterminate bar and, where cheap, a live "N files seen" counter. Correct, and costs nothing.
2. **Instrument the scanner** (later, its own change). A determinate bar needs a total, and a tree
   walk does not know its total until it has walked. That means either a counting pre-pass — which
   doubles IO on the exact operation the media-scan performance work just optimised — or a
   coarse-grained `n of m Memory Cards`, which is achievable and probably the right answer.

`n of m Memory Cards` is the recommendation when this is revisited: the card list is known up
front, it needs no pre-pass, and it changes `LibraryScanner` at the loop it already has.

### 4.4 State

```kotlin
// XMBUiState
val notificationPanel: NotificationPanelState? = null,   // null = closed
val runningTasks: List<BackgroundTaskInfo> = emptyList(),
val unreadNotifications: Int = 0,
```

`notificationPanel != null` joins `hasBlockingOverlay`. `BackgroundTaskInfo` is widened so the
counts stop being discarded — `fraction` keeps every existing notifier call working unchanged:

```kotlin
data class BackgroundTaskInfo(
    val id: String,
    val kind: TaskKind,              // SCAN · ARTWORK · METADATA · ACHIEVEMENT · DOWNLOAD
    val label: String,
    val current: Int? = null,        // was discarded at the call site
    val total: Int? = null,          // ditto
    val detail: String? = null,      // ScrapeProgress.title — the item being fetched
) {
    val fraction: Float?
        get() = if (current != null && total != null && total > 0) current.toFloat() / total else null
}
```

### 4.5 Interactions

| Input | Behaviour |
|---|---|
| **▲ / ▼** | Move the cursor; clamps at both ends. **Running rows are skipped** — they are a readout, not a list you act on |
| **✕** on a history row | Mark read, then run its action. `None` → the existing `InfoDialogState` |
| **△** on a history row | `Mark as Read` / `Mark as Unread` · `Open` (when actionable) · `Delete` *(destructive)* |
| **△** / the `…` button | `Mark All Read` · `Clear Read` · `Clear All` *(destructive)* |
| **START** again, **○**, tap the scrim | Close |

Options reuses `XMBContextMenu` / `XMBContextMenuItem` and the shared `PspContextMenuOverlay` —
the same right-edge panel as the Memory Card menu, nothing new built.

### 4.6 The button

**Leads the right group**, with the controller icon and the rest following it.

```kotlin
Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
    NotificationButton(unread, running, onTap = ::toggleNotificationPanel)   // ← first
    if (sys.controllerConnected) { /* SportsEsports */ }
    if (sys.bluetoothOn)         { /* status_bluetooth */ }
    sys.wifiLevel?.let           { /* WifiMeter */ }
    sys.cellularLevel?.let       { /* SignalBars */ }
    /* battery + % */
}
```

Leading the group is what makes it hold still. Every icon behind it is conditional — a controller
connects, Bluetooth goes off, cellular drops — so any later position would slide around as
hardware comes and goes. First means fixed, which is what a button needs and a status readout does
not.

Three states:

| State | Appearance |
|---|---|
| Nothing unread, nothing running | Bell at `StripMuted`, **dimmed but present** — never hidden |
| Unread | Bell tinted, unread count beside it |
| Work running | Bell tinted with a small activity mark; the count still wins when both apply |

- **Always tappable**, not gated on `resolvedShowTouchButton` — that flag exists so the *sort*
  chip doesn't look like a fake button under a controller, but hiding this one removes the only
  thing advertising the feature.
- **Themeable**: `status_notifications` added to `IconSlots.ALL` *and*
  `XmbStatusIcons.forSlotKey()`, rendered via `StatusIcon(slotKey = …)`.
  `DefaultSlotGlyphTest` guards that pairing and fails if only one side is added.
- It is the only interactive element in a group of passive indicators. Worth checking on device
  that the tint reads as "this is a button" and not "notifications hardware is on".

---

## 5. The icon set

Nine glyphs. One per kind, plus the strip button. **Severity is carried by the ring colour and
tint, not by a different glyph** — so a scan that succeeded and a scan that failed share
`notif_scan` and differ only in colour. That keeps the set at nine instead of thirty-six.

Style: single-weight line art inside a 64px circular ring, ~30px glyph, matching the Vita
reference and the existing `status_*` line work.

| Slot key | Kind | Proposed glyph | Echoes |
|---|---|---|---|
| `notif_scan` | File scanning | Disc outline with a sweep arc across it | `media_disc.xml` |
| `notif_artwork` | Artwork downloads | Picture frame with a mountain line, down-arrow entering at the corner | `catbar_photos.png` |
| `notif_metadata` | Scraping | Luggage-tag outline with two text lines and a pin hole | new — deliberately *not* the picture frame, so text-scraping reads as different work from art-fetching |
| `notif_achievement` | Matching / syncing | Concentric coin with the Shiba paw mark | `shiba_coin_gold.webp` — already the app's coin language |
| `notif_download` | File download *(future)* | Tray with a down-arrow landing in it | new |
| `notif_launch` | Launch failure | Play triangle crossed by a slash | new |
| `notif_system` | Theme / backup / setup | Gear | `catbar_settings.png` |
| `notif_feed` | RSS item *(future)* | Three RSS arcs from a corner dot | new |
| `status_notifications` | The strip button | Bell, flat-bottomed, with a clapper arc | new — sized to the right group (~13–15dp), not 64px |

Severity ring colours (the four `NotificationSeverity` values):

| Severity | Ring / tint | Used by |
|---|---|---|
| `INFO` | slate `#44586D` | scan complete, metadata updated |
| `SUCCESS` | green `#2E7D5B` | artwork scraped, sync finished |
| `WARNING` | amber `#9D6B1C` | partial results, import rejections |
| `ERROR` | red `#C0453A` | scan failed, launch failed |

**Five of the nine can ship as tinted Material or existing-drawable stand-ins on day one**
(`notif_system` = gear, `notif_achievement` = the existing coin, `notif_launch` = play + slash),
and the kind → drawable mapping is one table, so swapping in real art later is a single-file
change with no call-site churn.

> **Art gate.** All nine are new on-screen art. Confirm the look, or supply the art, before any of
> it is drawn.

---

## 6. Data layer

### 6.1 Domain (`core:core-domain`)

```kotlin
data class PfpNotification(
    val id: Long,
    val kind: NotificationKind,
    val severity: NotificationSeverity,
    val title: String,
    val body: String?,
    val sourceKey: String?,      // dedupe key, e.g. "scan:memcard_psx"
    val action: NotificationAction?,
    val payload: String?,        // opaque JSON — the RSS seam (§8)
    val createdAt: Long,
    val readAt: Long?,
)

enum class NotificationKind { SCAN, ARTWORK, METADATA, ACHIEVEMENT, LAUNCH, SYSTEM, DOWNLOAD, FEED }
enum class NotificationSeverity { INFO, SUCCESS, WARNING, ERROR }

sealed interface NotificationAction {
    data object None : NotificationAction
    data class OpenCategory(val categoryId: String) : NotificationAction
    data class OpenMemoryCard(val platformId: String) : NotificationAction
    data class OpenGame(val gameId: Long) : NotificationAction
    data class OpenSettingsScreen(val routeId: String) : NotificationAction
    data class OpenUrl(val url: String) : NotificationAction        // §8
}
```

`NotificationKind` and `TaskKind` deliberately overlap — a running `ARTWORK` task becomes an
`ARTWORK` history row, so one icon table serves both sections.

`NotificationRepository`: `observeAll()`, `observeUnreadCount()`, `post()`, `markRead()`,
`markAllRead()`, `delete()`, `clearAll()`, `clearRead()`.

### 6.2 Room (`core:core-data`) — DB v44 → v45

`NotificationEntity` (table `notifications`), `NotificationDao`, registered in `PFPDatabase`, with
a `MIGRATION_44_45` that only `CREATE TABLE`s — trivially non-destructive.

```
notifications
  id           INTEGER PK AUTOINCREMENT
  kind         TEXT NOT NULL          severity   TEXT NOT NULL
  title        TEXT NOT NULL          body       TEXT
  source_key   TEXT                   action_type TEXT    action_arg TEXT
  payload      TEXT                   created_at INTEGER NOT NULL    read_at INTEGER

  INDEX(created_at)  INDEX(read_at)  INDEX(kind)  UNIQUE INDEX(source_key)
```

- **Dedupe** — a post with a non-null `sourceKey` replaces that row and resets
  `created_at`/`read_at`. A Memory Card that fails four times is one unread row.
- **Retention** — after each insert, drop the oldest beyond `MAX_ROWS = 200`, read rows first;
  `notifications_auto_clear_days` defaults to 30. Both are provisional, and the user-facing
  guarantee does not depend on them: **Clear All is always available** and always empties the
  history.

### 6.3 Preferences (`PFPDataStore`)

`notifications_enabled` (true) · `notifications_mirror_to_shade` (true) ·
`notifications_auto_clear_days` (30, 0 = never).

---

## 7. Producers

**Change no caller's logic — widen the type and add a second sink.**

| Function | Shade today | After |
|---|---|---|
| `addBackgroundTask` | `running()` ongoing | **RUNNING row appears** |
| `updateBackgroundTask` | `running()` progress | **RUNNING row's bar + `n / m` update** |
| `completeBackgroundTask` | `complete()` | RUNNING row leaves; `SUCCESS` history row posted |
| `failBackgroundTask` | `failed()` | RUNNING row leaves; `ERROR` history row posted |

Because all four already funnel through one place, this covers ROM scans, PC scans, artwork
scrapes and metadata updates with no edits at the call sites — only the two `updateBackgroundTask`
lines change, to pass `p.current`/`p.total` instead of dividing them.

Then, each at an event that already fires:

1. **Achievement auto-matching and syncing** — both have `onProgress` callbacks today and neither
   reaches the notifier. Wire them to `addBackgroundTask` / `updateBackgroundTask` like the artwork
   path. This is the one producer that needs genuinely new wiring rather than a widened type.
2. **Launch failures** — `LaunchOutcomeRecorder` already persists `INTENT_FAILED` /
   `NEVER_FOREGROUNDED`; post `LAUNCH`/`ERROR` with `OpenGame(gameId)`.
3. **Artwork import reports** — `ArtworkImportReportEntity` already summarises rejects; post a
   `WARNING` with `OpenSettingsScreen("settings_artwork")`.
4. **System** — theme/backup restore results, setup completion.

---

## 8. RSS / direct-download preparation

A later, separate plan. What this one leaves behind, each costing nothing while unused:

- **`payload: String?`** — opaque JSON on the row; a feed item stores its enclosure URL, size and
  mime there.
- **`NotificationKind.FEED` and `DOWNLOAD`, `TaskKind.DOWNLOAD`** — so a feed item is a
  first-class row and an in-flight download is a first-class RUNNING row with a real byte-progress
  bar, reusing the same two sections rather than needing a new surface.
- **`NotificationAction.OpenUrl`** — the only action that leaves the app; it can stay
  unimplemented, degrading to the info dialog, until the feed lands.
- **The feed's home is the `network` category, not this panel.** `network` is seeded at position 5
  and currently renders as a plain app category. An RSS channel belongs there, *posting into* the
  panel — the same producer shape as everything else.

A download is the one future producer whose progress is genuinely determinate from the first byte
(`Content-Length`), so it will be the best-looking bar in the list.

---

## 9. Tests (written first)

| Test | Asserts |
|---|---|
| `NotificationRepositoryTest` | Dedupe by `sourceKey` replaces and resets read state; the cap evicts read rows before unread; `clearRead` spares unread; `observeUnreadCount` tracks `markRead`/`markAllRead` |
| `BackgroundTaskInfoTest` | `fraction` from `current`/`total`; null when either is absent; `total = 0` does not divide by zero; an indeterminate task keeps a null fraction |
| `NotificationRowsTest` | RUNNING sorts above EARLIER; running rows are skipped by the cursor; history newest-first; unread marker; the relative→absolute timestamp switch; empty state |
| `ClearDoesNotCancelTest` | `clearAll()` empties history and leaves `runningTasks` untouched; a task settling afterwards still posts its row |
| `NotificationPanelGateTest` | START opens and closes on the XMB; START inside a picker still confirms; START does nothing behind another overlay |
| `NotificationActionTest` | Each action maps to the expected navigation; `None` opens the info dialog; unknown `action_type` degrades to `None` rather than throwing |
| `MigrationTest` (44→45) | Table and indices created; existing rows untouched; round-trip |
| `BlockingOverlayTest` | `notificationPanel != null` implies `hasBlockingOverlay` |
| `DefaultSlotGlyphTest` (existing) | Extended for `status_notifications` — fails if `IconSlots.ALL` and `XmbStatusIcons.forSlotKey()` disagree |

---

## 10. Phases

| Phase | Deliverable | Files |
|---|---|---|
| **0 — Tests & contracts** | Failing tests above; domain model + repository interface only | `core-domain/model`, `core-domain/repository`, 9 test files |
| **1 — Data layer** | Entity, DAO, `MIGRATION_44_45`, repo impl, Hilt binding, DataStore keys | `core-data/database`, `PFPDatabase.kt`, `DatabaseModule.kt`, `PFPDataStore.kt` |
| **2 — Progress fidelity** | Widen `BackgroundTaskInfo` with `kind`/`current`/`total`/`detail`; stop discarding counts at the two call sites; expose `runningTasks` on `XMBUiState`; wire achievement matching + syncing | `XMBViewModel.kt`, `AchievementController` |
| **3 — The panel** | Composable with both sections, progress bars, pure row builder, `hasBlockingOverlay`, dispatcher branch, both context menus | `NotificationPanel.kt`, `NotificationRows.kt`, `XMBViewModel.kt`, `XMBShell.kt` |
| **4 — Button & START** | The `HOME` branch; the button leading the right group; `status_notifications` on both sides; corrected layout comment; action label; settings toggles | `XMBViewModel.kt`, `XmbStatusStrip.kt`, `IconSlots.kt`, `GamepadBinding.kt`, settings |
| **5 — Icon art** | The nine glyphs, kind → drawable table, themeable slots | `core-ui` drawables, `IconSlots.kt` |
| **6 — RSS seams** | `payload` plumbed, `OpenUrl` dispatched, `FEED`/`DOWNLOAD` rendered. **No feed code** | `NotificationRows.kt`, the action dispatcher |

Phases 0–1 are pure data work and land alone. Phase 2 is independently valuable — it fixes the
discarded counts even before any panel exists, improving the shade notifications too. 3 and 4 are
one visible increment.

---

## 11. Risks

| Risk | Mitigation |
|---|---|
| **Repeating the removed tray's mistake** | Progress sits on top of durable, actionable history — the thing the tray lacked (§1) |
| A persisted running task strands at 40% forever | Running work is never a Room row (§4.2) |
| Progress ticks thrash the DB | Same — ticks touch in-memory state only; one write per task, at settle |
| File scanning's bar is indeterminate and looks broken | Labelled honestly, with a live file counter; `n of m Memory Cards` is the named upgrade path (§4.3) |
| Clearing the tray looks like it cancels a scan | `ClearDoesNotCancelTest`; running rows visibly stay after a clear |
| START's picker meaning breaks | Only the main-XMB branch changes; `NotificationPanelGateTest` pins both halves |
| The button reads as a passive status indicator | It is the one interactive item in that group; if the tint proves too subtle, fall back to a faint pressable surface |
| Nine new drawables stall the feature | Five can ship as existing-art or Material stand-ins; the mapping is one table |

---

## 12. Settled

1. ~~Does the panel show in-progress work?~~ **Yes** — file scanning, artwork downloads, scraping,
   achievement matching and syncing, and later file downloads, each with a progress bar and `n / m`
   where the producer reports counts (§2.4).
2. ~~Retention default.~~ **200 rows / 30 days stands for now.** The guarantee that matters is that
   the user can always clear the tray, and that clearing never touches running work (§4.2).
3. ~~Row icons.~~ **Nine glyphs, listed in §5**, with severity carried by ring colour rather than by
   separate art.
4. ~~What the button shows at zero unread.~~ **Dimmed and present. Never hidden** (§4.6).

Still open: whether `LibraryScanner` gets `n of m Memory Cards` progress in this pass or a later
one (§4.3), and confirmation of the icon art (§5).
