package com.playfieldportal.studio.preview.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.studio.preview.StudioIconSet
import com.playfieldportal.studio.preview.WaveBackground
import com.playfieldportal.studio.preview.WaveMotion
import com.playfieldportal.studio.preview.XmbPreviewModel
import kotlin.math.pow
import com.playfieldportal.core.ui.theme.ThemeTokens
import com.playfieldportal.core.ui.theme.StorefrontColors
import com.playfieldportal.core.ui.theme.storefrontColorsFor
import com.playfieldportal.core.ui.theme.dimmed
import com.playfieldportal.core.ui.theme.unselectedLabel

// ── App Drawer (feature-appbar AppDrawerScreen.kt + appdrawer/*) ──────────────────────────────
//
// The launcher's PSP-era grid drawer as it stands right after "Android ▸ All Apps" opens it from
// the XMB (XMBShell: `uiState.activeAppDrawerFilter?.let { AppDrawerScreen(...) }`): ALL filter,
// empty search, cursor on the first tile, controller idle (hint pill up). Static frame — every
// launcher animation (selection cross-fade, underline slide, hint fade) is drawn at its target.
//
// Theme inputs, exactly as the launcher reads them: the drawer never reads iconColor, legibility or
// icon overrides; the Main / Sub text colours reach it only through its palette (storefrontColorsFor
// replaces the text roles; the icon roles keep their own tones). Its whole palette comes from
// core-ui StorefrontColors.storefrontColorsFor(PFPColors), i.e. waveColor (+ the white accentColor)
// and backgroundTop/backgroundBottom, over the XMB background (wallpaper + scrim, or the wave).

// ── Shared with AppPickerScreenPreview (same launcher building blocks) ────────────────────────

/**
 * Material3's default bodyLarge — the LocalTextStyle the launcher's `MaterialTheme(colorScheme =
 * PfpDarkColorScheme)` (PFPTheme.kt, no custom typography) provides, so every `Text(fontSize = …)`
 * without an explicit style merges onto it: 24sp line height, 0.5sp tracking, centred line-height
 * style (M3 DefaultTextStyle).
 */
internal val DrawerBodyLarge = TextStyle(
    fontWeight = FontWeight.Normal,
    fontSize = 16.sp,
    lineHeight = 24.sp,
    letterSpacing = 0.5.sp,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    ),
)

/**
 * The drawer's palette, as the launcher derives it ([storefrontColorsFor], theme-render) from the
 * model's PFPColors; recomputed only when one of its inputs changes. The drawer and both pickers
 * draw from it.
 */
@Composable
internal fun rememberDrawerPalette(model: XmbPreviewModel): StorefrontColors =
    remember(model.pfp) { storefrontColorsFor(model.pfp) }

/** AppDrawerHeader / AppPickerHeader HEADER_HEIGHT. */
internal val DrawerHeaderHeight = 56.dp

/** AppDrawerHeader's hand-drawn magnifier (Canvas 18 dp, 1.8 dp stroke) — the launcher's own path. */
@Composable
internal fun DrawerMagnifier(color: Color) {
    Canvas(modifier = Modifier.size(18.dp)) {
        val strokeW = 1.8f.dp.toPx()
        val cx = size.width * 0.42f
        val cy = size.height * 0.42f
        val r = size.width * 0.30f
        drawCircle(color = color, radius = r, center = Offset(cx, cy), style = Stroke(strokeW))
        drawLine(
            color = color,
            start = Offset(cx + r * 0.70f, cy + r * 0.70f),
            end = Offset(cx + r * 0.70f + size.width * 0.22f, cy + r * 0.70f + size.height * 0.22f),
            strokeWidth = strokeW,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * An installed app's launcher icon (DrawablePainter(app.icon) at the artwork size). Real icons are
 * not available to the Studio: the same neutral round stand-in the XMB preview uses (AppIconStandIn,
 * 22 sp letter at 48 dp), scaled to fill the launcher's icon box like an adaptive icon does.
 */
@Composable
internal fun DrawerAppIcon(label: String, size: Dp, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(size).clip(CircleShape).background(Color(0xFFE9ECF2)),
    ) {
        Text(
            text = (label.firstOrNull()?.uppercaseChar() ?: '?').toString(),
            color = Color(0xFF3B4A66),
            fontSize = (22f * size.value / 48f).sp,
            fontWeight = FontWeight.Bold,
            style = TextStyle.Default,
        )
    }
}

/**
 * core-ui PromptRow for one resolved Xbox glyph: glyph (ControllerIconGlyph, an Image at [glyphSize]),
 * 4 dp, then the label in [labelStyle] (an explicit style, so LocalTextStyle does not merge).
 */
@Composable
internal fun DrawerPrompt(glyph: String, label: String, labelColor: Color, labelStyle: TextStyle, glyphSize: Dp) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Image(StudioIconSet.chromePainter(glyph), contentDescription = null, modifier = Modifier.size(glyphSize))
        Text(text = label, color = labelColor, style = labelStyle)
    }
}

/**
 * AppDrawerGridItem.adaptiveArtworkSize / AppPickerScreen.pickerAdaptiveArtworkSize: three rows must
 * fit; the artwork shrinks from 72 dp toward the 48 dp floor. Row spacing 14, grid padding 2×14.
 */
internal fun drawerAdaptiveArtworkSize(viewportHeight: Dp): Dp {
    val tileFixedHeight = DrawerFrameRoom + 6.dp + 30.dp + 8.dp
    val rowHeight = (viewportHeight - 28.dp - 14.dp * 2) / 3
    return (rowHeight - tileFixedHeight).coerceIn(48.dp, 72.dp)
}

/** FRAME_ROOM: outer border + 2 dp gap + inner hairline on each side. */
internal val DrawerFrameRoom = 8.dp

/**
 * The grid's visible rows: a LazyVerticalGrid scrolled to the top (contentPadding 32/14, spacing
 * 10/14) clipped to its viewport. Rows are laid out unbounded so a cut-off row keeps its real
 * height, as a lazy grid's does.
 */
@Composable
internal fun <T> DrawerStaticGrid(
    items: List<T>,
    columns: Int,
    maxRows: Int,
    tile: @Composable (index: Int, item: T) -> Unit,
) {
    Box(Modifier.fillMaxSize().clipToBounds()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(Alignment.Top, unbounded = true)
                .padding(horizontal = 32.dp, vertical = 14.dp),
        ) {
            items.take(columns * maxRows).chunked(columns).forEachIndexed { r, rowItems ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    for (c in 0 until columns) {
                        Box(Modifier.weight(1f)) {
                            if (c < rowItems.size) tile(r * columns + c, rowItems[c])
                        }
                    }
                }
            }
        }
    }
}

/** The tile label: AppDrawerGridItem / AppPickerTile `Text(11.sp, maxLines 2, lineHeight 13.sp)`. */
@Composable
internal fun DrawerTileLabel(label: String, color: Color) {
    Text(
        text = label,
        color = color,
        fontSize = 11.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        lineHeight = 13.sp,
    )
}

/** Sample installed apps, sorted by label as InstalledAppRepository does (`sortedBy { label.lowercase() }`). */
internal val DrawerSampleApps: List<String> = listOf(
    "Calculator", "Calendar", "Camera", "Chrome", "Clock", "Discord", "Dolphin", "DuckStation",
    "Files", "Firefox", "Gallery", "Gmail", "Maps", "Netflix", "Play Store", "PPSSPP",
    "RetroArch", "Settings", "Spotify", "Steam Link", "Twitch", "YouTube", "Yuzu", "Zoom",
).sortedBy { it.lowercase() }

// ── Drawer-only constants ────────────────────────────────────────────────────────────────────

// AppDrawerViewModel GRID_COLUMNS.
private const val DrawerColumns = 6

// AppFilter labels in entry order, with sample counts (RECENT 0 — the count is hidden at 0).
private val DrawerTabs = listOf("All Apps" to 50, "Games" to 22, "Emulators" to 18, "Recently Used" to 0)

// AppDrawerHintBar items, Xbox glyphs for the default bindings (GamepadBinding: L1 PREV_CATEGORY,
// R1 NEXT_CATEGORY, B BACK, A SELECT, Y OPEN_CONTEXT_MENU, X CHANGE_SORT).
private val DrawerHints = listOf(
    "xmb/ctl_xb_bumper_left.png" to "Prev",
    "xmb/ctl_xb_bumper_right.png" to "Next",
    "xmb/ctl_xb_face_east.png" to "Back",
    "xmb/ctl_xb_face_south.png" to "Launch",
    "xmb/ctl_xb_face_north.png" to "Options",
    "xmb/ctl_xb_face_west.png" to "Search",
)

// ControllerHintBar (non-compact): 14 sp SemiBold white with the classic drop shadow.
private val DrawerHintLabel = TextStyle(
    fontSize = 14.sp,
    fontWeight = FontWeight.SemiBold,
    shadow = ThemeTokens.TextShadow,
)

// XmbPreviewCanvas WallpaperScrim (WallpaperBackground's legibility scrim).
private val DrawerWallpaperScrim = ThemeTokens.WallpaperScrim

@Composable
fun AppDrawerScreenPreview(model: XmbPreviewModel) {
    val sf = rememberDrawerPalette(model)
    Box(Modifier.fillMaxSize()) {
        // XMBShell hides the XMB foreground while the drawer is open and freezes the wave
        // (waveCovered) / releases the motion wallpaper (poster only): a still background.
        DrawerFrozenBackdrop(model)
        CompositionLocalProvider(LocalTextStyle provides DrawerBodyLarge) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(sf.backgroundDeep, sf.backgroundMid))),
            ) {
                Column(Modifier.fillMaxSize()) {
                    DrawerHeader(sf)
                    // Thin accent divider under the header.
                    Box(Modifier.fillMaxWidth().height(1.dp).background(sf.chromeDivider))
                    // AppDrawerCategoryTabs' unselected tab: themedText(textSecondary @ 0.65).
                    DrawerCategoryTabs(sf, tabUnselected = model.textOr(sf.textSecondary.copy(alpha = 0.65f)))
                    BoxWithConstraints(Modifier.weight(1f)) {
                        val artwork = drawerAdaptiveArtworkSize(maxHeight)
                        DrawerStaticGrid(DrawerSampleApps, DrawerColumns, maxRows = 4) { index, label ->
                            DrawerGridTile(label, selected = index == 0, artwork = artwork, sf = sf, unselected = sf.unselectedLabel(model.pfp))
                        }
                    }
                    // Permanent footer: the idle hint pill (shown — the controller is idle).
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        DrawerHintPill()
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerFrozenBackdrop(model: XmbPreviewModel) {
    val wallpaper = model.wallpaper
    if (wallpaper != null) {
        Image(
            bitmap = wallpaper,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(DrawerWallpaperScrim))
    } else {
        val params = remember(model.waveStyle) { WaveMotion.paramsFor(model.waveStyle) }
        WaveBackground(model, params) { WaveMotion.STATIC_TIME }
    }
}

// AppDrawerHeader: ‹ Android › <category> on the left, the magnifier + "Search" on the right
// (the inline field only shows while search is active).
@Composable
private fun DrawerHeader(sf: StorefrontColors) {
    Row(
        modifier = Modifier.fillMaxWidth().height(DrawerHeaderHeight).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Text(text = "‹", color = sf.textSecondary, fontSize = 18.sp, modifier = Modifier.padding(end = 8.dp))
            Text(text = "Android", color = sf.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(end = 6.dp))
            Text(
                text = "›",
                color = sf.textSecondary.dimmed(0.6f),
                fontSize = 14.sp,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(text = "All Apps", color = sf.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            DrawerMagnifier(sf.iconSecondary)
            Spacer(Modifier.width(5.dp))
            Text("Search", color = sf.textSecondary, fontSize = 13.sp)
        }
    }
}

// AppDrawerCategoryTabs: 32 dp side padding, 28 dp between tabs; selected = primary text + a 2 dp
// categorySelectedEdge underline the width of the label row; unselected = secondary @ 0.65
// (themedText: the Main colour at 0.65 once set).
@Composable
private fun DrawerCategoryTabs(sf: StorefrontColors, tabUnselected: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DrawerTabs.forEachIndexed { i, (label, count) ->
            val selected = i == 0
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(vertical = 12.dp),
            ) {
                val underline = sf.categorySelectedEdge
                Box(
                    contentAlignment = Alignment.BottomStart,
                    modifier = if (!selected) Modifier else Modifier.drawBehind {
                        val h = 2.dp.toPx()
                        // Drawn after the row in the launcher; the two never overlap (5 dp row padding).
                        drawRect(underline, topLeft = Offset(0f, size.height - h), size = Size(size.width, h))
                    },
                ) {
                    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(bottom = 5.dp)) {
                        Text(
                            text = label.uppercase(),
                            color = if (selected) sf.textPrimary else tabUnselected,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (count > 0) {
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = count.toString(),
                                color = if (selected) sf.textPrimary.copy(alpha = 0.85f) else sf.textSecondary.dimmed(0.55f),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}

// AppDrawerGridItem: bare artwork; the selected tile adds the selectionGlow plate, a 1 dp
// tileSelectedEdge border and a 1 dp tileSelectedInner hairline inset 2 dp; label secondary → primary.
@Composable
private fun DrawerGridTile(label: String, selected: Boolean, artwork: Dp, sf: StorefrontColors, unselected: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Box(modifier = Modifier.size(artwork + DrawerFrameRoom)) {
            if (selected) {
                Box(Modifier.matchParentSize().background(sf.selectionGlow))
                Box(Modifier.matchParentSize().border(1.dp, sf.tileSelectedEdge.copy(alpha = 1f)))
                Box(Modifier.matchParentSize().padding(2.dp).border(1.dp, sf.tileSelectedInner.copy(alpha = 1f)))
            }
            DrawerAppIcon(label, artwork, Modifier.align(Alignment.Center))
        }
        Spacer(Modifier.height(6.dp))
        DrawerTileLabel(label, if (selected) sf.textPrimary else unselected)
    }
}

// AppDrawerHintBar → ControllerHintBar(background = black @ 0.70): 10 dp corners, 14×8 dp padding,
// 16 dp between prompts, 20 dp glyphs.
@Composable
private fun DrawerHintPill() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.70f), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        for ((glyph, label) in DrawerHints) {
            DrawerPrompt(glyph, label, Color.White, DrawerHintLabel, 20.dp)
        }
    }
}
