package com.playfieldportal.studio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Dependency-free HSV picker: a saturation/value square plus a vertical hue strip, drawn
 * with plain gradients (no experimental widgets — see the CMP 1.6.11 stability note in
 * StudioApp). Emits packed opaque ARGB.
 *
 * The HSV working state lives here so grey/black picks don't lose their hue; external
 * changes (hex field, preset swatch) re-sync it.
 */
@Composable
fun HsvColorPicker(
    argb: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var hsv by remember { mutableStateOf(argbToHsv(argb)) }
    var lastEmitted by remember { mutableStateOf(argb) }
    if (argb != lastEmitted) {
        // The hex field or a swatch changed the color under us — resync.
        hsv = argbToHsv(argb)
        lastEmitted = argb
    }

    fun emit(h: Float, s: Float, v: Float) {
        hsv = floatArrayOf(h, s, v)
        val packed = hsvToArgb(h, s, v)
        lastEmitted = packed
        onChange(packed)
    }

    Row(modifier) {
        // ── Saturation/value square ──
        val squareSize = 168.dp
        Canvas(
            Modifier
                .size(squareSize)
                .pointerInput(Unit) {
                    detectTapGestures { pos -> emitFromSquare(pos, size.width, size.height, hsv, ::emit) }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        emitFromSquare(change.position, size.width, size.height, hsv, ::emit)
                    }
                },
        ) {
            // White → pure hue horizontally, then transparent → black vertically on top.
            val hueColor = Color(hsvToArgb(hsv[0], 1f, 1f))
            drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            // Thumb at (saturation, 1 - value).
            val thumb = Offset(hsv[1] * size.width, (1f - hsv[2]) * size.height)
            drawCircle(Color.Black, radius = 7f, center = thumb, style = Stroke(width = 3f))
            drawCircle(Color.White, radius = 5f, center = thumb, style = Stroke(width = 2f))
        }

        Spacer(Modifier.width(10.dp))

        // ── Hue strip ──
        Canvas(
            Modifier
                .width(22.dp)
                .height(squareSize)
                .pointerInput(Unit) {
                    detectTapGestures { pos -> emit(hueAt(pos.y, size.height), hsv[1], hsv[2]) }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        emit(hueAt(change.position.y, size.height), hsv[1], hsv[2])
                    }
                },
        ) {
            drawRect(
                Brush.verticalGradient(
                    colors = (0..6).map { Color(hsvToArgb(it / 6f, 1f, 1f)) },
                ),
            )
            val y = hsv[0] * size.height
            drawLine(Color.Black, Offset(0f, y), Offset(size.width, y), strokeWidth = 4f)
            drawLine(Color.White, Offset(0f, y), Offset(size.width, y), strokeWidth = 2f)
        }
    }
}

private inline fun emitFromSquare(
    pos: Offset,
    width: Int,
    height: Int,
    hsv: FloatArray,
    emit: (Float, Float, Float) -> Unit,
) {
    val s = (pos.x / width).coerceIn(0f, 1f)
    val v = 1f - (pos.y / height).coerceIn(0f, 1f)
    emit(hsv[0], s, v)
}

private fun hueAt(y: Float, height: Int): Float = (y / height).coerceIn(0f, 0.9999f)

private fun argbToHsv(argb: Int): FloatArray =
    java.awt.Color.RGBtoHSB(argb shr 16 and 0xFF, argb shr 8 and 0xFF, argb and 0xFF, null)

private fun hsvToArgb(h: Float, s: Float, v: Float): Int =
    0xFF000000.toInt() or (java.awt.Color.HSBtoRGB(h, s, v) and 0xFFFFFF)
