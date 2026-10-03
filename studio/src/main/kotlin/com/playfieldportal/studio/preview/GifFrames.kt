package com.playfieldportal.studio.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntSize
import com.playfieldportal.studio.StudioState
import com.playfieldportal.themekit.IconGifSupport
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image as SkiaImage

/**
 * Animated icon art for the live preview. A GIF icon animates only while its row / category is the
 * focused one (the launcher's own rule); every other icon shows frame 1, which is already the
 * decoded static override. Frames are decoded lazily with Skia's multi-frame [Codec], off the UI
 * thread, capped by [IconGifSupport]'s limits and a memory budget.
 */
class GifTimeline(delaysMs: List<Int>) {
    private val ends: IntArray = delaysMs.runningFold(0) { sum, d -> sum + d }.drop(1).toIntArray()
    val frameCount: Int get() = ends.size
    val totalMs: Int get() = ends.lastOrNull() ?: 0

    /** The frame showing [elapsedMs] into a looping playback. */
    fun frameAt(elapsedMs: Long): Int {
        if (ends.size <= 1 || totalMs <= 0) return 0
        val t = Math.floorMod(elapsedMs, totalMs.toLong())
        val i = ends.indexOfFirst { t < it }
        return if (i < 0) ends.lastIndex else i
    }
}

class GifAnimation(val frames: List<ImageBitmap>, val timeline: GifTimeline)

object GifFrames {

    /** A decoded animation never holds more than this many pixel bytes, whatever the GIF claims. */
    const val MEMORY_BUDGET_BYTES = 48L * 1024 * 1024

    /** GIF delays of 0-1 cs are conventionally played at 100 ms (browsers and the launcher's decoder do). */
    const val DEFAULT_DELAY_MS = 100

    /** How many frames of a [width] x [height] GIF fit the budget and [IconGifSupport.MAX_FRAMES]; at least 2 so it still animates. */
    fun frameLimit(width: Int, height: Int): Int {
        val perFrame = (width.toLong() * height * 4).coerceAtLeast(1L)
        return (MEMORY_BUDGET_BYTES / perFrame).coerceIn(2L, IconGifSupport.MAX_FRAMES.toLong()).toInt()
    }

    fun delayOrDefault(ms: Int): Int = if (ms <= 10) DEFAULT_DELAY_MS else ms

    /**
     * The icons that move: GIF overrides with 2+ frames inside [IconGifSupport]'s limits. A still, a
     * single-frame GIF, or a GIF over the caps is left to its static bitmap.
     */
    fun animatedIcons(state: StudioState): Map<String, ByteArray> = buildMap {
        val extensions = state.iconExtensions + state.sysiconExtensions
        for ((key, bytes) in state.iconOverrides + state.sysiconOverrides) {
            if (extensions[key]?.lowercase() != "gif" || !IconGifSupport.isGif(bytes)) continue
            val size = IconGifSupport.logicalScreenSize(bytes) ?: continue
            val frames = IconGifSupport.countFrames(bytes)
            if (frames < 2) continue
            if (IconGifSupport.validateAnimated(size.first, size.second, frames, IconGifSupport.durationMs(bytes)) != null) continue
            put(key, bytes)
        }
    }

    /** True when [key] should animate right now: live, focused, and it has an animation to play. */
    fun animates(key: String, focused: Boolean, live: PreviewLiveSpec?): Boolean =
        focused && live != null && key in live.iconGifs

    /** Up to [frameLimit] frames and their delays, or null when the bytes do not decode to an animation. */
    fun decode(bytes: ByteArray): GifAnimation? = runCatching {
        val codec = Codec.makeFromData(Data.makeFromBytes(bytes))
        try {
            val info = codec.imageInfo
            val count = minOf(codec.frameCount, frameLimit(info.width, info.height))
            val infos = codec.framesInfo
            val frames = ArrayList<ImageBitmap>(count)
            val delays = ArrayList<Int>(count)
            for (index in 0 until count) {
                val bitmap = Bitmap().apply { allocPixels(info) }
                val image = runCatching {
                    codec.readPixels(bitmap, index)
                    SkiaImage.makeFromBitmap(bitmap).toComposeImageBitmap()
                }.getOrNull() ?: continue
                frames += image
                delays += delayOrDefault(infos.getOrNull(index)?.duration ?: 0)
            }
            if (frames.size < 2) null else GifAnimation(frames, GifTimeline(delays))
        } finally {
            codec.close()
        }
    }.getOrNull()
}

/** A handful of recently decoded animations, so re-focusing a row does not decode again. */
internal object GifCache {
    private class Key(val bytes: ByteArray) {
        override fun equals(other: Any?) = other is Key && other.bytes === bytes
        override fun hashCode() = System.identityHashCode(bytes)
    }

    private const val MAX_ENTRIES = 8
    private val entries = object : LinkedHashMap<Key, GifAnimation>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, GifAnimation>?) = size > MAX_ENTRIES
    }

    @Synchronized
    fun get(bytes: ByteArray): GifAnimation? = entries[Key(bytes)]

    @Synchronized
    fun put(bytes: ByteArray, animation: GifAnimation) {
        entries[Key(bytes)] = animation
    }
}

/** The animation for [bytes] once it has decoded (off the UI thread); null while decoding or when it cannot. */
@Composable
internal fun rememberGifAnimation(bytes: ByteArray?): GifAnimation? {
    val animation by produceState<GifAnimation?>(initialValue = bytes?.let(GifCache::get), bytes) {
        value = if (bytes == null) {
            null
        } else {
            GifCache.get(bytes) ?: withContext(Dispatchers.Default) { GifFrames.decode(bytes) }
                ?.also { GifCache.put(bytes, it) }
        }
    }
    return animation
}

/** Draws whichever frame the shared preview clock says is current; the clock read happens in the draw phase. */
internal class GifPainter(
    private val animation: GifAnimation,
    private val elapsedMs: LongState,
) : Painter() {
    private var alpha = 1f
    private var colorFilter: ColorFilter? = null

    override val intrinsicSize: Size = animation.frames.first().let { Size(it.width.toFloat(), it.height.toFloat()) }

    override fun applyAlpha(alpha: Float): Boolean {
        this.alpha = alpha
        return true
    }

    override fun applyColorFilter(colorFilter: ColorFilter?): Boolean {
        this.colorFilter = colorFilter
        return true
    }

    override fun DrawScope.onDraw() {
        val frame = animation.frames[animation.timeline.frameAt(elapsedMs.longValue)]
        drawImage(
            image = frame,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            alpha = alpha,
            colorFilter = colorFilter,
            filterQuality = FilterQuality.Medium,
        )
    }
}
