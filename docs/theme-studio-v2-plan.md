# Theme Studio v2 — fit imported themes to our XMB

## Context

Studio v1 + launcher custom-icon support shipped and committed (`db0eb13`, `6f7eba1`, `72c1b40`); the export→import→apply loop is verified on device. V2's goal (user-locked): **users import an old PSP theme (or any wallpaper) and adjust it to fit our XMB.** Explicitly OUT: real-PSP `.ptf` export and the template gallery.

Scope: (1) per-theme layout end-to-end (manifest `layout: XmbLayoutSpec?` exists but is ignored — Studio drops it on open/export, launcher's `uiState.layoutSpec` is always DEFAULT); (2) alignment assist (auto-detect the dark cross-band PSP wallpapers bake in → `barTopFraction`, plus a manual slider with live preview); (3) wallpaper tools (crop/scale presets, busyness warning, dark-icon-color warning — Studio-side hints only); (4) **HSV color pickers** for Custom Accent and Custom Icon Color (user request); (5) **preview states**: the Studio preview can switch Home / Context Menu / Fullscreen Menu so accent changes are visible on those surfaces too (user request); (6) **security hardening pass** over every external-file loading path in both the Studio and the launcher (user request).

## Phase 4b — HSV color pickers (Studio)

- Create `studio/.../ui/HsvColorPicker.kt`: no new deps — a Canvas-based picker: saturation/value square (gradient white→hue horizontally × black vertically, drawn with two overlaid Brush gradients) + vertical hue strip (6-stop rainbow gradient), draggable thumbs via `detectDragGestures`, emitting packed ARGB. Pure param/state composable (`argb: Int, onChange: (Int) -> Unit`), reusable.
- [InspectorPanel.kt](studio/src/main/kotlin/com/playfieldportal/studio/ui/InspectorPanel.kt): the existing `HexField`s for Custom accent and Custom icon color gain an expandable picker (swatch button toggles it); hex field and picker stay in sync (single source = the state's ARGB).

**Verified repo facts:** `PfpThemeCodecTest` already round-trips `manifest.layout`; [XmbPreviewCanvas.kt](studio/src/main/kotlin/com/playfieldportal/studio/preview/XmbPreviewCanvas.kt) uses `XmbLayoutSpec.DEFAULT` in exactly 5 places (top-level `XmbLeftAnchor`/`LeadingIconSlot` vals + `spec` locals in `XmbCross`/`CategoryCell`/`ItemRow`); `XMBViewModel.observeColorScheme` maps prefs into `SchemePrefs` with `distinctUntilChanged`; only XMBShell reads `uiState.layoutSpec`; `PfpThemeStore.importBundle` stores bundles verbatim so layout survives import once `apply()` persists it.

## Phase 1 — theme-kit: layout serialization + clamps

- Create `themekit/XmbLayoutSpecCodec.kt`: `encode(spec): String` (compact JSON, `ignoreUnknownKeys` on read), `decode(json?): XmbLayoutSpec?` (lenient, null on malformed, always sanitized), `sanitize(spec)` clamping every field — `barTopFraction` **0.05..0.45** (`BAR_TOP_MIN/MAX` consts), `contentTopPaddingDp` 0..120, icon dp 16..160, text sp 8..40, gap 0..60, `leftAnchorExtraDp` −60..120, `previousItemRiseRows` 0..2; NaN/Inf → field's DEFAULT. Clamped at parse AND apply time so a hostile manifest can't wedge the XMB offscreen.
- `XmbLayoutSpecCodecTest`: round-trip, garbage → null, unknown keys ignored, hostile values clamped.

## Phase 2 — theme-kit: analyzers (AccentDeriver style, pure Kotlin, BmpImage in)

- `themekit/CrossBandDetector.kt`: `detectBarTopFraction(image, maxColumnSamples=256): Float?` —
  per-row mean luminance (Rec.601, stride-sampled columns) → moving-average smooth (window `max(3, h/64)`) → search rows `0.03h..0.55h` → reject flat profiles (stddev < 0.02) → dark rows = `< mean − max(0.08, 0.35·stddev)` as consecutive runs → qualify runs: length `0.06h..0.35h`, depth ≥ 0.10, **top-edge contrast ≥ 0.10** (rejects dark-from-top skies) → winner by `depth·√length` → return run TOP / height coerced 0.05..0.45; null when nothing qualifies.
- `themekit/WallpaperMetrics.kt`: `busyness(image): Float` = mean |luma gradient| over the label band (x 0.10..0.60, y 0.30..0.85, ~6000 samples); `isBusy = busyness > 0.055`; `luminance(argb)`; `DARK_ICON_LUMINANCE = 0.35f`.
- Tests with synthetic BmpImages (via testFixtures `buildBmp`): uniform → null; stripe at 0.25h → ≈0.25; lower-half/2-row/low-contrast stripes → null; near-top stripe clamps to 0.05. Metrics: flat quiet, checkerboard busy, soft gradient quiet. Validate busyness threshold against real Sony PTF wallpapers before locking.

## Phase 3 — Studio: layout state + spec-driven preview

- [StudioViewModel.kt](studio/src/main/kotlin/com/playfieldportal/studio/StudioViewModel.kt): `StudioState` gains `layout: XmbLayoutSpec = DEFAULT` (carry the WHOLE spec so opened manifests round-trip fields the UI doesn't expose), `wallpaperBusy: Boolean`, `pendingWallpaper` (crop staging). Intents: `setBarTopFraction` (coerced), `resetLayout`, `detectBarTop()` (wallpaper → `toBmpImage` → detector; hit = fraction + status "Crossbar detected at NN%", miss = status line, not a dialog), `stageWallpaper/confirmWallpaper(preset)`. `hydrate()` reads `manifest.layout` (sanitized) + computes busyness; `buildManifest()` writes `layout.takeUnless { it == DEFAULT }`.
- [PreviewModel.kt](studio/src/main/kotlin/com/playfieldportal/studio/preview/PreviewModel.kt): `XmbPreviewModel.layout`; [XmbPreviewCanvas.kt](studio/src/main/kotlin/com/playfieldportal/studio/preview/XmbPreviewCanvas.kt): all 5 DEFAULT sites → `model.layout` (top-level vals become spec-derived locals). [PreviewRenderer.kt](studio/src/main/kotlin/com/playfieldportal/studio/preview/PreviewRenderer.kt) batch overload passes the bundle's sanitized layout.
- Extend `RoundTripTest`: layout with off-default fields survives export→open; default exports null; clamping.

## Phase 4 — Studio UI

- [InspectorPanel.kt](studio/src/main/kotlin/com/playfieldportal/studio/ui/InspectorPanel.kt): new **Layout** section — "Crossbar position NN%" + M3 `Slider` (0.05..0.45, live) + "Detect from wallpaper" (enabled when wallpaper present) + "Reset to default" + 11sp hint. Warnings: busy-wallpaper hint in Wallpaper section; dark-icon hint (luminance < 0.35) in Icon color section. (Slider isn't on the known-crash list, but verify at first run — fallback is a stepped ±control.)
- Create `ui/WallpaperImportDialog.kt`: choosing a wallpaper stages it; dialog shows thumbnail + presets `PSP 480×272 / HD 1280×720 / Full HD 1920×1080 / Keep original (W×H)` with resulting dimensions → Import runs crop→accent-derive→busyness; plus a "Re-crop…" in the Wallpaper section (re-stages embedded bytes). Wire in [StudioApp.kt](studio/src/main/kotlin/com/playfieldportal/studio/ui/StudioApp.kt).
- [ImageCodecs.kt](studio/src/main/kotlin/com/playfieldportal/studio/io/ImageCodecs.kt): `centerCropScale(src, targetW, targetH)` (center-crop to aspect, bilinear, TYPE_INT_ARGB) + tests.

## Phase 4c — Preview states: Home / Context Menu / Fullscreen Menu (Studio)

Verified launcher facts to replicate (parity by formula, sample content static):
- **Context menu** ([ContextMenuOverlay.kt](feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/ContextMenuOverlay.kt)): right-edge 300dp column over a `0x40000000` scrim (wave stays visible); panel backdrop `waveColor.copy(alpha=0.75f)`; title white w/ shadow + underline rule @30%α; rows 15–16sp, 12dp vertical padding; selected-row cursor = horizontal gradient transparent → `menuCursorEdge()` @40%α, where [MenuCursor.kt](core/core-ui/src/main/kotlin/com/playfieldportal/core/ui/theme/MenuCursor.kt): `edge = lerp(accentColor, White, 0.55f).copy(alpha=0.95f)`, `fill = lerp(accentColor, White, 0.20f).copy(alpha=0.34f)`.
- **Fullscreen menu** (representative surface = [MusicBrowserScreen.kt](feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/MusicBrowserScreen.kt)): fullscreen vertical gradient `backgroundTop@72%α → backgroundBottom@90%α`; header (back arrow, 24sp Light title, right pills); search field (white@22%/14% bg, `menuCursorEdge()` focused border); sample track rows (cover placeholder `0xFF1B1B27`, white primary / `0xFFC9C7E8` secondary text).
- Color mapping parity: replicate the launcher's PFPColors construction — `waveColor = accent`, `accentColor = white` (XMBViewModel `withWaveTint`/`toPFPColors`), so cursor/backdrop tint exactly as on device.

Implementation:
- `XmbPreviewModel` gains `mode: PreviewMode { HOME, CONTEXT_MENU, FULLSCREEN_MENU }` (+ `waveColor`, background anchors already present); `StudioState.previewMode` + `setPreviewMode`.
- [XmbPreviewCanvas.kt](studio/src/main/kotlin/com/playfieldportal/studio/preview/XmbPreviewCanvas.kt): CONTEXT_MENU draws the Home frame + scrim + right panel with ~4 sample items (one selected w/ cursor gradient, one destructive red); FULLSCREEN_MENU draws the music-browser frame with static sample rows. Plain composables, static.
- Mode switcher: three plain toggle buttons above the preview in [StudioApp.kt](studio/src/main/kotlin/com/playfieldportal/studio/ui/StudioApp.kt) (same stable-widget rule — no experimental M3).
- Export preview.png always renders HOME mode.

## Phase 5 — Launcher applies the layout (mirrors accent/icons pref pattern)

- Prefs contract: string `theme_layout_spec` = `XmbLayoutSpecCodec.encode` of the sanitized spec; present ⇒ override, absent ⇒ DEFAULT; reads lenient + sanitized.
- [PfpThemeStore.kt](core/core-data/src/main/kotlin/com/playfieldportal/core/data/repository/PfpThemeStore.kt): public `KEY_THEME_LAYOUT`; `apply()` sets it from `manifest.layout` (sanitized, skipped when == DEFAULT) else removes — inside the existing edit block.
- [XMBViewModel.kt](feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/viewmodel/XMBViewModel.kt): `SchemePrefs` gains `layoutJson`; collect folds `XmbLayoutSpecCodec.decode(layoutJson) ?: DEFAULT` into the existing `uiState` update; `confirmColorSchemePicker` also removes `KEY_THEME_LAYOUT`. XMBShell unchanged (already spec-driven).
- New `PfpThemeStoreLayoutTest` (robolectric): apply with layout → decodable pref; without → removed; hostile 0.9 → stored clamped 0.45.

## Phase 6 — Security hardening: external-file entry points

Audit result: the theme-kit parsers are already solid (zlib inflate capped 32 MB, BMP dimensions capped 8192 + stride/offset bounds, zip entries capped 32 MB/4 MB-icon, IconSlots key whitelist blocks path smuggling, `XmbThemeLoader` has per-entry/total/count caps + canonical-path traversal guard, `HostileInputTest` covers bombs). The gaps are all at **entry points before data reaches the parsers**:

1. **Studio bounded reads** — new `io/SafeIo.kt`: `readBytesCapped(file, cap): ByteArray?` (streamed, bail > cap). Caps: `.ptf`/`.pfptheme` 64 MB. Use in `StudioViewModel.openPtf/openPfpTheme` (error dialog on oversize) and `BatchConverter` (oversize → `failed("file too large")`; also cap batch at 500 files/run).
2. **Studio image decode pre-check** — [ImageCodecs.kt](studio/src/main/kotlin/com/playfieldportal/studio/io/ImageCodecs.kt): before any full `ImageIO.read`, sniff dimensions via `ImageIO.createImageInputStream` + `ImageReader.getWidth/getHeight(0)` (header-only); reject > 8192 px per side (matches Bmp.kt's cap) or unreadable headers. Applies to `loadImage`/`decodeImage`/`normalizeIconPng`.
3. **Launcher bounded SAF reads** — `PfpThemeStore.importBundle` (line ~156) and `PtfThemeImporter.import` (line ~45) read SAF URIs with unbounded `readBytes()`: replace with a capped stream read (64 MB, mirroring `XmbThemeLoader.readBounded`).
4. **Launcher bitmap decode guards** — `BitmapFactory` calls get `inJustDecodeBounds` pre-checks + caps: `PfpThemeStore.createFromImage` (SAF photo) and `importBundle`'s wallpaper/preview decodes → reject > 8192/side, use `inSampleSize` to land ≤ 1920; `XMBViewModel.loadThemeIconOverrides` → reject icons > 2048/side (trusted-extraction origin, but the bundle author is untrusted).
5. **Tests** — studio: `SafeIoTest` (cap honored, exact-size ok), dimension pre-check test with a crafted huge-header PNG; theme-kit untouched (already covered). Launcher helpers factored so the caps are unit-testable without robolectric where possible.

## Risks / notes

- Detector false positives on dark-top wallpapers — mitigated by the top-edge-contrast criterion; Detect is user-initiated, never auto-applied.
- Busyness threshold tuning — synthetic tests bound it; check against real Sony PTFs via batch corpus.
- Pre-existing quirk surfaced during design (out of scope, flagging): `confirmColorSchemePicker` clears accent/icons/(now layout) but NOT `KEY_ICON_COLOR` — the manually-set icon color survives preset switches. Tell the user; don't fix silently.

## Verification

- `./gradlew :core:theme-kit:test :studio:test :core:core-data:testDebugUnitTest :feature:feature-xmb:testDebugUnitTest`, then `:studio:run`.
- Studio manual: open real `.ptf` → slider 11% → Detect on a cross-band PTF → slider jumps, bar sits on the art (plain photo → "no band" status) → drag = live preview → Reset → export → reopen (slider + untouched manifest fields survive; untouched theme has no `layout` in the zip); crop dialog HD → 1280×720, re-crop PSP → 480×272; checkerboard → busy hint; icon color `#202020` → dark hint.
- Pickers/modes: drag the HSV picker for accent → preview retints live and hex field tracks; same for icon color; switch preview to Context Menu → panel backdrop/cursor follow the accent; Fullscreen Menu → gradient follows; export always embeds the HOME frame.
- Security: attempt to open a >64 MB junk `.pfptheme` → clean error, no hang/OOM; import a PNG with crafted huge dimensions in the header → rejected before decode; on device, import an oversized bundle → rejected (capped read).
- Device (ask before build/install): import + apply layout-bearing theme → crossbar shifts and persists across restart; preset confirm clears it; v1 theme → default geometry; hostile pref value renders clamped, never offscreen.
