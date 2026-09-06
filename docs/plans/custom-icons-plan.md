# Custom XMB Icons (static + animated GIF) with Save-as-Theme

Source: project owner request, 2026-09-06. Effort: L (a week or more) — ships in two phases.
Branch base: `more-customization`.

This is an implementation handoff. It is written to be executed without the conversation that produced it. Every line reference was verified against the working tree on 2026-09-06. Add a row to `docs/plans/README.md` on completion.

---

## Context

Today the only way to change an XMB icon is to author a whole `.pfptheme` in the desktop Theme Studio and apply it. There is no per-icon customization in the app, no icon can move, and there is no way to turn the look you've built on your device back into a shareable theme.

The owner wants three things: replace individual icons from a live fullscreen editor over the XMB, allow animated (GIF) icons, and **export the finished result as a theme**.

That third requirement is what sets the shape of the work. Export must be the exact inverse of `PfpThemeStore.apply()` — and `apply()` currently cannot represent two of the things being added:

| `apply()` today (`PfpThemeStore.kt:93`) | Blocks |
|---|---|
| Writes `theme-icons/<key>.png` only | Animated icons |
| Keys gated by `IconSlots.isValidKey` | Console (`sysicon_*`) icons |
| `prefs.remove(KEY_MOTION_WALLPAPER)` — *"bundles carry no motion wallpaper"* (`:144`) | Motion wallpaper in an exported look |

So `.pfptheme` moves to **schema v3**, additively. `PfpThemeCodec.read` already ignores unknown zip entries and `Json { ignoreUnknownKeys = true }` ignores unknown manifest fields, so a v3 bundle still opens in older builds and in the desktop Theme Studio — they just get the v2 subset. That forward-compatibility is what makes this a safe bump rather than a break.

**Almost everything else already exists and should be reused, not rebuilt:**

| Need | Already in the tree |
|---|---|
| Slot registry, forever-stable keys | `core/theme-kit/.../IconSlots.kt` — 55 slots, `isValidKey()` |
| Override lookup at render time | `LocalXmbIconOverrides` + `ThemedGlyph` (`core-ui/.../icons/XmbIconOverrides.kt`) |
| Override rendering incl. legibility matte | `OverrideGlyphSurface` / `IconMatteSurface` |
| SAF pick → copy to `filesDir` → path in DataStore | `DisplaySettingsViewModel.importStillWallpaper` (`:229`) / `importMotionWallpaper` (`:263`) |
| Bounded decode of untrusted images | `core-data/.../SafeMedia.kt` |
| Animated-media limits + `validate` shape | `core-ui/.../motion/MotionWallpaperLimits.kt` |
| GIF decoding | Coil 3.6.1 + `coil-gif`; `AnimatedImageDecoder` registered in `ArtworkModule`, installed as Coil's singleton by `ArtworkImageCache.installAsSingleton()` |
| Live fullscreen editor over the real XMB | `XmbLayoutAdjustOverlay.kt` + `XmbLayoutAdjustSession` — the exact shape to copy |
| Bounded zip read/write, theme library, share | `PfpThemeCodec`, `PfpThemeStore` (`save`, `importBundle`, `exportForShare`) |

---

## Decisions already made

Settled with the project owner. If one turns out to be impractical, stop and report rather than substituting your own.

1. **Scope: the 55 theme slots *plus* console icons** (`sysicon_*`).
2. **UI: a live fullscreen editor over the real XMB**, launched from a Display settings row — mirroring "Adjust XMB Layout".
3. **Animation: only the focused/selected icon animates.** Everything else shows frame 1. One decoder at a time.
4. **Precedence: user picks win and survive theme switches.** `user pick > theme icon > built-in`. Applying a `.pfptheme` never deletes a user pick; a per-slot Reset falls back to the theme icon, not the built-in.
5. **Export bumps the bundle to schema v3** so GIFs and console icons round-trip rather than being silently dropped.
6. **Export captures the whole current look** — icons, wallpaper (still or motion), accent, icon color, wave style, layout spec.

### On "does export override the theme's icon?"

Yes — **in the exported artifact only, never in place.** Export flattens the two render tiers exactly as they draw: per slot, the user's pick if there is one, else the applied theme's icon, else nothing (built-in). The applied theme's own saved bundle is untouched, so decision 4 still holds.

The reverse also stays coherent: applying your own exported theme shows your picks (they're the same art), and if you later Reset All, the theme's copy is still there underneath.

## Accepted file types

| Kind | MIME accepted | Behavior |
|---|---|---|
| Static | `image/png`, `image/jpeg`, `image/webp`, `image/bmp`, `image/heif` | Decoded once to an `ImageBitmap`; drawn as-authored (untinted), matte-eligible |
| Animated | `image/gif` | First frame decoded eagerly for the unfocused state; full GIF played via Coil only while focused |

A single-frame GIF is stored and treated as static — no decoder is ever started for it.

### The trap

`LocalXmbIconOverrides` is `Map<String, ImageBitmap>` and it is tempting to leave the theme tier alone and widen only a new user tier. **Do not.** Once v3 lands, a *theme* can also carry a GIF and a console icon, so both tiers need the same value type. Keep them as two separate composition locals (precedence, and the `theme-icons/` wipe on apply are different concerns) but give both the `CustomIcon` type.

---

# Phase 1 — Custom icons and the editor

Independently shippable. Nothing here touches the bundle format.

## Architecture

### `core/core-ui/.../icons/CustomIcon.kt` (new)

```kotlin
sealed interface CustomIcon {
    val firstFrame: ImageBitmap
    data class Still(override val firstFrame: ImageBitmap) : CustomIcon
    data class Animated(val path: String, override val firstFrame: ImageBitmap) : CustomIcon
}

val LocalCustomIcons = staticCompositionLocalOf<Map<String, CustomIcon>> { emptyMap() }

/** True only for the row/column currently focused — gates GIF playback. See decision 3. */
val LocalIconAnimating = staticCompositionLocalOf { false }
```

`firstFrame` on both arms is what makes the unfocused case free: an unfocused `Animated` icon draws the same single-bitmap path a `Still` does.

Retype `LocalXmbIconOverrides` in `XmbIconOverrides.kt` to `Map<String, CustomIcon>` in this phase (Phase 1 only ever puts `Still` in it) so Phase 2 is a pure data change.

### `core/theme-kit/.../CustomizableIcons.kt` (new)

`IconSlots.ALL` is the bundle contract and its KDoc states console art is deliberately not a slot. **Do not reorder or rename anything in `IconSlots.ALL`** — keys are zip entry names. Add a superset registry:

```kotlin
object CustomizableIcons {
    val ALL: List<IconSlot> = IconSlots.ALL + SYSICON_PLATFORM_IDS.map { id ->
        IconSlot("sysicon_$id", IconSlot.Group.CONSOLE, consoleDisplayName(id), 256)
    }
    fun isValidKey(key: String): Boolean = key in byKey
}
```

Needs `CONSOLE` added to `IconSlot.Group`, and `SYSICON_PLATFORM_IDS: List<String>` beside the `when` in `core-ui/.../icons/SystemIcons.kt`. Keep that `when` static — its KDoc explains it is what stops R8 stripping the drawables — and keep the list in lockstep with a unit test.

`isValidKey` is load-bearing: slot keys are used verbatim as filenames, and it is what stops a crafted key escaping the directory.

### `core/core-data/.../repository/CustomIconStore.kt` (new)

The directory is the source of truth, matching how `PfpThemeStore` handles `theme-icons/`:

```
filesDir/custom-icons/<slotKey>.<png|jpg|webp|gif>
```

A `@Singleton` mirroring `PfpThemeStore`'s shape:

- `suspend fun import(slotKey: String, uri: Uri): ImportResult` — reject unknown keys and unaccepted MIME up front; size pre-check via `openAssetFileDescriptor`; copy with `contentResolver.openInputStream(uri).use { it.readCapped(MAX) }`; validate via `SafeMedia.decodeFileCapped` (still) or `CustomIconLimits.validate` (GIF); delete any existing file for that slot with a different extension; bump the stamp.
- `suspend fun clear(slotKey: String)` / `clearAll()` — prefs and stamp first, then delete the files (the ordering `DisplaySettingsViewModel.clearWallpaper` uses, so nothing references a file being removed).
- `suspend fun load(): Map<String, CustomIcon>` — `listFiles()` scan on `Dispatchers.IO`, skipping invalid keys and extensions; direct copy of `XMBViewModel.loadThemeIconOverrides` (`:~1490`).
- `val KEY_CUSTOM_ICONS_STAMP = longPreferencesKey("custom_icons_stamp")` — bumps to tell observers to reload; copies `PfpThemeStore.KEY_THEME_ICONS_STAMP`.

**Icons are stored under a slot-keyed name, not a unique one.** That deliberately differs from the wallpaper's unique-filename rule (which exists because Coil's path-keyed cache served stale bytes). Stills are decoded here, not by Coil, so no cache is involved — **but animated GIFs are read by Coil**, so `import` must call `ArtworkImageCache.evict(file.absolutePath)` after replacing a GIF. Skip that and the old animation keeps playing. It is the single easiest bug to ship in this feature.

### `core/core-ui/.../icons/CustomIconLimits.kt` (new)

Mirror `MotionWallpaperLimits` — same `Probe` / `validate(probe): String?` returning a user-facing message.

| Constant | Value | Why |
|---|---|---|
| `MAX_BYTES` | `8 MB` | An icon, not a wallpaper |
| `MAX_DIMENSION` | `512` | Largest template is 256px; 512 gives headroom |
| `MAX_FRAMES` | `120` | Bounds decode for a glyph in an 82dp box |
| `MAX_DURATION_MS` | `10_000` | A looping accent, not a video |

Oversized **stills** are downscaled by `decodeFileCapped(targetDimension = 512)`, not rejected. Oversized **GIFs** are rejected with a message — re-encoding a GIF is out of scope.

### Rendering

Three call sites funnel everything; each checks the user tier, then the theme tier, then falls through to today's code.

**1. `ThemedGlyph` (`XmbIconOverrides.kt`)** — covers ~40 call sites unchanged:

```kotlin
val icon = LocalCustomIcons.current[slotKey] ?: LocalXmbIconOverrides.current[slotKey]
if (icon != null) return CustomIconSurface(icon, contentDescription, modifier)
Icon(defaultVector, contentDescription, tint = tint, modifier = modifier)
```

**2. New `ConsoleIcon(platformId, contentDescription, modifier)`** in `core-ui/.../icons/` — same two-tier check on `"sysicon_$platformId"`, else today's `PortalIcon(painterResource(systemIconRes(platformId)), …)`. Replace the `PortalIcon(painterResource(systemIconRes(...)))` call sites in `XMBItemList.kt` (`:321` in `SiblingIcon`, plus the memory-card and settings branches of `XmbItemLeadingIcon`) with it.

**3. `CategoryIconGlyph`** — same pattern via `catbarSlotKeyFor(iconKey)`; console-art categories route through `ConsoleIcon`.

**`CustomIconSurface` (new)** is the one draw node:

```kotlin
@Composable
fun CustomIconSurface(icon: CustomIcon, contentDescription: String?, modifier: Modifier = Modifier) {
    if (icon is CustomIcon.Animated && LocalIconAnimating.current) {
        AsyncImage(model = icon.path, contentDescription = contentDescription,
                   contentScale = ContentScale.Fit, modifier = modifier)
    } else {
        OverrideGlyphSurface(icon.firstFrame, contentDescription, modifier)  // matte and all
    }
}
```

Note the deliberate asymmetry: **the legibility matte applies to the still frame but not to a playing animation.** Deriving a matte per GIF frame means re-running the alpha-offset pass every frame inside a `LazyColumn`, and the focused icon is the one least in need of legibility help. Say so in the KDoc so it doesn't read as an oversight.

### Gating animation to the focused item

Do **not** thread an `animated: Boolean` through ~40 `ThemedGlyph` calls. Provide the local once per container, where selection is already known:

| File | Where |
|---|---|
| `XMBCategoryBar.kt` | `XMBCategoryItem` has `isSelected` — wrap its icon `Box` |
| `XMBItemList.kt` | `XmbItemLeadingIcon(item, iconStyle, isSelected)` (`:756`) and `SiblingIcon(item, selected, …)` (`:283`) — wrap their bodies |

```kotlin
CompositionLocalProvider(LocalIconAnimating provides (isSelected && animationAllowed)) { … }
```

`animationAllowed` comes from `XMBUiState`: false when battery-saver mode is on (`display_battery_saver`, already read) and while any blocking overlay covers the XMB — reuse `XMBUiState.hasBlockingOverlay` rather than inventing a second condition.

## The editor: "Customize XMB Icons"

Copy the Adjust XMB Layout plumbing end to end — closest precedent, and the shape the owner picked.

| Step | File | What |
|---|---|---|
| 1 | `DisplaySettingsScreen.kt` | `SettingsRow("Customize XMB Icons", sublabel = "Replace any icon with your own image or GIF", onClick = onOpenCustomIcons)` in the Scale & Layout group beside `Adjust XMB Layout` (`:167`); new `onOpenCustomIcons: () -> Unit = {}` param |
| 2 | `SettingsNavHost.kt` | Thread the callback (`:58`, `:128`) exactly as `onOpenXmbLayoutAdjust` |
| 3 | `XMBShell.kt` | `onOpenCustomIcons = viewModel::openCustomIcons` (`:188`); pass into the settings screen (`:807`) |
| 4 | `XMBViewModel.kt` | `CustomIconSession` + `customIconSession: CustomIconSession?` on `XMBUiState`; `openCustomIcons()` / `closeCustomIcons()` / `onSlotFocused(key)` / `onIconPicked(key, uri)` / `onResetSlot(key)` / `onResetAll()` |
| 5 | `feature-xmb/.../ui/CustomIconsOverlay.kt` (new) | Editor chrome, rendered in the overlay block after the `LocalDensity` reset (`XMBShell.kt:791+`), beside `XmbLayoutAdjustOverlay` (`:919`) |
| 6 | `XMBViewModel.kt` | Gamepad routing in the overlay chain (`:4490`) — UP/DOWN slot, L/R group, SELECT pick, OPTIONS reset slot, BACK exit |

**Registration checklist — three lists must all learn about the new overlay**, or it renders over a live XMB that still answers the D-pad:

- `XMBUiState.hasBlockingOverlay` (`:757`)
- the foreground-suppression guard (`XMBShell.kt:525`)
- `waveCovered` (`XMBShell.kt:444`) — the editor is translucent, so it should keep the wave alive, same as layout adjust

### Overlay layout

Bottom-anchored panel over a light consuming scrim (`Color(0x22000000)`), real XMB visible above — identical to `XmbLayoutAdjustOverlay`:

- **Left:** group tabs from `IconSlot.Group` — Category Bar / Items / Status / Consoles.
- **Centre:** a horizontal strip of that group's slots, each showing `displayName` and its current icon rendered *through the real pipeline* (`CustomIconSurface` with `LocalIconAnimating provides true` for the focused one) — the preview animates and mattes exactly as the XMB will.
- **Right:** focused slot enlarged, plus its source: "Default" / "From theme" / the picked filename.
- **Bottom:** `ControllerPromptBar` — SELECT Pick · OPTIONS Reset · L/R Group · BACK Done — plus touch buttons Pick / Reset / Reset All / **Save as Theme…** (Phase 2) / Done.

SAF launcher lives in the overlay composable:

```kotlin
val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    uri?.let { onIconPicked(focusedSlotKey, it) }
}
picker.launch(arrayOf("image/png", "image/jpeg", "image/webp", "image/gif", "image/bmp", "image/heif"))
```

Edits apply **immediately** — the XMB behind updates as each pick lands, which is the whole point of a live editor. There is no Save/Cancel pair: unlike layout adjust there is no coherent draft to discard, and Reset / Reset All are the undo. State this in the overlay KDoc so a later reader doesn't "fix" it into a draft model.

---

# Phase 2 — Schema v3 and Save as Theme

## Bundle format v3

`core/theme-kit/.../PfpThemeCodec.kt` — additive only.

```
mytheme.pfptheme
├── manifest.json              (required; schemaVersion 3)
├── wallpaper.png              (optional — still wallpaper / motion poster)
├── motion.<mp4|webm|gif>      (v3, optional — motion wallpaper)
├── preview.png                (optional)
├── icons/<key>.<png|gif>      (v3 widens .png to .gif; keys per IconSlots)
└── sysicons/<platformId>.<png|gif>   (v3, optional — console art)
```

Changes:

- `ICONS_SUFFIX` becomes a small accepted-extension set; the read branch keeps `IconSlots.isValidKey` on the key part.
- New `sysicons/` branch gated on `CustomizableIcons` console keys — **not** added to `IconSlots.ALL`, so the desktop Theme Studio's slot list is unaffected until it opts in.
- New `motion.*` branch.
- `PfpThemeBundle.icons` becomes `Map<String, ThemeImage>` where `ThemeImage(bytes, extension)`; add `sysicons` and `motion` fields. `write` still sorts keys for byte-deterministic output.
- Raise `BUNDLE_LIMITS`: `maxEntries` 128 → **256** (98 icons + sysicons + manifest + wallpaper + motion + preview is uncomfortably close to 128), `maxEntryBytes` 32 MB → **64 MB** (a motion wallpaper is capped at 60 MB by `MotionWallpaperLimits.MAX_BYTES`), `maxTotalBytes` 128 MB → **256 MB**. `MAX_ICON_BYTES` 4 MB → **8 MB**, matching `CustomIconLimits.MAX_BYTES`.
- `PfpThemeManifest`: bump `schemaVersion` to 3. No new fields needed — accent, icon color, wave style and `layout` already exist.

Read stays tolerant: unknown entries ignored, unknown manifest keys ignored, a bundle that trips a limit is "not a `.pfptheme`" rather than a crash.

## `apply()` becomes v3-aware

`PfpThemeStore.apply()` (`:93`):

- Write `theme-icons/<key>.<ext>` preserving the entry's extension instead of forcing `.png`; write `sysicons/` entries into the same directory under their `sysicon_<id>` key. The wipe-first behavior stays.
- Write a `motion.*` entry into the wallpaper dir and **set** `KEY_MOTION_WALLPAPER` instead of the current unconditional `prefs.remove` (`:144`). The remove stays as the else branch — a theme without motion must still clear a previous theme's video.
- The theme-tier loader in `XMBViewModel.loadThemeIconOverrides` gains the same extension handling as `CustomIconStore.load`, returning `Map<String, CustomIcon>`.

Update the `:142-144` comment — "bundles carry no motion wallpaper — that's out of scope for the bundle format" becomes false the moment this lands.

## `saveCurrentLook`

New `suspend fun saveCurrentLook(name: String): SavedTheme?` on `PfpThemeStore`, built on the existing private `save()`. It reads live state and writes one bundle:

| Bundle part | Source |
|---|---|
| `icons/` + `sysicons/` | Per slot in `CustomizableIcons.ALL`: `custom-icons/<key>.*` if present, else `theme-icons/<key>.*`, else omit. Non-PNG stills are re-encoded to PNG; GIFs copied verbatim |
| `wallpaper.png` / `motion.*` | `KEY_CUSTOM_WALLPAPER` / `KEY_MOTION_WALLPAPER` |
| `accentColor`, `iconColor`, `waveStyle` | `KEY_ACCENT_OVERRIDE`, `KEY_ICON_COLOR`, `KEY_WAVE_STYLE` |
| `layout` | `KEY_THEME_LAYOUT` |
| `preview.png` | Derived from the wallpaper, as `save()` does today (`:266`) |
| `source` | `PfpThemeSource(type = TYPE_USER_CREATED)` |

**`XmbLayoutAdjust` is deliberately excluded.** The user's scale/offset from Adjust XMB Layout is stored per screen bucket in `xmbLayoutAdjustMap` and is device-specific — shipping it to another device would misplace the crossbar. `XmbLayoutSpec` (`KEY_THEME_LAYOUT`) is the portable geometry and is the one that travels. Note this in the KDoc; it is the kind of thing that looks like a bug later.

Entry points, one implementation:

- **"Save as Theme…"** button in `CustomIconsOverlay` — where the owner asked for it, right after finishing.
- **"Save Current Look as Theme"** row in `ThemesSettingsScreen.kt`, beside "Import Theme (.pfptheme)" (`:303`), calling through `ThemesSettingsViewModel`.

Both open a name dialog — reuse the rename-dialog pattern already in `XMBShell.kt:934-995`. Sharing the result needs no new code: `PfpThemeStore.exportForShare(id)` already copies a saved bundle into the FileProvider cache for `ACTION_SEND`.

---

## Backup

`feature-backup/.../BackupManager.kt`:

- Add `"custom-icons"` to `BUNDLED_FILE_ROOTS` (`:455`). `RestoreArchive` confines entries to declared roots, so that one line covers both directions.
- Add `BACKED_UP_LONG_KEYS = listOf(longPreferencesKey("custom_icons_stamp"))` beside the string/boolean/float lists, read at `:392` and written at `:409`. Without it the files restore but nothing reloads them.

Out of scope but worth telling the owner: `theme-icons/`, `pfpthemes/`, `display_icon_legibility` and `display_solid_unfocused_icons` are all currently outside backup too. Do not fix them here.

---

## Files touched

| File | Change |
|---|---|
| `core/theme-kit/.../IconSlots.kt` | Add `CONSOLE` to `IconSlot.Group`. **Do not touch `ALL`.** |
| `core/theme-kit/.../CustomizableIcons.kt` | **new** — theme slots + console slots |
| `core/theme-kit/.../PfpThemeCodec.kt` | v3: gif icons, `sysicons/`, `motion.*`, raised limits |
| `core/theme-kit/.../PfpTheme.kt` | `PfpThemeBundle` gains `ThemeImage` typing, `sysicons`, `motion`; manifest `schemaVersion` 3 |
| `core/core-ui/.../icons/SystemIcons.kt` | Add `SYSICON_PLATFORM_IDS` beside the existing `when` |
| `core/core-ui/.../icons/CustomIcon.kt` | **new** — `CustomIcon`, `LocalCustomIcons`, `LocalIconAnimating` |
| `core/core-ui/.../icons/CustomIconSurface.kt` | **new** — the one draw node |
| `core/core-ui/.../icons/CustomIconLimits.kt` | **new** — mirrors `MotionWallpaperLimits` |
| `core/core-ui/.../icons/ConsoleIcon.kt` | **new** — override-aware console icon |
| `core/core-ui/.../icons/XmbIconOverrides.kt` | Two-tier lookup; `LocalXmbIconOverrides` retyped to `CustomIcon` |
| `core/core-ui/.../icons/CategoryIconGlyph.kt` | Same, via `catbarSlotKeyFor` / `ConsoleIcon` |
| `core/core-data/.../repository/CustomIconStore.kt` | **new** — import / clear / load / stamp |
| `core/core-data/.../repository/PfpThemeStore.kt` | v3-aware `apply()`; new `saveCurrentLook()` |
| `feature/feature-xmb/.../ui/CustomIconsOverlay.kt` | **new** — the editor |
| `feature/feature-xmb/.../ui/XMBShell.kt` | Wire overlay, callback, the three registration lists |
| `feature/feature-xmb/.../ui/XMBCategoryBar.kt` | Provide `LocalIconAnimating` |
| `feature/feature-xmb/.../ui/XMBItemList.kt` | Provide `LocalIconAnimating`; `PortalIcon(sysicon)` → `ConsoleIcon` |
| `feature/feature-xmb/.../viewmodel/XMBViewModel.kt` | Session state, pick/reset, two-tier load, gamepad routing |
| `feature/feature-settings/.../ui/DisplaySettingsScreen.kt` | New row + param |
| `feature/feature-settings/.../ui/SettingsNavHost.kt` | Thread the callback |
| `feature/feature-settings/.../ui/ThemesSettingsScreen.kt` + ViewModel | "Save Current Look as Theme" row |
| `feature/feature-backup/.../BackupManager.kt` | New bundled root + long-key list |

**Do not touch:** `PortalIcon.kt` (the built-in tint path is correct and covered by `IconMatteTest`), `IconMatte.kt` / `IconMatteSurface.kt`, the ordering or naming of `IconSlots.ALL` (keys are zip entry names — never rename, only add), `SettingsScaffold.kt`, `RestoreArchive.kt`.

---

## Tests

Pure JVM unit tests, no Robolectric — the repo convention.

- `CustomizableIconsTest` — keys unique; `IconSlots.ALL` fully contained; `isValidKey` rejects `..`, `/`, empty, unknown.
- `SystemIconsTest` — every id in `SYSICON_PLATFORM_IDS` maps to a drawable other than `sysicon_default`, keeping the list and the R8-safe `when` in lockstep.
- `CustomIconLimitsTest` — boundary cases per constant, mirroring the motion-wallpaper limits test.
- `CustomIconStoreTest` — import writes `<slot>.<ext>`; re-import with a different extension removes the old file; an invalid slot key writes nothing; `load()` skips unknown keys and extensions; `clear` removes the file.
- `PfpThemeCodecTest` (extend) — **v3 round-trip**: write a bundle with a gif icon, a sysicon and a motion entry, read it back identical. **v2 bundles still read unchanged.** A v3 bundle read by the v2 branch logic yields the v2 subset without error. Entry-count and per-entry-size limits still reject.
- `PfpThemeStoreTest` (extend) — `saveCurrentLook` flattens user-over-theme per slot; excludes `XmbLayoutAdjust`; `apply()` of a v3 bundle writes gif and sysicon files and sets `KEY_MOTION_WALLPAPER`, and a bundle without motion still clears it.
- `SettingsHierarchyTest` — unchanged: the editor is an overlay off a Display row, not a new settings route.

---

## Verification

Ask the project owner before running Gradle — builds are not run unprompted in this repo.

```bash
./gradlew :core:theme-kit:test :core:core-ui:testDebugUnitTest :core:core-data:testDebugUnitTest :feature:feature-xmb:testDebugUnitTest
```

On device, the owner drives navigation; request screenshots only when they say they are ready.

**Phase 1**

1. Settings → Interface → Display → **Customize XMB Icons** opens over the live XMB; the crossbar stays visible and the D-pad no longer moves it.
2. Pick a PNG for `catbar_games` → the Games column changes immediately behind the panel; BACK exits and it persists.
3. Pick a multi-frame GIF for `catbar_music` → plays **only** while Music is the selected column.
4. Pick a GIF for an item row → plays only on the focused row; scrolling starts/stops exactly one animation.
5. Consoles tab → replace `sysicon_snes` → the SNES memory card and its sibling chip both change.
6. Re-pick a *different* GIF for a slot already holding one → the new animation plays (the Coil eviction check).
7. Apply a `.pfptheme` with its own icons → user picks still win; per-slot Reset falls back to the theme icon, not the built-in.
8. Enable Battery Saver → animations stop, still frames remain.
9. Reject paths: a 4000×4000 GIF and a 30 MB file each show a message and change nothing.
10. Backup → wipe app data → restore → custom icons return, animated ones still animate.

**Phase 2**

11. **Round trip:** customize icons, wallpaper and accent → Save as Theme → Reset All picks + Reset Applied Theme → apply the saved theme → the XMB is visually identical, GIFs included.
12. Save with a motion wallpaper set → apply the result on a fresh profile → the video loops.
13. Share the saved theme via `exportForShare` → re-import the `.pfptheme` → identical.
14. Open a **v2** theme saved before this work → applies exactly as before.
15. Open a **v3** bundle on a build without these changes (or confirm by test) → opens, shows the v2 subset, does not error.

---

## Follow-up (not this plan)

- Capture a real rendered-XMB frame as `preview.png` at export — `PfpThemeStore.save()` (`:266`) already flags this as the intended Phase C behavior.
- Teach the desktop Theme Studio the v3 entries so it can author animated and console icons.
- Driving the XMB behind the editor to the category matching the focused catbar slot, so it previews truly in place.
- Per-game and per-collection icon overrides — a different keying problem (content, not UI).
- Adding `theme-icons/`, `pfpthemes/` and the icon-legibility prefs to backup.
