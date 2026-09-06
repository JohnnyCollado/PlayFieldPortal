# Motion wallpaper — stop building an ExoPlayer for GIF/WebP wallpapers

Source: direct request (device logcat, 2026-09-05, XMB return from Settings). Effort: S.
Branch base: `more-customization` (the branch that introduced `core-ui/.../motion/`).

This is an implementation handoff. It is written to be executed without the conversation that
produced it. Every line reference was verified against the working tree on 2026-09-05; re-check any
that has drifted, but do not assume a helper exists that is not named here.

## Problem

With a **GIF** motion wallpaper applied, every re-entry into the XMB (backing out of Settings, in
the reported trace) emits:

```
ExoPlayerImpl          Init dc0c2f [AndroidXMedia3/1.11.0]
ExoPlayerImplInternal  Playback error
  androidx.media3.exoplayer.ExoPlaybackException: Source error
  Caused by: androidx.media3.exoplayer.source.UnrecognizedInputFormatException:
    None of the available extractors (FlvExtractor, …, AvifExtractor) could read the stream.
  sniff failures: [NoDeclaredBrand, NoDeclaredBrand]
```

`MotionVideoSurface` (`MotionWallpaperBackground.kt:89`) classifies the file at `:94`:

```kotlin
val isAnimatedImage = motionPath.endsWith(".gif", ignoreCase = true) ||
    motionPath.endsWith(".webp", ignoreCase = true)
```

…and then **builds an ExoPlayer unconditionally** at `:97-114`, feeding it that same GIF. Media3 has
no GIF extractor (note the extractor list in the trace: Jpeg/Png/Webp/Bmp/Heif/Avif — no Gif), so
every sniff fails and the player errors out on its loading thread.

Nothing visibly breaks. The animated layer at `:147-160` renders the GIF correctly —
`coil3.gif.AnimatedImageDecoder.Factory()` is registered on the singleton `ImageLoader`
(`ArtworkModule.kt:88`), and `XmbBackground.kt:74` never routes a `POSTER` decision into this
composable, so the request carries `REPEAT_INFINITE` and the wallpaper animates. The defect is
entirely a cost defect.

### Why it matters

The file's own governing rule (`MotionWallpaperBackground.kt:41-46`, restated in
`MotionWallpaperPolicy`):

> When the wave would be frozen, the motion wallpaper is not merely paused — no decoder exists.

The policy axis honors that rule exactly. The **format** axis violates it: a GIF wallpaper holds a
live `ExoPlayer` instance, its loader thread, and a `TextureView` for the entire time the XMB is on
screen, forever unable to produce a frame. On a handheld launcher that is precisely the allocation
the rule exists to prevent, and it is paid on every re-entry into the background branch.

Two dead artifacts ride along on the GIF path:

- `firstFrameRendered` (`:91`) never flips, so the `AndroidView`/`TextureView` at `:171-183` sits at
  `alpha = 0f` permanently — composed, measured, laid out, and invisible.
- `videoSize` (`:92`) stays null, so `applyCenterCrop` (`:193`) returns at its first line on every
  layout pass.

## Goal

Make "a GIF wallpaper constructs no `ExoPlayer`" **structural** — guaranteed by which composable is
called — rather than conditional on a null check inside a shared one. Two sibling surfaces, one per
decode strategy, chosen by the caller.

Non-goals: changing the import gate, the accepted formats (`MotionWallpaperLimits.SUPPORTED_MIME`),
the poster pairing, or anything in `MotionWallpaperPolicy.decide`.

## Implementation

All work is in `core/core-ui/src/main/kotlin/com/playfieldportal/core/ui/motion/`.

### Step 1 — extract the format classification as a pure function

`core-ui` has no Robolectric or Compose test infrastructure (`build.gradle.kts:34` pulls only
`libs.bundles.test.unit`), so the branch condition must live somewhere a JVM test can reach it.

Add to `MotionWallpaperLimits.kt` — it already owns the format vocabulary (`SUPPORTED_MIME` at
`:18`), so the extension list stays paired with the MIME list it mirrors:

```kotlin
/**
 * Which decoder a stored motion file needs. The extension is authoritative: the importer names
 * every file `wallpaper_<stamp>.<ext>` from the validated MIME (DisplaySettingsViewModel), so the
 * suffix IS the MIME by construction and no probe is needed at render time.
 */
enum class MotionFormat { VIDEO, ANIMATED_IMAGE }

fun formatOf(path: String): MotionFormat =
    if (path.endsWith(".gif", ignoreCase = true) || path.endsWith(".webp", ignoreCase = true)) {
        MotionFormat.ANIMATED_IMAGE
    } else {
        MotionFormat.VIDEO
    }
```

Keep VIDEO as the else-branch, matching today's behavior: an unexpected suffix goes to ExoPlayer,
which fails loudly, rather than to Coil, which would fail silently.

### Step 2 — split `MotionVideoSurface` in two

In `MotionWallpaperBackground.kt`, replace the single call at `:80`:

```kotlin
when (MotionWallpaperLimits.formatOf(motionPath)) {
    MotionFormat.ANIMATED_IMAGE -> AnimatedImageSurface(motionPath)
    MotionFormat.VIDEO -> MotionVideoSurface(motionPath, decision)
}
```

`AnimatedImageSurface(motionPath: String)` is the `AsyncImage` block lifted out of `:147-160`. It
takes no `decision`: the caller already guarantees the decision is not `POSTER`
(`XmbBackground.kt:74`), so `repeatCount` is unconditionally `MovieDrawable.REPEAT_INFINITE` and the
`if (decision == POSTER) 1 else …` conditional disappears with the branch that made it reachable.

`MotionVideoSurface` then loses `isAnimatedImage` (`:94`) and the whole `if (isAnimatedImage)` block
(`:147-160`). Everything else in it — the player, listener, dispose, fade, `TextureView`,
`applyCenterCrop` — is unchanged and now runs only for real video.

### Step 3 — update the KDoc

The file header (`:33-56`) and `MotionWallpaperPolicy`'s class doc both state the rule in terms of
the policy decision only. Add the format axis to the header's bullet list — one line, e.g.:

> • GIF/animated WebP never construct a player at all: they are a *separate composable*
>   (`AnimatedImageSurface`), because ExoPlayer has no GIF extractor and a player built on one
>   fails to sniff, logs a `Source error`, and holds a codec that can never render a frame.

The existing `:139-146` comment explaining Coil's repeat-count semantics moves with the `AsyncImage`
into `AnimatedImageSurface`; trim its now-dead `POSTER` sentence.

### Step 4 — tests

Add `MotionWallpaperFormatTest` (or extend `MotionWallpaperLimitsTest`) in
`core/core-ui/src/test/kotlin/com/playfieldportal/core/ui/motion/`, covering `formatOf`:

- `.gif`, `.GIF`, `.webp`, `.WebP` → `ANIMATED_IMAGE`
- `.mp4`, `.webm` → `VIDEO`
- an unknown or absent extension → `VIDEO` (pins the deliberate fallback)
- a path whose **directory** contains `.gif` but whose file does not
  (e.g. `/data/…/my.gif.stuff/wallpaper_1.mp4`) → `VIDEO`

Assert every member of `SUPPORTED_MIME` maps to some format, so adding a MIME without teaching
`formatOf` about it fails a test rather than silently landing in the ExoPlayer branch.

## Verification

Unit: `./gradlew :core:core-ui:testDebugUnitTest`

On device, with a **GIF** wallpaper applied (Settings ▸ Display ▸ wallpaper):

1. `adb logcat -c`, then enter Settings and back out to the XMB.
2. Expect **no** `ExoPlayerImpl Init` and no `ExoPlayerImplInternal Playback error` lines — the
   absence of `Init` is the assertion, not merely the absence of the error.
3. The wallpaper still animates and still loops (watch a full loop cycle).

Then repeat with an **MP4** wallpaper and confirm nothing regressed: `Init` appears once,
`MotionWallpaper motion first frame rendered (…)` follows, the video fades in over the poster, and
backgrounding to launch a game still logs a `Release`. The policy path is untouched, but it shares
the composable being edited.

The adb binary on this PC is at `C:\Users\johnn\AppData\Local\Android\Sdk\platform-tools\adb.exe`
(see CLAUDE.md).

## Follow-up, not in scope

`MotionWallpaperPolicy.Decision.PLAY_REDUCED` is a no-op for animated images — acknowledged in the
existing comment at `:154-157` ("there is no frame-rate knob"). After this split it becomes visible
as a signature difference: `AnimatedImageSurface` does not take the decision at all. If REDUCED is
meant to be a real power/accessibility guarantee rather than a look, the honest options are to treat
`PLAY_REDUCED` as `POSTER` for animated images, or to say in the settings copy that reduced motion
applies to video wallpapers only. Decide separately; do not fold it into this change.
