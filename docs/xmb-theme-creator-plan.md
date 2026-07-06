# XMB Theme Creator — a simple theming flow for users

Capstone plan tying together the icon system, PTF import, and color model into **one easy
way for users to make their XMB their own**. Companion docs:
[official-ptf-template.md](official-ptf-template.md) (PSP format research),
[icon-system-plan.md](icon-system-plan.md) (unified icon color),
[ptf-import-plan.md](ptf-import-plan.md) (import old themes).

## The core idea — one color drives everything

The whole plan rests on a single insight from the research: on the XMB, **a user picks one
color and the entire theme follows.** We already have the machinery:

- `lightBackgroundAnchors(waveColor)` derives the full background gradient from one color.
- Icons tint from one color via `ColorFilter.tint(SrcIn)` (vectors *and* raster silhouettes).
- A wallpaper's dominant hue yields a faithful accent automatically (verified on Sony's own
  themes).

So the user's job is tiny: **pick a background, pick a color.** Everything else is derived.
This mirrors the official PSP model ("background, color and icon design") but — because we
ship our own icons — collapses "icon design" into just recoloring.

## What a PlayFieldPortal theme is

```
Theme {
  name:        String
  wallpaper:   Image?          // null → animated wave background
  accentColor: Color           // THE one color: wave, gradient, cursor, highlights
  iconColor:   Color = auto    // defaults to derive-from-accent; user can override
  waveStyle:   { animated | static | reduced }
  // derived, never hand-set: backgroundTop/Bottom = lightBackgroundAnchors(accentColor)
  //                          textColor = auto contrast (light on dark, etc.)
}
```

## Four ways in (progressive disclosure — most users never leave the first two)

### 1. Presets — zero effort
Tap a built-in theme. Bump `XmbColorScheme` from 7 → **12 presets** (parity with the PSP's
twelve theme colors), including the month-cycling "Original."

### 2. Quick Create — the main flow, two choices
1. **Background** — pick a photo, or "None → live wave."
2. **Color** — a swatch grid + custom color wheel.

…then Save. Wave, gradient, cursor, and icon tint all update live on the real XMB behind the
picker. If they pick a wallpaper, we can pre-fill the color from its dominant hue so even
step 2 is optional.

### 3. Import an old PSP theme — nostalgia path
Pick a `.ptf` → auto-extract wallpaper + derive color → Save. Zero manual work. (See
[ptf-import-plan.md](ptf-import-plan.md).)

### 4. Fine-tune — advanced, collapsed by default
For power users: separate **icon color**, **text color**, **wave style**, and a manual
**background gradient** override. Hidden behind an "Advanced" expander so it never
complicates the common path.

## Simplicity guardrails (invisible; users can't make a bad theme)

These come straight from the research and run automatically so the user never has to think
about legibility:

- **One-color cascade** — never ask for five colors; derive the palette from one.
- **Auto contrast, always readable** — the existing wallpaper scrim (`0x59000000`) keeps
  labels/icons legible over any photo; for a *dark* chosen icon color we auto-add a subtle
  light outline/halo (Sony's own themes use exactly this on focus icons). Light/mid colors
  need nothing. So no combination produces an unreadable XMB.
- **"Auto" icon color by default** — derived from the accent; overriding is opt-in.
- **Live preview on the actual XMB** — what they see is what they get, always.
- **Soft-wallpaper hint** — Sony's examples are muted, low-contrast photos; optionally nudge
  users toward those (or just rely on the scrim). Non-blocking.

## Save · switch · export

- **Save** named themes via `ThemeEntity` / `ThemeDao`; apply/switch instantly.
- **Export** a theme as a `.pfptheme` bundle so users can back up and share their creations —
  a modern take on PSP theme-sharing. Importing is the same pipeline as PTF import, minus the
  PSP parsing.

### Preview before export (required step)

The PSP format bakes in a **preview image (300×170) + preview icon (16×16)** precisely so a
theme can be seen before it's applied. We do the same: **export always goes through a preview
gate.**

1. **Show the preview** — when the user taps Export, render a representative XMB frame with
   the theme fully applied (wallpaper + gradient + wave + category bar with *our* icons tinted
   + cursor + a couple of sample labels). Reuse the existing wallpaper-preview overlay; the
   only new work is compositing the icon row into it.
2. **Confirm or go back** — the user sees exactly what the theme looks like and either
   confirms or returns to tweak. Nothing is written until they confirm.
3. **Generate + embed the thumbnail** — on confirm, render that same XMB frame off-screen to a
   bitmap (Compose `GraphicsLayer.toImageBitmap()` / draw-to-bitmap), downscale to a standard
   preview size, and **write it into the `.pfptheme`**. Then anyone importing/browsing the
   file sees the preview *without applying it* — same UX as the PSP theme picker.

### `.pfptheme` file format

A plain zip with a stable layout — small, inspectable, forward-compatible:

```
mytheme.pfptheme  (zip)
├── manifest.json      // { name, accentColor, iconColor, waveStyle, schemaVersion }
├── wallpaper.png      // optional (absent → live wave)
└── preview.png        // the rendered preview thumbnail (always present)
```

Import reads `manifest.json` → rebuilds the `Theme`; shows `preview.png` in the picker;
applies `wallpaper.png` via the existing `KEY_CUSTOM_WALLPAPER` flow. Because the palette is
derived from `accentColor`, the bundle stays tiny (a JSON + one or two PNGs).

## Build order

1. **Theme model + persistence** — `Theme` entity (name, wallpaper, accentColor, iconColor,
   waveStyle); derive gradient via existing `lightBackgroundAnchors()`.
2. **One-color cascade wiring** — accent → wave + gradient + cursor + `iconColor`; icons
   tinted through the `PortalIcon` façade (see [icon-system-plan.md](icon-system-plan.md)).
3. **Quick Create screen** — background picker + color picker + live XMB preview + Save.
4. **Presets to 12** — extend `XmbColorScheme`.
5. **PTF import** — the nostalgia path ([ptf-import-plan.md](ptf-import-plan.md)).
6. **Fine-tune panel** — advanced overrides, collapsed.
7. **Preview gate + export** — preview screen (themed XMB frame) → off-screen render to a
   thumbnail → write the `.pfptheme` bundle (manifest + wallpaper + embedded `preview.png`).
   Import reads the same format and shows `preview.png` in the picker.

## Non-goals

- Re-drawing/replacing individual icons — we ship our own; theming = recolor, not redraw.
- Reproducing PSP-only elements (button hints, sounds, boot) — out of scope.
- Per-icon individual colors — the model is deliberately one unified accent + optional icon
  color.
