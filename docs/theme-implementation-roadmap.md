# Theme System — Implementation Roadmap (In-App + Companion App)

> **Status (2026-07-07):** Steps 1–5 SHIPPED on branch `pfp-themes-convertion`.
> Steps 1–4 (`f6078e3`..`cc7af6a`): theme-kit core (+ hostile-input hardening), one-color
> cascade, PortalIcon unified icon tint across all XMB glyphs, 12 presets, Themes settings
> UI, PTF import, Quick Create from photo, saved-theme library, `.pfptheme` share/import.
> Step 5: **Theme Studio v1** — `:studio` Compose Desktop module (Win/Linux/macOS; CMP
> 1.6.11 on Kotlin 2.0.0): open `.ptf`/`.pfptheme`, live XMB preview canvas (shared
> `XmbLayoutSpec` + `ColorCascade` from theme-kit), accent/icon-color/wave editing,
> wallpaper import, batch PTF→pfptheme, export with embedded rendered preview — PLUS the
> **custom icon-slot system** end-to-end: theme-kit `IconSlots` registry (42 slots:
> catbar/items/status; platforms + physical media excluded), `.pfptheme` schema v2
> `icons/<key>.png` entries, Studio icon editor + template-pack export, launcher renders
> overrides via `LocalXmbIconOverrides`/`ThemedGlyph` (applied themes extract to
> filesDir/theme-icons, stamp pref `theme_icons_stamp`).
> Remaining: in-app rendered-XMB preview gate, per-theme `XmbLayoutSpec` applied from
> manifests, Studio v2 (step 6: templates gallery, real-PSP `.ptf` export, alignment assist).

Consolidated build plan from the theme research and on-device experiments. Companion docs:
[official-ptf-template.md](official-ptf-template.md) (PTF format, verified),
[icon-system-plan.md](icon-system-plan.md) (unified icon color),
[ptf-import-plan.md](ptf-import-plan.md) (PTF → theme conversion),
[xmb-theme-creator-plan.md](xmb-theme-creator-plan.md) (creator UX, `.pfptheme` format).

Everything below is grounded in things already proven working: wallpaper extraction from
real PTFs (Evangelion, ClassyPink, Sony's own examples), accent derivation from the
wallpaper's dominant hue, live application through `KEY_CUSTOM_WALLPAPER`, the pixel-tuned
XMB alignment (commit `28e68f8`), and a hand-built `ClassyPink.pfptheme`.

---

## Architecture: one shared core, two frontends

```
                    ┌──────────────────────────────┐
                    │   :core:theme-kit  (KMP)      │
                    │  - PtfParser (5-slot, zlib,   │
                    │    BMP wallpaper, GIM decode) │
                    │  - AccentDeriver (dominant    │
                    │    hue → accent color)        │
                    │  - PfpThemeCodec (zip +       │
                    │    manifest.json read/write)  │
                    │  - ThemeModel + validation    │
                    │  - XmbLayoutSpec (crossbar %, │
                    │    anchors — the tuned values)│
                    └───────┬──────────────┬────────┘
                            │              │
              ┌─────────────┴───┐   ┌──────┴─────────────────┐
              │ PlayFieldPortal │   │ Theme Studio            │
              │ (Android app)   │   │ (Compose Desktop, KMP)  │
              │ apply / import  │   │ build / convert / batch │
              └─────────────────┘   └────────────────────────┘
```

**Recommendation: `theme-kit` as a Kotlin Multiplatform module** (jvm + android targets).
The PTF parser, accent derivation, and `.pfptheme` codec are pure logic — written once,
tested once, used by both apps. This is the single most important structural decision:
it guarantees a theme built in the companion renders identically in the launcher.

---

## Part 1 — In-app implementation (PlayFieldPortal)

### Phase A: Theme model + engine
1. **Extend the theme model** — `ThemeEntity`/`PFPTheme` gain:
   `accentColor: Long`, `iconColor: Long?` (null = derive from accent),
   `wallpaperPath: String?`, `waveStyle`, `schemaVersion`.
2. **One-color cascade** — accent → wave color → `lightBackgroundAnchors()` gradient →
   cursor → icon tint. All existing machinery; just wire accent as the single source.
3. **Per-theme layout anchors** — promote the tuned constants (`barTop`, item anchor,
   left anchor) into `XmbLayoutSpec` with the current values as the default. Imported
   themes may carry overrides (the Evangelion alignment becomes *that theme's* spec,
   not the global default). Resolves the open "global vs per-theme" question.
4. **`PortalIcon` façade** — single icon entry point: `tint(SrcIn)` for mono vector +
   raster silhouettes, `FilterQuality.None` for raster fallbacks, conditional light
   outline when `iconColor` is dark (the Sony visibility guardrail).

### Phase B: Theme management UI
5. **Theme picker** — grid of saved themes under Settings ▸ Themes, each showing its
   `preview.png`; apply instantly (PSP-picker UX).
6. **Quick Create** — background picker + accent swatch/wheel, live on the real XMB,
   accent pre-filled from wallpaper hue. Save via `ThemeDao`.
7. **Presets to 12** — extend `XmbColorScheme` to the PSP's twelve theme colors.

### Phase C: Import / export
8. **PTF import (SAF)** — pick `*.ptf` → `theme-kit` parses → wallpaper + derived accent
   + name → preview screen → confirm → saved theme. Reject `.ctf` with a clear message.
9. **`.pfptheme` import** — same flow minus parsing; show embedded `preview.png` before
   applying.
10. **Preview gate + export** — render the themed XMB off-screen
    (`GraphicsLayer.toImageBitmap()`) → user confirms → write `.pfptheme`
    (manifest + wallpaper + preview) via SAF share/save.

### In-app non-goals
- No theme *authoring* beyond Quick Create (wallpaper + colors). Heavy editing lives in
  the companion.
- No PSP-hardware `.ptf` export from the phone (companion feature, later).

---

## Part 2 — Companion app: **Theme Studio** (Compose Multiplatform Desktop)

Desktop is where users' old PSP theme files live and where wallpaper editing is
comfortable. Compose Desktop reuses `theme-kit` *and* the XMB preview composables.

### Core features (v1)
1. **Open** `.ptf` / `.pfptheme` (graceful reject of `.ctf` with an explanation).
2. **Live XMB preview canvas** — renders the actual XMB layout (same `XmbLayoutSpec`
   constants, same category bar / item column composables where practical) over the
   wallpaper, with the derived accent applied to wave + gradient + icons. What you see
   is what the launcher shows.
3. **Wallpaper tools** — import any image; crop/scale presets: PSP 480×272 (imports),
   handheld 16:9 (1280×720 / 1920×1080 for modern devices); soft-wallpaper warning when
   the image is busy/high-contrast behind text areas.
4. **Color editing** — accent picker (pre-filled from dominant hue), optional icon-color
   override, live contrast check (flags the Sony flat-color case and previews the
   outline guardrail).
5. **Alignment assist** — auto-detect a wallpaper's cross-band position (the same
   pixel-measurement used to tune Evangelion) and offer a per-theme `XmbLayoutSpec`
   override so PSP-style wallpapers line up automatically.
6. **Export `.pfptheme`** — manifest + wallpaper + rendered preview. Batch mode: point
   at a folder of `.ptf` files → a folder of `.pfptheme` files.

### Later (v2+)
- **Template gallery** — starter templates per the manifest spec (wallpaper-only, color
  pack, full theme) users fill in.
- **Real-PSP `.ptf` export** — the reverse pipeline (image → BMP → zlib → 5-slot table;
  icons → IDX8 GIM swizzle). All format knowledge is already documented; encoder work.
- **Icon-pack recolor preview** — palette-swap preview of our icon set per theme.

### Distribution
- Windows/macOS/Linux installers via Compose Desktop packaging (`jpackage`).
- Version the `.pfptheme` `schemaVersion`; Studio writes the version the app supports.

---

## Shared correctness (both parts)

- **Golden tests in `theme-kit`**: parse the known PTFs (Sony's classypink/cookies +
  Evangelion) and assert wallpaper hash, accent value, name — the corpus already exists.
- **Round-trip test**: theme → `.pfptheme` → import → identical `ThemeModel`.
- **Render parity**: one preview-render function used by app (preview gate) and Studio
  (canvas) so previews never drift from reality.

## Build order (cross-cutting)

| # | Deliverable | Depends on |
|---|---|---|
| 1 | `theme-kit` module: model, PtfParser, AccentDeriver, PfpThemeCodec + golden tests | — |
| 2 | App Phase A (model, cascade, layout spec, PortalIcon) | 1 |
| 3 | App Phase B (picker, Quick Create, 12 presets) | 2 |
| 4 | App Phase C (import/export + preview gate) | 1–3 |
| 5 | Theme Studio v1 (open/preview/edit/export, batch) | 1 (parallel with 3–4) |
| 6 | Studio v2 (templates, PSP export, recolor preview) | 5 |

Steps 1–2 unblock everything; Studio can start as soon as `theme-kit` lands and proceed
in parallel with the app UI phases.
