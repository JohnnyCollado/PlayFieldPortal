package com.playfieldportal.studio.preview.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.studio.preview.XmbPreviewModel
import java.util.Locale
import kotlin.math.roundToInt

/*
 * One game's achievements: "View Shiba Coins" / the Shiba Coins row on Game Detail opens
 * feature-xmb ui/detail/ShibaCoinsScreen.kt for that game. Shown for "Crossbar Racing" (PS3 trophies,
 * synced) as it opens: All view, Tier sort (Platinum Crown pinned first, then Bronze up, rarest first
 * within a tier — ShibaCoinsViewModel.arrange), the cursor on the pinned Search row
 * (ShibaCoinsViewModel.load), controller hints up. Provider badges are network images on the
 * device; a coin with no badge draws its tier medallion (CoinArt), which is what this frame shows.
 *
 * Sources: ShibaCoinsScreen.kt (header, view tabs, ShibaCoinsRow / Metric / Status / Tier columns,
 * Platinum Crown, CoinListRow redaction), ShibaCoinsViewModel.kt (shibaCoinsHelperItems, arrange,
 * providerLabel), ShibaDetailParts.kt, ShibaCoinArt.kt, ShibaLibraryViewModel.platformDisplay,
 * core-ui detail/ (all of it) and components/PfpCheck.kt.
 *
 * Theme inputs: wave colour + background anchors (detailPaletteFor: page, accent, text, rows, tabs,
 * bars), the wallpaper / frozen wave behind the page, and the shiba_coin_* icon overrides (drawn as
 * authored; a locked coin's greyscale + 0.6 alpha still applies to custom art). The header's ◀ is
 * plain text here (no menu_back slot). The Main / Sub text colours come through the palette (an
 * unselected tab is Main at 0.72); icon colour and legibility are not read.
 */

// ShibaCoinsScreen.kt constants.
private val CoinArtSize = 46.dp
private val MetricColumnWidth = 74.dp
private val RarityBarWidth = 64.dp
private val StatusColumnWidth = 108.dp
private val TierColumnWidth = 48.dp
private val HeaderTitleIndent = 52.dp
private val HeaderBarWidth = 74.dp
private val HeaderTierIconSize = 26.dp
private val TabShape = RoundedCornerShape(999.dp)

/** EarnedColor = DetailLaunchFill. */
private val EarnedColor = Color(0xFF45C46A)

// ── Sample: Crossbar Racing's PS3 trophy set (the Game Detail preview shows the same 14 / 24) ──
private const val GAME_TITLE = "Crossbar Racing"

/** headerSubtitle: platformDisplay("ps3") · the sync freshness. */
private const val HEADER_SUBTITLE = "PS3 · Synced 4 min ago"

private class TierTally(val earned: Int, val total: Int)

/** Platinum counts the crown (0 / 1 until mastered); the rest count individual coins. */
private val Tally = mapOf(
    ShibaPreviewTier.PLATINUM to TierTally(0, 1),
    ShibaPreviewTier.GOLD to TierTally(1, 3),
    ShibaPreviewTier.SILVER to TierTally(4, 6),
    ShibaPreviewTier.BRONZE to TierTally(9, 15),
)
private const val EARNED = 14
private const val TOTAL = 24

private class SampleCoin(
    val tier: ShibaPreviewTier,
    val title: String,
    val description: String,
    /** Percent of players; negative = the provider reported none. */
    val rarity: Double,
    /** "MMM d, yyyy" of the unlock, null while locked. */
    val earnedOn: String?,
    /** A hidden coin not yet earned: redacted until revealed. */
    val hidden: Boolean = false,
)

// The head of the Tier sort: Bronze first, rarest first. The frame shows the Platinum Crown and the
// first rows; the rest of the set is below the fold.
private val Coins = listOf(
    SampleCoin(ShibaPreviewTier.BRONZE, "Photo Finish", "Win a race by less than a tenth of a second", 3.2, "Sep 27, 2026"),
    SampleCoin(ShibaPreviewTier.BRONZE, "Midnight Run", "Finish the Neon Coast night race in first place", 4.8, null, hidden = true),
    SampleCoin(ShibaPreviewTier.BRONZE, "Clean Sweep", "Finish a Grand Prix without touching a wall", 6.5, null),
    SampleCoin(ShibaPreviewTier.BRONZE, "Draft Master", "Overtake 50 rivals using the slipstream", 11.4, "Sep 20, 2026"),
    SampleCoin(ShibaPreviewTier.BRONZE, "Drift King", "Chain a 10-second drift on any track", 18.9, "Sep 14, 2026"),
    SampleCoin(ShibaPreviewTier.BRONZE, "Garage Days", "Customize the paint on any car", 41.7, "Sep 12, 2026"),
)

@Composable
fun GameAchievementsScreenPreview(model: XmbPreviewModel) {
    val palette = rememberDetailPreviewPalette(model)
    DetailPreviewPage(model, palette) {
        Column(Modifier.fillMaxSize()) {
            CoinsHeader(model, palette)

            // Focus starts on the pinned Search row; its right half holds the view tabs.
            ShibaPreviewSearchRow(palette, focused = true, placeholder = "Search coins…") {
                ViewTabs(palette)
            }

            Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .wrapContentHeight(align = Alignment.Top, unbounded = true)
                        .padding(horizontal = DetailPreviewContentPadding),
                ) {
                    // PlatinumCrownRow: the set-completion award, locked until every coin is earned.
                    CoinRow(
                        model, palette,
                        tier = ShibaPreviewTier.PLATINUM,
                        dimmed = true,
                        title = "Platinum Crown",
                        titleItalic = false,
                        description = "Earn every other coin in the game",
                        metricValue = "$EARNED / $TOTAL",
                        metricFraction = EARNED.toFloat() / TOTAL,
                        metricLabel = "coins earned",
                        earned = false,
                        statusDetail = null,
                    )
                    Coins.forEach { coin ->
                        // CoinListRow: a hidden, unearned coin is redacted (no badge, italic placeholder).
                        val redacted = coin.hidden && coin.earnedOn == null
                        CoinRow(
                            model, palette,
                            tier = coin.tier,
                            dimmed = coin.earnedOn == null,
                            title = if (redacted) "Hidden Coin" else coin.title,
                            titleItalic = redacted,
                            description = if (redacted) "Keep playing — or press Confirm to reveal" else coin.description,
                            metricValue = if (coin.rarity < 0) "—" else String.format(Locale.US, "%.1f%%", coin.rarity),
                            metricFraction = if (coin.rarity < 0) 0f else (coin.rarity / 100.0).toFloat(),
                            metricLabel = "of players",
                            earned = coin.earnedOn != null,
                            statusDetail = coin.earnedOn,
                        )
                    }
                }
            }

            // shibaCoinsHelperItems with Search focused and one source.
            DetailPreviewFooter(
                palette,
                listOf(
                    DetailPreviewHint(DetailPadGlyphs.A, "Type"),
                    DetailPreviewHint(DetailPadGlyphs.X, "Search"),
                    DetailPreviewHint(DetailPadGlyphs.Y, "Options"),
                    DetailPreviewHint(listOf(DetailPadGlyphs.LB, DetailPadGlyphs.RB), "Change View"),
                    DetailPreviewHint(DetailPadGlyphs.B, "Back"),
                ),
            )
        }
    }
}

// ── Header (ShibaCoinsHeader) ───────────────────────────────────────────────

@Composable
private fun CoinsHeader(model: XmbPreviewModel, palette: DetailPreviewPalette) {
    Column(Modifier.fillMaxWidth().background(shibaPreviewHeaderShade(palette))) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = DetailPreviewContentPadding, end = DetailPreviewContentPadding, top = 4.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
                Text("◀", color = palette.textMuted, fontSize = 16.sp)
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = GAME_TITLE,
                color = palette.textPrimary,
                fontSize = 25.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(16.dp))
            Text(text = HEADER_SUBTITLE, color = palette.textMuted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = DetailPreviewContentPadding + HeaderTitleIndent, end = DetailPreviewContentPadding, bottom = 10.dp),
        ) {
            val progress = EARNED.toFloat() / TOTAL
            HeaderStat("COMPLETION", "${(progress * 100).roundToInt()}%", palette) {
                Spacer(Modifier.height(3.dp))
                ShibaPreviewProgressLine(progress, palette, Modifier.width(HeaderBarWidth), height = 4.dp)
            }
            HeaderStat("EARNED", "$EARNED / $TOTAL", palette)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                ShibaPreviewCoinOrder.forEach { tier ->
                    val tally = Tally.getValue(tier)
                    HeaderTierCell(model, palette, tier, tally.earned, tally.total)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.divider))
    }
}

@Composable
private fun HeaderStat(
    label: String,
    value: String,
    palette: DetailPreviewPalette,
    below: @Composable () -> Unit = {},
) {
    Column {
        Text(label, color = palette.textMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, lineHeight = 13.sp, maxLines = 1, softWrap = false)
        Text(value, color = palette.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.Medium, lineHeight = 23.sp, maxLines = 1, softWrap = false)
        below()
    }
}

@Composable
private fun HeaderTierCell(model: XmbPreviewModel, palette: DetailPreviewPalette, tier: ShibaPreviewTier, earned: Int, total: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ShibaPreviewCoin(model, tier, Modifier.size(HeaderTierIconSize))
        Spacer(Modifier.width(6.dp))
        Column {
            Text("$earned", color = palette.textPrimary, fontSize = 14.sp, lineHeight = 16.sp, maxLines = 1, softWrap = false)
            Text("/$total", color = palette.textMuted, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1, softWrap = false)
        }
    }
}

// ── View tabs (ShibaCoinsViewTabs) ──────────────────────────────────────────

@Composable
private fun ViewTabs(palette: DetailPreviewPalette) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxHeight(),
    ) {
        // ControllerPrompt(PREV_CATEGORY, label = "", glyphSize = 18dp): the glyph, 4dp, an empty label.
        ShoulderPrompt(DetailPadGlyphs.LB)
        ViewTab(palette, "All $TOTAL", selected = true)
        ViewTab(palette, "Earned $EARNED", selected = false)
        ViewTab(palette, "Locked ${TOTAL - EARNED}", selected = false)
        ShoulderPrompt(DetailPadGlyphs.RB)
    }
}

@Composable
private fun ShoulderPrompt(glyph: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        DetailPreviewPadGlyph(glyph, 18.dp)
        Text(text = "", color = Color.White.copy(alpha = 0.75f), style = androidx.compose.ui.text.TextStyle.Default)
    }
}

@Composable
private fun ViewTab(palette: DetailPreviewPalette, label: String, selected: Boolean) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxHeight()) {
        Text(
            text = label,
            color = if (selected) palette.textPrimary else palette.unselectedLabel,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .clip(TabShape)
                .then(
                    if (selected) {
                        Modifier
                            .background(palette.focus.copy(alpha = 0.28f), TabShape)
                            .border(1.dp, palette.focus, TabShape)
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

// ── Rows (ShibaCoinsRow) ────────────────────────────────────────────────────

@Composable
private fun CoinRow(
    model: XmbPreviewModel,
    palette: DetailPreviewPalette,
    tier: ShibaPreviewTier,
    dimmed: Boolean,
    title: String,
    titleItalic: Boolean,
    description: String,
    metricValue: String,
    metricFraction: Float,
    metricLabel: String,
    earned: Boolean,
    statusDetail: String?,
    focused: Boolean = false,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(ShibaPreviewRowHeight)
                .shibaPreviewFocus(focused, palette)
                .padding(horizontal = 10.dp),
        ) {
            // CoinArt with no badge: the tier medallion, greyed while locked.
            ShibaPreviewCoin(model, tier, Modifier.size(CoinArtSize), dimmed = dimmed)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = if (focused) palette.textPrimary else palette.textPrimary.copy(alpha = 0.85f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontStyle = if (titleItalic) FontStyle.Italic else FontStyle.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(text = description, color = palette.textMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(16.dp))
            // MetricColumn
            Column(Modifier.width(MetricColumnWidth), horizontalAlignment = Alignment.End) {
                Text(metricValue, color = palette.textPrimary, fontSize = 16.sp, lineHeight = 19.sp, maxLines = 1, softWrap = false)
                Spacer(Modifier.height(3.dp))
                ShibaPreviewProgressLine(metricFraction, palette, Modifier.width(RarityBarWidth))
                Spacer(Modifier.height(2.dp))
                Text(metricLabel, color = palette.textMuted, fontSize = 9.sp, lineHeight = 11.sp, maxLines = 1, softWrap = false)
            }
            Spacer(Modifier.width(12.dp))
            // StatusColumn
            Column(Modifier.width(StatusColumnWidth)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (earned) {
                        DetailPreviewCheckMark(EarnedColor, size = 14.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("Earned", color = EarnedColor, fontSize = 13.sp, maxLines = 1, softWrap = false)
                    } else {
                        Icon(Icons.Filled.Lock, contentDescription = null, tint = palette.iconMuted, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Locked", color = palette.textMuted, fontSize = 13.sp, maxLines = 1, softWrap = false)
                    }
                }
                if (earned && statusDetail != null) {
                    Text(statusDetail, color = palette.textMuted, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1, softWrap = false)
                }
            }
            // TierColumn: the medallion is never dimmed here.
            Column(Modifier.width(TierColumnWidth), horizontalAlignment = Alignment.CenterHorizontally) {
                ShibaPreviewCoin(model, tier, Modifier.size(24.dp))
                Text(
                    text = tier.name.lowercase().replaceFirstChar { it.uppercase() },
                    color = palette.textMuted,
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        ShibaPreviewSeparator(palette)
    }
}
