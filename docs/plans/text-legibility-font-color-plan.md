# Adaptive text legibility + user-selectable font color

## Context

A screenshot of Settings ▸ Sound on the Thor was measured for WCAG contrast against the real
pixels behind each text run. The results:

| text run | contrast | verdict |
|---|---|---|
| "Error / Invalid" (white, top of screen) | 6.17:1 | passes |
| "Boot Sound" (white, lower) | 3.19:1 | large-text only |
| "Reset Sound to Defaults" (white, light band) | **1.82:1** | fails |
| its sublabel (grey) | **1.14:1** | fails badly |
| every "PFP Default" value (accent blue) | **1.05 – 1.84:1** | fails everywhere |

Two independent root causes, and neither is "no backdrop":

1. **The backdrop luminance sweeps 0.091 → 0.539 down one screen.** White needs background
   luminance ≤ 0.183 to reach 4.5:1; black needs ≥ 0.175. This screen straddles that crossover, so
   no single fill color passes top and bottom. At y≈940 white is 1.89:1 and accent is 1.77:1 — a
   band where neither works.
2. **`PfpPalette.Accent` #4A90D9 has luminance 0.264, so it is structurally incapable of carrying
   body text.** Its ceiling is 6.28:1 on pure black and 3.34:1 on pure white. No shadow fixes this:
   a drop shadow adds a dark *edge* without changing the *fill* relationship. That is exactly why
   `SettingsTextShadow` isn't cutting it.

And the reason a uniform scrim is the wrong instrument: rescuing the light band needs a black
scrim at **alpha 0.66**, which buries the wallpaper across the other 80% of the screen. One
constant cannot serve a gradient.

**The most important finding is that the bright band is mostly the app's own paint, not the
wallpaper.** `SettingsScaffold.kt:625-634` draws `backgroundBottom.copy(alpha = 0.90f)`, and
`ColorCascade.lightBackgroundAnchors` (`core/theme-kit/.../ColorCascade.kt:18`) defines
`backgroundBottom = lighten(wave, 0.28f)`. At the "Reset" row the scrim is ~0.89 opaque, so ~11%
of the wallpaper reaches the eye. The launcher paints a light teal band and then puts white text
on it. That makes the measured screenshot fixable in two files with no wallpaper sampling at all —
which is why that is Phase 1 and ships before anything else.

Alongside the fix, users get a **font color** they can pick freely.

### Decisions taken

- Scope: everywhere text meets wallpaper.
- Font color: picked freely; by default its **lightness is adjusted** so contrast passes, with a
  **dismissible** notice, an **opt-out** ("use my exact color"), and **permanent suppression** of
  the notice.
- **Accent is no longer a text fill** — it stays on focus rings, dividers, switches, borders.
- Warning threshold in the picker: **3:1**.

### One correction to a decision

A *global* 3:1 render bar would ratify the failure above. WCAG "large text" is 24sp regular or
18.66sp bold; the measured strings are 15sp labels, 13sp values, 12sp sublabels
(`SettingsScaffold.kt:764, 1023, 889`). Only the XMB's 22sp SemiBold selected label qualifies.
So the **render** threshold is per-role — `BODY = 4.5f`, `LARGE = 3.0f` — while **3:1 stays the
picker's warning trigger**, which is where the decision was actually made.

---

## What already exists (reuse, don't rebuild)

| Piece | File |
|---|---|
| **A correct, tested WCAG engine** — `relativeLuminance`, `contrastRatio`, `ensureReadable(fg, bg, min)` | `core/core-ui/.../theme/StorefrontColors.kt:108-139` (+ `StorefrontColorsTest.kt`) |
| The App Drawer already runs `ensureReadable` on its text roles | `deriveStorefrontColors()`, same file |
| Icon-side solution to the identical problem — copy its shape and vocabulary | `core/core-domain/.../model/IconLegibilityStyle.kt`, `core-ui/.../icons/IconMatte.kt:91`, `IconMatteSurface.kt` |
| Unused text slots already in the theme | `PFPColors.textPrimary/textSecondary` (`core-ui/.../theme/PFPTheme.kt:13-14`); `XmbPalette.textColor` (`core-domain/.../XmbColorScheme.kt:31`, hardcoded white ×12 at `:73`) |
| The exact line a user color joins | `XMBViewModel.kt:1520-1524`, beside `iconColor` |
| Precedent for a user-toggled text property | `textShadow`: `DisplaySettingsScreen.kt:176` → `DisplaySettingsViewModel.kt:112,193` → `XMBViewModel.kt:8439-8448` → `XMBShell.kt:778` → `XMBItemList.kt:644` |
| Notice/dismiss idiom to copy verbatim | `_wallpaperMessage` → `DisplaySettingsScreen.kt:402` → `dismissWallpaperMessage()` |
| Two near-identical HSV picker modals to merge | `feature-xmb/.../ColorSchemePickerOverlay.kt:175-248`; private `IconColorCustomPicker` in `ThemesSettingsScreen.kt:545-618` |
| Theme bundle is additive-safe | `PfpThemeCodec.kt:62-66` `ignoreUnknownKeys = true`; no reader gates on version (pinned `PfpThemeCodecV3Test.kt:191,201`) |

**Do not lift** `studio/.../ui/HsvColorPicker.kt` — it uses `java.awt`, and `:studio` stays pure JVM.

---

## 1. The seam: re-point the ~20 constants at a CompositionLocal

There is no `PfpText` and there should not be one. ~400 `Text(...)` sites, but they funnel through
about twenty module-level constants, and `Text` already reads `LocalContentColor`.

New file `core/core-ui/src/main/kotlin/com/playfieldportal/core/ui/theme/PfpTextColors.kt`:

```kotlin
@Immutable
data class PfpTextColors(
    val primary: Color,          // resolved + clamped
    val secondary: Color,
    val inactive: Color,
    val destructive: Color,
    val requested: Color,        // what the user actually picked
    val adjusted: Boolean,       // primary != requested
    val achievedRatio: Float,
    val protection: TextProtection,   // NONE / SHADOW / PLATE(alpha) / OUTLINE
)
val LocalPfpTextColors = staticCompositionLocalOf { DefaultPfpTextColors }
```

`staticCompositionLocalOf` matches `LocalIconLegibility`'s precedent and rationale — the value
changes only on a settings edit, so full-subtree recomposition is the right trade.

The constants gain composable getters:

```kotlin
val SettingsText: Color
    @Composable get() = LocalPfpTextColors.current.primary
```

Every current call site is already in `@Composable` scope, so ~139 references repaint with no
edit. Sites that read a token *outside* composition (a `remember { }`, a non-composable helper)
will fail to compile — **that is the feature**: the compiler enumerates the exceptions instead of
you grepping. Budget ~5-15 fixups, each becoming an explicit parameter.

**The single highest-leverage line**, in `PFPTheme`:

```kotlin
CompositionLocalProvider(
    LocalPFPColors provides colors,
    LocalPfpTextColors provides resolved,
    LocalContentColor provides resolved.primary,   // ← covers every Text with no color= arg
) { ... }
```

Rejected alternatives: a `PfpText` wrapper costs 400 mechanical edits and still can't stop call
site 401 from being a raw `Text`. A `Modifier` approach cannot set a text color at all and cannot
reach the 171 sites that pass `color =` explicitly.

## 2. Background luminance

**Closed-form for every scrimmed surface** — Settings, App Drawer, storefront, detail, music,
context menus all paint a ≥0.72-alpha layer of *known* colors, so their effective background is
`composite(scrim, wallpaperWorstCase)` with no sampling. This covers most of the scope.

**A precomputed map for the XMB only** — the one surface that genuinely draws text on raw
wallpaper (`XMBItemList.kt:158-164` says so itself). New pure-JVM
`core/theme-kit/.../WallpaperLuminanceMap.kt` beside `WallpaperMetrics.kt`:

- 12 vertical bands × 3 horizontal zones, storing `{ meanLuminance, p90Luminance }` — 72 floats.
  `p90` drives protection strength, `mean` drives polarity. A plain mean hides a bright cloud
  behind one word.
- **Store WCAG relative luminance, not Rec.601 luma.** Trap: `WallpaperMetrics.luminance`
  (`WallpaperMetrics.kt:64`) is Rec.601 and does **not** compose with `contrastRatio`. Mixing them
  yields plausible-but-wrong thresholds.
- Computed at four write sites, in the same `save { }` transaction as `KEY_CUSTOM_WALLPAPER`:
  `DisplaySettingsViewModel.kt:372` (still import), `:457` (motion — the poster bitmap is already
  decoded at `:445`, so it is free), `PfpThemeStore.kt:177` (theme apply),
  `PhotoViewerViewModel.kt:264` (set as wallpaper). Self-heal on mismatch in
  `StartupDataPrep.kt:73-78`, which also covers backup restore.
- **No `ui_media_stamp` needed** — wallpaper paths are already uniquified per import so Coil's
  cache invalidates (`DisplaySettingsViewModel.kt:360-364`). Embed the source path in the map's own
  JSON and discard on mismatch. One key: `display_wallpaper_luma`.
- Cost: decode at `inSampleSize` to ≤256px (the bounds-pass idiom already exists in
  `probeMotionFile`), stride-sample as `WallpaperMetrics.busyness` does. Single-digit ms, on the
  existing import coroutine behind the existing spinner.
- Motion wallpapers: poster frame only, plus bias protection one step stronger when
  `KEY_MOTION_WALLPAPER` is set, since live frames drift from the poster.
- The wave: do not model per-pixel. A single tuned additive-luminance constant over the bottom
  ~35%, pinned by a unit test.

Runtime sampling is rejected: `PixelCopy` needs a Window/SurfaceView and the wallpaper is a Coil
`AsyncImage`; `GraphicsLayer.toImageBitmap()` would work but means a GPU→CPU readback in a
launcher whose background architecture is built on the opposite principle, and it would sample our
own text.

## 3. What actually renders

**AUTO resolution order** — state this in the KDoc, it is the design:

1. Resolve the requested color.
2. Get the background (closed-form composite, or map band).
3. `contrastRatio ≥ role.threshold` → render plain.
4. Else clamp lightness on the requested color, preserving H and S → render, `adjusted = true`,
   raise the notice.
5. Else apply a **text-shaped contrast plate** at the minimum alpha that gets the **original**
   color over the line.
6. Else fall back to `bestPolarity(bg)`.

Step 4 before 5 honours the color where possible; 5 before 6 prefers a local plate over
overriding the choice outright.

The plate is `Modifier.drawBehind` on the label's own box — a rounded pill of the winning polarity
at a *solved* alpha, with a short horizontal edge fade. One extra draw op, **zero extra layout
nodes**, and alpha is `0.0f` wherever the wallpaper is already dark. This is the direct answer to
the 0.66 problem: 0.66 is what a *full-screen* scrim costs; a text-shaped plate needs the same
local alpha but destroys ~12% of the wallpaper instead of 100%.

Rejected: **outline via `TextStyle(drawStyle = Stroke)`** as primary — `drawStyle` *replaces* the
fill, so outlined-and-filled means drawing `Text` twice, doubling nodes inside a `LazyColumn`,
against the rule `IconMatteSurface.kt:31-33` already states. Keep it as an opt-in mode for the few
large non-list labels. **Backdrop blur** — expensive, fights the battery/thermal/WaveStyle motion
budget, and decisively: blur reduces spatial detail but *preserves mean luminance*, so it cannot
fix a uniformly bright band at L=0.539. Blur solves busyness, a different problem.

Keep the existing drop shadow as the always-on floor, and keep `display_text_shadow` — `AUTO`
reads it as "may I use a shadow?", so no pref is retired and no user choice is broken.

New `core/core-domain/.../model/TextLegibilityStyle.kt`, mirroring `IconLegibilityStyle` exactly
(label on the enum, `DEFAULT`, tolerant `fromName`): `NONE / SHADOW / OUTLINE / PLATE / AUTO`,
`DEFAULT = AUTO`.

## 4. The font-color feature

**Extract the picker.** The two Android copies are near-identical (same 440dp panel, same
`0xFF15151F`, same three channel bars, same gamepad hint). Merge into
`core/core-ui/.../components/HsvColorPickerDialog.kt`, parameterizing only the title and the
accent/subtext colors, and move the generic `IconColorChoices`/`IconColorSwatchRow`/`IconSwatch`
(`ThemesSettingsScreen.kt:462-543`) with it. Add one thing neither copy has: **a live contrast
strip** showing sample text over the theme's darkest and brightest anchors with both ratios
printed — ~20 lines, since `contrastRatio` already exists, and it is what makes the adjustment
self-explanatory rather than surprising.

**The clamp uses HSL, not HSV.** HSV `V` at `S = 1.0` never reaches white, so clamping V would
report "unreachable" for colors that are trivially reachable. HSL `L` reaches both poles, so a
solution always exists in at least one direction. Bisect ≤8 iterations upward and downward, take
whichever result is *nearer* the original L (not whichever maximizes contrast — that just drives
everything to black and throws the hue away). Keep the picker in HSV; convert only inside the
clamp, round-tripping through packed ARGB.

**Notice, three actions** — copy the `_wallpaperMessage` idiom:
*Dismiss* (transient) · *Use my exact color* (`display_text_color_exact`; skips the clamp, but
protection still applies — say so in the dialog) · *Don't warn again*
(`display_text_contrast_notice_suppressed`; adjustment continues silently).

**DataStore keys** (`display_`-prefixed, matching `DisplaySettingsViewModel.kt:42-70`):
`display_text_color` (long, null = theme default), `display_text_color_exact` (bool),
`display_text_legibility` (string), `display_text_contrast_notice_suppressed` (bool),
`display_wallpaper_luma` (string).

**Register every one in `BackupManager.kt:492`** — it backs up by an explicit key list, so a new
key silently fails to survive restore. Covered by a test below.

**Theme bundle**: add `val textColor: String = ICON_COLOR_AUTO` after `PfpTheme.kt:23`. **Do not
bump `SCHEMA_VERSION`** — it stays 3, additive-safe by the argument the v3 comment already makes.
Five `PfpThemeStore` touch points mirroring `iconColor`: L150-152, L179, L202, L452-453, L617.
Studio parity: a `TextColorChoice` beside `IconColorChoice`, wired through `StudioViewModel`,
`io/PtfConversion.kt`, and `preview/XmbPreviewCanvas.kt` so the desktop preview shows the
*adjusted* color.

## 5. Accent is no longer a text fill

Migrate via a shim so the compiler enumerates the sites:

```kotlin
@Deprecated("Accent is not a text fill — use SettingsText / SettingsSubtext / destructive")
val SettingsAccentText: Color @Composable get() = SettingsText
```

Find/replace the text sites onto it, work the deprecation warnings out of the build log, delete
the shim. Representative sites: `SettingsScaffold.kt:1022` (the 1.05:1 value text) and `:677`
(breadcrumb eyebrow) → `SettingsText`/`SettingsSubtext`; the "Remove"/"Delete"/"✕" actions in
`LibraryManagerScreen.kt`, `CategoryManagerScreen.kt:205`, `ThemesSettingsScreen.kt:638` →
`StorefrontColors.destructive` (`#FFFF6B6B`, already defined) through the clamp; status/progress
strings in `ArtworkImportScreen`, `ArtworkSettingsScreen`, `AudioSettingsScreen`,
`EmulatorAssignmentScreen` → `SettingsText`.

**Staying accent** (fills, rings, borders — the affordance was always carried by these, so nothing
is lost): the Switch track (`:995`), TextField border/cursor (`:1112,1114`), the
`lerp(SettingsAccent, Black, 0.50f)` action-focus backgrounds, `Icon` tints,
`CircularProgressIndicator`, and `IconSwatch`'s selection ring.

## 6. Phases

| # | Scope | Visible effect |
|---|---|---|
| **0** | Promote the engine out of `StorefrontColors.kt` into a public `TextLegibility.kt`; add the clamp, `scrimAlphaFor`, `composite`, `bestPolarity`, `ResolvedTextColor`, `TextContrastRole`; add `TextLegibilityStyle`; write the pure-JVM tests | none (engine only) |
| **1** | **Ship first.** Solve the `SettingsScaffold.kt:625-634` scrim anchors for 4.5:1; `:1022` value text → `SettingsText`; `PFPTheme` provides `LocalContentColor` | **fixes the measured screenshot** |
| **2** | The seam — ~20 constants → composable getters; wire `XmbPalette.textColor` through `toPFPColors()` and fold the pref in at `XMBViewModel.kt:1520-1524`. Local carries white, so no behaviour change | none |
| **3** | Font-color feature: shared picker, clamp, notice/opt-out/suppression, keys, `BackupManager`, bundle field, Studio parity | the feature |
| **4** | `WallpaperLuminanceMap` + four compute sites + self-heal + the XMB text plate | XMB legible over any wallpaper |
| **5** | Cleanup: the 11× `0xFFEEEEEE`/`0xAAEEEEEE` copies, `EmulatorProfileEditorScreen.kt:28-29`, the raw-white leaks at `SettingsScaffold.kt:763,880`, and the accent migration above | none |

## 7. Verification

Pure-JVM (`core-ui`, follow `StorefrontColorsTest.kt`; no Robolectric):

- **The drift pin that would have caught this bug:** assert `contrastRatio(White, Color(0xFF4783C0))`
  — the literal `lightBackgroundAnchors(CLASSIC_BLUE).second` — is **< 4.5**, and that the new
  solved anchor is ≥ 4.5. A future `ColorCascade.lighten` tweak then cannot silently re-break it.
- The clamp preserves H to ±0.5° and S to ±0.01 across ~12 `(fg, bg)` pairs.
- The clamp never silently returns a failing color: either `adjusted == false`, or
  `achieved >= target`, or `adjusted == true && achieved < target` with the escalation flag set.
- **The exact bug case:** `#4A90D9` against the measured band — both bisection directions fail at
  4.5 and the plate escalation fires.
- **The design rationale as an executable test:** `scrimAlphaFor` on the measured bright band
  yields ≈0.66 for a full-screen scrim; the text-shaped plate at the same threshold covers a small
  fraction of the frame.

theme-kit (pure JVM, synthetic fixtures as in `WallpaperMetricsTest.kt`): band mean/p90 and JSON
round-trip, **plus a guard asserting the map's luminance ≠ `WallpaperMetrics.luminance`** for a
mid-green pixel, so nobody "unifies" them later.

Robolectric settings-VM (copy `DisplaySettingsViewModelLegibilityTest.kt`'s skeleton verbatim):
picking persists the long and clearing removes the key; `exact = true` suppresses adjustment but
not protection; `suppressed = true` stops the notice while adjustment continues; an unknown
persisted `display_text_legibility` surfaces as `DEFAULT`.

`PfpThemeStoreTextColorTest` — applying a bundle *without* `textColor` **removes** the pref rather
than inheriting the previous theme's (pattern at `PfpThemeStoreTest.kt:156-162`).

Drift pins: `SCHEMA_VERSION` stays 3 and a `textColor` manifest round-trips
(`PfpThemeCodecV3Test.kt`); **a `BackupManager` key-coverage test** asserting every
`display_text_*` key appears in the backed-up set — this class of bug is invisible until restore.

On-device (you drive; say when a screen is up and I'll capture):
1. Settings ▸ Sound after Phase 1 — "Reset Sound to Defaults" and every "PFP Default" legible; I'll
   re-run the same measurement script against the new screenshot and compare the table above.
2. Pick a saturated mid-tone font color → notice appears, color renders adjusted.
3. "Use my exact color" → renders as picked, plate appears instead.
4. "Don't warn again" → no notice on the next pick, adjustment still happens.
5. Set a bright wallpaper, open the XMB → game labels legible; set a dark one → plates vanish
   (alpha solves to 0).
6. Backup, wipe data, restore → font color, legibility style, and suppression all survive.
