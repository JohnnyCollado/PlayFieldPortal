# Changelog

All notable changes to Play Field Portal are documented here. This project follows
[Keep a Changelog](https://keepachangelog.com/) and [Semantic Versioning](https://semver.org/).

## [Unreleased]

Touch-first navigation and a consistent, theme-matched UI across every full-screen menu, plus a
move to permission-free storage: ROM libraries, media and backups now all go through the Storage
Access Framework, so the app no longer needs all-files access.
(Still `versionName 1.0.0-alpha.3` / `versionCode 3` — not yet cut as a release.)

### Added
- **Import PC Games.** New section under Library, and an option on the **All Games** card's Options
  menu. Detects installed PC launchers (BannerHub, GameHub Lite, GameNative, Winlator); **Add game
  by ID** builds each launcher's documented launch intent — with a **Test Launch** to verify — so
  the game launches straight back into the launcher (PFP is a frontend, never the PC runtime).
  Imported games land in a collection named after the launcher with the **Desktop PC** icon. Optional
  **Home mode**: set PFP as your Home app to auto-import every game a launcher publishes.
- **ES-DE ROM roots with one-scan autoload.** Grant a ROM root folder (internal storage **or** an
  SD card — multiple roots supported) and **Auto-Detect from ROM Root** walks its ES-DE system
  folders (`gba`, `snes`, `psx`…), creates a Memory Card for every folder that actually contains
  games, and loads them in a single scan. Empty folders are skipped; a system split across roots is
  merged into one console. ROMs load via `content://` URIs, so no storage permission is needed.
- **Set Up ROM Folders (ES-DE).** Pick an empty folder and PFP creates the standard ES-DE
  system-folder structure for you (no guessing folder names), so libraries transfer cleanly to and
  from a real ES-DE install.
- **Root Access** in the Library section. Lists granted ROM root folders as **Linked** or **Access
  lost**, with one-tap re-link (the picker opens pre-pointed at the saved folder). Recovers folder
  access after a restore or reinstall; re-linking a ROM root restores every console under it at once.
- **Single root folder for Music, Photos and Videos.** Each media section is driven by one folder,
  set in **Settings ▸ Music / Photo / Video**: *Root Folder* (shown), *Add / Replace Root Folder*
  (one SAF grant; replacing overwrites it — the picker opens pre-pointed at the saved folder so
  re-granting after a restore is one tap), and *Rescan* (fast incremental — new files in, deleted
  files out). Photo settings also has *Clear Thumbnail Cache*; Music/Video have a **Default Player**
  choice (Play Field Portal / System Default / a chosen app). Libraries update automatically after a
  scan. The XMB media sections show a single "＋ Add" getting-started row that opens the matching
  Settings section and disappears once a root has been added and scanned (even if it finds nothing).
- **Backups saved to a folder you choose.** *Back Up Now* writes the `.pfpbackup` into a
  SAF-granted folder — no storage permission, survives an uninstall, and stays user-accessible.
- **Full XMB touch navigation.** Swipe to step categories/items (discrete, D-pad-equivalent
  stepping with a small fling bonus), tap-to-point / tap-again-to-open, and a left-edge
  swipe for Back. A bottom-right contextual **App Drawer** button appears while using touch.
- **Touch UI that follows the last input source.** The header pills on the Game/App/Video/
  Photo detail screens and the Music browser (Back / Options / Sort) show only when the last
  input was touch and hide when a controller is used — the same behaviour as the XMB's
  contextual App Drawer button. Controllers keep their on-screen A/X/Y/B hints.
- **App collections for non-gaming categories** (Network, App Store, custom): create one from
  an app's Options, drill in, and move / rename / pin / delete — Android apps only.
- **Android Settings** entry at the top of the Settings category, opening the device settings.
- **Touch Sensitivity** setting (Low / Normal / High) in Settings ▸ Display, scaling swipe
  step distance.
- **Auto-fading photo title.** The photo viewer shows a centred title on each image, then
  fades it out after a short delay.
- **Touch prev/next in the photo viewer.** Left/right pill buttons (the touch counterparts of
  L1/R1, styled to match the Back / Options pills) page through photos; they dim at the first/last
  image and hide with the rest of the controls when you tap the photo.
- **Tablet display scaling.** On screens larger than a handheld, the whole UI (XMB cross, Settings,
  detail screens, drawer and dialogs) magnifies uniformly instead of leaving tiny, mis-aligned
  elements floating on a big canvas. One canvas-scale factor keyed to the screen size preserves the
  PSP proportions and alignment; the handheld is untouched (scale = 1) and very large screens are
  capped so nothing balloons.
- **Status-bar sort chip.** On touch, the XMB status bar's sort label becomes a tappable chip
  that cycles the sort order; on controller it stays a plain label (X / Square cycles it).
- Icons on all detail-screen action buttons; plus (＋) glyph on "Add Apps / Add Games" rows.

### Changed
- **Detail screens reskinned to match the Music browser** — Game/App/Video/Photo now use the
  translucent theme-gradient backdrop (the XMB wave shows through) with header pills, and the
  XMB foreground is hidden behind them.
- **One shared themed context menu** for the Game/App/Video/Photo option popups, matching every
  other context menu (right-edge, wave-colour panel, accent cursor). App Detail now mirrors
  Game Detail: a single **Launch** button plus an Options menu holding the rest.
- **Y / Triangle** opens the Options menu on the Game and App detail pages.
- App-artwork files are **versioned**, so an icon/hero/background change is reflected
  immediately; closing App/Game detail refreshes the list so a new background shows on the XMB
  at once.
- **App Drawer:** white labels with a theme-accent filter highlight; a touch-aware grid cursor
  that hides during touch scrolling and resumes near the last touch position on the d-pad.
- Android Settings uses the wrench icon, matching the other Settings rows.
- **ROM libraries and backups now use the Storage Access Framework** end-to-end (`content://`),
  so they need no storage permission and work on SD/USB volumes. Older single-root grants and
  backups migrate transparently; restoring a backup re-links folders via Library ▸ Root Access.
- **Media scans skip folders they shouldn't index** — any directory with a `.nomedia` file, hidden
  `.`-prefixed folders, and the app's own thumbnail cache are pruned before descending. This keeps
  gallery/thumbnail caches and hidden data out of the library and speeds up rescans. Photo
  thumbnails now live in the app's external cache (`…/files/cache/thumbnails`, with a `.nomedia`)
  and *Clear Thumbnail Cache* empties it while preserving the marker.

### Removed
- **All-files access.** `MANAGE_EXTERNAL_STORAGE` is no longer declared, and the *Grant All-Files
  Access* option is removed from the Library Manager — ROMs, media and backups all use SAF.
- **Per-folder media library management** and the standalone Folder Access screen — Music, Photos
  and Videos are now managed as a single root folder each in their own Settings section (existing
  grants and roots carry over on upgrade).
- The dedicated **Photo Apps** section (the Photo category no longer lists installed photo apps).
- The floating **Back** button that replaced the App Drawer button while drilled in — going back
  is now the left-edge swipe or tapping the active memory-card icon under the caticon.

### Fixed
- **No more clipped half-row at the bottom of the XMB.** The home item column now renders only
  rows that fully fit below the active item, so a partial "peek" row is never cut off at the screen
  edge — on the handheld and (via the uniform canvas scale) on tablets alike.
- **Controller cursor no longer dies after opening a collection** in Settings ▸ Collections. Each
  step (list / detail) now owns its focus scaffold, so drilling into a collection re-assigns D-pad
  focus instead of leaving the cursor stranded (touch was unaffected).
- **Seamless full-screen-menu back-out.** The category bar no longer animates back into place
  when the XMB reappears after closing the Music browser / app drawer / Settings (the visible
  "snap"); the selected slot is now seated instantly.
- Non-gaming apps show their assigned background on the XMB, and artwork changes reflect
  without a restart.
- Tapping a caticon while drilled into a sub-item is a no-op; the contextual App Drawer button
  returns after tapping caticons.
- **Launch crash** on the current Compose BOM — `collectAsStateWithLifecycle` now reads the
  platform `LocalLifecycleOwner` (the `androidx.lifecycle.compose` variant isn't present and
  crashed at composition).
- Repaired 8 pre-existing unit tests that used improper Android mocks (stick `MotionEvent`
  action, launch `Intent`).

## [1.0.0-alpha.3] — 2026-07-02

Third alpha. Adds a full **Photo** section and **Video** section polish, moves ROM libraries
onto the Storage Access Framework so SD-card storage no longer needs the all-files
permission, makes the status bar live, and lands another round of security hardening.
(`versionName 1.0.0-alpha.3` / `versionCode 3`.)

### Added
- **Photo section.** A PSP-style memory-card experience: **All Photos**, **Camera** (only
  when a camera app exists), **Add Photo Library**, and a single **Albums** entry that drills
  into your scanned folders. Add albums via the folder picker (SAF — no storage permission),
  browse `[thumbnail] name / resolution · date` rows, and open photos in a minimal
  full-screen viewer (hidden controls, zoom/pan, rotate, L1/R1 paging).
- **Set as Launcher Wallpaper.** From the photo viewer's options *or* a photo row's context
  menu — preview first, then apply. The image is EXIF-stripped and copied into app storage;
  GPS/location metadata is never read or stored.
- **SAF ROM libraries (Memory Cards).** Add a console's ROM folder through the folder picker
  and it scans/launches with **no storage permission** — including folders on SD cards and
  USB drives. `MANAGE_EXTERNAL_STORAGE` is now optional (only requested for older raw-path
  libraries).
- **Live status bar.** Wi-Fi and cellular now show real signal strength and hide entirely
  when there's no connection/service; Bluetooth shows only when enabled; a controller icon
  appears when a gamepad is connected. No new permissions.
- **Video "Collections."** Recently Watched, Favorites, and Playlists are grouped under a
  single **Collections** entry, trimming the Video root.
- **Level-aware drill flyout.** The two-pane flyout's left column now shows the *current
  level's* siblings — a library among your libraries, an album among your albums, a playlist
  among your playlists — matching how Games shows the console cross.
- **App drawer: single-press launch.** A single tap now launches an app (long-press opens
  its menu), matching the controller's single-select-to-launch.
- **Video detail: resume-aware actions.** A watched video leads with **Resume** plus **Start
  from Beginning**; unwatched videos show **Play**. The action list scrolls.

### Changed
- **Faster library scans.** Photo/Video/Music scanners now list each folder with one
  DocumentsContract query instead of several IPC round-trips per file; photo deep-scans also
  probe files with bounded parallelism. Recursive scanning is on by default with a per-album
  *Include Subfolders* toggle.
- **Theme-adaptive menu cursor.** Every menu's focus highlight (Settings, context menus,
  pickers, detail/player option lists, app drawer) now derives from the active color scheme
  with a bright edge, so the cursor stays clearly visible on any theme.
- **"Add …" rows** across Photo/Music/Video sit at the bottom of their section with a **＋**
  glyph, and cursor position is remembered per view across every category (and future custom
  categories).
- Built-in theme renamed **Classic PSP Blue → Classic Blue** (trademark hygiene; the theme
  id and look are unchanged).
- State collection is lifecycle-aware, so the XMB stops recomposing while backgrounded behind
  a game or emulator.

### Fixed
- App drawer no longer lets a tap fall through to the XMB behind it (which could open the
  Music library "for no reason"); the XMB isn't composed while the drawer is up.
- Removing a photo/album/video/video-library now also deletes its cached thumbnails, so
  removed content is fully forgotten.
- Reopening a video no longer needs two taps; the two-pane flyout and back navigation are
  consistent across all drill levels.

### Security
- **Theme loader hardened (Zip Slip / zip-bomb).** `.xmbtheme` extraction now rejects any
  entry that escapes the theme directory, sanitizes the theme id, and caps per-entry, total,
  and entry-count sizes — a malicious pack can no longer overwrite app files or exhaust
  storage. Covered by regression tests.
- **Privacy-first Photo/ROM SAF model.** Only user-picked folders are read (tree-scoped
  content URIs, no `MANAGE_EXTERNAL_STORAGE` for new libraries), nothing is uploaded, and
  scanners are hardened against cyclic/duplicate provider entries.
- Redundant per-file `DocumentFile` permission lookups replaced with tree-scoped
  `DocumentsContract` queries, keeping every scan within the granted folder.

## [1.0.0-alpha.2] — 2026-07-01

Second alpha. Adds a full Music section, a PSP-authentic XMB redesign (cross layout,
two-pane drill flyout, and the real "wave" background), menu sound effects, and a round of
security hardening. (`versionName 1.0.0-alpha.2` / `versionCode 2`.)

### Added
- **Music library & player.** Scan SAF music folders, browse **All Music** as
  `[cover] [title] {artist}` rows, and play in a full-screen player (play/pause, seek,
  previous/next). Album art is extracted from tags and cached on device.
- **Background playback.** "Play in Background" keeps music going via a foreground media
  service with a media-style notification (play/pause, prev, next, stop) and MediaSession,
  so you can leave PFP and keep listening.
- **Playlists.** Create, rename, and delete playlists; add/remove tracks from a song's
  options menu or an in-playlist "Add Tracks" picker.
- **Music section redesign.** Root shows **Now Playing** (only while a track is loaded),
  **Playlist**, **Music Apps** (your picked installed music apps), and a single **Music**
  memory-card item collecting all scanned tracks. A fullscreen, searchable browser backs
  Music and Playlists.
- **Two-pane drill flyout (all drill-ins).** Drilling into a Games sub-item (a platform,
  All Games, Favorites, a collection) **or** a Music section shows the PSP two-pane flyout:
  the memory-card "cross" on the left with a `◀` on the active card, and the drilled
  content pinned to that line on the right — previous card above, next below, uniform
  spacing. Game rows are labeled `[Title]` / `{Platform (Emulator)}`.
- **Authentic PSP wave background.** The XMB background is now the real PSP "wave" — soft,
  slow light folds over a theme-tinted gradient (AGSL shader on Android 13+, with a Canvas
  fallback). Colors follow the active color scheme.
- **Menu sound effects.** The XMB/ES-DE sound set (scroll, select, back, launch, favorite,
  category change) with a **Menu Sounds** toggle in *Settings → Display → Sound*.
- **Sort cycling (X / □).** Cycle sort per list — games (Title / Recently played / Date)
  and music (Title / Artist / Album / Date); the current mode shows in the status strip.
- **Settings item icons.** Every Settings row now shows a wrench badge, matching the rest
  of the XMB.

### Changed
- **PSP-authentic "Original" theme.** True XMB cross layout (the selected row seats just
  under the caticon, the previous row half-clips above the crossbar, icons locked on the
  caticon's vertical line); a single theme hue across the whole XMB; selection conveyed by
  scale + alpha with crisp white labels.
- **Reference-accurate blue gradient.** The default gradient anchors were sampled from the
  real XMB wave — saturated azure top easing to a brighter cyan-blue near the wave — and
  every scheme's gradient now stays a rich, saturated color instead of fading to navy/white.
- **Collections** render the physical-media memory-card art instead of the blank default
  icon, so they read as memory cards.
- **Android library** reworked to be app-picker driven, isolated from ROM libraries, and
  removable ("Remove from Library"); music-folder management moved to *Settings → Music*.

### Fixed
- Seamless XMB wave loop (no skip on repeat).
- Replacing a wallpaper now actually updates the image (unique filename defeats the
  path-keyed image cache).
- **Reset Hidden Apps** action added (hiding an app previously had no in-app way back).
- Settings background scrim lightened so the wallpaper/wave shows through.
- Tightened XMB item tap targets so stray touches in empty row areas no longer select/launch.
- Added breathing room between wide artwork tiles and their titles.
- Item list hard-stops on a row boundary — no partially-clipped rows at the top or bottom.
- Alpha release build fixes (signing, R8/manifest, app-drawer crash).

### Security
- **Captured-shortcut hardening.** Externally-supplied `INSTALL_SHORTCUT` intents are
  sanitized (all URI-permission grant flags and ClipData stripped, target pinned to a
  resolved installed component) at capture **and** at launch, closing a confused-deputy /
  arbitrary-file-read vector. Captured shortcuts now require an explicit **Add** confirmation
  (handled by a non-exported receiver the sender can't forge); silent library poisoning is
  prevented.
- **Network policy.** Cleartext (HTTP) denied app-wide; release builds trust only the system
  CA store (user-installed-CA MITM blocked); debug builds still trust user CAs for proxying.
- **Secrets encrypted at rest.** Artwork/metadata API keys are encrypted with an
  AndroidKeystore-backed AES-256/GCM key.
- **Backups disabled.** `allowBackup=false` plus data-extraction rules keep the library DB
  and API keys off cloud backup and device transfer.
- **Least privilege.** Removed dead exported receivers; All-Files access is requested only
  point-of-need (a prompt in *Settings → Library*), so SAF-only and app-picker users never
  grant it.

## [1.0.0-alpha.1] — 2026-06-28

- Initial alpha: XMB launcher shell, ROM library scanning, artwork scraping, emulator launch,
  gaming categories/collections, controller mapping, and touch controls.

[1.0.0-alpha.2]: https://github.com/JohnnyCollado/PlayFieldPortal/compare/v1.0.0-alpha.1...HEAD
[1.0.0-alpha.1]: https://github.com/JohnnyCollado/PlayFieldPortal/releases/tag/v1.0.0-alpha.1
