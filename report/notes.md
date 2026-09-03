# Session notes — controller prompts refactor & pre-merge audit

Branch: `ui-helper-buttons` · Date: 2026-09-01 · Repo: PlayFieldPortal

> **Historical snapshot.** Written before the hardening pass that landed after it.
> The merge-state section below (§1.7) describes a tree that has since changed:
> the working tree is no longer clean, several files flagged here as dead
> (`LibrarySettingsScreen`, `PlatformSdCardRow`, `BuiltInThemes`) have since been
> deleted, and the dependency findings (`accompanist-systemuicontroller`,
> `compose-compiler` pin) were already fixed at the time of writing. The evidence
> sections remain accurate for the commits they describe.

Everything in this session that was delivered as chat output rather than as a file.
The formatted audit lives beside this as [`merge-audit.html`](merge-audit.html), also
published at <https://claude.ai/code/artifact/e4e916b5-d6fe-49a6-8243-8d73d79a59a9>.

Contents:

1. [Audit — verified evidence](#1-audit--verified-evidence)
2. [Corrections made to the automated sweep](#2-corrections-made-to-the-automated-sweep)
3. [Outstanding / pending](#3-outstanding--pending)
4. [Appendix A — original Compose audit](#appendix-a--original-compose-audit-controllerbuttonglyphkt)
5. [Appendix B — the refactor plan as approved](#appendix-b--the-refactor-plan-as-approved)
6. [Appendix C — commit message for 14e9e8c](#appendix-c--commit-message-for-14e9e8c)

---

## 1. Audit — verified evidence

Raw backing data for the report's findings. "Verified" below means I reproduced it
directly — read the code, diffed against git history, or reproduced compiler/Gradle
output — rather than taking a scan's word for it.

### 1.1 The regression this branch introduced

The Phase 0 collapse merged `BUTTON_Y` and `LONG_PRESS` into `OPEN_CONTEXT_MENU`
on the premise they always meant the same thing. One site disproves it.

```
# current — feature-xmb/.../viewmodel/XMBViewModel.kt:4167-4169
GamepadAction.OPEN_CONTEXT_MENU -> resetXmbLayoutAdjust()
GamepadAction.OPEN_CONTEXT_MENU -> toggleXmbLayoutSliders()   # unreachable

# before — git show 128b4bc:.../XMBViewModel.kt:4175-4176
GamepadAction.BUTTON_Y   -> resetXmbLayoutAdjust()
GamepadAction.LONG_PRESS -> toggleXmbLayoutSliders()
```

`toggleXmbLayoutSliders` is still reachable from the on-screen touch control
(`XMBShell.kt:219`), so the feature is degraded rather than gone — only the
controller route is lost. The honest fix is a distinct action, not a re-pairing,
because the two behaviours are genuinely different.

The second duplicate, `AppDrawerViewModel.kt:288`, is benign: the original had
`LONG_PRESS` and `BUTTON_Y` both calling `openAppMenuForSelected()`, so collapsing
them produced two identical branches. Delete the second.

**How this got past me.** Both were `w:` lines on every build. My verification during
the refactor filtered compile output to `^e:` and `BUILD`, so a green build and 1069
green tests were silent on them. No test could have caught either — the collapse was
semantically wrong in a way only the compiler noticed.

Reproduce:

```bash
./gradlew :feature:feature-xmb:compileDebugKotlin --rerun-tasks --console=plain 2>&1 \
  | grep "^w:" | grep -E "Duplicate branch|always"
```

```
AppDrawerViewModel.kt:288:13  Duplicate branch condition in 'when'.
XMBViewModel.kt:2066:17       Condition is always 'true'.
XMBViewModel.kt:2066:49       Condition is always 'true'.
XMBViewModel.kt:4169:17       Duplicate branch condition in 'when'.
XMBViewModel.kt:4612:37       Condition is always 'true'.
```

The two always-true conditions are both `cat.isGamingCategory && cat.id != BuiltInCategory.GAMES`.
Either the predicate is redundant or the intended check was something else — needs a
human read, not a blind deletion.

### 1.2 The AGP chain

This is the single most consequential pre-existing finding, and the four flags that
look independent are one chain. AGP states it itself:

```bash
./gradlew :app:tasks -Pandroid.debug.obsoleteApi=true --console=plain
```

```
WARNING: API 'applicationVariants' is obsolete and has been replaced with
'AndroidComponentsExtension'. It will be removed in version 10.0 of the Android
Gradle plugin. The legacy variant API is disabled by default in AGP 9.0, but can
be re-enabled by adding  android.newDsl=false  to this project's gradle.properties.

REASON: The 'kotlin-android' plugin is currently calling this deprecated API.
Please migrate this project to built-in kotlin.
```

The chain:

```
android.builtInKotlin=false
  → org.jetbrains.kotlin.android plugin is used instead
    → that plugin calls applicationVariants / testVariants / unitTestVariants
      → legacy variant API, off by default in AGP 9
        → android.newDsl=false required to re-enable it
          → which is also what keeps kotlinOptions {} compiling in 16 modules
```

Everything in that chain is removed in AGP 10. Migrating `builtInKotlin` is the head;
the `kotlinOptions` → `compilerOptions` rewrite across 16 build scripts follows from it
rather than standing alone. Confirmed: the obsolete variant API is **not** called from
this repo's own build scripts — it comes from the applied plugin.

Additional deprecated flags in `gradle.properties`:

```
android.enableAppCompileTimeRClass=false
android.r8.optimizedResourceShrinking=false
android.usesSdkInManifest.disallowed=false
android.sdk.defaultTargetSdkToCompileSdkIfUnset=false
android.defaults.buildfeatures.resvalues=true
```

`r8.optimizedResourceShrinking=false` is load-bearing, not incidental: it protects the
`Resources.getIdentifier("sysicon_$safe", ...)` lookup at `XMBItemList.kt:1229`.
Optimised shrinking would strip those drawables because nothing references them
statically. Fixing the lookup and the flag is one job.

KSP config conflict — builds today, but the pairing is contradictory:

```
libs.versions.toml:10   ksp = "2.2.10-2.0.2"   # KSP2 generation
gradle.properties:11    ksp.useKSP2=false      # forces KSP1
```

### 1.3 Compose / Kotlin counts (measured, not estimated)

```
collectAsState()             33 uses
collectAsStateWithLifecycle  16 uses
lazy item builders           12 total, 11 without a key=
androidx.compose.ui.platform.LocalLifecycleOwner   5 sites (deprecated since lifecycle 2.8)
Enum.values() instead of .entries                 11 sites
```

Unkeyed lazy builders (counting `itemsIndexed` and multi-line forms; a naive
`grep "items("` undercounts these to 6):

```
XMBItemList.kt:441            GamePickerScreen.kt:150,182
GameDetailScreen.kt:407,577   ArtworkStudioScreen.kt:367
AppDrawerScreen.kt:601        AppDetailScreen.kt:435
DetailContextMenu.kt:120      PspContextMenu.kt:129
ColorSchemePickerOverlay.kt:114   PlatformSdCardRow.kt:89
```

**Root cause of the `collectAsState` spread** — the canonical "how to write a screen in
this codebase" doc comment teaches the wrong call:

```
core-ui/.../preview/PfpPreviewWrapper.kt:30
    val state by viewModel.uiState.collectAsState()
```

Fixing that one KDoc block is higher leverage than any individual call site, because it
stops the pattern reproducing.

**Kotlin 2.2 specific** — ~25 warnings of *"This annotation is currently applied to the
value parameter only, but in the future it will also be applied to field."* That is the
2.2 change to default annotation use-site targets on constructor properties. Every
Hilt-injected ViewModel emits it. Resolve project-wide with
`-Xannotation-default-target` rather than annotating 25 sites by hand.

### 1.4 Main-thread IO (verified chain)

```
XMBViewModel.launchGameDirectly()          :6834
  → viewModelScope.launch { }              :6838   (Dispatchers.Main.immediate)
    → getProfilesForPlatform()             :6877
      → getInstalledProfiles()             EmulatorProfileRepository.kt:48 (non-suspend)
        → loadPersistedProfiles()          :120
          → file.readText() + decodeFromString
```

Also reached from `XMBViewModel.kt:5194` and `LibraryManagerViewModel.kt:460`.
Nothing in the signature warns a caller that it blocks.

### 1.5 Dead code — confirmed by index comparison

Method for resources: build one index of every `R.drawable.*` / `@drawable/*` reference
across all `.kt` and `.xml`, build another of every declared drawable, and diff. A
per-file grep loop times out on this repo; the index approach takes seconds.

Method for declarations: index all 1,726 top-level declarations across the 659 non-`build/`
`.kt` files, run a single occurrence pass, and join. Exactly one occurrence repo-wide means
declaration site only. Each hit then re-verified individually with word-boundary search
including `.xml`, `.gradle.kts` and `AndroidManifest.xml`.

#### Whole dead files

| File | Lines | Note |
|---|---|---|
| `feature-xmb/.../ui/PlatformSdCardRow.kt` | 354 | Legacy category bar; `DESIGN.md` records it as superseded |
| `feature-settings/.../ui/LibrarySettingsScreen.kt` | 172 | Superseded by `LibraryManagerScreen` |
| `feature-themes/.../BuiltInThemes.kt` | — | Duplicated definition, see below |

Verification (`kt=1` means only the declaring file mentions the name):

```
PlatformSdCardRow        kt=1  md=2
BuiltInThemes            kt=1  md=1
LibrarySettingsScreen    kt=1  md=0
LibrarySettingsViewModel kt=2  md=2   ← own file + the dead screen (cascade)
```

**`LibrarySettingsScreen` is safe to delete, and knowing why matters.** It is not an
unreachable feature users are missing — `LibraryManagerScreen` owns the `settings_library`
route at `SettingsNavHost.kt:81`, plus `settings_windows_games` and `settings_import_pc`.
The old screen is an orphaned predecessor. Its ViewModel dies with it.

**`BuiltInThemes.kt` is the interesting one — a duplicated definition, not an abandoned
feature.** Its KDoc says it is *"seeded once by `DatabaseInitializer`"*. It is not. But
theme seeding did **not** regress: `DatabaseInitializer.kt:20` carries its own
`private val BUILTIN_CLASSIC_BLUE` with the same `builtin_classic_blue` id, and seeds
that. So the app ships a built-in theme correctly while the file that reads as the
authoritative source of it is orphaned. Anyone editing `BuiltInThemes.kt` to change what
ships would see no effect. Delete the orphan and fix the seeder's comment.

#### Unreferenced top-level declarations (15)

```
GameIconView.kt:395     CartridgeIcon()          @Composable, public
GameIconView.kt:336     PspRectangleIcon()       @Composable, public
XMBItemList.kt:385      CenterLockedColumn()
XMBItemList.kt:278      SiblingIcon()
WizardRows.kt:262       WizardCheckboxRow()      @Composable, public
ArtworkSettingsScreen.kt:393   credentialFieldColors()
PFPTheme.kt:24 (domain) enum ThemeSoundEvent + all 6 constants
PFPTheme.kt:96 (ui)     object PFPThemeTokens
StorefrontColors.kt:89  LocalStorefrontColors    CompositionLocal
SettingsScaffold.kt:130 LocalSettingsScrollToTop
SettingsScaffold.kt:158 SettingsBg   :163 SettingsSelectedBg
EmulatorProfileEditorScreen.kt:28 EditorText   :32 EditorBorder
InstalledAppPicker.kt:35 PickerScrim
```

Leave `ThemeSoundEvent` alone — `DESIGN.md:422` tracks it as pending work ("wire
`SoundPool` player triggered by `ThemeSoundEvent`"), so it is a planned feature's
placeholder, not residue. It is also the only dead enum; every other single-occurrence
enum constant cleared as a false positive (`WallpaperPreset.FULL_HD`,
`SimulatedThermal.SEVERE`, `LibraryProviderFilter.RETRO` are cycled via `entries`;
`GameContentType.MUSIC_APP` resolves dynamically through `fromName()`).

#### Properties written but never read (~37)

Room `@Entity` columns and `@Serializable` classes excluded. Concentrated in UI state:
`XMBUiState.mediaUri` (3 write sites), `.librarySetupComplete`, `GameDetailUiState.hasManual`
and `.mediaUris`, `InitialSetupUiState.retroArchDetecting` (5 sites),
`EmulatorsSettingsUiState.isResetting`, `CategoryManagerUiState.pendingIsGamingCategory`,
`MusicPlayerState.isPrepared`. Plus `PFPColors.backgroundOverlay`/`.selectedItem`/`.categoryBar`
and all three `ControllerDevice` capability flags.

Declared and never even assigned: four `ArtworkScrapePreferences` flows,
`ArtworkFolderRepository.storageMode`, `NavigationEngine.activeContextId`,
`PcLauncherAdapter.requiresIntegerId` (overridden twice, read never),
`ThemesSettingsUiState.installedThemes`, and several computed `val`s.

Caveat: several sit on `data class`es (`SsLookupDiagnostics`, `ControllerDevice`,
`RaProgressEntry`, the `RomRootScanRunner` report) whose generated `toString()` could carry
a field into a Timber log with no identifier reference. A grep for those types in log
statements found none, but identifier search cannot fully close that path — treat the
diagnostics-flavoured ones as *likely* rather than certain.

#### Categories that came back clean

- **No orphaned Gradle modules.** All 21 have a dependent. `:studio` has none by design —
  it is a Compose Desktop application entry point. `:feature-achievements` reaches `:app`
  transitively via `:feature-settings` and `:feature-xmb`.
- **No unused `res/values` resources.** Only `:app` has a `values/` dir, holding
  `app_name` and `Theme.PFP`, both referenced from the manifest.
- **No commented-out code blocks.** Three candidates of 5+ comment lines were prose
  explaining SQL and intent syntax.

#### Drawables

Genuinely dead (12 files):

```
core-ui/src/main/res/drawable/ic_help_button_{a,b,x,start}_{ps,switch,xbox}.xml
```

These are a third generation of the controller-glyph system, predating both
`btn_hint_*` (deleted this branch) and the current `ctl_*` set. They key art by
*printed letter plus family* — `a_ps`, `b_xbox` — which is exactly the modelling
mistake this branch removed. Deleting them closes the loop.

**False positives worth recording** — these four look orphaned to any static scan and
are not dead:

```
sysicon_allgames  sysicon_default  sysicon_favorites  sysicon_settings
```

They are resolved at runtime by string name via `Resources.getIdentifier` in
`XMBItemList.kt:1229-1232`. This is the reason to never delete an Android resource on
grep evidence alone.

Also dead:

- `accompanist-systemuicontroller` 0.34.0 — declared at `app/build.gradle.kts:107`,
  imported by zero source files, and itself officially deprecated in favour of
  `enableEdgeToEdge()`, which `MainActivity` already calls.
- `compose-compiler = "1.5.14"` at `libs.versions.toml:12` — the only unreferenced
  version key in the catalog. No `version.ref`, no `composeOptions`, no
  `kotlinCompilerExtensionVersion`. Under Kotlin 2.x the Compose compiler ships with
  the `kotlin-compose` plugin. Actively misleading rather than merely inert: it reads
  as a deliberate pin, so anyone auditing Compose versions will try to reason about why
  it is held at a 2023 number.
- `compose-multiplatform = "1.6.11"` is **not** dead — `:studio` applies it. Its
  justifying comment is stale, though (see §1.6).

### 1.6 Documentation drift (all verified against source)

| Fact | Docs say | Actually | Location |
|---|---|---|---|
| Room schema | v35 | **40** | `ARCHITECTURE.md:51`, `README.md:694`, `README.md:826` |
| Kotlin | 2.0.0 | **2.2.10** | `README.md:691` |
| Gradle | 8.14.5 | **9.5.0** | `README.md:704`, `README.md:724` |
| AGP | 8.10.1 | **9.3.1** | `README.md:704` |

Source of truth: `PFPDatabase.kt:106` → `version = 40`; `libs.versions.toml:2-3`;
`gradle/wrapper/gradle-wrapper.properties`.

The schema number is the dangerous one. Every other wrong number costs a contributor
setup friction. That one invites someone to write `MIGRATION_35_36` against a database
already five versions past it, in a schema the project documents as never-destructive.

Stale prose:

- `CONTEXT.md:36` — "The **planned** deep module that owns the ROM survey". `LibraryScanner`
  is implemented and injected in five places (`LibraryRescanCoordinator`, `RescanTriggerBus`,
  `LibraryManagerViewModel`, `RomRootScanRunner`, `XMBViewModel`).
- `docs/adr/0001-library-scanner-owns-rom-survey.md:3` — still "Accepted for
  implementation", and its Context section describes the pre-refactor state in present tense.
- `libs.versions.toml:32-33` — "1.6.11 is the newest release that supports Kotlin 2.0.0".
  The project is on 2.2.10, so the rationale for the Compose Multiplatform pin is now
  false and blocks anyone from re-evaluating it.

KDoc coverage is inverted — the shared modules are the least documented:

```
core-data       136 undocumented public declarations
core-ui          33
core-domain      23
theme-kit         5
core-common       0   ← the house standard already exists
core-navigation   0   ←
```

`theme-kit`'s five are the sharpest miss: it is the only module shared with the desktop
Studio, so it is a genuine cross-application contract.

Structural gaps: `docs/adr/` holds exactly one record and it is stale, while
`ARCHITECTURE.md` documents several decisions that read like ADRs without being filed as
such (never-destructive migrations, `:studio` must never take an Android dependency, the
flavour split). `CLAUDE.md` is 15 lines containing one hardcoded `C:\Users\johnn\` path
and nothing about the module graph, conventions, or the deliberate heavy-comment style.
No module-level README exists for any of the 21 modules except `discord-native`.

### 1.7 Merge state

Clear:

- Both flavours compile (`:app:compileFullDebugKotlin`, `:app:compileLiteDebugKotlin`).
- 1069 tests pass repo-wide, including 38 new ones for the controller mapping.
- Working tree clean; 5 ahead, 0 behind `main` — no rebase needed.
- CI (`.github/workflows/unit-tests.yml`) runs `./gradlew test` for every module except
  `:app` and `:discord:discord-native`, so the new `core-domain` and `core-ui` suites are
  covered automatically — including the `core-ui` test source set added this branch.
- The asset purge in `1ad5945` is safe: it removed only the extracted vendor trees under
  `assets/UI` and kept the three source ZIPs. All 72 `ctl_*` drawables are intact.

Risks:

- **The art provenance rules live outside the repo.** `Controller_Helper_Icon_Mapping.md`
  — which pack variant each of the 72 drawables came from, the `Buttons Solid` vs
  `Buttons Full Solid` distinction, the Pro D-Pad rule — exists only in Downloads. The
  repo preserves a summary as a comment header in `ControllerButtonGlyph.kt`, but without
  per-file source paths. That distinction is not recoverable by inspection: the two
  variants are byte-identical for unlettered art and differ only on lettered buttons.
  Recommend committing it to `docs/`.
- No ktlint, detekt, or Android Lint configuration. Given both blockers came from
  compiler warnings that a green build hid, the cheapest guard is failing CI on new
  warnings in touched modules — not adding a new tool.
- Three modules have no test source set: `:app`, `:discord:discord-native`,
  `:feature:feature-social`. Note that `ProvideControllerPrompts` — the Hilt entry point
  wiring the whole prompt system — lives in `:app` and is exercised by nothing in CI.
- Every visual change on this branch is unverified on a device.

---

## 2. Corrections made to the automated sweep

Recorded because both were plausible and would have shipped as fact if taken at face value.

**"`kotlinOptions` is removed in AGP 9."** Wrong. It is deprecated with removal in AGP
**10** — the build demonstrably works on AGP 9.3.1. AGP's own wording is *"It will be
removed in version 10.0 of the Android Gradle plugin."* The urgency is real but dated,
not immediate.

**"`EmulatorProfileRepository.initialize()` is a classic main-thread IO hazard."** Wrong
for that call site. Its only caller is `PFPApplication`, whose `appScope` is
`CoroutineScope(SupervisorJob() + Dispatchers.IO)` — so it is already off the main
thread. The defensible version of the finding is narrower and separately confirmed: the
**non-suspend** `getProfilesForPlatform()` reads and parses a file and is called from
`viewModelScope` during game launch (§1.4). The repository's real problem is that its
contract does not declare its dispatcher — it is safe today by coincidence of the call
site, not by construction.

**Lazy-list count overstated as "~40 items() calls, 1 keyed."** The true figure is 12
builders, 11 unkeyed. The scan's specific file:line list was accurate; only the
denominator was wrong.

---

## 3. Outstanding / pending

- **Device verification of this branch.** Four footers changed layout (App Drawer command
  bar, XMB context hint, wizard footer, Artwork Studio hints). Glyph scale against label
  baselines, and whether the wizard's revised footer reads as intended, are unconfirmed.
  This is the only part of the branch nothing in this session could check.
- **Cross-check worth noting.** `PlatformSdCardRow.kt:89` appears in both the unkeyed-lazy-list
  finding (§1.3) and the dead-file list (§1.5). If the file is deleted, that finding drops
  from 11 sites to 10.

### Suggested order of work

Not a plan, just the dependency order the findings imply:

1. The `XMBViewModel.kt:4169` regression — it is the only merge blocker.
2. Delete `AppDrawerViewModel.kt:288`'s redundant branch while in the same area.
3. Fix `PfpPreviewWrapper.kt:30`'s doc comment *before* sweeping the 33 `collectAsState`
   call sites, so the pattern stops reproducing.
4. Correct the four wrong version numbers in `README.md` / `ARCHITECTURE.md` — the Room
   schema one first.
5. Dead code — ~800 lines across three files plus 15 declarations. Independent of
   everything else, so it can go whenever.
6. The AGP chain. Largest, has a hard deadline (AGP 10), and is best done as its own
   branch with nothing else in it.

---

## Appendix A — original Compose audit (`ControllerButtonGlyph.kt`)

The review that started this work, run against the 6-point checklist.

| # | Status | Finding |
|---|---|---|
| 1 | PASS | `modifier: Modifier = Modifier` present and correctly positioned |
| 2 | PASS | `searchActive` is genuinely local; the rest is hoisted to the ViewModel |
| 3 | PASS | Grid `items()` keyed |
| 4 | **FAIL** | `AppDrawerScreen.kt:103` used `collectAsState()` |
| 5 | PASS | No IO in composition |
| 6 | PASS | State classes stable |

The art and the drawable tables were correct — I hash-matched every `ctl_*` drawable
against the source packs. The bug was in the two layers above them.

**The actual defect.** `faceButtonFromLabel` had unreachable branches:

```kotlin
"A", "CROSS", "B"   -> FACE_SOUTH
"B", "CIRCLE", "A"  -> FACE_EAST   // "B" and "A" already matched above
"X", "SQUARE", "Y"  -> FACE_WEST
"Y", "TRIANGLE", "X"-> FACE_NORTH  // "Y" and "X" already matched above
```

Kotlin `when` takes the first match, so the drawer footer rendered
`Ⓐ Back  Ⓐ Launch  Ⓧ Options  Ⓧ Search`. The deeper problem is that the function
*cannot* be made correct — `"B"` is the east button on Xbox and the south button on a
Switch. A printed letter is not a stable key; the semantic position is. Reordering the
branches would have fixed the symptom and left the defect.

Supporting findings: `size` was accepted and never applied (PS art is 480×480, Xbox and
Switch are 128×128, so families rendered at wildly different scales in one footer);
`drawableFor` used `getValue` and threw on family-exclusive inputs; `isShoulder` was a
dead parameter; `contentDescription = icon.name` announced "FACE UNDERSCORE SOUTH".

---

## Appendix B — the refactor plan as approved

The plan that produced commit `14e9e8c`. Phases 0–5 all landed.

**The key realisation.** The Confirm/Back and X/Y settings were *already* wired to
behaviour — `ControllerLayoutRepository.applyLayout` rewrites the real `GamepadMappings`
on every settings write. Only the footers were hardcoded. So this was never "plumb a
setting into the UI"; it was "invert an existing table". Behaviour reads keycode →
action; chrome needs action → keycode → position → art. Deriving both from one table is
what makes drift structurally impossible rather than a thing to remember.

**Four incompatible footer dialects existed:**

| Site | Rendered | Honoured display type | Honoured remap |
|---|---|---|---|
| `AppDrawerScreen` command bar | `ControllerIcon` art | yes | no |
| `WizardScaffold` footer | literal `"✕"` / `"○"` | no — PS only | no |
| `ArtworkStudioScreen` | Xbox letters in prose strings | no | no |
| `ContextMenuHint` | `FACE_NORTH` art | yes | no |

The last row is the subtle one: position-correct but *action*-incorrect. Under
`XYLayout.SWAPPED` the options action binds to the west face, so a hint that always
draws north is wrong. Anything keyed on a fixed position rather than an action has this bug.

**Phase 0 — collapse the action vocabulary.** `GamepadAction` carried four names for two
secondary actions, and `DEFAULT_BINDINGS` disagreed with the settings rebuild about which
name X and Y emitted. A button therefore changed meaning the first time a user opened
controller settings — which is why the App Drawer's search toggle died permanently after
any settings change. `OPEN_TASK_TRAY` outlived the task tray itself; every screen had
already repurposed it to sort, as `XMBViewModel.kt:4417` documented.

**Phase 1** — move `ControllerIcon` to core-domain (it describes hardware, not UI).
**Phase 2** — `Int.toControllerIcon()` and `GamepadMappings.iconFor(action)`, deliberately
skipping Enter / hardware Back / D-pad-centre so a keyboard key never lands in a
controller footer.
**Phase 3** — `ControllerPrompt` / `ControllerPromptBar` in core-ui, delivered by
`LocalControllerPromptStyle`, provided once at the app root.
**Phase 4** — migrate all four dialects; delete `ContextHintGlyph` and
`XMBUiState.controllerDisplayType` rather than leaving a second path.
**Phase 5** — prose sweep for hardcoded `△` references.

**Judgment calls made during implementation:**

- **`compositionLocalOf`, not `staticCompositionLocalOf`.** I wrote the static variant
  first and it was wrong: the value flips from default to real on every cold start when
  DataStore resolves, and a static local would recompose the entire app tree at that
  moment. The non-static one invalidates only the ~5 footers that read it.
- **Persisted-mapping migration.** Renaming the constants would have made every saved
  `GamepadMappings` JSON unparseable; `runCatching` would have swallowed it and silently
  reset the user's layout. `GamepadActionSerializer` writes current names and reads legacy
  ones.
- **`applyLayout` extracted** into `gamepadMappingsFor(confirmBack, xy)` in core-domain —
  that is what made the rebuild rule testable at all; it was previously stuck behind DataStore.
- **Wizard footer** keeps its PSP colour language on the labels (`WizardEnterBlue` /
  `WizardBackRed`) but the glyphs are now the user's own pad. The literal `✕`/`○` are gone.
- **ArtworkStudio** changed most: three prose strings became prompt rows. Its `◄ ►` and
  `D-Pad` hints were dropped rather than made glyphs, since those are directional
  affordances, not remappable actions.

**Known gap in the plan, now realised.** Phase 0 assumed `BUTTON_Y` and `LONG_PRESS`
always meant one thing. See §1.1 — one site disproved it, and the plan had no step for
auditing sites where the paired branches did *different* work.

---

## Appendix C — commit message for `14e9e8c`

**Title**

```
Rework controller button prompts to follow the user's controller settings
```

**Description**

```
- Added a per-family controller glyph set covering face buttons, D-pad, bumpers, triggers, sticks, and utility inputs, sourced from the PlayStation, Xbox Series, and Switch 2 asset packs; the Switch 2 pack is added to assets/UI alongside the two already tracked there.
- Introduced ControllerIcon in core-domain to identify buttons by physical position rather than printed letter, with per-family drawable and printed-label tables in core-ui that resolve to null for inputs a pad does not have.
- Collapsed GamepadAction's duplicate secondary actions: BUTTON_Y and LONG_PRESS become OPEN_CONTEXT_MENU, and BUTTON_X and OPEN_TASK_TRAY become CHANGE_SORT, removing the paired when-branches that consumers carried across the XMB, App Drawer, settings, video, photo, and artwork screens.
- Aligned DEFAULT_BINDINGS with the settings-driven rebuild so the X and Y buttons dispatch the same actions before and after a controller setting is changed; previously the App Drawer's search toggle stopped responding once the mapping table was rewritten.
- Extracted the binding rebuild out of ControllerLayoutRepository into gamepadMappingsFor(), and added GamepadMappings.iconFor() to resolve an action back to the button currently bound to it, skipping Enter, hardware Back, and D-pad centre.
- Added a GamepadAction serializer that writes current names and reads the retired ones, so previously saved mappings keep parsing instead of falling back to defaults.
- Added ControllerPrompt and ControllerPromptBar in core-ui, supplied through LocalControllerPromptStyle and provided at the app root from the layout and mapping repositories, so prompts render from the same table the input handler reads.
- Rebuilt the App Drawer command bar, XMB context-menu hint, setup wizard footer, and Artwork Studio hints on that API; the drawer no longer takes a displayType parameter and XMBUiState no longer mirrors the controller display type.
- Normalized glyph rendering to an explicit size, since the source packs ship at 480px for PlayStation and 128px elsewhere, and marked glyphs decorative so their action label carries the accessibility semantics.
- Removed the ContextHintGlyph module and its btn_hint_* drawables, which served Xbox art for the Nintendo display type.
- Relabelled the X/Y layout setting as Options and Sort in place of the removed Task Tray, and dropped hardcoded △ button references from settings and menu copy.
- Added unit suites covering the binding layouts, the action-to-position lookup, the per-family art tables, and the combined settings-to-drawable resolution; enabled unit tests for core-ui.
- Removed the GENERIC controller display type and updated the affected settings test.
```
