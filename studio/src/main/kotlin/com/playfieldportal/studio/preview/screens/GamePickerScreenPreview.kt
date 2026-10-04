package com.playfieldportal.studio.preview.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.studio.preview.GameLetterTile
import com.playfieldportal.studio.preview.XmbBackdrop
import com.playfieldportal.studio.preview.XmbPreviewModel
import com.playfieldportal.core.ui.theme.ThemeTokens
import com.playfieldportal.core.ui.theme.StorefrontColors
import com.playfieldportal.core.ui.theme.unselectedLabel

// ── Game Picker (feature-xmb ui/GamePickerScreen.kt + GamePickerLogic.kt) ─────────────────────
//
// "Add Games" as a custom category ("Party", holding Portal Quest and Shiba Run) opens it: a shelf
// list beside rows of ICON0 tiles at the game row's 126 × 70, each standing on a ledge. The shelves
// follow buildShelves — the category's own games, one per console with games, then the custom
// memory cards. The PlayStation 3 shelf is open with the cursor in the grid on Crossbar Racing,
// just checked, so the header reads "1 to add". The view is Custom Icon (the default global mode).
// Static frame: focus and check fades are drawn at their targets.
//
// Theme inputs: StorefrontColors over the XMB background (like the App Picker), plus PFPColors'
// text override for the unselected labels (StorefrontColors.unselectedLabel = textOr at 0.72).

private const val Category = "Party"

private class SampleGame(val title: String, val accentArgb: Long)

private class SampleShelf(val title: String, val games: List<SampleGame>, val cards: Int = 0) {
    val size: Int get() = if (cards > 0) cards else games.size
}

private val Ps3 = 0xFF003087
private val Psp = 0xFF003791
private val Windows = 0xFF0078D4

// SampleContent's library, titles in title order as each console card lists them.
private val Shelves = listOf(
    SampleShelf("In $Category", listOf(SampleGame("Portal Quest", Ps3), SampleGame("Shiba Run", Ps3))),
    SampleShelf(
        "PlayStation 3",
        listOf(
            SampleGame("Crossbar Racing", Ps3),
            SampleGame("Memory Card Blues", Ps3),
            SampleGame("Portal Quest", Ps3),
            SampleGame("Shiba Run", Ps3),
        ),
    ),
    SampleShelf("PlayStation Portable", listOf(SampleGame("Neon Drift", Psp), SampleGame("Pocket Legends", Psp))),
    SampleShelf("Windows Games", listOf(SampleGame("Desktop Dungeon", Windows))),
    // The custom memory cards not already in the category (Co-op Night).
    SampleShelf("Custom Memory Cards", emptyList(), cards = 1),
)

private const val OpenShelf = 1
private const val CursorTile = 0

// Already in the category, plus the one just checked.
private val Checked = setOf("Portal Quest", "Shiba Run", "Crossbar Racing")

// PickerFooter in the grid: D-pad Navigate, then the Xbox glyphs for the default bindings —
// A SELECT, Y OPEN_CONTEXT_MENU, X CHANGE_SORT, Menu (START) HOME, B BACK ("Shelves" from the grid).
private val GridPrompts = listOf(
    "xmb/ctl_xb_dpad_all.png" to "Navigate",
    "xmb/ctl_xb_face_south.png" to "Toggle",
    "xmb/ctl_xb_face_north.png" to "Options",
    "xmb/ctl_xb_face_west.png" to "Search",
    "xmb/ctl_xb_start.png" to "Done",
    "xmb/ctl_xb_face_east.png" to "Shelves",
)

// GamePickerLogic sizes.
private val Icon0Width = 126.dp
private val Icon0Height = 70.dp
private val ArtHeight = 84.dp
private val FramePad = 4.dp
private val TileSpacing = 8.dp
private val ListWidth = 220.dp

// XMBItemList.XmbTextShadow: the Launcher's drop shadow, over light storefront text (Text Shadow on).
private val PickerShadow = ThemeTokens.TextShadow

@Composable
fun GamePickerScreenPreview(model: XmbPreviewModel) {
    val sf = rememberDrawerPalette(model)
    val unselected = sf.unselectedLabel(model.pfp)
    val shadow = PickerShadow.takeIf { sf.textPrimary.luminance() > 0.5f }
    Box(Modifier.fillMaxSize()) {
        XmbBackdrop(model)
        CompositionLocalProvider(LocalTextStyle provides DrawerBodyLarge.merge(TextStyle(shadow = shadow))) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(sf.backgroundDeep, sf.backgroundMid))),
            ) {
                Header(sf)
                Box(Modifier.fillMaxWidth().height(1.dp).background(sf.chromeDivider))
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    ShelfList(sf, unselected)
                    ShelfPane(sf, unselected, Modifier.weight(1f).fillMaxHeight())
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(sf.chromeDivider))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
                ) {
                    for ((glyph, label) in GridPrompts) {
                        DrawerPrompt(glyph, label, sf.textSecondary, TextStyle(fontSize = 12.sp, shadow = shadow), 16.dp)
                    }
                }
            }
        }
    }
}

// PickerHeader: ‹ "Add Games · Party" on the left, pendingChangeLabel on the right.
@Composable
private fun Header(sf: StorefrontColors) {
    Row(
        modifier = Modifier.fillMaxWidth().height(DrawerHeaderHeight).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Text("‹", color = sf.textSecondary, fontSize = 18.sp, modifier = Modifier.padding(end = 8.dp))
            Text(
                text = buildAnnotatedString {
                    append("Add Games")
                    withStyle(SpanStyle(color = sf.textSecondary, fontWeight = FontWeight.Normal)) { append(" · $Category") }
                },
                color = sf.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text("1 to add", color = sf.textSecondary, fontSize = 13.sp)
    }
}

// GamePickerScreen.FocusChrome at full focus: glow, bright outer edge, inner hairline.
@Composable
private fun BoxScope.Focus(sf: StorefrontColors) {
    Box(Modifier.matchParentSize().background(sf.selectionGlow))
    Box(Modifier.matchParentSize().border(1.dp, sf.tileSelectedEdge.copy(alpha = 1f)))
    Box(Modifier.matchParentSize().padding(2.dp).border(1.dp, sf.tileSelectedInner.copy(alpha = 1f)))
}

private fun checkedIn(shelf: SampleShelf): Int = shelf.games.count { it.title in Checked }

// ShelfList / ShelfListEntry: the open shelf keeps its fill and a 2 dp right edge; the cursor is in
// the grid, so no entry carries the focus chrome. A gap sets the memory cards apart.
@Composable
private fun ShelfList(sf: StorefrontColors, unselected: Color) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .width(ListWidth)
            .fillMaxHeight()
            .background(sf.railBackground)
            .padding(vertical = 10.dp, horizontal = 8.dp),
    ) {
        Shelves.forEachIndexed { index, shelf ->
            val current = index == OpenShelf
            val edge = sf.categorySelectedEdge
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = if (shelf.cards > 0 && index > 0) 10.dp else 0.dp)
                    .background(if (current) sf.categorySelected else Color.Transparent)
                    .drawBehind {
                        if (current) {
                            val w = 2.dp.toPx()
                            drawRect(edge, topLeft = Offset(size.width - w, 0f), size = size.copy(width = w))
                        }
                    },
            ) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 9.dp, bottom = 10.dp)) {
                    val checked = checkedIn(shelf)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = shelf.title,
                            color = if (current) sf.textPrimary else unselected,
                            fontSize = 13.sp,
                            fontWeight = if (current) FontWeight.Medium else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("$checked / ${shelf.size}", color = sf.textSecondary, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    // GamePickerScreen: the track is the icon tone (iconSecondary), never the font colour.
                    Box(Modifier.fillMaxWidth().height(2.dp).background(sf.iconSecondary.copy(alpha = 0.18f))) {
                        Box(Modifier.fillMaxWidth(checked.toFloat() / shelf.size).height(2.dp).background(sf.tileSelectedInner))
                    }
                }
            }
        }
    }
}

// ShelfPane: the shelf's title, its counts, the "View:" pill, then the tile rows.
@Composable
private fun ShelfPane(sf: StorefrontColors, unselected: Color, modifier: Modifier) {
    val shelf = Shelves[OpenShelf]
    Column(modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(shelf.title, color = sf.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(12.dp))
            Text(
                "${shelf.size} games · ${checkedIn(shelf)} checked",
                color = sf.textSecondary,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                "View: Custom Icon",
                color = sf.textSecondary,
                fontSize = 11.sp,
                modifier = Modifier
                    .border(1.dp, sf.tileSelectedEdge.copy(alpha = 0.55f), RoundedCornerShape(2.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(TileSpacing),
            modifier = Modifier.padding(top = 12.dp),
        ) {
            shelf.games.forEachIndexed { index, game ->
                Tile(game, focused = index == CursorTile, checked = game.title in Checked, sf = sf, unselected = unselected)
            }
        }
    }
}

// PickerTile: the slot reserves the natural-art height so every ledge lines up; the ICON0 stands on
// it, the chrome hugs the art, and the 16 dp check badge sits top-right.
@Composable
private fun Tile(game: SampleGame, focused: Boolean, checked: Boolean, sf: StorefrontColors, unselected: Color) {
    val ledge = sf.chromeDivider
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(Icon0Width + FramePad * 2)) {
        Box(
            contentAlignment = Alignment.BottomCenter,
            modifier = Modifier
                .fillMaxWidth()
                .height(ArtHeight + FramePad * 2)
                .drawBehind {
                    val gap = TileSpacing.toPx() / 2f
                    val y = size.height - FramePad.toPx()
                    drawRect(
                        brush = Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.22f), Color.Transparent),
                            startY = y,
                            endY = y + 8.dp.toPx(),
                        ),
                        topLeft = Offset(-gap, y),
                        size = size.copy(width = size.width + gap * 2, height = 8.dp.toPx()),
                    )
                    drawLine(ledge.copy(alpha = 0.35f), Offset(-gap, y), Offset(size.width + gap, y), 1.dp.toPx())
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (focused) Focus(sf)
                if (checked) Box(Modifier.matchParentSize().background(sf.tileSelectedInner.copy(alpha = 0.10f)))
                Box(Modifier.padding(FramePad)) {
                    GameLetterTile(game.title, Color(game.accentArgb), Modifier.size(Icon0Width, Icon0Height))
                }
                if (checked) {
                    PickerCheckBadge(
                        fill = sf.tileSelectedEdge,
                        markColor = sf.backgroundDeep.copy(alpha = 1f),
                        diameter = 16.dp,
                        modifier = Modifier.align(Alignment.TopEnd).padding(1.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = game.title,
            color = if (focused) sf.textPrimary else unselected,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
