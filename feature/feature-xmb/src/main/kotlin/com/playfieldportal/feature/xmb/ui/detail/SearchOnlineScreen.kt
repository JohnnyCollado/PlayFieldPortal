package com.playfieldportal.feature.xmb.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.components.PspContextMenuOverlay
import com.playfieldportal.core.ui.components.PspMenuRow
import com.playfieldportal.core.ui.components.XmbHeaderPill
import com.playfieldportal.core.ui.detail.DetailContentPadding
import com.playfieldportal.core.ui.detail.DetailPalette
import com.playfieldportal.core.ui.detail.PfpDetailBackground
import com.playfieldportal.core.ui.detail.PfpDetailHelperFooter
import com.playfieldportal.core.ui.detail.detailPalette
import com.playfieldportal.feature.achievements.preview.PreviewCandidate
import com.playfieldportal.feature.xmb.ui.PspIcon0Icon

// ── Search online ─────────────────────────────────────────────────────────────
//
// Plan Task 8: the explicit provider search and its read-only preview, drawn on the achievement
// pages' own surfaces — the App Drawer-derived background and palette, the pinned Search row, the
// 64dp rows and the permanent helper footer — so it reads as part of the same browser.
//
// Three elements are this page's alone, and they all say the same thing: what you are looking at is
// not yours. The tile glyph on the library's "Search online" row (a magnifier over a globe), the
// gold PREVIEW chip beside the game's name, and the amber notice strip above the coins.

/** ICON0 is 144 × 80; result rows draw it at the library's height. */
private val Icon0Height = 48.dp
private const val ICON0_ASPECT = 144f / 80f

/**
 * The preview's gold. Deliberately fixed rather than taken from [DetailPalette]: this is a warning
 * mark, and it has to read as one against every theme the user can pick.
 */
private val PreviewGold = Color(0xFFFFD97A)
private val PreviewGoldEdge = Color(0xBFE6B93B)
private val PreviewGoldFill = Color(0x38E6B93B)

private val ChipShape = RoundedCornerShape(999.dp)
private val NoticeShape = RoundedCornerShape(6.dp)

@Composable
fun SearchOnlineScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenCredentials: () -> Unit = {},
    pendingGamepadAction: GamepadAction? = null,
    onGamepadActionConsumed: () -> Unit = {},
    showTouchControls: Boolean = false,
    onTouchInput: () -> Unit = {},
    viewModel: SearchOnlineViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.closed) {
        if (state.closed) {
            onClose()
            viewModel.onClosedHandled()
        }
    }
    LaunchedEffect(state.openCredentials) {
        if (state.openCredentials) {
            onOpenCredentials()
            viewModel.onCredentialsHandled()
        }
    }
    LaunchedEffect(pendingGamepadAction) {
        if (pendingGamepadAction != null) {
            viewModel.handleGamepadAction(pendingGamepadAction)
            onGamepadActionConsumed()
        }
    }

    // Focus follow, the library's: every focus or order change SNAPS the focused row to the
    // 1/3-viewport line (clamped at the list edges). Instant, PSP-style — the built-in scroll
    // animation is too slow for held input.
    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        snapshotFlow { state.rows.map { it.id } to state.focusPosition }.collect { (_, position) ->
            val third = listState.layoutInfo.viewportSize.height / 3
            listState.scrollToItem((position - 1).coerceAtLeast(0), scrollOffset = -third)
        }
    }

    val palette = detailPalette()
    // Report the input source without consuming the gesture; child controls still receive taps.
    PfpDetailBackground(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onTouchInput()
                }
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            SearchOnlineHeader(
                state = state,
                palette = palette,
                showTouchControls = showTouchControls,
                onBack = viewModel::close,
                onProviderClick = viewModel::openOptions,
            )

            SearchRow(
                query = state.queryNow,
                editing = state.searchEditingNow,
                focused = state.searchFocused,
                palette = palette,
                placeholder = state.searchPlaceholder,
                onQueryChange = viewModel::setQuery,
                onClick = viewModel::onSearchClick,
                onEditEnded = viewModel::onSearchEditEnded,
                trailing = {
                    state.preview?.let { preview ->
                        ShibaCoinsViewTabs(
                            active = preview.filter,
                            counts = preview.counts,
                            palette = palette,
                            showKeyCaps = !showTouchControls,
                            onSelect = viewModel::setFilter,
                        )
                    }
                },
            )

            // The promise the whole page exists to keep, kept in view above the coins.
            if (state.inPreview) PreviewNotice(palette)

            Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
                if (state.rows.isEmpty()) ListMessage(state, palette, viewModel)
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = DetailContentPadding),
                ) {
                    items(
                        items = state.rows,
                        key = { it.id },
                        // A result row and a coin row are different shapes; keep their pools apart.
                        contentType = { it::class },
                    ) { row ->
                        val focused = row.id == state.focusedRowId
                        val onClick = remember(row.id) { { viewModel.onRowClick(row.id) } }
                        when (row) {
                            is SearchOnlineRow.Result -> ResultRow(row.candidate, focused, palette, onClick)
                            is SearchOnlineRow.Coin -> CoinListRow(
                                coin = row.coin,
                                revealed = row.coin.id in state.revealedIds,
                                focused = focused,
                                palette = palette,
                                onClick = onClick,
                            )
                        }
                    }
                }
            }

            // Results are looked up only; the line below the list says so where the eye ends up.
            if (!state.inPreview && state.status == SearchStatus.RESULTS) {
                Text(
                    text = LOOKUP_ONLY,
                    color = palette.textMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = DetailContentPadding + 10.dp, vertical = 6.dp),
                )
            }

            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                PfpDetailHelperFooter(items = searchOnlineHelperItems(state), visible = !showTouchControls)
                // Touch mode: the hints fade and the controller-only actions become pills in the
                // same reserved band (rows, Search and ◀ are tappable already).
                if (showTouchControls && state.options == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (state.inPreview) {
                            XmbHeaderPill(label = "Close preview", onClick = viewModel::closePreview)
                        } else {
                            SearchProvider.entries.forEach { provider ->
                                XmbHeaderPill(
                                    label = provider.label,
                                    onClick = remember(provider) { { viewModel.setProvider(provider) } },
                                )
                            }
                        }
                    }
                }
            }
        }

        // The provider list, RetroAchievements' console list, and a preview's Refresh: the same
        // right-side menu the library and the coins page open on Triangle.
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

private const val LOOKUP_ONLY =
    "Results are looked up only — nothing here is added to your library or your coin wallet."

// ── Header ────────────────────────────────────────────────────────────────────

/**
 * Two lines, the coins page's geometry: the game (or the page) on line 1 with what is being
 * searched on the right, and the PREVIEW chip with the provider on line 2.
 */
@Composable
private fun SearchOnlineHeader(
    state: SearchOnlineUiState,
    palette: DetailPalette,
    showTouchControls: Boolean,
    onBack: () -> Unit,
    onProviderClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(headerShade(palette))) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = DetailContentPadding, end = DetailContentPadding, top = 4.dp),
        ) {
            // 48dp touch target (Android's minimum) around a 16sp glyph, as the breadcrumb does.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onBack,
                    ),
            ) {
                Text("◀", color = palette.textMuted, fontSize = 16.sp)
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = state.title,
                color = palette.textPrimary,
                fontSize = 25.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(16.dp))
            val preview = state.preview
            if (preview != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "AVAILABLE",
                        color = palette.textMuted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 12.sp,
                        maxLines = 1,
                        softWrap = false,
                    )
                    Text(
                        text = if (preview.loading) "—" else achievementCount(preview.available),
                        color = palette.textPrimary,
                        fontSize = 16.sp,
                        lineHeight = 20.sp,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            } else if (showTouchControls) {
                // Touch has no Triangle, so the provider is also the button that changes it.
                XmbHeaderPill(label = state.provider.label, onClick = onProviderClick)
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = DetailContentPadding + 52.dp, end = DetailContentPadding, bottom = 10.dp),
        ) {
            if (state.inPreview) PreviewChip()
            Text(
                text = state.subtitle,
                color = palette.textMuted,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.divider))
    }
}

private fun achievementCount(count: Int): String =
    if (count == 1) "1 achievement" else "$count achievements"

/** The gold PREVIEW chip: this game is not on this device and nothing here is being kept. */
@Composable
private fun PreviewChip() {
    Text(
        text = "PREVIEW",
        color = PreviewGold,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .background(PreviewGoldFill, ChipShape)
            .border(1.dp, PreviewGoldEdge, ChipShape)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/** The amber notice strip above a preview's coins: the same promise, spelled out. */
@Composable
private fun PreviewNotice(palette: DetailPalette) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DetailContentPadding, vertical = 6.dp)
            .background(palette.rowFill, NoticeShape)
            .border(1.dp, PreviewGoldEdge, NoticeShape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Icon(Icons.Filled.Info, contentDescription = null, tint = PreviewGold, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            text = "Preview only. This game isn't on this device, so nothing here is tracked, " +
                "counted or synced. Closing the preview discards it.",
            color = palette.textMuted,
            fontSize = 12.sp,
        )
    }
}

// ── List area ─────────────────────────────────────────────────────────────────

/** What the empty list area says, plus the one action its state offers. */
@Composable
private fun ListMessage(state: SearchOnlineUiState, palette: DetailPalette, viewModel: SearchOnlineViewModel) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(horizontal = DetailContentPadding + 10.dp, vertical = 24.dp),
    ) {
        Text(state.emptyMessage, color = palette.textMuted, fontSize = 15.sp)
        state.recovery?.let { recovery ->
            RecoveryButton(
                label = recovery.label,
                palette = palette,
                onClick = when (recovery) {
                    SearchRecovery.RETRY -> viewModel::retrySearch
                    SearchRecovery.OPEN_SETTINGS -> viewModel::requestCredentials
                },
            )
        }
    }
}

@Composable
private fun RecoveryButton(label: String, palette: DetailPalette, onClick: () -> Unit) {
    Text(
        text = label,
        color = palette.textPrimary,
        fontSize = 14.sp,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .background(palette.focus.copy(alpha = 0.18f), FocusShape)
            .border(1.5.dp, palette.focus, FocusShape)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/**
 * One search result: the game, and enough of the provider's own identity (console, app id) that the
 * user can tell two same-named entries apart before opening either.
 */
@Composable
private fun ResultRow(
    candidate: PreviewCandidate,
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
            // A game that isn't on this device has no ICON0, so the tile draws its letter card.
            PspIcon0Icon(
                artworkUri = null,
                accentColor = palette.focus,
                title = candidate.title,
                modifier = Modifier.height(Icon0Height).aspectRatio(ICON0_ASPECT),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    candidate.title,
                    color = if (focused) palette.textPrimary else palette.textPrimary.copy(alpha = 0.85f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = candidateSubtitle(candidate),
                    color = palette.textMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(16.dp))
            Text("›", color = palette.textMuted, fontSize = 18.sp)
        }
        Separator(palette)
    }
}

/** "Steam · app 645730" / "PlayStation · game 11240" — the provider's own name for this entry. */
private fun candidateSubtitle(candidate: PreviewCandidate): String {
    val id = when (candidate.provider) {
        AchievementProvider.STEAM -> "app ${candidate.providerGameId}"
        else -> "game ${candidate.providerGameId}"
    }
    return "${candidate.platformLabel} · $id"
}
