# Adjusted XMB Layout — measured reference and proportional formula

Captured 2026-09-02 from the live debug build (`com.playfieldportal.launcher.debug`)
on the **AYN Thor** (`ad3c2dd`, `kalama`), read straight out of the DataStore prefs.

Purpose: turn the hand-tuned Thor values into a device-independent rule so the Setup
Wizard and **Display ▸ Adjust XMB Layout** can auto-seed any screen with the same
PSP-authentic look instead of making the user re-nudge sliders.

---

## 1. Measured state

### 1.1 Saved preference (ground truth)

DataStore `pfp_prefs`, key `display_xmb_layout_adjust`:

```json
{"compact":{"scale":1.3199997,"barLeftFraction":-0.09999999,"barTopFraction":0.13}}
```

| Field             | Value     | Slider bounds                      | Meaning                                                       |
| ----------------- | --------- | ---------------------------------- | ------------------------------------------------------------- |
| `scale`           | **1.32**  | `SCALE_MIN 0.6` … `SCALE_MAX 1.8`  | extra density multiplier on top of the automatic canvas scale |
| `barLeftFraction` | **-0.10** | `LEFT_MIN -0.25` … `LEFT_MAX 0.35` | horizontal shift of the whole cross, fraction of canvas width |
| `barTopFraction`  | **0.13**  | `TOP_MIN 0.05` … `TOP_MAX 0.45`    | crossbar vertical position, fraction of canvas height         |

Only the **`compact`** bucket is tuned. `medium` and `expanded`
(`XmbFormFactor.forSmallestWidthDp`: `<600` / `<840` / `>=840` dp) are untouched —
they still fall back to the legacy path.

Notably **absent** from prefs, so both are at defaults and currently inert:

- `display_xmb_scale` (legacy `xmbScale`) -> `1.0`
- `display_bar_top_fraction` (legacy bar override) -> unset, so `XmbLayoutSpec.DEFAULT.barTopFraction = 0.11`

The adjust-map entry shadows both — see [XMBShell.kt:416](feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/XMBShell.kt:416),
where the bucket entry wins and the legacy `scale`/`barTopFraction` pair is only the fallback.

### 1.2 Thor display characteristics

| Property                | Value                          |
| ----------------------- | ------------------------------ |
| Panel (portrait native) | 1080 x 1920 px                 |
| App window (landscape)  | **1920 x 1080 px**             |
| Density                 | 369 dpi -> `density = 2.30625` |
| Physical dpi            | 318.98 x 320.84                |
| Window in dp            | **832.52 x 468.29 dp**         |
| smallestScreenWidthDp   | 468 -> bucket **`compact`**    |
| Aspect                  | 16:9 (1.7778)                  |

> The Thor also exposes a second internal panel (`Screen-2`, 1080 x 1240 @ 369 dpi).
> The XMB runs on display 0 only; the secondary screen is out of scope here.

### 1.3 Revised hand-tuned values (barLeftFraction = -0.05)

After further tuning, a gentler horizontal shift reads better on the Thor:

```json
{"compact":{"scale":1.3199997,"barLeftFraction":-0.05,"barTopFraction":0.13}}
```

| Field             | Old value | New value  | Slider bounds                      |
| ----------------- | --------- | ---------- | ---------------------------------- |
| `scale`           | 1.32      | **1.32**   | `SCALE_MIN 0.6` … `SCALE_MAX 1.8`  |
| `barLeftFraction` | -0.10     | **-0.05**  | `LEFT_MIN -0.25` … `LEFT_MAX 0.35` |
| `barTopFraction`  | 0.13      | **0.13**   | `TOP_MIN 0.05` … `TOP_MAX 0.45`    |

The vertical and scale values are unchanged; only the horizontal shift was relaxed.
Both the old and new values are reproduced exactly by the formula below — the only
constant that changed is the PSP cross-anchor x (`PSP_CROSS_ANCHOR_X_DP`), which the
formula turns into the per-device `barLeftFraction`.

---

## 2. How the three numbers actually reach pixels

From [XMBShell.kt](feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/XMBShell.kt)
(`XMB_BASELINE_HEIGHT_DP = 468f`, `XMB_BASELINE_WIDTH_DP = 832f`, `XMB_MAX_SCALE = 2.5f`),
the pipeline is:

```
uiScale = clamp( min( H_dp / 468 , W_dp / 832 ), 1.0, 2.5 )     // automatic, aspect-safe
D       = uiScale * adjust.scale                                 // total density multiplier
canvas  = ( W_dp / D , H_dp / D )                                // dp space the cross lays out in
```

Everything below is then measured **inside that canvas**:

```
barTop      = canvasH * barTopFraction          // XMBShell.kt:591
anchorTop   = barTop + CAT_BAR_HEIGHT (112dp)   // selected row line
hShift      = canvasW * barLeftFraction         // XMBShell.kt:604
startPad    = columnBaseInset + hShift
columnBaseInset = XmbLeftAnchor(130dp) + CategorySlotWidth/2 (62dp) - LEADING_ICON_CENTER (55dp)
                = 137 dp
```

Fixed dp constants that define the cross itself: `CAT_BAR_HEIGHT = 112`,
`ROW_HEIGHT = 88`, `CategorySlotWidth = 124`, `XmbLeftAnchor = 130`,
`contentTopPaddingDp = 20`, `LEADING_ICON_SLOT = 74`.

The tuned **`compact`** bucket was re-checked with `barLeftFraction = -0.05`
(a gentler left shift than the original -0.10). That changes the PSP reference
cross-anchor x from 73.97 dp to 105.49 dp (16.74 % of canvas width instead of 11.74 %).
The formula in §4 inherits the new anchor automatically.

**This is the crux**: the cross is built from *absolute dp*, but positioned by
*fractions*. So the look is only preserved if the **canvas dp size is preserved** —
copying the fractions alone is not enough.

---

## 3. Derived reference — "the PSP canvas"

Substituting the Thor's measurements:

```
uiScale = min(468.293/468, 832.520/832) = 1.000625      (clamp does not bind)
D       = 1.000625 * 1.3199997 = 1.320825
canvas  = 630.30 x 354.55 dp                            (aspect 1.7778)
```

**The tuned target is a 630.3 x 354.5 dp layout canvas.** That single number is what
you actually dialled in; `scale = 1.32` is just how you reach it from a 468 dp
baseline (`468 / 354.55 = 1.3200` exactly).

Resulting invariants to reproduce on every device:

| Invariant                                   | Thor value                                     |
| ------------------------------------------- | ---------------------------------------------- |
| Canvas height `H_REF`                       | **354.55 dp**                                  |
| Canvas width (16:9)                         | 630.30 dp                                      |
| `barTop`                                    | 46.09 dp                                       |
| Caticon band centre                         | **34.44 %** of canvas height                   |
| Selected-row top (`contentTop + anchorTop`) | **50.2 %** of height                           |
| Selected-row centre                         | 62.6 % of height                               |
| Cross icon-centre line `ANCHOR_X`           | **105.49 dp** from left edge (16.74 % of width) |
| `hShift`                                    | -31.52 dp                                      |

The 50.2 % selected-row line is the tell — that matches the real PSP XMB, and matches
the intent already documented at [XMBShell.kt:611](feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/XMBShell.kt:611).

---

## 4. The formula

Given a device's landscape window `W_px x H_px` and `densityDpi`:

```
d       = densityDpi / 160
W_dp    = W_px / d
H_dp    = H_px / d

uiScale = clamp( min( H_dp / 468 , W_dp / 832 ), 1.0, 2.5 )

# 1) SCALE - drive the effective canvas height to the tuned 354.55 dp
scale   = clamp( (H_dp / 354.546) / uiScale , 0.6, 1.8 )

# recompute the canvas the clamped scale actually produced
canvasH = H_dp / (uiScale * scale)
canvasW = W_dp / (uiScale * scale)

# 2) VERTICAL - hold the caticon band at 34.436 % of canvas height
barTopFraction  = clamp( 0.34436 - 76.0 / canvasH , 0.05, 0.45 )
                             #  76 = contentTopPadding 20 + CAT_BAR_HEIGHT/2 56

# 3) HORIZONTAL - hold the cross icon-centre line at 105.49 dp from the left edge
barLeftFraction = clamp( (105.49 - 137.0) / canvasW , -0.25, 0.35 )
                             # 137 = columnBaseInset
```

Constants to lift into code (suggest an `XmbLayoutPreset` object in `theme-kit`,
next to `XmbLayoutAdjust`):

```kotlin
const val PSP_CANVAS_HEIGHT_DP = 354.546f        // the tuned reference canvas
const val PSP_CATICON_CENTER_FRACTION = 0.34436f
const val PSP_CROSS_ANCHOR_X_DP = 105.49f
// derived from existing layout constants, not new magic numbers:
//   76f  = XmbLayoutSpec.DEFAULT.contentTopPaddingDp + CAT_BAR_HEIGHT / 2
//   137f = XmbLeftAnchor + CategorySlotWidth / 2 - LEADING_ICON_CENTER
```

### Why `barTopFraction` mostly stays 0.13

Whenever `scale` is **not** clamped, step 1 forces `canvasH = 354.55`, and step 2 then
returns `0.34436 - 76/354.55 = 0.1300` exactly. So on any device where the scale
clamp does not bind, the answer is literally the Thor's number. Step 2 only starts
moving when `SCALE_MAX` clips (see §5.1).

---

## 5. Verification across devices

Formula applied to representative screens (`*` = a clamp bound):

| Device                            | Window dp     | uiScale | scale       | barTop     | barLeft     | Canvas dp |
| --------------------------------- | ------------- | ------- | ----------- | ---------- | ----------- | --------- |
| **AYN Thor (reference)**          | 832.5 x 468.3 | 1.001   | **1.320**   | **0.1300** | **-0.0500** | 630 x 355 |
| Odin 2 — 1920x1080 @480dpi        | 640.0 x 360.0 | 1.000   | 1.015       | 0.1300     | -0.0500     | 630 x 355 |
| Retroid Pocket 5 — 1920x1080 @400 | 768.0 x 432.0 | 1.000   | 1.218       | 0.1300     | -0.0500     | 630 x 355 |
| 1080p phone @440dpi (2400x1080)   | 872.7 x 392.7 | 1.000   | 1.108       | 0.1300     | -0.0400     | 788 x 355 |
| 1080p phone @420dpi (2340x1080)   | 891.4 x 411.4 | 1.000   | 1.160       | 0.1300     | -0.0410     | 768 x 355 |
| 16:10 tablet 2560x1600 @320       | 1280 x 800    | 1.538   | 1.467       | 0.1300     | -0.0556     | 567 x 355 |
| 4K TV 3840x2160 @320              | 1920 x 1080   | 2.308   | 1.320       | 0.1300     | -0.0500     | 630 x 355 |
| ⚠️ Z Fold inner 2176x1812 @373    | 933.4 x 777.3 | 1.122   | **1.800\*** | 0.1469     | -0.0682     | 462 x 385 |

Every 16:9-ish screen lands on the identical 630 x 355 canvas and reproduces the Thor
look exactly (now with `barLeftFraction = -0.05`). Taller-aspect phones get a
**wider** canvas at the same height — the cross is left-anchored, so the surplus width
simply becomes label room, which is the correct behaviour. The horizontal shift stays
at -0.05 on any 16:9 panel; narrower canvases (tablets, foldables) drift slightly more
negative because the fixed 105.49 dp anchor occupies a larger fraction of a smaller width.

### 5.1 The one real failure: the `expanded` bucket

The Z Fold inner display is near-square, so `uiScale` is **width**-bound rather than
height-bound. The formula wants `scale = 1.954`, but `SCALE_MAX = 1.8` clips it, and
the canvas comes out 462 x 385 dp — too narrow and too tall. Step 2's fallback keeps
the caticon band on the right line (`0.1469`), so it degrades gracefully rather than
breaking, but it is **not** the same look.

Two options when this gets implemented:

1. **Raise `SCALE_MAX`** to ~2.2. Cheapest fix; the clamp exists only to guard absurd
   configs, and 1.8 was never chosen against this case.
2. **Letterbox to the canonical canvas** — change `uiScale` to fit a fixed
   630.3 x 354.5 dp box and centre it, leaving the surplus vertical space empty. This
   is the more honest model for the PSP look (the PSP is 16:9; a square panel simply
   has spare room) and would make the formula collapse to a constant `scale = 1.32`
   on *every* device. Bigger change — it touches [XMBShell.kt:396](feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/XMBShell.kt:396).

Option 2 if the goal is genuinely "the same look everywhere"; option 1 if this needs
to ship sooner.

---

## 6. Wiring it in

Suggested integration points:

- **Setup Wizard** — after the display step, offer an **optional** "Match PSP layout"
  checkbox. When ticked, compute the three values from the live `LocalConfiguration` +
  `LocalDensity` and write the bucket entry for the current `XmbFormFactor`. When left
  unchecked, no layout prefs are written and the XMB keeps its existing (default or
  previously-tuned) values. This must NOT be applied automatically — the user opts in.
- **Display ▸ Adjust XMB Layout** ([XmbLayoutAdjustOverlay.kt](feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/XmbLayoutAdjustOverlay.kt)) —
  add a third action next to the existing sliders: **"Auto-fit (PSP)"**, seeding the
  draft from the formula so you can still nudge from there. Pair it with the existing reset.
- **Per-bucket** — run the formula against the *current* window, so a foldable
  recomputes on unfold and each bucket gets its own correct seed. Only the bucket the
  user opted in for is written.
- Values must still pass through `XmbLayoutAdjustCodec.sanitize`, which re-applies all
  three clamps — the formula clamps defensively, but sanitize stays the gate.

### Suggested test

`XmbLayoutPresetTest` — feed the Thor's exact numbers (1920 x 1080 @ 369 dpi) and
assert the formula returns `scale ~= 1.32`, `barTopFraction ~= 0.13`,
`barLeftFraction ~= -0.05`, i.e. that it reproduces the revised hand-tuned state it
was derived from. That single test pins the whole derivation.

---

## Appendix — reproducing the capture

```bash
"C:\Users\johnn\AppData\Local\Android\Sdk\platform-tools\adb.exe" shell wm size
```

```bash
"C:\Users\johnn\AppData\Local\Android\Sdk\platform-tools\adb.exe" shell "run-as com.playfieldportal.launcher.debug cat files/datastore/pfp_prefs.preferences_pb | base64 -w0"
```

Base64-decode the second one; the JSON sits in the clear next to the
`display_xmb_layout_adjust` key.
