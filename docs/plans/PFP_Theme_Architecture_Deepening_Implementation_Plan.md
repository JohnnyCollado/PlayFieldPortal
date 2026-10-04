# Play Field Portal — Theme Architecture Deepening: Implementation Plan

Turn the theme system's shallow, copied modules into a few deep ones: one module that answers
"which tier wins" for icons and media, one set of theme-render rules the launcher and the Theme
Studio both run, one table of theme parameters behind apply / reset / save, a codec that keeps its
folder split to itself, one media import gate, and a theme-look module lifted out of
`XMBViewModel`. Two live bugs found by the review are fixed first.

**Status: implemented in one pass (2026-10-03), all passes; see §13 for what was decided where the
plan left a question open. Effort XL.**
**Source:** the architecture review of `final-polish` @ `9ef87f60` (report:
`%TEMP%\architecture-review-20261003-theme.html`, candidates 1–7). Every claim below was re-checked
against the code; the one that did not hold up is in §12.

> **Working rules for the implementing session**
> - Tests first: write each task's tests (§6) against the unchanged code, see them fail (or, for a
>   pure move, see them pass on the old code and keep passing), then implement.
> - Do not run Gradle unless the user asks. Hand the user each task's command block (§11).
> - Done = the task's tests green, the existing suites in the touched modules still green, and no
>   new Kotlin warnings.
> - Launcher/Studio parity: any change to a theme parameter, cap or render rule lands on both sides
>   in the same task.
> - Remove orphaned code at the end of every task: grep each replaced symbol across main, test and
>   other modules; delete what nothing wires to and name it in the summary.
> - Behaviour is unchanged unless the task says otherwise. No new on-screen surfaces.
> - One task per session. Stop at its Stop Condition. No commits unless the user asks.

---

## 1. Context

The last six commits are almost all theme work (format v4, Studio live preview, theme media, icon
editors, physical-media icons). Each new theme parameter or icon family has had to be threaded by
hand through the codec, the bundle, `PfpThemeStore`'s apply / reset / save, a dozen render sites,
the Settings rows, and a hand-copied Studio preview. The review found that the rules these places
share are restated rather than owned, that the restatements already disagree, and that the
"parity" tests compare the Studio's copies to themselves.

## 2. Current behaviour (verified)

| Area | Where | Today |
| --- | --- | --- |
| Icon tiers, render | `XMBShell.kt:611,614` provides `LocalXmbIconOverrides` (theme tier) and `LocalCustomIcons` (user tier) | 12 render sites write `LocalCustomIcons.current[k] ?: LocalXmbIconOverrides.current[k]` themselves (XMBItemList ×4, GameIconView, XmbStatusStrip, NotificationIcons, ShibaCoinArt, CustomIconsOverlay, CategoryIconGlyph, XmbIconOverrides, ConsoleIcon). Only `menuGlyphOverride` (`MenuGlyph.kt:24`) is tested, and only one site calls it. |
| Icon tiers, load | `CustomIconStore.load()` (`:249`); `XMBViewModel.loadThemeIconOverrides()` (`:2379`); `PfpThemeStore.findIconFile` (`:787`) | Three readers of one folder format. User tier decodes at 8192/512 with an extension filter; theme tier at 2048/2048 with none; save has its own extension set. |
| Reset | `PfpThemeStore.resetApplied` (`:308`); `ThemesSettingsViewModel.resetTheme` (`:150`); `XMBViewModel.confirmColorSchemePicker` (`:10638`) | Theme tier only / both tiers / stamp + layout only. |
| Preset picker | `confirmColorSchemePicker` `:10652-10661` | Removes `KEY_ACCENT_OVERRIDE`, `KEY_THEME_ICONS_STAMP`, `KEY_THEME_LAYOUT`. Leaves `theme-icons/`, `KEY_APPLIED_THEME_NAME`, theme text colours, legibility. **Bug B1.** |
| Media tiers | `UiMediaStore.pathFor` (`:71`), `themeAssignments` (`:93`), `PfpThemeStore.saveCurrentLook` (`:656`), `UiMediaRowText.label`, `ThemeMediaPrompt.of` | Five restatements; extension rules differ between `pathFor` (any) and `themeAssignments` (stored extensions). |
| Theme parameters | `PfpThemeStore.applyDetailed` (`:159`, ~16 keys), `resetApplied` (`:308`), `saveCurrentLook` (`:688`) | Three hand-kept key lists; wave style mapped both ways (`:243` vs `:690`). Keys are private, so 9 test files re-declare them by string; `"display_custom_wallpaper"` is declared in 6 main files, `"theme_accent_override"` in 5. |
| Dead-end keys | `display_text_legibility`, `display_text_color_exact` | Written by apply, reset, save, Display settings and backup; read only by `DisplaySettingsViewModel` for its row label and notice. No renderer reads them. |
| Store seams | `PfpThemeStore` constructors `:57-86` | Primary + `@Inject` + three `internal` overloads to thread probe / decode check / cache evictor. |
| Bundle icon split | `PfpTheme.kt` (3 fields, equals, hashCode); `PfpThemeCodec.kt` (3 write loops, 3 read branches, 3 `isRegisteredName` branches); `PfpThemeStore` merge (`:181`) + `iconsOrSysicons` (`:771`); `ThemeUpgrade.kt:66`; `StudioViewModel` (`:449, :892, :1047`) | `icons/` · `sysicons/` · `mediaicons/` handled as three parallel paths at every level. `"sysicon_"` literal ×6, no shared constant. |
| Media gates | `UiMediaStore.validateImported`/`pcm16` (`:293-322`); `ThemeMediaInstaller.stageValidateAndPlace`/`pcm16InPlace` (`:137-177`); Studio `io/MediaGates.kt` | Same pipeline twice on the launcher. Only the theme gate runs `decodeCheck`, so a user's own H.264 High 4:4:4 boot clip still installs and silently falls back. Two slot registries (`UiMediaSlot` core-domain, `ThemeMediaSlots` theme-kit) kept in step by a test. |
| Render rules | core-ui `TextLegibility.kt`, `PFPTheme.kt` (`textOr`/`subTextOr`), `StorefrontColors.kt` (`storefrontColorsFor` `:174`), `DetailPalette.kt`, `PfpTextColors.kt` | core-ui is an `android.library`; `studio/build.gradle.kts` depends on `:core:theme-kit` only. The rule code has no `android.*` imports (only `androidx.compose.ui.graphics.Color` / `lerp`). |
| Studio copies | `preview/PreviewModel.kt:45-72`, `screens/DetailChrome.kt:132-240`, `AppDrawerScreenPreview.kt:113-189`, `SettingsScreenPreview.kt:89-93,641-684`, `ArtworkStudioScreenPreview.kt:75-83`, `XmbPreviewCanvas.kt:133-136,938-946`, `PreviewFlyout.kt:322-400` | Contrast maths ×3, storefront palette ×2, detail palette ×1, text-role rule ×1 plus two private CompositionLocal schemes, text shadow ×6, `0xCCD8E6FF`/`0xAAC8DAF2`, `0.72`, `0x59000000`, `lerp(…, White, 0.55)` copied. |
| Parity tests | `PreviewTextColorTest`, `PaletteTextColorTest`, `OptionsPanelTextColorTest`, `PreviewFlyoutShadowTest` | Test the Studio copies against copied literals or themselves. None runs launcher code. |
| Theme look | `XMBViewModel.kt` (11,833 lines, 67 constructor dependencies) `observeColorScheme` `:2262-2405`, scheme picker `:10562`, icon editor `:10772`, Save as Theme `:10920`, boot media `:11040` | 11 prefs bundled into `SchemePrefs`; any text-colour or layout change re-decodes both icon folders. No test constructs `XMBViewModel`. |
| Ambience holds | `AmbienceController.setSuppressed` (`:163`), 6 owner names (`:292-305`); `HoldAmbience` (`AmbienceHold.kt`); `LaunchDispatcher` (1 hold, 6 releases); `AudioSettingsViewModel` timer; `MusicPlayerController` | `BootSequenceOverlay.kt:90` and `GameBootOverlay.kt:69` both hold `OWNER_ONE_SHOT`; the first to leave releases the other. **Bug B2.** `AmbienceController` has no unit test. |

## 3. Problem / root cause

The theme system has no module that owns its rules. The rules are "which tier wins", "what a
theme parameter is", "which folder an icon key lives in", "is this clip safe and playable", and
"how a theme colour becomes a text colour". Each one is restated wherever it is needed. Restatements
drift: two caps, three resets, two extension filters, an icon family whose read branch skips a
check, a decoder check on one import path only, and a preview that matches the launcher only as
long as someone remembers to copy the change.

## 4. Goals

1. Fix B1 and B2.
2. One module answers tier precedence for icons and media; render sites, Settings rows, the apply
   prompt and Save as Theme ask it.
3. The launcher and the Studio run the same theme-render rules (contrast, text roles, palettes,
   shared visual tokens); parity tests run that shared code.
4. Theme parameters are declared once; apply, reset and save iterate the declaration.
5. The bundle carries one slot-keyed icon map; only the codec knows about the three folders.
6. User imports and theme media pass one media gate, decoder check included.
7. The launcher's theme look is produced by one testable module that `XMBViewModel` consumes.
8. Ambience holds are handles that release once, idempotently.

## 5. Non-goals (do not touch)

- The `.pfptheme` on-disk format. Every pass must read and write byte-identical bundles
  (`RoundTripTest`, `ViewModelLosslessRoundTripTest` guard this).
- Studio composables (layout and drawing). Only the rules and tokens they call are shared.
- `SampleContent` / `PreviewNav` (fixture data and the preview's own reducer) — §12.
- The rest of `XMBViewModel` (music, pickers, Discord, setup wizard…). Pass 6 lifts the theme look
  only.
- Visual changes. If a shared rule changes a pixel on either side, stop and report it (§7 D2).
- Backup format (`BackupManager`) beyond switching to the shared key constants.

## 6. Test cases (write first)

Module paths are abbreviated: `core-data` = `core/core-data/src/test/kotlin/com/playfieldportal/core/data/repository/`,
`theme-kit` = `core/theme-kit/src/test/kotlin/com/playfieldportal/themekit/`, `studio` =
`studio/src/test/kotlin/com/playfieldportal/studio/`.

### P0 — bugs
- `XMBViewModel` preset path (via the new `ThemeTiers`, Pass 1): choosing a preset leaves no
  theme-tier icon resolvable, clears the applied theme name, and Save as Theme exports no theme
  icon. (Written in Task 0.1 against `PfpThemeStore`, see task.)
- `AmbienceControllerTest` (new, core-ui): two owners held, one released → still suppressed; boot
  and GameBoot holds use distinct owners.

### P1 — tiers (Pass 1)
- `ThemeTiersTest` (core-data, temp dirs): user beats theme beats built-in for an icon key and a
  media slot; `shadowed(themeKeys)` lists only keys present in both tiers; `clear(Tier.THEME)`
  leaves the user tier; both tiers use the same decode caps and extension filter; a gif with one
  frame is Still in both tiers; a stray `foo.txt` and an unregistered key are ignored in both.
- `ResolvedIconsTest` (core-ui, pure): the merged map carries the tier of each entry; a user entry
  over a theme entry reports `Tier.USER`.
- Re-point `MenuGlyphTest` at the merged map; delete what it no longer covers.

### P1 — shared render rules (Pass 2)
- Move `DetailPaletteTest` and `TextOverrideTest` cases to the shared module unchanged; they must
  pass against the shared code with the same expected colours (this is the parity proof).
- `OklabLerpTest`: the shared `lerp` equals `androidx.compose.ui.graphics.lerp` for a fixed table of
  ~30 colour pairs and fractions (table generated once on the launcher side and committed as data).
- Studio `PaletteTextColorTest` / `PreviewTextColorTest`: rewrite to call the shared rules; drop
  copied literals.

### P2 — theme parameters (Pass 3)
- `ThemeParametersTest` (core-data): for every declared parameter, apply then reset leaves no key;
  apply then save round-trips the manifest field; the wave style maps both ways through one table.
- `PfpThemeStore*Test`: replace string-declared keys with the public constants.

### P2 — codec (Pass 4)
- `MediaIconsCodecTest`, `SysiconExtrasTest`, `PfpThemeCodecV3Test`: keep every case; assert on
  `bundle.icons["physmedia_psx"]` / `["sysicon_psx"]` instead of the split maps.
- New: a registered-looking `mediaicons/` name with a bad extension is passthrough (closes the
  skipped check).
- `PhysicalMediaIconsTest`: one table cross-check — every console id with physical media maps to
  a bundled art file and a `physmedia_` slot.

### P2 — media gate (Pass 5)
- `MediaGateTest` (core-data, fake probe + fake decoder): user import and theme install produce the
  same verdict and message for the same file; an undecodable boot clip is refused on the user path.
- Keep `UiMediaStoreTest` and `PfpThemeStoreMediaTest` green unchanged.

### P3 — theme look (Pass 6)
- `ThemeLookTest` (feature-xmb or core-data, fake prefs + temp tiers): a text-colour change emits a
  new look without re-decoding icons; an icon stamp bump re-decodes once; the look carries colours,
  resolved icons, layout and boot media paths.

### P3 — ambience (Pass 7)
- `AmbienceControllerTest`: a handle released twice releases once; releasing one handle never ends
  another's hold; `LaunchDispatcherTest` asserts one release on each outcome.

## 7. Architectural decisions

**D1 — Tier precedence lives in `core-data` as `ThemeTiers`.** It owns both folders per kind (icons:
`custom-icons/` + `theme-icons/`; media: user + `theme-media/`), one loader, one cap pair, one
extension filter, `resolve`, `shadowed`, `clear(tier)`. The stores keep import/export; the stamps
stay where they are (`bumpStamp`). On the render side `XMBShell` provides one merged map
(`LocalXmbIcons`: key → icon + tier), built once in the view model; the 12 sites read it.
`LocalCustomIcons` / `LocalXmbIconOverrides` are deleted.

**D2 — Shared render rules move to `:core:theme-kit` as ARGB `Long` maths (recommended), with thin
`Color` adapters on each side.** theme-kit is already pure JVM and already shared. The catch:
`androidx.compose.ui.graphics.lerp` interpolates in Oklab, so the port must too, proven by
`OklabLerpTest`. Alternative: a new Kotlin Multiplatform module (android + jvm targets) using
Compose `ui-graphics`, which keeps `Color` but adds build plumbing to a project that has none.
**Needs the user's pick before Pass 2 (§10 Q1).**

**D3 — Theme parameters are a declared list in `core-data`** (`ThemeParameter(manifest read/write,
pref key, default)`), not reflection. `applyDetailed`, `resetApplied`, `saveCurrentLook` iterate it;
media, icons and wallpaper stay bespoke steps around the loop. Pref keys become public constants in
one `ThemePrefKeys` object; other modules import them.

**D4 — `PfpThemeBundle.icons` becomes the only icon map, keyed by `CustomizableIcons` slot key.**
`sysicons` and `mediaicons` are removed from the bundle; the codec maps `sysicon_<id>` ↔
`sysicons/<id>` and `physmedia_<id>` ↔ `mediaicons/<id>` through one prefix table, and gates with
one check. `SYSICON_PREFIX` joins `PHYSICAL_MEDIA_PREFIX` as a constant.

**D5 — One `MediaGate` in `core-data`** with the existing `MediaProbe` / `VideoDecodeCheck` seam
(two adapters: platform and fake). `UiMediaStore.import` and `ThemeMediaInstaller` both call it.
`PfpThemeStore` takes the gate (or the installer) by injection, collapsing its constructors to one
`@Inject` and one test constructor.

**D6 — `ThemeLook` is a `Flow` producer in `feature-xmb`** (it needs `CustomIcon`/`ImageBitmap`),
injected into `XMBViewModel`, which maps it into `XMBUiState`. It absorbs `observeColorScheme` and
`loadThemeIconOverrides`. The scheme picker and icon editor stay in `XMBViewModel` and call
`ThemeTiers` / `PfpThemeStore`.

**D7 — Ambience holds become handles.** `AmbienceSuppressor.hold(owner): Hold` with an idempotent
`release()`; `setSuppressed` is removed once callers move. `HoldAmbience` keeps its signature.

## 8. Rejected alternatives

- **Share the Studio's composables with the launcher.** Would need un-Androiding core-ui and
  feature-xmb; the drawing code differs legitimately (desktop canvas vs device). Rules only.
- **Generate Studio copies of launcher files with a Gradle task.** Keeps two copies and hides the
  drift instead of removing it.
- **Split `XMBViewModel` into many small helpers.** Moves complexity without deepening anything;
  only the theme look is lifted, as one module.
- **Merge `UiMediaSlot` and `ThemeMediaSlots` in Pass 5.** core-domain cannot depend on theme-kit
  today; the sync test is adequate. Revisit only if a third registry appears.

## 9. Order and dependencies

```
Pass 0 (bugs) ─► Pass 1 (tiers) ─► Pass 3 (parameters) ─► Pass 6 (theme look)
                       │                                      ▲
                       └─► Pass 5 (media gate) ───────────────┘
Pass 2 (shared rules)  — independent, after Q1 is answered
Pass 4 (codec)         — independent, before any new icon family
Pass 7 (ambience)      — independent, last
```

## 10. Open questions (answer before the pass that needs it)

- **Q1 (Pass 2):** shared rules as ARGB maths in theme-kit (recommended) or a new KMP module?
- **Q2 (Task 0.1):** what should choosing a colour preset take away? Proposed: the theme's icons,
  layout and accent override **and** its applied name; the wallpaper, text colours, legibility and
  theme media stay. Alternative: a preset is a full theme reset (same as Themes ▸ Reset, theme tier
  only).
- **Q3 (Pass 3):** the dead-end keys `display_text_legibility` and `display_text_color_exact` —
  wire them to the renderers (they are theme parameters the user can see in Display settings) or
  remove them from apply / save / backup? This is a behaviour decision, not a refactor.

## 11. Implementation phases and tasks

Commands per module (hand these to the user; one per block):

```bash
./gradlew :core:theme-kit:test
```
```bash
./gradlew :core:core-data:testDebugUnitTest
```
```bash
./gradlew :core:core-ui:testDebugUnitTest
```
```bash
./gradlew :feature:feature-xmb:testDebugUnitTest
```
```bash
./gradlew :feature:feature-settings:testDebugUnitTest
```
```bash
./gradlew :feature:feature-launcher:testDebugUnitTest
```
```bash
./gradlew :studio:test
```
```bash
./gradlew :app:assembleFullDebug
```

### Pass 0 — Bugs

#### Task 0.1 — Preset picker leaves the theme tier behind (B1)
- Needs Q2.
- Tests: `PfpThemeStoreTest` — new `clearThemeLook()` removes the theme-icons folder, the stamp, the
  layout, the accent override and the applied name, and leaves wallpaper, text colours and
  `theme-media/` (per Q2). Save Current Look after it exports no theme icon.
- Change: add `PfpThemeStore.clearThemeLook()`; `confirmColorSchemePicker` calls it instead of
  removing keys itself. Pass 1 later moves the folder half into `ThemeTiers`.
- Stop: tests green; preset pick on device (user-driven) shows built-in glyphs and Settings no
  longer names the theme.

#### Task 0.2 — Boot and GameBoot release each other's hold (B2)
- Tests: `AmbienceControllerTest` (new, pure state; extract the suppressor set if needed).
- Change: `OWNER_BOOT` and `OWNER_GAMEBOOT` replace `OWNER_ONE_SHOT`; update both overlays and the
  KDoc owner list.
- Stop: tests green; `OWNER_ONE_SHOT` grep is empty.

### Pass 1 — Tier precedence (`ThemeTiers`)

#### Task 1.1 — `ThemeTiers` for icons
- Tests: `ThemeTiersTest` icon cases (§6 P1).
- Change: new `core-data/.../ThemeTiers.kt` with one loader (caps: the user tier's 8192/512 —
  confirm the theme tier's 2048 cap is not load-bearing for memory; if it is, use one cap for
  both and note it). `CustomIconStore.load` and `XMBViewModel.loadThemeIconOverrides` delegate to
  it; `PfpThemeStore.findIconFile` uses its extension set.
- Stop: tests green; old loaders deleted.

#### Task 1.2 — One resolved icon map for rendering
- Tests: `ResolvedIconsTest`; `MenuGlyphTest` re-pointed.
- Change: `XMBViewModel` builds the merged map once; `XMBShell` provides `LocalXmbIcons`; the 12
  sites read it (`CustomIconsOverlay` reads the tier for its "theme icon" hint). Delete
  `LocalCustomIcons`, `LocalXmbIconOverrides` and the per-site `?:`.
- Stop: tests green; grep for `LocalCustomIcons` / `LocalXmbIconOverrides` empty; app builds.

#### Task 1.3 — Media tiers
- Tests: `ThemeTiersTest` media cases; `UiMediaRowTextTest`, `ThemeMediaPromptTest` unchanged.
- Change: `UiMediaStore.pathFor` / `themeAssignments` and `saveCurrentLook`'s media scan go through
  `ThemeTiers`; one extension rule. `ThemeMediaPrompt.of` and `UiMediaRowText.label` take
  `shadowed(...)` output instead of re-deriving it.
- Stop: tests green.

#### Task 1.4 — Reset semantics in one place
- Tests: `ThemeTiersTest.clear`; `ThemesSettingsViewModel` reset path (add a small test with fakes
  if the view model allows; otherwise test the store calls).
- Change: `resetApplied`, `ThemesSettingsViewModel.resetTheme` and `clearThemeLook` call
  `ThemeTiers.clear(tier)` for files; each keeps only its own pref work.
- Stop: tests green.

### Pass 2 — Shared theme-render rules (needs Q1)

#### Task 2.1 — Contrast maths and Oklab lerp in theme-kit
- Tests: move `TextLegibility` tests to theme-kit; `OklabLerpTest` with the launcher-generated table.
- Change: ARGB versions of `relativeLuminance`, `contrastRatio`, `ensureReadable`, `bestPolarity`,
  `composite`, `scrimAlphaFor`, `solveScrimColor`, `clampLightnessForContrast`, HSL helpers, `lerp`.
  core-ui's `TextLegibility.kt` becomes `Color` adapters (or callers convert at the edge).
- Stop: core-ui and theme-kit suites green with unchanged expected values.

#### Task 2.2 — Text roles, storefront and detail palettes
- Tests: `TextOverrideTest`, `DetailPaletteTest`, storefront cases moved to theme-kit unchanged.
- Change: `textOr` / `subTextOr` / `repaint` / `dimmed`, `storefrontColorsFor`, `detailPaletteFor`,
  `isVividHue`, `SECONDARY_TEXT_WEIGHT` move as pure functions over a small `ThemeColors` input;
  core-ui keeps the CompositionLocals and `themedText` wrappers.
- Stop: suites green; no visual change.

#### Task 2.3 — Shared visual tokens
- Change: `ThemeTokens` in theme-kit: text shadow (black 0.75, (0,2), blur 4), XMB label greys
  `0xCCD8E6FF` / `0xAAC8DAF2`, wallpaper scrim `0x59000000`, edge-lerp fractions, menu shadow alpha.
  Launcher call sites read them.
- Stop: suites green.

#### Task 2.4 — Studio onto the shared rules
- Tests: rewrite `PaletteTextColorTest`, `PreviewTextColorTest`, `PreviewFlyoutShadowTest` to the
  shared code; `LivePreviewRenderTest` unchanged.
- Change: delete the Studio copies in `PreviewModel`, `DetailChrome`, `AppDrawerScreenPreview`,
  `SettingsScreenPreview`, `ArtworkStudioScreenPreview`, `XmbPreviewCanvas`, `PreviewFlyout`;
  collapse the two private CompositionLocal schemes into the preview model; remove stale
  "launcher line N" comments.
- Stop: `:studio:test` green; Studio renders unchanged (user eyeball).

### Pass 3 — Theme parameters

#### Task 3.1 — Public pref keys
- Change: `ThemePrefKeys` object in core-data; the 6 + 5 string declarations and the 9 test
  re-declarations import it. Backup reads the same constants.
- Stop: suites green; grep for the string literals finds only `ThemePrefKeys`.

#### Task 3.2 — Declared parameter list
- Needs Q3.
- Tests: `ThemeParametersTest`.
- Change: `ThemeParameters` list; `applyDetailed`, `resetApplied`, `saveCurrentLook` iterate it.
  Wave-style mapping becomes one bidirectional table. Apply Q3's answer to the dead-end keys.
- Stop: all `PfpThemeStore*Test` green.

#### Task 3.3 — Studio parameter chain
- Tests: `ViewModelLosslessRoundTripTest` + a new case per declared parameter.
- Change: `StudioViewModel.hydrate` / `buildManifest` / `toPreviewModel` read the theme-kit manifest
  field list where one exists, so a new parameter fails a test until both sides carry it.
- Stop: `:studio:test` green.

### Pass 4 — Icon split inside the codec

#### Task 4.1 — One icon map on the bundle
- Tests: §6 P2 codec cases.
- Change: drop `PfpThemeBundle.sysicons` / `mediaicons`; one prefix table in the codec; one gate in
  `isRegisteredName`; delete `iconsOrSysicons`, the apply-side merge, the Studio merge/split and
  `ThemeUpgrade`'s triplet; add `SYSICON_PREFIX`.
- Stop: theme-kit, core-data, studio suites green; round-trip bundles byte-identical.

#### Task 4.2 — Physical-media id table
- Tests: `PhysicalMediaIconsTest` cross-check.
- Change: one table keyed by platform alias → (bundled art file, `physmedia_` slot id) in
  `PhysicalMediaIcons.kt`; the Studio's `StudioIconSet` alias reads the same id list from theme-kit.
- Stop: tests green.

### Pass 5 — One media gate

#### Task 5.1 — `MediaGate`
- Tests: `MediaGateTest`.
- Change: extract stage → normalize (WAV → PCM16) → probe → limits → decode check from
  `ThemeMediaInstaller`; `UiMediaStore.import` uses it, so user boot / GameBoot clips get the
  decoder check (behaviour change: a refused pick shows the gate's message inline, per the
  existing import-refusal rule). Delete `validateImported` / `pcm16` duplicates.
- Stop: suites green.

#### Task 5.2 — One `PfpThemeStore` constructor seam
- Change: inject the gate / installer; delete the three `internal` overloads; tests build the store
  through one test constructor.
- Stop: core-data suite green.

### Pass 6 — Theme look out of `XMBViewModel`

#### Task 6.1 — `ThemeLook`
- Tests: `ThemeLookTest`.
- Change: move `observeColorScheme`, `SchemePrefs` and the icon loading into `ThemeLook`; decode
  icons only on icon stamps; `XMBViewModel` collects one Flow. Boot media path observation moves
  with it.
- Stop: tests green; app builds; device check by the user (theme apply, preset pick, text colour).

### Pass 7 — Ambience handles

#### Task 7.1 — `hold(owner): Hold`
- Tests: §6 P3 ambience.
- Change: add handles; move `HoldAmbience`, `AudioSettingsViewModel`, `MusicPlayerController` and
  `LaunchDispatcher` (one `try/finally` per launch) onto them; delete `setSuppressed`.
- Stop: suites green; `setSuppressed` grep empty.

## 12. Claims checked and dropped

- **"`PhysicalMediaIcons`' alias tables contradict each other."** Not a bug: the art-file aliases
  (`x360 → xbox360`, `tgfx16 → tg16`) name bundled PNG files, while the slot aliases
  (`xbox360 → x360`, `tgfx16 → pcengine`) name `physmedia_` slot ids — two namespaces. It is still
  two tables that must agree by hand, which Task 4.2 makes one.

## 13. As built (2026-10-03)

Every pass landed in one change. Where the plan left a question open, the review's own notes were
followed:

- **Q1 → a new pure-JVM module, `:core:theme-render`**, not ARGB maths in theme-kit. The review
  proposed "a Kotlin-JVM/CMP module both sides depend on"; using Compose's own `Color` and `lerp`
  (the JetBrains multiplatform `ui-graphics` artifact, which resolves to androidx on the launcher —
  Compose is 1.12.0 across the app, so no version skew) moved the rules verbatim and made the
  Oklab port and `OklabLerpTest` unnecessary. The files keep their `core.ui.theme` / `core.ui.detail`
  packages, so launcher call sites did not change; CompositionLocals and `@Composable` wrappers stay
  in core-ui (`*Local.kt` files). Moved: TextLegibility, PFPColors (+ text roles, PfpPalette),
  PfpTextColors (+ `pfpTextColorsFor`), StorefrontColors, DetailPalette, new `ThemeTokens`,
  `menuTextShadowFor`, `menuCursorFillFor` / `menuCursorEdgeFor`, pure `unselectedLabel(pfp)`.
- **Q2 → the proposed answer**: a colour preset takes the theme's icons, layout, accent override and
  applied name (`PfpThemeStore.clearThemeLook`); wallpaper, text colours, legibility and theme media
  stay.
- **Q3 → no behaviour change.** `display_text_legibility` and `display_text_color_exact` are declared
  theme parameters (applied, reset, saved, backed up) and Display settings shows them; the false
  "Must match XMBViewModel" note is gone and the key's comment now says the renderers do not read it
  yet. Wiring them into the XMB renderers is a feature, still open for the user.
- **Ambience**: holds are handles (`AmbienceHolds`, `AmbienceSuppressor.hold`); boot and GameBoot hold
  under `OWNER_BOOT` / `OWNER_GAMEBOOT`. LaunchDispatcher keeps one hold per launch; a launch that never
  hands off releases it in one `finally` (`launch`) / one catch (`launchShortcut`). The other two
  release points (back in the launcher, the watchdog) are outcomes of a launch that did hand off.
- **ThemeLook** carries the boot media too (plan §6 P3), on the UI-media stamp.
- **Tests that tested copies were retired**: `MenuGlyphTest` (→ `XmbIconsTest`),
  `PreviewFlyoutShadowTest` (→ theme-render `MenuTextShadowTest`). `TextLegibilityTest` and
  `StorefrontColorsTest` moved to theme-render; `DetailPaletteTest` / `TextOverrideTest` stay in
  core-ui and now exercise the shared code. Parameter parity is checked on both sides against
  theme-kit's `ThemeParameterFields`.
