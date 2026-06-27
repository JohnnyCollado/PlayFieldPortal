package com.playfieldportal.feature.xmb.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.playfieldportal.core.domain.model.Category
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal enum class XmbCategoryIconType {
    SETTINGS,
    PHOTO,
    MUSIC,
    VIDEO,
    GAME,
    NETWORK,
    APP_STORE,
}

internal fun xmbCategoryIconType(category: Category): XmbCategoryIconType =
    xmbCategoryIconTypeOrNull(category) ?: XmbCategoryIconType.GAME

// The Canvas icon set only has glyphs for these built-in types. Other keys (the console icons
// picked for custom gaming categories — SNES, PSP, N64, …) return null so the caller can render
// them from the shared sprite sheet instead of collapsing them all to the controller glyph.
internal fun xmbCategoryIconTypeOrNull(category: Category): XmbCategoryIconType? = when (category.iconKey) {
    "ic_settings" -> XmbCategoryIconType.SETTINGS
    "ic_photos" -> XmbCategoryIconType.PHOTO
    "ic_music" -> XmbCategoryIconType.MUSIC
    "ic_videos" -> XmbCategoryIconType.VIDEO
    "ic_network" -> XmbCategoryIconType.NETWORK
    "ic_appstore" -> XmbCategoryIconType.APP_STORE
    "ic_games" -> XmbCategoryIconType.GAME
    else -> null
}

@Composable
internal fun XmbCategoryIcon(
    type: XmbCategoryIconType,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        when (type) {
            XmbCategoryIconType.SETTINGS -> drawToolboxIcon(tint)
            XmbCategoryIconType.PHOTO -> drawPhotoIcon(tint)
            XmbCategoryIconType.MUSIC -> drawMusicIcon(tint)
            XmbCategoryIconType.VIDEO -> drawFilmIcon(tint)
            XmbCategoryIconType.GAME -> drawControllerIcon(tint)
            XmbCategoryIconType.NETWORK -> drawGlobeIcon(tint)
            XmbCategoryIconType.APP_STORE -> drawAppStoreIcon(tint)
        }
    }
}

@Composable
internal fun XmbMemoryCardIcon(
    tint: Color,
    cutoutColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.08f, h * 0.14f),
            size = Size(w * 0.78f, h * 0.72f),
            cornerRadius = CornerRadius(w * 0.04f, w * 0.04f),
        )
        val notch = Path().apply {
            moveTo(w * 0.08f, h * 0.50f)
            lineTo(w * 0.30f, h * 0.32f)
            lineTo(w * 0.30f, h * 0.68f)
            close()
        }
        drawPath(notch, cutoutColor)
        drawLine(
            color = cutoutColor.copy(alpha = 0.55f),
            start = Offset(w * 0.42f, h * 0.28f),
            end = Offset(w * 0.76f, h * 0.28f),
            strokeWidth = w * 0.04f,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
internal fun XmbBluetoothIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = size.minDimension * 0.10f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.42f, h * 0.10f)
            lineTo(w * 0.68f, h * 0.34f)
            lineTo(w * 0.42f, h * 0.54f)
            lineTo(w * 0.42f, h * 0.10f)
            moveTo(w * 0.42f, h * 0.54f)
            lineTo(w * 0.70f, h * 0.76f)
            lineTo(w * 0.42f, h * 0.96f)
            lineTo(w * 0.42f, h * 0.54f)
            moveTo(w * 0.20f, h * 0.30f)
            lineTo(w * 0.42f, h * 0.54f)
            lineTo(w * 0.20f, h * 0.78f)
        }
        drawPath(path, tint, style = stroke)
    }
}

@Composable
internal fun XmbWifiIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = size.minDimension * 0.10f, cap = StrokeCap.Round)
        val w = size.width
        val h = size.height
        drawArc(
            color = tint,
            startAngle = 220f,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = Offset(w * 0.08f, h * 0.08f),
            size = Size(w * 0.84f, h * 0.84f),
            style = stroke,
        )
        drawArc(
            color = tint,
            startAngle = 225f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(w * 0.25f, h * 0.28f),
            size = Size(w * 0.50f, h * 0.50f),
            style = stroke,
        )
        drawCircle(tint, radius = w * 0.07f, center = Offset(w * 0.50f, h * 0.78f))
    }
}

@Composable
internal fun XmbBatteryIcon(
    level: Int,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokeWidth = h * 0.12f
        val bodyWidth = w * 0.78f
        val bodyHeight = h * 0.58f
        val bodyTop = h * 0.21f
        val bodyLeft = w * 0.04f
        drawRoundRect(
            color = tint,
            topLeft = Offset(bodyLeft, bodyTop),
            size = Size(bodyWidth, bodyHeight),
            cornerRadius = CornerRadius(h * 0.08f, h * 0.08f),
            style = Stroke(width = strokeWidth),
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.86f, h * 0.36f),
            size = Size(w * 0.10f, h * 0.28f),
            cornerRadius = CornerRadius(h * 0.04f, h * 0.04f),
        )
        val fillWidth = (bodyWidth - strokeWidth * 2f) * (level.coerceIn(0, 100) / 100f)
        drawRoundRect(
            color = tint.copy(alpha = 0.78f),
            topLeft = Offset(bodyLeft + strokeWidth, bodyTop + strokeWidth),
            size = Size(fillWidth, (bodyHeight - strokeWidth * 2f).coerceAtLeast(0f)),
            cornerRadius = CornerRadius(h * 0.04f, h * 0.04f),
        )
    }
}

private fun DrawScope.drawToolboxIcon(color: Color) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = w * 0.065f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawRoundRect(
        color = color,
        topLeft = Offset(w * 0.16f, h * 0.36f),
        size = Size(w * 0.68f, h * 0.42f),
        cornerRadius = CornerRadius(w * 0.06f, w * 0.06f),
        style = stroke,
    )
    drawRoundRect(
        color = color,
        topLeft = Offset(w * 0.37f, h * 0.20f),
        size = Size(w * 0.26f, h * 0.18f),
        cornerRadius = CornerRadius(w * 0.04f, w * 0.04f),
        style = stroke,
    )
    drawLine(color, Offset(w * 0.16f, h * 0.50f), Offset(w * 0.84f, h * 0.50f), strokeWidth = w * 0.055f)
    drawLine(color, Offset(w * 0.42f, h * 0.43f), Offset(w * 0.42f, h * 0.57f), strokeWidth = w * 0.055f)
    drawLine(color, Offset(w * 0.58f, h * 0.43f), Offset(w * 0.58f, h * 0.57f), strokeWidth = w * 0.055f)
}

private fun DrawScope.drawPhotoIcon(color: Color) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = w * 0.065f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawRoundRect(
        color = color,
        topLeft = Offset(w * 0.16f, h * 0.22f),
        size = Size(w * 0.68f, h * 0.56f),
        cornerRadius = CornerRadius(w * 0.04f, w * 0.04f),
        style = stroke,
    )
    drawCircle(color, radius = w * 0.055f, center = Offset(w * 0.34f, h * 0.38f))
    val mountains = Path().apply {
        moveTo(w * 0.22f, h * 0.70f)
        lineTo(w * 0.42f, h * 0.50f)
        lineTo(w * 0.54f, h * 0.62f)
        lineTo(w * 0.66f, h * 0.48f)
        lineTo(w * 0.82f, h * 0.70f)
    }
    drawPath(mountains, color, style = stroke)
}

private fun DrawScope.drawMusicIcon(color: Color) {
    val w = size.width
    val h = size.height
    val strokeWidth = w * 0.075f
    drawLine(color, Offset(w * 0.60f, h * 0.20f), Offset(w * 0.60f, h * 0.66f), strokeWidth, StrokeCap.Round)
    drawLine(color, Offset(w * 0.60f, h * 0.20f), Offset(w * 0.78f, h * 0.16f), strokeWidth, StrokeCap.Round)
    drawCircle(color, radius = w * 0.15f, center = Offset(w * 0.42f, h * 0.70f))
}

private fun DrawScope.drawFilmIcon(color: Color) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = w * 0.06f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawRoundRect(
        color = color,
        topLeft = Offset(w * 0.24f, h * 0.18f),
        size = Size(w * 0.52f, h * 0.64f),
        cornerRadius = CornerRadius(w * 0.04f, w * 0.04f),
        style = stroke,
    )
    repeat(4) { index ->
        val y = h * (0.28f + index * 0.14f)
        drawLine(color, Offset(w * 0.18f, y), Offset(w * 0.28f, y), strokeWidth = w * 0.055f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.72f, y), Offset(w * 0.82f, y), strokeWidth = w * 0.055f, cap = StrokeCap.Round)
    }
}

private fun DrawScope.drawControllerIcon(color: Color) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * 0.28f, h * 0.34f)
        cubicTo(w * 0.14f, h * 0.34f, w * 0.08f, h * 0.50f, w * 0.09f, h * 0.68f)
        cubicTo(w * 0.10f, h * 0.89f, w * 0.28f, h * 0.94f, w * 0.38f, h * 0.66f)
        lineTo(w * 0.62f, h * 0.66f)
        cubicTo(w * 0.72f, h * 0.94f, w * 0.90f, h * 0.89f, w * 0.91f, h * 0.68f)
        cubicTo(w * 0.92f, h * 0.50f, w * 0.86f, h * 0.34f, w * 0.72f, h * 0.34f)
        cubicTo(w * 0.64f, h * 0.34f, w * 0.62f, h * 0.42f, w * 0.56f, h * 0.42f)
        lineTo(w * 0.44f, h * 0.42f)
        cubicTo(w * 0.38f, h * 0.42f, w * 0.36f, h * 0.34f, w * 0.28f, h * 0.34f)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawGlobeIcon(color: Color) {
    val w = size.width
    val h = size.height
    val center = Offset(w * 0.50f, h * 0.50f)
    val radius = w.coerceAtMost(h) * 0.34f
    val stroke = Stroke(width = w * 0.055f, cap = StrokeCap.Round)
    drawCircle(color, radius = radius, center = center, style = stroke)
    drawLine(color, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), w * 0.045f)
    drawLine(color, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), w * 0.045f)
    drawArc(
        color = color,
        startAngle = 90f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(center.x - radius * 0.55f, center.y - radius),
        size = Size(radius * 1.1f, radius * 2f),
        style = stroke,
    )
    drawArc(
        color = color,
        startAngle = -90f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(center.x - radius * 0.55f, center.y - radius),
        size = Size(radius * 1.1f, radius * 2f),
        style = stroke,
    )
}

private fun DrawScope.drawAppStoreIcon(color: Color) {
    val w = size.width
    val h = size.height
    val center = Offset(w * 0.50f, h * 0.50f)
    val ringRadius = w.coerceAtMost(h) * 0.30f
    val dotRadius = w * 0.085f
    val stroke = Stroke(width = w * 0.055f, cap = StrokeCap.Round)
    drawCircle(color, radius = ringRadius, center = center, style = stroke)
    val dots = listOf(270.0, 30.0, 150.0).map { degrees ->
        val radians = degrees / 180.0 * PI
        Offset(
            x = center.x + cos(radians).toFloat() * ringRadius,
            y = center.y + sin(radians).toFloat() * ringRadius,
        )
    }
    dots.forEach { dot ->
        drawLine(color, center, dot, strokeWidth = w * 0.045f, cap = StrokeCap.Round)
        drawCircle(color, radius = dotRadius, center = dot)
    }
}
