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
| Smart / manual category builder | ⏳ Upcoming |
| Unmatched ROM assignment UI | ⏳ Upcoming |
| Theme sound playback | ⏳ Upcoming |
| Boot animation override from theme | ⏳ Upcoming |

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
| Azahar (3DS) | ACTION_VIEW |
| Sudachi (Switch) | ACTION_VIEW |
| melonDS | ACTION_VIEW |
| mGBA | ACTION_VIEW |
| Winlator | SHORTCUT |
| GameHub | SHORTCUT |

Custom profiles can be added in **Settings → Emulators**.

---

## License

Private repository — all rights reserved. Not licensed for redistribution.
