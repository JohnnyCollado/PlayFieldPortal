# Play Field Portal — Architecture

Play Field Portal (PFP) is an Android **home-screen launcher** styled after the PSP/PS3
**XMB** (Cross Media Bar). It presents installed apps, emulator ROM libraries, and user
collections as a horizontal bar of categories with a vertical list of items beneath the
selected category, and launches games through external emulator apps.

- **Package:** `com.playfieldportal.launcher` (debug builds use the `.debug` suffix)
- **Min / Target / Compile SDK:** 29 (Android 10 — Winlator's floor) / 35 / 37
- **Version:** `1.3.0` (`versionCode` 10), unreleased; the last release is `1.2.1`
- **Stack:** Kotlin, Jetpack Compose, MVVM + Clean Architecture, Hilt DI, Room, DataStore,
  Coil (image loading), Media3/ExoPlayer, Coroutines/Flow, Ktor (scrapers, knowledge downloads),
  Retrofit + OkHttp (Steam Web API / Store / Community, Steam Hunters), Tink (`Ed25519Verify` for
  signed emulator knowledge).
- **Last reviewed:** 2026-10-02.
- **Entry points:** [`PFPApplication`](app/src/main/kotlin/com/playfieldportal/launcher/PFPApplication.kt)
  (Hilt + first-run DB seeding) and
  [`MainActivity`](app/src/main/kotlin/com/playfieldportal/launcher/MainActivity.kt)
  (declared as the `HOME` launcher).

> **Unreleased.** The subsystems under [Customization](#customization-icons-ui-media-and-motion)
> — custom icons, UI media, motion wallpapers, theme schema v3 — and the
> [emulator knowledge base](#game--emulator-launching-featurefeature-launcher) landed after the
> 1.2.1 release and ship in 1.3.0. `CHANGELOG.md` draws the same line.

## Design principles

| Principle | What it means in practice |
|---|---|
| **Launcher-first** | Replaces the Android home screen. Users should never need to leave PFP to play games. |
| **Performance over polish** | The XMB wave is iconic but must never kill frame rate. Tiered degradation over dropped frames. |
| **No background polling** | Nothing watches the filesystem. No `FileObserver`, no polling loop, no scanning service. Rescans are event-driven — see [ADR-0002](docs/adr/0002-rescan-triggers-without-a-filesystem-watcher.md). |
| **Zero junk in release** | Debug tooling is compile-time excluded. The release APK contains no simulation code. |
| **PSP soul, Android body** | The aesthetic is XMB but the interaction model is Android — back gestures, Intents, Compose. |
| **Tested by default** | Every new module ships with unit tests. Pure-JVM tests (MockK + Turbine) for logic; integration tests for the database and migrations. |
| **A rejected import changes nothing** | Media and icon imports are staged. A file that fails validation never disturbs the assignment that already worked. |

## Module structure

The project is multi-module with a strict dependency direction: **features depend on core,
core does not depend on features**, and `app` wires everything together via Hilt.

```
app  ──▶ feature:*  ──▶ core:core-ui ──▶ core:core-data ──▶ core:core-domain ──▶ core:core-common
```

| Module | Responsibility |
| --- | --- |
| `app` | DI wiring, manifest, `PFPApplication`, `MainActivity` (HOME launcher), broadcast receivers |
| `studio` | Theme Studio — Compose Multiplatform Desktop companion (Windows/Linux/macOS); must never grow an Android dependency |
| `core:theme-kit` | Pure-JVM theme core shared with the Theme Studio: PTF/BMP/GIM/LZR parsers, `.pfptheme` codec, color cascade, icon-slot registry, XMB layout spec, and the shared limit objects (`UiMediaLimits`, `MotionLimits`, `IconGifSupport`) |
| `core:core-archive` | Pure-JVM bounded ZIP ingestion (`BoundedZipReader`, `SafeArchivePath`) shared by theme parsing, backup restore, and the theme codec |
| `core:core-common` | Cross-cutting utilities and extensions (incl. the shared Keystore AES-GCM helper) |
| `core:core-domain` | Domain models, repository interfaces, use-case-level contracts (no Android deps where avoidable), `FeatureFlags`, and the pure emulator-knowledge logic in `model/emulatorkb/` (decoder, validator, layer merge, import plan, export, platform-extension planner) |
| `core:core-data` | Room database, DAOs, entities, migrations, DataStore, repository implementations, seeders, the user-asset stores (`CustomIconStore`, `UiMediaStore`, `PfpThemeStore`), and `kb/` (`EmulatorKbDownloader`, `PlatformKnowledgeApplier`, `LegacyEmulatorIdRewriter`) |
| `core:core-navigation` | Pure navigation logic with no Android dependency — `NavigationEngine`, `NavigationCommand`, grid movement (`gridMove`). Consumed by feature-xmb and feature-settings so cursor behavior is unit-testable away from Compose |
| `core:core-ui` | Shared Compose components, theming, the category-icon catalog, wave renderer, motion-wallpaper surfaces, `MenuSoundPlayer` |
| `feature:feature-xmb` | The XMB shell — wave background, category bar, item list, status bar, game/app detail, context menus, the custom-icon editor overlay, the main `XMBViewModel` |
| `feature:feature-library` | ROM scanning into the Memory Card library, plus the rescan triggers |
| `feature:feature-launcher` | Emulator detection, the launch-resolution ladder, the launch dispatcher, the built-in knowledge asset (`assets/emulator_kb/emulators.json`) and `kb/` (`EmulatorKnowledgeStore`, `EmulatorKnowledgeRefresher`, `EmulatorKbUpdater`, `KbSignatureVerifier`, `SignerProbe`) |
| `feature:feature-artwork` | Metadata/artwork scrapers (ScreenScraper/TGDB/IGDB/SteamGridDB), the `ArtworkStore` storage seam, the portable artwork library (`portable/` — user-owned SAF folder, manifest, per-entry metadata) and the ES-DE artwork importer (`importer/`) |
| `feature:feature-achievements` | Shiba Coins: RetroAchievements / Steam / Local Steam (GSE/Goldberg) / PS3 (ARMSX3) providers, hidden-description enrichment (Steam Hunters, then Steam Community pages), coin mapping, wallet + standings, sync, emu-kit generation |
| `feature:feature-themes` | Theme loader/repository, built-in themes, `.pfptheme` / PSP `.ptf` install paths |
| `feature:feature-settings` | All settings screens (including Emulator knowledge: `EmulatorKnowledgeScreen`, `EmulatorKnowledgeReview`, `EmulatorKnowledgeExport`), the first-run setup wizard, PC game import |
| `feature:feature-appbar` | App drawer, app→category classification, filtering |
| `feature:feature-backup` | Backup & restore (`.pfpbackup`). **Parked:** `FeatureFlags.BACKUP_RESTORE = false` hides it from Settings until the format is reworked; the module and route stay in the build |
| `feature:feature-social` | Discord Social UI — full flavor only |
| `discord:discord-native` | NDK/CMake bridge to the Discord Social SDK (full flavor only) |

## Data layer (`core:core-data`)

- **Room** database [`PFPDatabase`](core/core-data/src/main/kotlin/com/playfieldportal/core/data/database/PFPDatabase.kt)
  (currently **v55**). Migrations are hand-written, one `MIGRATION_n_n+1` per version, registered
  in [`DatabaseModule`](core/core-data/src/main/kotlin/com/playfieldportal/core/data/database/di/DatabaseModule.kt).
  **Never** use destructive migration — it would wipe the user's library.
- **Seeding** is first-run only, gated by a DataStore flag, in
  [`DatabaseInitializer`](core/core-data/src/main/kotlin/com/playfieldportal/core/data/database/seeder/DatabaseInitializer.kt).
  Definition changes that must reach already-seeded installs ship as migrations (e.g. the v13
  Xbox 360 platform) or as idempotent per-launch reconciles (built-in categories).
- **Key entities:** `GameEntity` (games *and* app-shortcut rows, distinguished by `content_type`
  — `GAME` vs `ANDROID_APP`), `PlatformEntity`, `CategoryEntity`, `MemoryCardEntity`,
  `CollectionEntity`, `ThemeEntity`, and the `launch_outcomes` table added in v41.
- **DataStore** holds settings/preferences (icon style, wave style, color scheme, setup flags,
  custom wallpaper, media stamps, seed flags).
- **User assets are files, not blobs.** Custom icons, UI media and extracted theme icons live under
  `filesDir/` with the directory as the source of truth; DataStore holds only invalidation stamps.

## Library scanning (`feature:feature-library`)

[`LibraryScanner`](feature/feature-library/src/main/kotlin/com/playfieldportal/feature/library/scanner/LibraryScanner.kt)
is the single owner of ROM-survey policy: source resolution, one-upsert-per-path across multiple
sources, optional Missing reconciliation, changed-only persistence, per-card single-flight, and IO
execution. Both the settings interface and the trigger path delegate to it — see
[ADR-0001](docs/adr/0001-library-scanner-owns-rom-survey.md).

Scans start one of two ways:

- **Manually**, from the Library Manager or the XMB's Memory Card scan action.
- **By trigger**, through `LibraryRescanCoordinator` → `RescanTriggerBus`: app resume (throttled to
  one scan per 5 minutes), plus media mount and USB disconnect (2 s debounce, where a later event
  cancels the pending job). All triggers share a single-flight mutex, and console discovery runs
  before the incremental pass so a ROM dropped into a folder for a console with no Memory Card yet
  is still picked up.

Nothing watches the filesystem. Every scan traces back to a user action, a lifecycle event, or a
system broadcast — the guards and the rejected alternatives are recorded in
[ADR-0002](docs/adr/0002-rescan-triggers-without-a-filesystem-watcher.md).

## Game / emulator launching (`feature:feature-launcher`)

**Detection.** What PFP knows about emulators is **data**, not code: the emulator knowledge base
(KB) replaced the old hard-coded `KnownEmulatorCatalog`. Each entry names an emulator's packages,
the platforms it runs and one launch recipe (intent type, activity, action, category, extras,
flags, MIME type, optional per-package overrides and optional signer pins).
[`EmulatorDetector`](feature/feature-launcher/src/main/kotlin/com/playfieldportal/feature/launcher/EmulatorDetector.kt)
turns the effective KB plus installed packages (and RetroArch cores) into `EmulatorProfile`s. The
Winlator / GameHub SHORTCUT profiles are not KB entries; they still load from
`assets/emulator_profiles/bundled_profiles.json`.

**Knowledge layers.** [`EmulatorKnowledgeStore`](feature/feature-launcher/src/main/kotlin/com/playfieldportal/feature/launcher/kb/EmulatorKnowledgeStore.kt)
owns three layers and publishes their merge (`EmulatorKbMerge`, pure, in `core-domain`):

1. **Built-in** — `assets/emulator_kb/emulators.json` (85 emulators, `"version": 1`,
   `"label": "built-in"`).
2. **Official** — `filesDir/emulator_kb/official/emulators.json`, installed only by the updater and
   applied only when its version is greater than the built-in one.
3. **User files** — `filesDir/emulator_kb/user/<id>.json` plus `index.json`, applied in import order
   (at most 32).

Later layers replace an entry by id or strip the packages they claim; `legacyIds` (retiring an
old id) are honoured only from built-in and official layers. A user's own edits to a persisted
profile still win over every layer. Every layer is re-decoded and re-validated on every load
(`EmulatorKbDecoder`, `EmulatorKbValidator`); built-in and official layers are all-or-nothing,
user files per entry (an unreadable user file stays listed so it can be removed). Writes are temp
file then rename. The KB directory sits outside every backup root, so a restore can never become
an unreviewed import.

**Validation is the security model.** A KB file is data, never commands: `CUSTOM_COMMAND` and
`SHORTCUT` launches are refused, as are packages under `android.`, `com.android.`, `com.google.`
and other OEM system prefixes, PFP's own package, invalid identifiers, unsupported intent flags,
and extras that are anything but one placeholder or a plain literal (no `/` or `:`, so never a path
or URI). Sizes are capped (1 MB of text, 500 emulators, 64 platforms, 8 packages per entry, 32
extensions per platform, bounded string length and nesting depth). Platform ids the app does not
know are dropped, not refused. `CUSTOM_COMMAND` profiles are also refused at launch by
`EmulatorIntentResolver` and no longer offered by the profile editor. An entry may pin signer
SHA-256 digests: the import review blocks it when the installed app's signer differs
(`SignerProbe`), and `validateBeforeLaunch` refuses to launch a pinned profile whose installed
package carries none of the pinned certificates, so a look-alike app cannot receive the recipe.

**Refresh.** [`EmulatorKnowledgeRefresher.run()`](feature/feature-launcher/src/main/kotlin/com/playfieldportal/feature/launcher/kb/EmulatorKnowledgeRefresher.kt)
is the single entry point for "the KB may have changed": app start (after the database seed) and
every KB-changing flow (official install, import, remove, reset) call it. It runs serially and
non-cancellably, in this order:

1. **Platform extensions** — `PlatformKnowledgeApplier` (`core-data/kb/`) adds KB ROM extensions to
   the seeded platform and its Memory Card in one Room transaction. Additive only, and a row is
   written only while it still equals the seed default or the last KB-applied set
   (`PlatformExtensionPlanner`), so a list the user customized is never touched. Removing a file or
   resetting never takes extensions back. Gains are surfaced as `gainedFileTypes` for the
   "rescan suggested" note; no rescan is triggered.
2. **Auto-config** — `EmulatorAutoConfigService.runOnStartup()` re-detects and persists profiles.
3. **Legacy id rewrite** — `LegacyEmulatorIdRewriter` moves stored references to retired bundled ids
   onto the detected `auto_<pkg>` profile; only built-in/official entries can drive this, and ids a
   persisted (user-edited) profile or a bundled shortcut still holds are kept.

**Official updates.** [`EmulatorKbUpdater`](feature/feature-launcher/src/main/kotlin/com/playfieldportal/feature/launcher/kb/EmulatorKbUpdater.kt)
fetches `emulators.json` and `emulators.json.sig` from the `emulator-kb` release of
`JohnnyCollado/PlayFieldPortal` over HTTPS (`EmulatorKbDownloader`, streaming caps of 1 MiB and
1 KiB, `User-Agent` the only header). Pipeline: throttle (automatic checks at most once per 24 h,
toggleable; manual checks bypass both) → download body, then signature → verify the detached
Ed25519 signature over the exact bytes **before** parsing (`KbSignatureVerifier`, Tink
`Ed25519Verify`, any of the pinned keys) → decode → schema and `minAppVersion` (an app
`versionCode`) gates → validate all-or-nothing → **anti-rollback**: refuse a version lower than the
highest ever accepted (kept in `official/state.json`, which a reset deliberately keeps) or not
greater than the built-in version; an equal version is allowed so a reset can re-fetch → record the
new high-water mark first, then install, then refresh. Every failure keeps the installed file and
records why. `KbSignatureVerifier.PINNED_KEYS` is **empty** in this build, so `isConfigured` is
false, nothing is downloaded and the Settings rows read "Not available in this build". Releasing
is documented in [`tools/emulator-kb/README.md`](tools/emulator-kb/README.md).

**Import and export** (Settings › Emulators › Emulator knowledge). An imported file is decoded,
validated and turned into an `EmulatorKbImportPlan` (New → ticked; Change → unticked with a field
diff and "Overrides official" / "Your edit" flags; console extension updates → unticked; Blocked
with reasons). Only the ticked entries are re-serialized into the user file — never the original
bytes. `EmulatorKbExport` writes the user's custom and edited profiles through the same validator,
so a recipe that cannot be imported cannot be exported; exports carry launch settings only.

**Resolution.** [`EmulatorLaunchResolver`](feature/feature-launcher/src/main/kotlin/com/playfieldportal/feature/launcher/EmulatorLaunchResolver.kt)
walks the configuration ladder and returns a typed `ResolvedLaunch(profile, source, core)` — the
winning profile *and* the `LaunchSource` enum naming which level decided it, so the UI can attribute
the choice on screen without matching on strings. Precedence, pinned by tests:

1. per-game override → 2. Memory Card emulator → 3. platform default → 4. first valid candidate

The resolver is pure: every input is passed in, so it carries no Android, database or Hilt
dependency. [`EmulatorIntentResolver`](feature/feature-launcher/src/main/kotlin/com/playfieldportal/feature/launcher/EmulatorIntentResolver.kt)
then builds the `Intent` (`ACTION_VIEW` with a FileProvider content URI, or a `COMPONENT` intent
with extras), with fallbacks for emulators whose intent filters omit a MIME type.

**Dispatch.** Every game-path launch funnels through
[`LaunchDispatcher`](feature/feature-launcher/src/main/kotlin/com/playfieldportal/feature/launcher/LaunchDispatcher.kt),
which owns three things that used to be scattered across call sites:

- **Named failures.** `startActivity` lives here, so an `ActivityNotFoundException` or
  `SecurityException` can never be silently swallowed. Preflight also refuses launches with revoked
  SAF grants, or launch activities dropped by an emulator update.
- **Outcome recording.** Each settled launch writes a `LaunchOutcome` row (the `launch_outcomes` table, added in schema v41).
- **Post-launch verification.** PFP is the HOME launcher, so no usage-stats permission is needed: a
  successful dispatch backgrounds it. If the host is never stopped inside the stop window, the
  emulator never took the foreground; a return before the minimum session length is treated as an
  instant crash or refusal. Both are conservative — a real session records `SUCCEEDED` silently and
  never pops UI.

Failures emit a `LaunchRecoveryRequest` so the shell can offer retry, a different emulator or core,
per-system defaults, and copyable diagnostics instead of a dead end.

## State & the XMB shell (`feature:feature-xmb`)

- [`XMBViewModel`](feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/viewmodel/XMBViewModel.kt)
  exposes a single `XMBUiState` `StateFlow`. The stateless
  [`XMBShell`](feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/XMBShell.kt)
  renders it; `XMBShellContainer` wires the ViewModel's callbacks in.
- **Navigation model:** the Games category root lists the UMD slot, then synthetic folders —
  **All Games**, **Favorites** (shown only when something is favorited), user collections (shown
  as Custom Memory Cards), then one row per enabled Memory Card. Folders are entered by setting
  `selectedPlatformId` (sentinels `__all_games__` / `__favorites__` / `__category_card__`) or
  `selectedCollectionId`; BACK clears them. A custom gaming category's root is the UMD slot, its
  own Memory Card (`__category_card__`: junction games ∪ the games on its custom cards), its
  custom cards and Add Games — never loose game rows.
- **List arrangement:** every arrangeable list has a string key (`ListKeys`: `root:<category>`,
  `card:<platform>`, `all_games`, `favorites`, `catcard:<category>`, `collection:<id>`,
  `apps:<section>`). `list_settings` holds a list's own sort (no row = follow the global sort in
  `SortPreferences`), `list_items` its Custom order and pinned games, `umd_slots` the inserted
  game per gaming column. The pure rules live in `ListArrangement` (core-domain) and
  [`XmbLists.kt`](feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/viewmodel/XmbLists.kt);
  `XMBViewModel` owns the state and the writes. Pins for cards, custom cards and apps stay in
  their own tables' columns.
- **Settings hierarchy:** two levels. The Settings root is **Android Settings** followed by the L1
  sections (`SettingsSection`: Library, Emulators, Interface, Achievements, Media, System), which
  open as nested XMB items; their L2 rows come from `settingsSectionItems()` and route to settings
  screens through the `SETTINGS_SCREEN_ROUTES` allowlist in `SettingsNavHost`. A row behind a
  feature flag is filtered out there (Backup & Restore, via `FeatureFlags.BACKUP_RESTORE`), and
  `isSettingsRouteEnabled` stops a stale notification from opening it. The structure is pinned by
  `SettingsHierarchyTest`, and the README's Settings reference mirrors it.
- **D-pad LEFT:** *Left Backs Out* (`ControllerLayoutPreferences.leftBacksOut`) is an XMB-only
  preference — LEFT unwinds a crossbar folder or flyout through the same `backOutOfDrill()` ladder
  as BACK. `SettingsScaffold` never treats LEFT as back: on a row without inline actions it is a
  no-op, so LEFT cannot leave a Settings screen or a wizard page.
- **Input:** a gamepad dispatcher routes D-pad/A/B/Y to the focused layer. `hasBlockingOverlay`
  guards the main XMB navigation so input never drives the bar behind a dialog or overlay. Cursor
  movement itself lives in `core:core-navigation`, away from Compose.
- **Item icons** ([`XMBItemList`](feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/XMBItemList.kt)):
  games show a 144:80 landscape tile; apps with artwork show the same tile, apps without it show
  the launcher icon; folder rows use console / `sysicon_*` art.

## Icon system (`core:core-ui`)

All built-in category-pick icons live in one catalog,
[`CategoryIcons`](core/core-ui/src/main/kotlin/com/playfieldportal/core/ui/icons/CategoryIcons.kt):
each `iconKey` maps to an individual drawable (`catbar_*` column glyphs + `sysicon_*` console art,
all from the [xmb-menu-es-de](https://github.com/anthonycaccese/xmb-menu-es-de) theme).
`categoryIconFor()` resolves current and legacy keys; `CategoryIconGlyph` renders them. There is
**no sprite sheet and no hand-drawn (Canvas) icon code** — that was removed pre-launch.

The catalog is the *built-in* tier. What actually draws for a given icon slot is decided by the
render tiers: **user pick > theme icon > built-in**.

## Customization: icons, UI media, and motion

*Unreleased — landed after 1.2.1, ships in 1.3.0.* Four subsystems share one shape: a per-slot file store
under `filesDir/`, a limits object in `core:theme-kit` so the app and the desktop Studio agree on
the numbers, staged imports, and a DataStore stamp for cache invalidation.

**Custom icons.** [`CustomIconStore`](core/core-data/src/main/kotlin/com/playfieldportal/core/data/repository/CustomIconStore.kt)
keeps one file per slot at `filesDir/custom-icons/<slotKey>.<png|jpg|webp|gif>`. Keys come from
`CustomizableIcons` (theme slots plus `sysicon_*` console slots) and are used verbatim as
filenames, which makes key validation load-bearing — it is what stops a crafted key escaping the
directory. Limits live in `CustomIconLimits` (8 MB, 512 px, 120 frames, 10 s for GIFs). Stills are
decoded in the store; animated GIFs are read by Coil at render time, so an import must evict the
replaced path from the image cache through the `CustomIconCacheEvictor` seam or the old animation
keeps playing. Editing happens in a live fullscreen overlay above the XMB, and only the focused
row/column animates (gated on battery saver and `hasBlockingOverlay`).

**UI media.** `UiMediaStore` keeps `filesDir/ui-media/<slot>.<ext>` for eight slots: five menu
sounds, the boot and GameBoot clips, and the ambience track (`UiMediaSlot`). Per-slot duration and
byte caps come from `UiMediaLimits`, and a duration bound is always mandatory. `MenuSoundPlayer`
resolves a custom sample over its bundled `R.raw` default *inside the player*, so roughly ninety
call sites needed no change.

**A presentation's sound travels inside the presentation.** Boot Sequence and GameBoot are each ONE
thing: a switch, a built-in Compose-drawn sequence with its own bundled sound
(`bootDefaultAudioUri` / `gameBootDefaultAudioUri`), and one clip the user can replace it with,
which brings its own audio. That is why `resolveBootAudio` and `resolveGameBootAudio` are the same
two branches and why neither has an audio slot. There is no launch sound at all: opening an app is
silent, and opening a game is scored by GameBoot or by nothing. `pruneOrphans()` sweeps files and
preferences for retired slots on cold start and after a backup restore (`sound_launch`,
`boot_audio`, `gameboot_audio`).

**Volume.** `AudioChannel` names the eight launcher sounds; `AudioLevels` (core-ui) / `AudioLevelStore`
(core-data) resolve `master × channel` through one square-law taper, and **that function is the only
place the taper exists**. Master at 0 is the app's mute — the `sound_menu_enabled` boolean it
replaced is gone, along with its three independent declaration sites. Levels govern launcher chrome
only: the music and video players run at system volume, because scaling the user's own media by a
launcher setting is not what a master volume means. Every player reads gain as a *flow*, never a
snapshot, so a dragged slider is audible immediately; `MenuSoundPlayer` caches one because a
one-shot fired from the gamepad dispatcher cannot suspend.

**Ambience.** `AmbienceController` loops the assigned `AMBIENCE_AUDIO` clip under the XMB — the
first continuous sound the launcher has ever made, and the reason volume exists. Four gates must all
hold: assigned, audible, foregrounded, unsuppressed; plus a start condition on the boot sequence
finishing, so the chime plays and then the music. It **releases** rather than pauses on background
(a paused ExoPlayer still holds a codec) and handles audio focus fully, so it yields to Spotify and
to a call. The music and video players *push* suppression into it, because core-ui cannot see
feature-xmb and because audio focus is per-application — our own music player taking focus would
never make our own ambience yield.

**Motion wallpapers.** `MotionWallpaperBackground` composites a looping clip over an import-time
poster still. Video decodes through ExoPlayer; **GIF and animated WebP never construct a player at
all** — they render through a separate `AnimatedImageSurface` backed by Coil's animated decoder,
because ExoPlayer has no GIF extractor. Every freeze condition releases the decoder outright and
falls back to the poster rather than holding a paused player. `MotionLimits` (1080p, 60 s, 60 MB)
is shared with the Studio.

**Theme bundles.** The `.pfptheme` schema **v3** adds animated GIF icons, `sysicons/`, and a
`motion.<ext>` entry over v2. It is additive: readers never gate on `schemaVersion`, so a v3 bundle
still opens on a v2-era build and simply sees the v2 subset. `PfpThemeStore.saveCurrentLook()`
flattens the *applied look* — user picks having already won over theme icons — into a new bundle,
deliberately excluding the device-specific XMB layout adjustment. Applying a theme never clears
`custom-icons/`; it replaces only the middle render tier.

## Conventions

Touch helper bars are a separate input family from controller prompts. Use `TouchGesture` and
`TouchPromptBar` for touch gestures; use `ControllerPromptBar` for physical controller inputs, and
never mix both families in one helper row without first redesigning the touch tap glyph — a screen
shows one family at a time, chosen by `resolvedShowTouchButton`, not both at once. Options uses a
vertical kebab (`⋮`) rather than the old horizontal ellipsis convention. Photo, Video and Music
have all had their touch pass; remaining section screens should follow the same shape. That rule is
about the *helper row* (which gestures a finger is told about, and which buttons a pad is); state a
finger can act on is not part of the choice — the browser's now-playing strip is drawn in both input
modes, because what it says is worth reading either way.

Options menus follow one set of rules. Every per-item menu draws through `PspContextMenuOverlay`
(rows are `PspMenuRow`; `opensMenu` draws the `›`), and `PspMenuNav` in `core-ui` owns movement
(no wrap), Back-climbs / Triangle-closes and the menu sounds, so no screen decides those itself.
Rows come from pure builders (`GameContextMenuItems`, `MemoryCardContextMenuItems`,
`MediaContextMenuItems`, `CategoryMenuItems`, `AppMenuItems`). Destructive actions are red, last,
and confirm through `PfpConfirmModal` (opening on Cancel). A setting is a row with a value, a toggle
is one label with On / Off, and Triangle and long-press open the same menu everywhere, including
Settings rows (`SettingsRow.onLongPress`).

**Switching from finger to pad is a press, not a free action.** A list a finger can scroll keeps its
cursor hidden while the finger owns it, and the *first* controller press afterwards is a revival
press: it parks the cursor on the visible row nearest the middle of the window — the content the
user was looking at — and, when directional, is spent doing that rather than also moving. It has to
be handled on the source transition (the entry point for controller input) and not inside the
directional handler, because Options, Search, Open and Back are transitions too, and a press that
acts on the stale pre-scroll row drags the list back to it. `SettingsScaffold`,
`MusicBrowserScreen` and `MusicTrackPicker` all follow this rule; the re-anchor itself is
`nearestToViewportCentre`. Revealing the cursor is also the moment to take the scroll mutex
(`listState.scroll { }`), so a fling the finger left running cannot carry the row out from under a
cursor that just landed on it.

**A viewport position is a fact about one frame, and must never enter the navigation engine.** The
two terms of that rule are *row centres*, not row tops — the window is compared against the middle
of the viewport, so a row's top hands the win to the row below as soon as the centre falls in its
lower half — and they are held by the screen's owner, replaced wholesale on every layout, never
merged into `NavigationEngine`'s geometry map. That map persists and is what vertical traversal
sorts by, so a single flick would leave every row ever seen holding a position from a different
scroll offset and the order a press walks would be two scroll positions interleaved. A list whose
registration order already *is* its visual order should therefore report no geometry at all and let
spec §7.2 supply the order.

**Revealing the focused row is a one-row scroll.** A list whose cursor the D-pad walks uses
`revealRow`, not `LazyListState.scrollToItem` on its own. `scrollToItem` aligns the row to the *top*
of the viewport: one row of travel stepping up past the first visible row, a whole page stepping
down past the last one, because the row that was below the window is thrown to the top and every row
between moves with it — so a held direction lurches downward where it glides upward. The minimal
reveal seats a downward one-row step against the bottom edge instead. A move of more than one row
(a sort that recycles the list, a query that refilters it) is not a cursor step and stays a jump.

**The two built-in players are one instrument.** `VideoPlayerScreen` and `MusicPlayerScreen` share
the same transport pieces from `feature-xmb/.../ui/media/MediaTransport.kt` — `MediaScrubBar`,
`Scrubber`, `TransportButton` — and the same controller ladder: **A** play/pause, **◀/▶** seek
∓10s, **L1/R1** previous/next, **Y** options, **B** close. A binding added to one belongs in the
other. Seeking commits on release, not per pixel: dragging a decoder position on every touch event
stutters it for the length of the drag. Their *layouts* diverge on purpose: the video player's
centre is the content, while the music player's centre is the visualizer and must stay empty, so
its album art is a 62dp thumbnail rather than a hero tile.

**One clock, one array, N draws.** The music player's visualizer
(`feature-xmb/.../ui/visualizer/`) draws a live preview of every field in its picker strip at the
same time as the hero. That is only affordable because it is **one** simulation drawn many times,
and the rule is easy to break by accident:

- **One `withFrameNanos` loop**, owned by `VisualizerHost`. Not `rememberInfiniteTransition`, and
  never one per tile — that would be one animation subscription and one recomposition scope per
  preview.
- **One particle array per field**, advanced once per frame in the host. Each tile draws the first
  N entries scaled into its own bounds, so simulation cost is O(1) in tile count and only draw
  scales.
- **The frame is read in the draw phase**, inside the `Canvas` lambda. Read it from a composable
  body instead and the whole player recomposes at 60Hz.
- **One pre-rendered sprite**, `drawImage`d — not N radial-gradient `drawCircle` calls, each of
  which rebuilds its shader.

If you are adding motion to the music player, add it to `VisualizerHost`. A second animation loop
is the single change most likely to undo all of the above.

Renderers implement `PfpVisualizer` and receive a `VisualizerFrame(timeNanos, energy, bands)`.
`energy` is synthetic today (three incommensurate sines seeded from the track id) and `bands` is
always null, because playback is a `MediaPlayer` with no audio tap; the seam is shaped so an
ExoPlayer migration can supply RMS and an FFT **without changing a renderer**. The one contract
every renderer must honour is that it consumes `energy` in at least one term — the envelope decays
to a floor on pause, and a field that sails on through a pause is the clearest possible tell that
nothing is listening.

- **MVVM:** ViewModels own state (`StateFlow<UiState>`); composables are stateless and driven by
  state + callbacks.
- **Repositories** are the boundary between features and the data layer; features never touch DAOs
  directly.
- **Pure logic leaves Android.** Navigation math, launch-ladder precedence, scan policy and the
  theme/limits objects are plain Kotlin, so their tests need no device.
- **Seams over platform mocks.** Wall clocks (`RescanClock`, `LaunchClock`), cache eviction
  (`CustomIconCacheEvictor`) and scan sources (`ScanSourceResolver`) are injected interfaces, so
  timing and IO boundaries are drivable from unit tests.
- **Long-running work** (scans, artwork fetches, achievement updates) runs off the main thread and
  surfaces progress in the launcher's own notification panel (RUNNING / EARLIER) rather than the
  Android shade.

## Where decisions are recorded

- **`docs/adr/`** — architecture decision records. Read these before proposing a change that
  reverses one.
- **`CONTEXT.md`** — the domain glossary. Terms defined there (Memory Card, ROM survey, icon slot,
  render tier, applied look, UI media slot) are used precisely in this document.
- **`docs/plans/README.md`** — the plan index: what shipped, what did not, and why. Implemented
  plans are deleted, so the index is the record.
- **`CHANGELOG.md`** — user-facing history, with unreleased work kept separate.

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
