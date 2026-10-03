package com.playfieldportal.studio.ui.sections

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.playfieldportal.studio.CropFrame
import kotlin.math.abs
import kotlin.math.min

/**
 * Maps between the crop view (pixels of the canvas) and the source image (pixels of the file). The
 * source is drawn letterboxed and centered, so one uniform [scale] and an offset describe it. Pure:
 * every gesture is turned into a [CropFrame] operation here, never in the composable.
 */
data class CropViewport(val viewW: Float, val viewH: Float, val sourceW: Int, val sourceH: Int) {
    val scale: Float = min(viewW / sourceW, viewH / sourceH)
    val offsetX: Float = (viewW - sourceW * scale) / 2f
    val offsetY: Float = (viewH - sourceH * scale) / 2f

    fun sourceX(viewX: Float): Float = (viewX - offsetX) / scale
    fun sourceY(viewY: Float): Float = (viewY - offsetY) / scale
    fun viewX(sourceX: Float): Float = offsetX + sourceX * scale
    fun viewY(sourceY: Float): Float = offsetY + sourceY * scale

    /** What a press at ([x], [y]) grabs: a corner handle first, then the frame body, else nothing. */
    fun grabAt(frame: CropFrame, x: Float, y: Float, handleRadius: Float = HANDLE_RADIUS): CropGrab? {
        val left = viewX(frame.x)
        val top = viewY(frame.y)
        val right = viewX(frame.x + frame.w)
        val bottom = viewY(frame.y + frame.h)
        val corners = listOf(
            CropFrame.Corner.TOP_LEFT to Offset(left, top),
            CropFrame.Corner.TOP_RIGHT to Offset(right, top),
            CropFrame.Corner.BOTTOM_LEFT to Offset(left, bottom),
            CropFrame.Corner.BOTTOM_RIGHT to Offset(right, bottom),
        )
        corners.minByOrNull { (_, at) -> abs(at.x - x) + abs(at.y - y) }
            ?.takeIf { (_, at) -> abs(at.x - x) <= handleRadius && abs(at.y - y) <= handleRadius }
            ?.let { return CropGrab.Corner(it.first) }
        return if (x in left..right && y in top..bottom) CropGrab.Body else null
    }

    /**
     * Applies one drag step. A body drag slides the frame by the view delta ([dxView], [dyView]); a
     * corner drag pulls that corner to the pointer ([pointerX], [pointerY], view px).
     */
    fun drag(frame: CropFrame, grab: CropGrab, dxView: Float, dyView: Float, pointerX: Float, pointerY: Float): CropFrame =
        when (grab) {
            CropGrab.Body -> frame.moved(dxView / scale, dyView / scale)
            is CropGrab.Corner -> frame.resizedFromCorner(grab.corner, sourceX(pointerX), sourceY(pointerY))
        }

    companion object {
        /** Half-size of a corner's grab target, in view px. */
        const val HANDLE_RADIUS = 14f
    }
}

/** What a drag holds: the whole frame, or one of its corners. */
sealed interface CropGrab {
    data object Body : CropGrab
    data class Corner(val corner: CropFrame.Corner) : CropGrab
}

/** 0 = the largest frame, 1 = the smallest; the zoom slider's value. */
fun cropZoomFraction(frame: CropFrame): Float {
    val span = frame.maxW - frame.minW
    return if (span <= 0f) 0f else ((frame.maxW - frame.w) / span).coerceIn(0f, 1f)
}

/** [frame] zoomed (around its center) to the slider value [fraction]. */
fun cropWithZoomFraction(frame: CropFrame, fraction: Float): CropFrame {
    val target = frame.maxW - fraction.coerceIn(0f, 1f) * (frame.maxW - frame.minW)
    return frame.zoomed(frame.w / target)
}

/**
 * The source image with the crop frame on top: drag the body to move it, drag a corner to resize it
 * (the ratio stays locked to the fit). Everything outside the frame is dimmed.
 */
@Composable
fun CropFrameView(
    image: ImageBitmap,
    sourceW: Int,
    sourceH: Int,
    frame: CropFrame,
    onFrame: (CropFrame) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentFrame by rememberUpdatedState(frame)
    val currentOnFrame by rememberUpdatedState(onFrame)
    var grab by remember { mutableStateOf<CropGrab?>(null) }
    Box(modifier) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(sourceW, sourceH) {
                    // Read per event, not once: the canvas can be resized while the gesture block lives.
                    fun viewport() = CropViewport(size.width.toFloat(), size.height.toFloat(), sourceW, sourceH)
                    detectDragGestures(
                        onDragStart = { at -> grab = viewport().grabAt(currentFrame, at.x, at.y) },
                        onDragEnd = { grab = null },
                        onDragCancel = { grab = null },
                    ) { change, delta ->
                        val held = grab ?: return@detectDragGestures
                        change.consume()
                        currentOnFrame(
                            viewport().drag(currentFrame, held, delta.x, delta.y, change.position.x, change.position.y),
                        )
                    }
                },
        ) {
            val vp = CropViewport(size.width, size.height, sourceW, sourceH)
            val imageLeft = vp.viewX(0f)
            val imageTop = vp.viewY(0f)
            val imageW = sourceW * vp.scale
            val imageH = sourceH * vp.scale
            drawImage(
                image,
                dstOffset = IntOffset(imageLeft.toInt(), imageTop.toInt()),
                dstSize = IntSize(imageW.toInt(), imageH.toInt()),
            )
            val left = vp.viewX(frame.x)
            val top = vp.viewY(frame.y)
            val width = frame.w * vp.scale
            val height = frame.h * vp.scale
            val dim = Color(0x99000000)
            // Four bands around the frame; the picture shows through only inside it.
            drawRect(dim, Offset(imageLeft, imageTop), Size(imageW, top - imageTop))
            drawRect(dim, Offset(imageLeft, top + height), Size(imageW, imageTop + imageH - top - height))
            drawRect(dim, Offset(imageLeft, top), Size(left - imageLeft, height))
            drawRect(dim, Offset(left + width, top), Size(imageLeft + imageW - left - width, height))
            drawRect(Color.White, Offset(left, top), Size(width, height), style = Stroke(width = 2f))
            val handle = 9f
            listOf(
                Offset(left, top), Offset(left + width, top),
                Offset(left, top + height), Offset(left + width, top + height),
            ).forEach { at ->
                drawRect(Color.White, Offset(at.x - handle / 2, at.y - handle / 2), Size(handle, handle))
            }
        }
    }
}
