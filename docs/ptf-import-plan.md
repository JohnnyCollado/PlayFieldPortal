# PTF Import — Convert an old PSP theme into a PlayFieldPortal theme

Let users bring in a `.ptf` file they already own and turn it into a PlayFieldPortal theme.
We ship our **own** icons — so importing a PSP theme means capturing its **look**
(wallpaper + color), rendered with our icon set. No PSP icons are imported.

## Goal

`user picks *.ptf` → we produce a PlayFieldPortal theme (custom wallpaper + derived color
scheme) they can preview and apply.

## What converts — and what we deliberately drop

| PTF element | Convert? | How |
|---|---|---|
| **Wallpaper** (slot ID 1) | ✅ | zlib → 24-bit BMP → PNG → app custom wallpaper. Clean, exact, verified. |
| **Theme color / accent** | ✅ | **Derived from the wallpaper's dominant hue** → drives wave + gradient + icon tint. Verified: cookies→amber, classypink→pink, auto. |
| **Theme name** (header @0x08) | ✅ | Read ASCII name → theme title |
| **Icons** (category / first / second level) | ❌ | We ship our own. PSP icons are 48–64px (fuzzy when scaled), would clash with our set, and aren't needed for the "feel." |
| **Wave graphic, sounds, boot** | ❌ | Not part of our theme model |

Why derive color from the wallpaper instead of reading the PSP's stored theme color: the PSP
only stores one of 12 fixed presets (or none), and slot ID 4 turned out to be a GIM icon, not
a color block. The wallpaper-derived accent is both **more faithful** (matches what the user
actually sees) and **universal** (works for any `.ptf`, including ones with no theme color).

## Pipeline

1. **Pick** — SAF file picker for `*.ptf`.
2. **Parse + validate** — check `\0PTF` magic; read the 5-slot table at `0x100`; read name
   at `0x08`. Reject non-PTF / CXMB `.ctf` with a clear message.
3. **Extract wallpaper** — slot ID 1 → find zlib stream → inflate → it's a `BM` 24-bit BMP →
   decode → save PNG to app storage. (If a theme has no wallpaper slot, fall back to a solid
   background from the derived color.)
4. **Derive palette** — dominant saturated hue of the wallpaper → `waveColor`; reuse the
   existing `lightBackgroundAnchors(waveColor)` to get the gradient; feed the same color to
   the unified `iconColor` (see [icon-system-plan.md](icon-system-plan.md)).
5. **Build theme** — create a `ThemeEntity` (name + wallpaper path + palette). Persist the
   wallpaper via the existing `KEY_CUSTOM_WALLPAPER` flow.
6. **Preview → apply** — show it on the XMB (reuse the existing wallpaper preview overlay);
   user confirms.

## Maps onto existing app infrastructure

- **Custom wallpaper** — `KEY_CUSTOM_WALLPAPER` / `WallpaperBackground` already exist; the
  importer just writes to the same preference.
- **Color** — `XmbPalette` + `lightBackgroundAnchors()` already derive a full gradient from a
  single wave color, so we only need to compute one accent color from the wallpaper.
- **Theme storage** — `ThemeEntity` / `ThemeDao` already persist themes.
- **Unified icon color** — the derived accent can also set the icon tint, so an imported PSP
  theme automatically recolors our icons to match its mood.

## Copyright / scope note

This is a **personal-use conversion tool**: the user imports a `.ptf` **they already own**,
on their device, and we extract only the wallpaper + a derived color. We do not bundle,
redistribute, or ship any PSP/Sony theme assets. Wallpaper content is the user's own.

## Technical facts the importer relies on (verified)

- `.ptf` = 5-slot container (ID 0–4); wallpaper is in **ID 1**.
- Wallpaper is a **zlib'd 24-bit BMP**, *not* a GIM — decode directly, no swizzle.
- Full byte-level format in [official-ptf-template.md](official-ptf-template.md).

## Open confirmation

- **Icons stay ours (recommended).** Import captures wallpaper + color only. If we ever want
  an optional "also pull the PSP icons" mode, it's a separate, lower-value add-on (and runs
  into the low-res + copyright issues above).
