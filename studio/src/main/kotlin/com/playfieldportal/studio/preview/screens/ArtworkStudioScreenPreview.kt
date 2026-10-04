package com.playfieldportal.studio.preview.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.studio.preview.StudioIconSet
import com.playfieldportal.studio.preview.XmbBackdrop
import com.playfieldportal.studio.preview.XmbPreviewModel
import kotlin.math.ceil
import kotlin.math.floor
import com.playfieldportal.studio.preview.LocalPreviewModel
import com.playfieldportal.studio.preview.previewText
import com.playfieldportal.studio.preview.previewSubText

/*
 * The Artwork Studio for one game, replicated from feature-xmb ui/detail:
 *  - ArtworkStudioScreen.kt ArtworkStudioContent 249-973 (backdrop, header, tabs, current-art rail,
 *    source chips, match line, grid slot and tiles, footer prompts);
 *  - StudioTouchControls.kt (StudioPageLine 48-126 and StudioOptionsControl 205-227, controller mode);
 *  - ArtworkStudioViewModel.kt (STUDIO_TABS 725, StudioSource labels 53) and StudioGridCapacity.kt
 *    (the page is one measured gridful).
 *
 * The state is the launcher's own preview state (ArtworkStudioPreview.kt sampleStudioState) for the
 * sample game: ICON0 on SteamGridDB, the grid focused on its seventh tile, a title match, page 1 of 23
 * results, nothing picked, controller mode. Result tiles are drawn the way that preview draws them,
 * blank under their labels (no artwork reaches the Studio), and the current-art panel shows the
 * launcher's own "No artwork set" state, since the sample game has none.
 *
 * Theme inputs: the Studio paints its own near-opaque backdrop from PFPColors.backgroundTop (0.97) →
 * backgroundBottom over the XMB background (wallpaper/wave, via [XmbBackdrop]); every highlight is
 * menuCursorEdge(), which derives from PFPColors.accentColor (white on every theme). Text is white at
 * fixed alphas, repainted by the theme's Main / Sub text colours where the launcher's Studio calls
 * themedText / themedSubText ([previewText] / [previewSubText]); source chips, result tile labels and
 * "No artwork set" stay fixed, as they do on the device. Legibility and icons are not read.
 */

// The launcher's Text calls without a style inherit MaterialTheme's bodyLarge (24 sp line height,
// 0.5 sp tracking); PFPTheme keeps the default typography, so this is the same style.
private val BodyLarge: TextStyle = Typography().bodyLarge

// ArtworkStudioScreen STUDIO_GRID_GAP (= StudioGridCapacity GAP_DP).
private val GridGap = 8.dp

// The rail is 150 dp below STUDIO_WIDE_WINDOW_DP (1000 dp); the 832 dp baseline is below it.
private val RailWidth = 150.dp

// ── Prompt glyphs: default Xbox bindings (GamepadBinding DEFAULT_BINDINGS) ────

private const val GLYPH_A = "xmb/ctl_xb_face_south.png"       // SELECT
private const val GLYPH_B = "xmb/ctl_xb_face_east.png"        // BACK
private const val GLYPH_X = "xmb/ctl_xb_face_west.png"        // CHANGE_SORT (search)
private const val GLYPH_Y = "xmb/ctl_xb_face_north.png"       // OPEN_CONTEXT_MENU
private const val GLYPH_LB = "xmb/ctl_xb_bumper_left.png"     // PREV_CATEGORY
private const val GLYPH_RB = "xmb/ctl_xb_bumper_right.png"    // NEXT_CATEGORY

// ── Sample state ─────────────────────────────────────────────────────────────

private const val GAME_TITLE = "Crossbar Racing"

// The subtitle appends the game's platformId uppercased.
private const val PLATFORM_ID = "ps3"

/** STUDIO_TABS labels, in order. The ICON0 tab is selected. */
private val TABS = listOf(
    "ICON0", "ICON1", "BOX ART", "3D BOX", "PHYS. MEDIA", "HERO", "BACKGROUND", "LOGO", "SCREENSHOT", "MANUAL", "VIDEO",
)
private const val TAB_INDEX = 0
private const val TAB_CONTRACT = "XMB tile · 144×80 · crop"

// StudioTileClass.LANDSCAPE: the ICON0 tab's tile shape.
private const val TILE_ASPECT = 1.5
private const val TILE_MIN_WIDTH_DP = 112.0

/** StudioSource labels, in order; every source has its key, so none is disabled. SteamGridDB is selected. */
private val SOURCES = listOf("ScreenScraper", "SteamGridDB", "TheGamesDB", "IGDB", "Steam", "Local File")
private const val SOURCE_INDEX = 1

private const val TOTAL_RESULTS = 23
private const val FOCUSED_TILE = 6

/** SteamGridDB's tile label: style · W×H (the preview state's pattern). */
private fun tileLabel(i: Int): String = if (i % 4 == 3) "white_logo · 660×930" else "alternate · 600×900"

@Composable
fun ArtworkStudioScreenPreview(model: XmbPreviewModel) {
    // ArtworkStudioContent: accent = menuCursorEdge().
    val accent = model.menuCursorEdge
    Box(Modifier.fillMaxSize()) {
        XmbBackdrop(model)
        CompositionLocalProvider(LocalTextStyle provides BodyLarge, LocalPreviewModel provides model) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to model.backgroundTop.copy(alpha = 0.97f),
                            1f to model.backgroundBottom,
                        ),
                    ),
            ) {
                Column(Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 14.dp)) {
                    StudioHeader(accent)
                    StudioTabs(accent)
                    Row(Modifier.weight(1f)) {
                        CurrentArtRail()
                        Spacer(Modifier.width(18.dp))
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            SourceChips(accent)
                            Spacer(Modifier.height(6.dp))
                            MatchLine()
                            Spacer(Modifier.height(6.dp))
                            ResultGrid(accent, Modifier.fillMaxWidth().weight(1f))
                        }
                    }
                    FooterPrompts()
                }
            }
        }
    }
}

// ── Header row (36 dp): back, title, the query field, Options ────────────────

@Composable
private fun StudioHeader(accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(36.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("◀", color = previewSubText(Color.White.copy(alpha = 0.55f)), fontSize = 14.sp, modifier = Modifier.padding(end = 12.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    GAME_TITLE,
                    color = previewText(Color.White), fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 320.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Artwork Studio · ${PLATFORM_ID.uppercase()}",
                    color = previewSubText(Color.White.copy(alpha = 0.55f)), fontSize = 11.sp,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        // The query field: the game's own title, so no accent border and the "game title" caption.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(start = 12.dp)
                .widthIn(max = 300.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.10f))
                .padding(horizontal = 10.dp),
        ) {
            GlyphOnly(GLYPH_X, 13.dp)
            Text(
                GAME_TITLE,
                color = previewText(Color.White.copy(alpha = 0.92f)),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).padding(start = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            Text("game title", color = previewSubText(Color.White.copy(alpha = 0.45f)), fontSize = 9.sp)
        }
        // StudioOptionsControl, controller mode: the Y hint.
        Prompt(
            glyph = GLYPH_Y,
            label = "Options",
            glyphSize = 13.dp,
            labelColor = previewSubText(Color.White.copy(alpha = 0.6f)),
            labelStyle = TextStyle(fontSize = 9.5.sp),
            modifier = Modifier.padding(start = 12.dp).padding(vertical = 4.dp),
        )
    }
}

// ── Destination tabs (28 dp): LB, the chip row, RB ───────────────────────────

@Composable
private fun StudioTabs(accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(28.dp)) {
        GlyphOnly(GLYPH_LB, 14.dp, Modifier.padding(end = 6.dp))
        // A LazyRow scrolled to the selected (first) tab: chips past the edge are clipped.
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState(), enabled = false),
        ) {
            TABS.forEachIndexed { index, label ->
                val selected = index == TAB_INDEX
                Box(
                    modifier = Modifier
                        .height(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selected) accent.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.07f))
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = previewText(if (selected) Color.White else Color.White.copy(alpha = 0.62f)),
                        fontSize = 10.5.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
        GlyphOnly(GLYPH_RB, 14.dp, Modifier.padding(start = 6.dp))
    }
}

// ── Current artwork rail (150 dp) ────────────────────────────────────────────

@Composable
private fun CurrentArtRail() {
    Column(Modifier.width(RailWidth).fillMaxHeight()) {
        Column(Modifier.weight(1f)) {
            Text(TABS[TAB_INDEX], color = previewText(Color.White), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(TAB_CONTRACT, color = previewSubText(Color.White.copy(alpha = 0.55f)), fontSize = 9.5.sp, lineHeight = 12.sp)
            Spacer(Modifier.height(6.dp))
            // The thumbnail, in the tab's tile shape.
            Box(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .aspectRatio(TILE_ASPECT.toFloat())
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF080E1E).copy(alpha = 0.55f))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("No artwork set", color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
            }
        }
    }
}

// ── Sources row (24 dp) ──────────────────────────────────────────────────────

@Composable
private fun SourceChips(accent: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().height(24.dp).horizontalScroll(rememberScrollState()),
    ) {
        SOURCES.forEachIndexed { index, label ->
            val selected = index == SOURCE_INDEX
            // The grid holds the cursor, so the selected chip has no focus border.
            Box(
                modifier = Modifier
                    .height(24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (selected) accent.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.07f))
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (selected) Color.White else Color.White.copy(alpha = 0.62f),
                    fontSize = 10.5.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
        // SteamGridDB is active: its mature filter's badge.
        Box(
            modifier = Modifier
                .padding(start = 4.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Color.Black.copy(alpha = 0.18f))
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Mature off", color = previewText(Color.White.copy(alpha = 0.6f)), fontSize = 9.sp, lineHeight = 12.sp, maxLines = 1)
        }
    }
}

// ── Match line (22 dp) ───────────────────────────────────────────────────────

private val MatchGreen = Color(0xFF66BB6A)

@Composable
private fun MatchLine() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(22.dp)) {
        CheckMark(MatchGreen, 12.dp)
        Spacer(Modifier.width(7.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Text("Matched as ", color = previewSubText(Color.White.copy(alpha = 0.75f)), fontSize = 10.5.sp, maxLines = 1)
            Text(
                GAME_TITLE,
                color = previewText(Color.White), fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        // Not confirmed, so no FORGET; SteamGridDB has title search, so CHANGE MATCH is live.
        Box(
            modifier = Modifier.fillMaxHeight().clip(RoundedCornerShape(6.dp)).padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("CHANGE MATCH", color = previewText(Color.White), fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** core-ui PfpCheckMark: the path mark, stroke 0.14 of its size. */
@Composable
private fun CheckMark(color: Color, markSize: Dp) {
    Canvas(Modifier.size(markSize)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.21f, h * 0.52f)
            lineTo(w * 0.40f, h * 0.71f)
            lineTo(w * 0.79f, h * 0.31f)
        }
        drawPath(
            path, color,
            style = Stroke(width = size.minDimension * 0.14f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

// ── Grid slot + page line ────────────────────────────────────────────────────

/** StudioGridCapacity.of: how many tiles of the tab's class fit the measured slot. */
private data class GridCapacity(val columns: Int, val rows: Int) {
    companion object {
        private const val GAP = 8.0
        private const val EPSILON = 1e-6

        private fun fits(span: Double, tile: Double): Int = floor((span + GAP) / (tile + GAP) + EPSILON).toInt()

        fun of(widthDp: Float, heightDp: Float): GridCapacity {
            val columns = fits(widthDp.toDouble(), TILE_MIN_WIDTH_DP).coerceIn(3, 8)
            val tileWidth = ((widthDp - GAP * (columns - 1)) / columns).coerceAtLeast(0.0)
            val rows = fits(heightDp.toDouble(), tileWidth / TILE_ASPECT).coerceIn(1, 6)
            return GridCapacity(columns, rows)
        }
    }
}

// The page line under the slot: 6 dp gap, then a 16 dp band (controller mode).
private val PageLineGap = 6.dp
private val PageLineHeight = 16.dp

/**
 * The grid slot (whatever height is left) and the page line under it. The slot's measured size
 * decides the page (one gridful); tiles take the tab's aspect, capped at an even share of the height.
 */
@Composable
private fun ResultGrid(accent: Color, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val slotWidth = maxWidth
        val slotHeight = (maxHeight - PageLineGap - PageLineHeight).coerceAtLeast(0.dp)
        val capacity = GridCapacity.of(slotWidth.value, slotHeight.value)
        val columns = capacity.columns
        val rows = capacity.rows
        val pageSize = columns * rows
        val tileWidth = (slotWidth - GridGap * (columns - 1)) / columns
        val tileHeight = maxOf(
            0.dp,
            minOf(tileWidth / TILE_ASPECT.toFloat(), (slotHeight - GridGap * (rows - 1)) / rows),
        )
        val onPage = minOf(pageSize, TOTAL_RESULTS)
        val focused = minOf(FOCUSED_TILE, onPage - 1)
        val pageCount = ceil(TOTAL_RESULTS / pageSize.toDouble()).toInt()
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(slotHeight)) {
                // LazyVerticalGrid(Fixed(columns)), 8 dp spacing both ways, not scrollable.
                Column(verticalArrangement = Arrangement.spacedBy(GridGap)) {
                    for (r in 0 until rows) {
                        val first = r * columns
                        if (first >= onPage) break
                        Row(horizontalArrangement = Arrangement.spacedBy(GridGap)) {
                            for (c in 0 until columns) {
                                val i = first + c
                                if (i >= onPage) break
                                ResultTile(tileLabel(i), i == focused, accent, Modifier.width(tileWidth).height(tileHeight))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(PageLineGap))
            PageLine(rangeEnd = onPage, pageCount = pageCount)
        }
    }
}

/** A result tile with no art: the dark tile, its border (2 dp accent when focused), the label strip. */
@Composable
private fun ResultTile(label: String, focused: Boolean, accent: Color, modifier: Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF10101A))
            .border(
                if (focused) 2.dp else 1.dp,
                if (focused) accent else Color.White.copy(alpha = 0.1f),
                RoundedCornerShape(8.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, color = Color.White.copy(alpha = 0.85f), fontSize = 9.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))
                .padding(horizontal = 5.dp, vertical = 3.dp),
        )
    }
}

/** StudioPageLine, controller mode, on page 1 with nothing picked: the range, then LB ‹ Page › RB. */
@Composable
private fun PageLine(rangeEnd: Int, pageCount: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(PageLineHeight)) {
        Text(
            "1–$rangeEnd of $TOTAL_RESULTS",
            color = previewSubText(Color.White.copy(alpha = 0.6f)), fontSize = 9.5.sp, lineHeight = 12.sp,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        if (pageCount > 1) {
            GlyphOnly(GLYPH_LB, 12.dp)
            PageArrow("‹", enabled = false)
            Text(
                "Page 1 / $pageCount",
                color = previewSubText(Color.White.copy(alpha = 0.6f)), fontSize = 9.5.sp, lineHeight = 12.sp,
                maxLines = 1,
            )
            PageArrow("›", enabled = true)
            GlyphOnly(GLYPH_RB, 12.dp)
        }
    }
}

@Composable
private fun PageArrow(glyph: String, enabled: Boolean) {
    Text(
        glyph,
        color = previewText(Color.White.copy(alpha = if (enabled) 0.85f else 0.3f)),
        fontSize = 11.sp, fontWeight = FontWeight.SemiBold, lineHeight = 12.sp,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).padding(horizontal = 8.dp),
    )
}

// ── Footer prompts (GRID zone, single-art tab, no changes waiting) ───────────

/**
 * ControllerPromptBar under the columns: 14 dp glyphs, 10 sp labels at white 0.35 (Sub), 14 dp apart. The
 * Row is not width-filling, so it sits at the start edge.
 */
@Composable
private fun FooterPrompts() {
    val labelColor = previewSubText(Color.White.copy(alpha = 0.35f))
    val labelStyle = TextStyle(fontSize = 10.sp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
        modifier = Modifier.padding(top = 6.dp),
    ) {
        Prompt(GLYPH_A, "Preview / Apply", 14.dp, labelColor, labelStyle)
        Prompt(GLYPH_B, "Back", 14.dp, labelColor, labelStyle)
        Prompt(GLYPH_X, "Search", 14.dp, labelColor, labelStyle)
        Prompt(GLYPH_Y, "Options", 14.dp, labelColor, labelStyle)
    }
}

// ── Prompt rendering (core-ui ControllerPrompt → PromptRow) ──────────────────

/** A glyph, 4 dp, then the label in [labelStyle] (the style replaces bodyLarge, as Text(style=) does). */
@Composable
private fun Prompt(
    glyph: String,
    label: String,
    glyphSize: Dp,
    labelColor: Color,
    labelStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Image(StudioIconSet.chromePainter(glyph), contentDescription = null, modifier = Modifier.size(glyphSize))
        Text(text = label, color = labelColor, style = labelStyle)
    }
}

/**
 * ControllerPrompt with an empty label: the glyph and the row's 4 dp spacing before the empty Text,
 * which still takes the 4 dp.
 */
@Composable
private fun GlyphOnly(glyph: String, glyphSize: Dp, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Image(StudioIconSet.chromePainter(glyph), contentDescription = null, modifier = Modifier.size(glyphSize))
        Spacer(Modifier.width(4.dp))
    }
}
