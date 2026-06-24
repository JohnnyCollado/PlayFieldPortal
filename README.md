# Play Field Portal (PFP)

An Android game launcher styled after the PlayStation Portable's XMB (Cross Media Bar). Replaces the Android home screen and serves as a unified frontend for ROM emulation, Android games, PC-layer titles via Winlator, and native apps.

> **Status:** Active development — pre-release. Not yet on the Play Store.

---

## Features

| Category | Status |
|---|---|
| XMB shell (wave, category bar, item list, status bar, boot sequence) | ✅ Done |
| Game library — ROM scanning, disc image resolution, SAF folder picker | ✅ Done |
| Game detail screen (hero banner, metadata, custom artwork, notes) | ✅ Done |
| Game icon styles — PSP rectangle, cartridge, Android squircle | ✅ Done |
| Emulator compatibility layer — 13 bundled launch profiles | ✅ Done |
| SteamGridDB artwork scraping | ✅ Done |
| App drawer — All Apps / Games / Emulators / Recently Used | ✅ Done |
| Controller mapping — full XMB nav via InputDevice API | ✅ Done |
| Backup & restore — `.pfpbackup` ZIP including settings | ✅ Done |
| Theme engine — `.xmbtheme` packages, built-in Classic PSP Blue | ✅ Done |
| Idle wave degradation (FULL → REDUCED → STATIC) | ✅ Done |
| PSP-style platform folders under Games | ✅ Done |
| Emulator profile editor UI — add/edit/delete custom launch profiles | ✅ Done |
| Custom Emulator Wizard — pick an app, auto-detect launch settings, test-launch, save | ✅ Done |
| User collections — custom many-to-many game folders (like Favorites, user-defined) | ✅ Done |
| Content-type filtering — All Games shows real games only (apps stay in their sections) | ✅ Done |
| Android-app & launcher shortcuts — add apps to Favorites/Collections; pull per-game shortcuts (GameHub/Moonlight, BannerHub, Winlator) | ✅ Done |
| XMB color schemes — PSP-style presets with live preview, in Themes | ✅ Done |
| Background tasks surfaced to the Android notification bar | ✅ Done |
| Smart / manual category builder | ⏳ Upcoming |
| Unmatched ROM assignment UI | ⏳ Upcoming |
| Theme sound playback | ⏳ Upcoming |
| Boot animation override from theme | ⏳ Upcoming |

---

## How to Use the Launcher

### Setting it as your home screen
PFP registers as an Android **HOME** launcher. The first time you press Home, Android asks which launcher to use — pick **Play Field Portal** (choose "Always" to make it the default). You can change this later in *Android Settings → Apps → Default apps → Home app*. On Android 13+ the app also asks for notification permission so background tasks can report progress.

### Navigation

PFP is built for a controller but works with touch too.

| Input | Controller | Touch |
|---|---|---|
| Move between items | D-Pad / Left Stick | Tap an item |
| Select / launch / open | **A / Cross** | Tap |
| Back / close / exit a folder | **B / Circle** | On-screen Back |
| Options (context) menu | **Y / Triangle** (or long-press) | Long-press |
| Switch app-drawer tabs | **L1 / R1** | Tap a tab |
| Confirm in pickers | **Start** | Confirm button |

The horizontal bar is your **categories** (Settings, Photo, Music, Video, Game, Network, App Store, plus any custom ones). Move left/right to switch; the vertical list shows that category's items. While any menu, settings screen, picker, or dialog is open, the main XMB is locked — input only affects the overlay on top.

### The Game category

Selecting **Game** shows, in order:

1. **All Games** — every real game across all consoles, aggregated. Only actual games appear here; Android/Video/Music apps never show up automatically.
2. **Your Collections** — user-made folders (see below).
3. **Memory Cards** — one per console you've configured. Open a card to see its games; press **△** on a card for *Scan This Console / Refresh / Pin / Hide / Remove*.

### Collections

Collections are custom folders of games (e.g. "RPGs", "Currently Playing", "Best PSP Games"). They behave like Favorites but are user-defined, and a game can live in several at once.

- **Create:** *Settings → Collections → Create New Collection*, or from a game's **△** options → *Add to Collection → Create New Collection*. The same action is on the **Game Detail** and **App Detail** screens.
- **Add / remove a game:** open a game's options (**△**), choose **Add to Collection**, and toggle the collections (a ✓ marks current membership). When viewing a game from inside a collection, **Remove from Collection** appears.
- **Manage:** *Settings → Collections* (rename, reorder, delete, remove games) or press **△** on a collection row in the Games list.

### Game & app options (△)

Pressing **△** (or long-press) on a game opens its options: Launch, Add/Remove Favorite, Add to Collection, Manage Collections, Refresh Metadata/Artwork, View File Location. Android apps add Move/Add/Remove/Pin to category, Hide, and Rename. The same actions are reachable from the full **Game Detail** screen.

### Adding Android apps

- **App sections** (App Store / Video / Music / Network / custom): open the section and choose **Add Apps** to pick installed apps.
- **Android games under Games:** open the Android library card and choose **Find Games**. These are tagged as apps, so they stay out of All Games but remain in their card and can be added to any collection.
- **Shortcut any app to Favorites / Collections:** press **△** on an app and choose **Add to Favorites** or **Add to Collection** — a shortcut entry is created that references the app (no metadata duplication).

### Pulling per-game shortcuts

PFP can surface another app's individual items as launchable entries — press **△** on a host app and choose **Import Game Shortcuts**. This reads the app's published launcher shortcuts (e.g. GameHub/Moonlight PCs) and groups them into a collection named after the app. Apps that create shortcuts via the modern pin API (**BannerHub**, modern **Winlator**) land automatically when you create a shortcut in them. Both require PFP to be the active default launcher.

### Background tasks

ROM scans, artwork fetches and metadata refreshes run in the background and report to the **Android notification bar** with live progress — pull down the shade to watch them.

### Settings

*Settings* (gear category) covers: Library, Categories, Collections, Artwork, Emulators, Themes (including the **Color Scheme** picker with live preview), Display, Controller, Backup & Restore, Logs, About, and Credits.

---

## Tech Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose (MVVM + state hoisting)
- **DI:** Hilt
- **Database:** Room 2.6.1 (v3, explicit migrations only)
- **Settings:** DataStore Preferences
- **Networking:** Ktor (SteamGridDB)
- **Image loading:** Coil 2.6
- **Background tasks:** WorkManager
- **Logging:** Timber (7-day rolling file log + in-app viewer)
- **Serialization:** Kotlinx Serialization
- **Testing:** JUnit 4 + MockK + Turbine

---

## Module Structure

```
app/                      — MainActivity, PFPApplication, Hilt app module
core/
  core-common/            — Shared utilities and extensions
  core-domain/            — Domain models, repository interfaces
  core-data/              — Room DB v3, DAOs, DataStore, repository implementations
  core-ui/                — PFPTheme, PFPColors, XMBWave, GameIconStyle enum
feature/
  feature-xmb/            — XMB shell, ViewModel, game icons, game detail, gamepad
  feature-library/        — ROM scanner, disc image resolver, platform extension map
  feature-launcher/       — Emulator intent resolver, launch profile repository
  feature-artwork/        — SteamGridDB API client, artwork repository
  feature-themes/         — .xmbtheme loader, ThemeRepository, built-in themes
  feature-settings/       — All 9 settings screens + ViewModels
  feature-appbar/         — App drawer, filter views, InstalledAppRepository
  feature-backup/         — BackupManager, BackupWorker, RestoreWorker
```

---

## Build

### Prerequisites
- Android Studio Hedgehog or newer
- JDK 17
- Android SDK 35, min SDK 29 (Android 10)

### Steps

```bash
git clone <repo-url>
cd xmbdroid
./gradlew assembleDebug
```

To run unit tests:

```bash
./gradlew test
```

---

## .xmbtheme Package Format

A `.xmbtheme` file is a renamed ZIP archive. Create one with any ZIP tool and rename the extension.

### Required

**`theme.json`**
```json
{
  "format_version": 1,
  "id": "my_unique_theme_id",
  "name": "My Theme",
  "author": "Your Name",
  "version": "1.0",
  "wave_color": "#0055AA",
  "wave_opacity": 0.7,
  "wave_speed": 1.0,
  "wave_amplitude": 1.0,
  "accent_color": "#FFFFFF",
  "text_color": "#FFFFFF",
  "has_background": false,
  "has_boot_animation": false,
  "has_sound_pack": false,
  "font_key": "system_default"
}
```

| Field | Type | Notes |
|---|---|---|
| `format_version` | int | Must be `1`. Future versions may add fields. |
| `id` | string | Unique identifier. Used as the asset directory name. No spaces. |
| `wave_color` | string | `#RRGGBB` (opaque) or `#AARRGGBB` (with alpha). |
| `wave_opacity` | float | 0.0–1.0. Default `0.7`. |
| `wave_speed` | float | Multiplier. `1.0` = normal, `2.0` = double speed. |
| `wave_amplitude` | float | Multiplier. `1.0` = normal. |
| `accent_color` | string | Used for selected items and highlighted UI elements. |
| `text_color` | string | Primary text. Secondary text is derived at 70% opacity. |
| `has_background` | bool | Set `true` if ZIP contains `background.jpg`. |
| `has_boot_animation` | bool | Set `true` if ZIP contains `boot_animation.mp4`. |
| `has_sound_pack` | bool | Set `true` if ZIP contains a `sounds/` directory. |
| `font_key` | string | `"system_default"` only for now. |

### Optional Assets

| Path in ZIP | Condition | Notes |
|---|---|---|
| `background.jpg` | `has_background: true` | Displayed behind the XMB wave. |
| `boot_animation.mp4` | `has_boot_animation: true` | Replaces the default boot sequence. |
| `sounds/navigate_h.ogg` | `has_sound_pack: true` | Horizontal navigation sound. |
| `sounds/navigate_v.ogg` | `has_sound_pack: true` | Vertical navigation sound. |
| `sounds/select.ogg` | `has_sound_pack: true` | Confirm / select sound. |
| `sounds/back.ogg` | `has_sound_pack: true` | Back / cancel sound. |
| `sounds/category_change.ogg` | `has_sound_pack: true` | Category bar switch sound. |
| `sounds/boot.ogg` | `has_sound_pack: true` | Plays during boot sequence. |

Assets not listed in the manifest flags are ignored even if present in the ZIP.

### Installing a Theme

1. Copy the `.xmbtheme` file to your device.
2. In PFP → Settings → Themes → **Install from File**, pick the file.
3. The theme appears in the list. Tap it to activate.

---

## ROM Library Setup

1. Go to **Settings → Library** (or tap the prompt in the Games category on first launch).
2. Tap **Choose Root Folder** and pick the folder that contains your ROM collection.
3. PFP reads platform subfolders automatically: `{root}/psx/`, `{root}/psp/`, `{root}/gba/`, etc.
4. Tap **Scan Now** to discover ROMs.

### Supported Folder Structure

```
ROMs/
  psx/        → PlayStation
  psp/        → PlayStation Portable
  gba/        → Game Boy Advance
  gbc/        → Game Boy Color
  gb/         → Game Boy
  n64/        → Nintendo 64
  snes/       → Super Nintendo
  nes/        → NES / Famicom
  md/         → Mega Drive / Genesis
  gg/         → Game Gear
  nds/        → Nintendo DS
  3ds/        → Nintendo 3DS
  gcn/        → GameCube
  wii/        → Wii
  ps2/        → PlayStation 2
  dc/         → Dreamcast
  ngp/        → Neo Geo Pocket
```

---

## Emulator Support

Bundled launch profiles (in `assets/emulators/`):

| Emulator | Intent type |
|---|---|
| RetroArch (32-bit) | COMPONENT |
| RetroArch (aarch64) | COMPONENT |
| PPSSPP | ACTION_VIEW |
| PPSSPP Gold | ACTION_VIEW |
| Dolphin | ACTION_VIEW |
| DuckStation | ACTION_VIEW |
| NetherSX2 | ACTION_VIEW |
| Azahar / AzaharPlus (3DS) | ACTION_VIEW |
| Lime3DS | ACTION_VIEW |
| Eden (Switch) | ACTION_VIEW |
| Sudachi (Switch) | ACTION_VIEW |
| melonDS | ACTION_VIEW |
| mGBA | ACTION_VIEW |
| Flycast / Redream (Dreamcast) | ACTION_VIEW |
| Mupen64Plus FZ | ACTION_VIEW |
| Winlator | SHORTCUT |

### Custom Emulator Wizard

For anything not bundled, **Settings → Emulators → Add Custom Emulator** runs an assisted setup:

1. **Pick an installed app** from a controller-navigable list.
2. PFP **auto-detects** launch settings — matching a curated catalog where possible, or inspecting the app's `ACTION_VIEW` handlers via `PackageManager`.
3. The editor opens **pre-filled** (with a confidence banner). Every field is editable — intent type, activity, action, MIME, URI mode, extras, flags, RetroArch core — and **recommended templates** fill common launch shapes in one tap.
4. **Test Launch** with a scanned ROM: preview the exact intent, attempt it, and get an actionable error if it fails.
5. **Save** — usable as a platform / Memory Card / per-game emulator.

ROMs on **removable SD cards / USB volumes** (`/storage/<uuid>/…`) launch correctly via FileProvider.

---

## Credits

### XMB design — Sony

The entire look and feel of this launcher is inspired by the **XMB (XrossMediaBar)**, the interface Sony created for the PlayStation Portable, PlayStation 3 and other devices. The cross-bar layout, the flowing wave background, the category/item navigation model and the options-menu behaviour are all homages to Sony's original design.

**"XrossMediaBar", "XMB", "PSP", "PlayStation" and related marks are trademarks of Sony Interactive Entertainment Inc.** Play Field Portal is an independent, non-commercial fan project. It is **not affiliated with, endorsed by, or sponsored by Sony** in any way, and ships none of Sony's code, firmware, fonts or proprietary assets.

### System & console artwork

The system, console and category icons in the launcher come from the **[XMB Menu for ES-DE](https://github.com/anthonycaccese/xmb-menu-es-de)** theme — a community recreation of the PSP XMB interface for ES-DE.

**All rights to this artwork belong to its creators — [Anthony Caccese](https://github.com/anthonycaccese), building on the original work by InitialDin.** The icons are used here with gratitude and remain the property of their respective authors.

- Project: XMB Menu for ES-DE
- Authors: Anthony Caccese · InitialDin
- Source: https://github.com/anthonycaccese/xmb-menu-es-de
- Used for: category-bar icons, per-console system icons, and the physical-media (cartridge) icon set

### Game artwork & metadata

Box art, hero banners, logos and grid icons are fetched at the user's request from third-party providers and remain the property of their respective owners:

- **SteamGridDB** — community artwork (grids, heroes, logos, icons)
- **IGDB** and **TheGamesDB** — optional metadata and artwork sources

### Notes

If you are a rights holder and would like attribution changed or any asset removed, please open an issue and it will be addressed promptly.

---

## License

Private repository — all rights reserved. Not licensed for redistribution. Third-party artwork remains the property of its respective authors (see [Credits](#credits)).
