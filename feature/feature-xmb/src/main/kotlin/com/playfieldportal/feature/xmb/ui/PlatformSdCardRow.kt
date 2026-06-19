package com.playfieldportal.feature.xmb.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.domain.model.BuiltInCategory
import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.domain.model.CategoryType
import com.playfieldportal.core.ui.theme.LocalPFPColors

// ── Horizontal platform SD card browser ──────────────────────────────────────
//
// Platform categories render as SD card–shaped tiles.
// Utility categories (Favorites, Android, Settings) render as compact tabs.
//
// Selecting a platform changes the vertical list below to that platform's games.
// The XMB wave tints to the selected platform's accent color (handled in VM).

@Composable
fun PlatformSdCardRow(
    categories: List<Category>,
    selectedIndex: Int,
    platformGameCounts: Map<String, Int>,
    onPlatformSelected: (Int) -> Unit,
    onPlatformLongPress: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(selectedIndex) {
        if (categories.isNotEmpty()) {
            listState.animateScrollToItem(
                index       = selectedIndex.coerceIn(0, categories.size - 1),
                scrollOffset = -200,
            )
        }
    }

    LazyRow(
        state               = listState,
        modifier            = modifier,
        contentPadding      = PaddingValues(horizontal = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment   = Alignment.CenterVertically,
    ) {
        itemsIndexed(categories) { index, category ->
            val isSelected = index == selectedIndex
            if (category.type == CategoryType.BUILT_IN) {
                UtilityTab(
                    category   = category,
                    isSelected = isSelected,
                    onClick    = { onPlatformSelected(index) },
                )
            } else {
                PlatformSdCard(
                    category        = category,
                    isSelected      = isSelected,
                    gameCount       = platformGameCounts[category.id] ?: 0,
                    onClick         = { onPlatformSelected(index) },
                    onLongPress     = { onPlatformLongPress(index) },
                )
            }
        }
    }
}

// ── SD card tile ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlatformSdCard(
    category: Category,
    isSelected: Boolean,
    gameCount: Int,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val colors = LocalPFPColors.current
    val accentColor = category.accentColor?.let { Color(it) } ?: colors.accentColor

    val scale by animateFloatAsState(
        targetValue    = if (isSelected) 1.16f else 1f,
        animationSpec  = spring(stiffness = Spring.StiffnessMedium),
        label          = "sdCardScale",
    )
    val bgAlpha by animateFloatAsState(
        targetValue = if (isSelected) 0.28f else 0.10f,
        label       = "sdBgAlpha",
    )
    val borderAlpha by animateFloatAsState(
        targetValue = if (isSelected) 0.90f else 0.22f,
        label       = "sdBorderAlpha",
    )
    val countAlpha by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        label       = "sdCountAlpha",
    )

    val abbrev  = platformAbbrev(category.id)
    val sdShape = rememberSdCardShape()

    Box(
        modifier = Modifier
            .size(width = CARD_W, height = CARD_H)
            .scale(scale)
            // Outer glow — drawn as a blurred shadow behind the card
            .then(if (isSelected) Modifier.glowEffect(accentColor, radius = 12.dp) else Modifier)
            .clip(sdShape)
            .background(accentColor.copy(alpha = bgAlpha))
            .border(1.5.dp, accentColor.copy(alpha = borderAlpha), sdShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(4.dp),
        ) {
            // Platform abbreviation — always visible
            Text(
                text       = abbrev,
                color      = if (isSelected) Color.White else Color.White.copy(alpha = 0.55f),
                fontSize   = 13.sp,
                fontWeight = FontWeight.Bold,
                textAlign  = TextAlign.Center,
                lineHeight = 15.sp,
                maxLines   = 2,
            )
            // Game count — fades in when selected
            Text(
                text      = "$gameCount",
                color     = accentColor.copy(alpha = 0.85f),
                fontSize  = 9.sp,
                fontWeight = FontWeight.SemiBold,
                modifier  = Modifier.alpha(countAlpha),
            )
        }
    }
}

// ── Utility tab (Favorites / Android / Settings) ──────────────────────────────

@Composable
private fun UtilityTab(
    category: Category,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val scale by animateFloatAsState(
        targetValue   = if (isSelected) 1.12f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label         = "utilityScale",
    )
    val contentAlpha by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0.40f,
        label       = "utilityAlpha",
    )
    val labelAlpha by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        label       = "utilityLabelAlpha",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .scale(scale)
            .width(52.dp)
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        Text(
            text     = utilityEmoji(category.id),
            fontSize = 22.sp,
            modifier = Modifier.alpha(contentAlpha),
        )
        Text(
            text       = category.name,
            color      = Color.White.copy(alpha = labelAlpha),
            fontSize   = 9.sp,
            fontWeight = FontWeight.Medium,
            textAlign  = TextAlign.Center,
        )
    }
}

// ── Separator between card row and game list ──────────────────────────────────

@Composable
fun PlatformInfoStrip(
    platformName: String,
    gameCount: Int,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPFPColors.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.08f)),
    )
}

// ── SD card shape (top-left corner cut diagonally) ───────────────────────────

private class SdCardShapeImpl(private val cornerRadiusPx: Float) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: androidx.compose.ui.unit.Density,
    ): Outline {
        val notch  = size.width * 0.24f
        val r      = cornerRadiusPx
        val path   = Path().apply {
            moveTo(notch, 0f)
            lineTo(size.width - r, 0f)
            quadraticBezierTo(size.width, 0f, size.width, r)
            lineTo(size.width, size.height - r)
            quadraticBezierTo(size.width, size.height, size.width - r, size.height)
            lineTo(r, size.height)
            quadraticBezierTo(0f, size.height, 0f, size.height - r)
            lineTo(0f, notch)
            close()
        }
        return Outline.Generic(path)
    }
}

// Instantiated per-composable so the density-converted radius is available
@Composable
private fun rememberSdCardShape(): Shape {
    val density = LocalDensity.current
    return remember(density) { SdCardShapeImpl(with(density) { 8.dp.toPx() }) }
}

// ── Glow effect (blurred shadow drawn behind the card) ───────────────────────

private fun Modifier.glowEffect(color: Color, radius: Dp): Modifier = this.drawBehind {
    drawIntoCanvas { canvas ->
        val paint = Paint().apply {
            asFrameworkPaint().apply {
                isAntiAlias = true
                this.color = android.graphics.Color.TRANSPARENT
                setShadowLayer(radius.toPx(), 0f, 0f, android.graphics.Color.argb(
                    (0.55f * 255).toInt(),
                    (color.red * 255).toInt(),
                    (color.green * 255).toInt(),
                    (color.blue * 255).toInt(),
                ))
            }
        }
        canvas.drawRoundRect(0f, 0f, size.width, size.height, 8f, 8f, paint)
    }
}

// ── Dimensions ────────────────────────────────────────────────────────────────

private val CARD_W = 80.dp
private val CARD_H = 56.dp

// ── Platform abbreviations ────────────────────────────────────────────────────

private fun platformAbbrev(platformId: String): String = when (platformId) {
    "psx"             -> "PS1"
    "ps2"             -> "PS2"
    "ps3"             -> "PS3"
    "psp"             -> "PSP"
    "psvita"          -> "VITA"
    "nes"             -> "NES"
    "snes"            -> "SNES"
    "n64"             -> "N64"
    "gb"              -> "GB"
    "gbc"             -> "GBC"
    "gba"             -> "GBA"
    "nds"             -> "NDS"
    "n3ds"            -> "3DS"
    "gc"              -> "GCN"
    "wii"             -> "Wii"
    "wiiu"            -> "Wii U"
    "switch"          -> "NSW"
    "virtualboy"      -> "VB"
    "megadrive"       -> "GEN"
    "mastersystem"    -> "SMS"
    "gamegear"        -> "GG"
    "saturn"          -> "SAT"
    "dreamcast"       -> "DC"
    "segacd"          -> "SCD"
    "sega32x"         -> "32X"
    "atari2600"       -> "2600"
    "atari5200"       -> "5200"
    "atari7800"       -> "7800"
    "atarilynx"       -> "LYNX"
    "pcengine"        -> "PCE"
    "neogeo"          -> "NEO"
    "ngp"             -> "NGP"
    "mame"            -> "ARC"
    "wonderswan"      -> "WS"
    "wonderswancolor" -> "WSC"
    "c64"             -> "C64"
    "android"         -> "APK"
    "windows"         -> "WIN"
    else              -> platformId.take(4).uppercase()
}

private fun utilityEmoji(categoryId: String): String = when (categoryId) {
    BuiltInCategory.FAVORITES       -> "★"
    BuiltInCategory.RECENTLY_PLAYED -> "⏱"
    BuiltInCategory.ANDROID         -> "🤖"
    BuiltInCategory.SETTINGS        -> "⚙"
    else                            -> "☰"
}
