# Play Field Portal — Context Menu Consistency: Implementation Plan

Make every per-item menu (XMB rows, detail screens, viewers, players, App Drawer, Settings lists,
Artwork Studio) follow one set of rules: one panel, one set of movement/Back/Triangle/sound
rules, one name per action, settings as values, and a Cancel-first confirm before anything that
loses data.

**Status: planned, not started (2026-10-01). Effort L.**
**Gate: implement this plan LAST** — after the concurrent *Import Playlist* plan (adds a row to
the music/video playlist lists and the Music Browser list menu) and the *local-image category
icon* plan (adds "Your Image" to Change Icon) are DONE. Tasks that touch those areas list them as
`EXT-IP` / `EXT-CI` dependencies.

**Source:** the approved audit, section `p2` of the overnight mockups (13 house rules, mockups 1–5,
the "Fix first" list, the "One name per action" table, and the per-menu Belongs / Feels off / Add
data). Every claim was re-checked against the code on `final-polish` @ `ab536499`; the ones that
proved false are in §13 and are not planned.

> **Working rules for the implementing session**
> - Tests first: write each task's tests (§6) against the not-yet-changed code, see them fail,
>   then implement. Revert production code written ahead of its test.
> - Do not run Gradle unless the user asks. Hand the user each task's command block.
> - Done = the task's tests green and **no new Kotlin warnings** in the touched modules.
> - Match the surrounding comment style (explanatory `//` blocks that say *why*, KDoc on pure
>   builders).
> - One task per session. Stop at its Stop Condition. No commits unless the user asks.
> - UI that the approved mockups do not show (§12) needs a mockup and a yes before it is drawn.

---

## 1. Context

The audit found the same action written several ways, four hand-built menu panels next to the
shared one, destructive rows that delete on one press, confirms that open on the destructive
button, menus that wrap where others clamp, Back that closes everything in some screens and climbs
one level in others, Triangle that closes menus in some screens and is ignored in others, and
menus that play no sounds. The house rules the audit cites all exist in code already
(`PspContextMenu.kt`, `PfpModals.kt` `PfpModalNav`, `MemoryCardContextMenuItems.kt`,
`GameDetailViewModel.kt` options, `XMBViewModel.kt` `gamesFilterRows`, the Themes menu,
`SettingsScaffold`), so this plan converges on them rather than inventing new ones.

## 2. Current behaviour (verified)

| Area | Where | Today |
| --- | --- | --- |
| Shared panel | `core/core-ui/.../components/PspContextMenu.kt` `PspContextMenuOverlay`, `PspMenuRow(label, isDestructive, checked, value, header)` | No `opensMenu` (›). No panel-alpha parameter. |
| Copy of the panel | `feature/feature-xmb/.../ui/DetailContextMenu.kt` `DetailContextMenu`, `DetailMenuRow(..., opensMenu, header)`, `panelAlpha` | Used by `GameDetailScreen.kt:739`, `VideoDetailScreen.kt:314`, `PhotoViewerScreen.kt:268`, `AppDetailScreen.kt:192,202`. Its comment says a value and a › never share a row. |
| Hand-built menus | `appbar/appdrawer/AppDrawerOptions.kt` (centred box), `video/VideoPlayerScreen.kt` `OptionsOverlay` (rounded card), `VideoDetailScreen.kt` `PlaylistPicker` (● ○ "+ Create New Playlist") | Wrap-around movement; no sounds in menus. |
| XMB menu state | `XMBViewModel.kt:152` `XMBContextMenu` (has `parent` + `afterBack()`), `:243` `XMBContextMenuItem(id, label, isDestructive, checked, value, header)` | Clamps (`shiftContextMenu`), BACK climbs a level, Triangle closes (`:6182–6200`). No sounds on move/open/commit/close. |
| XMB confirms | `XMBViewModel.kt:7639–7668, 7471, 7612` | Drawn as a two-row XMB menu. `remove_game` and `remove_missing` open on the destructive row (no `selectedIndex`); `delete_collection` and `remove_category` open on Cancel. |
| XMB unconfirmed deletes | `:7672 remove_app`, `:7549 remove → removeCard`, `:4989 remove_track`, `:3665 video_remove`, `:4001 photo_remove`, `:5005 delete_playlist`, `:3718 delete_video_playlist`, `:8879 CLEAR_ALL` | Run on one press. |
| Triangle vs long-press | `XMBViewModel.kt:6592` (Triangle) vs `:9985 onItemLongPress` | Long-press lacks `openAchievementsContextMenu` and the mark-mode branch. `ContextMenuPredicateTest` claims to mirror both but only tests `hasContextMenu`. |
| Unreachable code | `XMBViewModel.kt:6617` second `OPEN_CONTEXT_MENU` branch; `:7512 menu.musicFolderId` branch + `handleMusicFolderAction` (`:4954`) — no `XMBContextMenu(musicFolderId = …)` is ever built; `appbar/storefront/StorefrontAppDrawer.kt` (`StorefrontAppDrawerScreen` has no caller) | Dead. |
| Game row menu | `viewmodel/GameContextMenuItems.kt` | Flat list up to ~20 rows; "Add to Favorites"/"Remove from Favorites" flips; "Add to Card…"; "View File Location"; Choose Disc comment says it launches (it only sets the preferred disc, `:7566`); emulator picker (`:7777`) has no checkmark. |
| App row menu | `XMBViewModel.kt:7183 openAppContextMenu` | "Launch" repeats X; "Add to Favorites" never flips (`addAppToFavorites` only sets true, `:10307`); "Move To Category"/"Add To Category"/"Remove From Category". |
| Container rows | `XmbLists.kt:349 rootRowMenuItems`, `:359 customCardMenuItems` | "Open" first (repeats X); Missing's menu is only "Open"; a custom card's Sort is always `XmbListKind.GAMES` (`sortTargetOf`, `:6695`). |
| Media rows | `XMBViewModel.kt:3602–3723, 3987–4024, 4636–4776` | Play/Open repeat X; "Remove From Library"; "Remove from this Playlist" is red; video "Play"/"Resume"/"Details" all open the same screen; "Stop & Close" vs "Stop and Close". |
| Notifications | `XMBViewModel.kt:8852` | Mark All Read / Clear Read / Clear All always listed; Clear All unconfirmed. |
| Social | `XMBViewModel.kt:9711` | Title "Account". |
| Category icons | `XMBShell.kt:948` → `XMBCategoryBar.onCategoryLongPress` | Only Settings, only the debug host (`app/src/debug/.../AppXmbHost.kt`) wires it. |
| Game Detail | `GameDetailViewModel.kt:383–429` (`DetailAction`), `GameDetailScreen.kt:1128 detailMenuRows` | Two levels with groups (the model). Triangle inert inside the menu (`:1185`). "Remove", "Custom Memory Cards", "Show Location", "Fetching Artwork..." (ASCII). Manual listed without a manual (`hasManual` exists, `:1040`). On-screen button "Unfavorite" (`:553`), opener `Icons.Filled.MoreHoriz` (`:585`) against `ARCHITECTURE.md:254` (⋮). |
| Video Detail | `VideoDetailViewModel.kt:26,132–204` | Wraps; Triangle ignored; Play/Resume/Start repeat page buttons; Favorite label flips; Remove not red; no menu sounds (no `MenuSoundPlayer`). |
| Photo Viewer | `PhotoViewerViewModel.kt:54,150` | Wraps; Zoom Out / Reset Zoom shown unzoomed; "Open File Location", "Remove From Library". |
| App Detail | `AppDetailViewModel.kt:33,349–387` | Two menus (Options, Artwork); "Change Game Icon"; Reset All Artwork unconfirmed; Triangle ignored in menus; static footer (`AppDetailScreen.kt:317`). |
| Shiba Coins | `ShibaCoinsViewModel.kt:300–312, 718–750, 1198–1210` | "Sort (Tier)"; "Refresh this game"; Change Match and Unlink both call `achievementRepository.unlink`; Unlink not red, unconfirmed; Back closes from a group. |
| Shiba Library / Player Status / Search Online | `ShibaLibraryViewModel.kt:240`, `PlayerStatusViewModel.kt:115`, `SearchOnlineViewModel.kt:241` | Values glued into labels ("Filter (Title A–Z)", "Provider (All)", "System (…)"); Back closes from a group; "Refresh preview"; Search Online footer "Change Provider" (`:300`). |
| Artwork Studio | `ArtworkStudioViewModel.kt:628 StudioAction`, `:664 SS_TYPES_FOR_KIND`, `:3324–3344`; `ArtworkStudioScreen.kt:909,1487,1492` | Clear Artwork / Forget Match unconfirmed; raw media codes; "CROP OPTIONS"; "Live Preview: On"; lowercase footer; Triangle ignored in menus. |
| App Drawer | `AppDrawerViewModel.kt:32–68, 165–288` | Box menu, wraps; Mark/Unmark label resolves async (wrong for a frame); Uninstall confirm: any SELECT confirms (`:243`). |
| Themes | `ThemesSettingsScreen.kt:168–176, 238–251` | Apply / Share / Remove (red, unconfirmed); commit plays `SELECT`; no footer Options prompt. |
| Settings rows | `SettingsScaffold.kt:1097 SettingsRow(onLongPress)`, `ControllerNavigation.kt` `ControllerNavItem.onLongPress` | Long-press wired for touch; Triangle never reaches it (`SettingsScaffold.kt:695–777` has no `OPEN_CONTEXT_MENU` branch). No screen passes `onLongPress`. |
| Settings lists | `BackupSettingsScreen.kt:70` (value rows, inert), `CollectionsSettingsScreen.kt:343` (X removes, "tap to remove from card"), `LibraryManagerScreen.kt:582,608` (X removes app / extension) | No per-item menus. |

## 3. Problem / root cause

Each surface grew its own menu state, its own panel or a copy of the shared one, and its own
input ladder. The shared pieces stop short: `PspMenuRow` has no chevron, `PfpModalNav` covers
modals but there is no equivalent for menus, the XMB draws confirms as menus, and Settings never
routes Triangle to a row. So every screen re-decides wrap vs clamp, Back vs close, sound vs
silence and label vs value — and they drifted.

## 4. Goals

1. Every destructive row is red, last, and confirms through `PfpConfirmModal` opening on Cancel.
2. One panel (`PspContextMenuOverlay`) for every per-item menu; `DetailContextMenu` is deleted.
3. One rule object for menu movement, Back, Triangle and sounds, used by every menu.
4. One name per action (glossary §8), settings as values, toggles as fixed label + On/Off, and
   no row that cannot act.
5. Long-press does what Triangle does — XMB rows, multi-select, Shiba Coins hub rows, Settings rows.
6. The approved additions: group headers (mockups 1, 3, 4), App Drawer parity (mockup 2), the
   category icon menu (mockup 5), Settings list menus, Change Match that matches.
7. Dead menus and stale comments removed.

## 5. Non-Goals (do not touch)

- The Artwork Studio's Apply / Replace / Leave prompts (`ArtworkStudioScreen.kt:1371–1400`). They
  are not data loss and keep their menu-drawn form.
- Custom Icons' Reset and Adjust XMB Layout's Reset (Triangle shortcuts, `CustomIconsOverlay.kt`,
  `XmbLayoutAdjustOverlay.kt`): no confirm added.
- "Hide Everywhere" undo hint; the notification panel's Square footer prompt; Game Detail's
  `EmulatorPickerPanel`; `AppPickerScreen`'s removal panel; Game Picker's "Whole Shelf".
- The Import Playlist rows and the local-image category icon — owned by their own plans. This plan
  leaves room for them (§10) and never removes them.
- Navigation core (`core/core-navigation`), `GamepadInputHandler`, the overlay ladder order in
  `XMBViewModel.dispatchGamepadAction` beyond the branches named in a task.
- Database schema. No Room migration in this plan.
- Video playlist "Add Videos" picker UI and Shiba Coins Change Match behaviour beyond what §12
  decides — both are BLOCKED until decided.

## 6. Test cases (write first)

Ranked: **P0** = a stray press can lose data or a rule is safety-relevant; **P1** = navigation /
input-parity behaviour; **P2** = wording and layout pins. New test files are marked *(new)*.
Module test roots: `feature/feature-xmb/src/test/kotlin/com/playfieldportal/feature/xmb/`,
`core/core-ui/src/test/kotlin/com/playfieldportal/core/ui/`, `feature/feature-appbar/src/test/...`,
`feature/feature-settings/src/test/...`, `core/core-data/src/test/...`.

### P0

| ID | Test file | Case → assertion | Task |
| --- | --- | --- | --- |
| P0-1 | `xmb/ui/ShellModalSpecTest.kt` | A `pendingConfirm` state maps to `PfpModalSpec.Confirm` with `destructive = true` and `openOnCancel = true`. | 1.1 |
| P0-2 | `xmb/viewmodel/XmbConfirmTest.kt` *(new)* | Each `XmbConfirm` kind → title / message / confirm label; Remove Card's message says ROM files stay on disk; Remove from Library says the next scan adds it back. | 1.1, 1.2 |
| P0-3 | `core/ui/components/PfpModalNavTest.kt` | `SELECT` on `initialConfirmFocus(destructive = true)` calls `onCancel`, never `onConfirm`. | 1.1 |
| P0-4 | `xmb/viewmodel/XmbConfirmTest.kt` | `confirmFor(menu, itemId)` is non-null for every `isDestructive` id produced by `gameContextMenuItems`, `platformCardMenuItems`, `customCardMenuItems` and the media builders, and for `CLEAR_ALL`. | 1.2, 3.4 |
| P0-5 | `xmb/ui/app/AppDetailModalSpecTest.kt` | Reset All Artwork → destructive confirm opening on Cancel; nothing cleared before Confirm. | 1.3 |
| P0-6 | `xmb/ui/detail/ArtworkStudioViewModelTest.kt` | Clear Artwork and Forget Match each open a confirm; the slot / match is untouched until Confirm; Cancel leaves it. | 1.4 |
| P0-7 | `xmb/ui/detail/ShibaCoinsOptionsTest.kt` + `ShibaCoinsViewModelTest.kt` | Unlink Game is `isDestructive` and last; activating it opens a confirm and `achievementRepository.unlink` is not called until Confirm. | 1.5 |
| P0-8 | `settings/ui/SavedThemeMenuTest.kt` *(new)* | Saved-theme rows end with "Delete Theme" (destructive); activating it yields a destructive confirm spec; `onDeleteSavedTheme` not called before Confirm. | 1.6 |
| P0-9 | `appbar/AppDrawerViewModelTest.kt` | With the uninstall confirm open, `SELECT` (initial focus) does not call `uninstallApp`; Right then Select does. Existing "back closes the dialog not the drawer" stays green. | 1.7 |
| P0-10 | `settings/ui/CollectionCardGameMenuTest.kt` *(new)* | A card game row's `onClick` no longer removes; "Remove from Card" lives in its menu. | 4.4 |
| P0-11 | `settings/viewmodel/BackupSettingsViewModelTest.kt` *(new)* | Delete Backup opens a confirm; the document is deleted only after Confirm; Restore enqueues `RestoreWorker` with that backup's uri. | 4.3 |

### P1

| ID | Test file | Case → assertion | Task |
| --- | --- | --- | --- |
| P1-1 | `core/ui/components/PspMenuNavTest.kt` *(new)* | UP at 0 / DOWN at last → index unchanged, no cue; a real move → `SCROLL`. | 2.1 |
| P1-2 | `PspMenuNavTest.kt` | BACK at depth > 0 → `Up` + `BACK`; BACK at root → `Close` + `BACK`; `OPEN_CONTEXT_MENU` at any depth → `Close` + `BACK`. | 2.1 |
| P1-3 | `PspMenuNavTest.kt` | Activation cue: `opensMenu` row → `SELECT`; commit row → `CONFIRM`; `silent` row (Favorite) → none. | 2.1 |
| P1-4 | `xmb/viewmodel/ContextMenuBackTest.kt` | A picker opened from a menu: BACK climbs to the parent with the cursor where it was; Triangle closes outright; each emits the PspMenuNav cue. | 2.2 |
| P1-5 | `xmb/viewmodel/ContextMenuPredicateTest.kt` | For every row type already covered: `contextMenuTarget(item, state, byTouch = false) == contextMenuTarget(item, state, byTouch = true)` in kind. New: Shiba Coins hub rows and mark mode resolve the same for both. `hasContextMenu` == `target != null`. | 2.3 |
| P1-6 | `xmb/viewmodel/MediaContextMenuItemsTest.kt` *(new)* | Controller-opened music track / video file / photo / playlist / library menus have no Play/Open/Resume/Details; long-press ones keep exactly one (video: "View Details"). | 3.4 |
| P1-7 | `xmb/ui/detail/GameDetailViewModelTest.kt` | Triangle with a sub-panel open closes the whole menu; Back steps out one level (existing case in `GameDetailOptionsMenuTest` kept). | 2.4 |
| P1-8 | `xmb/ui/detail/VideoDetailViewModelTest.kt` | Options clamp (UP at 0 stays 0), Triangle closes, sounds on move/open/close; playlist picker clamps and keeps checkmarks. | 2.6 |
| P1-9 | `xmb/ui/photo/PhotoViewerOptionsTest.kt` *(new)* + `xmb/ui/app/AppDetailOptionsTest.kt` *(new)* | Photo: clamp, no wrap. App Detail: Triangle closes a menu; BACK from the Artwork sub-panel returns to Options. | 2.5, 4.7 |
| P1-10 | `appbar/AppDrawerViewModelTest.kt` | Menu clamps (DOWN at last stays); moving plays `SCROLL`; the menu is published only once Favorite / Mark as Game values are known. | 2.7, 4.1 |
| P1-11 | `xmb/video/VideoPlayerOptionsTest.kt` *(new)* | `videoPlayerOptionRows` root rows carry values and `opensMenu`; a sub-list checks the active choice; Back climbs. | 2.8 |
| P1-12 | `ShibaCoinsViewModelTest.kt`, `ShibaLibraryViewModelTest.kt`, `SearchOnlineViewModelTest.kt`, `PlayerStatusOptionsTest.kt` | BACK inside a group returns to the root with the cursor on the group's row; Triangle closes from a group. | 2.9 |
| P1-13 | `settings/ui/ControllerNavigationStateTest.kt` + `SettingsScaffoldNavigationTest.kt` | Triangle on a row with `onLongPress` runs it once; on a row without, nothing happens and nothing is consumed silently by the scaffold. | 2.10 |
| P1-14 | `xmb/viewmodel/CategoryMenuItemsTest.kt` *(new)* | Mockup 5 rows in order; Settings has no Show on Bar; a category's Move row present only while visible. | 4.2 |
| P1-15 | `ContextMenuPredicateTest.kt` | Long-press on a category icon opens the category menu; Triangle on the bar does not (it acts on the row). | 4.2 |
| P1-16 | `core/data/repository/PfpThemeStoreTest.kt` | `rename(id, name)` changes the listed name and the bundle's manifest name; other bundle bytes unchanged. | 4.6 |

### P2

| ID | Test file | Case → assertion | Task |
| --- | --- | --- | --- |
| P2-1 | `xmb/viewmodel/GameContextMenuItemsTest.kt` | Mockup 1 order and headers (Play / Library / Arrange / Customize / PC / Manage); "Favorite" with value On/Off; "Add to Card" `opensMenu`; "Show File Location"; Change Emulator value + `opensMenu`. Update `a game's card rows say Card` (pins "Add to Card…"). | 3.1 |
| P2-2 | `xmb/viewmodel/ArrangeMenusTest.kt` | Controller-opened root-row / custom-card menus have no "Open"; Missing has no menu; an app card's Sort offers app sorts. | 3.3 |
| P2-3 | `appbar/AppMenuItemsTest.kt` *(new)* | App row: "Favorite" On/Off, "Add to Card ›", "Move to Category ›", "Add to Category ›", "Remove from Category", App Info, Uninstall (red, last); no Launch when `byTouch = false`. | 3.2 |
| P2-4 | `xmb/ui/detail/GameDetailOptionsMenuTest.kt` | Update `the renamed rows read as agreed`: "Add to Card", "Remove from Library", "Show File Location"; Manual hidden when `hasManual = false`; "Fetching Artwork…" with U+2026. | 3.5 |
| P2-5 | `VideoDetailViewModelTest.kt` | Mockup 3 rows and headers; Favorite fixed label + value; Remove red. | 3.6 |
| P2-6 | `ShibaCoinsOptionsTest.kt` | Mockup 4: "Sort" value "Tier" `opensMenu`; "Update Achievements" / "Updating…"; "Change Match" `opensMenu`; "Unlink Game" red last. Replace every "Sort (Tier)" / "Refresh this game" pin and fix the stale KDoc. | 3.7 |
| P2-7 | `PlayerStatusOptionsTest.kt`, `ShibaLibraryViewModelTest.kt`, `SearchOnlineViewModelTest.kt` | Root labels "Sort" / "Provider" / "System" with values; "Refresh Preview"; Search Online footer "Options". | 3.8 |
| P2-8 | `ArtworkStudioViewModelTest.kt` / `StudioFiltersTest.kt` | Media filter values read "Box Art 2D", not "box-2D"; crop menu title "Crop Options", row "Live Preview" with value. | 3.9 |
| P2-9 | `xmb/viewmodel/NotificationPanelLogicTest.kt`, `MusicBrowserSortLabelTest.kt` | Notification rows hidden when they cannot act; browser list menu "Sort" with value and `opensMenu`, "Resume" with the track as value. | 3.10 |
| P2-10 | `settings/ui/SettingsGlossaryTest.kt` *(new, label constants)* | "Edit Folder" / "Remove Folder" in both families; "Options Hint"; "Show on Bar". | 3.11 |

## 7. Architectural decisions

| # | Decision | Reason | Rules out |
| --- | --- | --- | --- |
| AD-1 | **One confirm surface: `PfpConfirmModal` via `PfpModalSpec.Confirm(destructive = true)`.** XMB gets one `pendingConfirm: XmbConfirm?` in `XMBUiState`, mapped first in `XMBShell.shellModalSpec` and forwarded through the existing `forwardToShellModal`. Screens with a modal host add a spec; Coins, Studio and the App Drawer gain a host (`rememberPfpModalHost`). | Approved decision 2; `PfpModalNav.initialConfirmFocus` already encodes "Cancel first"; the shell already hosts confirms (Windows setup, shortcut review, Stop). | XMB two-row confirm menus (all four migrate, including the ones that already open on Cancel). |
| AD-2 | **One panel: `PspContextMenuOverlay`.** `PspMenuRow` gains `opensMenu` (draws ›) and the overlay gains `panelAlpha` (Game Detail's 0.88). A row may show a value **and** › (mockups 1, 4, 5 do). `DetailContextMenu.kt` is deleted once its four callers move. | Rule 1; the approved mockups. | Keeping two panels; DetailContextMenu's "never both" rule. |
| AD-3 | **One rule object: `PspMenuNav` in `core-ui`, beside `PfpModalNav`.** Pure; given an action, index, row count, depth and the focused row's cue kind it returns an outcome (`Moved`, `Activate`, `Up`, `Close`, `Ignored`) and plays the cue: `SCROLL` only when the index changes, `SELECT` on opening/descending, `CONFIRM` on a commit, `BACK` on Up/Close; Triangle closes from any depth; no wrap. | Rules 9–11; decision 3; one place to test. | Per-screen modulo wrap; per-screen sound decisions. |
| AD-4 | **Cue kind lives on the row.** `XMBContextMenuItem` and `PspMenuRow` gain `opensMenu`; XMB rows gain `silent` (Favorite). The XMB and VM-driven screens play through their injected `MenuSoundPlayer`; composition-driven ones (Themes, Video Player, Settings) through `LocalMenuSounds`. | The `MenuSoundSink` doc's split; Favorite stays silent by prior decision. | Sounds inferred from labels. |
| AD-5 | **Repeat-the-press rows are removed from controller-opened menus only.** XMB builders take `byTouch`; `onItemLongPress` passes true. Open / Play / Launch stay for long-press; "View Game Details" always stays. Video Detail's Play/Resume/Start and Themes' Apply go in every case — those menus sit beside the page buttons (Video) or are only reachable by Triangle (Themes). | Decision 1 and mockup 3. | One global rule that breaks touch-only users on XMB rows. |
| AD-6 | **One open-target resolver for Triangle and long-press in the XMB:** `contextMenuTarget(item, state)` (pure, top-level beside `hasContextMenu`); both entry points and `hasContextMenu` read it. | Rule 12; the predicate test's own stated intent. | Two `when` ladders kept "in sync" by comment. |
| AD-7 | **Every menu's rows come from a pure builder with a test,** following `GameContextMenuItems.kt`: new `MediaContextMenuItems.kt` and `CategoryMenuItems.kt` (feature-xmb `viewmodel/`), `AppMenuItems.kt` (feature-appbar, shared by the XMB app row and the App Drawer), `videoPlayerOptionRows` (video), saved-theme / backup / card-game / library rows (feature-settings). | Testability without the 11k-line ViewModel; one row vocabulary per action. | Rows assembled inline in openers. |
| AD-8 | **The App Drawer keeps its own menu state** (`AppDrawerViewModel`), renders the PSP panel, and takes its rows from `AppMenuItems.kt`. XMB-owned actions (Edit App Details, Add to Card) go up through new `AppDrawerScreen` callbacks into `XMBViewModel` (`openAppDetail`, `addAppToCollection`), whose menus already draw over the drawer and capture input first. | Smallest change that gives parity; drawer-only actions (App Info, Uninstall) already live in the drawer. | Moving the drawer menu into `XMBContextMenu`. |
| AD-9 | **The category icon menu is XMB-owned and reuses Category Manager for Rename and Change Icon** via a deep link (`settings_categories` opened on that category with its rename modal or icon picker up). Show on Bar uses `CategoryRepositoryImpl.setVisible`, Move uses `startCategoryMove`, Manage opens `settings_categories`. | Reuse rename validation and whatever picker the EXT-CI plan ships. | A second icon picker or rename dialog in the XMB. |
| AD-10 | **Settings item menus: Triangle runs the focused row's `onLongPress`.** `SettingsScaffold` gains an `OPEN_CONTEXT_MENU` branch calling a new `ControllerNavigationState.longPressFocused()`; screens open a PSP menu from `onLongPress` through one small shared host (`SettingsItemMenu`, generalising Themes' `ThemeMenu`). The footer shows "Options" while the focused row has one. | Decision 5; `SettingsRow.onLongPress` already reaches the nav node. | Per-screen Triangle interceptors. |
| AD-11 | **Labels change; ids stay** (except where a toggle collapses to one id with a value). Ids are what handlers and tests key on. | Smaller diffs, stable handlers. | Renaming ids for the glossary. |
| AD-12 | **Reversible removals** (Remove from Card, Remove from Category, Remove from this Playlist, Hide) are not red and do not confirm. Remove from Category keeps its existing *conditional* prompt (it also drops the game's card memberships, which Hidden Items cannot restore), moved to the modal, `openOnCancel`, not red. | Decision 2; the prompt exists for a different loss. | Dropping that prompt. |

## 8. Glossary (applied in Pass 3)

| Action | Use |
| --- | --- |
| Remove an imported item | **Remove from Library** |
| Delete something you created | **Delete ‹Thing›** (Delete Playlist, Delete Custom Card, Delete Category, Delete Theme, Delete Backup) |
| Change a title | **Edit Title** (imported items) · **Rename ‹Thing›** (created things) — Video Detail "Rename Title" → "Edit Title"; App Detail "Change Display Name" → "Edit Title" |
| Item info | **View Information** |
| File path | **Show File Location** |
| Favorite | **Favorite** + On/Off value |
| Card membership | **Add to Card ›** (Game Detail's "Custom Memory Cards" included) |
| Category moves | **Move to Category ›**, **Add to Category ›**, **Remove from Category** |
| Achievement refresh | **Update Achievements** (one game) · **Update Installed Achievements** (all) · "Updating…" |
| Stop music | **Stop and Close** |
| Sort | **Sort** + value (Shiba Library's "Filter" becomes "Sort") |
| Folders | **Edit Folder** / **Remove Folder** |
| Triangle's prompt | **Options**; setting "Context Menu Hint" → **Options Hint** |
| Ellipsis | "…" (U+2026), never "..." |

## 9. Rejected alternatives

- **Keep `DetailContextMenu` and only add `opensMenu` to `PspMenuRow`.** Two panels drift again;
  the audit's first finding is exactly that drift.
- **Keep XMB confirms as in-menu rows (the Delete Custom Card form).** Contradicts decision 2, and
  two of the four already open on the destructive row; the modal's Cancel-first rule is tested once.
- **Route the App Drawer's menu into `XMBContextMenu`.** Moves App Info / Uninstall / Mark as Game
  out of the module that implements them and couples the drawer to XMB state for no user gain.
- **Copy Category Manager's rename and icon picker into the XMB.** Duplicates validation and would
  miss "Your Image" from the EXT-CI plan.
- **Wrap-around movement anywhere.** Rule 10; XMB, Themes and App Detail already clamp.
- **Inject `MenuSoundPlayer` into composition-driven screens.** `MenuSoundSink`'s doc already
  decides that seam (`LocalMenuSounds`).
- **Remove Shiba Coins' Change Match.** Rejected by decision 6.

## 10. Coexistence with the concurrent plans

- **EXT-IP — `PFP_Playlist_File_Import_Implementation_Plan.md`.** It adds "Import Playlist" as list
  *rows* (items after Create Playlist, built by `playlistRootItems` / `videoPlaylistItems`, moved to
  a tested builder file — its D10) and a menu row `music_browser_import` in `browserListMenuItems()`
  (Playlists view only), handled under the `music_browser_` prefix (`XMBViewModel.kt:7492`).
  This plan never touches the list-row builders. Task 3.10 turns `browserListMenuItems()` into a
  pure builder and keeps `music_browser_import` in the list-level group, after Sort, still
  Playlists-only. If EXT-IP placed it differently, keep its placement and report.
- **EXT-CI — `PFP_Category_Local_Icons_Implementation_Plan.md`.** "Your Image" lives in Category
  Manager's own Change Icon step (`PICK_ICON`, "From Your Device" group, user categories only), and
  its rows gain an `iconLabel` ("Your Image" or the catalog label). Task 4.2's Change Icon deep-links
  to that step (AD-9) and shows the same label. If `iconLabel` is computed only inside
  `CategoryManagerViewModel`, 4.2 extracts the rule to a pure function beside EXT-CI's
  `core-ui/.../icons/UserCategoryIconKeys.kt` rather than copying it.

## 11. Implementation phases and tasks

Passes follow the mockup page. Each task: tests first, then code. Commands are for the user to run.

### Pass 1 — Safety

#### Task 1.1 — Shell confirm for the XMB, and the existing confirms onto it
**Objective:** one Cancel-first destructive confirm for every XMB menu.
**Scope:** add `XmbConfirm` (sealed kinds + pure `xmbConfirmCopy`) in a new
`viewmodel/XmbConfirm.kt`; `XMBUiState.pendingConfirm`; map it first in `shellModalSpec`
(`XMBShell.kt:1545`) as `PfpModalSpec.Confirm(destructive = true)` (Remove from Category:
`openOnCancel = true`, not destructive — AD-12); forward presses with `forwardToShellModal` from a
branch directly under the context-menu branch (`XMBViewModel.kt:6182`), so it also wins over the
notification panel; migrate `remove_game`, `remove_missing`, `delete_collection`,
`remove_category` (`:7471, 7612, 7639, 7654`) and delete their `confirm_*`/`cancel_*` ids.
**Do Not Change:** what each confirm does once confirmed; any other menu row.
**Acceptance:** P0-1, P0-2 (these four kinds), P0-3 green; the four prompts open on Cancel; no
two-row confirm menu remains in `XMBViewModel.kt`.
**Change Budget:** 3 modified (`XMBViewModel.kt`, `XMBShell.kt`, `ShellModalSpecTest.kt`), 1 new
source, 1 new test, plus one case in `PfpModalNavTest.kt`.
**Stop:** at acceptance. **If Blocked:** report the ladder branch that swallowed the press.
```bash
./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*ShellModalSpecTest*" --tests "*XmbConfirmTest*"
./gradlew :core:core-ui:testDebugUnitTest --tests "*PfpModalNavTest*"
```

#### Task 1.2 — Confirm every unconfirmed XMB delete
**Objective:** no XMB row loses data on one press.
**Scope:** route through `pendingConfirm`: `remove_app` (`:7672`), card `remove` (`:7549`),
`remove_track` (`:4989`), `video_remove` (`:3665`), `photo_remove` (`:4001`), `delete_playlist`
(`:5005`), `delete_video_playlist` (`:3718`), `NotificationMenuIds.CLEAR_ALL` (`:8879`). Add the pure
`confirmFor(menu, itemId)` used by P0-4. Labels unchanged here (Pass 3 renames).
**Do Not Change:** the repository calls each performs; Clear Read / Mark All Read.
**Acceptance:** P0-2 (all kinds), P0-4 for game, card and custom-card builders green.
**Change Budget:** 2 modified (`XMBViewModel.kt`, `XmbConfirm.kt`), 1 test file.
**Stop / If Blocked:** as 1.1.
```bash
./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*XmbConfirmTest*" --tests "*NotificationPanel*"
```

#### Task 1.3 — App Detail: confirm Reset All Artwork
**Objective:** Reset All Artwork asks first.
**Scope:** `AppDetailUiState.confirmReset`; `RESET_ARTWORK` opens it (`AppDetailViewModel.kt:385`);
add a destructive `PfpModalSpec.Confirm` to the screen's existing modal spec (`AppDetailScreen.kt:514`).
**Do Not Change:** `clearAllArtwork()`; the two-menu structure (Task 4.7).
**Acceptance:** P0-5. **Budget:** 2 modified + `AppDetailModalSpecTest.kt`.
```bash
./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*AppDetailModalSpecTest*"
```

#### Task 1.4 — Artwork Studio: confirm Clear Artwork and Forget Match
**Objective:** neither runs on one press.
**Scope:** a `destructiveConfirm` state in `ArtworkStudioViewModel`; a modal host in
`ArtworkStudioScreen` fed the forwarded press; Clear's message names the backup that goes too.
**Do Not Change:** Apply / Replace / Leave prompts (Non-Goals).
**Acceptance:** P0-6. **Budget:** 2 modified + `ArtworkStudioViewModelTest.kt`.
**If Blocked:** if the Studio's input forwarding cannot reach a modal host, stop and report.
```bash
./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*ArtworkStudioViewModelTest*"
```

#### Task 1.5 — Shiba Coins: Unlink Game red, last, confirmed
**Objective:** Unlink cannot happen by accident.
**Scope:** `CoinOptionRow` gains `isDestructive`; Unlink last and red; activating opens a confirm
hosted in `ShibaCoinsScreen` (new modal host); `unlinkGame()` only on Confirm.
**Do Not Change:** Change Match behaviour (Task 4.9); labels (Task 3.7).
**Acceptance:** P0-7. **Budget:** 2 modified (`ShibaCoinsViewModel.kt`, `ShibaCoinsScreen.kt`) + 2 tests.
```bash
./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*ShibaCoinsOptionsTest*" --tests "*ShibaCoinsViewModelTest*"
```

#### Task 1.6 — Themes: Delete Theme confirmed
**Objective:** a saved theme is deleted only after a confirm.
**Scope:** extract `savedThemeMenuRows(theme)` (pure) from `openMenuForSavedTheme`
(`ThemesSettingsScreen.kt:168`); "Remove" → "Delete Theme", red, last; it opens a destructive
confirm through the screen's `rememberSettingsModal`.
**Do Not Change:** Share; the card row's own buttons; Apply (removed in 2.10).
**Acceptance:** P0-8. **Budget:** 1 modified, 1 new test.
```bash
./gradlew :feature:feature-settings:testDebugUnitTest --tests "*SavedThemeMenuTest*"
```

#### Task 1.7 — App Drawer: Uninstall through `PfpConfirmModal`
**Objective:** X can no longer go straight through the uninstall guard.
**Scope:** replace `UninstallConfirmDialog` use with a modal host in `AppDrawerScreen` (spec from
`confirmUninstall`, destructive); presses go to `modal.intercept` while open; drop the
"any SELECT confirms" branch (`AppDrawerViewModel.kt:243`).
**Do Not Change:** the menu itself (2.7); `appRepository.uninstallApp`.
**Acceptance:** P0-9; existing drawer tests green.
**Budget:** 2 modified, `appdrawer/AppDrawerOptions.kt` loses `UninstallConfirmDialog`, 1 test.
```bash
./gradlew :feature:feature-appbar:testDebugUnitTest --tests "*AppDrawerViewModelTest*"
```

### Pass 2 — One component, shared rules

#### Task 2.1 — `PspMenuRow.opensMenu`, `panelAlpha`, and `PspMenuNav`
**Objective:** the shared panel and the shared rules every later task adopts.
**Scope:** `PspMenuRow.opensMenu` (value then ›, both allowed — AD-2); `PspContextMenuOverlay(panelAlpha)`;
new `PspMenuNav` (AD-3) with a `PspMenuCue { NONE, SELECT, CONFIRM }` row kind; previews updated;
`XMBContextMenuItem.opensMenu` / `silent` and `ContextMenuOverlay`'s mapping extracted to a pure
`XMBContextMenu.toPspRows()`.
**Do Not Change:** colours, sizes, the glow; no adopter yet.
**Acceptance:** P1-1..P1-3 green; mapping test passes `opensMenu` through.
**Budget:** 2 modified + `ContextMenuOverlay.kt`, 1 new source, 1 new test.
```bash
./gradlew :core:core-ui:testDebugUnitTest --tests "*PspMenuNavTest*"
./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*ContextMenuBackTest*"
```

#### Task 2.2 — XMB menus on `PspMenuNav`, with sounds
**Objective:** XMB menus clamp, climb, close and sound like Themes.
**Scope:** the context-menu branch (`XMBViewModel.kt:6182`) and touch activation go through
`PspMenuNav`; one opening seam plays `SELECT`; `opensMenu` set on rows that open pickers (Add to
Card, Change Emulator, Icon Display, Choose Disc, Move/Add to Category, Add to Playlist, Sort);
Favorite rows `silent`.
**Do Not Change:** Games Filter group behaviour beyond using the same rules.
**Acceptance:** P1-4. **Budget:** 2 modified + `ContextMenuBackTest.kt`.
```bash
./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*ContextMenuBackTest*" --tests "*GamesFilterTest*"
```

#### Task 2.3 — Long-press is Triangle in the XMB
**Objective:** one resolver for both entry points (AD-6).
**Scope:** `contextMenuTarget(item, state)` beside `hasContextMenu`; `onItemLongPress` and the
Triangle branch dispatch on it, long-press with `byTouch = true`; adds the hub rows and mark mode to
long-press; `hasContextMenu` derives from it. Rewrite the predicate test's KDoc to say what it pins.
**Do Not Change:** which rows have menus today.
**Acceptance:** P1-5. **Budget:** 1 modified + `ContextMenuPredicateTest.kt` (+ `ContextMenuHintStateTest` stays green).
```bash
./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*ContextMenuPredicateTest*" --tests "*ContextMenuHintStateTest*"
```

#### Task 2.4 — Game Detail on the PSP panel; Triangle closes
**Scope:** `detailMenuRows` returns `PspMenuRow`; `GameDetailScreen.kt:739` uses
`PspContextMenuOverlay(panelAlpha = 0.88f)`; Triangle with options open → `closeOptions()` from any
depth (`GameDetailViewModel.kt:1185` path through the options modal).
**Do Not Change:** the engine's modal contexts; labels (3.5).
**Acceptance:** P1-7; `GameDetailOptionsMenuTest` updated to the new row type and green.
**Budget:** 2 modified + 2 tests.
```bash
./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*GameDetailOptionsMenuTest*" --tests "*GameDetailViewModelTest*"
```

#### Task 2.5 — Photo Viewer and App Detail on the panel and the rules
**Scope:** both screens render `PspContextMenuOverlay`; both VMs use `PspMenuNav` (inject
`MenuSoundPlayer`); Photo stops wrapping; App Detail's menus close on Triangle.
**Acceptance:** P1-9 (photo clamp; App Detail Triangle). **Budget:** 4 modified + 2 new tests.
Reason for 4: the two screens are the remaining small callers; splitting doubles review for a
mechanical swap.
```bash
./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*PhotoViewerOptionsTest*" --tests "*AppDetailOptionsTest*" --tests "*AppDetailModalSpecTest*"
```

#### Task 2.6 — Video Detail menu and playlist picker on the panel; delete `DetailContextMenu`
**Scope:** options and the Add to Playlist picker become `PspContextMenuOverlay` (checkmarks,
"Create New Playlist"); `VideoDetailViewModel` uses `PspMenuNav` (clamp, Triangle closes, cues;
inject `MenuSoundPlayer`, update the test constructor); delete `ui/DetailContextMenu.kt` and the
`PlaylistPicker` composable.
**Do Not Change:** row contents (3.6).
**Acceptance:** P1-8; no reference to `DetailContextMenu` remains.
**Budget:** 2 modified, 1 deleted, 1 test.
```bash
./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*VideoDetailViewModelTest*" --tests "*VideoDetailModalSpecTest*"
```

#### Task 2.7 — App Drawer menu on the panel
**Scope:** `AppDrawerScreen` renders `PspContextMenuOverlay` for `menuApp`; `AppDrawerViewModel`
uses `PspMenuNav` (clamp, Triangle/Back/X close, cues); delete `AppDrawerOptions`.
**Do Not Change:** rows (4.1).
**Acceptance:** P1-10 (clamp + cue part). **Budget:** 2 modified, 1 deleted, 1 test.
```bash
./gradlew :feature:feature-appbar:testDebugUnitTest --tests "*AppDrawerViewModelTest*"
```

#### Task 2.8 — Video Player options on the panel with › sub-lists
**Scope:** pure `videoPlayerOptionRows(state, group)`: root rows Playback Speed / Subtitles / Audio
Track / Screen Mode with values and `opensMenu`; each opens its list (speeds, track choices from a
pure track-list function, screen modes) with the active one checked; `PspMenuNav` drives it with
`LocalMenuSounds`; replace `OptionsOverlay` (`VideoPlayerScreen.kt:534`).
**Do Not Change:** playback, seek, queue keys.
**Acceptance:** P1-11. **Budget:** 1 modified, 1 new source, 1 new test.
**If Blocked:** if Media3 track enumeration cannot be made pure, keep in-place cycling on the
panel and report.
```bash
./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*VideoPlayerOptionsTest*"
```

#### Task 2.9 — Coins, Library, Player Status, Search Online: Back climbs, Triangle closes
**Scope:** each `handleOptionsAction` goes through `PspMenuNav` (depth = group != null); BACK in a
group returns to the root on the opener row.
**Acceptance:** P1-12. **Budget:** 4 VMs modified + their tests. Reason: four copies of the same
ten lines; doing them together keeps them identical.
```bash
./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*ShibaCoins*" --tests "*ShibaLibrary*" --tests "*SearchOnline*" --tests "*PlayerStatus*"
```

#### Task 2.10 — Settings: Triangle runs a row's long-press; Themes on the rules
**Scope:** `ControllerNavigationState.longPressFocused()`; `SettingsScaffold` `OPEN_CONTEXT_MENU`
branch; footer gains "Options" while the focused row has `onLongPress`; small `SettingsItemMenu`
host (state + `PspContextMenuOverlay` + `PspMenuNav` with `LocalMenuSounds`); Themes moves onto it
(commit cue `CONFIRM`, Apply removed — AD-5) and its saved-theme strip shows "Options" in the footer.
**Acceptance:** P1-13; P0-8 still green. **Budget:** 3 modified, 1 new source, 2 tests.
```bash
./gradlew :feature:feature-settings:testDebugUnitTest --tests "*ControllerNavigationStateTest*" --tests "*SettingsScaffoldNavigationTest*" --tests "*SavedThemeMenuTest*"
```

### Pass 3 — Wording, values, toggles, hidden rows

#### Task 3.1 — XMB game row: mockup 1
**Scope:** `gameContextMenuItems` grouped Play / Library / Arrange / Customize / PC / Manage via the
`group` helper (move it from `MemoryCardContextMenuItems.kt` to a shared internal); "Favorite" (one
id `favorite_toggle`, value On/Off, silent); "Add to Card" ›; Change Emulator value (override name
or "Default") ›, and the emulator picker checks the current choice (`:7777`); Icon Display value ›;
"Show File Location"; Remove last; fix the Choose Disc comments (`GameContextMenuItems.kt:36`,
`XMBViewModel.kt:7794`). Manage Custom Cards stays in Library (§12 Q2).
**Acceptance:** P2-1; `GameContextMenuItemsTest` updated. **Budget:** 3 modified, 1 test.
```bash
./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*GameContextMenuItemsTest*" --tests "*MemoryCardContextMenuItemsTest*"
```

#### Task 3.2 — XMB app row: shared builder, wording, parity rows
**Scope:** new `feature-appbar/.../AppMenuItems.kt` `appMenuItems(context)` → entries (id, label,
value, header, opensMenu, destructive); XMB maps them in `openAppContextMenu`. Groups Library /
Arrange / Manage; Favorite On/Off (resolve the shortcut row's `isFavorite` before publishing; the
handler toggles); "Move to Category ›", "Add to Category ›", "Remove from Category"; App Info and
Uninstall (red, last, shell confirm `XmbConfirm.Uninstall`); Launch only when `byTouch`.
**Acceptance:** P2-3. **Budget:** 1 modified (`XMBViewModel.kt`), 1 new source, 1 new test (+ `XmbConfirm.kt`).
```bash
./gradlew :feature:feature-appbar:testDebugUnitTest --tests "*AppMenuItemsTest*"
./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*XmbConfirmTest*"
```

#### Task 3.3 — Container rows
**Scope:** `rootRowMenuItems` / `customCardMenuItems` take `byTouch` (Open only then); Missing has no
controller menu (`contextMenuTarget` returns null when the builder is empty); a custom card in an
app category sorts with app sorts (`sortTargetOf` uses the card's category kind). All Games is
left as is (§12 Q3).
**Acceptance:** P2-2. **Budget:** 2 modified (`XmbLists.kt`, `XMBViewModel.kt`) + `ArrangeMenusTest.kt`.
```bash
./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*ArrangeMenusTest*" --tests "*ContextMenuPredicateTest*"
```

#### Task 3.4 — XMB media rows
**Scope:** new `viewmodel/MediaContextMenuItems.kt` builders for music track, playlist row, video
file, video library, video playlist, photo file, photo album, Now Playing and the music player
options; openers call them. Repeat rows only with `byTouch` (video: one "View Details"); "Favorite"
On/Off; "Add to Playlist ›"; "Remove from Library"; "Remove from this Playlist" not red; "Stop and
Close" in both music menus; fix the Now Playing comment (`:4761`). Keep any EXT-IP row.
**Acceptance:** P1-6, P0-4 (media). **Budget:** 1 modified, 1 new source, 1 new test.
```bash
./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*MediaContextMenuItemsTest*" --tests "*XmbConfirmTest*"
```

#### Task 3.5 — Game Detail wording and hidden rows
**Scope:** `DetailAction` labels "Add to Card" (opens the picker; `opensMenu`), "Remove from
Library", "Show File Location"; "Fetching Artwork…"; Manual hidden when `!hasManual`; on-screen
Favorite button keeps "Favorite" (state from its icon); opener `Icons.Filled.MoreVert`.
**Acceptance:** P2-4; `GameDetailScreenContentTest` / `GameDetailHelperFooterTest` green.
**Budget:** 2 modified, 1 test. Mockup note: icon/label swap only (§12 Q4).
```bash
./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*GameDetail*"
```

#### Task 3.6 — Video Detail (mockup 3) and Photo Viewer rows
**Scope:** Video: Library (Favorite On/Off, Add to Playlist ›), Customize (Edit Title, Change
Thumbnail), Manage (View Information, Show File Location, Remove from Library red); no Play rows;
Edit Title modal title. Photo: View (Rotate Left/Right, Zoom In; Zoom Out and Reset Zoom only when
zoomed) and Manage (Set as Launcher Wallpaper, View Information, Show File Location, Remove from
Library); "Show File Location" shows the path notice.
**Acceptance:** P2-5, photo rows in `PhotoViewerOptionsTest`. **Budget:** 4 modified, 2 tests.
```bash
./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*VideoDetail*" --tests "*PhotoViewer*"
```

#### Task 3.7 — Shiba Coins (mockup 4) wording
**Scope:** `coinOptionRows`: "Sort" value + ›, "Update Achievements"/"Updating…", "Change Match" ›,
"Unlink Game" last red; stale comments (`ShibaCoinsViewModel.kt:45,744`, `ShibaCoinsScreen.kt:82,267`)
and the test KDoc.
**Acceptance:** P2-6. **Budget:** 2 modified, 1 test.
```bash
./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*ShibaCoinsOptionsTest*" --tests "*ShibaCoinsHelperFooterTest*"
```

#### Task 3.8 — Library, Player Status, Search Online values (+ Library update row)
**Scope:** row types gain `value` / `opensMenu` and screens pass them to `PspMenuRow`; Library
"Filter" → "Sort"; Search Online "Refresh Preview" and footer "Options"; Shiba Library adds "Update
Installed Achievements" (same call Player Status uses, `PlayerStatusViewModel.kt:323`).
**Acceptance:** P2-7. **Budget:** 3 VMs + 3 screens = 6 (reason: label/value plumbing per screen) + tests.
```bash
./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*PlayerStatus*" --tests "*ShibaLibrary*" --tests "*SearchOnline*"
```

#### Task 3.9 — Artwork Studio wording; Triangle closes its menus
**Scope:** readable names for `SS_TYPES_FOR_KIND` codes in the Media filter; "Crop Options"; "Live
Preview" + value; Title Case footer; Triangle closes actions / filter group / crop menus.
**Acceptance:** P2-8. **Budget:** 2 modified, 1–2 tests.
```bash
./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*ArtworkStudioViewModelTest*" --tests "*StudioFiltersTest*"
```

#### Task 3.10 — XMB list-level menus
**Depends:** EXT-IP. **Scope:** notification menu rows hidden when they cannot act (no unread / no
read / empty) and the menu not opened when empty; social menu title = account name; Music Browser
list menu as a pure builder: "Resume" (value = track), "Sort ›" (value) opening the sort list
instead of `cycleSort()`, EXT-IP's Import Playlist kept in the group.
**Acceptance:** P2-9. **Budget:** 1 modified, 1 new source, 2 tests.
```bash
./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*NotificationPanelLogicTest*" --tests "*MusicBrowserSortLabelTest*"
```

#### Task 3.11 — Settings wording
**Scope:** "Edit directory"/"Remove directory" (`RootAccessSection.kt:78,91`) and the wizard's
"Edit folder"/"Remove folder" (`WizardRows.kt:252,265`) all become "Edit Folder"/"Remove Folder"
(labels and content descriptions); "Context Menu Hint" → "Options Hint"
(`DisplaySettingsScreen.kt:506`); Category Manager "Show On Bar" → "Show on Bar".
**Acceptance:** P2-10. **Budget:** 4 modified, 1 test.
```bash
./gradlew :feature:feature-settings:testDebugUnitTest --tests "*SettingsGlossaryTest*"
```

### Pass 4 — Additions

#### Task 4.1 — App Drawer parity (mockup 2)
**Depends:** 2.7, 3.2. **Scope:** drawer rows from `appMenuItems(drawer context)`: Edit App Details,
Favorite (On/Off), Add to Card ›, Mark as Game (On/Off), App Info, Uninstall; values resolved before
the menu is published; new `AppDrawerScreen` callbacks `onEditAppDetails(pkg)` /
`onAddAppToCard(pkg, label)` / `onToggleAppFavorite(pkg, label)` wired in `XMBShell.kt:1138` to
`XMBViewModel`.
**Acceptance:** P1-10 (values), drawer rows test. **Budget:** 3 modified (+ `XMBViewModel.kt`), 1 test.
```bash
./gradlew :feature:feature-appbar:testDebugUnitTest --tests "*AppDrawerViewModelTest*" --tests "*AppMenuItemsTest*"
```

#### Task 4.2 — Category icon menu (mockup 5)
**Depends:** EXT-CI, 2.2. **Scope:** `CategoryMenuItems.kt`: Rename Category, Change Icon (value) ›,
Show on Bar (On/Off; hidden for Settings), Move, Manage Categories; `XMBShell.kt:948` long-press
opens it for any category (debug Settings hook kept as is); Rename / Change Icon deep-link into
`CategoryManagerScreen` (new initial-target parameter through `SettingsNavHost`), Move →
`startCategoryMove`, Show on Bar → `setVisible`.
**Acceptance:** P1-14, P1-15. **Budget:** 4 modified, 1 new source, 1 new test.
**If Blocked:** if Category Manager's VM cannot take an initial target without restructuring, stop
and report.
```bash
./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*CategoryMenuItemsTest*" --tests "*ContextMenuPredicateTest*"
```

#### Task 4.3 — Saved Backups menu
**Scope:** `BackupSettingsUiState.backups: List<BackupInfo>`; rows open a `SettingsItemMenu`:
Restore (`restoreFromUri(info.uri)`), Share (ACTION_SEND with read grant), Delete Backup (red,
confirm, `DocumentsContract.deleteDocument`, then refresh).
**Acceptance:** P0-11. **Budget:** 2 modified, 1 new test.
```bash
./gradlew :feature:feature-settings:testDebugUnitTest --tests "*BackupSettingsViewModelTest*"
```

#### Task 4.4 — Custom card game rows
**Scope:** row `onClick` = open the menu (and long-press/Triangle); menu: View Game Details, Remove
from Card (not red, no confirm); sublabel drops "tap to remove from card". View Game Details goes up
a new `onOpenGameDetail(gameId)` callback (`SettingsNavHost` → `XMBShell` → `activeGameId`).
**Acceptance:** P0-10. **Budget:** 3 modified, 1 test.
**If Blocked:** if Game Detail does not draw above Settings, stop and report the layer order.
```bash
./gradlew :feature:feature-settings:testDebugUnitTest --tests "*CollectionCardGameMenuTest*"
```

#### Task 4.5 — Library Manager app and extension rows
**Scope:** app rows: menu Remove from Library (red, confirm); extension rows: Remove Extension (not
red, no confirm); X opens the menu; trailing "Remove" text goes.
**Acceptance:** rows test. **Budget:** 2 modified, 1 test (`LibraryManagerViewModelTest.kt`).
```bash
./gradlew :feature:feature-settings:testDebugUnitTest --tests "*LibraryManager*"
```

#### Task 4.6 — Saved Themes: Rename Theme
**Scope:** `PfpThemeStore.rename(id, name)` rewrites the manifest name (`PfpThemeCodec`); menu rows
Share, Rename Theme (text entry), Delete Theme.
**Acceptance:** P1-16, `SavedThemeMenuTest` updated. **Budget:** 2 modified (+ theme VM), 2 tests.
```bash
./gradlew :core:core-data:testDebugUnitTest --tests "*PfpThemeStoreTest*"
./gradlew :feature:feature-settings:testDebugUnitTest --tests "*SavedThemeMenuTest*"
```

#### Task 4.7 — App Detail: one menu
**Scope:** Options = Favorite (On/Off), Add to Card ›, Artwork › (Change Icon, Change Background,
Reset All Artwork — confirm from 1.3), App Info, Hide; "Edit Title"; footer names the focused
button; Artwork square button opens the menu on its sub-panel.
**Acceptance:** P1-9 (sub-panel), `AppDetailOptionsTest`. **Budget:** 2 modified, 1 test.
**If Blocked:** App Info / Hide need `InstalledAppRepository` / hide APIs reachable from feature-xmb — report if not.
```bash
./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*AppDetail*"
```

#### Task 4.8 — Media additions
**Scope:** Now Playing row gains Visualizer (same action as the player's); music track gains View
Information (shell `InfoDialogState`).
**Acceptance:** `MediaContextMenuItemsTest` cases. **Budget:** 2 modified, 1 test.
```bash
./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*MediaContextMenuItemsTest*"
```

#### Task 4.9 — Shiba Coins Change Match opens the match picker — **BLOCKED (§12 Q1)**

#### Task 4.10 — Video playlists "Add Videos" — **BLOCKED (§12 Q5, needs a mockup)**

### Pass 5 — Dead code

#### Task 5.1 — Delete dead menus and fix stale comments
**Scope:** delete `appbar/storefront/StorefrontAppDrawer.kt`; delete `handleMusicFolderAction`, the
`menu.musicFolderId` branch and `XMBContextMenu.musicFolderId`; delete the duplicate
`OPEN_CONTEXT_MENU, CHANGE_SORT` branch (`XMBViewModel.kt:6617`); fix comments: `XMBViewModel.kt:203`
and `:882` ("Sync All Coins"), `:230` ("Add to Collection…"), any Choose Disc / Now Playing /
Coins-footer comment Pass 3 left.
**Acceptance:** module tests green; the removed `when` duplicate warning is gone; no new warnings.
**Budget:** 3 modified, 1 deleted.
```bash
./gradlew :feature:feature-xmb:testDebugUnitTest
./gradlew :feature:feature-appbar:testDebugUnitTest
```

#### Task 5.2 — README §4.5 matches the menus
**Scope:** `README.md:364–378` describes the current menus (it still lists "Launch Game, Edit Title,
Edit Note" for games). Docs only.

Every task: **Stop Condition** — stop when its acceptance criteria hold; do not continue into the
next task. **If Blocked** — stop and report what was tried, what blocked, which file, and what
decision is needed; do not invent architecture.

## 12. Open questions (need the user)

1. **Change Match (4.9).** Game Detail's picker (`StorefrontRematchPanel` + ~450 lines of state in
   `GameDetailViewModel.kt:2275–2740`) sets the *storefront* identity; Coins' Steam link is made at
   Auto-Match time from that id, so a new storefront match does not by itself relink coins. Options:
   (a) extract the rematch state machine into a shared holder both ViewModels host, then on a Steam
   pick unlink and re-run Auto-Match — L effort; (b) Change Match closes Coins and opens Game Detail
   on Store Match, user re-runs Auto-Match — S; (c) Change Match unlinks and opens Coins' own
   `StorefrontMatchPanel` seeded with a Steam search — M. Recommended: (c).
2. **Manage Custom Cards on the game row.** Mockup 1 does not show it and does not strike it. Plan
   keeps it in Library. Remove?
3. **All Games "This List" / "All Lists" headers.** They would split the Display group the card
   menus share. Plan leaves All Games unchanged. Proceed anyway?
4. **Game Detail button changes (3.5)** — "Favorite" with a filled/outline icon and the ⋮ opener are
   not in a mockup. Small; confirm or ask for a mockup.
5. **Add Videos (4.10)** needs a video picker; nothing like `MusicTrackPicker` exists for video.
   Mockup first, or drop.
6. **Video Detail Play rows (AD-5)** are removed for touch too, because the page buttons sit beside
   the menu. Confirm.

## 13. Audit claims that proved false or were dropped

- **Player Status "stays open after Update"** — false: `syncAll()` sets `options = null`
  (`PlayerStatusViewModel.kt:323`). Dropped.
- **"The Steam picker calls it Toggle All"** — no "Toggle All" string exists; only Game Picker's
  "Whole Shelf". The "Select All" rename is dropped.
- **App Drawer "No sounds"** — partly false: `openAppMenu` plays `SELECT`; moves and close are
  silent. Covered by 2.7 anyway.
- **"Video playlists have no Add Videos"** — true, but there is no picker to reuse; BLOCKED (Q5).
- Line numbers in the audit were approximate; every row in §2 carries the verified one.

## 14. Risks

- **`XMBViewModel.kt` churn.** Twelve tasks touch it. Keep each to its named functions; run the
  whole feature-xmb suite at 3.4 and 5.1.
- **Shell confirm ordering.** `pendingConfirm` must sit above the notification panel branch in the
  ladder and first in `shellModalSpec`, or Clear All's confirm loses presses to the panel.
- **Visual drift on migration.** `DetailContextMenu` draws without ripple; the PSP panel uses the
  default clickable. Check Game Detail / Video / Photo / App Detail on device after 2.6.
- **Async values before publish.** Favorite / Mark as Game rows must resolve before the menu shows
  (fixes the drawer flash) — a slow DB read delays the menu; keep it one read.
- **Settings Triangle.** Screens with their own Triangle interceptors (Themes, `MediaAssignmentRow`)
  run first and keep working; check MediaAssignmentRow's "Use Default" after 2.10.
- **EXT plans.** If either lands differently than §10 assumes, 3.10 / 4.2 stop and report.
- **Device checks.** Sounds, panel alpha over Game Detail art, drawer menu over the grid, category
  long-press on touch — one device pass after Pass 2 and after Pass 4.

## 15. Execution Task Index

| ID | Task | Depends On | Status |
| --- | --- | --- | --- |
| 1.1 | Shell confirm for the XMB; migrate existing confirms | EXT-IP, EXT-CI (gate) | DONE |
| 1.2 | Confirm every unconfirmed XMB delete | 1.1 | DONE |
| 1.3 | App Detail: confirm Reset All Artwork | gate | DONE |
| 1.4 | Artwork Studio: confirm Clear / Forget Match | gate | DONE |
| 1.5 | Shiba Coins: Unlink red, last, confirmed | gate | DONE |
| 1.6 | Themes: Delete Theme confirmed | gate | DONE |
| 1.7 | App Drawer: Uninstall through PfpConfirmModal | gate | DONE |
| 2.1 | PspMenuRow.opensMenu, panelAlpha, PspMenuNav | Pass 1 | DONE |
| 2.2 | XMB menus on PspMenuNav with sounds | 2.1 | DONE |
| 2.3 | Long-press is Triangle in the XMB | 2.2 | DONE |
| 2.4 | Game Detail on the panel; Triangle closes | 2.1 | DONE |
| 2.5 | Photo Viewer and App Detail on the panel | 2.1, 1.3 | DONE |
| 2.6 | Video Detail + playlist picker on the panel; delete DetailContextMenu | 2.4, 2.5 | DONE |
| 2.7 | App Drawer menu on the panel | 2.1, 1.7 | DONE |
| 2.8 | Video Player options with › sub-lists | 2.1 | DONE |
| 2.9 | Coins/Library/Player Status/Search Online: Back climbs | 2.1, 1.5 | DONE |
| 2.10 | Settings: Triangle = long-press; Themes on the rules | 2.1, 1.6 | DONE |
| 3.1 | XMB game row: mockup 1 | 2.2, 1.2 | DONE |
| 3.2 | XMB app row: shared builder, wording, parity | 2.3, 1.2 | DONE |
| 3.3 | Container rows | 2.3 | DONE |
| 3.4 | XMB media rows | 2.3, 1.2 | DONE |
| 3.5 | Game Detail wording and hidden rows | 2.4 | DONE |
| 3.6 | Video Detail (mockup 3) and Photo rows | 2.6, 2.5 | DONE |
| 3.7 | Shiba Coins (mockup 4) wording | 2.9 | DONE |
| 3.8 | Library / Player Status / Search Online values | 2.9 | DONE |
| 3.9 | Artwork Studio wording; Triangle closes | 1.4, 2.1 | DONE |
| 3.10 | XMB list-level menus | 2.2, 1.2, EXT-IP | DONE |
| 3.11 | Settings wording | gate | DONE |
| 4.1 | App Drawer parity (mockup 2) | 2.7, 3.2 | DONE |
| 4.2 | Category icon menu (mockup 5) | 2.3, EXT-CI | DONE |
| 4.3 | Saved Backups menu | 2.10 | DONE |
| 4.4 | Custom card game rows menu | 2.10 | DONE |
| 4.5 | Library Manager row menus | 2.10 | DONE |
| 4.6 | Saved Themes: Rename Theme | 2.10 | DONE |
| 4.7 | App Detail: one menu | 2.5, 1.3 | DONE |
| 4.8 | Media additions | 3.4 | DONE |
| 4.9 | Shiba Coins Change Match → match picker | 3.7, §12 Q1 | DONE |
| 4.10 | Video playlists Add Videos | §12 Q5 (mockup) | DEFERRED |
| 5.1 | Delete dead menus; fix stale comments | Pass 3 | DONE |
| 5.2 | README §4.5 matches the menus | Pass 4 | DONE |

## Resolved Decisions (2026-10-01, approved direction)
- Q1 Change Match (4.9): option (c) — Unlink, then open Coins' own match panel with a Steam search. 4.9 is UNBLOCKED.
- Q2: keep "Manage Custom Cards" on the game row, in the Manage group.
- Q3: leave All Games unchanged (no This List / All Lists headers).
- Q4: confirmed — on-screen Favorite keeps one label and shows state by icon; the touch opener becomes ⋮.
- Q5 Add Videos (4.10): DEFERRED — needs a new picker, which needs a mockup first. Mark 4.10 DEFERRED and skip it.
- Q6: confirmed — Video Detail's Play/Resume/Start rows are removed for touch too.
- Device checks inside the plan are deferred to the user's end-of-run device pass; they do not block DONE.
