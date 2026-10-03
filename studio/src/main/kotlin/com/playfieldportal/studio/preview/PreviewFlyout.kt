package com.playfieldportal.studio.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The options flyout: a replica of core-ui PspContextMenuOverlay (300 dp right panel, waveColor at
 * 75 %, light scrim, instant open, submenus swapping in place) plus the pure rows it shows. The
 * Games filter reuses the same panel (see PreviewFilter).
 */

/** One row of the flyout; [PspMenuRow] plus the submenu marker the launcher's handler keeps by id. */
data class PreviewMenuRow(
    val id: String,
    val label: String,
    val destructive: Boolean = false,
    val checked: Boolean = false,
    /** The row's current setting, pinned to the panel's right edge and dimmer than the label. */
    val value: String? = null,
    /** Enter swaps the panel to this row's submenu instead of acting. */
    val opensSubmenu: Boolean = false,
)

enum class FlyoutKind { OPTIONS, GAMES_FILTER }

/** [ROOT] is the first panel; [SUBMENU] is a row's own list (collections); [SORT] is the filter's Sort group. */
enum class FlyoutMenu { ROOT, SUBMENU, SORT }

/**
 * An open flyout. [rootCursor] remembers the root row that led into the current submenu, so back
 * lands the cursor where the eye already is.
 */
data class FlyoutState(
    val kind: FlyoutKind,
    val menu: FlyoutMenu = FlyoutMenu.ROOT,
    val cursor: Int = 0,
    val rootCursor: Int = 0,
)

/** The sample row types the launcher builds different menus for. */
enum class RowKind { GAME, VIDEO, CONSOLE, LIBRARY, DEFAULT }

object PreviewFlyout {

    const val ADD_COLLECTION = "add_collection"
    const val ADD_PLAYLIST = "add_playlist"

    fun kindOf(row: SampleContent.Row): RowKind = when {
        row.isGame -> RowKind.GAME
        row.slotKey == "item_video_file" -> RowKind.VIDEO
        row.slotKey.startsWith("sysicon_") && row.children.isNotEmpty() -> RowKind.CONSOLE
        row.slotKey.startsWith("item_memcard_") -> RowKind.LIBRARY
        else -> RowKind.DEFAULT
    }

    private fun row(label: String, destructive: Boolean = false, value: String? = null, submenu: String? = null) =
        PreviewMenuRow(id = submenu ?: label, label = label, destructive = destructive, value = value, opensSubmenu = submenu != null)

    /** The root rows of [row]'s options menu, in the launcher's order. */
    fun optionRows(row: SampleContent.Row): List<PreviewMenuRow> = when (kindOf(row)) {
        RowKind.GAME -> listOf(
            row("View Game Details"), row("View Shiba Coins"), row("Add to Favorites"),
            row("Add to Collection", submenu = ADD_COLLECTION), row("Change Emulator", value = "Default"),
            row("Icon Display", value = "Box Art"), row("Fetch Artwork"), row("Hide from Games"),
            row("Remove from Library", destructive = true),
        )
        RowKind.VIDEO -> listOf(
            row("Play"), row("Resume"), row("Add to Favorites"),
            row("Add to Playlist", submenu = ADD_PLAYLIST), row("Details"),
            row("Remove From Library", destructive = true),
        )
        RowKind.CONSOLE -> listOf(
            row("Scan This Console"), row("Update Metadata"), row("Scrape Missing Artwork"),
            row("Icon Display", value = "Console Art"), row("Pin To Top"), row("Open in Library Manager"),
            row("Hide From Games"), row("Remove Memory Card", destructive = true),
        )
        RowKind.LIBRARY -> listOf(row("Open"), row("Scan Library"), row("Manage in Settings"))
        RowKind.DEFAULT -> listOf(row("Open"), row("Information"))
    }

    /** A submenu's rows: the lists the item can join, with the ones it is already in checked. */
    fun submenuRows(parentId: String): List<PreviewMenuRow> = when (parentId) {
        ADD_COLLECTION -> listOf(
            PreviewMenuRow("Favorites", "Favorites", checked = true),
            PreviewMenuRow("Co-op Night", "Co-op Night"),
            PreviewMenuRow("Backlog", "Backlog"),
        )
        ADD_PLAYLIST -> listOf(
            PreviewMenuRow("Road Trip", "Road Trip", checked = true),
            PreviewMenuRow("Focus", "Focus"),
        )
        else -> emptyList()
    }

    private fun optionsRoot(state: PreviewNavState): List<PreviewMenuRow> =
        PreviewNav.selectedRow(state)?.let(::optionRows).orEmpty()

    /** The rows of the open flyout's current panel; empty when none is open. */
    fun rows(state: PreviewNavState): List<PreviewMenuRow> {
        val f = state.flyout ?: return emptyList()
        return when (f.kind) {
            FlyoutKind.GAMES_FILTER -> PreviewFilter.rows(state.filter, f.menu)
            FlyoutKind.OPTIONS -> when (f.menu) {
                FlyoutMenu.SUBMENU -> optionsRoot(state).getOrNull(f.rootCursor)?.let { submenuRows(it.id) }.orEmpty()
                else -> optionsRoot(state)
            }
        }
    }

    /** The panel heading: the item's name, "Filter" / "Sort", or the submenu row's label. */
    fun title(state: PreviewNavState): String {
        val f = state.flyout ?: return ""
        return when (f.kind) {
            FlyoutKind.GAMES_FILTER -> if (f.menu == FlyoutMenu.SORT) "Sort" else "Filter"
            FlyoutKind.OPTIONS ->
                if (f.menu == FlyoutMenu.SUBMENU) optionsRoot(state).getOrNull(f.rootCursor)?.label.orEmpty()
                else PreviewNav.selectedRow(state)?.title.orEmpty()
        }
    }
}

// ── Rendering ────────────────────────────────────────────────────────────────

private val PanelWidth = 300.dp

// PspContextMenu TextDropShadow: black .75, (0, 2), blur 4.
private val MenuTextShadow = Shadow(Color.Black.copy(alpha = 0.75f), Offset(0f, 2f), 4f)

/**
 * PspContextMenuOverlay over the frame: light scrim (a click dismisses), then the right-edge panel
 * (a click inside is swallowed). Instant: no enter or exit transition, like the launcher.
 */
@Composable
fun BoxScope.FlyoutPanel(model: XmbPreviewModel, nav: PreviewNavState, onNav: (PreviewNavAction) -> Unit) {
    val flyout = nav.flyout ?: return
    val rows = PreviewFlyout.rows(nav)
    val listState = rememberLazyListState()
    LaunchedEffect(flyout.cursor, flyout.menu) {
        if (rows.isNotEmpty()) listState.scrollToItem(flyout.cursor.coerceIn(0, rows.lastIndex))
    }
    val none = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x40000000))
            .clickable(interactionSource = none, indication = null) { onNav(PreviewNavAction.DismissFlyout) },
    )
    Column(
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(PanelWidth)
            .background(model.menuPanelBackdrop)
            .clickable(interactionSource = none, indication = null) {} // swallow clicks inside the panel
            .padding(start = 28.dp, end = 40.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = PreviewFlyout.title(nav),
            fontSize = 19.sp,
            fontWeight = FontWeight.Light,
            color = Color.White.copy(alpha = 0.92f),
            style = TextStyle(shadow = MenuTextShadow),
            maxLines = 2,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        Box(Modifier.fillMaxWidth().padding(end = 8.dp).height(1.dp).background(Color.White.copy(alpha = 0.30f)))
        LazyColumn(state = listState, modifier = Modifier.padding(top = 10.dp)) {
            itemsIndexed(rows) { index, row ->
                FlyoutRow(model, row, selected = index == flyout.cursor, onClick = { onNav(PreviewNavAction.ClickFlyoutRow(index)) })
            }
        }
    }
}

@Composable
private fun FlyoutRow(model: XmbPreviewModel, row: PreviewMenuRow, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // The cursor: a glow band that brightens toward the screen edge; no border, no box.
            .background(
                Brush.horizontalGradient(
                    0f to Color.Transparent,
                    1f to if (selected) model.menuCursorEdge.copy(alpha = 0.40f) else Color.Transparent,
                ),
            )
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.label,
                fontSize = if (selected) 16.sp else 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    row.destructive && selected -> Color(0xFFFF7070)
                    row.destructive -> Color(0xAAFF7070)
                    selected -> Color.White
                    else -> Color.White.copy(alpha = 0.62f)
                },
                style = TextStyle(shadow = MenuTextShadow),
                // A value fills the row so it can sit on the far edge; otherwise the label hugs its
                // text, which keeps a check mark right beside the words.
                modifier = if (row.value != null) Modifier.weight(1f) else Modifier.weight(1f, fill = false),
            )
            if (row.value != null) {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = row.value,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color.White.copy(alpha = if (selected) 0.85f else 0.55f),
                    style = TextStyle(shadow = MenuTextShadow),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (row.checked) {
                Spacer(Modifier.width(10.dp))
                CheckMark(model)
            }
        }
    }
}

/** The `menu_check` slot's override when the theme has one; otherwise the built-in white tick (15 dp). */
@Composable
private fun CheckMark(model: XmbPreviewModel) {
    val override = model.iconOverrides[MENU_CHECK_KEY]
    if (override != null) {
        Image(bitmap = override, contentDescription = null, modifier = Modifier.size(15.dp))
        return
    }
    Canvas(Modifier.size(15.dp)) {
        val tick = Path().apply {
            moveTo(size.width * 0.15f, size.height * 0.55f)
            lineTo(size.width * 0.40f, size.height * 0.78f)
            lineTo(size.width * 0.85f, size.height * 0.25f)
        }
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        // Shadow first, offset like the menu text's, then the tick.
        translate(top = MenuTextShadow.offset.y) { drawPath(tick, MenuTextShadow.color, style = stroke) }
        drawPath(tick, Color.White, style = stroke)
    }
}

private const val MENU_CHECK_KEY = "menu_check"
