# Play Field Portal — Architecture

Play Field Portal (PFP) is an Android **home-screen launcher** styled after the PSP/PS3
**XMB** (Cross Media Bar). It presents installed apps, emulator ROM libraries, and user
collections as a horizontal bar of categories with a vertical list of items beneath the
selected category, and launches games through external emulator apps.

- **Package:** `com.playfieldportal.launcher` (debug builds use the `.debug` suffix)
- **Min / Target / Compile SDK:** 29 (Android 10 — Winlator's floor) / 35 / 35
- **Version:** `1.1.0`
- **Stack:** Kotlin, Jetpack Compose, MVVM + Clean Architecture, Hilt DI, Room, DataStore,
  Coil (image loading), Coroutines/Flow.
- **Entry points:** [`PFPApplication`](app/src/main/kotlin/com/playfieldportal/launcher/PFPApplication.kt)
  (Hilt + first-run DB seeding) and
  [`MainActivity`](app/src/main/kotlin/com/playfieldportal/launcher/MainActivity.kt)
  (declared as the `HOME` launcher).

## Module structure

The project is multi-module with a strict dependency direction: **features depend on core,
core does not depend on features**, and `app` wires everything together via Hilt.

```
app  ──▶ feature:*  ──▶ core:core-ui ──▶ core:core-data ──▶ core:core-domain ──▶ core:core-common
```

| Module | Responsibility |
| --- | --- |
| `app` | DI wiring, manifest, `PFPApplication`, `MainActivity` (HOME launcher) |
| `studio` | Theme Studio — Compose Multiplatform Desktop companion (Windows/Linux/macOS); must never grow an Android dependency |
| `core:theme-kit` | Pure-JVM theme core shared with the Theme Studio: PTF/BMP/GIM/LZR parsers, `.pfptheme` codec, color cascade, icon-slot registry, XMB layout spec |
| `core:core-common` | Cross-cutting utilities and extensions (incl. the shared Keystore AES-GCM helper) |
| `core:core-domain` | Domain models, repository interfaces, use-case-level contracts (no Android deps where avoidable) |
| `core:core-data` | Room database, DAOs, entities, migrations, DataStore, repository implementations, seeders |
| `core:core-ui` | Shared Compose components, theming, the category-icon catalog, wave renderer |
| `feature:feature-xmb` | The XMB shell — wave background, category bar, item list, status bar, game/app detail, context menus, the main `XMBViewModel` |
| `feature:feature-library` | ROM scanning into the Memory Card library |
| `feature:feature-launcher` | Emulator detection + intent resolution (launching a ROM in the right emulator) |
| `feature:feature-artwork` | Metadata/artwork scrapers (ScreenScraper/TGDB/IGDB/SteamGridDB), the `ArtworkStore` storage seam, the portable artwork library (`portable/` — user-owned SAF folder, manifest, per-entry metadata) and the ES-DE artwork importer (`importer/`) |
| `feature:feature-achievements` | Shiba Coins: RetroAchievements / Steam / Local Steam (GSE/Goldberg) providers, coin mapping, wallet + standings, sync, emu-kit generation |
| `feature:feature-themes` | Theme loader/repository, built-in themes, `.pfptheme` / PSP `.ptf` install paths |
| `feature:feature-settings` | All settings screens, the first-run setup wizard, PC game import |
| `feature:feature-appbar` | App drawer, app→category classification, filtering |
| `feature:feature-backup` | Backup & restore (`.pfpbackup`) |
| `feature:feature-social` | Discord Social UI — full flavor only |
| `discord:discord-native` | NDK/CMake bridge to the Discord Social SDK (full flavor only) |

## Data layer (`core:core-data`)

- **Room** database [`PFPDatabase`](core/core-data/src/main/kotlin/com/playfieldportal/core/data/database/PFPDatabase.kt)
  (currently **v35**). Migrations are hand-written, one `MIGRATION_n_n+1` per version, registered
  in [`DatabaseModule`](core/core-data/src/main/kotlin/com/playfieldportal/core/data/database/di/DatabaseModule.kt).
  **Never** use destructive migration — it would wipe the user's library.
- **Seeding** is first-run only, gated by a DataStore flag, in
  [`DatabaseInitializer`](core/core-data/src/main/kotlin/com/playfieldportal/core/data/database/seeder/DatabaseInitializer.kt).
  Definition changes that must reach already-seeded installs ship as migrations (e.g. the v13
  Xbox 360 platform) or as idempotent per-launch reconciles (built-in categories).
- **Key entities:** `GameEntity` (games *and* app-shortcut rows, distinguished by `content_type`
  — `GAME` vs `ANDROID_APP`), `PlatformEntity`, `CategoryEntity`, `MemoryCardEntity`,
  `CollectionEntity`, `ThemeEntity`.
- **DataStore** holds settings/preferences (icon style, wave style, color scheme, setup flags,
  custom wallpaper, seed flags).

## Game / emulator launching (`feature:feature-launcher`)

1. [`KnownEmulatorCatalog`](feature/feature-launcher/src/main/kotlin/com/playfieldportal/feature/launcher/KnownEmulatorCatalog.kt)
   lists supported emulators (package, launch activity, intent shape, supported platforms).
2. [`EmulatorDetector`](feature/feature-launcher/src/main/kotlin/com/playfieldportal/feature/launcher/EmulatorDetector.kt)
   scans installed packages (plus RetroArch cores) into `EmulatorProfile`s on startup.
3. The launch profile is chosen in priority order: **per-game override → memory-card emulator →
   platform `preferredEmulatorPackage` → first valid**.
4. [`EmulatorIntentResolver`](feature/feature-launcher/src/main/kotlin/com/playfieldportal/feature/launcher/EmulatorIntentResolver.kt)
   builds the launch `Intent` (`ACTION_VIEW` with a FileProvider content URI, or a `COMPONENT`
   intent with extras), with fallbacks for emulators whose intent filters omit a MIME type.

## State & the XMB shell (`feature:feature-xmb`)

- [`XMBViewModel`](feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/viewmodel/XMBViewModel.kt)
  exposes a single `XMBUiState` `StateFlow`. The stateless
  [`XMBShell`](feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/XMBShell.kt)
  renders it; `XMBShellContainer` wires the ViewModel's callbacks in.
- **Navigation model:** the Games category root lists synthetic folders — **All Games**,
  **Favorites** (shown only when something is favorited), user collections, then one row per
  enabled Memory Card. Folders are entered by setting `selectedPlatformId` (sentinels
  `__all_games__` / `__favorites__`) or `selectedCollectionId`; BACK clears them.
- **Input:** a gamepad dispatcher routes D-pad/A/B/Y to the focused layer. `hasBlockingOverlay`
  guards the main XMB navigation so input never drives the bar behind a dialog/overlay.
- **Item icons** ([`XMBItemList`](feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/XMBItemList.kt)):
  games show a 144:80 landscape tile; apps with artwork show the same tile, apps without it show
  the launcher icon; folder rows use console / `sysicon_*` art.

## Icon system (`core:core-ui`)

All category-pick icons live in one catalog,
[`CategoryIcons`](core/core-ui/src/main/kotlin/com/playfieldportal/core/ui/icons/CategoryIcons.kt):
each `iconKey` maps to an individual drawable (`catbar_*` column glyphs + `sysicon_*` console art,
all from the [xmb-menu-es-de](https://github.com/anthonycaccese/xmb-menu-es-de) theme).
`categoryIconFor()` resolves current and legacy keys; `CategoryIconGlyph` renders them. There is
**no sprite sheet and no hand-drawn (Canvas) icon code** — that was removed pre-launch.

## Conventions

- **MVVM:** ViewModels own state (`StateFlow<UiState>`); composables are stateless and driven by
  state + callbacks.
- **Repositories** are the boundary between features and the data layer; features never touch DAOs
  directly.
- **ROM scanning is manual only** — user-initiated, never automatic (no FileObserver/polling).
- **Long-running work** (scans, artwork fetches, backups) runs off the main thread and surfaces
  progress through `BackgroundTaskNotifier` (system notifications).

## Build & run

The app has two flavors — `full` (ships the Discord Social SDK) and `lite` (omits it;
use this on x86_64 emulators, the native bridge is arm-only). See the README's
[For Developers](README.md#for-developers) section for the full build guide.

```bash
# Build the lite debug APK
./gradlew :app:assembleLiteDebug

# Install to a connected device
adb install -r app/build/outputs/apk/lite/debug/app-lite-debug.apk
```

If `adb install` reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (debug-signature mismatch),
uninstall first — note this clears local app data:

```bash
adb uninstall com.playfieldportal.launcher.debug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
