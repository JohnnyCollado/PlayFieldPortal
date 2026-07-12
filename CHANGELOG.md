# Changelog

All notable changes to Play Field Portal are documented here. This project follows
[Keep a Changelog](https://keepachangelog.com/) and [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.0.4] - 2026-07-12

### Added
- **Game Detail upgrades.** The action row is now Launch (renamed from Play) plus four
  square buttons: **Video** (plays the game's snap — external player if one is pinned in
  Settings ▸ Video, else a built-in fullscreen player), **Options**, **Artwork**, and
  **Manual**. A **Screenshot** panel renders under the info panels (scraped from
  ScreenScraper or ES-DE-imported). Controller polish: Up from the button row always
  returns to Launch, one Up rewinds the page scroll entirely, and D-pad scrolling strides
  farther per press.
- **Icon Display Modes.** Every game tile can now be drawn four ways: **Custom Icon** (the
  PSP-authentic 144:80 ICON0 fill), **Box Art**, **Physical Media** (cartridge/disc shot),
  or **3D Box Art** — the last three render at their art's natural aspect inside the same
  fixed slot, so row pitch never moves. Set the global default in Settings ▸ Artwork ▸
  **Game Icon Display**, from any Memory Card / All Games card's △ menu (**Icon Display**),
  or per game via its △ menu. Each mode has its own placeholder when art is missing:
  ICON0 → 144:80 letter tile, Box Art / 3D Box → a letter tile shaped like that platform's
  box, Physical Media → the bundled per-platform cartridge/disc icon. Storage-side, true
  box art now owns the ES-DE `covers/` folder while 144:80 icons live in the PFP-only
  `pfp/icon0/` namespace — existing libraries migrate automatically (same-tree moves), and
  Scan & Relink reclaims grids a pre-split scan may have mislabeled as box art.
- **ICON1 video snaps.** In Custom Icon mode, resting on a game for ~1.5 s plays its video
  snap inside the icon — muted, capped at 60 s, then fading back to the still, exactly like
  a PSP ICON1.PMF. Battery-conscious by design: one shared player, playback skipped under
  Battery Saver / low battery unplugged / thermal pressure, plus an **Animated Icons**
  master toggle. When ScreenScraper has no ready-made snap but does have the full video,
  PFP downloads it and converts it locally (60 s trim, icon-sized, audio stripped) — the
  full video is never stored.
- **PIC0 logo choreography.** The focused game's clear logo fades in center-right over the
  hover background on the PSP stagger (icon → PIC1 → PIC0). Game rows are icon-first: no
  text labels except the focused row of a logo-less game, whose title + emulator label fade
  in on the same timeline.
- **Launch behavior.** Settings ▸ Display ▸ Games ▸ **Launch Games Directly**: confirm on a
  game boots straight into it (Game Detail opens underneath and fires its own Play, so every
  launch path stays in one place); the game's △ menu gains **View Game Details** for edits.
- **Richer scrapes.** ScreenScraper now supplies box-2D, box-3D and cartridge/disc
  (support-2D) art for the display modes, plus player count, age rating, franchise,
  community rating and release date (persisted for the future Game Detail redesign).
  Manuals download by default now (still size-capped); video snaps remain opt-in.
- **Settings ▸ Logs is real now.** PFP writes INFO+ log files on every build (rotating,
  512 KB × 4 cap) with **privacy-first redaction at write time** — credentials, tokens,
  ScreenScraper account names and email addresses never reach disk — and the Logs screen
  can view them (controller: ▲▼ scroll, ◄ ► pan, Ⓐ share, Ⓑ close) and share a file for
  bug reports via the system share sheet.
- **DB v27.** New game columns for the display-mode art (`box_art_uri`,
  `physical_media_uri`, `box3d_uri`, `icon_display_mode`) and scrape metadata (`players`,
  `age_rating`, `franchise`, `community_rating`, `release_date`); ES-DE-imported "icons"
  that were really box art are reclassified in place.
- **Fullscreen Artwork Studio.** Game Detail's Artwork button now opens a controller-first
  full-screen editor (it replaces the old in-detail manager). One tab per artwork kind —
  ICON0, ICON1, Box Art, 3D Box, Physical Media, Hero, Background, Logo, Screenshot, Manual,
  Video — with LB/RB to switch tabs, Y to jump to the source row, and a results grid you
  navigate as a grid (not just left/right). Each tab offers whichever sources can serve it:
  **ScreenScraper**, **SteamGridDB** (full art with an NSFW filter you can toggle with X),
  **TheGamesDB**, **IGDB**, and **Local File**; ICON0 offers all of them for maximum choice.
  Results page ~20 at a time. Press A to preview a candidate before applying — video tiles
  play a muted looping preview on focus/long-press, and manual PDFs page through with
  Left/Right before you commit. Press **START** for the per-slot actions menu: **Adjust
  Crop / Position**, **Restore Previous**, **Reset to Scraped Default**, **Clear Artwork**,
  and **View File Information**.
- **Crop / position editor.** A fixed, aspect-locked frame per kind (ICON 144:80, hero
  920:430, background 16:9, others free) with the image panning and scaling behind it —
  D-pad to move and LB/RB to zoom on a controller, drag and pinch on touch. Crops bake into
  the displayed file while the untouched original is kept for lossless re-crops. ICON1 snaps
  crop too: the clip is re-encoded to the framed region, not flattened to a still.
- **Scrape-as-you-go ScreenScraper.** The Studio's ScreenScraper source no longer needs a
  prior scrape — browsing a never-scraped game triggers one live match on the spot and
  caches the result, so the next open (and the next full scrape) is a free cache hit.
- **DB v28 / v29.** v28 adds the `ss_media_cache` table (one ScreenScraper response carries
  every kind's URLs, so later scrapes and the Studio skip the metadata call). v29 adds
  provenance and versioning to `artwork_records` — origin URL and provider for the file-info
  panel, a one-previous backup for Restore Previous, and baked-crop bookkeeping. Both
  migrations are additive; the private `pfp/versions/` and `pfp/originals/` namespaces stay
  invisible to Scan and Export.
- **More ScreenScraper platforms.** Xbox 360, Commodore 64, Android and Windows games now
  resolve to their ScreenScraper systems (they were silently unmatchable before).

### Changed
- **Artwork cache accounting is honest.** Settings ▸ Artwork shows the real stored-artwork
  size (image cache + internal store; your artwork folder is never counted), and
  **Clear All Artwork** now truly resets: Coil caches, internal files, records and every
  game's art links — while never touching files in your artwork folder.
- **A custom wallpaper freezes the wave** (Display-picked or theme-applied) to save battery
  — the wave resumes when the wallpaper is removed. The wallpaper preview now dismisses
  with Confirm/Back as well as tap.
- **ES-DE export is more correct**: `covers/` now carries real box art instead of 144:80
  grid icons.
- About shows the real installed version (read from the package, not a stale constant) and
  About / Credits scroll with the controller; Credits now credit **ScreenScraper** as the
  primary scraper.

### Removed
- Display ▸ **Icon Style** (superseded by Game Icon Display — Physical Media mode is the
  cartridge look) and Artwork ▸ **Preferred Grid Style** (was never wired to anything).
- The old in-detail artwork manager, fully replaced by the fullscreen Artwork Studio.

### Fixed
- **Scrape Missing fills partial games.** "Scrape Missing Games Only" judged a game complete
  as soon as it had a background, so a game with a background but no box art or logo was
  skipped. It now targets any game missing primary artwork (background, box art or logo) and
  fills only the gaps — existing artwork is never re-downloaded or overwritten.
- **Studio video previews are reliable.** ScreenScraper serves videos with no length and no
  seek support, so clips whose index trails the data couldn't stream and the tile silently
  fell back to a badge. The Studio now streams first and, on failure, plays from a cached
  local copy.

### Added
- **Portable media library + ES-DE artwork import.** Settings ▸ Artwork ▸ **Artwork Folder &
  Import**: pick a folder (SAF, read+write grant — no all-files permission) where PFP keeps
  artwork as a user-owned, reconnectable library with a clean two-folder root —
  `Artwork/{platform}/{covers,fanart,marquees,…}/{ROM Filename}.png` plus an `Import/` drop
  zone and a root `pfp-artwork-library.json` manifest. The tree inside `Artwork/` is exactly an
  ES-DE `downloaded_media` layout, so pointing another frontend at it works with no export
  step. Libraries created before the `Artwork/` nesting upgrade themselves automatically
  (same-drive folder moves, zero bytes copied). Provenance (source, user-assigned, locked)
  lives in the `artwork_records` table. Drop another
  launcher's media under `Import/<Launcher>` (ES-DE `downloaded_media` in V1) and PFP detects it
  by structure, matches artwork to games in three passes (exact ROM filename → display title →
  tag-stripped title; ambiguities are reviewed, never guessed), shows a full preview (counts by
  media type, size, needs-review, unmatched), then imports in a background worker with
  notification progress. **Move mode** transfers same-volume files without copying bytes
  (`moveDocument`); copy mode uses in-kernel copies. Imports are resumable, respect existing and
  locked artwork, and land as ES-DE covers→icon, miximages→hero, fanart→background,
  marquees→logo, plus stored-for-later screenshots/title screens/physical media, and **PDF
  manuals** — the game's Options ▸ Manual action opens the manual **in-app** (built-in PDF
  renderer, no external viewer): D-pad ◀▶ turns pages, ▲▼ scrolls, B closes; touch taps the
  screen edges to turn pages and drags to scroll. Every run is logged to a persistent
  **Import Report**.
- **DB v25/v26.** `games.artwork_key` (stable portable identity), `artwork_import_reports`,
  and `artwork_records` — the rebuildable map over the artwork folder with per-asset
  provenance (replaces the interim `artwork_index`). Existing per-game-folder libraries are
  upgraded to the ES-DE layout in place (same-volume moves, lossless, automatic).
- **Game metadata import from ES-DE `gamelist.xml`.** Drop ES-DE's `gamelists` folder alongside
  the media and the importer fills each matched game's description, developer, publisher,
  release year, genre and canonical title — fill-missing-only, never overwriting scraped or
  user-set values. The Game Detail screen now shows publisher and total play time, and the
  page scrolls with the D-pad (DOWN past the buttons) as well as touch.

- **Scraped and hand-picked artwork now lands in the portable library too.** With a folder
  linked, every artwork save — metadata scrapes, SteamGridDB/TGDB/IGDB grid picks, local image
  picks — writes into `{platform}/{mediaDir}/{ROM Filename}.{ext}` alongside imports; without
  one, internal storage works exactly as before. Auto-scrapes never overwrite an existing valid
  library asset; hand-picked artwork is marked user-assigned + locked so nothing automatic
  touches it. "Clear All Artwork" clears app state only — files in the user's folder are never
  deleted (Relink restores them).
- **Scan & Relink Library** (Artwork Folder & Import) — one pass that reconnects folder
  artwork to games, refreshes moved/changed files, removes references to deleted ones
  (only ever with live folder access — a disconnected folder never destroys state), and
  reports duplicate names. Linking a folder that already contains ES-DE-shaped media —
  a previous PFP library or a plain `downloaded_media` tree — **adopts it in place**, zero
  bytes copied. A lost folder grant shows a warning on the Artwork settings row.
- **Export for ES-DE** — copies the library's standard media folders into any folder you pick
  (e.g. an ES-DE install's `downloaded_media`), incremental (existing files skipped), with
  notification progress and a report entry. The live library is never modified; `pfp/`-private
  art is never exported.
- Game Detail shows publisher and total play time; long descriptions are reachable by D-pad
  (DOWN past the buttons scrolls the page) as well as touch.
- **Move Into Folder** (Artwork Folder & Import ▸ App Storage) — artwork scraped or picked
  *before* a folder was linked lives in app-private storage; one tap moves it into the
  portable library (background worker, cancellable, resumable, report entry). Existing
  folder artwork always wins — a valid library asset is never overwritten, and the redundant
  internal copy is cleaned up. Hand-picked artwork migrates as locked. Game references
  repoint to the new files; internal space is freed only after each verified write.

### Changed
- `PlatformFolderHintResolver` and the SAF child-listing helpers moved from `feature-library`
  to `core-data` so the artwork importer shares the exact ES-DE system-name mapping and
  cursor-based directory listing the scanners use.

### Fixed
- Artwork scrapes are real background jobs now: progress shows in the notification shade, a
  **Cancel Scrape** row stops the batch after the in-flight game (everything fetched so far is
  kept), and the run survives leaving the settings screen — previously it ran invisibly inside
  the screen and silently died when you navigated away. Reopening Artwork Settings mid-scrape
  reattaches to the live progress.
- Scan & Relink now reconnects scraped artwork, not just imported artwork: files PFP wrote
  itself reconnect through their own records (exact claim), so sanitized-title names
  ("Resident Evil: The Mercenaries 3D" → no colon on disk) and collision-suffixed names
  ("Game (2).png") no longer fall through the fuzzy matcher as orphans. A library file also
  now replaces a game reference that points at a remote URL — a rotted CDN link no longer
  blocks the repoint and leaves the game artless.
- Video snaps saved into the portable library no longer fail silently — extension detection
  was image-only, so a valid MP4/WebM passed validation but never landed on disk; video files
  now save with correct names and MIME types.
- Artwork validity checks now understand `content://` references — previously every portable
  artwork ref was misjudged as stale, so "Scrape Missing Games Only" silently wiped imported
  artwork links (files were never touched; Relink Library restores them).
- Concurrent imports of two same-platform games could create a duplicate platform folder
  ("gba (1)") in the artwork library — directory creation is now serialized.
- Multi-row disc games (.cue + .bin scanned as two entries) no longer make their artwork
  "ambiguous"; both rows share the same imported files. Dump-index-prefixed artwork
  ("0556 - Game.png") matches its game at a degraded confidence.

## [1.0.0] — 2026-07-07

The 1.0 release. Touch-first navigation and a consistent, theme-matched UI across every
full-screen menu; a move to permission-free storage (ROM libraries, media and backups all go
through the Storage Access Framework, so the app no longer needs all-files access); a new
**opt-in Discord Social section** (QR sign-in, friends, presence sharing); and the **custom
theme system** — `.pfptheme` bundles with custom icons and per-theme layout, PSP `.ptf` import
(zlib + LZR), and the cross-platform **Theme Studio** desktop companion with its PTF unpacker.

### Added
- **Custom themes (`.pfptheme`).** The theme system, built on a one-color cascade — pick a
  background and one color and the wave, gradient, cursor, and icon tint all follow. Settings ▸
  Themes now offers 12 PSP-style preset schemes (7 → 12: adds Sakura Pink, Golden Amber, Aqua
  Teal, Midnight Navy and Charcoal, plus the month-cycling *Original*), a unified **icon color**
  for every XMB glyph (8 curated swatches; content imagery — game art, covers, app icons — is
  never tinted), **Quick Create** (any photo becomes a theme, accent auto-derived from its
  dominant hue), a **saved-theme library** with apply/share/delete, import of real PSP **`.ptf`**
  themes — wallpapers in both zlib (firmware 3.80+) and LZR compression (firmware 3.70 era), via
  an independent LZR decompressor; CXMB files are rejected with an explanation — and `.pfptheme`
  bundle share/import. Applying a theme drives wallpaper, accent, icon color, **custom icons**, and
  **per-theme XMB layout** in one step; choosing a preset scheme cleanly exits custom-theme mode.
- **`:core:theme-kit` module.** A pure-JVM theme parsing/conversion core shared by the launcher
  and the Theme Studio: official PSP `.ptf` container parser (built from a byte-level study of
  Sony's format — see `docs/official-ptf-template.md`), BMP/GIM decoders, the LZR decompressor,
  wallpaper accent derivation, `.pfptheme` codec, icon-slot registry, and the XMB layout spec —
  with a hermetic test suite plus golden tests against Sony's own example themes.
- **Custom icon slots.** Themes can replace 47 XMB glyphs — the 9 category-bar icons, the item-row
  glyphs (folders, playlists, social rows…), and the status-strip battery/Bluetooth icons. Custom
  icons render exactly as the author drew them; untouched slots keep the built-in art and follow
  the theme's icon color. Platform/console icons stay uniform by design.
- **Theme Studio (desktop companion).** A new `:studio` Compose Desktop app (Windows/Linux/macOS,
  `gradlew :studio:run` / `run-theme-studio.bat`) for making and converting themes: a live
  pixel-parity XMB preview with **Home / Context-menu / Fullscreen-menu** states, preset swatches +
  hex fields + **HSV color pickers**, an **icon editor** over every themeable slot with an editable
  template-pack export, wallpaper import with **crop presets** (PSP 480×272 / 720p / 1080p) and
  soft-wallpaper/dark-icon legibility hints, a **crossbar alignment assist** that auto-detects the
  dark band PSP wallpapers bake in (plus a manual position slider the launcher honors), export with
  an embedded rendered preview, **batch `.ptf` → `.pfptheme`** folder conversion, and a
  **PTF unpacker** ("Unpack PTF…") that extracts every resource of an official PSP theme —
  wallpaper, embedded preview, category ribbons, and item icons with their focused variants
  (GIM textures: indexed + direct-color formats, PSP swizzle, transparency preserved) — as
  reference PNGs for rebuilding the theme with original assets.

- **Discord Social section (opt-in).** A new **Social** column on the XMB. Sign in by scanning a
  **QR code** with your phone (OAuth2 device grant — no password typed on the handheld); tokens are
  stored **encrypted** in the Android Keystore and renewed automatically so you stay signed in.
  Drill into your account for **Friends** (avatars + a colored presence dot and what they're playing
  in PFP), **Activity Settings** (opt-in — default **off** — sharing that you're in Playfield Portal,
  with a **Generic Mode** that shows just "a game"), and **Discord Settings** (Sign Out). The account
  row's Options (Y/△) offers **Reconnect** for when the network drops and comes back. Everything is
  inert until you connect, and presence is limited by Discord to this app only — nothing outside PFP
  is ever shared. **Voice chat** rooms (join by code, invite friends) with Krisp noise cancellation,
  a Game↔Voice audio balance, and **push-to-talk** — either a floating hold-to-talk button that
  works over a running game, or a controller button you map yourself (held while in PFP). Discord is
  **optional at build time**: a **lite** build ships without the SDK for a ~44 MB smaller download
  (its native voice libraries are the bulk of the size) and simply omits the Social section.
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

### Security
- **Hostile theme files can't hurt the app.** Every external-file path (PSP `.ptf`, `.pfptheme`,
  wallpaper/icon images — on both the launcher and Theme Studio) is bounded: 64 MB read caps at
  every entry point, zip-bomb and zlib-inflation caps, image-dimension pre-checks before any pixel
  allocation, icon names whitelisted against the slot registry (no path smuggling), per-theme
  layout values clamped so a mangled manifest can never push the XMB offscreen, and the PTF
  unpacker's LZR/GIM decoders bounded per record (32 MB) and in total (256 MB) so a crafted
  record chain can't expand into a decompression bomb.

### Changed
- **XMB geometry retuned to the authentic PSP layout**, pixel-measured against a real-PSP theme
  capture: the category bar sits higher (icon row ≈ 25% of screen height) with the selected item
  ≈ 50%, category icons and item glyphs are larger, item labels are bigger with a clear gap from
  the icon column, **every** first-level item is labeled (not just the selected one and the next),
  and the previous item rises fully clear of the category icon before dissolving. The geometry now
  lives in a per-theme layout spec (`theme-kit`), so imported themes can eventually carry their own
  alignment.
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

### Security
- **Theme parsers hardened against hostile files.** `.ptf` and `.pfptheme` files arrive from
  arbitrary sources via the file picker; crafted inputs could previously crash the app: the PTF
  wallpaper inflater now caps decompressed output (zlib bombs), the BMP decoder caps image
  dimensions (fixing an integer-overflow that bypassed its bounds checks), and the `.pfptheme`
  reader caps each zip entry (zip bombs). All three guards are pinned by tests.

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

[Unreleased]: https://github.com/JohnnyCollado/PlayFieldPortal/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/JohnnyCollado/PlayFieldPortal/compare/v1.0.0-alpha.3...v1.0.0
[1.0.0-alpha.3]: https://github.com/JohnnyCollado/PlayFieldPortal/compare/v1.0.0-alpha.2...v1.0.0-alpha.3
[1.0.0-alpha.2]: https://github.com/JohnnyCollado/PlayFieldPortal/compare/v1.0.0-alpha.1...v1.0.0-alpha.2
[1.0.0-alpha.1]: https://github.com/JohnnyCollado/PlayFieldPortal/releases/tag/v1.0.0-alpha.1
