# XMB Icon Legibility — PSP-style two-layer glyphs

Source: direct request (device screenshot, 2026-09-05, AYN Thor, snow-scene wallpaper). Effort: S–M.
Branch base: `more-customization`.

This is an implementation handoff. It is written to be executed without the conversation that
produced it. Every line reference was verified against the working tree on 2026-09-05; re-check any
that has drifted, but do not assume a helper exists that is not named here.

## Problem

XMB glyphs are hard to read over a busy custom wallpaper. Today three unrelated mechanisms attempt
to fix that, and none of them targets the actual failure:

| Mechanism | Where | What it does |
|---|---|---|
| Symmetric blur halo behind selected labels | `XMBItemList.kt:143`, `XMBCategoryBar.kt:49` | `Shadow(0x73001627, offset = Zero, blurRadius = 12f)` |
| Flat full-screen wallpaper scrim | `XmbBackground.kt:153` | `Color(0x59000000)` over the whole image |
| Horizontal gradient scrim over game art | `XMBShell.kt:499-508` | already the right shape of solution |

The halo is the thing the owner objected to: zero offset plus a 12f blur is a low-frequency smudge,
not depth. Note also that it is applied **only to the selected label** — unselected labels and every
icon get nothing.

Measured from the owner's frame (1080x1920, density 369, wallpaper = bright snow scene under a
near-black sky, theme `iconColor` a dark red ≈ `#D52E1F`):

- Crossbar glyphs over the dark sky (video, network, social) are close to invisible.
- The same glyphs over the snow read acceptably — one screen, opposite failure directions.
- Unfocused glyphs are additionally drawn at **58% alpha** (`XMBCategoryBar.kt:143`) and **50%** in
  the item column (`XMBItemList.kt:313,319`), blending nearly half the wallpaper into every glyph
  that is not under the cursor.

### What the original PSP did

It drew each icon twice: a dark copy expanded behind a light copy. The separation comes from a
second copy of **the same silhouette**, so it hugs the contour instead of fogging the area around
it. That is the effect this plan ports.

### The trap

The PSP could hardcode "black matte under white glyph" because its icons were always white. PFP's
are not — `iconColor` is themeable and is currently *dark*. A hardcoded dark matte behind a dark red
glyph on a black sky adds nothing; this was confirmed by compositing all four treatments onto the
owner's actual frame. **The matte must be the luminance opposite of the glyph.**

## Goal

A Settings option that lets the owner choose the icon legibility treatment, with the current
behavior preserved as an explicit choice, and every option applied through the one composable that
already renders every XMB silhouette glyph.

## Options to implement

Exactly these, in this order — the order is the cycle order in the UI:

| # | Enum constant | Label | Behavior |
|---|---|---|---|
| 1 | `NONE` | `None` | Today's rendering, unchanged. Ships as the default. |
| 2 | `OFFSET_SHADOW` | `Offset Shadow` | One matte copy, offset down-right. |
| 3 | `CONTOUR_DARK` | `Contour (Dark)` | Dark matte copies dilated all around the glyph. |
| 4 | `CONTOUR_LIGHT` | `Contour (Light)` | Same, light matte. |
| 5 | `CONTOUR_AUTO` | `Contour (Auto)` | Same, matte color derived from the glyph's luminance. |

`CONTOUR_AUTO` is the one expected to win, but it ships **behind** `NONE` as the default — the owner
picks on device after seeing all five. Do not change the default without being asked.

## Decisions already made

Settled with the project owner. If one turns out to be impractical, stop and report rather than
substituting your own.

- **The effect lives in `PortalIcon`** (`core/core-ui/.../icons/PortalIcon.kt`), not at call sites.
  It is already the documented single entry point for XMB silhouette art, so the crossbar
  (`XMBCategoryBar.kt:178`) and the item column (`XMBItemList.kt:316`, `:1090`, `:1122`, `:1179`)
  are covered by one change.
- **Theme icon overrides get the matte too.** `CategoryIconGlyph.kt:21-28` bypasses `PortalIcon` for
  custom `.pfptheme` bitmaps; those are full-color art drawn as-authored, so the matte is built from
  the bitmap's own alpha and the glyph itself stays untinted.
- **One draw node per icon.** Do not implement this as a `Box` of nine stacked `Image` composables —
  the item column is a `LazyColumn` and that multiplies node count on every scroll.
- **Icon opacity is a separate setting** (Task 6), not folded into the styles. The owner wants to
  A/B it independently.
- **Text labels are out of scope for this plan.** The same technique applies to `SelectedTextShadow`
  / `SelectedLabelShadow`, but it is a different mechanism (`TextStyle.shadow`) and lands
  separately. Leave both `Shadow` constants alone.

## Tuning constants (measured, not guessed)

The preview the owner approved used a **4 px** dilation and a **3 px** offset on a 1080x1920 frame
at density 369 (`densityDpi / 160 = 2.306`). Convert once and express in dp:

- Contour radius: `1.75.dp` (≈ 4.0 px on this device)
- Shadow offset: `1.25.dp` down and right (≈ 2.9 px)
- Matte alpha: `0.95f` for the contour styles, `0.85f` for the offset shadow

---

## Task 1 — The style enum

**New file:** `core/core-domain/src/main/kotlin/com/playfieldportal/core/domain/model/IconLegibilityStyle.kt`

core-domain, matching `IconDisplayMode` and `TouchNavButtonMode`. `core-ui` already declares
`api(project(":core:core-domain"))` (`core/core-ui/build.gradle.kts:18`), so `PortalIcon` can see it.

```kotlin
enum class IconLegibilityStyle(val label: String) {
    NONE("None"),
    OFFSET_SHADOW("Offset Shadow"),
    CONTOUR_DARK("Contour (Dark)"),
    CONTOUR_LIGHT("Contour (Light)"),
    CONTOUR_AUTO("Contour (Auto)");

    companion object {
        val DEFAULT = NONE

        /** Tolerant parse for the persisted preference; unknown/blank falls back to [DEFAULT]. */
        fun fromName(value: String?): IconLegibilityStyle =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}
```

Follow `TouchNavButtonMode.fromName` exactly — a non-null return with a default, not a nullable.
Document in the KDoc what the matte is and why `CONTOUR_AUTO` exists (the theme `iconColor` is not
always light).

## Task 2 — Matte geometry and color, as pure functions

**New file:** `core/core-ui/src/main/kotlin/com/playfieldportal/core/ui/icons/IconMatte.kt`

Keep these free of Compose UI plumbing so they are unit-testable without a device:

```kotlin
/** Matte offsets in units of the contour radius; empty when the style draws no matte. */
fun matteOffsets(style: IconLegibilityStyle): List<Offset>

/** The matte color for [style] behind a glyph of [glyphColor]; null when no matte is drawn. */
fun matteColorFor(style: IconLegibilityStyle, glyphColor: Color): Color?
```

Requirements:

- `NONE` returns an empty offset list **and** a null color. Callers must treat that as "draw exactly
  what ships today" — not "draw a matte at alpha 0", which would still pay the draw cost.
- `OFFSET_SHADOW` returns a single `Offset(1f, 1f)` scaled by the shadow offset, not the contour
  radius. Keep the two constants distinct.
- The contour styles return the 8 compass directions on the unit circle — N, NE, E, SE, S, SW, W,
  NW, with the diagonals at `0.7071f` so the contour is round rather than square.
- `CONTOUR_AUTO` picks by `glyphColor.luminance()` (`androidx.compose.ui.graphics.luminance`):
  below `0.5f` → the light matte, at or above → the dark matte. Define both as named constants
  (`MatteLight = Color(0xFFEBF5FF)`, `MatteDark = Color(0xFF000A12)`) and have `CONTOUR_DARK` /
  `CONTOUR_LIGHT` return those same two, so Auto can never disagree with an explicit pick.
- Apply the alpha constants here, so the drawing code receives a final color.

## Task 3 — Draw the matte in `PortalIcon`

**File:** `core/core-ui/src/main/kotlin/com/playfieldportal/core/ui/icons/PortalIcon.kt`

Keep the public signature (`painter`, `contentDescription`, `modifier`, `tint`, `contentScale`) —
there are call sites across feature-xmb and feature-settings and none should have to change.

When the style is `NONE`, leave the existing `Image` call **exactly as it is**. Only the matte path
takes new code, so the default ships bit-identical to today.

For the matte path, replace `Image` with a single drawing node:

```kotlin
Box(
    modifier
        .semantics { if (contentDescription != null) this.contentDescription = contentDescription }
        .drawWithCache {
            val factor = contentScale.computeScaleFactor(painter.intrinsicSize, size)
            val dst = painter.intrinsicSize * factor       // fitted glyph size
            val origin = Offset((size.width - dst.width) / 2f, (size.height - dst.height) / 2f)
            val r = radiusPx                               // contour radius or shadow offset, in px
            onDrawBehind {
                for (o in offsets) {
                    translate(origin.x + o.x * r, origin.y + o.y * r) {
                        with(painter) { draw(dst, colorFilter = ColorFilter.tint(matte, BlendMode.SrcIn)) }
                    }
                }
                translate(origin.x, origin.y) {
                    with(painter) { draw(dst, colorFilter = ColorFilter.tint(tint, BlendMode.SrcIn)) }
                }
            }
        }
)
```

That is the shape, not finished code — `computeScaleFactor` returns a `ScaleFactor`, and
`Painter.draw` is a `DrawScope` extension taking `size`, `alpha`, `colorFilter`. Get the fitted-size
math right rather than assuming a square: the catalog art is not all 1:1.

Requirements:

- One node. No stacked `Image` layers (see Decisions).
- The glyph draws **last**, at the true origin, with the existing `SrcIn` tint. The matte must not
  show through a semi-transparent glyph — verify on an unfocused (58% alpha) crossbar icon.
- Alpha applied by the caller's `Modifier.alpha` (both call sites use it) applies to the whole layer,
  glyph and matte together. That is correct — an unfocused icon should dim as a unit — but it does
  mean the matte is weakest exactly where it is needed most. That is what Task 6 addresses; do not
  compensate by boosting matte alpha here.
- Read the contour radius through `LocalDensity`; never hardcode px.

## Task 4 — The composition local

**File:** `core/core-ui/src/main/kotlin/com/playfieldportal/core/ui/icons/IconMatte.kt` (with Task 2)

```kotlin
val LocalIconLegibility = staticCompositionLocalOf { IconLegibilityStyle.DEFAULT }
```

`staticCompositionLocalOf` matches `LocalXmbIconOverrides` (`XmbIconOverrides.kt:20`) — the value
changes rarely, so a full subtree recomposition on change is the right trade.

**Provide it in** `feature/feature-xmb/.../ui/XMBShell.kt:395-401`, in the existing
`CompositionLocalProvider` next to `LocalXmbIconOverrides` and `LocalIconDisplayMode`:

```kotlin
LocalIconLegibility provides uiState.iconLegibility,
```

Feed it from `XMBViewModel`: add `iconLegibility: IconLegibilityStyle = IconLegibilityStyle.DEFAULT`
to the UI state (`XMBViewModel.kt:688`, where `iconDisplayMode` sits) and read the pref in
`observeTouchNavButtonMode` (`XMBViewModel.kt:7981-8000`) — that function already collects
`pfpDataStore.data` for four display prefs, so adding two costs no extra collector. Add the keys to
the companion alongside `KEY_TOUCH_NAV_BUTTON` (`:8097-8098`) with the same "must match
DisplaySettingsViewModel" comment.

## Task 5 — Theme icon overrides

**File:** `core/core-ui/src/main/kotlin/com/playfieldportal/core/ui/icons/CategoryIconGlyph.kt:20-28`

The override branch draws a decoded `.pfptheme` bitmap and returns before `PortalIcon`. Give it the
same treatment: matte copies tinted to the matte color (`SrcIn` against the bitmap's alpha), then
the bitmap drawn **untinted** on top. The "custom icons draw as-authored" contract in
`XmbIconOverrides.kt:29-30` refers to the glyph, and a matte behind it does not break that — but say
so in the comment, because it reads like a contradiction otherwise.

Factor the loop out of `PortalIcon` into an internal helper both branches call rather than writing
the draw code twice. `ThemedGlyph` (`XmbIconOverrides.kt:33-46`) has the same override/default shape
and should get the same helper — check whether its call sites want the matte before wiring it; if in
doubt, wire it and flag it in the handoff notes.

## Task 6 — "Solid Unfocused Icons" toggle

A separate setting, default `false` (today's behavior). When on, the unfocused dimming is skipped:

- `XMBCategoryBar.kt:142-146` — `itemAlpha` target becomes `1f` instead of `0.58f`.
- `XMBItemList.kt:313` and `:319` — `.alpha(if (selected) 1f else 0.5f)` becomes `1f`.

Selection is still signalled by icon size (`categoryIconSelectedDp`) and by the label fading in
(`labelAlpha`, `XMBCategoryBar.kt:150-154`), so nothing is lost when the dimming goes.

This is a two-line change and, per the owner's read of the preview, may deliver more legibility than
the matte does. Implement it as its own commit so it can be evaluated alone.

## Task 7 — Settings UI

**File:** `feature/feature-settings/.../viewmodel/DisplaySettingsViewModel.kt`

Mirror the `WaveStyle` plumbing — it is the closest precedent, in the same file:

- Keys next to the others at `:35-56`:
  `private val KEY_ICON_LEGIBILITY = stringPreferencesKey("display_icon_legibility")` plus
  `KEY_SOLID_UNFOCUSED_ICONS` (boolean) for Task 6. Both need the "Must match XMBViewModel" comment,
  since the XMB reads them too.
- Skip the labels map. `WAVE_STYLE_LABELS` (`:78-83`) exists because `WaveStyle` has no label
  property; `IconLegibilityStyle` carries its own, so use it directly.
- Add `iconLegibility` and `solidUnfocusedIcons` to `DisplaySettingsUiState` (`:85-107`) and to the
  `combine` block (`:124-144`), parsing with `IconLegibilityStyle.fromName` — the same shape as
  `TouchNavButtonMode.fromName(prefs[KEY_TOUCH_NAV_BUTTON])` at `:132`.
- `fun cycleIconLegibility()` next to `cycleWaveStyle` (`:147-151`), same modulo-cycle shape. Use
  `entries`, not the deprecated `values()` that `cycleWaveStyle` still calls.

**File:** `feature/feature-settings/.../ui/DisplaySettingsScreen.kt`

Under `SettingsGroup("Appearance")` (`:83`), after the Wave Style / Background Motion rows
(`:118-137`) — this is an appearance choice, not an Interface one:

```kotlin
SettingsValueRow(
    label    = "Icon Legibility",
    sublabel = "How XMB icons separate from the background.  " +
        "None  |  Offset Shadow  |  Contour (Dark)  |  Contour (Light)  |  Contour (Auto — follows the icon color)",
    value    = state.iconLegibility.label,
    onClick  = { viewModel.cycleIconLegibility() },
)

SettingsToggleRow(
    label    = "Solid Unfocused Icons",
    sublabel = "Draw unselected icons at full opacity — selection still reads by size and label",
    checked  = state.solidUnfocusedIcons,
    onToggle = { viewModel.setSolidUnfocusedIcons(it) },
)
```

Unlike Wave Style, do **not** gate these rows on `customWallpaperPath == null`. They matter most
when a wallpaper is set, and they still apply over the wave.

## Task 8 — Tests

**`core/core-ui/src/test/kotlin/.../icons/IconMatteTest.kt`** (new — the module already has a test
source set, under `.../core/ui/motion/`):

- `matteOffsets(NONE)` is empty; `OFFSET_SHADOW` has exactly one entry; each contour style has 8.
- The 8 contour offsets are unit-length within a small epsilon (catches a diagonal written as `1f`).
- `matteColorFor(NONE, any)` is null.
- `CONTOUR_AUTO` against a dark glyph (the owner's `#D52E1F`) returns the **light** matte, and
  against white returns the dark one. This is the defect the whole plan exists to avoid — pin it.
- `CONTOUR_AUTO` agrees with `CONTOUR_DARK` / `CONTOUR_LIGHT` for glyphs on either side of the
  threshold.
- `IconLegibilityStyle.fromName` round-trips every constant and returns `NONE` for null, `""`, and a
  stale name.

**`feature/feature-settings/src/test/kotlin/.../viewmodel/DisplaySettingsViewModelTest.kt`** — model
the DataStore harness on the existing `DisplaySettingsViewModelWallpaperTest`:

- `cycleIconLegibility` advances `NONE → OFFSET_SHADOW → … → CONTOUR_AUTO → NONE` and persists the
  enum **name**.
- An unknown persisted value surfaces as `NONE` rather than throwing. `cycleWaveStyle` needed a
  `runCatching` at `:125-127` for exactly this; `fromName` makes that unnecessary — prove it.

No Compose UI test. There is no screenshot harness in this repo, and the visual result is judged on
device (Verification below).

---

## Files touched

| File | Change |
|---|---|
| `core-domain/.../model/IconLegibilityStyle.kt` | **new** — the five-option enum |
| `core-ui/.../icons/IconMatte.kt` | **new** — offsets, colors, `LocalIconLegibility` |
| `core-ui/.../icons/PortalIcon.kt` | matte draw path; `NONE` keeps today's `Image` call |
| `core-ui/.../icons/CategoryIconGlyph.kt` | matte behind theme-override bitmaps |
| `core-ui/.../icons/XmbIconOverrides.kt` | `ThemedGlyph` shares the helper; comment update |
| `feature-xmb/.../ui/XMBShell.kt` | provide `LocalIconLegibility` at `:395-401` |
| `feature-xmb/.../viewmodel/XMBViewModel.kt` | UI-state fields + pref read in the existing collector |
| `feature-xmb/.../ui/XMBCategoryBar.kt` | Task 6 only — `itemAlpha` |
| `feature-xmb/.../ui/XMBItemList.kt` | Task 6 only — row icon alpha |
| `feature-settings/.../viewmodel/DisplaySettingsViewModel.kt` | keys, state, cycle, setter |
| `feature-settings/.../ui/DisplaySettingsScreen.kt` | two rows under Appearance |
| `core-ui/src/test/.../icons/IconMatteTest.kt` | **new** |
| `feature-settings/src/test/.../DisplaySettingsViewModelTest.kt` | cycle + tolerant-parse cases |

**Do not touch:** `SelectedTextShadow` (`XMBItemList.kt:143`), `SelectedLabelShadow`
(`XMBCategoryBar.kt:49`), the wallpaper scrim (`XmbBackground.kt:153`), the game-art gradient
(`XMBShell.kt:499-508`), and anything in `GameIconView.kt`. Game artwork is content imagery, not
silhouette art — `PortalIcon`'s own KDoc draws that line and this plan keeps it. Text legibility is
a separate follow-up.

## Verification

Ask the project owner before running Gradle — builds are not run unprompted in this repo.

```bash
./gradlew :core:core-ui:testDebugUnitTest :feature:feature-settings:testDebugUnitTest :feature:feature-xmb:testDebugUnitTest
```

On device, the owner drives navigation; request screenshots only when they say they are ready. The
reference frame is the snow-scene wallpaper that motivated this plan — a bright subject on the
right, a near-black sky on the left, in one screen.

1. **`NONE` is genuinely unchanged.** At the default setting the XMB is pixel-identical to the
   pre-change build. This is the gate for shipping that default; if anything shifted, the matte path
   is running when it should not be.
2. **All five options are reachable** by cycling the row with the controller, the label updates, and
   the value survives an app restart.
3. **`CONTOUR_AUTO` reads on both halves of the screen.** The video / network / social glyphs over
   the dark sky and the achievements glyph over the lit lantern are all legible in one frame.
4. **`CONTOUR_DARK` is visibly worse than `CONTOUR_LIGHT` on this theme.** Expected, not a bug — it
   is the evidence that Auto is choosing correctly. If Dark and Auto look identical here, the
   luminance branch is inverted.
5. **A light theme inverts it.** Switch to a theme with a light `iconColor` and confirm
   `CONTOUR_AUTO` now matches `CONTOUR_DARK`. Without this check, the auto branch is untested in the
   direction the PSP actually shipped.
6. **Theme override icons get the matte** — apply a `.pfptheme` carrying custom crossbar icons and
   confirm the contour appears without recoloring the custom art.
7. **Unfocused icons still dim** with Task 6 off, and stop dimming with it on, in both the crossbar
   and the item column.
8. **Scroll performance.** Flick the item column hard on the busiest console with `CONTOUR_AUTO` on.
   Nine draws per icon should be free, but confirm rather than assume — this is the one change that
   touches a `LazyColumn` item's draw path.
9. **The wave path is unaffected.** With no wallpaper set, the XMB over the wave looks correct in
   `NONE` and improved in `CONTOUR_AUTO` — the matte is not wallpaper-specific.

## Follow-up (not this plan)

Text legibility. `Total Games: 30` and the secondary line under `All Games` are the least legible
elements in the reference frame, and they are unrelated to icons: they need the `TextStyle.shadow`
treatment reworked (a directional 4f shadow, matching `PspContextMenu.kt:59` and
`ControllerHintBar.kt:55-59`, or a drawn second copy). Whether that becomes a sixth option on this
setting or its own row is an open design question for the owner.
