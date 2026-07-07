package com.playfieldportal.studio.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.playfieldportal.themekit.XmbLayoutSpec
import kotlin.math.min
import kotlin.math.sin

/*
 * A static, faithful frame of the launcher's XMB, replicated from the launcher sources so
 * "what you author is what the phone renders". Every constant here mirrors a named source:
 *   background/wave/bloom — feature-xmb XmbBackground.kt
 *   crossbar geometry     — feature-xmb XMBShell.kt + XMBCategoryBar.kt
 *   item rows             — feature-xmb XMBItemList.kt
 *   sizes/fractions       — theme-kit XmbLayoutSpec.DEFAULT (shared, not re-typed)
 */

// Design box the frame is authored at; the canvas fit-scales it into whatever space it gets.
private val DESIGN_WIDTH = 960.dp
private val DESIGN_HEIGHT = 540.dp

// XMBCategoryBar.kt
private val CategorySlotWidth = 124.dp
private val XmbLeftAnchor = CategorySlotWidth + XmbLayoutSpec.DEFAULT.leftAnchorExtraDp.dp
private val CatBarHeight = 112.dp
private val LabelInactive = Color(0xCCD8E6FF)
private val SelectedLabelShadow = Shadow(color = Color(0x73001627), offset = Offset.Zero, blurRadius = 12f)

// XMBItemList.kt
private val RowHeight = 88.dp
private val LeadingIconSlot = XmbLayoutSpec.DEFAULT.itemIconSlotDp.dp
private val LeadingIconCenter = 18.dp + LeadingIconSlot / 2

// XmbBackground.kt
private const val STATIC_TIME = 2.0f
private const val TAU = 6.2831853f
private val WallpaperScrim = Color(0x59000000)

@Composable
fun XmbPreviewCanvas(model: XmbPreviewModel, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val scale = min(maxWidth / DESIGN_WIDTH, maxHeight / DESIGN_HEIGHT)
        Box(
            Modifier
                .requiredSize(DESIGN_WIDTH, DESIGN_HEIGHT)
                .graphicsLayer(scaleX = scale, scaleY = scale)
                .clipToBounds(),
        ) {
            XmbFrame(model)
        }
    }
}

/** The full frame at design size — also rendered offscreen for the bundle's preview.png. */
@Composable
fun XmbFrame(model: XmbPreviewModel) {
    Box(Modifier.fillMaxSize()) {
        if (model.wallpaper != null) {
            // WallpaperBackground: image fills, plus the legibility scrim. No wave.
            Image(
                bitmap = model.wallpaper,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(WallpaperScrim))
        } else {
            WaveBackground(model)
        }
        XmbCross(model)
        StatusStrip(model)
    }
}

@Composable
private fun WaveBackground(model: XmbPreviewModel) {
    // The launcher's exact gradient call — including its default (diagonal) direction.
    val gradient = Brush.linearGradient(
        colorStops = arrayOf(
            0.00f to model.backgroundTop,
            0.30f to model.backgroundTop,
            0.70f to lerp(model.backgroundTop, model.backgroundBottom, 0.5f),
            1.00f to model.backgroundBottom,
        ),
    )
    val alphaScale = if (model.reducedWave) 0.5f else 1f
    val ampScale = if (model.reducedWave) 0.65f else 1f
    Box(Modifier.fillMaxSize().background(gradient)) {
        Canvas(Modifier.fillMaxSize()) {
            // FallbackWave frozen at the launcher's static pose.
            val amp = 0.05f * ampScale
            drawFold(STATIC_TIME, base01 = 0.63f, amp01 = amp * 0.9f, freq = 0.80f, phase = 1.7f, drift = -0.38f, sheet = 0.090f * alphaScale, edge = 0.125f * alphaScale)
            drawFold(STATIC_TIME, base01 = 0.75f, amp01 = amp * 1.2f, freq = 0.42f, phase = 3.1f, drift = 0.30f, sheet = 0.105f * alphaScale, edge = 0.145f * alphaScale)
            // Soft off-centre light bloom.
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.10f), Color.Transparent),
                    center = center.copy(x = size.width * 0.48f, y = size.height * 0.30f),
                    radius = size.minDimension * 0.62f,
                ),
            )
        }
    }
}

// Verbatim port of XmbBackground.drawFold.
private fun DrawScope.drawFold(
    t: Float, base01: Float, amp01: Float, freq: Float, phase: Float, drift: Float,
    sheet: Float, edge: Float,
) {
    val w = size.width
    val h = size.height
    val n = 48
    val crestPath = Path()
    val fillPath = Path()
    fillPath.moveTo(0f, h)
    for (i in 0..n) {
        val xx = i / n.toFloat()
        val y = (base01 + amp01 * sin(xx * TAU * freq + t * drift + phase)) * h
        val x = xx * w
        if (i == 0) { crestPath.moveTo(x, y); fillPath.lineTo(x, y) } else { crestPath.lineTo(x, y); fillPath.lineTo(x, y) }
    }
    fillPath.lineTo(w, h)
    fillPath.close()
    drawPath(fillPath, color = Color.White.copy(alpha = sheet))
    drawPath(crestPath, color = Color.White.copy(alpha = edge * 0.5f), style = Stroke(width = h * 0.022f))
    drawPath(crestPath, color = Color.White.copy(alpha = edge), style = Stroke(width = h * 0.006f))
}

@Composable
private fun XmbCross(model: XmbPreviewModel) {
    val spec = XmbLayoutSpec.DEFAULT
    Box(Modifier.fillMaxSize().padding(top = spec.contentTopPaddingDp.dp)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val barTop = maxHeight * spec.barTopFraction
            val anchorTop = barTop + CatBarHeight

            // ── Category bar: the selected slot seats at the left anchor; earlier
            //    categories tile leftward (mostly off-screen), later ones rightward. ──
            val barStart = XmbLeftAnchor - CategorySlotWidth * SampleContent.SELECTED_CATEGORY
            Row(Modifier.offset(x = barStart, y = barTop).height(CatBarHeight)) {
                SampleContent.categories.forEachIndexed { index, category ->
                    CategoryCell(model, category, selected = index == SampleContent.SELECTED_CATEGORY)
                }
            }

            // ── Item column under the caticon (XMBShell startPad math). ──
            val startPad = XmbLeftAnchor + (CategorySlotWidth / 2) - LeadingIconCenter
            Column(Modifier.offset(x = startPad, y = anchorTop)) {
                SampleContent.rows.forEachIndexed { index, row ->
                    ItemRow(model, row, selected = index == SampleContent.SELECTED_ROW)
                }
            }
        }
    }
}

@Composable
private fun CategoryCell(model: XmbPreviewModel, category: SampleContent.Category, selected: Boolean) {
    val spec = XmbLayoutSpec.DEFAULT
    val iconSize = if (selected) spec.categoryIconSelectedDp.dp else spec.categoryIconDp.dp
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(CategorySlotWidth).height(CatBarHeight).padding(top = 4.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(82.dp).alpha(if (selected) 1f else 0.58f),
        ) {
            SlotIcon(model, category.slotKey, Modifier.size(iconSize))
        }
        Text(
            text = category.label,
            color = if (selected) Color.White else LabelInactive,
            fontSize = if (selected) 15.sp else 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            style = if (selected) TextStyle(shadow = SelectedLabelShadow) else TextStyle.Default,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).alpha(if (selected) 1f else 0.82f),
        )
    }
}

@Composable
private fun ItemRow(model: XmbPreviewModel, row: SampleContent.Row, selected: Boolean) {
    val spec = XmbLayoutSpec.DEFAULT
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(RowHeight)
            .graphicsLayer(
                scaleX = if (selected) 1.06f else 0.9f,
                scaleY = if (selected) 1.06f else 0.9f,
                alpha = if (selected) 1f else 0.68f,
                // Scale pivots on the leading-icon centre so icons stay on the caticon line.
                transformOrigin = TransformOrigin(0f, 0.5f),
            )
            .padding(horizontal = 18.dp),
    ) {
        Box(Modifier.size(LeadingIconSlot), contentAlignment = Alignment.Center) {
            SlotIcon(model, row.slotKey, Modifier.size(spec.itemIconDp.dp * 0.75f))
        }
        Spacer(Modifier.width(spec.itemTextStartGapDp.dp))
        Column {
            Text(
                text = row.title,
                color = Color.White,
                fontSize = if (selected) spec.itemTextSelectedSp.sp else spec.itemTextSp.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
            )
            if (row.subtitle != null && selected) {
                Text(text = row.subtitle, color = Color(0xCCD8E6FF), fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.StatusStrip(model: XmbPreviewModel) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 16.dp),
    ) {
        Text("21:30", color = Color.White, fontSize = 13.sp)
        Spacer(Modifier.width(8.dp))
        SlotIcon(model, "status_bluetooth", Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        SlotIcon(model, "status_battery_full", Modifier.width(24.dp).height(11.dp))
    }
}

/**
 * One draw path for every slot: a custom icon renders as-authored (untinted, like PSP theme
 * icons); the built-in glyph follows the unified icon color via SrcIn — the PortalIcon rule.
 */
@Composable
private fun SlotIcon(model: XmbPreviewModel, key: String, modifier: Modifier) {
    val override = model.iconOverrides[key]
    if (override != null) {
        Image(bitmap = override, contentDescription = null, modifier = modifier)
    } else {
        val painter: Painter = StudioIconSet.defaultPainter(key)
        Image(
            painter = painter,
            contentDescription = null,
            colorFilter = ColorFilter.tint(model.iconTint, BlendMode.SrcIn),
            modifier = modifier,
        )
    }
}
