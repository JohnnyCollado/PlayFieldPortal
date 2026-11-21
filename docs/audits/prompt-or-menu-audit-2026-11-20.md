# Prompt-or-menu audit (F5), 20 Nov 2026

Rule (xmb-feel-plan-2026-11-20.md section 1.2): a menu answers "what do you want to do"; a prompt
interrupts to confirm, inform or block. Picking a value belongs in the right-hand list. A menu row
that deletes, removes or resets must raise a prompt first.

Report only: no code was changed for this audit. Paths are relative to the repo root; `ui/` means
`feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/`, `vm/` means
`.../feature/xmb/viewmodel/`, `set/` means
`feature/feature-settings/src/main/kotlin/com/playfieldportal/feature/settings/ui/`.

Note on the "14 uses": a search finds 8 production `PfpModalSpec.Choice(` constructions (table A).
The other matches are the modal host (`PfpModalHost.kt`, 4 references) and tests (`PfpPromptBandTest`,
`CustomCardTextTest`, `KitChoiceSpecTest`, `PlayerPickerSpecTest`, `ShellModalSpecTest`), which build or
read the same specs, so the figure of 14 does not reproduce as production sites.

## A. `PfpModalSpec.Choice` (prompt with a list), 8 production sites

| # | Where | What it asks | Verdict | Evidence |
|---|---|---|---|---|
| A1 | `set/DisplaySettingsScreen.kt:208` | Pick-one setting (title, summary, list of values, Select stores the pick) | should be a right-hand list (it only picks a value) | `onConfirm = { viewModel.choose(openOption, index) }`; no confirm, no inform, no destructive step. Comment in the file calls it "a pick-one setting" |
| A2 | `set/DisplaySettingsScreen.kt:192` | Low-contrast font colour warning; options "OK" / "Don't warn again" | fine | Informs of a problem; the two options are acknowledgements, not a value pick |
| A3 | `set/CollectionsSettingsScreen.kt:431` (used at :127) | Which category a new card goes into (list of categories, then Create) | should be a right-hand list (it only picks a value) | `onConfirm` calls `onPick(category.id)` which creates the card at once; the only decision is the category value. Create is the single follow-through, not a confirm of something destructive |
| A4 | `set/PlayerPickerSpec.kt:26` | Which player app to use for a media type | should be a right-hand list (it only picks a value) | `onConfirm = onChoose(option.value)`: stores the choice, nothing destructive, nothing to inform |
| A5 | `set/ThemesSettingsScreen.kt:205` | Apply this theme, plus which of the user's own media/icons/boot sequence it may replace | fine | Confirms an apply that overwrites user data and offers a short list of extra scopes; the no-choice case uses `PfpModalSpec.Confirm` (:219) |
| A6 | `set/WindowsGameListScreen.kt:220` | Folder has no achievement list: link only, or write the emulator kit and link | fine | Two ways to proceed (matches `page_select_setting_dialog` in section 1.2) |
| A7 | `ui/XMBShell.kt:1877` | Icon editor source: applied theme or device image | fine | Two ways to proceed, Continue then branches |
| A8 | `ui/XMBShell.kt:1960` | Failed launch: Retry / Change emulator / Change default / Copy diagnostics / Dismiss | fine | Reports an error with repair choices (3 to 4). Reads as an inform-and-act prompt, within "a few choices" |

Counts for A: fine 5, should be a right-hand list 3, needs a confirm prompt 0.

## B. Rows in the shared options panel (context menus and Options menus)

Grouped by builder. Where a whole group has one verdict it is one line; rows that are not "fine" or that
carry a destructive id are listed on their own. "Confirm" means the row's dispatch ends in
`XmbConfirm` / a `PfpModalSpec.Confirm` before anything is removed.

| # | file:line | What the row asks or does | Verdict | Evidence |
|---|---|---|---|---|
| B1 | `vm/GameContextMenuItems.kt:51-84, 91-100, 111-134` | Game menu actions: details, coins, change emulator, favorite, add to card, arrange, icon display, fetch artwork, PC rows, manage, hide here | fine | Each runs an action or opens a sub-list (`opensMenu`); `vm/XMBViewModel.kt:7851-7940` |
| B2 | `vm/GameContextMenuItems.kt:83` `remove_from_collection` | Remove game from this card | fine | Acts at once (`XMBViewModel.kt:7869`), but fully reversible by Add to Card; the settings twin documents the same call (`set/CollectionsSettingsScreen.kt:283`) |
| B3 | `vm/GameContextMenuItems.kt:104` `remove_category` | Remove game from its category | fine | Raises `XmbConfirm.RemoveFromCategory` only when the game also sits on custom cards (`XMBViewModel.kt:7881-7899`); otherwise reversible |
| B4 | `vm/GameContextMenuItems.kt:133` `hide_here` | Hide this game from this place | fine | Immediate, recoverable in Hidden Items (`XMBViewModel.kt:7906`) |
| B5 | `vm/GameContextMenuItems.kt:142, 149, 152, 156` `remove_missing`, `remove_app_row`, `remove_app`, `remove_game` | Delete the library entry (red rows) | fine | `confirmFor` (`vm/XmbConfirm.kt:182-185`) returns a Confirm for all four ids |
| B6 | `vm/CategoryMenuItems.kt:37-49` | Rename, change icon, Hide category, Move, New card, Manage | fine | `XMBViewModel.kt:7003-7030`: rename/icon/manage open Category Manager; hide is immediate but recoverable there |
| B7 | `vm/MemoryCardContextMenuItems.kt:39-64, 85-101` | Scan, update metadata, fetch artwork, match achievements, icon display, sort, pin, move, library manager, scan all | fine | Actions or sub-lists, nothing deleted |
| B8 | `vm/MemoryCardContextMenuItems.kt:65` `hide` | Hide the card | fine | `XMBViewModel.kt:7816` `hideCard` sets enabled=false at once; reversible in Library settings |
| B9 | `vm/MemoryCardContextMenuItems.kt:67` `remove` | Remove the console card (red) | fine | `confirmFor` -> `XmbConfirm.RemoveCard` (`XmbConfirm.kt:179-180`) |
| B10 | `vm/MemoryCardContextMenuItems.kt:92` `relink_artwork` | Re-link artwork for every game | fine | Batch background job (`XMBViewModel.kt:7793`); relinks, does not delete |
| B11 | `vm/XmbLists.kt:497-527` custom card menu (open, add games, sort, pin, move, rename, move to category, manage) | Card actions | fine | Actions or sub-lists |
| B12 | `vm/XmbLists.kt:527` `delete_collection` | Delete a custom card (red) | fine | `confirmFor` -> `XmbConfirm.DeleteCard` (`XmbConfirm.kt:175-176`) |
| B13 | `vm/XmbLists.kt:482-486` `insert_umd` / `eject_umd`, `:446-448` pin/move | UMD slot and arrangement | fine | Actions; eject only clears a slot |
| B14 | `vm/MediaContextMenuItems.kt:24-29, 35-37, 46-49, 55-56, 62-64, 72-74, 80-91, 114-123` | Play, add to playlist, info, rename, open, scan, set wallpaper/lock screen, music_close | fine | Actions or sub-lists. Set wallpaper/lock screen replaces an image but is not data loss |
| B15 | `vm/MediaContextMenuItems.kt:28` `remove_from_playlist`, `:49` `video_remove_playlist` | Take a track or video out of the open playlist | fine | Acts at once (`XMBViewModel.kt:5072`, `:3801`); reversible by re-adding, the file is untouched |
| B16 | `vm/MediaContextMenuItems.kt:30, 38, 50, 57, 75` `remove_track`, `delete_playlist`, `video_remove`, `delete_video_playlist`, `photo_remove` | Remove from library / delete playlist (red) | fine | All five map to a Confirm in `confirmFor` (`XmbConfirm.kt:165-178`) |
| B17 | `vm/ListMenuItems.kt:20` `mark_all_read` | Mark every notification read | fine | Non-destructive |
| B18 | `vm/ListMenuItems.kt:21` `clear_read` | Delete every read notification from history | needs a confirm prompt (a menu row that deletes/removes/resets immediately) | `handleNotificationMenuItem` (`XMBViewModel.kt:9209-9222`): `confirmFor` returns a Confirm only for `CLEAR_ALL`; `CLEAR_READ` calls `notificationRepository.clearRead()` straight away. The sibling Clear All (B19) confirms, so the pair is inconsistent. Lowest severity: notifications are transient |
| B19 | `vm/ListMenuItems.kt:22` `clear_all` | Clear all notifications (red) | fine | `XmbConfirm.ClearNotifications` (`XmbConfirm.kt:171-172`) |
| B20 | `vm/ListMenuItems.kt:44-51`, `vm/PlaylistListItems.kt:69`, `vm/XmbLists.kt:319-338` | Resume, sort mode lists, playlist import | fine | Actions or sort lists with a checkmark; the sort lists are already the right-hand-list pattern |
| B21 | `vm/XMBViewModel.kt:1363-1374` (Games filter panel) | Search, sort, clear filter | fine | Actions/sub-lists |
| B22 | `vm/XMBViewModel.kt:3814-3815, 4837-4839` playlist pickers, `:7164, 7182, 8025` icon display pickers, `:8042-8043` emulator picker, `:7214` launch-with, `:7527, 7541, 8603` category pickers, `:8061-8091` | Second-level pick-a-value lists opened from a parent row | fine | These already are the right-hand list (the model's own pattern); pick applies and closes |
| B23 | `vm/XMBViewModel.kt:7211-7223` achievements hub menu (`ach_auto_match`, `ach_sync_all`) | Run Auto-Match / Update installed | fine | Start background jobs |
| B24 | `vm/XMBViewModel.kt:10074` `social_reconnect` | Reconnect Discord | fine | Action |
| B25 | `feature-appbar/.../AppMenuItems.kt:68-93` (also used at `vm/XMBViewModel.kt:7455`) | App menu: launch, edit, mark/unmark game, favorite, add to card, pin, move, category rows, rename, app info | fine | Actions or sub-lists |
| B26 | `AppMenuItems.kt:82` `remove` ("Remove from category") | Take the app out of its category | fine | Acts at once (`XMBViewModel.kt:7990`) but reversible by Add to Category; same call as B3 |
| B27 | `AppMenuItems.kt:90-91` `hide_from_category`, `hide_everywhere` | Hide the app | fine | Immediate, recoverable in Hidden Items (`XMBViewModel.kt:7993-7997`) |
| B28 | `AppMenuItems.kt:93` `uninstall` (red) | Uninstall the app | fine | `XmbConfirm.Uninstall` (`XmbConfirm.kt:164`) |
| B29 | `ui/app/AppDetailViewModel.kt:58, 84` `RESET_ARTWORK` (red) | Reset the app's artwork | fine | `confirmReset = true` (`AppDetailViewModel.kt:481`) raises a prompt first |
| B30 | `ui/detail/GameDetailScreen.kt:1180` (Options menu), `DetailAction.REMOVE` red | Remove game from library | fine | `requestRemove()` sets `confirmRemove`, shown as `PfpModalSpec.Confirm` (`GameDetailScreen.kt:391`) |
| B31 | `ui/detail/GameDetailScreen.kt:1180` other rows | Favorite, emulator, storefront, fetch artwork, add to card | fine | Actions or sub-lists |
| B32 | `ui/photo/PhotoViewerViewModel.kt:122` `REMOVE` (red) | Remove photo from library | fine | `confirmRemove = true` (`PhotoViewerViewModel.kt:252`) |
| B33 | `ui/detail/VideoDetailScreen.kt:327` `REMOVE` (red) | Remove video from library | fine | `confirmRemove = true` (`VideoDetailViewModel.kt:256`) |
| B34 | `ui/detail/VideoDetailScreen.kt:345-346` playlist picker | Add to / remove from playlists, create playlist | fine | Toggle list; reversible |
| B35 | `ui/detail/ShibaCoinsScreen.kt:290`, `vm: ui/detail/ShibaCoinsViewModel.kt:344` `Unlink` (red) | Unlink the account | fine | `requestUnlink()` sets `unlinkConfirm` (`ShibaCoinsViewModel.kt:1334`) |
| B36 | `ui/detail/PlayerStatusScreen.kt:148`, `ShibaLibraryScreen.kt:247`, `SearchOnlineScreen.kt:242` | Sort, provider filter, console, refresh, recover | fine | Sub-lists with checkmarks (already the right-hand-list pattern) and actions |
| B37 | `ui/detail/ArtworkStudioScreen.kt:1434`, `StudioAction.CLEAR` (red) | Clear the slot's artwork | fine | `requestClear()` -> `destructiveConfirm` (`ArtworkStudioViewModel.kt:2768, 3195`) |
| B38 | `ui/detail/ArtworkStudioScreen.kt:1434`, `StudioAction.RESET_DEFAULT` | Replace the slot's art with the re-downloaded scraped default | needs a confirm prompt (a menu row that deletes/removes/resets immediately) | `ArtworkStudioViewModel.kt:2767` calls `resetToScrapedDefault()` directly (:2794), no confirm. Mitigation: Restore Previous (`:2766`) can undo it, and the row is not drawn red. Borderline: judge it as a call for the owner |
| B39 | `ui/detail/ArtworkStudioScreen.kt:1434`, `REMOVE_FAILED`, `RESTORE_PREVIOUS`, other studio actions | Remove failed queue items; restore; crop; file info; match | fine | `removeFailed()` (:2365) only drops failed queue entries; the rest are actions |
| B40 | `ui/detail/ArtworkStudioScreen.kt:1420-1429` filter rows | Filter lists | fine | Pick-a-value lists in the right-hand panel |
| B41 | `ui/GamePickerLogic.kt:322-332` | Game Picker menu: View, Select All, view modes | fine | Not edited (Game Picker is out of bounds); actions/sub-list |
| B42 | `video/VideoPlayerOptions.kt:65-75` | Speed, subtitles, audio, screen mode, each with a value list | fine | Already right-hand lists with a checkmark |
| B43 | `set/BackupSettingsScreen.kt:204` `restore` | Restore this backup | fine | Sets `pendingRestore` and shows a Confirm (`BackupSettingsScreen.kt:68-73`) |
| B44 | `set/BackupSettingsScreen.kt:205` `share` | Share backup | fine | Action |
| B45 | `set/BackupSettingsScreen.kt:206` `delete` (red) | Delete backup | fine | `pendingDelete` -> Confirm (`BackupSettingsScreen.kt:56-63`) |
| B46 | `set/ThemesSettingsScreen.kt:501-504` | Share, rename, update file, delete (red) | fine | Delete goes through `deleteThemeConfirmSpec` (`ThemesSettingsScreen.kt:190`) |
| B47 | `set/CollectionsSettingsScreen.kt:291-292` | View details; Remove from Card | fine | Documented as reversible and unconfirmed (`CollectionsSettingsScreen.kt:283`) |
| B48 | `set/LibraryManagerScreen.kt:816-817` (red) | Remove an Android app row | fine | Confirm via `appToRemove` (`LibraryManagerScreen.kt:569`) |
| B49 | `set/LibraryManagerScreen.kt:827` | Remove a file extension from a console | fine | Documented as reversible and unconfirmed |
| B50 | `set/LibraryManagerScreen.kt:682` | Set emulator for a card (list of emulators) | fine | Is already a right-hand list; pick applies |
| B51 | `set/WindowsGameListScreen.kt:171` | Label a Windows game (Steam / Non-Steam / Local) | fine | Is already a right-hand list |
| B52 | `ui/detail/ArtworkStudioScreen.kt:1445-1456` apply prompt and replace prompt | Apply queued artwork changes / replace the stored previous version, drawn as option-panel rows | other: a prompt drawn as a menu (the inverse of the rule) | Built from `StudioConfirmPrompt` (`ArtworkStudioViewModel.kt:559-583`): title plus Apply/Cancel-style rows, red row when it deletes. Behaviour is a confirm; only the chrome is the options panel |
| B53 | `ui/detail/ArtworkStudioScreen.kt:1461-1470` leave prompt | "N changes waiting": Stay / Apply / Discard | other: a prompt drawn as a menu (the inverse of the rule) | `StudioLeaveChoice` rows, Discard drawn red; it interrupts on Back, so it is a prompt in an options-panel frame |

## Counts

Table A (`PfpModalSpec.Choice`, 8 sites):
- fine: 5 (A2, A5, A6, A7, A8)
- should be a right-hand list (it only picks a value): 3 (A1, A3, A4)
- needs a confirm prompt: 0

Table B (options-panel row groups, 53 lines; a line may cover several rows of one builder):
- fine: 49
- should be a right-hand list: 0 (every value-picking row in the panel already is one)
- needs a confirm prompt: 2 (B18 `clear_read`, B38 `RESET_DEFAULT`, the latter borderline)
- other (prompt drawn as options-panel rows, the inverse case): 2 (B52, B53)

Combined: fine 54, right-hand list 3, needs a confirm 2, other 2.

## Notes for the plan

- Every red (`isDestructive`) row in the panels other than the two above already raises a confirm.
  `confirmFor` in `vm/XmbConfirm.kt` is the single table for the XMB; the Settings, Detail and Studio
  screens each carry their own confirm state.
- The rows left unconfirmed on purpose (B2, B15, B26, B47, B49) are reversible and the owner documented
  that in code comments; the audit marks them fine on that basis.
- Not audited: rows of the Settings list screens themselves (`SettingsRow` toggles and navigation), which
  are not the shared options panel.
