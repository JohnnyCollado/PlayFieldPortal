# Icon System — Unification & User-Selectable Unified Icon Color

Plan for (1) unifying how icons are accessed/rendered and (2) letting users recolor **all**
icons with a single chosen color. Feasibility is confirmed — see the reasoning below.

## Goal

Give users one control that tints every XMB icon to a single color, and route all icons
through one rendering path so that control has exactly one place to apply.

## Why it's possible (verified)

- **All monochrome icons tint cleanly** — vectors (battery, status, button hints) *and*
  the 52 raster PNGs (`catbar_*`, `sysicon_*`). The rasters are silhouettes: single fill
  color carried by the alpha channel (0% saturated pixels), so `ColorFilter.tint(color,
  SrcIn)` recolors them with no quality loss. Vector-vs-raster is irrelevant for tinting.
- **Shaded multi-color vectors** (`media_umd`, `media_cartridge*`, discs) are the only
  exception — a flat tint flattens their shading. Options below.
- **Category icons are currently untinted** — `CategoryIconGlyph` renders
  `Image(painterResource(...))` with no `colorFilter`, so they show at native white today.

## Current icon formats (context)

| Format | Count | Tint path |
|---|---|---|
| VectorDrawable XML (mono) | ~24 | `tint(SrcIn)` |
| VectorDrawable XML (shaded) | ~6 | `ColorMatrix` hue-rotate, or exclude |
| Raster PNG mono (`catbar_*`, `sysicon_*`) | 52 | `tint(SrcIn)` |
| Material `Icons.*` (ImageVector) | 32 distinct | `tint` |

## Feature: Unified icon color

### Where it lives
Extend the existing theme model rather than inventing parallel state:

- `XmbPalette` (in `core-domain/model/XmbColorScheme.kt`) already has an unused
  `accentColor` (hardcoded `0xFFFFFFFF`). **Add `iconColor: Long`** (or repurpose
  `accentColor`) as the single icon tint.
- Each `XmbColorScheme` preset resolves a sensible default `iconColor` (e.g. white on
  colored schemes, or the scheme's accent).
- **User override**: a nullable `iconColorOverride` persisted via `PFPDataStore`
  (DataStore). When set, it wins over the scheme default. Default = current amber
  `#C8A840` to preserve today's look.

### How it's applied
Introduce a **`PortalIcon` façade** in `core-ui` — the single entry point every screen
uses instead of `Icon(...)` / `Image(painterResource(...))` / `Icons.Filled.*` directly:

```
PortalIcon(id: PortalIconId, modifier)   // resolves format + applies current iconColor
```

- Reads the active `iconColor` from a `CompositionLocal` (e.g. `LocalXmbPalette`).
- Mono vector + mono raster → `ColorFilter.tint(iconColor, BlendMode.SrcIn)`.
- Shaded media vectors → per the decision below.
- One place decides tinting; no call site hardcodes a color.

### Settings UI
Hook into the existing theme surface — `ColorSchemePickerOverlay` /
`ThemesSettingsScreen` already let users pick schemes. Add an **"Icon Color"** control
(swatch row + custom picker) that writes `iconColorOverride`. Live preview via the
existing theme state flow.

## ⚠ Official Sony constraint — flat single-color icons are discouraged

Sony's **Custom Theme Creation Guidelines v5.00** explicitly warns against exactly the
naive version of this feature:

> "When creating an icon, avoid painting the icon with a single color. This is because the
> user can set any arbitrary background color using the wallpaper setting function. Icons
> should be designed with gradations or borders so they can be easily distinguished from
> arbitrary background images."

A flat unified-color icon can vanish against a matching wallpaper. This does **not** kill
the feature — the real XMB tints icons too — but the unified tint must preserve visibility:

- **Add a contrasting outline / border** to tinted icons (1–2px stroke, or a subtle
  drop-shadow), so they read against any background. This is how the stock XMB stays legible.
- **Or** keep the icon's internal gradation and only shift hue (the `ColorMatrix` path),
  rather than flattening to one flat color.
- **Contrast guard:** if the chosen icon color is close to the wallpaper's dominant color,
  auto-apply the outline (or nudge lightness). Cheapest safe default: always draw the outline.

Bake this into the `PortalIcon` façade so every tinted icon gets the visibility treatment in
one place. See [official-ptf-template.md](official-ptf-template.md) for the full spec.

## Open decision — shaded media icons under a unified color

The ~6 shaded media-disc vectors can't both "be the unified color" and keep their 3D
shading. Pick one:

| Option | Result | Note |
|---|---|---|
| **A. Flatten** (recommended for true unification) | They become solid silhouettes in the chosen color, exactly like every other icon | Simplest, fully consistent; loses the disc shading |
| **B. Hue-rotate** | They keep shading but shift toward the color | Preserves detail; color won't match exactly |
| **C. Exclude** | They stay natural; everything else recolors | Cleanest look for those 6, but not "everything is one color" |

Recommendation: **A** for a literal one-color result; revisit if the media icons look too
flat. (Alternatively, convert those 6 to mono vectors so A looks intentional.)

## Vectorize the raster icon set (scaling sharpness)

**First-class step, not optional.** The `catbar_*` (8, 256px) and `sysicon_*` (44, 128px)
PNGs are the only assets that go fuzzy when scaled onto large XMB tiles — a fixed-resolution
raster can't be sharpened up (edge filters add crunch/halos but don't recover detail).
Vectors are resolution-independent, so this is the durable fix, and it doubles as a tinting
win (crisp *and* recolorable). They're monochrome silhouettes, which trace cleanly.

- **Convert `catbar_*` (8) and `sysicon_*` (44) → VectorDrawable / `ImageVector`.** Auto-trace
  the silhouettes (e.g. potrace → SVG → `ImageVector`), then hand-clean. Because they're
  single-color alpha shapes, tracing is reliable.
- **Stopgap for any raster that stays** (during migration, or icons not yet vectorized):
  render with **`filterQuality = FilterQuality.None`** for crisp nearest-neighbor scaling
  instead of the blurry bilinear default. Blocky, but sharp — acceptable until the vector
  lands. Wire this into the `PortalIcon` façade so raster fallbacks get it automatically.
- **Do NOT vectorize/ship the extracted PSP theme icons** — they're Sony's copyrighted art
  and the textured ones don't trace cleanly anyway. Reference only; author our own.

## Supporting work

1. **`#C8A840` cleanup** — the amber accent is hardcoded in 49 places across the media
   drawables. Pull it into a color token so it's swappable, not painted-in.

## Phased implementation

1. **Model + persistence** — add `iconColor` to `XmbPalette`, `iconColorOverride` to
   DataStore, resolve defaults per scheme.
2. **`PortalIcon` façade + `LocalXmbPalette`** — one component, semantic icon registry
   (`PortalIconId` → vector/raster/material source). Raster fallbacks render with
   **`filterQuality = FilterQuality.None`** (crisp nearest-neighbor) so nothing looks fuzzy
   scaled up before it's vectorized.
3. **Tint wiring** — `SrcIn` for mono (incl. `CategoryIconGlyph`, which gains a
   `colorFilter`); apply the media-icon decision.
4. **Vectorize `catbar_*` (8) + `sysicon_*` (44)** — trace the silhouettes to
   VectorDrawable/`ImageVector` for resolution-independent scaling; register them under
   their `PortalIconId`s so the façade prefers the vector.
5. **Settings control** — icon-color picker in the theme settings screen.
6. **Migrate call sites** — replace direct `Icon`/`Image`/`Icons.*` icon usage with
   `PortalIcon` incrementally.
7. **Cleanup** — `#C8A840` → token.

## Non-goals (for now)

- PSP button-hint / battery / volume overlays are not part of this (system-locked in the
  PSP format; see [official-ptf-template.md](official-ptf-template.md)).
- Per-icon individual colors — this feature is deliberately **one unified color**.
