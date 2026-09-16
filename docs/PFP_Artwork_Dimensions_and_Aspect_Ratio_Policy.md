# Play Field Portal — Artwork Dimension & Aspect Ratio Policy

## Purpose

This document defines the default artwork dimensions and aspect-ratio behavior for **Play Field Portal (PFP)**.

The goal is to provide consistent artwork handling across:

- Artwork Studio
- Box Art previews
- XMB game icons
- placeholder artwork
- local-file artwork imports
- ScreenScraper artwork
- SteamGridDB artwork
- TheGamesDB artwork
- IGDB artwork
- ES-DE artwork imports

The system should prefer the **actual dimensions of downloaded artwork whenever possible** and only fall back to platform defaults when no better dimensional information is available.

These defaults are based primarily on the packaging conventions represented by **ScreenScraper** and the physical packaging used by each platform.

---

# Core Design Principle

## Do Not Force Artwork Into One Resolution

ScreenScraper does **not** define one universal pixel resolution for every artwork type.

For example, `box-2D` artwork may vary depending on:

- platform
- region
- source scan
- uploaded image
- packaging type
- game release

PFP should therefore treat the values in this document as **canonical crop/canvas proportions**, not mandatory output resolutions.

Example:

```text
PS2 default box canvas: 430 × 600
```

This means:

```text
Aspect ratio = 430 / 600 ≈ 0.717
```

A downloaded PS2 cover that is:

```text
1000 × 1395
```

should NOT automatically be resized to `430 × 600`.

Instead:

```text
Preserve original image
Preserve original resolution
Use ~0.717 as the fallback crop/placeholder ratio
```

The dimensions in this document therefore act primarily as:

- crop-frame defaults
- placeholder dimensions
- expected artwork proportions
- layout hints
- provider fallback ratios

---

# Artwork Source Priority

When determining the dimensions or crop ratio for artwork, PFP should use this order:

1. **Actual source image dimensions**
2. **ScreenScraper media dimensions**
3. **Artwork-provider supplied dimensions**
4. **Platform-specific PFP preset**
5. **Generic artwork-type fallback**

If the selected artwork provides reliable intrinsic dimensions, those dimensions should always win over a hard-coded platform preset.

---

# Region Priority

PFP currently prefers ScreenScraper artwork approximately in this order:

```text
US
World
Untagged
Other region
```

The default packaging dimensions in this document therefore lean toward **US packaging** when major regional differences exist.

However, if a user selects a different regional artwork asset, the selected artwork's actual aspect ratio should take priority.

---

# Universal Artwork Defaults

These artwork types generally do not need unique dimensions for every console.

| Artwork Type | Default Canvas | Behavior |
|---|---:|---|
| ICON0 | 144 × 80 | Fixed crop |
| ICON1 / Video Snap | 144 × 80 viewport | Fit/crop into ICON0 viewport |
| Hero | 920 × 430 | Fixed crop |
| Background | 1920 × 1080 | Fixed 16:9 crop |
| Logo / Wheel | 600 × 300 bounding canvas | Contain, never crop |
| Screenshot | Source dimensions | Preserve aspect |
| Title Screen | Source dimensions | Preserve aspect |
| 3D Box | Source dimensions | Preserve transparent bounds |
| Physical Media | Source dimensions | Preserve transparent bounds |
| Manual | Source/PDF | Never crop |
| Video | Source aspect | Preserve source aspect |
| Box Art | Platform-specific | Use platform crop policy below |

---

# Box Art Platform Defaults

These dimensions define the default **box-front canvas** for each PFP platform.

They should be treated as aspect-ratio presets rather than mandatory image resolutions.

## Sony

| PFP ID | Platform | Default Canvas | Ratio |
|---|---|---:|---:|
| `psx` | PlayStation | 600 × 600 | 1.00 |
| `ps2` | PlayStation 2 | 430 × 600 | 0.72 |
| `ps3` | PlayStation 3 | 480 × 600 | 0.80 |
| `psp` | PlayStation Portable | 354 × 600 | 0.59 |
| `psvita` | PlayStation Vita | 468 × 600 | 0.78 |

### Notes

PSP and Vita **must not share the same ratio**.

The current implementation has historically treated both as approximately `0.59`, which is acceptable for PSP but too narrow for Vita.

---

## Nintendo

| PFP ID | Platform | Default Canvas | Ratio |
|---|---|---:|---:|
| `nes` | Nintendo Entertainment System | 430 × 600 | 0.72 |
| `snes` | Super Nintendo | 600 × 438 | 1.37 |
| `n64` | Nintendo 64 | 600 × 438 | 1.37 |
| `gb` | Game Boy | 600 × 600 | 1.00 |
| `gbc` | Game Boy Color | 600 × 600 | 1.00 |
| `gba` | Game Boy Advance | 600 × 600 | 1.00 |
| `nds` | Nintendo DS | 540 × 600 | 0.90 |
| `n3ds` | Nintendo 3DS | 540 × 600 | 0.90 |
| `gc` | GameCube | 430 × 600 | 0.72 |
| `wii` | Wii | 430 × 600 | 0.72 |
| `wiiu` | Wii U | 430 × 600 | 0.72 |
| `switch` | Nintendo Switch | 366 × 600 | 0.61 |
| `virtualboy` | Virtual Boy | 600 × 600 | 1.00 |

### Regional Exceptions

SNES and Nintendo 64 packaging vary significantly by region.

For US artwork:

```text
SNES → landscape
N64  → landscape
```

Japanese and PAL artwork may use substantially different proportions.

Therefore:

```text
If actual source dimensions exist:
    use source aspect
Else:
    use the PFP default above
```

---

## Sega

| PFP ID | Platform | Default Canvas | Ratio |
|---|---|---:|---:|
| `megadrive` | Mega Drive / Genesis | 430 × 600 | 0.72 |
| `mastersystem` | Master System | 430 × 600 | 0.72 |
| `gamegear` | Game Gear | 424 × 600 | 0.71 |
| `saturn` | Sega Saturn | 600 × 600 | 1.00* |
| `dreamcast` | Sega Dreamcast | 600 × 600 | 1.00 |
| `segacd` | Sega CD / Mega CD | 420 × 600 | 0.70* |
| `sega32x` | Sega 32X | 430 × 600 | 0.72 |

`*` = region-sensitive packaging.

Saturn and Sega CD packaging differ substantially by region.

The selected artwork's actual source dimensions should take priority.

---

## Atari

| PFP ID | Platform | Default Canvas | Ratio |
|---|---|---:|---:|
| `atari2600` | Atari 2600 | 440 × 600 | 0.73 |
| `atari5200` | Atari 5200 | 440 × 600 | 0.73 |
| `atari7800` | Atari 7800 | 440 × 600 | 0.73 |
| `atarilynx` | Atari Lynx | 488 × 600 | 0.81* |

`*` Atari Lynx packaging can vary.

Prefer actual source dimensions when available.

---

## NEC

| PFP ID | Platform | Default Canvas | Ratio |
|---|---|---:|---:|
| `pcengine` | PC Engine / TurboGrafx-16 | 600 × 600 | 1.00* |

Packaging differs between PC Engine and TurboGrafx releases.

Treat this primarily as a fallback.

---

## SNK

| PFP ID | Platform | Default Canvas | Ratio |
|---|---|---:|---:|
| `neogeo` | Neo Geo | 480 × 600 | 0.80 |
| `ngp` | Neo Geo Pocket | 600 × 600 | 1.00 |

---

## Bandai

| PFP ID | Platform | Default Canvas | Ratio |
|---|---|---:|---:|
| `wonderswan` | WonderSwan | 600 × 600 | 1.00 |
| `wonderswancolor` | WonderSwan Color | 600 × 600 | 1.00 |

---

## Commodore

| PFP ID | Platform | Default Canvas | Ratio |
|---|---|---:|---:|
| `c64` | Commodore 64 | 430 × 600 | Variable |

C64 packaging is inconsistent.

The default should only be used when no usable source dimensions exist.

---

## Arcade

| PFP ID | Platform | Default Canvas | Ratio |
|---|---|---:|---:|
| `mame` | Arcade / MAME | 600 × 600 | Variable |
| `cps1` | Capcom Play System | 600 × 600 | Variable |
| `cps2` | Capcom Play System II | 600 × 600 | Variable |
| `cps3` | Capcom Play System III | 600 × 600 | Variable |

Arcade games do not have a consistent consumer box format.

Therefore these should strongly prefer:

```text
Source dimensions
```

The 600 × 600 value is only a safe fallback for previews and empty placeholders.

---

## Microsoft

| PFP ID | Platform | Default Canvas | Ratio |
|---|---|---:|---:|
| `x360` | Xbox 360 | 430 × 600 | 0.72 |

---

## PC / Android

| PFP ID | Platform | Default Canvas | Ratio |
|---|---|---:|---:|
| `windows` | Windows Games | 600 × 600 | Variable |
| `android` | Android Games | 600 × 600 | Variable |

Windows and Android titles do not have a reliable physical packaging standard.

For these platforms:

```text
Prefer provider/source aspect
```

The square canvas exists only as a fallback.

---

# Source-Aware Platforms

The following platforms should be considered especially sensitive to regional or packaging variation:

```text
snes
n64
saturn
segacd
atarilynx
pcengine
c64
mame
cps1
cps2
cps3
windows
android
```

For these platforms, hard-coded dimensions should never override known source dimensions.

---

# Recommended Data Model

Create one centralized artwork-dimension model.

Example conceptual structure:

```kotlin
data class ArtworkCanvas(
    val width: Int,
    val height: Int,
    val sourceAspectPreferred: Boolean = false,
) {
    val aspectRatio: Float
        get() = width.toFloat() / height.toFloat()
}
```

A platform mapping can then define the defaults.

Example:

```kotlin
val BOX_ART_CANVAS = mapOf(
    "psx" to ArtworkCanvas(600, 600),
    "ps2" to ArtworkCanvas(430, 600),
    "ps3" to ArtworkCanvas(480, 600),
    "psp" to ArtworkCanvas(354, 600),
    "psvita" to ArtworkCanvas(468, 600),

    "snes" to ArtworkCanvas(600, 438, sourceAspectPreferred = true),
    "n64" to ArtworkCanvas(600, 438, sourceAspectPreferred = true),

    "windows" to ArtworkCanvas(600, 600, sourceAspectPreferred = true),
    "android" to ArtworkCanvas(600, 600, sourceAspectPreferred = true),
)
```

This is only an example.

Claude should determine the most appropriate package/module location based on the current architecture.

---

# Single Source of Truth

Do **not** duplicate these platform ratios across multiple UI components.

There should be one shared source used by:

- Artwork Studio
- XMB placeholder artwork
- Game Detail
- Artwork previews
- box-art crop editor
- local-file import
- downloaded-provider artwork
- ES-DE imported artwork
- any future Library grid using box art

The existing `boxArtAspectFor()` behavior should ideally become a thin wrapper around the centralized policy rather than containing its own independent hard-coded table.

Example:

```kotlin
fun boxArtAspectFor(platformId: String?): Float {
    return ArtworkDimensions.boxArt(platformId).aspectRatio
}
```

---

# Existing Behavior To Correct

The current rough aspect-ratio approach groups several systems together.

One important example:

```text
PSP  → ~0.59
Vita → ~0.59
```

This should become:

```text
PSP  → ~0.59
Vita → ~0.78
```

Other generic `0.70` fallback cases should be replaced with the platform-specific values in this document where applicable.

---

# Artwork Studio Behavior

## When Opening Existing Artwork

When the user opens artwork in Artwork Studio:

```text
Read actual bitmap dimensions
↓
Calculate intrinsic aspect ratio
↓
Use intrinsic ratio for preview/crop
```

The platform preset should not alter existing artwork.

---

## When Selecting Provider Artwork

When artwork comes from:

- ScreenScraper
- SteamGridDB
- TheGamesDB
- IGDB
- another artwork provider

PFP should:

```text
1. Read candidate dimensions if provider supplies them
2. Otherwise inspect the downloaded image
3. Use source aspect
4. Fall back to platform preset only when dimensions cannot be determined
```

---

## Local Files

When the user selects a local file:

```text
Read bitmap dimensions
Use actual source aspect
```

The platform preset should only define the **initial crop target** when a fixed box-front crop is required.

Do not stretch artwork to the default canvas.

---

# Crop Editor Behavior

## Fixed Artwork Types

The following should retain fixed crop targets:

### ICON0

```text
144 × 80
```

### Hero

```text
920 × 430
```

### Background

```text
16:9
Recommended reference canvas:
1920 × 1080
```

---

## Box Art

Box Art should use:

```text
actual selected artwork ratio
```

when known.

Otherwise:

```text
platform default ratio
```

The user should still be able to reposition and scale the artwork inside the crop frame.

---

## Artwork That Should Not Be Cropped By Default

Do not force crop behavior for:

```text
Logo
Wheel
3D Box
Physical Media
Screenshot
Title Screen
Manual
Video
```

These should normally use:

```text
ContentScale.Fit
```

or an equivalent contain-style layout.

Transparent artwork must retain its transparent bounds.

---

# Placeholder Behavior

When no box art exists, PFP should render its letter/gradient placeholder using the platform's default aspect ratio.

Example:

```text
PS1 placeholder   → square
PS2 placeholder   → tall DVD-style
PSP placeholder   → narrow handheld case
Vita placeholder  → wider handheld case
SNES placeholder  → landscape
N64 placeholder   → landscape
Switch placeholder → narrow vertical case
```

This provides recognizable platform silhouettes even before artwork is downloaded.

---

# ScreenScraper Integration

PFP currently consumes ScreenScraper media such as:

```text
box-2D
box-3D
support-2D
support-texture
fanart
wheel
wheel-hd
ss
manuel
video-normalized
video
```

These should continue mapping approximately as follows:

```text
box-2D
→ Box Art

box-3D
→ 3D Box

support-2D
support-texture
→ Physical Media

fanart
→ Hero / Background candidate

wheel
wheel-hd
→ Logo

ss
→ Screenshot

manuel
→ Manual

video-normalized
video
→ Video
```

Do not use the platform box-art dimensions for non-box artwork.

---

# ScreenScraper Download Strategy

When requesting ScreenScraper artwork, avoid unnecessarily downscaling artwork merely to match the preset dimensions.

The ideal behavior is:

```text
Download highest practical resolution
Store original
Render efficiently at UI size
```

If ScreenScraper request parameters are used to limit dimensions for bandwidth or memory reasons, they should act as **maximum bounds**, not as target crop dimensions.

Example:

```text
maxwidth = 2000
maxheight = 2000
```

is acceptable as a bandwidth cap.

Do not request:

```text
430 × 600
```

solely because PS2 uses that fallback canvas.

---

# Image Storage Policy

PFP should preserve the original artwork whenever practical.

Avoid destructive resize operations during:

```text
scrape
download
import
provider selection
```

A crop operation should ideally create either:

1. stored crop metadata, or
2. a generated derivative while retaining the source

depending on the current artwork architecture.

Do not discard a higher-resolution original simply because the launcher currently renders a much smaller image.

---

# Rendering Policy

The UI should distinguish between:

```text
Artwork Resolution
Artwork Aspect Ratio
Rendered UI Size
```

These are three separate concepts.

Example:

```text
Source:
1600 × 2232

Aspect:
0.717

PFP display:
62dp × 86dp
```

The source should remain `1600 × 2232`.

Compose/Coil handles the display scaling.

---

# Expected Fallback Logic

Conceptually:

```kotlin
fun resolveArtworkAspect(
    platformId: String?,
    sourceWidth: Int?,
    sourceHeight: Int?,
): Float {

    if (
        sourceWidth != null &&
        sourceHeight != null &&
        sourceWidth > 0 &&
        sourceHeight > 0
    ) {
        return sourceWidth.toFloat() / sourceHeight.toFloat()
    }

    return ArtworkDimensions
        .boxArt(platformId)
        .aspectRatio
}
```

The real implementation should follow the project's existing architecture and naming conventions.

---

# Generic Fallback

If a platform does not exist in the map:

```text
Default box art:
430 × 600
```

Ratio:

```text
~0.72
```

This represents a common DVD-style keep case and is a better fallback than square artwork for an unknown console.

For explicitly variable platforms such as Arcade, Windows, or Android, a square fallback may still be preferable.

---

# Acceptance Criteria

The implementation is complete when all of the following are true.

## Platform Presets

- [ ] Every built-in PFP platform has a box-art default.
- [ ] PSP and Vita use different ratios.
- [ ] SNES and N64 use landscape US defaults.
- [ ] Switch uses its narrow vertical case ratio.
- [ ] DS and 3DS use their wider handheld-case ratio.
- [ ] variable-format systems are marked source-preferred.

---

## Artwork Studio

- [ ] Existing artwork uses its actual intrinsic dimensions.
- [ ] ScreenScraper artwork uses actual source dimensions when available.
- [ ] Local-file artwork uses actual source dimensions.
- [ ] Platform presets are only fallbacks.
- [ ] Fixed ICON0 crop remains 144 × 80.
- [ ] Hero crop remains 920 × 430.
- [ ] Background remains 16:9.
- [ ] Logos are contained rather than cropped.
- [ ] 3D box art preserves transparent bounds.
- [ ] Physical media preserves transparent bounds.

---

## XMB

- [ ] Missing box-art placeholders use the correct platform ratio.
- [ ] Vita placeholders no longer use PSP proportions.
- [ ] SNES/N64 placeholders render landscape.
- [ ] Switch placeholders render narrow and vertical.
- [ ] Existing downloaded art retains natural aspect.

---

## Architecture

- [ ] No duplicate artwork-ratio tables remain.
- [ ] `boxArtAspectFor()` uses the shared dimension policy.
- [ ] Artwork Studio uses the same shared policy.
- [ ] Placeholder rendering uses the same shared policy.
- [ ] New platforms automatically receive a safe fallback.
- [ ] Unit tests cover representative platform ratios.

---

# Suggested Tests

Add unit tests covering at minimum:

```text
PS1
PS2
PSP
PS Vita
SNES
N64
Nintendo DS
Nintendo 3DS
Switch
Dreamcast
Xbox 360
Windows
Android
unknown platform
```

Examples:

```kotlin
assertEquals(1.0f, boxArtAspectFor("psx"), tolerance)

assertEquals(
    354f / 600f,
    boxArtAspectFor("psp"),
    tolerance
)

assertEquals(
    468f / 600f,
    boxArtAspectFor("psvita"),
    tolerance
)

assertEquals(
    600f / 438f,
    boxArtAspectFor("snes"),
    tolerance
)
```

Also verify that intrinsic image dimensions override presets.

Example:

```text
Platform = SNES
Preset = landscape

Downloaded artwork = 500 × 700

Expected resolved ratio:
500 / 700

NOT:
600 / 438
```

---

# Implementation Guidance For Claude

Before changing code:

1. Search for all existing artwork aspect-ratio constants.
2. Find every use of `boxArtAspectFor()`.
3. Find crop-frame definitions in Artwork Studio.
4. Find placeholder rendering logic.
5. Find ScreenScraper artwork download/storage logic.
6. Find image metadata or bitmap-dimension utilities already available.
7. Avoid creating duplicate utilities if equivalent infrastructure already exists.

Then:

1. Introduce the centralized dimension policy.
2. Migrate `boxArtAspectFor()` to it.
3. Update Artwork Studio.
4. Update placeholders.
5. Verify provider artwork keeps its intrinsic dimensions.
6. Add tests.
7. Run existing artwork/UI tests.
8. Confirm no visual regression for ICON0.

---

# Non-Goals

This task does **not** require:

- changing ScreenScraper matching logic
- changing metadata matching
- changing game IDs
- changing provider priority
- replacing existing artwork providers
- re-encoding every downloaded image
- automatically rescaling existing artwork files
- altering manual PDFs
- altering video files

The focus is strictly:

```text
Artwork dimensions
Artwork proportions
Crop behavior
Rendering behavior
Platform fallback policy
```

---

# Final Design Rule

The simplest rule for the whole implementation is:

> **Use the artwork's real dimensions when PFP knows them. Use the platform's physical packaging dimensions only when PFP does not.**

The platform presets are guard rails.

They should improve consistency without destroying source artwork or forcing every provider into the same shape.
