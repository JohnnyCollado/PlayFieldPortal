package com.playfieldportal.core.ui.components

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import com.playfieldportal.core.domain.model.FocusGlow
import com.playfieldportal.core.domain.model.FocusGlowMode
import com.playfieldportal.core.ui.motion.LocalImageMotion
import com.playfieldportal.core.ui.motion.LocalMotionAllowed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Display ▸ Focus glow, as the user set it. Provided once at the app root. */
val LocalFocusGlowMode = compositionLocalOf { FocusGlowMode.DEFAULT }

/**
 * The glow of one icon: its own shape, blurred twice. [soft] is the wide haze and [tight] the
 * brighter rim; each offset is where that image sits relative to the icon's top-left corner.
 */
class ShapeHalo(
    /** The size of the picture the glow was built from; the glow is scaled if the icon is drawn at another. */
    val sourceWidth: Int,
    val soft: Bitmap,
    val softOffset: IntArray,
    val tight: Bitmap,
    val tightOffset: IntArray,
)

/**
 * Builds the glow for [content], a picture of the icon exactly as it is drawn. The icon's
 * transparency is its shape; blurring that shape gives a light that follows the icon's outline
 * instead of its box. Both calls exist on every Android version.
 */
fun buildShapeHalo(content: Bitmap): ShapeHalo {
    fun blurred(radius: Float): Pair<Bitmap, IntArray> {
        val offset = IntArray(2)
        val paint = Paint().apply { maskFilter = BlurMaskFilter(radius.coerceAtLeast(0.5f), BlurMaskFilter.Blur.NORMAL) }
        return content.extractAlpha(paint, offset) to offset
    }
    val w = content.width.toFloat()
    val h = content.height.toFloat()
    val (soft, softOffset) = blurred(FocusGlow.softRadiusPx(w, h))
    val (tight, tightOffset) = blurred(FocusGlow.tightRadiusPx(w, h))
    return ShapeHalo(content.width, soft, softOffset, tight, tightOffset)
}

/**
 * Draws the focus glow under whatever this modifier is put on, in that content's own shape, while
 * [active]. Put it on the box an icon is drawn in (before any clip, so the glow can spill out).
 *
 * The content is recorded as it is drawn, a picture is taken of it shortly after, and the glow is
 * built from that picture off the main thread. The picture is retaken a couple of times so art
 * that loads late is caught. [key] names what is shown: a new key starts again.
 *
 * [prepare] builds the glow ahead of time for something that is not focused yet, so it is already
 * there the instant it becomes [active]: the crossbar does this for every category, so stepping
 * along it never shows a moment with no glow. The glow is scaled to the size the content is drawn
 * at, so an icon that grows when focused keeps the glow it was given while small.
 *
 * Off draws nothing extra. Pulsing swells and fades slowly, and holds still whenever motion is
 * not wanted (see [FocusGlowMode.shown]).
 */
@Composable
fun Modifier.focusGlow(active: Boolean, key: Any? = null, prepare: Boolean = active): Modifier {
    val mode = LocalFocusGlowMode.current.shown(
        imageMotion = LocalImageMotion.current,
        motionAllowed = LocalMotionAllowed.current && ValueAnimator.areAnimatorsEnabled(),
    )
    if (mode == FocusGlowMode.OFF || (!active && !prepare)) return this

    val layer = rememberGraphicsLayer()
    // Only whether there is a size yet: an icon that is growing must not restart the capture each frame.
    var hasSize by remember { mutableStateOf(false) }
    var halo by remember(key) { mutableStateOf<ShapeHalo?>(null) }
    LaunchedEffect(key, hasSize) {
        if (!hasSize) return@LaunchedEffect
        for (wait in captureDelaysMs(active)) {
            delay(wait)
            // Fails until the first frame has been drawn (the recording is still empty): try again.
            val captured = runCatching { layer.toImageBitmap().asAndroidBitmap() }.getOrNull() ?: continue
            halo = withContext(Dispatchers.Default) {
                // A captured picture can live on the GPU; the blur needs one in ordinary memory.
                runCatching { buildShapeHalo(captured.copy(Bitmap.Config.ARGB_8888, false)) }.getOrNull()
            } ?: halo
        }
    }
    val phase: State<Float>? = if (active && mode == FocusGlowMode.PULSING) {
        rememberInfiniteTransition(label = "focusGlow").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(FocusGlow.PULSE_MS, easing = LinearEasing), RepeatMode.Restart),
            label = "focusGlowPhase",
        )
    } else {
        null
    }
    val paint = remember {
        Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = PorterDuffColorFilter(FocusGlow.COLOR_ARGB, PorterDuff.Mode.SRC_IN)
        }
    }
    return this
        .onSizeChanged { hasSize = it.width > 0 && it.height > 0 }
        .drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            val h = halo
            if (active && h != null) {
                // Read here, in the draw, so the pulse redraws without recomposing anything.
                val level = phase?.value?.let(FocusGlow::pulseLevel) ?: 1f
                val scale = size.width / h.sourceWidth
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas
                    native.save()
                    native.scale(scale, scale)
                    paint.alpha = (FocusGlow.SOFT_ALPHA * level).toInt()
                    native.drawBitmap(h.soft, h.softOffset[0].toFloat(), h.softOffset[1].toFloat(), paint)
                    paint.alpha = (FocusGlow.TIGHT_ALPHA * level).toInt()
                    native.drawBitmap(h.tight, h.tightOffset[0].toFloat(), h.tightOffset[1].toFloat(), paint)
                    native.restore()
                }
            }
            drawLayer(layer)
        }
}

/**
 * The waits between pictures of the icon, after it first has a size: soon, then twice more for art
 * that arrives late (a cover still loading, a theme icon decoding). A focused icon looks twice more,
 * out to about nine seconds, for a cover still being fetched; each look is a GPU read and a blur, so
 * icons prepared in the background (the crossbar's, static art) keep the short schedule.
 */
internal fun captureDelaysMs(active: Boolean): List<Long> =
    if (active) listOf(60L, 300L, 1200L, 2500L, 5000L) else listOf(60L, 300L, 1200L)
