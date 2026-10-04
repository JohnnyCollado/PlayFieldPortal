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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
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

/** One row of the flyout: core-ui PspMenuRow. */
data class PreviewMenuRow(
    val id: String,
    val label: String,
    val destructive: Boolean = false,
    val checked: Boolean = false,
    /** The row's current setting, pinned to the panel's right edge and dimmer than the label. */
    val value: String? = null,
    /** The row opens a list (drawn with a ›); Enter swaps the panel to it when the preview has its rows. */
    val opensSubmenu: Boolean = false,
    /** A group name drawn above this row, set on the first row of each group. */
    val header: String? = null,
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

/** The sample row types the launcher builds different menus for (XMBViewModel.contextMenuKind); NONE has no menu. */
enum class RowKind {
    GAME, UMD, ALL_GAMES, FAVORITES, CUSTOM_CARD, CONSOLE,
    MEDIA_CARD, PHOTO_FILE, ALBUM, VIDEO_FILE, VIDEO_LIBRARY, VIDEO_PLAYLIST, NOW_PLAYING,
    APP, SOCIAL_ACCOUNT, SHIBA, NONE,
}

object PreviewFlyout {

    const val GAME_ICON_DISPLAY = "icon_display"
    const val CARD_ICON_DISPLAY = "icon_display_platform"

    // IconDisplayMode labels, in order; the default is Custom Icon (ICON0).
    private val ICON_DISPLAY_MODES = listOf("Custom Icon", "Box Art", "Physical Media", "3D Box Art")
    private const val DEFAULT_ICON_DISPLAY = "Custom Icon"

    fun kindOf(row: SampleContent.Row): RowKind = row.menu

    private fun row(label: String, destructive: Boolean = false, value: String? = null, submenu: Boolean = false) =
        PreviewMenuRow(id = label, label = label, destructive = destructive, value = value, opensSubmenu = submenu)

    /** XmbLists.group: [rows] with [header] on the first of them. */
    private fun MutableList<PreviewMenuRow>.group(header: String, vararg rows: PreviewMenuRow) =
        rows.forEachIndexed { i, r -> add(if (i == 0) r.copy(header = header) else r) }

    /**
     * The root rows of [row]'s options menu, in the launcher's order, or empty where it has none.
     * [parent] is the row whose list [row] is shown in (null at a category root), and [categoryLabel]
     * the category on screen; both name the "Hide from …" rows.
     */
    fun optionRows(row: SampleContent.Row, parent: SampleContent.Row? = null, categoryLabel: String = ""): List<PreviewMenuRow> =
        buildList {
            when (kindOf(row)) {
                // GameContextMenuItems.gameContextMenuItems, for a single-disc game in a game list
                // (a custom card's list offers Remove from Card in place of the library delete).
                RowKind.GAME -> {
                    val inCard = parent?.menu == RowKind.CUSTOM_CARD
                    val favorite = if (parent?.menu == RowKind.FAVORITES) "On" else "Off"
                    group(
                        "Play",
                        row("View Game Details"), row("View Shiba Coins"),
                        row("Change Emulator", value = "Default", submenu = true), row("Insert as UMD"),
                    )
                    if (inCard) {
                        group(
                            "Library", row("Favorite", value = favorite), row("Add to Card", submenu = true),
                            row("Remove from Card"), row("Select Multiple"),
                        )
                    } else {
                        group("Library", row("Favorite", value = favorite), row("Add to Card", submenu = true), row("Select Multiple"))
                    }
                    group("Arrange", row("Pin to Top", value = "Off"))
                    group(
                        "Customize",
                        PreviewMenuRow(GAME_ICON_DISPLAY, "Icon Display", value = DEFAULT_ICON_DISPLAY, opensSubmenu = true),
                        row("Fetch Artwork"),
                    )
                    if (row.subtitle == "Windows") group("PC", row("Install Goldberg Achievements"), row("Export Game"))
                    val manage = mutableListOf(row("Manage Custom Cards"), row("Show File Location"), row("Hide from ${parent?.title ?: "All Games"}"))
                    if (!inCard) manage += row("Remove from Library", destructive = true)
                    group("Manage", *manage.toTypedArray())
                }
                // The UMD slot: the inserted game's menu with the slot's own rows (umd = RECENT).
                RowKind.UMD -> {
                    group(
                        "Play",
                        row("View Game Details"), row("View Shiba Coins"),
                        row("Change Emulator", value = "Default", submenu = true), row("Insert as UMD"), row("Eject UMD"),
                    )
                    group("Library", row("Favorite", value = "Off"), row("Add to Card", submenu = true))
                    group(
                        "Customize",
                        PreviewMenuRow(GAME_ICON_DISPLAY, "Icon Display", value = DEFAULT_ICON_DISPLAY, opensSubmenu = true),
                        row("Fetch Artwork"),
                    )
                    group(
                        "Manage",
                        row("Manage Custom Cards"), row("Show File Location"), row("Remove from Library", destructive = true),
                    )
                }
                // XmbLists.rootRowMenuItems for Favorites (controller: no Open row).
                RowKind.FAVORITES -> add(row("Sort", value = "Global: Title", submenu = true))
                // XmbLists: a gaming custom card.
                RowKind.CUSTOM_CARD -> {
                    add(row("Add Games"))
                    add(row("Sort", value = "Global: Title", submenu = true))
                    add(row("Pin to Top", value = "Off"))
                    add(row("Rename Card"))
                    add(row("Manage Custom Cards"))
                    add(row("Delete Custom Card", destructive = true))
                }
                // MediaContextMenuItems.
                RowKind.PHOTO_FILE -> {
                    add(row("Set as Launcher Wallpaper"))
                    add(row("Remove from Library", destructive = true))
                }
                RowKind.ALBUM -> {
                    add(row("Scan Album"))
                    add(row("Manage in Settings"))
                }
                RowKind.VIDEO_FILE -> {
                    add(row("Favorite", value = if (parent?.slotKey == "item_video_favorites") "On" else "Off"))
                    add(row("Add to Playlist", submenu = true))
                    if (parent?.menu == RowKind.VIDEO_PLAYLIST) add(row("Remove from this Playlist"))
                    add(row("Remove from Library", destructive = true))
                }
                RowKind.VIDEO_LIBRARY -> {
                    add(row("Scan Library"))
                    add(row("Manage in Settings"))
                }
                RowKind.VIDEO_PLAYLIST -> {
                    add(row("Rename Playlist"))
                    add(row("Delete Playlist", destructive = true))
                }
                RowKind.NOW_PLAYING -> {
                    add(row("Pause"))
                    add(row("Visualizer"))
                    add(row("Stop and Close"))
                }
                RowKind.SOCIAL_ACCOUNT -> add(row("Reconnect"))
                // MemoryCardContextMenuItems.allGamesMenuItems.
                RowKind.ALL_GAMES -> {
                    group("Games", row("Scan All Cards"))
                    group("Update", row("Update Metadata"), row("Fetch Missing Artwork"), row("Relink Artwork"))
                    group(
                        "Display",
                        row("Icon Display", value = DEFAULT_ICON_DISPLAY, submenu = true),
                        row("Sort", value = "Global: Title", submenu = true),
                        row("Global Sort", value = "Title", submenu = true),
                    )
                    group("Manage", row("Library Manager"))
                }
                // MemoryCardContextMenuItems.platformCardMenuItems.
                RowKind.CONSOLE -> {
                    val windows = row.slotKey == "sysicon_windows"
                    if (windows) group("Games", row("Scan for Games"), row("Import PC Games")) else group("Games", row("Scan for Games"))
                    if (windows) {
                        group("Update", row("Update Metadata"), row("Fetch Missing Artwork"), row("Match Achievements"))
                    } else {
                        group("Update", row("Update Metadata"), row("Fetch Missing Artwork"))
                    }
                    group(
                        "Display",
                        PreviewMenuRow(CARD_ICON_DISPLAY, "Icon Display", value = "Global: $DEFAULT_ICON_DISPLAY", opensSubmenu = true),
                        row("Sort", value = "Global: Title", submenu = true),
                        row("Pin to Top", value = "Off"),
                    )
                    if (windows) {
                        group("Manage", row("Library Manager"), row("Hide Card"))
                    } else {
                        group("Manage", row("Library Manager"), row("Hide Card"), row("Remove Card", destructive = true))
                    }
                }
                // MediaContextMenuItems.mediaCardMenuItems: "Scan Photos" / "Scan Music" / "Scan Videos".
                RowKind.MEDIA_CARD -> {
                    add(row("Scan ${row.title}"))
                    add(row("Manage in Settings"))
                }
                // feature-appbar AppMenuItems.appMenuItems, for a controller (the sample's Play Store is a system app).
                RowKind.APP -> {
                    group(
                        "Library",
                        row("Edit App Details"), row("Mark as Game"), row("Favorite", value = "Off"), row("Add to Card", submenu = true),
                    )
                    group(
                        "Arrange",
                        row("Pin to Top", value = "Off"), row("Move to Category", submenu = true),
                        row("Add to Category", submenu = true), row("Remove from Category"),
                    )
                    val manage = mutableListOf(row("Rename Shortcut"), row("Hide from $categoryLabel"), row("Hide Everywhere"), row("App Info"))
                    if (row.title != "Play Store") manage += row("Uninstall", destructive = true)
                    group("Manage", *manage.toTypedArray())
                }
                // The Shiba hub rows share one menu (player card, All Tracked Games, Untracked).
                RowKind.SHIBA -> {
                    add(row("Auto-Matching"))
                    add(row("Update Installed Achievements"))
                }
                RowKind.NONE -> Unit
            }
        }

    /**
     * A submenu's rows, for the submenus the preview can show: a game's or a card's Icon Display
     * picker (openIconDisplayPickerMenu / openPlatformIconDisplayPickerMenu), on the default. Every
     * other › row lists device state (emulators, cards, categories) and says so on Enter instead.
     */
    fun submenuRows(parentId: String): List<PreviewMenuRow> {
        val first = when (parentId) {
            GAME_ICON_DISPLAY -> "Use Default ($DEFAULT_ICON_DISPLAY)"
            CARD_ICON_DISPLAY -> "Use Global Setting ($DEFAULT_ICON_DISPLAY)"
            else -> return emptyList()
        }
        return listOf(PreviewMenuRow(first, first, checked = true)) + ICON_DISPLAY_MODES.map { PreviewMenuRow(it, it) }
    }

    private fun optionsRoot(state: PreviewNavState): List<PreviewMenuRow> {
        val row = PreviewNav.selectedRow(state) ?: return emptyList()
        return optionRows(row, PreviewNav.parentRow(state), SampleContent.categories[state.category].label)
    }

    /** The selected row has an options menu, so Y opens one (and the idle hint offers it). */
    fun hasOptions(state: PreviewNavState): Boolean = optionsRoot(state).isNotEmpty()

    // The player card's menu is titled for the card, not the rank on its row.
    private fun menuTitle(row: SampleContent.Row): String =
        if (row.leading == SampleContent.Leading.LEVEL) "Player Card" else row.title

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
                if (f.menu == FlyoutMenu.SUBMENU) {
                    optionsRoot(state).getOrNull(f.rootCursor)?.label.orEmpty()
                } else {
                    PreviewNav.selectedRow(state)?.let { menuTitle(it) }.orEmpty()
                }
        }
    }
}

// ── Rendering ────────────────────────────────────────────────────────────────

private val PanelWidth = 300.dp

// PspContextMenu TextDropShadow: black .75, (0, 2), blur 4.
internal const val MENU_SHADOW_ALPHA = 0.75f
private val MenuTextShadow = Shadow(Color.Black.copy(alpha = MENU_SHADOW_ALPHA), Offset(0f, 2f), 4f)

/** PspContextMenu.menuTextShadowFor: the shadow dims with the fill, so a 45% header is not darker behind than in front. */
internal fun menuTextShadowFor(fill: Color): Shadow =
    MenuTextShadow.copy(color = MenuTextShadow.color.copy(alpha = MENU_SHADOW_ALPHA * fill.alpha))

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
            .focusProperties { canFocus = false }
            .clickable(interactionSource = none, indication = null) { onNav(PreviewNavAction.DismissFlyout) },
    )
    Column(
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(PanelWidth)
            .background(Brush.verticalGradient(optionsPanelBackdrop(model)))
            .focusProperties { canFocus = false }
            .clickable(interactionSource = none, indication = null) {} // swallow clicks inside the panel
            .padding(start = 28.dp, end = 40.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = PreviewFlyout.title(nav),
            fontSize = 19.sp,
            fontWeight = FontWeight.Light,
            // PspContextMenu: themedText(White @ 0.92).
            color = model.textOr(Color.White.copy(alpha = 0.92f)),
            style = TextStyle(shadow = menuTextShadowFor(model.textOr(Color.White.copy(alpha = 0.92f)))),
            maxLines = 2,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        Box(Modifier.fillMaxWidth().padding(end = 8.dp).height(1.dp).background(Color.White.copy(alpha = 0.30f)))
        LazyColumn(state = listState, modifier = Modifier.padding(top = 10.dp)) {
            itemsIndexed(rows) { index, row ->
                row.header?.let { FlyoutGroupHeader(model, it, first = index == 0) }
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
            // The cursor: PspContextMenu's solid row fill, the Settings rows' menuCursorFill.
            .background(if (selected) lerp(model.drillCursor, Color.White, 0.20f).copy(alpha = 0.34f) else Color.Transparent)
            .focusProperties { canFocus = false }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        val labelColor = when {
            row.destructive && selected -> Color(0xFFFF7070)
            row.destructive -> Color(0xAAFF7070)
            // PspContextMenu: the focused label is plain white on the cursor (as in Settings);
            // the others are themedText at their own weight.
            selected -> Color.White
            else -> model.textOr(Color.White.copy(alpha = 0.62f))
        }
        // PspContextMenu: values and the submenu arrow are sub text, at one weight.
        val subColor = model.subTextOr(Color.White.copy(alpha = if (selected) 0.85f else 0.55f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.label,
                fontSize = if (selected) 16.sp else 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = labelColor,
                style = TextStyle(shadow = menuTextShadowFor(labelColor)),
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
                    color = subColor,
                    style = TextStyle(shadow = menuTextShadowFor(subColor)),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (row.checked) {
                Spacer(Modifier.width(10.dp))
                CheckMark(model)
            }
            if (row.opensSubmenu) {
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

/** PspContextMenuGroupHeader: a group's name, with a faint rule above every group but the first. */
@Composable
private fun FlyoutGroupHeader(model: XmbPreviewModel, label: String, first: Boolean) {
    Column(Modifier.fillMaxWidth().padding(top = if (first) 0.dp else 8.dp)) {
        if (!first) {
            Box(Modifier.fillMaxWidth().padding(end = 8.dp).height(1.dp).background(Color.White.copy(alpha = 0.14f)))
        }
        val headerColor = model.subTextOr(Color.White.copy(alpha = 0.45f))
        Text(
            text = label,
            fontSize = 11.sp,
            color = headerColor,
            style = TextStyle(shadow = menuTextShadowFor(headerColor)),
            modifier = Modifier.padding(top = if (first) 0.dp else 8.dp),
        )
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

/**
 * PspContextMenu's panel: the full-screen storefront backdrop (the drawer's deep-to-mid gradient),
 * never the raw accent — a bright accent made the old panel lighter than the page behind it.
 */
internal fun optionsPanelBackdrop(model: XmbPreviewModel): List<Color> {
    val sf = com.playfieldportal.studio.preview.screens.drawerPalette(model)
    return listOf(sf.backgroundDeep, sf.backgroundMid)
}
