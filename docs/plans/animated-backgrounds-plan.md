# Animated backgrounds — user-supplied motion wallpapers

Today the background is a two-way choice made in `XmbBackground`: a **wave** (AGSL shader on API 33+,
Canvas fallback below) or, when `customWallpaperPath` is set, a **still image** that replaces it
entirely. This plan adds a third state — a **motion wallpaper**: a user-picked MP4/WebM/GIF that
loops behind the XMB — and holds it to the exact power discipline the wave already obeys.

The governing rule, stated once so the rest of the plan can refer to it:

> **When the wave would be frozen, the motion wallpaper is not merely paused — no decoder exists.**

That is what "the same performance restraints as the wave" has to mean. `WaveStyle.STATIC` does not
pause a frame loop; it never starts one (`produceState` returns early, no
`withInfiniteAnimationFrameMillis`). The motion equivalent is: the composable renders a **poster
still** and the `ExoPlayer` is released, not held paused. A paused ExoPlayer still holds a codec
instance, a surface, and buffers — on the low-end handhelds this launcher targets that is precisely
the cost we are trying to avoid, and it would compete with the emulator the user is about to launch.

---

## 1. The freeze pipeline already exists — generalize it, do not duplicate it

`XMBShell.kt` (~line 440) already computes every input we need:

```kotlin
val waveCovered = uiState.showBootSequence || uiState.activeVideoId != null || … || uiState.musicPlayerVisible
val powerThrottled = rememberWavePowerThrottle(respectBatterySaver, thermalThrottleAware)
val effectiveWaveStyle = if (waveCovered || powerThrottled || uiState.customWallpaperPath != null) {
    uiState.waveStyle.frozen
} else uiState.waveStyle
```

Note the third clause: *"a wallpaper is set, so freeze the wave"*. That clause exists because the
wave is invisible under a wallpaper. It stops being the right shape once the wallpaper itself can
animate — the wallpaper needs the **first two** inputs (`waveCovered`, `powerThrottled`) applied to
*it*, and the wave needs to be off whenever a wallpaper of either kind is set.

**Change:** stop deriving one `WaveStyle` and start deriving one *motion budget* that both layers read.

```kotlin
// XMBShell
val motionAllowed = !waveCovered && !powerThrottled
XmbBackground(
    waveStyle           = if (motionAllowed) uiState.waveStyle else uiState.waveStyle.frozen,
    customWallpaperPath = uiState.customWallpaperPath,
    motionWallpaperPath = uiState.motionWallpaperPath,
    motionAllowed       = motionAllowed,
    modifier            = Modifier.fillMaxSize(),
)
```

`XmbBackground` keeps its "wallpaper replaces wave" contract and gains one branch:

| `motionWallpaperPath` | `customWallpaperPath` | `motionAllowed` | Renders |
|---|---|---|---|
| set | set (poster) | true | looping video/GIF |
| set | set (poster) | false | **poster still only — no player constructed** |
| null | set | — | still image (today's behavior, unchanged) |
| null | null | — | wave, honoring `waveStyle` (unchanged) |

The `waveStyle.frozen` call when a wallpaper is set is no longer needed as a separate clause: with a
wallpaper set the wave branch is not composed at all, so nothing allocates. Keeping `motionAllowed`
as the single boolean means there is **one** definition of "the device is busy or conserving" and
both the shader and the decoder obey it.

### Sanity check on existing callers

`BootSequenceOverlay.kt:66` calls `XmbBackground(waveStyle = ANIMATED)` with no wallpaper args. With
default parameters (`motionWallpaperPath = null`, `motionAllowed = true`) it behaves exactly as
today — boot never spins up a decoder. Worth an explicit comment there, because "boot plays the
user's video" is a tempting-but-wrong future edit; boot is the single moment the device is most
contended.

---

## 2. The poster frame is what makes this cheap

Every freeze path renders a still. Producing that still **at import time**, not at render time, is
the load-bearing decision:

* the frozen path becomes literally the existing `WallpaperBackground` composable — a Coil
  `AsyncImage` of a JPEG, which is already tuned and cached;
* nothing has to open the video container to show a frozen background, so battery-saver mode costs
  the same as a static wallpaper does today;
* a corrupt or unplayable motion file degrades to "your background is a still image" instead of a
  black screen.

**Store the poster in the existing `KEY_CUSTOM_WALLPAPER` pref.** This is the part worth being
deliberate about: three consumers read that key today (`DisplaySettingsViewModel`, `XMBViewModel`,
and `PfpThemeStore` writes it). If the poster lives there, *every one of them keeps working with no
change* and shows something correct — the Display preview screen, the theme cascade, everything.
Only `XmbBackground` learns that a second key exists.

New pref, alongside it in the same contract block:

```kotlin
private val KEY_MOTION_WALLPAPER = stringPreferencesKey("display_motion_wallpaper")
```

**Invariant: `KEY_MOTION_WALLPAPER` is never set without `KEY_CUSTOM_WALLPAPER`.** The poster is the
fallback for both freeze and failure, so a motion file without one is an unrenderable state. Enforce
it at the two write sites (import, and clear) and treat "motion set, poster missing" on read as
"no motion" rather than trying to recover.

Poster extraction at import, `MediaMetadataRetriever`:

* frame at ~1 s (`getFrameAtTime(1_000_000)`), falling back to frame 0 for very short clips — the
  first frame of a fade-in loop is often black;
* GIF/animated WebP: decode the first frame through `ImageDecoder` instead;
* write next to the motion file as `wallpaper_<stamp>.jpg`, so the existing "delete every file in
  the dir except the one just applied" cleanup keeps both members of the pair or neither.

---

## 3. Where the money is spent — import-time validation

The single largest risk in this feature is a user picking a 4K/60 200 MB clip and concluding the
launcher is broken. Playback discipline cannot rescue a file that should never have been accepted, so
the gate belongs at import.

Probe with `MediaMetadataRetriever` before copying anything, and reject with a specific message
(the wallpaper importer already has the `_wallpaperMessage` channel for exactly this):

| Check | Limit | Message |
|---|---|---|
| MIME | `video/mp4`, `video/webm`, `image/gif`, `image/webp` | "Unsupported format — use MP4, WebM, or GIF" |
| Resolution | ≤ 1920×1080 | "Video is too large — 1080p or smaller" |
| Duration | ≤ 60 s | "Clip is too long — 60 seconds or less" |
| File size | ≤ 60 MB | "File is too large — under 60 MB" |
| Frame rate | ≤ 30 fps advisory | accepted, but slowed under REDUCED (see §4) |
| Decodability | poster extraction must succeed | "Couldn't read that video — try a different file" |

Every one of those limits is a judgment call rather than a hardware fact, which is an argument for
putting the numbers in one `object MotionWallpaperLimits` and pointing the tests at it, so tuning
them later is a one-file change rather than an archaeology exercise.

**Phase 2, explicitly out of scope for the first pass:** transcoding oversized picks down to
1080p30 H.264 with `media3-transformer` (already a dependency in `feature-artwork`) instead of
rejecting them. That turns four of the six rejections into a progress bar and is a much nicer
experience — but it is a second feature with its own failure modes, and shipping the rejection path
first tells us whether anyone actually hits the limits.

---

## 4. Playback discipline

`Icon1VideoOverlay.kt` is the in-repo precedent and most of its rules transfer verbatim. Differences
are called out.

* **One player, one owner.** Constructed inside the motion branch of `XmbBackground`, keyed on the
  file path; there is exactly one background, so there is exactly one player.
* **Audio never decoded.** `setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)` plus `volume = 0f` —
  same two-belt approach as `Icon1VideoOverlay`. A wallpaper with audio would also fight the music
  player.
* **`REPEAT_MODE_ALL`.** This is the one place we diverge from Icon1, which deliberately does *not*
  loop. A background loops by definition — which is also why the 60 s duration cap matters more here
  than the 60 s *clip* cap did there.
* **Released, not paused, in `onDispose`.** With `motionAllowed` flipping the branch, leaving the
  branch destroys the player. This is the mechanism that implements the governing rule above.
* **Lifecycle.** `waveCovered` covers in-app overlays, but not the app being backgrounded — the
  composition survives `ON_STOP`. Add a `LifecycleEventObserver` (or `lifecycle.currentStateAsState()`)
  and fold `ON_START..ON_STOP` into `motionAllowed`. A launcher is backgrounded constantly, every
  time a game launches; missing this would leave a decoder running behind an emulator, which is the
  worst possible outcome for this feature.
* **`TextureView`, not `SurfaceView`.** Icon1 chose TextureView so its fade composites correctly.
  Here the reason is different but points the same way: the background sits underneath the whole
  Compose tree, and a `SurfaceView` behind the window requires a punched-through hole in an opaque
  window — fragile, and it breaks the crossfade in §5. TextureView costs an extra GPU copy per frame;
  measure it on a real device before considering the SurfaceView route as an optimization.
* **Slowdown under `REDUCED`.** `WaveStyle.REDUCED` halves the wave's speed and opacity. The motion
  analogue is `setPlaybackSpeed(0.5f)` plus the existing scrim raised slightly — not a real
  frame-rate cap (ExoPlayer has no such knob), but it halves perceived motion for the same "calmer"
  intent. The settings copy should not oversell REDUCED as a battery feature for video: it costs
  roughly what ANIMATED costs. STATIC is the setting that saves power.

### GIF and animated WebP

ExoPlayer plays neither. Rather than a second bespoke decoder, register Coil's animated-image
decoder (`coil-gif` artifact → `AnimatedImageDecoder` on API 28+; `minSdk` here is 29, so the
older `GifDecoder` fallback is unnecessary) and route GIF/animated-WebP through the *existing*
`AsyncImage` call with an `ImageRequest` that enables animation. The frozen path is unchanged — it is
the same poster JPEG either way.

> **Implementation correction (verified against Coil 3.6.1):** a GIF/WebP is NOT animated merely by
> being routed through `AsyncImage`. Coil decodes animated images honoring the file's OWN repeat
> metadata — a GIF whose loop flag is absent (or 0) renders its first frame and stops, i.e. an
> animated image with `repeatCount(1)` is visually a still. `MotionVideoSurface` must therefore
> build the request with an explicit `repeatCount(MovieDrawable.REPEAT_INFINITE)` while a play
> decision stands, and drop back to `repeatCount(1)` when the decision returns to POSTER — the
> CPU-decoder analogue of releasing the ExoPlayer. The repeat count is carried in the request's
> memory-cache-key extras, so the two counts are distinct decodes; the poster request (the same
> file, pinned to one frame) never animates behind the animated layer. See
> `MotionWallpaperBackground.kt`.

Requires one catalog addition:

```toml
coil-gif = { group = "io.coil-kt.coil3", name = "coil-gif", version.ref = "coil" }
```

and registering the decoder factory in the existing Coil `ImageLoader` setup. Note that animated
GIFs decode on the CPU and hold every frame's bitmap — the resolution and size caps in §3 are doing
more work for GIF than for video, and it is defensible to set a tighter size cap for GIFs
specifically.

---

## 5. Transitions

The still-wallpaper path today swaps instantly. For motion, the first frame lands tens of
milliseconds after the player is created, so:

* render the poster underneath the `TextureView` always, and fade the video in on
  `onRenderedFirstFrame` — the same `animateFloatAsState` pattern `Icon1VideoOverlay` uses;
* when `motionAllowed` goes false, the player is released and the poster is simply what remains —
  no crossfade needed in that direction, and a hard cut is *correct* there: the user just opened an
  overlay or the device just went into battery saver, and an animated exit would be the one thing
  still animating.

The `0x59000000` legibility scrim stays exactly where it is, over both layers.

---

## 6. Settings surface

`DisplaySettingsScreen` — the wallpaper block. Today: Choose / Preview / Reset, then Wave Style
shown only when no wallpaper is set. Proposed:

* **Choose Wallpaper** — extend the picker's MIME array to include the motion types, and update the
  sublabel: *"Pick an image or a short video (PNG, JPG, WEBP, MP4, WEBM, GIF) — replaces the wave"*.
  One picker, not two: the user's mental model is "my background", and `onWallpaperPicked` already
  branches on MIME.
* **Reset Wallpaper** — must clear *both* keys. Easy to miss; a leftover motion path with a cleared
  poster is the invalid state §2 warns about.
* **Wave Style** row — unchanged in visibility logic (hidden when any wallpaper is set), but when a
  *motion* wallpaper is set, show a **Background Motion** row in its place with the same
  Animated/Reduced/Static cycle, writing `KEY_WAVE_STYLE`. Reusing that key means one setting
  governs "how lively is my background" regardless of which background is active, and a user who
  set STATIC for the wave gets a still poster the moment they pick a video — which is almost
  certainly what they meant.
* **Preview Wallpaper** — the full-screen preview should play the motion file, since a "preview"
  that shows a frozen frame of a video is a bug report waiting to happen.

Also: the battery-saver and thermal toggles' sublabels currently say "wave". They now govern the
wallpaper too, so the copy should say "background".

---

## 7. Theme bundles

`PfpThemeStore.applyBundle` writes `KEY_CUSTOM_WALLPAPER` from a sidecar and, crucially, *removes*
it when a bundle carries no wallpaper. That removal must extend to `KEY_MOTION_WALLPAPER`, and so
must `resetApplied()` and its file cleanup — otherwise applying a wave-only theme leaves the previous
theme's video looping behind it. This is the single highest-risk regression in the plan, because it
fails in a state the author of the theme change will not be testing.

Motion wallpapers *inside* `.ptf` bundles (a real PS3-dynamic-theme equivalent) are deliberately out
of scope: it means a bundle format bump, size limits on an already large format, and a Studio-side
preview that can play video. Worth doing later; not worth coupling to this.

---

## 8. Testing

The repo's pattern is pure functions tested off-device (`AppPickerLogic`, `DiscSetBuilder`,
`RescanTriggerBus`), and the interesting logic here factors out the same way.

1. **`MotionWallpaperPolicy`** (core-ui, pure) —
   `fun decide(hasMotion, hasPoster, style, covered, throttled, appVisible): Playback` returning
   `PLAY`, `PLAY_REDUCED`, or `POSTER`. Test matrix: every freeze input independently forces
   `POSTER`; `STATIC`/`REDUCED_STATIC` force `POSTER`; motion-without-poster forces `POSTER`; only
   the all-clear case plays. This is the plan's central claim, so it should be the thing a test pins.
2. **`MotionWallpaperLimits.validate(mime, width, height, durationMs, bytes)`** — one case per
   rejection reason plus boundary values at each cap (a 60.0 s clip is accepted, 60.1 s is not).
3. **Pref-contract test** — clearing the wallpaper clears both keys; applying a wave-only theme
   bundle clears both keys. Mirrors the existing `PfpThemeStoreTest` style.

Not unit-testable, needs a device pass: decoder release on background (verify with the profiler, or
`adb shell dumpsys media.player`, that no codec survives a game launch), thermal freeze, and the
first-frame crossfade.

---

## 9. Suggested order

| Step | Scope | Why here |
|---|---|---|
| 1 | `MotionWallpaperPolicy` + `MotionWallpaperLimits` + tests | Pure, no Android, pins the contract before any UI exists |
| 2 | Prefs contract + import path (probe, poster extraction, both-keys writes, clear) | Nothing renders yet; verifiable through Settings and the file system |
| 3 | `XmbBackground` motion branch + `XMBShell` `motionAllowed` refactor | The render work, now that a valid file and a policy exist |
| 4 | Lifecycle observer + release-on-background | Separable, and the one most worth reviewing on its own |
| 5 | Settings copy, preview playback, theme-store clearing | The edges |
| 6 | GIF/WebP via `coil-gif` | Independent of 3–5; can slip without blocking video |

Effort: **M** (a few days), with step 3 carrying most of the risk and step 4 most of the consequence.
