package com.playfieldportal.core.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.ui.preview.CombinedPreviews
import com.playfieldportal.core.ui.preview.PfpPreview
import com.playfieldportal.core.ui.theme.deriveStorefrontColors
import com.playfieldportal.core.ui.theme.menuCursorEdge
import com.playfieldportal.core.ui.theme.menuCursorFill
import com.playfieldportal.core.ui.theme.themedSubText
import com.playfieldportal.core.ui.theme.themedText
import com.playfieldportal.core.ui.theme.menuTextShadowFor

// ── PSP-style context menu panel ──────────────────────────────────────────────
//
// The canonical XMB sub-menu look: a translucent column anchored to the right
// edge over a light scrim, a plain title underlined by a thin rule, and the
// selected item marked by the Settings rows' cursor — one solid accent-tinted
// fill across the row (no boxed panel). Shared by the XMB's Y/Triangle menu and any settings
// screen that opens a per-item options menu — one source, no style drift.
//
// Controller navigation is the caller's job (selectedIndex in, activation out);
// this composable handles touch/click interaction.

/** One row of a [PspContextMenuOverlay]. */
data class PspMenuRow(
    val label: String,
    val isDestructive: Boolean = false,
    // Marks a current membership/selection (e.g. collections the item already belongs to).
    val checked: Boolean = false,
    /**
     * The row's current setting, pinned to the panel's right edge and dimmer than the label — a
     * root row that names a list (label "Search", value "None") reads as label-and-value rather
     * than as one long sentence, and the values line up in a column the eye can scan.
     *
     * Deliberately its own field rather than glue inside [label]: two spaces in a string cannot
     * right-align, cannot be dimmed, and cannot be read back by a test asserting the setting.
     */
    val value: String? = null,
    /**
     * A group name drawn above this row, set on the first row of each group. It lives on the row
     * rather than as a list entry of its own so [PspContextMenuOverlay]'s `selectedIndex` stays a
     * row index: a caller's controller navigation never has to step over a header.
     */
    val header: String? = null,
    /**
     * Activating this row opens a list rather than doing something: a › is drawn at the row's right
     * edge, after any [value] (a row may show both — "Sort  Tier  ›"), and [PspMenuNav] plays SELECT.
     */
    val opensMenu: Boolean = false,
    /** Activating this row plays no cue (Favorite: the toggle is its own feedback). */
    val silent: Boolean = false,
) {
    /** The cue [PspMenuNav] plays when this row is activated. */
    val cue: PspMenuCue
        get() = when {
            silent -> PspMenuCue.NONE
            opensMenu -> PspMenuCue.SELECT
            else -> PspMenuCue.CONFIRM
        }
}

private val PanelWidth = 300.dp
// Screen kept clear to the left of the panel, so a narrow screen never has the panel (and its
// title) touching the edge.
private val PanelEdgeMargin = 24.dp
// The panel's content starts this far in; rows draw their cursor from PanelContentStart and inset
// their text by RowTextInset, so the title, rule, headers and labels share one left edge.
private val PanelContentStart = 20.dp
private val RowTextInset = 8.dp

/** The softer warning tone destructive rows start from, before being lightened to pass contrast. */
internal val DestructiveBase = Color(0xFFFFB4A8)

/** WCAG minimum contrast for normal text. */
internal const val MinTextContrast = 4.5

private fun Color.luminance(): Double {
    fun channel(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(red) + 0.7152 * channel(green) + 0.0722 * channel(blue)
}

/** WCAG contrast ratio between two opaque colours, 1.0 (none) to 21.0 (black on white). */
internal fun contrastRatio(a: Color, b: Color): Double {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/**
 * The colour of a destructive row's label: [DestructiveBase], moved toward white until it reaches
 * [MinTextContrast] against every colour in [backgrounds] (the panel's two gradient ends, and the
 * cursor's fill over them, for the focused state). On a light background it moves toward black
 * instead; if nothing passes, white or black is the answer.
 */
internal fun destructiveRowColor(backgrounds: List<Color>, base: Color = DestructiveBase): Color {
    val lightPanel = backgrounds.isNotEmpty() && backgrounds.all { it.luminance() > 0.5 }
    val target = if (lightPanel) Color.Black else Color.White
    for (step in 0..20) {
        val candidate = androidx.compose.ui.graphics.lerp(base, target, step / 20f).copy(alpha = 1f)
        if (backgrounds.all { contrastRatio(candidate, it) >= MinTextContrast }) return candidate
    }
    return target
}

/** Whether the list has rows off its top and bottom edges, for the "more this way" arrows. */
internal data class MenuMoreCue(val above: Boolean, val below: Boolean)

/**
 * Which directions have more rows. [firstIndex] / [firstOffset] are the first visible row and how
 * far it is scrolled off the top; [lastIndex] / [lastEnd] are the last visible row and where it
 * ends, against [viewportEnd]; [total] counts the rows. An empty list has no cue.
 */
internal fun menuMoreCue(
    firstIndex: Int,
    firstOffset: Int,
    lastIndex: Int,
    lastEnd: Int,
    viewportEnd: Int,
    total: Int,
): MenuMoreCue {
    if (total <= 0) return MenuMoreCue(above = false, below = false)
    return MenuMoreCue(
        above = firstIndex > 0 || firstOffset > 0,
        below = lastIndex < total - 1 || lastEnd > viewportEnd,
    )
}


@Composable
fun PspContextMenuOverlay(
    title: String,
    rows: List<PspMenuRow>,
    selectedIndex: Int,
    onRowActivated: (index: Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    // Default keeps the XMB's light PSP-style scrim (wave visible behind); busier hosts
    // (e.g. the Artwork Studio) pass a darker one so the menu reads clearly.
    scrim: Color = Color(0x40000000),
    // Ask the host to blur what is behind the panel (Android 12+; see LocalPfpBackdropBlur).
    allowBlur: Boolean = true,
) {
    RaiseBackdropBlur(allowBlur)
    // The Options cue, once, when the panel appears.
    val optionSounds = com.playfieldportal.core.ui.sound.LocalMenuSounds.current
    androidx.compose.runtime.LaunchedEffect(Unit) { optionSounds.play(com.playfieldportal.core.ui.sound.MenuSound.OPTIONS) }
    // The panel sits on the same backdrop as the full-screen pages it opens over (the App Drawer,
    // Settings, the detail screens): the storefront's deep-to-mid gradient from the theme's
    // darkened anchors. The raw wave colour it used before is the theme accent itself, so a bright
    // accent made the menu lighter than the page behind it, and its light text hard to read.
    val storefront = deriveStorefrontColors()
    val listState = rememberLazyListState()
    // One warning colour that reads on the panel both unfocused and under the cursor.
    val cursorFill = menuCursorFill()
    val warningColor = remember(storefront.backgroundDeep, storefront.backgroundMid, cursorFill) {
        val panel = listOf(storefront.backgroundDeep, storefront.backgroundMid)
        destructiveRowColor(panel + panel.map { cursorFill.compositeOver(it) })
    }
    val moreCue by remember(listState, rows.size) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            menuMoreCue(
                firstIndex = listState.firstVisibleItemIndex,
                firstOffset = listState.firstVisibleItemScrollOffset,
                lastIndex = last?.index ?: -1,
                lastEnd = last?.let { it.offset + it.size } ?: 0,
                viewportEnd = info.viewportEndOffset - info.afterContentPadding,
                total = rows.size,
            )
        }
    }

    LaunchedEffect(selectedIndex) {
        if (rows.isNotEmpty()) {
            listState.animateScrollToItem(selectedIndex.coerceIn(0, rows.size - 1))
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(scrim)
            .clickable(onClick = onDismiss),
    ) {
        // Right-edge column on the storefront backdrop (0.88 alpha), so the wave still shows through.
        BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(PanelWidth.coerceAtMost((maxWidth - PanelEdgeMargin).coerceAtLeast(0.dp)))
                .background(Brush.verticalGradient(listOf(storefront.backgroundDeep, storefront.backgroundMid)))
                .clickable(onClick = {}) // consume clicks so the scrim isn't triggered inside
                .padding(start = PanelContentStart, end = 40.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            // ── Title ─────────────────────────────────────────────────────
            Text(
                text = title,
                fontSize = 19.sp,
                fontWeight = FontWeight.Light,
                color = themedText(Color.White.copy(alpha = 0.92f)),
                style = TextStyle(shadow = menuTextShadowFor(themedText(Color.White.copy(alpha = 0.92f)))),
                maxLines = 2,
                // Inset so the title never sits on the screen edge, and lines up with the labels.
                modifier = Modifier.padding(start = RowTextInset, bottom = 10.dp),
            )
            // Thin underline rule beneath the title.
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = RowTextInset, end = 8.dp)
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.30f)),
            )

            // ── Items ──────────────────────────────────────────────────────
            Box(Modifier.padding(top = 10.dp)) {
                LazyColumn(state = listState) {
                    itemsIndexed(rows) { index, row ->
                        row.header?.let { PspContextMenuGroupHeader(it, first = index == 0) }
                        PspContextMenuRow(
                            row          = row,
                            isSelected   = index == selectedIndex,
                            warningColor = warningColor,
                            onClick      = { onRowActivated(index) },
                        )
                    }
                }
                // "More this way" cues, only while rows exist off that edge (scrolled by pad or touch).
                if (moreCue.above) {
                    PspMoreArrow(up = true, backdrop = storefront.backgroundDeep, modifier = Modifier.align(Alignment.TopCenter))
                }
                if (moreCue.below) {
                    PspMoreArrow(up = false, backdrop = storefront.backgroundMid, modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        }
    }
}

/** A small solid triangle on a short fade of the panel colour, marking rows beyond an edge. */
@Composable
private fun PspMoreArrow(up: Boolean, backdrop: Color, modifier: Modifier = Modifier) {
    val fade = if (up) listOf(backdrop, backdrop.copy(alpha = 0f)) else listOf(backdrop.copy(alpha = 0f), backdrop)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(16.dp)
            .background(Brush.verticalGradient(fade)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.width(12.dp).height(7.dp)) {
            val path = Path().apply {
                if (up) {
                    moveTo(size.width / 2f, 0f); lineTo(size.width, size.height); lineTo(0f, size.height)
                } else {
                    moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2f, size.height)
                }
                close()
            }
            drawPath(path, Color.White.copy(alpha = 0.9f))
        }
    }
}

/** A small warning triangle with a bar and dot, drawn in code, so colour is not the only cue. */
@Composable
private fun PspWarningGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(14.dp)) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.11f
        drawPath(
            Path().apply { moveTo(w / 2f, stroke); lineTo(w - stroke, h - stroke); lineTo(stroke, h - stroke); close() },
            color,
            style = Stroke(width = stroke, join = StrokeJoin.Round),
        )
        drawLine(color, Offset(w / 2f, h * 0.38f), Offset(w / 2f, h * 0.62f), strokeWidth = stroke)
        drawCircle(color, radius = stroke * 0.7f, center = Offset(w / 2f, h * 0.78f))
    }
}

@Composable
private fun PspContextMenuRow(
    row: PspMenuRow,
    isSelected: Boolean,
    warningColor: Color,
    onClick: () -> Unit,
) {
    // Accent-tinted so the cursor follows the chosen color scheme; menuCursorFill blends it toward
    // white so a dark theme accent still reads clearly on the scrim.
    val cursorFill = menuCursorFill()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // The Settings rows' cursor: one solid accent-tinted fill across the whole row, no
            // border or rounding — so a menu and the page it opens over highlight the same way.
            .background(if (isSelected) cursorFill else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = RowTextInset, vertical = 12.dp),
    ) {
        val labelColor = when {
            // A soft warning tone chosen to pass contrast on the panel and under the cursor
            // (destructiveRowColor); the warning glyph before it means colour is not the only cue.
            row.isDestructive               -> warningColor
            // The focused label is plain white on the cursor, as a focused Settings row's is;
            // the others take the user's Main font colour when set, at their own weight
            // (values, chevrons and group headers take the Sub colour).
            isSelected                      -> Color.White
            else                            -> themedText(Color.White.copy(alpha = 0.62f))
        }
        // Values and the submenu arrow share one sub-text weight.
        val subColor = themedSubText(Color.White.copy(alpha = if (isSelected) 0.85f else 0.55f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (row.isDestructive) {
                PspWarningGlyph(warningColor)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = row.label,
                fontSize = if (isSelected) 16.sp else 15.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = labelColor,
                style = TextStyle(shadow = menuTextShadowFor(labelColor)),
                // A value fills the row so it can be pushed to the far edge; without one the label
                // keeps hugging its text, which is what puts a checkmark right beside the words
                // instead of stranding it across the panel.
                modifier = if (row.value != null) Modifier.weight(1f) else Modifier.weight(1f, fill = false),
            )
            if (row.value != null) {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = row.value,
                    // One step below the label in both states, and dimmer: the label is what the
                    // row IS and the value is what it currently says, so the value must never win
                    // the row. Both brighten together when the cursor arrives.
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    color = subColor,
                    style = TextStyle(shadow = menuTextShadowFor(subColor)),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (row.checked) {
                Spacer(Modifier.width(10.dp))
                if (!MenuGlyphOverride(MENU_CHECK_KEY, 15.dp)) {
                    PfpCheckMark(Color.White, size = 15.dp, shadow = com.playfieldportal.core.ui.theme.ThemeTokens.TextShadow.color)
                }
            }
            if (row.opensMenu) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "›",
                    fontSize = 17.sp,
                    color = subColor,
                    style = TextStyle(shadow = menuTextShadowFor(subColor)),
                )
            }
        }
    }
}

/** A group's name, with a faint rule above every group but the first (the title has its own). */
@Composable
private fun PspContextMenuGroupHeader(label: String, first: Boolean) {
    Column(Modifier.fillMaxWidth().padding(top = if (first) 0.dp else 8.dp)) {
        if (!first) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = RowTextInset, end = 8.dp)
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.14f)),
            )
        }
        val color = themedSubText(Color.White.copy(alpha = 0.45f))
        Text(
            text = label,
            fontSize = 11.sp,
            color = color,
            style = TextStyle(shadow = menuTextShadowFor(color)),
            modifier = Modifier.padding(start = RowTextInset, top = if (first) 0.dp else 8.dp),
        )
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

/** A card menu: four headed groups, settings as values, the destructive row last. */
@CombinedPreviews
@Composable
fun PspContextMenuGroupedPreview() {
    PfpPreview {
        PspContextMenuOverlay(
            title = "PSP Memory Card",
            rows = listOf(
                PspMenuRow("Scan for Games", header = "Games"),
                PspMenuRow("Update Metadata", header = "Update"),
                PspMenuRow("Fetch Missing Artwork"),
                PspMenuRow("Icon Display", value = "Global: Box Art", opensMenu = true, header = "Display"),
                PspMenuRow("Pin to Top", value = "Off"),
                PspMenuRow("Library Manager", header = "Manage"),
                PspMenuRow("Hide Card"),
                PspMenuRow("Remove Card", isDestructive = true),
            ),
            selectedIndex = 0,
            onRowActivated = {},
            onDismiss = {},
        )
    }
}

/** The Games Filter root: two rows that name a list, with the setting pinned to the right edge. */
@CombinedPreviews
@Composable
fun PspContextMenuValueRowsPreview() {
    PfpPreview {
        PspContextMenuOverlay(
            title = "Filter",
            rows = listOf(
                PspMenuRow("Search", value = "\"zel\""),
                PspMenuRow("Sort", value = "Recently Played", opensMenu = true),
                PspMenuRow("Clear Search"),
            ),
            selectedIndex = 0,
            onRowActivated = {},
            onDismiss = {},
        )
    }
}

@CombinedPreviews
@Composable
fun PspContextMenuPreview() {
    val rows = listOf(
        PspMenuRow("Play"),
        PspMenuRow("Information"),
        PspMenuRow("Delete", isDestructive = true),
        PspMenuRow("Add to Favorites", checked = true),
        PspMenuRow("Assign Album", opensMenu = true),
    )
    PfpPreview {
        PspContextMenuOverlay(
            title = "Gran Turismo 4",
            rows = rows,
            selectedIndex = 1,
            onRowActivated = {},
            onDismiss = {},
        )
    }
}
