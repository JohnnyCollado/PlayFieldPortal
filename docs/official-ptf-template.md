# Official PSP Theme (PTF) — Template Spec

Template surface for **official Sony PTF themes only** (`.ptf`). A template is
**wallpaper + a color palette + an accent + an optional XMB icon set**.

Unlike CXMB (`.ctf`), which replaces the entire XMB via a raw `flash0:` filesystem, the
official format themes a **fixed-order icon atlas**: you supply replacement icons for
known XMB slots (or leave them to fall back to PSP system defaults). It is more constrained
than CXMB, but it *does* theme the icons — not just the wallpaper.

## Target

| Property | Value |
|---|---|
| Format | Official Sony PTF (`.ptf`) |
| Firmware baseline | **6.20** (newest official; safest on real hardware + Adrenaline) |
| Screen / wallpaper size | **480 × 272** (fixed — PSP native resolution) |
| Icon/preview-icon format | GIM, **256-color CLUT (IDX8)** — PSP-**swizzled** (see note below) |
| Wallpaper / preview-image format | **24-bit uncompressed BMP** (`BM…`), zlib-compressed — *not* a GIM |

## Container structure (reference)

Every official theme is the same fixed container — not a free-form archive:

```
.ptf file
├── Header (0x00–0xFF)
│    magic "\0PTF" · internal name @0x08 · firmware tag @0xB8 ("3.70".."6.20")
├── Resource table @0x100  → up to 5 pointers
└── 5 fixed resource slots, ID 0–4, each = [ ID(2) · subtype(2) · size(4) · dataOffset(4) ]
     └── payload = zlib streams → GIM icon textures + a 24-bit BMP wallpaper + color/config
```

The official format has **exactly 5 slots (ID 0–4)** — that is the entire customization
surface. ~30 of 35 sample themes fill all 5; minimal ones fill fewer.

### ⚠ Encoding notes (verified by decoding Sony's own `classypink.ptf` / `cookies.ptf`)

- **Icons + preview icon = GIM, and PSP-swizzled.** Pixels are stored in a tiled layout
  (16-byte × 8-row blocks) whenever the GIM header's `pixelOrder == 1`. A **decoder must
  unswizzle**; an **encoder must swizzle**. This applies to *all* GIM textures — the paletted
  icons *and* the RGBA preview image — not just the icons.
- **Wallpaper + preview image are plain 24-bit BMP** (`BM…`), zlib-compressed, stored in the
  image slot — **not** GIM, not swizzled. Encoder path: source PNG/JPG → 24-bit BMP → zlib →
  slot. Decoder path: unzip slot → it's a standard BMP, open directly.
- **CLUT / palette:** icon GIMs carry a 256-entry RGBA8888 palette in the GIM `0x05` chunk;
  the pixel data is 8-bit indices in the `0x04` chunk.

## Manifest (v1)

```jsonc
{
  "manifest": "official-ptf-template",
  "version": 1,
  "targetFirmware": "6.20",
  "name": "",                    // theme display name -> PTF header @0x08 (max 16 chars, ASCII)

  "fields": {

    "wallpaper": {                       // -> PTF slot ID 1 (image)
      "type": "image",
      "required": true,
      "width": 480,
      "height": 272,
      "alpha": false,                    // opaque; background sits behind everything
      "accepts": ["png", "jpg", "bmp"],
      "note": "Exact 480x272. Anything else is rejected or must be pre-cropped."
    },

    "colors": {                          // -> PTF slot ID 4 (color/config block)
      "type": "palette",
      "required": true,
      "slots": {
        "textPrimary":   { "rgba": "#FFFFFFFF", "desc": "Unselected menu item text" },
        "textSecondary": { "rgba": "#B0B0B0FF", "desc": "Subtext / descriptions / clock" },
        "textSelected":  { "rgba": "#FFFFFFFF", "desc": "Highlighted item text" },
        "backdrop":      { "rgba": "#000000FF", "desc": "Base tint behind the wallpaper" }
      }
    },

    "wave": {                            // -> PTF slot ID 2 / ID 3 (waveform graphics)
      "type": "accent",
      "required": false,
      "color":   { "rgba": "#3A6EA5FF", "desc": "Flow-line / wave color" },
      "graphic": { "type": "image", "optional": true, "note": "Custom wave sheet; omit to keep default wave shape" }
    },

    "icons": {                           // -> PTF slot ID 0 (icon atlas, IDX8/paletted)
      "type": "iconSet",
      "required": false,
      "incremental": true,               // supply only the icons you override; rest = PSP default
      "format": "IDX8",                  // 8-bit paletted -> recolor a whole set by swapping palette
      "slots": {
        "cursor":       { "w": 16, "h": 16, "desc": "XMB pointer/cursor" },
        "categoryBar":  { "w": 64, "h": 48, "count": "6-8", "desc": "Top column icons: Settings/Photo/Music/Video/Game/Network/PSN" },
        "categoryLarge":{ "w": 64, "h": 64, "count": "0-30", "desc": "Category icons at focused/large scale" },
        "functions":    { "w": 48, "h": 48, "count": "0-60", "desc": "Function / option / submenu icon set (the bulk)" },
        "badges":       { "w": 32, "h": 32, "count": "0-50", "desc": "Small status / indicator badges (also 34x32)" }
      }
    }
  }
}
```

## Slot mapping (assembler-side; not shown to users)

| Manifest field | PTF slot | Encoded as |
|---|---|---|
| `wallpaper` | **ID 1** | **24-bit BMP** (zlib'd), 480×272 — *not* GIM |
| `icons` | **ID 0** | GIM IDX8 (256-color CLUT), **swizzled**, sizes 16/32/48/64 |
| `wave.graphic` | **ID 2 / ID 3** | GIM (swizzled) |
| `colors`, `wave.color` | **ID 4** | RGBA color/config block |
| `name` | header @0x08 | ASCII, ≤16 bytes |
| *(preview image)* | ID 0 | **GIM RGBA, swizzled**, **300×170** — theme-picker image; auto-generate from the wallpaper |

## XMB icon set — OFFICIAL spec

Source of truth: **Sony "PSP Custom Theme Creation Guidelines v5.00"** (official). The
reverse-engineered dimensions matched this exactly; the table below is the authoritative
taxonomy. Icon images are **256-color 32-bit CLUT (alpha enabled)** — i.e. IDX8 with an
RGBA palette — accepted as **PNG / TGA / GIM**. The `.ptf` is produced by Sony's *Custom
Theme Converter*.

| Element | Body size | Focus size | Format | Notes |
|---|---|---|---|---|
| **Category icons** (horizontal list) | **64×48** | — | 256-color CLUT, PNG/TGA/GIM | **8 types** (Settings/Photo/Music/Video/Game/Network/PSN/…); Network shown only on JP/KR + PSP-2000/3000 |
| **First-level icons** (vertical list under a category) | **48×48** | **64×64** | 256-color CLUT, PNG/TGA/GIM | UMD & Memory Stick icons are customized once, regardless of category |
| **Second-level icons** (Settings sub-items only — the "wrench" list) | **32×32** | **48×48** | 256-color CLUT, PNG/TGA/GIM | Only the Settings category exposes these |
| **Wallpaper** | **480×272** | — | **24-bit color RLE uncompressed bitmap** | Not paletted |
| **Preview icon** | **16×16** | — | 256-color CLUT, PNG/TGA/GIM | The small theme-list icon |
| **Preview image** | **300×170** | — | **24-bit color RLE uncompressed** | Shown centered in the theme picker |

Key rules straight from the guidelines:

- **Every icon needs BODY + FOCUS art.** "Focus" is the selected state and is a *larger*
  image (48→64, 32→48) — not merely a glow. This is what appears as icon "pairs" in a `.ptf`.
- **Incremental / default fallback.** "The default icon is used for icons that have not been
  set. If the default icon itself has not been set, the original [PSP system] icon is used."
  So a theme only needs to supply the icons it changes.
- **12 theme colors**, or none. Selected via **Settings ▸ Theme Settings ▸ Theme**; sets the
  XMB background color. (`XmbColorScheme` in the app currently offers 7 — see the plan.)
- **⚠ Do NOT use flat single-color icons.** Direct quote: *"avoid painting the icon with a
  single color… the user can set any arbitrary background color… Icons should be designed
  with gradations or borders so they can be easily distinguished from arbitrary background
  images."* A single-color icon becomes invisible on a matching wallpaper. **This directly
  constrains the unified-icon-color feature** — see [icon-system-plan.md](icon-system-plan.md).
- **Metadata limits:** name ≤128-byte UTF-8, Product ID ≤48 bytes, Version ≤8 bytes (up to
  three 0–99 numbers). Usable chars for IDs: half-width alphanumerics, `-`, `_`.
- **Install path:** `/PSP/THEME` on the Memory Stick.

## Validation rules

1. `wallpaper` **must** be exactly 480×272 — reject or force-crop; do not silently upscale.
2. `name` — ASCII only, ≤16 chars (longer names corrupt the header).
3. All colors are 8-digit RGBA hex (`#RRGGBBAA`); alpha `FF` unless intentionally translucent.
4. `wave.graphic` and `icons` are optional and **incremental** — supply only what you
   override; anything omitted falls back to PSP system defaults (or a baseline PTF such as
   `Carbon Fiber.ptf` / `Original Sony Theme`). All icons must be authored as IDX8 (≤256
   colors) or down-converted at encode time.
5. Pin every template to `targetFirmware: 6.20` — don't mix firmware bases in one library.

## Minimum viable template

A user only *needs* `name` + `wallpaper` + `colors`. That covers what ~90% of official
themes actually do. `wave` and `iconSheet` are power-user extras.

## Worked example

```jsonc
{
  "manifest": "official-ptf-template", "version": 1, "targetFirmware": "6.20",
  "name": "Neon Blue",
  "fields": {
    "wallpaper": { "type": "image", "src": "neon_bg.png" },      // 480x272
    "colors": {
      "textPrimary":   "#E0F7FFFF",
      "textSecondary": "#7FB8C8FF",
      "textSelected":  "#FFFFFFFF",
      "backdrop":      "#05101AFF"
    },
    "wave": { "color": "#22D3EEFF" }
  }
}
```

This maps 1:1 onto both a PSP-hardware `.ptf` export (via the ID 0–4 assembler) and the
in-app XMB renderer (which consumes `wallpaper` + `colors` + `wave.color` directly).
