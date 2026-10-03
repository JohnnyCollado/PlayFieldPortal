package com.playfieldportal.studio.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.playfieldportal.themekit.MotionCrop
import java.io.File

/**
 * What the interactive preview animates, resolved from the document. Null where a [PreviewLive] is
 * expected means a static frame — which is what the exported preview.png always renders.
 */
data class PreviewLiveSpec(
    /** Icon key -> GIF bytes, for the GIF icons that animate when focused. */
    val iconGifs: Map<String, ByteArray> = emptyMap(),
    /** The staged motion wallpaper and the crop the launcher would play it with. */
    val motionFile: File? = null,
    val motionCrop: MotionCrop? = null,
    /** The boot / GameBoot sequence currently playing over the frame, if any. */
    val boot: BootPlaybackState = BootPlaybackState.IDLE,
)

/**
 * The live preview's runtime: the spec, the wave's rules, and the one clock the wave and GIF icons
 * share. [elapsedMs] is written once per frame and read only from draw lambdas, so a tick redraws
 * and never recomposes.
 */
@Stable
class PreviewLive(
    val spec: PreviewLiveSpec,
    val wave: WaveParams,
    val elapsedMs: MutableLongState,
) {
    /** The motion loop could not keep up (or could not decode): the poster is showing, and says so. */
    var motionFellBack by mutableStateOf(false)
}

val LocalPreviewLive = compositionLocalOf<PreviewLive?> { null }

/**
 * Builds the runtime for [spec] (null = static) and runs the shared clock only while something
 * reads it: an animating wave over the wave background, or a GIF icon somewhere on screen.
 */
@Composable
internal fun rememberPreviewLive(spec: PreviewLiveSpec?, waveStyle: String, hasWallpaper: Boolean): PreviewLive? {
    val elapsed = remember { mutableLongStateOf(0L) }
    val wave = WaveMotion.paramsFor(waveStyle)
    val live = spec?.let { remember(it, waveStyle) { PreviewLive(it, wave, elapsed) } }
    val clockNeeded = spec != null && ((wave.animated && !hasWallpaper) || spec.iconGifs.isNotEmpty())
    LaunchedEffect(clockNeeded) {
        if (!clockNeeded) return@LaunchedEffect
        var start = -1L
        while (true) {
            androidx.compose.runtime.withFrameNanos { nanos ->
                if (start < 0) start = nanos
                elapsed.longValue = (nanos - start) / 1_000_000L
            }
        }
    }
    return live
}
