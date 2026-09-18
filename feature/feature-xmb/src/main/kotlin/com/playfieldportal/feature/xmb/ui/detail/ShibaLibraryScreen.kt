package com.playfieldportal.feature.xmb.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.achievement.ShibaLevelMedallion
import com.playfieldportal.core.ui.components.PspContextMenuOverlay
import com.playfieldportal.core.ui.components.PspMenuRow
import com.playfieldportal.core.ui.components.XmbHeaderPill
import com.playfieldportal.core.ui.detail.DetailContentPadding
import com.playfieldportal.core.ui.detail.DetailPalette
import com.playfieldportal.core.ui.detail.PfpDetailBackground
import com.playfieldportal.core.ui.detail.PfpDetailBreadcrumb
import com.playfieldportal.core.ui.detail.PfpDetailHelperFooter
import com.playfieldportal.core.ui.detail.detailPalette
import com.playfieldportal.feature.xmb.ui.PspIcon0Icon
import com.playfieldportal.feature.xmb.viewmodel.ShibaLibraryMode
import kotlin.math.roundToInt

// ── Achievements library screen ───────────────────────────────────────────────
//
// docs/plans/PFP_Achievements_Screen_Design.md: a console-native achievement browser built from the
// shared detail-page surfaces — the App Drawer-derived background and palette, the ◀ breadcrumb, and
// the permanent helper footer. Below the header sit a pinned Search row (navigation position 0) and
// a full-width list of quiet, separator-divided game rows. Tracked and Untracked share every
// dimension; only the right-hand information changes. Game art is the XMB's ICON0 tile. Triangle's
// Options menu is the shared PSP context menu, entering from the right over the still-visible list.

/** ICON0 is 144 × 80; rows draw it at this height. */
private val Icon0Height = 48.dp
private const val ICON0_ASPECT = 144f / 80f
private val CoinCellWidth = 52.dp
private val PercentColumnWidth = 80.dp
private val ShortBarWidth = 64.dp

/** Reserved in both views so the header is the same height with or without the summary. */
private val SummaryHeight = 48.dp

private fun LibraryCoinCounts.countFor(tier: ShibaTier): Int = when (tier) {
    ShibaTier.PLATINUM -> platinum
    ShibaTier.GOLD -> gold
    ShibaTier.SILVER -> silver
    ShibaTier.BRONZE -> bronze
}

private val ShibaLibraryMode.viewTitle: String
    get() = when (this) {
        ShibaLibraryMode.TRACKED -> "Tracked Games"
        ShibaLibraryMode.UNTRACKED -> "Untracked Games"
    }

@Composable
fun ShibaLibraryScreen(
    mode: ShibaLibraryMode,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenCoins: (ShibaCoinsTarget) -> Unit = {},
    pendingGamepadAction: GamepadAction? = null,
    onGamepadActionConsumed: () -> Unit = {},
    showTouchControls: Boolean = false,
    viewModel: ShibaLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(mode) { viewModel.load(mode) }
    LaunchedEffect(state.closed) {
        if (state.closed) {
            onClose()
            viewModel.onClosedHandled()
        }
    }
    LaunchedEffect(state.openCoins) {
        state.openCoins?.let { target ->
            onOpenCoins(target)
            viewModel.onOpenHandled()
        }
    }
    LaunchedEffect(pendingGamepadAction) {
        if (pendingGamepadAction != null) {
            viewModel.handleGamepadAction(pendingGamepadAction)
            onGamepadActionConsumed()
        }
    }

    // Focus follow, unchanged from the previous screen: every focus or order change SNAPS the focused
    // row to the 1/3-viewport line (clamped at the list edges, so the top rows sit flush under
    // Search). Instant, PSP-style — the built-in scroll animation is too slow for held input. The
    // snap also drops the keyed LazyColumn's anchor after a reorder, which otherwise keeps the
    // viewport glued to the old rows. Search is pinned above the list, so focusing it shows the top.
    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        snapshotFlow { state.rows.map { it.id } to state.focusPosition }.collect { (_, position) ->
            val third = listState.layoutInfo.viewportSize.height / 3
            listState.scrollToItem((position - 1).coerceAtLeast(0), scrollOffset = -third)
        }
    }

    val palette = detailPalette()
    PfpDetailBackground(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            PfpDetailBreadcrumb(
                title = "Achievements / ${state.mode.viewTitle}",
                subtitle = when (state.mode) {
                    ShibaLibraryMode.TRACKED -> gameCount(state.trackedCount)
                    ShibaLibraryMode.UNTRACKED -> gameCount(state.untrackedCount)
                },
                onBack = viewModel::close,
                // The design's darker header band: the page darkened in place, so it follows the theme.
                modifier = Modifier.background(headerShade(palette)),
                trailing = {
                    Box(Modifier.height(SummaryHeight), contentAlignment = Alignment.CenterEnd) {
                        if (state.mode == ShibaLibraryMode.TRACKED) LibrarySummaryBlock(state.summary, palette)
                    }
                },
            )

            SearchRow(
                query = state.query,
                editing = state.searchEditing,
                focused = state.searchFocused,
                palette = palette,
                onQueryChange = viewModel::setQuery,
                onClick = viewModel::onSearchClick,
                onEditEnded = viewModel::onSearchEditEnded,
            )

            Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
                if (state.rows.isEmpty()) {
                    // The shell stays; only the list area explains itself.
                    Text(
                        text = state.emptyMessage,
                        color = palette.textMuted,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(horizontal = DetailContentPadding + 10.dp, vertical = 24.dp),
                    )
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = DetailContentPadding),
                ) {
                    items(state.rows, key = { it.id }) { row ->
                        GameRow(
                            row = row,
                            focused = row.id == state.focusedRowId,
                            palette = palette,
                            onClick = { viewModel.onRowClick(row.id) },
                        )
                    }
                }
            }

            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                PfpDetailHelperFooter(items = shibaLibraryHelperItems(state), visible = !showTouchControls)
                // Touch mode: the footer hints fade, and the controller-only actions become pills in
                // the same reserved band (rows, Search and the breadcrumb are tappable already).
                if (showTouchControls && state.options == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        XmbHeaderPill(label = "Options", onClick = viewModel::openOptions)
                        XmbHeaderPill(
                            label = ShibaLibraryMode.entries.first { it != state.mode }.viewTitle,
                            onClick = { viewModel.switchSibling(1) },
                        )
                    }
                }
            }
        }

        // The Icon Display pattern: "Options" lists Filter (…) and Provider (…); each opens its own
        // list, titled by name, with the active choice checked.
        state.options?.let { menu ->
            PspContextMenuOverlay(
                title = menu.title,
                rows = state.optionRows.map { row -> PspMenuRow(label = row.label, checked = row.checked) },
                selectedIndex = menu.selectedIndex,
                onRowActivated = viewModel::onOptionActivated,
                onDismiss = viewModel::closeOptions,
            )
        }
    }
}

private fun gameCount(count: Int): String = if (count == 1) "1 game" else "$count games"

// ── Header summary ────────────────────────────────────────────────────────────

@Composable
private fun LibrarySummaryBlock(summary: LibrarySummary, palette: DetailPalette) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        ShibaLevelMedallion(level = summary.level, size = 40.dp, accent = palette.focus)
        SummaryStat(label = "NEXT LEVEL", value = "${(summary.nextLevelFraction * 100).roundToInt()}%", palette = palette) {
            ProgressLine(summary.nextLevelFraction, palette, Modifier.width(56.dp))
        }
        SummaryStat(label = "TOTAL", value = "%,d".format(summary.total), palette = palette)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CoinOrder.forEach { tier -> CoinCount(tier, summary.coins.countFor(tier), palette, iconSize = 18.dp) }
        }
    }
}

@Composable
private fun SummaryStat(
    label: String,
    value: String,
    palette: DetailPalette,
    below: @Composable () -> Unit = {},
) {
    Column {
        Text(label, color = palette.textMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, lineHeight = 12.sp, maxLines = 1, softWrap = false)
        Text(value, color = palette.textPrimary, fontSize = 16.sp, lineHeight = 20.sp, maxLines = 1, softWrap = false)
        below()
    }
}

// ── Game rows ─────────────────────────────────────────────────────────────────

@Composable
private fun GameRow(
    row: ShibaLibraryRow,
    focused: Boolean,
    palette: DetailPalette,
    onClick: () -> Unit,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(RowHeight)
                .shibaFocus(focused, palette)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
                .padding(horizontal = 10.dp),
        ) {
            GameIcon0(row, palette)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    row.title,
                    color = if (focused) palette.textPrimary else palette.textPrimary.copy(alpha = 0.85f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(row.platformLabel, color = palette.textMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(16.dp))
            if (row.isTracked) TrackedStats(row, focused, palette) else UntrackedReason(row.reason.orEmpty(), palette)
        }
        Separator(palette)
    }
}

/**
 * The game's ICON0 in the XMB's own 144:80 tile. With no ICON0 assigned, the tile draws its default
 * letter card — the same one the XMB shows — rather than borrowing other artwork.
 */
@Composable
private fun GameIcon0(row: ShibaLibraryRow, palette: DetailPalette) {
    PspIcon0Icon(
        artworkUri = row.icon0Uri,
        accentColor = palette.focus,
        title = row.title,
        modifier = Modifier.height(Icon0Height).aspectRatio(ICON0_ASPECT),
    )
}

@Composable
private fun TrackedStats(row: ShibaLibraryRow, focused: Boolean, palette: DetailPalette) {
    Column(Modifier.width(PercentColumnWidth), horizontalAlignment = Alignment.End) {
        Text(
            "${(row.progress * 100).roundToInt()}%",
            color = if (focused) palette.textPrimary else palette.textPrimary.copy(alpha = 0.85f),
            fontSize = 20.sp,
            lineHeight = 24.sp,
        )
        Spacer(Modifier.height(3.dp))
        ProgressLine(row.progress, palette, Modifier.width(ShortBarWidth))
    }
    Spacer(Modifier.width(20.dp))
    Row {
        CoinOrder.forEach { tier ->
            Box(Modifier.width(CoinCellWidth), contentAlignment = Alignment.CenterStart) {
                CoinCount(tier, row.coins.countFor(tier), palette, iconSize = 18.dp)
            }
        }
    }
}

@Composable
private fun UntrackedReason(reason: String, palette: DetailPalette) {
    Text(
        reason,
        color = palette.textMuted,
        fontSize = 13.sp,
        textAlign = TextAlign.End,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = PercentColumnWidth + 20.dp + CoinCellWidth * CoinOrder.size),
    )
}
