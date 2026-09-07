# Theme Studio — author motion wallpapers (video pass-through)

Source: project owner request, 2026-09-06. Effort: M (two to three days). Branch base: `more-customization`.

This is an implementation handoff. It is written to be executed without the conversation that produced it. Every line reference was verified against the working tree on 2026-09-06; re-check any that has drifted, but do not assume a helper exists that is not named here. Add a row to `docs/plans/README.md` on completion.

Sibling of `custom-icons-plan.md`, whose follow-up list already names *"teach the desktop Theme Studio the v3 entries"* — this plan does the motion half of that.

---

## Context

A `.pfptheme` has carried a motion wallpaper since schema v3: `PfpThemeCodec` writes `motion.<mp4|webm|gif>` (`:92`), `ThemeMotion` streams it without holding it in memory, and `PfpThemeStore.apply()` unpacks it to `KEY_MOTION_WALLPAPER` (`:134`). **There is no format work in this plan.** The desktop Theme Studio is simply the one place that cannot author it: `StudioState` has no motion field and `exportTo` (`:343`) never sets `bundle.motion`.

### The video is passed through, not converted

The request originally asked for video → GIF conversion "to save on performance". That is backwards, and the reasoning is worth keeping because the instinct is a common one:

| | MP4 | GIF |
|---|---|---|
| Decoder on device | ExoPlayer — **hardware** | Coil `MovieDrawable` — **CPU**, every frame held as a bitmap |
| Same 10 s 720p clip | ~2 MB | 20–60 MB |
| Routed by | `formatOf()` → `VIDEO` | `formatOf()` → `ANIMATED_IMAGE` |

Transcoding would spend real effort producing the worse artifact. GIF's one genuine advantage was that `javax.imageio` can write one with no dependency — and pass-through needs no writer at all, so that advantage evaporates.

**What pass-through costs, stated plainly:** the author's original container bytes ship inside the theme and are parsed on someone else's device by MediaCodec, a native decoder with a long CVE history. Re-encoding from pixels would have severed that. It is not a *new* hole — `DisplaySettingsViewModel` already accepts arbitrary user MP4s, and themes already carry attacker-controlled wallpaper and icon bytes — so this uses the existing exposure rather than widening it. It does mean the Studio's checks are load-bearing; see the invariants.

### Why JCodec is still a dependency

Only for the **poster**, and only ever for one frame. The launcher enforces *"motion is never set without its poster"*, and `PfpThemeStore.apply()` maps the bundle's **still wallpaper** onto that poster key — so a bundle with motion and no still is an invalid theme. When the author imports only a video, frame 1 becomes the still. Pure Java, so a hostile stream yields an exception rather than the native overflow a C decoder risks. `:studio` stays pure JVM and the jpackage installer stays small.

### Already landed on the branch

| Change | File |
|---|---|
| `MotionWallpaperLimits` → `MotionLimits`, moved into theme-kit so desktop and launcher share one set of numbers | `core/theme-kit/.../MotionLimits.kt` |
| Added `mimeForExtension` (`:85`) and `bundleExtensionFor` (`:99`) — the Studio has a filename, not a MIME | same |
| `core-ui` keeps only the render-side classifier | `core/core-ui/.../motion/MotionFormat.kt` |
| `core-ui` gains `api(project(":core:theme-kit"))` (was test-only) | `core/core-ui/build.gradle.kts` |
| Call sites repointed, no logic changed | `DisplaySettingsViewModel`, `PhotoViewerViewModel` |
| Import gate: `probe` (`:88`), `firstFrame` (`:103`), `accept` (`:116`) | `studio/.../io/VideoCodecs.kt` |
| `org.jcodec:jcodec` + `org.jcodec:jcodec-javase`, both `0.2.5` | `gradle/libs.versions.toml`, `studio/build.gradle.kts` |
| Test moved and passing as `MotionLimitsTest` | `core/theme-kit/src/test/.../MotionLimitsTest.kt` |

### Build state

| Target | Result |
|---|---|
| `:core:theme-kit:test` | ✅ 10 pass |
| `:core:core-ui:compileDebugKotlin` + unit tests | ✅ pass |
| `:feature:feature-settings:compileDebugKotlin` | ✅ pass |
| `:feature:feature-xmb:compileDebugKotlin` | ✅ pass |
| `:studio:compileKotlin` | ❌ **never ran** — see T1 |

---

## T1 — Compile `:studio` and fix the JCodec API surface

**Blocked by:** nothing. Do this first; T3–T7 all sit on top of it.
**Files:** `studio/build.gradle.kts`, `gradle/libs.versions.toml`, `studio/src/main/kotlin/com/playfieldportal/studio/io/VideoCodecs.kt`

Nothing in `VideoCodecs` has been type-checked. The machine that wrote it could not resolve JCodec — Gradle hit a TLS interception (`PKIX path building failed`) against both Maven Central and Google's mirror — so the module never compiled. The API names are written from memory. One artifact-split error was already caught by inspection (`AWTUtil` lives in `jcodec-javase`, not the core jar); assume there are more.

```bash
./gradlew :studio:compileKotlin
```

Suspect first: `FrameGrab.createFrameGrab`, `.videoTrack.meta`, `meta.videoCodecMeta.size`, `meta.totalDuration`, `grab.nativeFrame`, `AWTUtil.toBufferedImage`.

Keep the behaviour while fixing names: header-only probe, exactly one frame decoded, and only after every cheap check has passed. Do **not** resolve a compile error by widening what is accepted — `SUPPORTED_EXTENSIONS` stays `{mp4, m4v}` (`VideoCodecs:54`).

**Done when** `./gradlew :studio:compileKotlin` passes with no change to the accept/reject ladder in `accept()` (`:116`).

---

## T2 — Motion state and its scratch-file lifecycle

**Blocked by:** T1
**Files:** `studio/src/main/kotlin/com/playfieldportal/studio/StudioViewModel.kt`

1. Add to `StudioState` (`:64`): `motionFile: File?` and `motionFileName: String?`.
2. It is a `File`, **never** a `ByteArray` — see *Never put the video on the heap*.
3. On import, copy the pick to a scratch temp file and hold that. A source the author moves or deletes must not fail the export an hour later.
4. Delete the scratch file whenever it is replaced, cleared, or dropped. Each of `newTheme()` (`:109`), `hydrate()` (`:199`) and the new clear action resets `StudioState` wholesale, so every one is a leak site.

**Done when** importing twice, clearing, then New leaves no temp file behind.

---

## T3 — Import a video, and take its poster

**Blocked by:** T2
**Files:** `studio/src/main/kotlin/com/playfieldportal/studio/StudioViewModel.kt`

1. `importVideo(file)` calls `VideoCodecs.accept(file)` inside `runBusy`.
2. On `Rejected`, raise `StudioDialog.Error` with `message` **verbatim** — the strings are already written for the author.
3. On `Accepted`, push `.poster` through the existing `stage()` (`:253`) so the author picks a crop exactly like any still, and gets `AccentDeriver` and `WallpaperMetrics.isBusy` for free (both already in `confirmWallpaper`, `:268`).
4. **Set `motionFile` only when the crop is confirmed**, not at stage time. Cancelling the crop dialog would otherwise leave motion with no poster — the invalid state the whole poster rule exists to prevent.

**Done when** importing a video fills both the still wallpaper and the motion file, and cancelling the crop dialog leaves neither.

---

## T4 — Write the motion entry on export

**Blocked by:** T2
**Files:** `studio/src/main/kotlin/com/playfieldportal/studio/StudioViewModel.kt`

1. In `exportTo` (`:343`), set `bundle.motion = ThemeMotion.ofFile(motionFile, bundleExtension)`. `ofFile` (`PfpTheme.kt:97`) streams the entry straight into the zip.
2. Take the extension from `MotionLimits.bundleExtensionFor()` (`:99`), never from the temp file's own name.
3. Guard: never write motion when `wallpaperPng == null` (`:69`). Belt to T3's braces — this is the one place that can put an invalid bundle on disk.

**Done when** an exported bundle round-trips through `PfpThemeCodec.read` with its motion entry intact, and a wallpaper-less state exports without one.

---

## T5 — Carry motion through open → re-export

**Blocked by:** T2
**Files:** `studio/src/main/kotlin/com/playfieldportal/studio/StudioViewModel.kt`

Two defects, same function pair.

1. `hydrate()` (`:199`) drops `bundle.motion`. Opening a motion theme and saving it silently strips the video — the same bug class the existing icon comment in `hydrate` warns about. Spill the opened `ThemeMotion` to a scratch file so it lands in `motionFile` like any import.
2. `openPfpTheme` (`:185`) uses `PfpThemeCodec.read(bytes)` (`:195`) behind `SafeIo.readBytesCapped`, whose cap is 64 MB (`SafeIo:14`) while `MotionLimits.MAX_BYTES` alone is 60 MB (`:30`). **A legitimate motion theme is rejected today as "too large to be a theme bundle."** Switch to `PfpThemeCodec.read(file)` (`:205`), which also leaves the motion entry on disk instead of inflating it — the same reason `PfpThemeStore` uses it.

**Done when** a bundle with a 40 MB motion entry opens, and re-exporting it preserves that entry byte-for-byte.

---

## T6 — Inspector row and file picker

**Blocked by:** T3
**Files:** `studio/.../ui/InspectorPanel.kt`, `studio/.../ui/StudioApp.kt`

1. Add a `SectionLabel("Motion wallpaper")` block under the existing Wallpaper one (`InspectorPanel:129`), with Choose / Clear buttons matching that row's shape, and the filename or `"None — still wallpaper only"` beneath.
2. Wire `onChooseVideo` in `StudioApp` next to `onChooseWallpaper` (`:133`): `FileDialogs.openFile(window, "Import video", setOf("mp4", "m4v"))`.
3. Add a hint when motion is set: the still becomes the poster shown whenever playback is frozen — battery saver, a game launching, or a Static wave style. Authors will otherwise wonder why their video sometimes doesn't play.
4. **No frame-rate or size controls.** The video is passed through; there is nothing to configure.

**Done when** a video can be imported, seen, and cleared without the UI layer reaching past the ViewModel.

---

## T7 — Tests

**Blocked by:** T1
**Files:** `core/theme-kit/src/test/.../MotionLimitsTest.kt`, `studio/src/test/kotlin/com/playfieldportal/studio/`

1. Pin `mimeForExtension` and `bundleExtensionFor` against `PfpThemeCodec.MOTION_EXTENSIONS` (`:48`), so the mapping and the zip writer cannot drift into the silent-drop behaviour below.
2. Cover `accept()`'s rejection ladder: wrong extension, oversized, and bytes that merely claim to be an MP4. Hand-built fixture files are enough — the reject paths need no real video.
3. Round-trip an export with motion through `PfpThemeCodec`, asserting the entry name and that the bytes match the source.

**Done when** `:core:theme-kit:test` and `:studio:test` pass and every rejection message is covered.

---

## Invariants — don't let these regress

**Motion is never set without its poster.** `DisplaySettingsViewModel` enforces it on the launcher's own import, and `PfpThemeStore.apply()` maps a bundle's *still wallpaper* onto the poster key. A bundle with motion and no still fails safe at render time — `MotionWallpaperPolicy.decide` returns `POSTER` when `hasPoster` is false — so the symptom is a video that silently never plays. That is harder to debug, not easier.

**Never put the video on the heap.** `ThemeMotion.ofFile` streams into the zip; `PfpThemeCodec.read(File)` leaves the entry on disk. Both exist so a 60 MB video is never a `ByteArray`. Any change reaching for `read(bytes)` or storing motion as bytes gives that up.

**An unknown motion extension vanishes without an error.** `PfpThemeCodec.write` checks `ext in MOTION_EXTENSIONS` (`:92`) and skips the entry otherwise — `motion.m4v` would disappear from the zip silently. That is why `bundleExtensionFor` exists and why T7 pins it.

**The Studio is the only gate for a Studio-authored theme.** `PfpThemeStore.apply()` (`:134`) installs a bundle's motion entry **without** re-running `MotionLimits.validate()`. A pick that gets past the desktop is never checked again on the handheld. Closing that gap is queued separately (see Follow-up).

---

## Manual verification

1. Import a 10 s 720p MP4 → crop dialog appears showing frame 1 → confirm → both still and motion are set, accent shifts to match.
2. Cancel the crop dialog instead → neither still nor motion is set.
3. Import a 2-hour movie → rejected in well under a second, message names the duration cap.
4. Import a 4K clip → rejected, message names the resolution cap.
5. Import a `.webm` → rejected with the unsupported-format message, not a crash.
6. Rename a `.txt` to `.mp4` and import → rejected as undecodable.
7. Export with motion → unzip the `.pfptheme` → `motion.mp4` present and byte-identical to the source.
8. Clear the still wallpaper while motion is set → export → no motion entry in the zip.
9. Apply that theme on device → the video loops behind the XMB; the poster shows while it starts.
10. Set the wave style to Static on device → the video stops and the poster remains.
11. Open the exported theme back in the Studio → motion survives → re-export → still byte-identical.
12. Open a v2 theme with no motion → applies exactly as before.

---

## Follow-up (not this plan)

- **`PfpThemeStore.apply()` does not validate theme-delivered motion**, and sets `KEY_MOTION_WALLPAPER` even when the still wallpaper failed to extract. A crafted `.pfptheme` can therefore install an oversized looping video, and can leave motion set with no poster. Both fixes belong on the launcher side, next to `PfpThemeStoreV3Test`.
- WebM authoring, if a pure-JVM demuxer ever justifies it. The launcher already plays WebM; only the Studio's poster extraction and dimension probe are missing.
- Trimming a longer clip to a chosen window, rather than rejecting anything over 60 s.
- An animated preview of the motion wallpaper in the Studio's own canvas — it currently shows the poster still.
