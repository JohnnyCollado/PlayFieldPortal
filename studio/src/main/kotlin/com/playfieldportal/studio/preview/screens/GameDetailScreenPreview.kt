package com.playfieldportal.studio.preview.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.studio.preview.XmbPreviewModel

/*
 * The launcher's Game Detail page (feature-xmb ui/detail/GameDetailScreen.kt GameDetailContent) as it
 * opens on "Crossbar Racing" from the XMB: a PS3 ROM with no artwork, a favorite, played yesterday,
 * a synced Shiba Coins set, the controller cursor on Launch (its landing node) and the helper footer
 * up. The frame is the body's top band: on the 468dp baseline the hero shrinks to fit Launch and the
 * quick actions above the footer (detailHeroHeightFor), and the rows below scroll into view later.
 *
 * Sources: GameDetailScreen.kt (page composition, facts, helper items), core-ui detail/
 * DetailScaffold.kt, DetailHeroBanner.kt, DetailActions.kt, DetailRows.kt, DetailPalette.kt,
 * feature-xmb ui/media/MediaDetailSlotKeys.kt (detail_* slots, favoriteOverrideAlpha).
 *
 * Theme inputs: wave colour + background anchors (the whole palette via detailPaletteFor), the
 * wallpaper / frozen wave behind the 0.88 page gradient, the detail_play / detail_favorite /
 * detail_artwork / detail_manual / detail_more and menu_back icon overrides, and the icon legibility
 * matte on the built-in action glyphs, and the Main / Sub text colours through the palette
 * ([DetailPreviewPalette]: primary and muted text). Icon colour is not read by this page.
 */

// ── Sample (consistent with SampleContent's Game category and the Shiba hub) ──
private const val GAME_TITLE = "Crossbar Racing"

/** The platform row's name (PlatformSeeder "PlayStation 3"): breadcrumb title and hero line. */
private const val PLATFORM_NAME = "PlayStation 3"

/** Game.kindLabel() for a ROM. */
private const val KIND_LABEL = "ROM"

/** Crossbar Racing is in Favorites in the XMB sample. */
private const val IS_FAVORITE = true

/** A manual is attached, so the Manual quick action is live. */
private const val HAS_MANUAL = true

// The game's coin set (the per-game achievements preview shows the same set).
private const val COINS_EARNED = 14
private const val COINS_TOTAL = 24

private const val DESCRIPTION =
    "Tear across neon-lit city circuits in a high-speed arcade racer built for split-second " +
        "decisions. Draft behind rivals, chain drifts into boosts and master twenty tracks across " +
        "five Grand Prix cups — then take your best laps online against ghosts from around the world."

// ── GameDetailScreen.kt / DetailActions.kt constants ──
private val PlayGreen = Color(0xFF45C46A)
private val DetailLaunchText = Color(0xFF06210D)
private val DetailHeroHeight: Dp = 220.dp
private val DetailHeroMinHeight: Dp = 120.dp

/** DetailPalette.DetailHeroBandBelow: lead-in spacer, gap under the hero, icon tile column, margin. */
private val DetailHeroBandBelow: Dp = 16.dp + 18.dp + 124.dp + 8.dp

/** DetailRows.DetailRowSpacing. */
private val DetailRowSpacing: Dp = 12.dp

private val DetailIconTileWidth = 196.dp
private val DetailIconTileHeight = 110.dp
private val RowShape = RoundedCornerShape(10.dp)

/** Focus lands on Launch when the page opens. */
private enum class DetailNode { LAUNCH, FAVORITE, ARTWORK, MANUAL, OPTIONS, COINS, OVERVIEW }

private val FocusedNode = DetailNode.LAUNCH

@Composable
fun GameDetailScreenPreview(model: XmbPreviewModel) {
    val palette = rememberDetailPreviewPalette(model)
    DetailPreviewPage(model, palette) {
        Column(Modifier.fillMaxSize()) {
            DetailPreviewBreadcrumb(model, palette, title = PLATFORM_NAME, subtitle = KIND_LABEL)
            // PfpDetailScaffold's body: a hard-clipped viewport between header and footer, the
            // content centred at its readable max width.
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
                val heroHeight = (maxHeight - DetailHeroBandBelow).coerceIn(DetailHeroMinHeight, DetailHeroHeight)
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = DetailPreviewContentMaxWidth)
                        .fillMaxWidth()
                        .wrapContentHeight(align = Alignment.Top, unbounded = true)
                        .padding(start = DetailPreviewContentPadding, end = DetailPreviewContentPadding, bottom = 22.dp),
                ) {
                    GameDetailBody(model, palette, heroHeight)
                }
            }
            DetailPreviewFooter(
                palette,
                // gameDetailHelperItems, base page, Confirm named for the focused node.
                listOf(
                    DetailPreviewHint(DetailPadGlyphs.A, confirmLabelFor(FocusedNode)),
                    DetailPreviewHint(DetailPadGlyphs.Y, "Options"),
                    DetailPreviewHint(DetailPadGlyphs.B, "Back"),
                ),
            )
        }
    }
}

/** GameDetailScreen.confirmLabelFor. */
private fun confirmLabelFor(node: DetailNode): String = when (node) {
    DetailNode.COINS -> "View"
    DetailNode.OVERVIEW -> "Read more"
    DetailNode.FAVORITE -> "Favorite"
    DetailNode.ARTWORK -> "Edit artwork"
    DetailNode.MANUAL -> "Open manual"
    DetailNode.OPTIONS -> "Options"
    DetailNode.LAUNCH -> "Launch"
}

@Composable
private fun GameDetailBody(model: XmbPreviewModel, palette: DetailPreviewPalette, heroHeight: Dp) {
    // The page-top bring-into-view anchor, then the lead-in.
    Box(Modifier.fillMaxWidth().height(1.dp))
    Spacer(Modifier.height(16.dp))

    HeroBanner(
        palette = palette,
        title = GAME_TITLE,
        platform = PLATFORM_NAME,
        // Last played / play time / kind (relativeDays, formatPlayTime).
        facts = listOf("Last played Yesterday", "Play time 12 h 40 min", KIND_LABEL),
        favorite = IS_FAVORITE,
        height = heroHeight,
    )

    Spacer(Modifier.height(DetailRowSpacing + 6.dp))

    // ── Primary actions ──
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        IconTile(palette, GAME_TITLE)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LaunchButton(model, palette, focused = FocusedNode == DetailNode.LAUNCH)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickAction(
                    model, palette, "Favorite",
                    // One label; the icon carries the state.
                    icon = if (IS_FAVORITE) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    slotKey = "detail_favorite",
                    focused = FocusedNode == DetailNode.FAVORITE,
                    available = true,
                    // favoriteOverrideAlpha: custom art dims for the un-favorited state.
                    overrideAlpha = if (IS_FAVORITE) 1f else 0.4f,
                    modifier = Modifier.weight(1f),
                )
                QuickAction(
                    model, palette, "Artwork", Icons.Filled.Brush, "detail_artwork",
                    focused = FocusedNode == DetailNode.ARTWORK, available = true, modifier = Modifier.weight(1f),
                )
                QuickAction(
                    model, palette, "Manual", Icons.AutoMirrored.Filled.MenuBook, "detail_manual",
                    focused = FocusedNode == DetailNode.MANUAL, available = HAS_MANUAL, modifier = Modifier.weight(1f),
                )
                QuickAction(
                    model, palette, "Options", Icons.Filled.MoreVert, "detail_more",
                    focused = FocusedNode == DetailNode.OPTIONS, available = true, modifier = Modifier.weight(1f),
                )
            }
        }
    }

    // ── Shiba Coins (not an Android entry) ──
    Spacer(Modifier.height(DetailRowSpacing))
    val progress = COINS_EARNED.toFloat() / COINS_TOTAL
    ProgressRow(
        palette = palette,
        label = "Shiba Coins",
        value = "$COINS_EARNED / $COINS_TOTAL",
        secondary = "${(progress * 100).toInt()}%",
        progress = progress,
        focused = FocusedNode == DetailNode.COINS,
    )

    // ── Overview ──
    Spacer(Modifier.height(DetailRowSpacing))
    TextRow(palette, label = "Overview", text = DESCRIPTION, focused = FocusedNode == DetailNode.OVERVIEW)
}

// ── Hero (DetailHeroBanner.kt) ──────────────────────────────────────────────

@Composable
private fun HeroBanner(
    palette: DetailPreviewPalette,
    title: String,
    platform: String,
    facts: List<String>,
    favorite: Boolean,
    height: Dp,
) {
    val shadowed = TextStyle(shadow = DetailPreviewTextShadow)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(14.dp))
            // Artwork-less fallback: the page accent's plate at the same height (no readability gradient).
            .background(palette.focus.copy(alpha = 0.16f)),
        contentAlignment = Alignment.BottomStart,
    ) {
        Column(modifier = Modifier.padding(horizontal = 26.dp, vertical = 18.dp)) {
            Text(
                text = title,
                color = palette.textPrimary,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = shadowed,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = platform,
                color = palette.textMuted,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = shadowed,
            )
            if (facts.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = facts.joinToString("  ·  "),
                    color = palette.textMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = shadowed,
                )
            }
        }
        if (favorite) FavoriteBadge(palette, Modifier.align(Alignment.TopEnd).padding(14.dp))
    }
}

/** DetailHeroBanner.detailStarColor: lerp(PFPColors.accentColor (white), gold, 0.85). */
private val StarColor = lerp(Color.White, Color(0xFFFFD766), 0.85f)

@Composable
private fun FavoriteBadge(palette: DetailPreviewPalette, modifier: Modifier) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.55f))
            .border(1.dp, lerp(Color.White, Color.Transparent, 0.55f), shape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DetailPreviewStarMark(color = StarColor, size = 12.dp)
        Text(text = "FAVORITE", color = palette.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

// ── Actions (DetailActions.kt) ──────────────────────────────────────────────

/** PfpDetailIconTile with no icon art: the title's first letter in muted text. */
@Composable
private fun IconTile(palette: DetailPreviewPalette, title: String) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .width(DetailIconTileWidth)
            .height(DetailIconTileHeight)
            .clip(shape)
            .background(palette.rowFill)
            .border(1.dp, palette.rowEdge, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = title.take(1).uppercase(), color = palette.textMuted, fontSize = 34.sp, fontWeight = FontWeight.Bold)
    }
}

/** PfpDetailLaunchButton: green fill (lifted toward white under focus), focus edge, dark label. */
@Composable
private fun LaunchButton(model: XmbPreviewModel, palette: DetailPreviewPalette, focused: Boolean) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 58.dp)
            .clip(shape)
            .background(if (focused) lerp(PlayGreen, Color.White, 0.10f) else PlayGreen)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) palette.focus else Color.Black.copy(alpha = 0.25f),
                shape = shape,
            )
            .padding(vertical = 15.dp, horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DetailPreviewThemedGlyph(model, "detail_play", Icons.Filled.PlayArrow, DetailLaunchText, Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            text = "Launch",
            color = DetailLaunchText,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** PfpDetailQuickAction: row fill, the shared focus ring, 0.38 alpha when unavailable. */
@Composable
private fun QuickAction(
    model: XmbPreviewModel,
    palette: DetailPreviewPalette,
    label: String,
    icon: ImageVector,
    slotKey: String,
    focused: Boolean,
    available: Boolean,
    modifier: Modifier,
    overrideAlpha: Float = 1f,
) {
    val shape = RoundedCornerShape(9.dp)
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 46.dp)
            .clip(shape)
            .background(palette.rowFill, shape)
            .detailFocusRing(focused, palette, fill = palette.focus.copy(alpha = 0.12f), shape = shape)
            .alpha(if (available) 1f else 0.38f)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        DetailPreviewThemedGlyph(model, slotKey, icon, palette.iconPrimary, Modifier.size(18.dp), overrideAlpha)
        Text(
            text = label,
            color = palette.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** DetailScaffold.detailFocusRing: a thin bright edge plus a fill lift, inside the node's bounds. */
private fun Modifier.detailFocusRing(
    focused: Boolean,
    palette: DetailPreviewPalette,
    fill: Color,
    shape: RoundedCornerShape,
): Modifier = this
    .background(if (focused) fill else Color.Transparent, shape)
    .border(
        width = if (focused) 1.5.dp else 1.dp,
        color = if (focused) palette.focus else palette.rowEdge,
        shape = shape,
    )

// ── Rows (DetailRows.kt) ────────────────────────────────────────────────────

@Composable
private fun RowShell(palette: DetailPreviewPalette, focused: Boolean, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RowShape)
            .background(palette.rowFill, RowShape)
            .detailFocusRing(focused, palette, fill = palette.focus.copy(alpha = 0.10f), shape = RowShape)
            .padding(horizontal = 16.dp, vertical = 13.dp),
    ) {
        content()
    }
}

/** PfpDetailProgressRow with its disclosure chevron. */
@Composable
private fun ProgressRow(
    palette: DetailPreviewPalette,
    label: String,
    value: String,
    secondary: String,
    progress: Float,
    focused: Boolean,
) {
    RowShell(palette, focused) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label.uppercase(),
                    color = palette.textMuted.dimmed(0.8f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Spacer(Modifier.size(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = value, color = palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    Spacer(Modifier.width(10.dp))
                    Text(text = secondary, color = palette.textMuted, fontSize = 12.sp, maxLines = 1)
                }
                Spacer(Modifier.size(8.dp))
                // PfpDetailProgressBar: 5dp track with the focus-colour fill.
                val fraction = progress.coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(palette.track),
                ) {
                    if (fraction > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(palette.focus),
                        )
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            DetailPreviewChevronMark(color = palette.iconMuted, size = 14.dp)
        }
    }
}

/** PfpDetailTextRow, collapsed to three lines, with its Confirm-to-expand affordance (> 190 chars). */
@Composable
private fun TextRow(palette: DetailPreviewPalette, label: String, text: String, focused: Boolean) {
    RowShell(palette, focused) {
        Text(
            text = label.uppercase(),
            color = palette.textMuted.dimmed(0.8f),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = text,
            color = palette.textPrimary,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (text.length > OVERVIEW_EXPAND_THRESHOLD) {
            Spacer(Modifier.size(6.dp))
            Text(
                text = "Confirm to read more",
                color = if (focused) palette.focus else palette.textMuted.dimmed(0.7f),
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
    }
}

/** GameDetailScreen.OVERVIEW_EXPAND_THRESHOLD. */
private const val OVERVIEW_EXPAND_THRESHOLD = 190
