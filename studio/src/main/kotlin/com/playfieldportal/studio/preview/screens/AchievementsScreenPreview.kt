package com.playfieldportal.studio.preview.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.studio.preview.XmbPreviewModel
import kotlin.math.roundToInt

/*
 * "The achievement screen": the Shiba Coins library in its Tracked Games view (feature-xmb
 * ui/detail/ShibaLibraryScreen.kt, TRACKED mode) — what "All Tracked Games" opens from the Shiba
 * Coins category (XMBViewModel ACH_ALL_ITEM_ID -> openShibaLibrary(TRACKED)). Its breadcrumb reads
 * "Achievements / Tracked Games" and it is the screen docs/plans/PFP_Achievements_Screen_Design.md
 * specifies; the player card's Player Status view is a recent-unlocks summary instead.
 *
 * Shown as it opens: Search online pinned first, the five tracked games in Title A–Z order, the
 * cursor on the first game (ShibaLibraryViewModel focusFirstRow), the list at its top (the 1/3-line
 * snap clamps there), controller hints up.
 *
 * Sources: ShibaLibraryScreen.kt (header summary, rows, Search online tile), ShibaLibraryViewModel.kt
 * (rows, summary, platformDisplay, helper items), ShibaDetailParts.kt, ShibaCoinArt.kt, GameIconView
 * PspIcon0Icon, core-ui achievement/ShibaPlayerCard.kt ShibaLevelMedallion, core-ui detail/ (all of it).
 *
 * Theme inputs: wave colour + background anchors (detailPaletteFor: page, accent, text, rows, ICON0
 * tile accent, level medallion), the wallpaper / frozen wave behind the page, the shiba_coin_* and
 * menu_back icon overrides, and the Main / Sub text colours through the palette. Coin art is drawn as
 * authored (never tinted, no matte); the level medallion's text is fixed, as on the device. Icon
 * colour and legibility are not read here.
 */

// ShibaLibraryScreen.kt constants.
private val Icon0Height = 48.dp
private const val ICON0_ASPECT = 144f / 80f
private val CoinCellWidth = 52.dp
private val PercentColumnWidth = 80.dp
private val ShortBarWidth = 64.dp
private val SummaryHeight = 48.dp

// ShibaPlayerCard.kt ShibaLevelMedallion: its fixed text colours and base size.
private val MedallionTextPrimary = Color(0xFFEEEEEE)
private val MedallionTextMuted = Color(0x88EEEEEE)
private val MedallionSize = 56.dp

private class LibraryGame(
    val title: String,
    /** ShibaLibraryViewModel.platformDisplay(platformId) ("windows" reads "Steam", PS3 falls to "PS3"). */
    val platform: String,
    val progress: Float,
    val platinum: Int,
    val gold: Int,
    val silver: Int,
    val bronze: Int,
)

// The XMB's sample library (SampleContent): 5 tracked, 1 mastered, 1,240 of 3,600 coins earned.
// Per game (earned / available): 14/24, 1,070/3,380, 48/48, 32/56, 76/92.
private val TrackedGames = listOf(
    LibraryGame("Crossbar Racing", "PS3", 14f / 24f, platinum = 0, gold = 1, silver = 4, bronze = 9),
    LibraryGame("Desktop Dungeon", "Steam", 1070f / 3380f, platinum = 0, gold = 120, silver = 310, bronze = 640),
    LibraryGame("Neon Drift", "PSP", 1f, platinum = 1, gold = 6, silver = 12, bronze = 30),
    LibraryGame("Portal Quest", "PS3", 32f / 56f, platinum = 0, gold = 2, silver = 9, bronze = 21),
    LibraryGame("Shiba Run", "PS3", 76f / 92f, platinum = 0, gold = 4, silver = 22, bronze = 50),
)

// LibrarySummary: Shiba Level, its progress, and the wallet's tier counts (Platinum = games mastered).
private const val LEVEL = 27
private const val NEXT_LEVEL_FRACTION = 0.42f
private val SummaryCounts: Map<ShibaPreviewTier, Int> = ShibaPreviewTier.entries.associateWith { tier ->
    TrackedGames.sumOf { it.count(tier) }
}

private fun LibraryGame.count(tier: ShibaPreviewTier): Int = when (tier) {
    ShibaPreviewTier.PLATINUM -> platinum
    ShibaPreviewTier.GOLD -> gold
    ShibaPreviewTier.SILVER -> silver
    ShibaPreviewTier.BRONZE -> bronze
}

/** The cursor: the first game (list position 1, after the pinned Search online row). */
private const val FOCUSED_INDEX = 0

@Composable
fun AchievementsScreenPreview(model: XmbPreviewModel) {
    val palette = rememberDetailPreviewPalette(model)
    DetailPreviewPage(model, palette) {
        Column(Modifier.fillMaxSize()) {
            DetailPreviewBreadcrumb(
                model, palette,
                title = "Achievements / Tracked Games",
                subtitle = "${TrackedGames.size} games",
                modifier = Modifier.background(shibaPreviewHeaderShade(palette)),
                trailing = {
                    Box(Modifier.height(SummaryHeight), contentAlignment = Alignment.CenterEnd) {
                        SummaryBlock(model, palette)
                    }
                },
            )

            ShibaPreviewSearchRow(palette, focused = false, placeholder = "Search games…")

            Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .wrapContentHeight(align = Alignment.Top, unbounded = true)
                        .padding(horizontal = DetailPreviewContentPadding),
                ) {
                    SearchOnlineRow(palette)
                    TrackedGames.forEachIndexed { index, game ->
                        GameRow(model, palette, game, focused = index == FOCUSED_INDEX)
                    }
                }
            }

            // shibaLibraryHelperItems on a tracked game.
            DetailPreviewFooter(
                palette,
                listOf(
                    DetailPreviewHint(DetailPadGlyphs.A, "View Achievements"),
                    DetailPreviewHint(DetailPadGlyphs.X, "Search"),
                    DetailPreviewHint(DetailPadGlyphs.Y, "Options"),
                    DetailPreviewHint(listOf(DetailPadGlyphs.LB, DetailPadGlyphs.RB), "Change View"),
                    DetailPreviewHint(DetailPadGlyphs.B, "Back"),
                ),
            )
        }
    }
}

// ── Header summary ──────────────────────────────────────────────────────────

@Composable
private fun SummaryBlock(model: XmbPreviewModel, palette: DetailPreviewPalette) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        LevelMedallion(LEVEL, size = 40.dp, accent = palette.focus)
        SummaryStat("NEXT LEVEL", "${(NEXT_LEVEL_FRACTION * 100).roundToInt()}%", palette) {
            ShibaPreviewProgressLine(NEXT_LEVEL_FRACTION, palette, Modifier.width(56.dp))
        }
        SummaryStat("TOTAL", "%,d".format(SummaryCounts.values.sum()), palette)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ShibaPreviewCoinOrder.forEach { tier ->
                ShibaPreviewCoinCount(model, tier, SummaryCounts.getValue(tier), palette, iconSize = 18.dp)
            }
        }
    }
}

@Composable
private fun SummaryStat(
    label: String,
    value: String,
    palette: DetailPreviewPalette,
    below: @Composable () -> Unit = {},
) {
    Column {
        Text(label, color = palette.textMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, lineHeight = 12.sp, maxLines = 1, softWrap = false)
        Text(value, color = palette.textPrimary, fontSize = 16.sp, lineHeight = 20.sp, maxLines = 1, softWrap = false)
        below()
    }
}

/** ShibaLevelMedallion: the accent ring around "LV" over the level, scaled from its 56dp design. */
@Composable
private fun LevelMedallion(level: Int, size: Dp, accent: Color) {
    val scale = size / MedallionSize
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.18f))
            .border(2.dp * scale.coerceAtLeast(0.75f), accent, CircleShape),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("LV", color = MedallionTextMuted, fontSize = 9.sp * scale, lineHeight = 10.sp * scale, fontWeight = FontWeight.Bold)
            Text("$level", color = MedallionTextPrimary, fontSize = 20.sp * scale, lineHeight = 22.sp * scale, fontWeight = FontWeight.Bold)
        }
    }
}

// ── Rows ────────────────────────────────────────────────────────────────────

/** The pinned "Search online" row: library row geometry, a magnifier-over-globe tile, a › at the end. */
@Composable
private fun SearchOnlineRow(palette: DetailPreviewPalette) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(ShibaPreviewRowHeight)
                .padding(horizontal = 10.dp),
        ) {
            SearchOnlineTile(palette, Modifier.height(Icon0Height).aspectRatio(ICON0_ASPECT))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Search online", color = palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Look up a game that isn't on this device", color = palette.textMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(16.dp))
            Text("›", color = palette.textMuted, fontSize = 18.sp)
        }
        ShibaPreviewSeparator(palette)
    }
}

/** ShibaLibraryScreen.SearchOnlineTile: the launcher's own canvas glyph, in the page accent. */
@Composable
private fun SearchOnlineTile(palette: DetailPreviewPalette, modifier: Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .background(palette.rowFill, ShibaPreviewFocusShape)
            .border(1.dp, palette.rowEdge, ShibaPreviewFocusShape),
    ) {
        Canvas(Modifier.size(26.dp)) {
            val accent = palette.focus
            val stroke = Stroke(width = size.minDimension * 0.07f)
            val globe = size.minDimension * 0.72f
            drawCircle(accent, radius = globe / 2f, center = Offset(globe / 2f, globe / 2f), style = stroke)
            drawLine(accent, Offset(0f, globe / 2f), Offset(globe, globe / 2f), strokeWidth = stroke.width)
            drawOval(accent, topLeft = Offset(globe * 0.31f, 0f), size = Size(globe * 0.38f, globe), style = stroke)
            drawLine(
                accent,
                Offset(globe * 0.86f, globe * 0.86f),
                Offset(size.width, size.height),
                strokeWidth = stroke.width * 1.3f,
            )
        }
    }
}

/** ShibaLibraryScreen.GameRow for a tracked game: ICON0, title / platform, percent + bar, tier counts. */
@Composable
private fun GameRow(model: XmbPreviewModel, palette: DetailPreviewPalette, game: LibraryGame, focused: Boolean) {
    val primary = if (focused) palette.textPrimary else palette.textPrimary.copy(alpha = 0.85f)
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(ShibaPreviewRowHeight)
                .shibaPreviewFocus(focused, palette)
                .padding(horizontal = 10.dp),
        ) {
            // No ICON0 assigned: the XMB's default letter card, in the page accent.
            ShibaPreviewIcon0Tile(game.title, palette.focus, Modifier.height(Icon0Height).aspectRatio(ICON0_ASPECT))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(game.title, color = primary, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(game.platform, color = palette.textMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(16.dp))
            // TrackedStats
            Column(Modifier.width(PercentColumnWidth), horizontalAlignment = Alignment.End) {
                Text("${(game.progress * 100).roundToInt()}%", color = primary, fontSize = 20.sp, lineHeight = 24.sp)
                Spacer(Modifier.height(3.dp))
                ShibaPreviewProgressLine(game.progress, palette, Modifier.width(ShortBarWidth))
            }
            Spacer(Modifier.width(20.dp))
            Row {
                ShibaPreviewCoinOrder.forEach { tier ->
                    Box(Modifier.width(CoinCellWidth), contentAlignment = Alignment.CenterStart) {
                        ShibaPreviewCoinCount(model, tier, game.count(tier), palette, iconSize = 18.dp)
                    }
                }
            }
        }
        ShibaPreviewSeparator(palette)
    }
}
