# Play Field Portal — Theme Studio Restructure & Theme Format v4: Implementation Plan

Rebuild the desktop Theme Studio around a **section rail + faithful interactive preview + inspector**
layout, and move the `.pfptheme` format to **schema 4**: legibility, exact wave style, cropped
motion, theme sounds and boot media, author/description/updated, and **128 icon slots** (from 52),
with a lossless, repair-don't-reject compatibility story in both directions.

**Status: planned, nothing implemented.** Every decision in §3 is approved. Every file:line in this
document was read on branch `claude/theme-studio-refactor-mockup-15cb1f`.

**Mockup (approved):** claude.ai artifact "Theme Studio Restructure" —
https://claude.ai/artifact/2CvmhFrCoYYentyxEv67m8 (not checked into `docs/mockup/`).

> **Order.** Format first, launcher second, Studio UI last. A Studio that writes v4 before the
> launcher can read it ships themes nobody can apply; a launcher that reads v4 before the codec
> carries it has nothing to read. Phases 1–2 are pure data and unit-testable; Phase 3 is the
> only phase that needs the handheld; Phases 4–5 are desktop-only.

> **Test-first, every task.** The owner's rule: each task's failing tests are written before its
> production code. Tasks list their tests under **Tests first**. Two tasks are exempt by nature
> and say so: TS-09 (documentation only) and TS-26 (a spike whose deliverable is a finding).

---

## 1. Objective

1. A Studio an author can actually reason in: one rail section per concern, a preview that behaves
   like the handheld (navigable, animated, same geometry), and an inspector that only shows the
   active section.
2. A theme file that carries the whole look — including the legibility, sound, boot and motion
   choices that today live only in device prefs or are lost on export.
3. Zero data loss across versions: every schema (1, 2, 3, 4, future) opens in both the launcher and
   the Studio; the Studio round-trips what it does not understand; older launchers keep applying
   the subset they know.

---

## 2. What exists today

### 2.1 The format (core/theme-kit, pure JVM)

| Fact | Where |
|---|---|
| Manifest: `name`, `accentColor`, `iconColor`, `textColor`, `waveStyle`, `layout`, `source`, `created`; schema 3 | [PfpTheme.kt:16-49](../../core/theme-kit/src/main/kotlin/com/playfieldportal/themekit/PfpTheme.kt#L16) |
| Wave constants are three-valued (`animated` / `static` / `reduced`) — no Reduced+Static | [PfpTheme.kt:46-48](../../core/theme-kit/src/main/kotlin/com/playfieldportal/themekit/PfpTheme.kt#L46) |
| Bundle fields: `wallpaper`, `preview`, `icons`, `sysicons`, `motion` (streamed `ThemeMotion`, never on heap) | [PfpTheme.kt:84-174](../../core/theme-kit/src/main/kotlin/com/playfieldportal/themekit/PfpTheme.kt#L84) |
| Zip caps 256 entries / 64 MB per entry / 256 MB total; icon entry cap 8 MB | [PfpThemeCodec.kt:51-59](../../core/theme-kit/src/main/kotlin/com/playfieldportal/themekit/PfpThemeCodec.kt#L51) |
| `ignoreUnknownKeys = true`; readers never gate on `schemaVersion` | [PfpThemeCodec.kt:61-66](../../core/theme-kit/src/main/kotlin/com/playfieldportal/themekit/PfpThemeCodec.kt#L61) |
| **Writer silently drops** unregistered icon keys; **reader silently drops** unregistered keys and unknown entries | [PfpThemeCodec.kt:78-87](../../core/theme-kit/src/main/kotlin/com/playfieldportal/themekit/PfpThemeCodec.kt#L78), [:138-171](../../core/theme-kit/src/main/kotlin/com/playfieldportal/themekit/PfpThemeCodec.kt#L138) |
| Manifest decoded through the typed serializer — **unknown JSON keys are lost** on any re-write | [PfpThemeCodec.kt:130-135](../../core/theme-kit/src/main/kotlin/com/playfieldportal/themekit/PfpThemeCodec.kt#L130) |
| 52 slots: 10 catbar, 36 items (incl. 3 `item_shiba_*`), 6 status; `Group { CATEGORY_BAR, ITEMS, STATUS, CONSOLE }` | [IconSlots.kt:29-111](../../core/theme-kit/src/main/kotlin/com/playfieldportal/themekit/IconSlots.kt#L29) |
| 40 console ids; excludes `default`, `favorites`, `settings`, `desktop` | [CustomizableIcons.kt:14-55](../../core/theme-kit/src/main/kotlin/com/playfieldportal/themekit/CustomizableIcons.kt#L14) |
| UI-media caps; KDoc already anticipates "a future `.pfptheme` sound pack" | [UiMediaLimits.kt:25-28](../../core/theme-kit/src/main/kotlin/com/playfieldportal/themekit/UiMediaLimits.kt#L25), [:113-134](../../core/theme-kit/src/main/kotlin/com/playfieldportal/themekit/UiMediaLimits.kt#L113) |
| Motion caps 1080p / 60 s / 60 MB; "`PfpThemeStore.apply()` installs a bundle's motion entry WITHOUT re-running validate" | [MotionLimits.kt:25-30](../../core/theme-kit/src/main/kotlin/com/playfieldportal/themekit/MotionLimits.kt#L25), [:84-86](../../core/theme-kit/src/main/kotlin/com/playfieldportal/themekit/MotionLimits.kt#L84) |
| Theme geometry (11 fields) and the device-only adjust (scale 0.6–1.8, H −25..35 %, V 5..45 %) | [XmbLayoutSpec.kt](../../core/theme-kit/src/main/kotlin/com/playfieldportal/themekit/XmbLayoutSpec.kt), [XmbLayoutAdjust.kt:30-36](../../core/theme-kit/src/main/kotlin/com/playfieldportal/themekit/XmbLayoutAdjust.kt#L30) |

History: v1 shape is at commit `f6078e30`, v2 at `db0eb13b` (`git show <sha>:core/theme-kit/src/main/kotlin/com/playfieldportal/themekit/PfpTheme.kt`).

### 2.2 Apply / export on the launcher (core/core-data `PfpThemeStore`)

- `apply()` [PfpThemeStore.kt:100-203](../../core/core-data/src/main/kotlin/com/playfieldportal/core/data/repository/PfpThemeStore.kt#L100):
  wipe-then-extract icons into `theme-icons/` (:120-132), stream motion into `wallpaper/` (:137-147),
  then one `edit{}` with the **set-or-remove** contract for every cascade pref (:180-201).
- **Bug (a):** wave mapping knows three values (:170-174) — a `REDUCED_STATIC` device pref exports
  as `animated` through `saveCurrentLook()` (:473-477), and apply can never produce it.
- `resetApplied()` (:211-227) clears the cascade keys, `theme-icons/`, and every file in `wallpaper/`.
- `saveCurrentLook()` (:425-527) is the exact inverse of apply; it already flattens user icon
  picks over theme icons per slot (:433-447) and deliberately excludes `XmbLayoutAdjust` (:420-423).
- **Stale comment:** :631-632 says bundles "carry no motion wallpaper" — false since v3 (:137-147, :189).
- **Stale doc ref:** :39 cites `docs/xmb-theme-creator-plan.md`, which was deleted in `0aee2791`
  (also cited by PfpTheme.kt:9, ThemesSettingsViewModel.kt:34, XMBViewModel.kt:10221).
- **Import cap:** the whole picked file is capped at `SafeMedia.MAX_THEME_FILE_BYTES = 64 MB`
  ([SafeMedia.kt:17](../../core/core-data/src/main/kotlin/com/playfieldportal/core/data/repository/SafeMedia.kt#L17),
  applied at PfpThemeStore.kt:316). This governs the v4 size budget — §5.4.

### 2.3 Device-only settings the theme will start carrying

| Setting | Pref key | Declared |
|---|---|---|
| Text legibility (`TextLegibilityStyle`: NONE/SHADOW/OUTLINE/PLATE/AUTO) | `display_text_legibility` | [DisplaySettingsViewModel.kt:84](../../feature/feature-settings/src/main/kotlin/com/playfieldportal/feature/settings/viewmodel/DisplaySettingsViewModel.kt#L84), enum [TextLegibilityStyle.kt:24](../../core/core-domain/src/main/kotlin/com/playfieldportal/core/domain/model/TextLegibilityStyle.kt#L24) |
| Icon legibility (`IconLegibilityStyle`: NONE/OFFSET_SHADOW/CONTOUR_DARK/LIGHT/AUTO) | `display_icon_legibility` | [:71](../../feature/feature-settings/src/main/kotlin/com/playfieldportal/feature/settings/viewmodel/DisplaySettingsViewModel.kt#L71), enum [IconLegibilityStyle.kt:20](../../core/core-domain/src/main/kotlin/com/playfieldportal/core/domain/model/IconLegibilityStyle.kt#L20) |
| Solid unfocused icons | `display_solid_unfocused_icons` | [:73](../../feature/feature-settings/src/main/kotlin/com/playfieldportal/feature/settings/viewmodel/DisplaySettingsViewModel.kt#L73) |
| Use my exact colour | `display_text_color_exact` | [:82](../../feature/feature-settings/src/main/kotlin/com/playfieldportal/feature/settings/viewmodel/DisplaySettingsViewModel.kt#L82) |
| Wave style (4 values incl. `REDUCED_STATIC`) | `display_wave_style` | [WaveStyle.kt:12-32](../../core/core-ui/src/main/kotlin/com/playfieldportal/core/ui/wave/WaveStyle.kt#L12) |

`display_text_shadow` (:75) stays device-only: `TextLegibilityStyle.AUTO` reads it as "may I use a
shadow?" (TextLegibilityStyle.kt:21), so it is a user permission, not a look.

### 2.4 UI media

- Slots: `sound_scroll`, `sound_back`, `sound_confirm`, `sound_error`, `sound_notification`,
  `boot_video`, `gameboot_video`, `ambience_audio` —
  [UiMediaSlot.kt:51-73](../../core/core-domain/src/main/kotlin/com/playfieldportal/core/domain/model/UiMediaSlot.kt#L51).
- Storage `filesDir/ui-media/<key>.<ext>`; the file's presence is the assignment; `pathFor()` is
  the playback seam exposed to core-ui as `UiMediaPaths`
  ([UiMediaStore.kt:65-71](../../core/core-data/src/main/kotlin/com/playfieldportal/core/data/repository/UiMediaStore.kt#L65),
  [UiMediaPaths.kt:14-28](../../core/core-ui/src/main/kotlin/com/playfieldportal/core/ui/media/UiMediaPaths.kt#L14));
  `ui_media_stamp` invalidates players (:306).
- Import gate probes with `MediaMetadataRetriever`, falling back to
  [MediaDurationFallback](../../core/core-data/src/main/kotlin/com/playfieldportal/core/data/repository/MediaDurationFallback.kt#L37)
  — already pure JVM (MP3 Xing/CBR + PCM WAV only), but it lives in core-data where the Studio
  cannot reach it.
- Volumes (`AudioChannel` / `AudioLevelStore`), Show Boot Sequence and GameBoot on/off are device
  settings and stay that way.

### 2.5 Icon render sites

- Precedence everywhere: `LocalCustomIcons[key] ?: LocalXmbIconOverrides[key] ?: built-in`
  ([XmbIconOverrides.kt:45](../../core/core-ui/src/main/kotlin/com/playfieldportal/core/ui/icons/XmbIconOverrides.kt#L45),
  [ConsoleIcon.kt:21-22](../../core/core-ui/src/main/kotlin/com/playfieldportal/core/ui/icons/ConsoleIcon.kt#L21),
  [XmbStatusStrip.kt:442-445](../../feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/XmbStatusStrip.kt#L442)).
  Both locals are provided by [XMBShell.kt:516-520](../../feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/XMBShell.kt#L516).
- `ConsoleIcon` already resolves `sysicon_<anything>` including `sysicon_default`; only registry
  gating stops a theme supplying cps1/xbox/default art.
- `systemIconRes`: xbox borrows x360 ([SystemIcons.kt:62-63](../../core/core-ui/src/main/kotlin/com/playfieldportal/core/ui/icons/SystemIcons.kt#L62)), cps1–3 fall through to default (:64).
  `SystemIconsTest` requires **every** `SYSICON_PLATFORM_IDS` entry to have dedicated art.
- **Bug (b), Shiba hub rows:** `ach_all` draws the bundled memory card directly
  ([XMBItemList.kt:1286-1294](../../feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/XMBItemList.kt#L1286)),
  and `ach_connect` / `ach_untracked` call `ThemedGlyph("" , …)` with an **empty slot key**
  (:1296-1300, glyph table :1334-1338). The three registered `item_shiba_*` slots are never read.
- Status strip: bell `Icons.Filled.Notifications` (:346), controller `SportsEsports` (:260-266),
  Wi-Fi / Signal are **level-aware Canvas meters** (:274-279, :384-430); only Bluetooth and battery
  have slots (`forSlotKey` :95-101).
- Notifications: one kind→glyph table, 8 kinds ([NotificationIcons.kt:29-39](../../feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/NotificationIcons.kt#L29)),
  rendered at [NotificationPanel.kt:221, :323](../../feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/NotificationPanel.kt#L221).
- Media transports: [MusicPlayerScreen.kt:421-443](../../feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/MusicPlayerScreen.kt#L421),
  [VideoPlayerScreen.kt:440-457](../../feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/video/VideoPlayerScreen.kt#L440).
- Game Detail actions (PlayArrow, Favorite/FavoriteBorder, Brush, MenuBook, MoreHoriz):
  [GameDetailScreen.kt:406-449](../../feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/detail/GameDetailScreen.kt#L406).
- Menus: check mark is the drawn `PfpCheckMark` ([PfpCheck.kt:39](../../core/core-ui/src/main/kotlin/com/playfieldportal/core/ui/components/PfpCheck.kt#L39)) used at
  [PspContextMenu.kt:212](../../core/core-ui/src/main/kotlin/com/playfieldportal/core/ui/components/PspContextMenu.kt#L212);
  back arrow is a `"◀"` **text glyph** at [XmbTouchButton.kt:73-82](../../core/core-ui/src/main/kotlin/com/playfieldportal/core/ui/components/XmbTouchButton.kt#L73)
  and [DetailScaffold.kt:223](../../core/core-ui/src/main/kotlin/com/playfieldportal/core/ui/detail/DetailScaffold.kt#L223) (plus SettingsScaffold.kt:860, DetailComponents, ArtworkStudio).
- Coin medallions: [ShibaCoinArt.kt:23-39](../../feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/detail/ShibaCoinArt.kt#L23).
- Guard tests that force lockstep when slots are added: `DefaultSlotGlyphTest` (launcher, every
  `CustomizableIcons.ALL` key needs a default — [DefaultSlotGlyph.kt:80-99](../../feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/DefaultSlotGlyph.kt#L80)),
  `StudioIconSetTest` (Studio, every non-console key needs art), and the exhaustive
  `groupLabel` `when` in [CustomIconsOverlay.kt:285-289](../../feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/CustomIconsOverlay.kt#L285).
  The on-device editor lists only four groups ([XMBViewModel.kt:9521-9527](../../feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/viewmodel/XMBViewModel.kt#L9521)).

### 2.6 Motion playback

- [MotionWallpaperPolicy.kt:48-58](../../core/core-ui/src/main/kotlin/com/playfieldportal/core/ui/motion/MotionWallpaperPolicy.kt#L48) — POSTER / PLAY / PLAY_REDUCED.
- [MotionWallpaperBackground.kt:62-87](../../core/core-ui/src/main/kotlin/com/playfieldportal/core/ui/motion/MotionWallpaperBackground.kt#L62):
  poster `ContentScale.Crop` underneath, video on a `TextureView` whose transform is
  `applyCenterCrop` (:202-215). **This matrix is where a crop rect plugs in.**
- Wave: reduced = alpha .5 / amp .65 / speed .5, wallpaper scrim `0x59000000`
  ([XmbBackground.kt:154, :164-171](../../feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/XmbBackground.kt#L154)).

### 2.7 The Studio (studio/, CMP desktop 1.12.0 — gradle/libs.versions.toml:44)

- One `StudioViewModel` + `StudioState` ([StudioViewModel.kt:74-116](../../studio/src/main/kotlin/com/playfieldportal/studio/StudioViewModel.kt#L74)); no undo.
- **Bug (c):** `hydrate()` (:258-326) builds a fresh `StudioState` with no sysicons field — an
  opened theme's console art is dropped on re-export.
- **Bug (d):** `buildManifest()` (:561-578) writes `created = today` (:577) on every export.
- Icons: only `IconSlots` keys are editable (`setIconOverride` :491); templates export `IconSlots.ALL` (:670-679).
- Video: JCodec, **MP4/H.264 only**, poster frame + header probe; video bytes ship untouched
  ([VideoCodecs.kt:1-132](../../studio/src/main/kotlin/com/playfieldportal/studio/io/VideoCodecs.kt), rationale in its KDoc).
- Preview: static frames, `PreviewMode` HOME/CONTEXT_MENU/FULLSCREEN_MENU, **960 dp design width**
  ([XmbPreviewCanvas.kt:60](../../studio/src/main/kotlin/com/playfieldportal/studio/preview/XmbPreviewCanvas.kt#L60),
  [PreviewRenderer.kt:30](../../studio/src/main/kotlin/com/playfieldportal/studio/preview/PreviewRenderer.kt#L30))
  vs the launcher's 832×468 baseline ([XMBShell.kt:103-107](../../feature/feature-xmb/src/main/kotlin/com/playfieldportal/feature/xmb/ui/XMBShell.kt#L103));
  Reduced wave only (`reducedWave = waveStyle == WAVE_REDUCED`, PreviewModel.kt:54).
- "No frame animation" workarounds citing **CMP 1.6.11**: StudioApp.kt:89-90, :124-125;
  InspectorPanel.kt:144; HsvColorPicker.kt:26. The toolchain is now 1.12.0.

---

## 3. Decisions (approved — do not relitigate)

| # | Decision |
|---|---|
| D1 | **Layout:** left rail (Info, Color, Background, Legibility, Layout, Icons, Sounds, Boot & GameBoot, Export check); center live preview + "In this file" contents/budget strip; right inspector. Toolbar: New, Open, Upgrade folder…, **Import PSP theme** menu (Convert .ptf, Batch convert, Unpack assets), Undo/Redo, Export .pfptheme. Studio chrome is **neutral** — never takes the theme accent. |
| D2 | **Preview is interactive and faithful**, never click-to-edit. ←→ category, ↑↓ item, Enter drill/open, Esc back, Tab/right-click options flyout, X Games filter. Geometry and motion mirror the launcher (§7.5). Exported `preview.png` stays the static Home frame. First Studio-UI task is a **CMP 1.12 animation-stability spike**. |
| D3 | **Background** merges wallpaper + motion + wave: source Wave / Image / Video; Fit PSP 480×272 / HD / Full HD / Original; interactive crop frame locked to the fit ratio. Images are **baked** on export. **Video crop applies at playback:** bytes ship untouched, manifest carries normalized `motionCrop`, launcher transforms the surface, poster baked with the same crop. Older launchers ignore the rect (center-crop). Motion control: Animated / Reduced / Static / Reduced+Static. |
| D4 | **Legibility moves into the theme** (text, icon, solid unfocused). Color adds "Use my exact color" (`textColorExact`, default OFF). Info adds author, description, `updated`; `created` is preserved. |
| D5 | **Layout:** "Preview with my layout" replicates Adjust XMB Layout — **preview only, never saved in the theme**, remembered per Studio install. ⓘ tooltips name where device settings live. Theme geometry (`XmbLayoutSpec`, Detect + 11 advanced fields) stays in the theme. |
| D6 | **Sounds in theme:** five menu sounds + ambience + boot + gameboot. Caps from `UiMediaLimits`. Volumes, Show Boot, GameBoot on/off stay device-only. Precedence mirrors icons: **user pick > theme > built-in**; apply/reset/saveCurrentLook cover it. |
| D7 | **Icons 52 → 128**, all six new groups this pass (§5.3). Each new slot gets launcher wiring through the existing override lookup and a Studio default. Out: ~90 controller prompts, 102 physical-media cards. Picker: search, group chips with counts, filters On screen / Customized / New, drag-drop, folder/zip pack import with unmatched report, slot card. |
| D8 | Font: out of scope. |
| D9 | **Compatibility:** never gate on `schemaVersion`; default missing, ignore unknown; new enum values = fallback in the OLD field + exact in a NEW field; Studio round-trips losslessly; repair-don't-reject with an upgrade report; schema → 4. Studio: upgrade banner, report in Export check, Upgrade folder… (originals kept as `.bak`). Launcher: "Older format" tag + "Update theme file"; apply never requires upgrade. Golden fixtures per schema + future; a v4 bundle must still apply its subset through the v3-era reading path. |
| D10 | Fix in-plan: (a) Reduced+Static lost; (b) Shiba slots unwired; (c) hydrate drops sysicons; (d) buildManifest overwrites `created`; (e) stale docs — write `docs/theme-format.md`, fix CONTEXT.md and the PfpThemeStore.kt:631 comment. |

### 3.1 Decisions this plan adds (architect's calls — flagged in the hand-off report)

| # | Decision | Why | Rules out |
|---|---|---|---|
| A1 | Theme legibility / exact-colour fields are **nullable**; apply **writes the device pref when the field is present and leaves it untouched when absent**. Reset does not clear them. | These are Display settings the user owns (XMBViewModel.kt:10225-10226 says so for the font colour); a v1–v3 theme says nothing about legibility and must not reset a user's choice. | Remove-when-absent (the `textColor` contract) for these four keys. |
| A2 | Theme media live in a **new `filesDir/theme-media/`** tier; `UiMediaStore.pathFor()` returns user file, else theme file, else null (built-in). `assignments()` stays **user-only**. | Every player already resolves through `pathFor`; one seam change gives every consumer the theme tier, and settings screens keep showing only what the user picked. Mirrors `custom-icons/` vs `theme-icons/`. | A second `UiMediaPaths` interface; writing theme files into `ui-media/` (would make theme sounds look like user picks and survive theme switches). |
| A3 | New icon groups are **new `IconSlot.Group` values**; the three existing `item_shiba_*` keys **stay in `ITEMS`**. The Studio's "Shiba Coins (7)" chip is a Studio presentation group = `SHIBA` ∪ `item_shiba_*`. | Moving a slot's group changes the on-device editor's Items list. Keys are the contract; groups are presentation. | Re-grouping existing slots. |
| A4 | Console extras (`cps1`, `cps2`, `cps3`, `xbox`, `favorites`, `desktop`, `default`) go in a **separate `SYSICON_EXTRA_IDS`** list appended to `CustomizableIcons`' console group; `SYSICON_PLATFORM_IDS` is untouched. No new bundled drawables. | `SystemIconsTest` requires every `SYSICON_PLATFORM_IDS` id to have dedicated art; cps1–3 have none. `ConsoleIcon` already looks these keys up. | Adding art assets (needs art approval); weakening `SystemIconsTest`. |
| A5 | The launcher's on-device "Customize XMB Icons" editor **keeps its four groups** this pass. | Scope: the brief targets the Studio picker. The new keys are still valid for `CustomIconStore`. | Exposing 76 new slots on the handheld editor now (follow-up). |
| A6 | Status Wi-Fi / Signal overrides are **static images that replace the level meter**. Game Detail "favorite" is one slot for both states; the un-favorited state draws the override at reduced alpha. | One slot = one image (the slot model has no states). | Per-level or per-state slot families. |
| A7 | Theme media zip names: `sounds/<uiMediaKey>.<ext>` for the five menu sounds, top-level `ambience.<ext>`, `boot.<ext>`, `gameboot.<ext>`. Keys are the `UiMediaSlot` keys, mirrored in theme-kit `ThemeMediaSlots` with a drift test in core-domain. | theme-kit cannot see core-domain (dependency runs the other way). | Inventing new key names. |
| A8 | **Launcher import cap raised** from 64 MB to `BUNDLE_LIMITS.maxTotalBytes` (256 MB); the Studio's Export check **warns above 64 MB** that pre-v4 launchers will refuse the file. | Staging already streams to disk; a motion + boot + gameboot theme alone can reach 110 MB. | Holding v4 to 64 MB (forbids the feature's own headline case). |
| A9 | Theme-side byte caps for audio: **8 MB per menu sound, 32 MB ambience** (new constants in `UiMediaLimits`); video entries use the existing 25 MB / 60 MB caps. | `AUDIO_STAGE_MAX_BYTES` (128 MB) exceeds the 64 MB per-entry zip cap and is an anti-DoS staging number, not a theme budget. | Reusing the staging ceiling. |
| A10 | Studio boot/GameBoot import is **MP4 only**, like motion. Studio sound preview plays **WAV only**; other formats show "plays on device". | JCodec cannot demux WebM; the JVM has no MP3/OGG/AAC decoder and new dependencies need approval. | Adding an audio codec dependency without approval. |
| A11 | Unknown manifest keys round-trip as a raw `JsonObject` merged under the typed fields on write; unknown zip entries round-trip as **streamed passthrough entries** (never heap-held), each name validated by a strict safe-name rule. | Lossless (D9) without re-introducing the heap problem `ThemeMotion` solved, and without letting a crafted name escape its directory. | Holding unknown entries as `ByteArray`; relaxing `IconSlots.isValidKey` gating. |
| A12 | Launcher "Update theme file" regenerates `preview.jpg` from the wallpaper (the existing import path) when the bundle has no `preview.png`; it never renders an XMB frame. | The launcher has no offscreen XMB renderer; the Studio does. | Building one. |
| A13 | `docs/theme-format.md` is a **new** document; the deleted `docs/xmb-theme-creator-plan.md` is not resurrected. Code comments are re-pointed. | House rule: deleted plans stay deleted. | Recreating the old path. |

---

## 4. Rejected alternatives

| Alternative | Why rejected |
|---|---|
| Gate behaviour on `schemaVersion` | Breaks the "older readers apply the subset" rule the format has kept since v2 (PfpThemeCodec.kt:30-34). |
| Re-encode video to bake the crop | VideoCodecs.kt:16-24 already records why: MP4 through ExoPlayer is ~10× cheaper than any CPU path, and a pure-Java re-encoder is out of reach. |
| Save the user's Adjust XMB Layout into the theme | Explicitly device-specific (PfpThemeStore.kt:420-423, per-bucket storage). |
| Replace waveStyle's value set with four values | Older launchers map unknown values to `animated` (PfpThemeStore.kt:170-174) — a Reduced+Static theme would animate at full strength on every pre-v4 device. The fallback-field rule gives them `static`. |
| Theme sounds written into `ui-media/` | Indistinguishable from user picks; survives theme switches; breaks "user pick wins". |
| Separate "v4 codec" | Two readers drift; the existing one is additive by design. |

---

## 5. Format v4 specification

### 5.1 `manifest.json`

```jsonc
{
  "manifest": "pfptheme",
  "schemaVersion": 4,
  "name": "Night Drive",
  "author": "Jane",                      // v4, optional
  "description": "Neon over rain",       // v4, optional, clamped to 500 chars on read
  "accentColor": "#3A6FD8",
  "iconColor": "auto",                   // "#RRGGBB" | "auto"
  "textColor": "auto",                   // "#RRGGBB" | "auto"
  "textColorExact": false,               // v4, optional — absent = theme says nothing (A1)
  "waveStyle": "static",                 // LEGACY, always written: animated | reduced | static
  "waveStyleV4": "reduced_static",       // v4 exact: animated | reduced | static | reduced_static
  "legibility": {                        // v4, optional object; each member optional
    "text": "auto",                      // auto | none | shadow | outline | plate
    "icon": "contour_auto",              // none | offset_shadow | contour_dark | contour_light | contour_auto
    "solidUnfocusedIcons": false
  },
  "motionCrop": { "x": 0.1, "y": 0.0, "w": 0.8, "h": 1.0 },  // v4, optional, normalized source frame
  "layout": { /* XmbLayoutSpec, 11 fields, unchanged */ },
  "source": { "type": "user-created" },
  "created": "2026-07-06",               // preserved forever once set
  "updated": "2026-10-02",               // v4, set on every export / upgrade
  "someFutureField": { }                 // unknown keys: preserved by Studio + in-place upgrade
}
```

**Read rules (both apps):**
1. Never gate on `schemaVersion`. Missing → defaults. Unknown keys → ignored by apply, preserved by
   the Studio and by in-place upgrade (A11).
2. Exact wave = `waveStyleV4` if recognized, else `waveStyle` if recognized, else `animated`.
3. Unknown enum value inside `legibility` → that member is treated as absent.
4. `motionCrop` sanitized: non-finite → absent; clamp to [0,1]; `w`,`h` ≥ 0.05; `x+w ≤ 1`, `y+h ≤ 1`
   (shrink, don't shift). Applies only to MP4/WebM motion; ignored for GIF motion.
5. `layout` keeps `XmbLayoutSpecCodec.sanitize` on every read path (unchanged).

**Write rules:**
1. `schemaVersion = 4`. `waveStyle` = legacy fallback of the exact value (`reduced_static` → `static`).
2. `created` = existing value if present, else today. `updated` = today.
3. Unknown keys from the source manifest are merged back; typed fields always win on collision.
4. Future enum additions follow the same pattern: fallback in the old field, exact in a new one.

### 5.2 Zip entries

```
mytheme.pfptheme
├── manifest.json                           (required; written first — readManifest relies on it)
├── wallpaper.png                           (optional; baked crop)
├── preview.png                             (optional on read; Studio always writes the Home frame)
├── icons/<key>.<png|gif>                   (88 keys in v4; v3 had 52)
├── sysicons/<id>.<png|gif>                 (47 ids in v4; v3 had 40)
├── motion.<mp4|webm|gif>                   (streamed)
├── sounds/<sound_scroll|sound_back|sound_confirm|sound_error|sound_notification>.<mp3|wav|ogg|m4a>   (v4, streamed)
├── ambience.<mp3|wav|ogg|m4a>              (v4, streamed)
├── boot.<mp4|webm>                         (v4, streamed)
├── gameboot.<mp4|webm>                     (v4, streamed)
└── <anything else>                         (preserved verbatim by Studio/upgrade; ignored by apply)
```

### 5.3 Icon slots: 52 → 128

| Group (registry) | New | Total | Keys (new ones) | Bundle dir |
|---|---|---|---|---|
| CATEGORY_BAR | 0 | 10 | — | icons/ |
| ITEMS | 0 | 36 | (incl. existing `item_shiba_connect/track/untracked`) | icons/ |
| STATUS | +4 | 10 | `status_notifications`, `status_controller`, `status_wifi`, `status_signal` | icons/ |
| SHIBA (new) | +4 | 4 (+3 ITEMS shown in the Studio chip = 7) | `shiba_coin_bronze`, `shiba_coin_silver`, `shiba_coin_gold`, `shiba_coin_platinum` | icons/ |
| MEDIA (new) | +6 | 6 | `media_play`, `media_pause`, `media_prev`, `media_next`, `media_back10`, `media_fwd10` | icons/ |
| GAME_DETAIL (new) | +5 | 5 | `detail_play`, `detail_favorite`, `detail_artwork`, `detail_manual`, `detail_more` | icons/ |
| NOTIFICATIONS (new) | +8 | 8 | `notif_album`, `notif_image`, `notif_tag`, `notif_coin`, `notif_blocked`, `notif_settings`, `notif_download`, `notif_feed` | icons/ |
| MENUS (new) | +2 | 2 | `menu_check`, `menu_back` | icons/ |
| CONSOLE | +7 | 47 | `sysicon_cps1`, `_cps2`, `_cps3`, `_xbox`, `_favorites`, `_desktop`, `_default` | sysicons/ |
| **Total** | **+76** | **128** | | |

Key names are proposals fixed by TS-06/TS-05; once shipped they are forever-stable (IconSlots.kt:12-13).

### 5.4 Limits and budget

| Entry | Cap | Source |
|---|---|---|
| Entries per bundle | 256 | `BUNDLE_LIMITS` (128 icons + ≤12 others fits) |
| Any entry | 64 MB | `BUNDLE_LIMITS.maxEntryBytes` |
| Whole bundle | 256 MB | `BUNDLE_LIMITS.maxTotalBytes`; launcher import cap raised to match (A8) |
| Icon | 8 MB; GIF caps per `IconGifSupport` | `PfpThemeCodec.MAX_ICON_BYTES` |
| Motion | 1080p / 60 s / 60 MB | `MotionLimits` |
| Boot / GameBoot | 10 s / 25 MB, MP4/WebM | `UiMediaLimits.BOOT_CLIP` / `GAMEBOOT_CLIP` |
| Menu sound | per-slot duration cap; **8 MB** (A9) | `UiMediaLimits` + new `THEME_SOUND_MAX_BYTES` |
| Ambience | 10 min; **32 MB** (A9) | `UiMediaLimits.AMBIENCE` + new `THEME_AMBIENCE_MAX_BYTES` |
| Description | 500 chars | sanitized on read |

Validation runs **on Studio export and again on launcher apply** — apply must not trust the bundle
(the MotionLimits.kt:84-86 gap is closed for motion and every new media entry in TS-12).

### 5.5 Path safety

- Registered entries: key gated by registry (`IconSlots`, `CustomizableIcons`, `ThemeMediaSlots`)
  — unchanged discipline.
- Passthrough entries: name must match `^[a-z0-9_]+(/[a-z0-9_]+)?\.[a-z0-9]{1,5}$`, ≤ 96 chars, no
  `..`, no leading `/`, no `\`, at most one directory level, not colliding with a registered entry
  name; anything else is dropped and reported as "Can't recover". Passthrough is **never extracted**
  to disk by the launcher; it is only re-written into a bundle.

---

## 6. Compatibility matrix

| Bundle → / Reader ↓ | v1 | v2 | v3 | v4 | Future (v5+) |
|---|---|---|---|---|---|
| **Pre-v4 launcher** | applies | applies | applies | applies v3 subset: wallpaper, colors, legacy `waveStyle` (Reduced+Static → static), layout, 52 icons, 40 sysicons, motion uncropped; ignores sounds/boot/legibility/new slots. **Refuses files > 64 MB** (TooLarge) | applies known subset |
| **v4 launcher** | applies; "Older format" tag | same | same | full | applies known subset; unknown kept on "Update theme file" |
| **Pre-v4 Studio** | opens | opens | opens (drops sysicons — bug c) | opens v3 subset; re-export loses v4 data | opens subset |
| **v4 Studio** | opens + upgrade banner | same | same | full, lossless | opens, lossless passthrough, banner says "made by a newer version" |

"Applies" never requires an upgrade. Upgrading writes a v4 file that every column above still opens.

---

## 7. Architecture per module

### 7.1 core/theme-kit
- `PfpThemeManifest` gains the v4 fields (§5.1); `ThemeLegibility`, `MotionCrop` value types with
  sanitizers; `WaveStyles` helper (resolve exact / encode legacy+exact).
- `PfpThemeBundle` gains `manifestExtras: JsonObject`, `media: Map<ThemeMediaSlot, ThemeMotion-like stream>`,
  `passthrough: List<PassthroughEntry>`. `ThemeMotion`'s reopen mechanism is generalized
  (`motionFrom` → any entry name) rather than duplicated.
- `PfpThemeCodec.readDetailed()` returns bundle + `ReadDiagnostics`; `read()` keeps its signature.
- `ThemeUpgrade`: `report(bundle, diagnostics)` → Kept / Added / Repaired / Can't recover;
  `upgrade(bundle, today)` → v4 bundle.
- `ThemeMediaSlots` (keys mirror `UiMediaSlot`); `MediaDurationProbe` (pure JVM: MP3, WAV, OGG, MP4/M4A).
- Registry growth (§5.3).

### 7.2 core/core-data
- `PfpThemeStore.apply()` / `saveCurrentLook()` / `resetApplied()` extended field by field.
- New `theme-media/` tier extracted by apply after an **apply-side gate**; `UiMediaStore.pathFor`
  falls back to it; `ui_media_stamp` bumped on apply/reset so players reload.
- New pref `display_motion_crop` (set-or-remove; cleared by Display's own motion import); backed up.
- `SavedTheme.schemaVersion` + `upgradeInPlace(id)`.

### 7.3 core/core-ui, feature-xmb, feature-settings (launcher render)
- Crop-aware video transform replacing `applyCenterCrop`.
- New-slot wiring at each render site through the existing two-tier lookup (`ThemedGlyph` or the
  `LocalCustomIcons ?: LocalXmbIconOverrides` idiom).
- My Themes: "Older format" tag + "Update theme file" option.

### 7.4 studio — model
- `StudioState` v4 fields; lossless hydrate/export; `created`/`updated`; undo/redo history that
  owns scratch-file lifetime; media import gates; crop engine; upgrade flows; icon-pack import;
  Export-check model (report + budget).

### 7.5 studio — UI and preview
- Shell per D1. Preview rebuilt at **832×468 dp** with launcher geometry: 124 dp category slots
  (XMBCategoryBar.kt:54), anchor 130 dp, bar slide `spring(StiffnessMediumLow)` (:144, :195),
  category icons 72/56 alpha .58 (:200) with label on selected only; rows 88 dp (XMBItemList.kt:118),
  start pad 137, previous row half-clipped on bar top, selected under bar; row scale 1.06/0.9 is a
  **spring** and alpha .68 (XMBItemList.kt:627-636) while row *position* moves instantly;
  category switch `AnimatedContent` fade 220 + slide +h/8 260 / fade 160 + slide −h/10 180
  (XMBShell.kt:845-846); drill-in instant (bar truncates to sel+1, shifts −121 dp, left sibling icon
  column with ◀ accent, children at x=154); `PspContextMenu` flyout instant (300 dp, waveColor@75 %);
  Games filter (Search top-right, Sort group); PIC0 logo fade after 650 ms (XMBShell.kt:699) over
  500 ms; wave per XmbBackground with all four modes (frozen at t=2); scrim `0x59`.
- Live motion: animated wave; GIF icon on focus (Skia `Codec`); motion loop via JCodec frame
  decode capped at `MotionLimits`, poster fallback when decode cannot keep up; boot/GameBoot playable.

---

## 8. Risks

| Risk | Mitigation |
|---|---|
| **CMP animation stability.** The Studio avoided frame animation on 1.6.11 after NodeChain/SlotTable crashes (StudioApp.kt:89-90, PreviewRenderer.kt KDoc). D2 needs continuous animation. | TS-26 spike first; Phase 5 preview-motion tasks are BLOCKED until it passes. Offscreen `ImageComposeScene` renders stay on the AWT thread (existing rule). |
| **JCodec decode performance.** Pure-Java H.264 at 1080p may not reach 30 fps. | Decode to a bounded frame ring at preview resolution; measured fps below threshold → poster fallback with a visible "Preview shows poster — plays on device" note. Never block the UI thread. |
| **Native parsing of shipped audio/video** (MediaCodec CVE surface — VideoCodecs.kt:26-35). v4 widens it to sounds and boot clips. | Gate on **export** (Studio: header probe + caps) **and on apply** (launcher: `MediaMetadataRetriever` probe + `UiMediaLimits`/`MotionLimits.validate`) — TS-12. Reject per-entry, never the whole theme. |
| **Robolectric cannot probe media.** `MediaMetadataRetriever` returns nothing under Robolectric. | Apply-side gate takes an injectable probe function; tests pass a fake. |
| **Bundle size budget / heap.** Icons and passthrough read paths; 64 MB old-launcher cap. | Media + passthrough streamed, never held. Export check shows per-kind byte totals and the 64 MB pre-v4 warning (A8). |
| **Slot-key / entry-name path safety.** 76 new keys + passthrough. | Registry gating unchanged for registered entries; strict safe-name rule for passthrough (§5.5); hostile-input tests extend `HostileInputTest`. |
| **Undo vs scratch files.** `discardMotion` deletes the temp video (StudioViewModel.kt:147-149); undoing a clear would reference a deleted file. | History owns file lifetime: a scratch file is deleted only when no live state or history snapshot references it (TS-21). |
| **Guard-test lockstep.** Adding slots fails `DefaultSlotGlyphTest`, `StudioIconSetTest`, and the exhaustive `groupLabel` `when` at once. | TS-06 changes all three in one task (stated budget exception). |
| **Legibility ownership.** Theme writing Display prefs can surprise a user. | A1: absent fields never touch prefs; flagged for owner confirmation. |
| **Display motion import keeps a stale theme crop.** | TS-11: Display's motion import removes `display_motion_crop`. |

---

## 9. Testing strategy

- **Golden fixtures** (TS-01): programmatic builders for v1 (shape at `f6078e30`), v2 (`db0eb13b`),
  v3 (current), v4, and "future" (schema 99, unknown keys, unknown entries, unknown enum values) in
  `core/theme-kit/src/testFixtures`. A frozen **v3-era reader snapshot** (copy of today's manifest
  class + gating rules, test sources only) proves a v4 bundle still yields its v3 subset.
- theme-kit: codec round-trip, passthrough, diagnostics, upgrade report, sanitizers, registry
  counts (=128), hostile names, duration probe per container.
- core-data (Robolectric, existing `PfpThemeStore*Test` style): apply/reset/saveCurrentLook per new
  field, media tier precedence, apply-side gate with fake probe, upgrade-in-place.
- core-domain: `ThemeMediaSlots` ↔ `UiMediaSlot` drift pin.
- feature-xmb / core-ui: pure slot-key mapping functions (e.g. `shibaSlotKeyFor`,
  `notificationSlotKey`), crop transform math; guard tests stay green.
- studio: ViewModel round-trips (existing `ViewModel*RoundTripTest` style), undo/redo, media gates,
  crop engine, upgrade batch, icon-pack matching, `PreviewNav` reducer.
- Device checks and Studio checks are done by the owner (§11 gates).

```bash
./gradlew :core:theme-kit:test
./gradlew :core:core-domain:testDebugUnitTest
./gradlew :core:core-data:testDebugUnitTest --tests "*PfpThemeStore*" --tests "*UiMediaStore*"
./gradlew :core:core-ui:testDebugUnitTest
./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*DefaultSlotGlyph*"
./gradlew :feature:feature-backup:testDebugUnitTest --tests "*BackupKeyCoverage*"
./gradlew :studio:test
```

---

## 10. Out of scope

- Fonts (D8). Controller-prompt glyphs (~90). Physical-media cards (102).
- New bundled art for cps1–3 / xbox (A4) — slots exist, defaults unchanged.
- Exposing the new groups in the on-device Customize XMB Icons editor (A5).
- Volumes, Show Boot Sequence, GameBoot on/off, Battery Saver / Thermal, `display_text_shadow`,
  Adjust XMB Layout values — device-only, never in a theme.
- GIF motion cropping; WebM boot/motion authoring in the Studio (A10).
- Any audio-decoder dependency for the Studio (A10) unless the owner approves one.
- Launcher-side offscreen XMB preview rendering (A12).

---

## 11. Execution Task Index

Status key: `READY` · `IMPLEMENTING` · `VERIFYING` · `DONE` · `BLOCKED`.
Effort: S = under a day · M = a few days · L = a week or more.
`DONE` requires: acceptance criteria met, the task's tests written first and passing, no known blocker.
**DEVICE** = stop after this task; the owner verifies on the handheld. **STUDIO** = stop; the owner runs the desktop app.

| ID | Task | Depends On | Effort | Check | Status |
|---|---|---|---|---|---|
| **Phase 1 — Format v4 (theme-kit)** | | | | | |
| TS-01 | Golden fixtures per schema + frozen v3-era reader snapshot | None | M | — | DONE |
| TS-02 | Manifest v4 fields, wave fallback, legibility/crop sanitizers | TS-01 | M | — | DONE |
| TS-03 | Lossless passthrough: unknown manifest keys + unknown entries, safe names | TS-02 | M | — | DONE |
| TS-04 | Read diagnostics + `ThemeUpgrade` (report + upgrade to v4) | TS-03 | M | — | DONE |
| TS-05 | Console extra slots (`SYSICON_EXTRA_IDS`) | TS-01 | S | — | DONE |
| TS-06 | 29 new UI slots + 5 groups, lockstep defaults (launcher + Studio) | TS-05 | M | — | DONE |
| TS-07 | Theme media entries in codec + `ThemeMediaSlots` + drift pin + caps | TS-03 | M | — | DONE |
| TS-08 | Pure-JVM `MediaDurationProbe` (MP3/WAV moved; OGG, MP4/M4A added) | None | M | — | DONE |
| TS-09 | `docs/theme-format.md` + stale doc/comment fixes | TS-02, TS-04, TS-06, TS-07 | S | — | DONE |
| *Gate G1* | *Owner runs theme-kit, core-domain, feature-xmb guard and studio unit tests* | | | | |
| **Phase 2 — Launcher data (core-data)** | | | | | |
| TS-10 | Manifest-field fidelity in apply/saveCurrentLook: exact wave (bug a), legibility, exact colour | TS-02 | M | — | DONE |
| TS-11 | Motion crop persistence (`display_motion_crop`) + Display import clears + backup | TS-02 | S | — | DONE |
| TS-12 | Apply-side media gate + `theme-media/` extraction (motion re-validated too) + reset; import cap raise | TS-07, TS-08 | M | — | DONE |
| TS-13 | Theme media tier in `UiMediaStore.pathFor` + saveCurrentLook media capture | TS-12 | M | DEVICE | DONE |
| TS-14 | Older-format detection, `upgradeInPlace`, My Themes tag + "Update theme file" | TS-04, TS-10 | M | DEVICE | DONE |
| **Phase 3 — Launcher render wiring** | | | | | |
| TS-15 | Crop-aware motion playback transform | TS-11 | S | DEVICE | DONE |
| TS-16 | Shiba Coins slots (bug b) + coin medallions | TS-06 | S | DEVICE | DONE |
| TS-17 | Status (+4) and Notifications (8) slots | TS-06 | M | DEVICE | DONE |
| TS-18 | Media controls (6) and Game Detail (5) slots | TS-06 | M | DEVICE | DONE |
| TS-19 | Menus: check mark + back arrow slots | TS-06 | S | DEVICE | DONE |
| *Gate G3* | *Owner device pass over TS-13…TS-19* | | | | |
| **Phase 4 — Studio model** | | | | | |
| TS-20 | Lossless hydrate/export (bugs c, d) + v4 `StudioState` fields | TS-04, TS-06, TS-07 | M | — | DONE |
| TS-21 | Undo/Redo history owning scratch-file lifetime | TS-20 | M | — | DONE |
| TS-22 | Studio media import gates (sounds, ambience, boot, gameboot) + Export-check model | TS-08, TS-20 | M | — | DONE |
| TS-23 | Crop engine: ratio-locked rect, image bake, poster bake, `motionCrop` | TS-20 | M | — | DONE |
| TS-24 | Upgrade banner state + "Upgrade folder…" batch (.bak + report) | TS-20 | M | — | DONE |
| TS-25 | Icon-pack import (folder/zip, slot-key match, unmatched report) | TS-20 | S | — | DONE |
| *Gate G4* | *Owner runs `:studio:test`* | | | | |
| **Phase 5 — Studio UI** | | | | | |
| TS-26 | Spike: CMP 1.12 frame-animation stability | None | S | STUDIO | DONE (GO) |
| TS-27 | Shell restructure: rail, center + "In this file" strip, inspector, toolbar, neutral chrome | TS-21, TS-26 | M | STUDIO | DONE |
| TS-28 | Panels A: Info, Color, Legibility, Layout (+Advanced, preview-only adjust), Export check | TS-22, TS-24, TS-27 | M | STUDIO | DONE |
| TS-29 | Panels B: Background (source/fit/crop/motion control), Sounds, Boot & GameBoot | TS-22, TS-23, TS-27 | M | STUDIO | DONE |
| TS-30 | Icons section picker (search, chips, filters, drag-drop, slot card, pack import, templates) | TS-25, TS-27 | M | STUDIO | DONE |
| TS-31 | Preview I: 832×468 geometry + `PreviewNav` + category/row/drill motion | TS-26, TS-27 | L | STUDIO | DONE |
| TS-32 | Preview II: options flyout, Games filter, PIC0 fade, legibility, layout-adjust overlay | TS-31 | M | STUDIO | DONE |
| TS-33 | Preview III: live motion (wave modes, GIF icons, motion loop + fallback, boot/GameBoot) | TS-32 | L | STUDIO | DONE |

Phase gates: G1 after TS-09 · G2 = device checks inside TS-13/TS-14 · G3 after TS-19 · G4 after TS-25 ·
TS-26's outcome gates TS-31…TS-33 (if it fails, they go `BLOCKED` and the plan is revised).

---

## 12. Task specifications

Each block below is the hand-off for one task. Give the implementer only its block plus §5 when
the block says so.

---

### TS-01 — Golden fixtures per schema + frozen v3-era reader snapshot

**Parent / Phase:** Theme Format v4 — Phase 1 · **Depends On:** None · **Status:** READY

**Objective.** Pin today's read behaviour for every historical schema before anything changes, and
capture the v3-era reader as a frozen test artifact so later tasks can prove backwards compatibility.

**Scope.** Test fixtures and characterization tests only. No production code.

**Existing / relevant code.**
- `core/theme-kit/src/testFixtures/kotlin/com/playfieldportal/themekit/TestFixtures.kt` — add builders here.
- `PfpThemeCodecV3Test.kt` — style to follow (e.g. "v2-shaped bundle still reads unchanged").
- v1 manifest: `git show f6078e30:core/theme-kit/src/main/kotlin/com/playfieldportal/themekit/PfpTheme.kt`; v2: `db0eb13b`.

**Tests first / Requirements.**
1. `ThemeFixtures` builders: `v1()`, `v2()`, `v3()` (gif icon, sysicon, motion, textColor), `future()`
   (schemaVersion 99, unknown manifest keys incl. a nested object, unknown zip entries incl.
   `extras/thing.bin` and `icons/item_from_the_future.png`, unknown `waveStyle` value). Built from raw
   JSON strings + `ZipOutputStream`, not from today's data classes (so they cannot drift with them).
2. `V3EraReader` in test sources: a frozen copy of today's `PfpThemeManifest` field set and today's
   icon/sysicon gating key lists (52 + 40), decoding with `ignoreUnknownKeys`. Document it as frozen.
3. `GoldenBundleTest`: every fixture reads non-null through `PfpThemeCodec.read(bytes)` and through
   `V3EraReader`; assert the exact fields/entries each yields today.

**Do not change.** Any file under `src/main`. Existing tests.

**Expected files.** Modify `TestFixtures.kt` or add `ThemeFixtures.kt` (testFixtures); add `V3EraReader.kt`, `GoldenBundleTest.kt` (test).

**Acceptance criteria.**
- [ ] Five fixtures exist and are reusable from core-data and studio tests (testFixtures source set).
- [ ] `GoldenBundleTest` passes against unmodified production code.

**Change budget.** 0 production files; 1–2 testFixtures files; 2 test files.

**Verification.** `./gradlew :core:theme-kit:test --tests "*GoldenBundle*"`

**Stop condition.** Stop when the fixtures exist and characterize today's behaviour. Do not begin v4 fields.

**If blocked.** STOP and report what you attempted, what blocked you, which file caused it, and what decision is needed.

---

### TS-02 — Manifest v4 fields, wave fallback, sanitizers

**Parent / Phase:** Phase 1 · **Depends On:** TS-01 · **Status:** READY

**Objective.** Add the v4 typed manifest fields (§5.1) with their read/write rules, so every later
task has one place to read them from.

**Scope.** `PfpThemeManifest` fields `author`, `description`, `updated`, `textColorExact` (Boolean?),
`waveStyleV4` (String?), `legibility` (`ThemeLegibility?`), `motionCrop` (`MotionCrop?`);
`SCHEMA_VERSION = 4`; `WAVE_REDUCED_STATIC = "reduced_static"`; `WaveStyles.resolveExact(manifest)` and
`WaveStyles.encode(exact) → (legacy, v4)`; sanitizers for `MotionCrop`, description length,
legibility enum strings (unknown → null).

**Existing / relevant code.** `PfpTheme.kt:16-49`; `XmbLayoutSpecCodec.sanitize` as the sanitizer pattern;
`PfpThemeCodecV3Test` "textColor round-trips and the schema version does not move" (update deliberately).

**Tests first.** Wave resolve matrix (V4 present/absent/unknown × legacy present/unknown);
`reduced_static` encodes legacy `static`; crop clamp/NaN/min size cases; legibility unknown members;
v1/v2/v3/future fixtures still read; fields absent in old fixtures read as null.

**Do not change.** Codec entry handling; `PfpThemeStore`; Studio.

**Expected files.** Modify `PfpTheme.kt`; add `ThemeManifestV4.kt` (value types + `WaveStyles`); add `ManifestV4Test.kt`; adjust one assertion in `PfpThemeCodecV3Test.kt`.

**Acceptance criteria.**
- [ ] All §5.1 read rules 2–4 and write rule 1 hold under test.
- [ ] `GoldenBundleTest` still passes.

**Change budget.** 1–2 modified, 1 new, 1 new test file.

**Verification.** `./gradlew :core:theme-kit:test`

**Stop condition.** Stop at typed fields + helpers. No passthrough, no media.

**If blocked.** STOP and report (four points).

---

### TS-03 — Lossless passthrough with safe names

**Parent / Phase:** Phase 1 · **Depends On:** TS-02 · **Status:** READY

**Objective.** A bundle read then written back loses nothing it did not understand (A11).

**Scope.** `PfpThemeBundle.manifestExtras` (unknown manifest keys as `JsonObject`, merged on write
with typed fields winning); `PfpThemeBundle.passthrough` (unknown entries — including unregistered
`icons/` and `sysicons/` names — as streamed entries reopened from the source like `motionFrom`,
PfpThemeCodec.kt:244-256, generalized); safe-name rule §5.5; `equals`/`hashCode` updated.

**Existing / relevant code.** `PfpThemeCodec.kt:116-189` read, `:68-99` write; `ThemeMotion` (PfpTheme.kt:84-110);
`BoundedZipReader`; `HostileInputTest.kt`.

**Tests first.** `future()` fixture round-trips byte-for-byte on manifest extras and on every unknown
entry; passthrough is not heap-held (stream-backed, assert via a counting source); traversal names
(`../x`, `/abs`, `a\\b`, `a/b/c.png`, over-length) are dropped and listed; a passthrough name that
collides with a registered entry is dropped; the existing test "write still drops unregistered icon
keys" stays green (passthrough is a separate field, not a relaxation of `icons`).

**Do not change.** Registered-entry gating; `BUNDLE_LIMITS`; `readManifest`.

**Expected files.** Modify `PfpThemeCodec.kt`, `PfpTheme.kt`; add `PassthroughEntry.kt`; add `PassthroughTest.kt`; extend `HostileInputTest.kt`.

**Acceptance criteria.**
- [ ] Read→write of `future()` preserves all unknown keys and entries.
- [ ] No hostile name reaches the written zip.

**Change budget.** 2 modified, 1 new, 1–2 test files.

**Verification.** `./gradlew :core:theme-kit:test`

**Stop condition.** Stop when passthrough is lossless and safe. Diagnostics are TS-04.

**If blocked.** STOP and report (four points).

---

### TS-04 — Read diagnostics + ThemeUpgrade

**Parent / Phase:** Phase 1 · **Depends On:** TS-03 · **Status:** READY

**Objective.** Repair-don't-reject: every read can explain what it kept, repaired, and could not recover,
and any bundle can be upgraded to v4 without loss.

**Scope.** `PfpThemeCodec.readDetailed(file|bytes)` → `(bundle, ReadDiagnostics)` (dropped entries with
reason: over cap, bad extension, hostile name, undecodable manifest field); `read()` delegates and keeps
its signature. `ThemeUpgrade.report(bundle, diagnostics)` → `UpgradeReport(kept, added, repaired, cantRecover)`
with human strings; `ThemeUpgrade.upgrade(bundle, today)` → v4 manifest (legacy+exact wave, `created`
preserved or backfilled, `updated = today`, extras and passthrough kept).

**Existing / relevant code.** `PfpThemeCodec.kt:116-189`; sanitizers from TS-02.

**Tests first.** Each fixture produces the expected report; upgrade of v1/v2/v3/future is idempotent
(upgrading twice = once); upgraded bundle still decodes under `V3EraReader` to the same v3 subset as the
original; malformed accent hex reported as Repaired; an 9 MB icon reported as Can't recover.

**Do not change.** Launcher and Studio callers (they adopt it later).

**Expected files.** Modify `PfpThemeCodec.kt`; add `ThemeUpgrade.kt`; add `ThemeUpgradeTest.kt`.

**Acceptance criteria.**
- [ ] `read()` behaviour unchanged for all existing tests.
- [ ] Report categories correct for every fixture; upgrade idempotent and v3-subset-preserving.

**Change budget.** 1 modified, 1 new, 1 test file.

**Verification.** `./gradlew :core:theme-kit:test`

**Stop condition.** Stop at the pure model. No UI, no store wiring.

**If blocked.** STOP and report (four points).

---

### TS-05 — Console extra slots

**Parent / Phase:** Phase 1 · **Depends On:** TS-01 · **Status:** DONE

**Objective.** Let themes carry art for `cps1`, `cps2`, `cps3`, `xbox`, `favorites`, `desktop`, `default` (A4).

**Scope.** `SYSICON_EXTRA_IDS` list + display names; `CustomizableIcons.ALL` console group =
platform ids then extras; `sysicons/` codec gating follows automatically through `CustomizableIcons.isValidKey`.

**Existing / relevant code.** `CustomizableIcons.kt:14-130`; `CustomizableIconsTest.kt` (the
"rejects sysicon_default" assertion and "derives from the platform id list" must be **deliberately**
updated, with a comment citing A4); `SystemIconsTest` (must remain untouched and green);
`ConsoleIcon.kt:21-22` (already resolves these keys); `DefaultSlotGlyph.kt:81-83` (CONSOLE handled generically).

**Tests first.** Console group size 47; extras accepted by `isValidKey`; `SYSICON_PLATFORM_IDS` unchanged
(size 40); codec round-trips `sysicons/cps1.png` and `sysicons/default.png`; `V3EraReader` drops them.

**Do not change.** `SystemIcons.kt`, `SystemIconsTest`, bundled drawables.

**Expected files.** Modify `CustomizableIcons.kt`, `CustomizableIconsTest.kt`; extend `PfpThemeCodecV3Test.kt` or a new test.

**Acceptance criteria.**
- [ ] 47 console slots; `SystemIconsTest` and `DefaultSlotGlyphTest` green without edits.

**Change budget.** 1 modified main, 1–2 test files.

**Verification.**
```bash
./gradlew :core:theme-kit:test
./gradlew :core:core-ui:testDebugUnitTest --tests "*SystemIcons*"
./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*DefaultSlotGlyph*"
```

**Stop condition.** Stop at registry + tests.

**If blocked.** STOP and report (four points).

---

### TS-06 — 29 new UI slots, 5 groups, lockstep defaults

**Parent / Phase:** Phase 1 · **Depends On:** TS-05 · **Status:** DONE

**Objective.** Register the 29 new `icons/` slots of §5.3 and give each a built-in default in both apps
so the three guard tests stay green.

**Scope.** `IconSlot.Group` += SHIBA, MEDIA, GAME_DETAIL, NOTIFICATIONS, MENUS; `IconSlots.ALL` appends
(status +4, shiba medallions 4, media 6, detail 5, notifications 8, menus 2) with template sizes
(status 128, others 256); launcher `DefaultSlotGlyph.defaultGlyphFor` defaults (vectors listed in §2.5;
medallions → `shibaCoinRes`; `menu_check` / `menu_back` → a vector or a new `SlotGlyphDefault` case if the
drawn check needs one); `CustomIconsOverlay.groupLabel` labels; Studio `StudioIconSet` art for every new key.

**Existing / relevant code.** `IconSlots.kt`; `DefaultSlotGlyph.kt:56-99`; `CustomIconsOverlay.kt:285-289`;
`studio/.../preview/StudioIconSet.kt`; `StudioIconSetTest`, `DefaultSlotGlyphTest`, `CustomizableIconsTest`.

**Tests first.** Total `CustomizableIcons.ALL.size == 128`; `IconSlots.ALL` stays a verbatim prefix of
itself pre-change (first 52 unchanged, in order); group counts per §5.3; guard tests drive the defaults.

**Do not change.** Any render site (Phase 3 wires them); existing keys, order, or groups (A3);
`customIconGroups` in XMBViewModel (A5).

**Expected files.** Modify `IconSlots.kt`, `DefaultSlotGlyph.kt`, `CustomIconsOverlay.kt`, `StudioIconSet.kt`; extend `CustomizableIconsTest.kt`.

**Acceptance criteria.**
- [ ] 128 slots; the 52 original keys unchanged in key, order and group.
- [ ] `DefaultSlotGlyphTest`, `StudioIconSetTest`, `CustomizableIconsTest` green.

**Change budget.** 4 modified + 1 test. **Exceeds the 2–4 guideline on purpose:** three guard tests in three
modules fail together the moment a key is added; splitting would leave the tree red between tasks.

**Verification.**
```bash
./gradlew :core:theme-kit:test
./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*DefaultSlotGlyph*"
./gradlew :studio:test --tests "*StudioIconSet*"
```

**Stop condition.** Stop when registered and defaulted. Do not wire any render site.

**If blocked.** STOP and report (four points).

---

### TS-07 — Theme media entries in the codec

**Parent / Phase:** Phase 1 · **Depends On:** TS-03 · **Status:** DONE

**Objective.** The bundle can carry the five menu sounds, ambience, boot and GameBoot (§5.2, A7, A9).

**Scope.** `ThemeMediaSlots` (theme-kit) with keys identical to `UiMediaSlot` keys and their entry names;
codec read/write as streamed entries (same reopen mechanism as motion); extension gating via
`UiMediaLimits.mimeForExtension` and kind; `THEME_SOUND_MAX_BYTES` (8 MB) and `THEME_AMBIENCE_MAX_BYTES`
(32 MB) added to `UiMediaLimits`; drift pin in core-domain (`ThemeMediaSlots` keys == `UiMediaSlot` keys).

**Existing / relevant code.** `PfpThemeCodec.kt`; `UiMediaLimits.kt:108-146, :212-242`;
`UiMediaSlot.kt:51-73`; `UiMediaSlotTest.kt` (core-domain, add the pin here).

**Tests first.** Round-trip each media entry streamed; wrong extension (`boot.gif`, `sounds/sound_back.mp4`)
dropped and reported; unknown sound key (`sounds/sound_launch.mp3`, a retired key) becomes passthrough,
not media; `V3EraReader` ignores all media; drift pin.

**Do not change.** `UiMediaSlot` enum; launcher stores.

**Expected files.** Modify `PfpThemeCodec.kt`, `PfpTheme.kt`, `UiMediaLimits.kt`; add `ThemeMediaSlots.kt`; add `ThemeMediaCodecTest.kt`; extend `UiMediaSlotTest.kt`.

**Acceptance criteria.**
- [ ] All eight media entries round-trip without heap materialization.
- [ ] Drift pin green.

**Change budget.** 3 modified, 1 new, 2 test files.

**Verification.**
```bash
./gradlew :core:theme-kit:test
./gradlew :core:core-domain:testDebugUnitTest --tests "*UiMediaSlot*"
```

**Stop condition.** Stop at the codec. No launcher extraction.

**If blocked.** STOP and report (four points).

---

### TS-08 — Pure-JVM media duration probe

**Parent / Phase:** Phase 1 · **Depends On:** None · **Status:** DONE

**Objective.** The Studio must time a sound without `MediaMetadataRetriever` (UiMediaLimits.validate
requires a duration — UiMediaLimits.kt:186-193).

**Scope.** Move `MediaDurationFallback`'s MP3/WAV parsing into theme-kit as `MediaDurationProbe`; add OGG
(Vorbis/Opus: identification header sample rate + last page granule position) and MP4/M4A (`mvhd`
timescale/duration). core-data `MediaDurationFallback.durationMs` delegates (keeps its signature and
its "never widens what passes" contract).

**Existing / relevant code.** `MediaDurationFallback.kt` (core-data); `MediaDurationFallbackTest.kt`.

**Tests first.** Port existing MP3/WAV cases; synthetic minimal OGG (Vorbis and Opus) and M4A headers
with known durations; truncated/hostile headers return null, never throw; bounded reads (head ≤ 64 KB,
tail ≤ 64 KB for OGG).

**Do not change.** `UiMediaStore` import flow behaviour.

**Expected files.** Add `MediaDurationProbe.kt` (theme-kit) + test; modify `MediaDurationFallback.kt` (delegate); keep `MediaDurationFallbackTest` green.

**Acceptance criteria.**
- [ ] Four containers timed; hostile input returns null.
- [ ] core-data tests unchanged and green.

**Change budget.** 1 modified, 1 new, 1 new test.

**Verification.**
```bash
./gradlew :core:theme-kit:test --tests "*MediaDurationProbe*"
./gradlew :core:core-data:testDebugUnitTest --tests "*MediaDurationFallback*"
```

**Stop condition.** Stop at the probe.

**If blocked.** STOP and report (four points).

---

### TS-09 — Theme format document + stale references

**Parent / Phase:** Phase 1 · **Depends On:** TS-02, TS-04, TS-06, TS-07 · **Status:** DONE

**Objective.** One authoritative format document; no code comment points at a deleted file (A13, D10e).

**Scope.** Write `docs/theme-format.md` (v1→v4 history, §5 of this plan as shipped, compatibility rules,
§6 matrix). Re-point comments at PfpTheme.kt:9, PfpThemeStore.kt:39, ThemesSettingsViewModel.kt:34,
XMBViewModel.kt:10221. Fix PfpThemeStore.kt:631-632 (bundles DO carry motion since v3). Fix CONTEXT.md
"UI media slot" (:78-86: five menu sounds, not six; no `BOOT_AUDIO`; add the theme tier sentence).

**Tests first.** Exempt — documentation and comments only. No behaviour change.

**Do not change.** Any executable line.

**Expected files.** Add `docs/theme-format.md`; modify the five files above (comments only).

**Acceptance criteria.**
- [ ] `rg "xmb-theme-creator-plan"` returns nothing in source.
- [ ] Diff of the Kotlin files is comments-only.

**Change budget.** 1 new doc; 5 files comment-only (stated exception: comment re-pointing).

**Verification.** `rg -n "xmb-theme-creator-plan|six menu sounds|BOOT_AUDIO" --glob '!docs/plans/**'`

**Stop condition.** Stop when the doc exists and references resolve.

**If blocked.** STOP and report (four points).

---

### TS-10 — Manifest-field fidelity in apply / saveCurrentLook

**Parent / Phase:** Theme Format v4 — Phase 2 · **Depends On:** TS-02 · **Status:** DONE

**Objective.** Fix bug (a) and carry legibility + exact colour through apply and saveCurrentLook (A1).

**Scope.** `apply()`: wave via `WaveStyles.resolveExact` → `REDUCED_STATIC` reachable (PfpThemeStore.kt:170-174);
legibility/exact fields written to `display_text_legibility`, `display_icon_legibility`,
`display_solid_unfocused_icons`, `display_text_color_exact` **only when present**. `saveCurrentLook()`:
writes legacy+v4 wave (:473-477), and the four fields from current prefs, `author`/`description` blank,
`created` today, `updated` today. `resetApplied()`: unchanged for these four (A1).

**Existing / relevant code.** `PfpThemeStore.kt:100-227, :425-527, :641-643`; enum `fromName`s;
`PfpThemeStoreTest`, `PfpThemeStoreV3Test` patterns.

**Tests first.** Device `REDUCED_STATIC` → saveCurrentLook → apply → pref is `REDUCED_STATIC`; v3 bundle
(no legibility) leaves a user's `display_icon_legibility = CONTOUR_AUTO` untouched; v4 bundle with
legibility writes all four; unknown enum string leaves pref untouched.

**Do not change.** Icon/motion/wallpaper handling; `display_text_shadow`; DisplaySettingsViewModel.

**Expected files.** Modify `PfpThemeStore.kt`; add `PfpThemeStoreV4FieldsTest.kt`.

**Acceptance criteria.**
- [ ] Bug (a) fixed both directions; A1 contract holds.

**Change budget.** 1 modified, 1 test.

**Verification.** `./gradlew :core:core-data:testDebugUnitTest --tests "*PfpThemeStore*"`

**Stop condition.** Stop at these fields.

**If blocked.** STOP and report (four points).

---

### TS-11 — Motion crop persistence

**Parent / Phase:** Phase 2 · **Depends On:** TS-02 · **Status:** DONE

**Objective.** The applied theme's `motionCrop` reaches a pref the renderer can read, and never leaks onto a user's own video.

**Scope.** New `display_motion_crop` (compact JSON) — apply sets-or-removes alongside the motion key
(PfpThemeStore.kt:189); reset removes; saveCurrentLook captures; DisplaySettingsViewModel's motion
import (:637) and clear remove it; BackupManager string key list + `BackupKeyCoverageTest`.

**Existing / relevant code.** `PfpThemeStore.kt:180-201`; `DisplaySettingsViewModel.kt:637, :720`; `BackupManager.kt:564-592`; `BackupKeyCoverageTest.kt`.

**Tests first.** Apply with crop sets key; apply without crop removes; Display motion import removes; backup covers key.

**Do not change.** Rendering (TS-15).

**Expected files.** Modify `PfpThemeStore.kt`, `DisplaySettingsViewModel.kt`, `BackupManager.kt`; tests in `PfpThemeStoreV4FieldsTest.kt`, `DisplaySettingsViewModelWallpaperTest.kt`, `BackupKeyCoverageTest.kt`.

**Acceptance criteria.**
- [ ] Crop pref lifecycle per scope; backup covered.

**Change budget.** 3 modified, 3 test files touched.

**Verification.**
```bash
./gradlew :core:core-data:testDebugUnitTest --tests "*PfpThemeStore*"
./gradlew :feature:feature-settings:testDebugUnitTest --tests "*DisplaySettingsViewModelWallpaper*"
./gradlew :feature:feature-backup:testDebugUnitTest --tests "*BackupKeyCoverage*"
```

**Stop condition.** Stop at persistence.

**If blocked.** STOP and report (four points).

---

### TS-12 — Apply-side media gate + theme-media extraction

**Parent / Phase:** Phase 2 · **Depends On:** TS-07, TS-08 · **Status:** DONE

**Objective.** Close the "apply never re-validates" gap (MotionLimits.kt:84-86) and install theme media.

**Scope.** In `apply()`: wipe `filesDir/theme-media/`; for each media entry stream to a staging file,
probe (injectable `(File, mime) -> Probe` seam; production = `MediaMetadataRetriever` + `MediaDurationFallback`),
validate with `UiMediaLimits` / theme caps, rename to `theme-media/<key>.<ext>` or drop with a log; motion
validated with `MotionLimits.validate` before its pref is set (invalid → treated as no motion, poster stays).
Bump `ui_media_stamp` when theme media changed. `resetApplied()` deletes `theme-media/` and bumps.
Raise `SafeMedia.MAX_THEME_FILE_BYTES` to `PfpThemeCodec.BUNDLE_LIMITS.maxTotalBytes` (A8).

**Existing / relevant code.** `PfpThemeStore.kt:100-227`; `UiMediaStore.kt:270-293` (probe pattern), `:306`;
`SafeMedia.kt:17`; `PfpThemeStoreV3Test` motion tests.

**Tests first.** Fake probe: valid sound installed; over-cap sound dropped while the rest of the theme applies;
invalid motion not installed; reset clears dir; stamp bumped; a 70 MB synthetic bundle imports.

**Do not change.** `UiMediaStore.pathFor` (TS-13); `ui-media/` contents.

**Expected files.** Modify `PfpThemeStore.kt`, `SafeMedia.kt`; add `ThemeMediaInstaller.kt` (core-data, holds the gate); add `PfpThemeStoreMediaTest.kt`.

**Acceptance criteria.**
- [ ] No unvalidated media or motion reaches disk-as-installed.
- [ ] Per-entry rejection never fails the whole apply.

**Change budget.** 2 modified, 1 new, 1 test.

**Verification.** `./gradlew :core:core-data:testDebugUnitTest --tests "*PfpThemeStore*"`

**Stop condition.** Stop at extraction. Playback precedence is TS-13.

**If blocked.** STOP and report (four points).

---

### TS-13 — Theme media tier precedence + saveCurrentLook media — **DEVICE**

**Parent / Phase:** Phase 2 · **Depends On:** TS-12 · **Status:** DONE

**Objective.** Theme sounds/boot/ambience play unless the user picked their own (A2), and "Save as Theme" captures them.

**Scope.** `UiMediaStore.pathFor()` → user file, else `theme-media/` file, else null; KDoc of
`UiMediaPaths.pathFor` updated ("null = built-in"); `assignments()` and `pruneOrphans()` unchanged (user tier
only). `saveCurrentLook()` captures per media slot user-over-theme, streamed.

**Existing / relevant code.** `UiMediaStore.kt:65-94, :208-245`; `UiMediaPaths.kt:16-21`; `PfpThemeStore.saveCurrentLook`.

**Tests first.** Theme-only → theme path; user+theme → user path; neither → null; `assignments()` ignores
theme files; saveCurrentLook bundle contains user sound over theme sound.

**Do not change.** `MenuSoundPlayer`, `UiMediaAudioPlayer`, any consumer (they already go through `pathFor`).

**Expected files.** Modify `UiMediaStore.kt`, `UiMediaPaths.kt` (KDoc), `PfpThemeStore.kt`; extend `UiMediaStoreTest.kt`, `PfpThemeStoreMediaTest.kt`.

**Acceptance criteria.**
- [ ] Precedence holds under test.
- [ ] **Device:** apply a theme with a custom Navigation sound → hear it; assign your own → yours wins; Reset theme → yours still plays.

**Change budget.** 3 modified, 2 tests.

**Verification.** `./gradlew :core:core-data:testDebugUnitTest --tests "*UiMediaStore*" --tests "*PfpThemeStore*"` then `./gradlew :app:installFullDebug` and hand to the owner.

**Stop condition.** Stop after the owner's device check.

**If blocked.** STOP and report (four points).

---

### TS-14 — Older format tag + Update theme file — **DEVICE**

**Parent / Phase:** Phase 2 · **Depends On:** TS-04, TS-10 · **Status:** DONE

**Objective.** My Themes shows which saved themes are older format and can rewrite them in place (D9).

**Scope.** `SavedTheme.schemaVersion` from `readManifest` (scan stays manifest-only, PfpThemeStore.kt:580-600);
`upgradeInPlace(id)`: `readDetailed` → `ThemeUpgrade.upgrade` → write temp → atomic replace; preview sidecar
kept, regenerated from wallpaper only when missing (A12). `ThemesSettingsViewModel.updateThemeFile(id)` with a
notification on result. UI: "Older format" tag on the card (`SavedThemeCardRow`, ThemesSettingsScreen.kt:514)
and "Update theme file" in `openMenuForSavedTheme` (:180-186), shown only when older.

**Existing / relevant code.** As cited; `reportTheme` (ThemesSettingsViewModel.kt:71).

**Tests first.** v1/v2/v3 fixtures report `schemaVersion < 4`; upgradeInPlace yields v4 with extras +
passthrough + media preserved; failure leaves the original file intact; apply of an un-upgraded v1 works.

**Do not change.** Apply path; share/delete.

**Expected files.** Modify `PfpThemeStore.kt`, `ThemesSettingsViewModel.kt`, `ThemesSettingsScreen.kt`; add `PfpThemeStoreUpgradeTest.kt`.

**Acceptance criteria.**
- [ ] Tag and option appear only for older themes; upgrade is atomic and lossless.
- [ ] **Device:** import an old theme → tag shows → Update → tag gone → theme applies identically.

**Change budget.** 3 modified, 1 test. Mockup: UI change is a tag + one menu row matching existing rows — confirm with the owner before building if any doubt (owner rule: mockup before UI).

**Verification.** `./gradlew :core:core-data:testDebugUnitTest --tests "*PfpThemeStoreUpgrade*"`, then device.

**Stop condition.** Stop after device check.

**If blocked.** STOP and report (four points).

---

### TS-15 — Crop-aware motion playback — **DEVICE**

**Parent / Phase:** Phase 3 · **Depends On:** TS-11 · **Status:** DONE

**Objective.** A theme's `motionCrop` frames its video exactly as the poster was baked (D3).

**Scope.** Extract a pure `motionTransform(viewW, viewH, videoW, videoH, crop: MotionCrop?)` returning
scale/translate; null crop = today's center-crop result. `MotionWallpaperBackground` reads the crop
(passed from XmbBackground via the existing pref plumbing) and applies it in place of `applyCenterCrop`
(MotionWallpaperBackground.kt:202-215). GIF path unchanged.

**Tests first.** JVM tests: null crop equals current center-crop numbers; full-frame crop on matching aspect
= identity; off-center crop maps rect corners to view corners.

**Do not change.** `MotionWallpaperPolicy`; poster rendering; player lifecycle.

**Expected files.** Modify `MotionWallpaperBackground.kt`, `XmbBackground.kt`, XMBViewModel pref read (one field);
add `MotionTransform.kt` + `MotionTransformTest.kt` (core-ui).

**Acceptance criteria.**
- [ ] Math tests pass; no change without a crop.
- [ ] **Device:** a cropped-video theme shows the poster and video aligned through the fade-in.

**Change budget.** 3 modified, 1 new, 1 test.

**Verification.** `./gradlew :core:core-ui:testDebugUnitTest --tests "*MotionTransform*"` then `./gradlew :app:installFullDebug`.

**Stop condition.** Stop after device check.

**If blocked.** STOP and report (four points).

---

### TS-16 — Shiba Coins slots + medallions — **DEVICE**

**Parent / Phase:** Phase 3 · **Depends On:** TS-06 · **Status:** DONE (device check pending)

**Objective.** Fix bug (b) and make coin medallions themeable.

**Scope.** Pure `shibaSlotKeyFor(id)`: `ach_connect → item_shiba_connect`, `ach_all → item_shiba_track`,
`ach_untracked → item_shiba_untracked`. XMBItemList.kt:1286-1300: `ach_all` checks the two-tier lookup before
`BundledSilhouetteIcon`; the glyph rows pass the real key instead of `""`. `ShibaCoinIcon` (ShibaCoinArt.kt:32-39)
checks `shiba_coin_<tier>` overrides first. Verify the detail screens sit inside XMBShell's provider
(XMBShell.kt:516-520) — if not, STOP.

**Tests first.** `shibaSlotKeyFor` mapping; tier → key mapping.

**Do not change.** `levelBadge` row; hub behaviour; `CoinArt` provider-badge branch.

**Expected files.** Modify `XMBItemList.kt`, `ShibaCoinArt.kt`; add `ShibaSlotKeysTest.kt`.

**Acceptance criteria.**
- [ ] **Device:** a theme with all seven Shiba art files shows them on the hub and coin lists.

**Change budget.** 2 modified, 1 test.

**Verification.** `./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*ShibaSlotKeys*"` then device.

**Stop condition.** Stop after device check.

**If blocked.** STOP and report (four points).

---

### TS-17 — Status (+4) and Notifications (8) — **DEVICE**

**Parent / Phase:** Phase 3 · **Depends On:** TS-06 · **Status:** READY

**Objective.** Wire `status_notifications`, `status_controller`, `status_wifi`, `status_signal`, and the eight `notif_*` slots.

**Scope.** XmbStatusStrip: bell (:346) and controller (:260-266) route through `StatusIcon`-style override
lookup; Wi-Fi/Signal: when an override exists draw it instead of the meter (A6), else the meter. Notifications:
`notificationSlotKey(kind)` beside `notificationGlyph` (NotificationIcons.kt:29-39); NotificationPanel.kt:221/:323
check the two-tier lookup.

**Tests first.** `notificationSlotKey` covers every `NotificationKind` (exhaustive test over `entries`).

**Do not change.** Severity rings; meter drawing code; bell tap behaviour.

**Expected files.** Modify `XmbStatusStrip.kt`, `NotificationIcons.kt`, `NotificationPanel.kt`; add `NotificationSlotKeyTest.kt`.

**Acceptance criteria.**
- [ ] **Device:** overrides render in the strip and panel; without overrides nothing changes.

**Change budget.** 3 modified, 1 test.

**Verification.** `./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*NotificationSlotKey*"` then device.

**Stop condition.** Stop after device check.

**If blocked.** STOP and report (four points).

---

### TS-18 — Media controls (6) and Game Detail (5) — **DEVICE**

**Parent / Phase:** Phase 3 · **Depends On:** TS-06 · **Status:** DONE

**Objective.** Wire `media_*` and `detail_*` slots.

**Scope.** `TransportButton` sites in MusicPlayerScreen.kt:421-443 and VideoPlayerScreen.kt:440-457 take a slot
key and render the override when present; GameDetailScreen.kt:406-449 action icons likewise; favorite per A6.

**Tests first.** Pure mapping of transport action → slot key and detail action → slot key.

**Do not change.** Button sizes, enablement, focus, layout.

**Expected files.** Modify the three screens; add `MediaDetailSlotKeysTest.kt`.

**Acceptance criteria.**
- [ ] **Device:** music, video and game-detail overrides render; untouched without a theme.

**Change budget.** 3 modified, 1 test.

**Verification.** `./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*MediaDetailSlotKeys*"` then device.

**Stop condition.** Stop after device check.

**If blocked.** STOP and report (four points).

---

### TS-19 — Menus: check mark + back arrow — **DEVICE**

**Parent / Phase:** Phase 3 · **Depends On:** TS-06 · **Status:** DONE

**Objective.** Wire `menu_check` and `menu_back`.

**Scope.** `PspContextMenuRow` check (PspContextMenu.kt:212) uses the override when present, else `PfpCheckMark`.
Back arrow: the shared `XmbTouchButton` back (:73-82) and `DetailScaffold` breadcrumb (:223) only.

**Tests first.** A small pure resolver (`menuGlyphKey`) test; existing `PspContextMenu` previews unaffected.

**Do not change.** `PfpCheckMark` itself (used by checkboxes/badges); SettingsScaffold/DetailComponents/ArtworkStudio `◀` (follow-up).

**Expected files.** Modify `PspContextMenu.kt`, `XmbTouchButton.kt`, `DetailScaffold.kt`; add a test.

**Acceptance criteria.**
- [ ] **Device:** themed check in context menus; themed back arrow on touch back + detail headers.

**Change budget.** 3 modified, 1 test.

**Verification.** `./gradlew :core:core-ui:testDebugUnitTest` then device.

**Stop condition.** Stop after device check. Gate G3.

**If blocked.** STOP and report (four points).

---

### TS-20 — Studio lossless hydrate/export + v4 state

**Parent / Phase:** Studio — Phase 4 · **Depends On:** TS-04, TS-06, TS-07 · **Status:** DONE

**Objective.** Fix bugs (c) and (d); the Studio round-trips every bundle losslessly.

**Scope.** `StudioState` += sysicon overrides (same shape as icons), `manifestExtras`, `passthrough`, media scratch
files, `author`, `description`, `created`, legibility, exact wave, `textColorExact`, `motionCrop`, `schemaVersion`
of the opened file, `upgradeReport`. `hydrate()` (:258-326) fills all of them; `buildManifest()` (:561-578)
preserves `created`, sets `updated`, writes v4 per §5.1; `exportTo()` writes sysicons, media, passthrough.
`setIconOverride` accepts any `CustomizableIcons` key (console → sysicons). Scratch lifecycle extended to media.

**Tests first.** Open each golden fixture → export → re-read: equal modulo `updated`/`schemaVersion`; v3 with
sysicons keeps them (bug c); `created` preserved (bug d); `future()` keeps extras and passthrough.

**Do not change.** UI files; PTF conversion.

**Expected files.** Modify `StudioViewModel.kt`; add `ViewModelLosslessRoundTripTest.kt`; extend `RoundTripTest.kt`.

**Acceptance criteria.**
- [ ] Lossless for all fixtures; bugs c, d covered by named tests.

**Change budget.** 1 modified (large), 1–2 tests.

**Verification.** `./gradlew :studio:test`

**Stop condition.** Stop at model.

**If blocked.** STOP and report (four points).

---

### TS-21 — Undo/Redo owning scratch-file lifetime

**Parent / Phase:** Phase 4 · **Depends On:** TS-20 · **Status:** DONE

**Objective.** Every user edit is undoable without ever pointing state at a deleted temp file.

**Scope.** Snapshot history (bounded, e.g. 100) of `StudioState` edits; coalescing for continuous edits (slider
drags); `undo()`/`redo()`; `canUndo/canRedo`; scratch files reference-counted across live state + history, deleted
when unreachable or on New/close. `discardMotion` callers routed through the owner.

**Tests first.** Clear motion → undo → file still exists and exports; history drop deletes unreachable files;
redo after new edit is cleared; slider coalescing.

**Do not change.** Export logic.

**Expected files.** Modify `StudioViewModel.kt`; add `EditHistory.kt`; add `EditHistoryTest.kt`.

**Acceptance criteria.** [ ] No undo path yields a missing file; no leak after New.

**Change budget.** 1 modified, 1 new, 1 test.

**Verification.** `./gradlew :studio:test --tests "*EditHistory*"`

**Stop condition.** Stop at model.

**If blocked.** STOP and report (four points).

---

### TS-22 — Studio media import gates + Export-check model

**Parent / Phase:** Phase 4 · **Depends On:** TS-08, TS-20 · **Status:** DONE

**Objective.** The Studio rejects, by name, any sound or clip the launcher would reject, and can describe the file it will write.

**Scope.** `importSound(slot, file)`, `importAmbience`, `importBoot`, `importGameBoot`: extension →
`UiMediaLimits.mimeForExtension`; duration via `MediaDurationProbe` (audio) or JCodec header probe (MP4 video, A10);
`UiMediaLimits.validate` + theme byte caps. `ExportCheck` model: per-kind bytes, total, warnings (> 64 MB pre-v4 cap,
> 256 MB hard), upgrade report, missing poster for motion.

**Tests first.** Each rejection message; WebM boot rejected with the A10 reason; budget math; warnings thresholds.

**Do not change.** Motion import (`VideoCodecs.accept`).

**Expected files.** Modify `StudioViewModel.kt`; add `io/MediaGates.kt`, `ExportCheck.kt`; add tests.

**Acceptance criteria.** [ ] Studio gate ≡ launcher gate for every slot.

**Change budget.** 1 modified, 2 new, 1–2 tests.

**Verification.** `./gradlew :studio:test`

**Stop condition.** Stop at model.

**If blocked.** STOP and report (four points).

---

### TS-23 — Crop engine

**Parent / Phase:** Phase 4 · **Depends On:** TS-20 · **Status:** DONE

**Objective.** One crop model drives image bake, poster bake and the video `motionCrop` (D3).

**Scope.** `CropFrame` pure model: fit ratio lock (PSP 480×272, HD, Full HD, Original), drag, corner-resize, zoom,
center, reset, clamped to source; `ImageCodecs` bake (crop + scale to fit); poster baked with the same rect;
normalized `motionCrop` output. Replaces the preset-only center crop in `confirmWallpaper` (:430-459).

**Tests first.** Ratio invariant under every operation; clamping; normalized rect ↔ pixel rect round-trip;
bake output size per fit.

**Do not change.** Accent derivation; busy-wallpaper metric.

**Expected files.** Add `CropFrame.kt` + test; modify `StudioViewModel.kt`, `io/ImageCodecs.kt`.

**Acceptance criteria.** [ ] Poster and `motionCrop` describe the same region.

**Change budget.** 2 modified, 1 new, 1 test.

**Verification.** `./gradlew :studio:test --tests "*CropFrame*"`

**Stop condition.** Stop at model.

**If blocked.** STOP and report (four points).

---

### TS-24 — Upgrade banner + Upgrade folder batch

**Parent / Phase:** Phase 4 · **Depends On:** TS-20 · **Status:** DONE

**Objective.** Opening an older theme explains itself; a folder of themes can be upgraded safely (D9).

**Scope.** State flag `upgradeAvailable` + report on open (newer-than-known schema → "made by a newer version"
notice instead). `UpgradeBatch.run(dir)`: each `.pfptheme` → `.bak` copy kept → upgraded written → summary
(upgraded / already current / failed with reasons). Mirrors `BatchConverter` progress shape.

**Tests first.** Temp-dir batch over the fixtures; `.bak` byte-identical to originals; a corrupt file reported and untouched; re-run is a no-op.

**Do not change.** PTF batch convert.

**Expected files.** Add `io/UpgradeBatch.kt` + test; modify `StudioViewModel.kt`.

**Acceptance criteria.** [ ] Originals always recoverable; report complete.

**Change budget.** 1 modified, 1 new, 1 test.

**Verification.** `./gradlew :studio:test --tests "*UpgradeBatch*"`

**Stop condition.** Stop at model.

**If blocked.** STOP and report (four points).

---

### TS-25 — Icon-pack import

**Parent / Phase:** Phase 4 · **Depends On:** TS-20 · **Status:** DONE

**Objective.** Drop a folder or zip of `<slotKey>.<png|gif>` files and fill matching slots (D7).

**Scope.** `IconPackImport.scan(folder|zip)`: match by exact slot key (case-insensitive file name) across
`CustomizableIcons.ALL`; zip via `BoundedZipReader` + safe names; each match through the existing
`setIconOverride` gate; report matched / replaced / unmatched (with nearest-key suggestion) / rejected (gate reason).

**Tests first.** Folder and zip fixtures; hostile zip names; unmatched report; GIF over caps rejected.

**Do not change.** Single-icon import path.

**Expected files.** Add `io/IconPackImport.kt` + test; modify `StudioViewModel.kt`.

**Acceptance criteria.** [ ] Every file accounted for in the report.

**Change budget.** 1 modified, 1 new, 1 test.

**Verification.** `./gradlew :studio:test --tests "*IconPackImport*"` — Gate G4.

**Stop condition.** Stop at model.

**If blocked.** STOP and report (four points).

---

### TS-26 — Spike: CMP 1.12 frame-animation stability — **STUDIO**

**Parent / Phase:** Studio — Phase 5 · **Depends On:** None · **Status:** DONE (GO) — automated headless spike, see §13

**Objective.** Prove (or disprove) that continuous frame animation is stable in the Studio window on CMP 1.12.0
before any preview work depends on it (D2).

**Scope.** A throwaway debug-only screen (behind a launch flag) exercising `rememberInfiniteTransition`,
`AnimatedContent` with the launcher's specs, spring `animateDpAsState`, M3 `CircularProgressIndicator` and
`TabRow`, plus a concurrent `PreviewRenderer.renderPreviewPng` call every few seconds. Run 10 minutes.
Write findings into this plan's §13.

**Tests first.** Exempt — a spike's deliverable is the finding. (Any helper it keeps must have tests.)

**Do not change.** Production UI; the 1.6.11 workaround comments stay until TS-27 acts on the finding.

**Expected files.** Add one debug composable + flag in `Main.kt`; remove both before closing the task.

**Acceptance criteria.**
- [ ] §13 records pass/fail per primitive, with any crash trace.
- [ ] Owner has run it. If any primitive crashes, TS-31…TS-33 move to BLOCKED and the owner decides.

**Change budget.** Temporary only; net zero files.

**Verification.** Owner runs `./gradlew :studio:run` with the flag.

**Stop condition.** Stop after recording the finding.

**If blocked.** STOP and report (four points).

---

### TS-27 — Shell restructure — **STUDIO**

**Parent / Phase:** Phase 5 · **Depends On:** TS-21, TS-26 · **Status:** DONE

**Objective.** The D1 layout, with existing behaviour re-homed and nothing lost.

**Scope.** Left rail (nine sections), center (existing preview + "In this file" strip from `ExportCheck`), right
inspector hosting today's `InspectorPanel`/`IconEditorPanel` content under the right sections; toolbar per D1
(Import PSP theme menu = existing convert/batch/unpack actions; Undo/Redo bound to TS-21, Ctrl+Z/Ctrl+Shift+Z);
neutral `darkColorScheme` chrome never fed the accent. Remove 1.6.11 workarounds only where TS-26 passed.

**Tests first.** ViewModel-level: section enum + selection state; toolbar actions call the same VM functions as today (unit test on a pure action table).

**Do not change.** Preview rendering (TS-31+); VM behaviour.

**Expected files.** Modify `ui/StudioApp.kt`, `ui/InspectorPanel.kt`; add `ui/SectionRail.kt`, `ui/FileContentsStrip.kt`; 1 test.

**Acceptance criteria.** [ ] Every pre-existing action reachable; chrome neutral with any accent. **STUDIO** check against the mockup.

**Change budget.** 2 modified, 2 new, 1 test.

**Verification.** `./gradlew :studio:test` then owner runs the Studio.

**Stop condition.** Stop after owner check.

**If blocked.** STOP and report (four points).

---

### TS-28 — Panels A: Info, Color, Legibility, Layout, Export check — **STUDIO**

**Parent / Phase:** Phase 5 · **Depends On:** TS-22, TS-24, TS-27 · **Status:** DONE

**Objective.** The non-media sections, editing the v4 fields.

**Scope.** Info (name, author, description, created read-only, updated read-only, upgrade banner); Color (accent,
icon colour, text colour + "Use my exact color"); Legibility (text 5, icon 5, solid unfocused); Layout ("Preview
with my layout": scale 0.6–1.8 step .02, H −25..35 % step 1, V 5..45 % step 1, Biblically Accurate preview via
`XmbLayoutPreset`, persisted per install with `java.util.prefs`, **never exported**; Advanced: Detect + 11
`XmbLayoutSpec` fields); Export check (report + budget + warnings). ⓘ tooltips with the device paths in D5.

**Tests first.** Studio-local layout prefs never appear in `buildManifest`; field setters clamp like the codec.

**Do not change.** Preview engine.

**Expected files.** Add `ui/sections/InfoSection.kt`, `ColorSection.kt`, `LegibilitySection.kt`, `LayoutSection.kt`, `ExportCheckSection.kt` (small files); modify `StudioViewModel.kt`; 1 test.

**Acceptance criteria.** [ ] Layout adjust provably preview-only. **STUDIO** check.

**Change budget.** 1 modified; 5 new small UI files (one per section — stated exception, the rail's unit of work); 1 test.

**Verification.** `./gradlew :studio:test` then owner.

**Stop condition.** Stop after owner check.

**If blocked.** STOP and report (four points).

---

### TS-29 — Panels B: Background, Sounds, Boot & GameBoot — **STUDIO**

**Parent / Phase:** Phase 5 · **Depends On:** TS-22, TS-23, TS-27 · **Status:** DONE

**Objective.** Media sections over the TS-22/TS-23 models.

**Scope.** Background: source Wave/Image/Video, Fit chips, interactive crop frame (drag, corner-resize, zoom,
center, reset) over the source, motion control 4-way; replaces `WallpaperImportDialog`. Sounds: five rows + ambience,
pick/replace/clear, WAV preview else "plays on device" (A10). Boot & GameBoot: pick/replace/clear MP4, ⓘ to Show Boot
Sequence / GameBoot device settings.

**Tests first.** Crop-frame gesture → `CropFrame` op mapping (pure); motion-control → exact wave value mapping.

**Do not change.** Gates (TS-22); crop math (TS-23).

**Expected files.** Add `ui/sections/BackgroundSection.kt`, `CropFrameView.kt`, `SoundsSection.kt`, `BootSection.kt`; remove `WallpaperImportDialog.kt` usage; 1 test.

**Acceptance criteria.** [ ] All D3/D6 controls present. **STUDIO** check.

**Change budget.** 4 new UI files, 1 modified, 1 test (stated: one file per section).

**Verification.** `./gradlew :studio:test` then owner.

**Stop condition.** Stop after owner check.

**If blocked.** STOP and report (four points).

---

### TS-30 — Icons section picker — **STUDIO**

**Parent / Phase:** Phase 5 · **Depends On:** TS-25, TS-27 · **Status:** DONE

**Objective.** Author 128 slots efficiently (D7).

**Scope.** Search; group chips with counts (Studio presentation groups incl. "Shiba Coins (7)", A3); filters On screen
(keys the preview currently shows — exposed by TS-31's nav state; until then, Home keys), Customized, New (keys added
in v4); drag-drop onto cells; pack import UI over TS-25 with report; slot card (real sizes on wallpaper, GIF frames,
template size, Replace/Reset/Export template); template export covers all 128.

**Tests first.** Filter/search pure function over `CustomizableIcons.ALL`; "New" = keys absent from `V3EraReader` list.

**Do not change.** Icon gates.

**Expected files.** Rewrite `ui/IconEditorPanel.kt`; add `ui/SlotCard.kt`, `IconFilters.kt`; modify `StudioViewModel.exportIconTemplates`; 1 test.

**Acceptance criteria.** [ ] Every slot reachable within two interactions. **STUDIO** check.

**Change budget.** 2 modified, 2 new, 1 test.

**Verification.** `./gradlew :studio:test` then owner.

**Stop condition.** Stop after owner check.

**If blocked.** STOP and report (four points).

---

### TS-31 — Preview I: geometry + navigation + core motion — **STUDIO**

**Parent / Phase:** Phase 5 · **Depends On:** TS-26, TS-27 · **Status:** DONE

**Objective.** A preview the owner cannot tell apart from the handheld's Home/drill behaviour (D2, §7.5).

**Scope.** 832×468 dp base (replace the 960 dp design width in XmbPreviewCanvas/PreviewRenderer); `PreviewNav` pure
reducer (category, row, drill stack) driven by ←→↑↓ Enter Esc; sample content per category reusing `SampleContent`
extended; category bar spring, AnimatedContent category switch, instant row position, spring row scale/alpha,
instant drill-in with truncated bar and sibling column. `preview.png` still renders the static Home frame.

**Tests first.** `PreviewNav` reducer transitions; geometry constants pinned against the launcher values cited in §7.5.

**Do not change.** Flyout, filter, motion (TS-32/33).

**Expected files.** Rewrite `preview/XmbPreviewCanvas.kt`; add `preview/PreviewNav.kt`; modify `preview/PreviewModel.kt`, `PreviewRenderer.kt`; 1 test.

**Acceptance criteria.** [ ] Side-by-side with the device, owner-approved. **STUDIO** check.

**Change budget.** 3 modified, 1 new, 1 test.

**Verification.** `./gradlew :studio:test --tests "*PreviewNav*"` then owner.

**Stop condition.** Stop after owner check.

**If blocked.** STOP and report (four points).

---

### TS-32 — Preview II: flyout, Games filter, PIC0, legibility, adjust overlay — **STUDIO**

**Parent / Phase:** Phase 5 · **Depends On:** TS-31 · **Status:** DONE

**Objective.** The remaining interactive surfaces.

**Scope.** Tab/right-click options flyout replicating `PspContextMenuOverlay` (300 dp, waveColor@75 %, swap-in-place
submenus, checks, values, destructive red); X opens the Games filter (Search top-right, Sort group — mirror of
`gamesFilterRows`, XMBViewModel.kt:1167); PIC0 logo fade (650 ms delay / 500 ms); text/icon legibility rendering;
Adjust-on-preview overlay replicating `XmbLayoutAdjustOverlay` driven by TS-28's layout state.

**Tests first.** Reducer extensions for flyout/filter states; legibility style → render params mapping.

**Do not change.** Launcher code.

**Expected files.** Add `preview/PreviewFlyout.kt`, `preview/PreviewFilter.kt`; modify `XmbPreviewCanvas.kt`, `PreviewNav.kt`; 1 test.

**Acceptance criteria.** [ ] Owner-approved against device. **STUDIO** check.

**Change budget.** 2 modified, 2 new, 1 test.

**Verification.** `./gradlew :studio:test` then owner.

**Stop condition.** Stop after owner check.

**If blocked.** STOP and report (four points).

---

### TS-33 — Preview III: live motion — **STUDIO**

**Parent / Phase:** Phase 5 · **Depends On:** TS-32 · **Status:** DONE

**Objective.** The preview moves like the device.

**Scope.** Wave in all four modes (Reduced: speed .5 amp .65 alpha .5; Static and Reduced+Static frozen at t=2);
GIF icons animate on focus via Skia `Codec`; motion wallpaper loop via JCodec frame decode at preview resolution,
bounded ring buffer, off the UI thread, crop applied, poster fallback with a note when fps falls below threshold;
boot and GameBoot playable (video frames; audio per A10).

**Tests first.** Frame scheduler (pure): fallback triggers below threshold; frozen modes request no frames; decode never on the UI dispatcher.

**Do not change.** Export path (`preview.png` stays static).

**Expected files.** Add `preview/MotionPlayer.kt`, `preview/GifFrames.kt`; modify `XmbPreviewCanvas.kt`, `io/VideoCodecs.kt` (frame iterator); 1 test.

**Acceptance criteria.** [ ] Stable for 10 minutes with a 1080p motion theme; fallback visible when forced. **STUDIO** check.

**Change budget.** 2 modified, 2 new, 1 test.

**Verification.** `./gradlew :studio:test` then owner.

**Stop condition.** Stop after owner check. Feature complete.

**If blocked.** STOP and report (four points).

---

## 13. Spike findings (TS-26)

**Verdict: GO.** TS-31..33 may use frame animations. Caveat: owner should still eyeball the running
app once (`./gradlew :studio:run`) — the spike was automated and headless, not a 10-minute windowed run.

**Method (deviation from the TS-26 block, owner asleep).** Instead of a debug screen, a JUnit test:
`studio/src/test/.../preview/FrameAnimationStabilityTest.kt` (~6 s). An `ImageComposeScene` is created
and rendered on the AWT thread, one `invokeAndWait` per frame (16.67 ms synthetic frame time) for
**2400 frames (~40 s of animation time)**, with state mutated every 7/17/23/31 frames to force repeated
recomposition. In parallel a second thread issues **12 `PreviewRenderer.renderPreviewPng` calls**, which
hop onto the AWT thread and interleave between frames (the real threading shape).

| Primitive | Result |
|---|---|
| `rememberInfiniteTransition` (2 floats, Reverse + Restart) | pass |
| `animateDpAsState` spring (bouncy), `animateFloatAsState` tween, retargeted every 7 frames | pass |
| `AnimatedContent` (slide + fade enter/exit), retargeted every 23 frames | pass |
| M3 1.9.0 indeterminate `CircularProgressIndicator` | pass |
| M3 `PrimaryTabRow` (animated indicator), tab changing every 31 frames | pass |
| M3 `SingleChoiceSegmentedButtonRow` / `SegmentedButton`, selection changing every 17 frames | pass |
| Concurrent offscreen `PreviewRenderer.renderPreviewPng` (12 renders, valid PNGs) | pass |

No exception, no NodeChain/SlotTable corruption, no crash signature observed.

**Limits.** Headless software-rendered scene: no real window, GPU/Skiko window surface, input events,
or vsync-driven frame clock; the 1.6.11 crashes may have needed those. Note `TabRow` is deprecated in
M3 1.9.0 (use `PrimaryTabRow`/`SecondaryTabRow`). The 1.6.11 workaround comments stay until TS-27 acts.
