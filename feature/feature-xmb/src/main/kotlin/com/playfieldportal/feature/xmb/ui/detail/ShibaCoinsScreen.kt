package com.playfieldportal.feature.xmb.ui.detail

import android.content.res.Resources
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import com.playfieldportal.core.ui.components.PfpBusyRing
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.achievement.LocalCopyOwnership
import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.text.UiText
import com.playfieldportal.core.ui.components.ControllerPrompt
import com.playfieldportal.core.ui.components.PfpCheckMark
import com.playfieldportal.core.ui.components.PfpModalSpec
import com.playfieldportal.core.ui.components.PspContextMenuOverlay
import com.playfieldportal.core.ui.components.PspMenuRow
import com.playfieldportal.core.ui.components.rememberPfpModalHost
import com.playfieldportal.core.ui.detail.unselectedLabel
import com.playfieldportal.core.ui.theme.menuCursorEdge
import com.playfieldportal.core.ui.theme.menuCursorFill
import com.playfieldportal.core.ui.detail.DetailContentPadding
import com.playfieldportal.core.ui.detail.DetailLaunchFill
import com.playfieldportal.core.ui.detail.DetailPalette
import com.playfieldportal.core.ui.detail.PfpDetailBackground
import com.playfieldportal.core.ui.detail.PfpDetailHelperFooter
import com.playfieldportal.core.ui.detail.detailPalette
import com.playfieldportal.core.ui.text.asString
import com.playfieldportal.feature.xmb.R
import com.playfieldportal.core.ui.text.PfpDateFormat
import com.playfieldportal.core.ui.text.PfpDateStyle
import java.util.Locale
import kotlin.math.roundToInt

// ── A game's own achievements page ────────────────────────────────────────────
//
// The Tracked/Untracked browser's
// shape applied to one game's coins — the App Drawer-derived background and palette, a two-line
// header carrying completion and the per-tier tally, a pinned Search row whose right half holds the
// All / Earned / Locked view tabs, full-width 64dp coin rows led by the Platinum Crown, and the
// permanent helper footer. Sort, Update Achievements and Change Match live in Triangle's shared PSP context
// menu, not on the page. Every color comes from DetailPalette: this screen owns no palette of its own.

/** Coin art in a row. Square, lightly rounded — the badge reads as artwork, not as a button. */
private val CoinArtSize = 46.dp
private val CoinArtCorner = 4.dp

/** Right-hand columns. Fixed widths so every row's rarity, status and tier line up. */
private val MetricColumnWidth = 74.dp
private val RarityBarWidth = 64.dp
private val StatusColumnWidth = 108.dp
private val TierColumnWidth = 48.dp

/** Header line 2 lines up under the title, past the back arrow's 48dp target and its gap. */
private val HeaderTitleIndent = 52.dp
private val HeaderBarWidth = 74.dp
private val HeaderTierIconSize = 26.dp

private val TabShape = RoundedCornerShape(999.dp)

/** Earned reads in the same restrained green the detail pages already use for Launch. */
private val EarnedColor = DetailLaunchFill

@Composable
fun ShibaCoinsScreen(
    target: ShibaCoinsTarget,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    pendingGamepadAction: GamepadAction? = null,
    onGamepadActionConsumed: () -> Unit = {},
    showTouchControls: Boolean = false,
    onTouchInput: () -> Unit = {},
    viewModel: ShibaCoinsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(target) { viewModel.load(target) }
    LaunchedEffect(state.closed) {
        if (state.closed) {
            onClose()
            viewModel.onClosedHandled()
        }
    }
    // Unlink Game asks through the shared confirm modal. Its cursor lives in the host, so a press
    // goes there first while one is up and only otherwise reaches the page.
    val modal = rememberPfpModalHost(
        spec = coinUnlinkModalSpec(
            resources = detailResources(),
            state = state,
            onConfirm = viewModel::confirmUnlink,
            onCancel = viewModel::cancelUnlink,
        ),
        showHints = !showTouchControls,
    )
    LaunchedEffect(pendingGamepadAction) {
        if (pendingGamepadAction != null) {
            if (!modal.intercept(pendingGamepadAction)) viewModel.handleGamepadAction(pendingGamepadAction)
            onGamepadActionConsumed()
        }
    }

    // The game folder pick. OpenDocumentTree rather than OpenDocument because a game is a FOLDER,
    // and because the grant has to cover reading the progress file and creating steam_appid.txt
    // inside it — the linker persists it the moment the result arrives.
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> viewModel.onFolderPicked(uri) }

    LaunchedEffect(state.requestFolderPick) {
        if (state.requestFolderPick) {
            viewModel.onFolderPickLaunched()
            folderPicker.launch(null)
        }
    }

    // Focus follow, the library's: every focus or order change SNAPS the focused row to the
    // 1/3-viewport line (clamped at the list edges, so the top rows sit flush under Search).
    // Instant, PSP-style — the built-in scroll animation is too slow for held input. The snap also
    // drops the keyed LazyColumn's anchor after a reorder, which otherwise keeps the viewport glued
    // to the old rows. Search is pinned above the list, so focusing it shows the top.
    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        snapshotFlow { state.rows.map { it.id } to state.focusPosition }.collect { (_, position) ->
            val third = listState.layoutInfo.viewportSize.height / 3
            listState.scrollToItem((position - 1).coerceAtLeast(0), scrollOffset = -third)
        }
    }

    val palette = detailPalette()
    // Report the input source without consuming the gesture; child controls still receive taps and
    // scrolling. Presentation state belongs to XMBViewModel, not this page.
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
            ShibaCoinsHeader(
                state,
                palette,
                onBack = viewModel::close,
                onSelectSource = viewModel::selectSource,
                onOptions = viewModel::openOptions.takeIf { showTouchControls && state.options == null },
            )

            SearchRow(
                query = state.query,
                editing = state.searchEditing,
                focused = state.searchFocused,
                palette = palette,
                placeholder = stringResource(R.string.shiba_coins_search_placeholder),
                onQueryChange = viewModel::setQuery,
                onClick = viewModel::onSearchClick,
                onEditEnded = viewModel::onSearchEditEnded,
                trailing = {
                    // An unlinked game has no coins to view, so it has no views to switch between.
                    if (!state.showLinkPanel) {
                        ShibaCoinsViewTabs(
                            active = state.filter,
                            counts = state.viewCounts,
                            palette = palette,
                            showKeyCaps = !showTouchControls,
                            onSelect = viewModel::setFilter,
                        )
                    }
                },
            )

            // Sync and match results read as a quiet line in the page, not as a modal.
            state.message?.let { message -> NoticeLine(message.asString(), palette, viewModel::dismissMessage) }

            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (state.rows.isEmpty()) {
                    // The shell stays; only the list area explains itself.
                    Text(
                        text = state.emptyMessage.asString(),
                        color = palette.textMuted,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(horizontal = DetailContentPadding + 10.dp, vertical = 24.dp),
                    )
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = DetailContentPadding),
                ) {
                    items(
                        items = state.rows,
                        key = { it.id },
                        // The link panel is a different shape from a coin row; keep their pools apart.
                        contentType = { it::class },
                    ) { row ->
                        val focused = row.id == state.focusedRowId
                        val onClick = remember(row.id) { { viewModel.onRowClick(row.id) } }
                        when (row) {
                            is CoinListItem.Platinum -> PlatinumCrownRow(row, focused, palette, onClick)
                            is CoinListItem.Coin -> CoinListRow(
                                coin = row.coin,
                                revealed = row.coin.id in state.revealedIds,
                                focused = focused,
                                palette = palette,
                                onClick = onClick,
                            )
                            is CoinListItem.LinkPanel -> LinkPanelRow(state, focused, palette, viewModel)
                        }
                    }
                }
            }

            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                PfpDetailHelperFooter(items = shibaCoinsHelperItems(state).toPromptItems(), visible = !showTouchControls)
                // Touch mode: the hints fade; Options is the header's ⋮ (the tabs, rows and ◀ are
                // tappable already).
            }
        }

        // The ambiguous-match picker, reused verbatim from Game Detail — same panel, same keys, and
        // therefore the same controller-navigable screen for free.
        state.storefrontMatch?.let { match ->
            StorefrontMatchPanel(
                ui                = match,
                focusFill         = menuCursorFill(),
                focusEdge         = menuCursorEdge(),
                showTouchControls = showTouchControls,
                onRowClick        = viewModel::onStorefrontRowTapped,
                onMoreInfo        = viewModel::openStorefrontMoreInfo,
                onCloseMoreInfo   = viewModel::closeStorefrontMoreInfo,
                onChooseFocused   = { viewModel.chooseStorefrontCandidate(match.focus) },
                onClose           = viewModel::cancelAutoMatch,
                // Coins only ever matches Steam, so the picker has one store and no chips to tap.
                onStoreClick      = {},
            )
        }

        // Sort, Update Achievements and Change Match: the same right-side menu the library opens on Triangle.
        state.options?.let { menu ->
            PspContextMenuOverlay(
                title = menu.title.asString(),
                rows = state.optionRows.map { row -> PspMenuRow(label = row.label.asString(), isDestructive = row.isDestructive, checked = row.checked, value = row.value?.asString(), opensMenu = row.opensMenu) },
                selectedIndex = menu.selectedIndex,
                onRowActivated = viewModel::onOptionActivated,
                onDismiss = viewModel::closeOptions,
            )
        }

        modal.Content()
    }
}

/**
 * The shared confirm for Unlink Game, or null when none is asking. Destructive, so it opens on
 * Cancel and a stray Confirm press dismisses. Internal and free of composition so the mapping from
 * UI state to modal can be tested directly.
 */
internal fun coinUnlinkModalSpec(
    resources: Resources,
    state: ShibaCoinsUiState,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
): PfpModalSpec? {
    if (!state.unlinkConfirm) return null
    return PfpModalSpec.Confirm(
        key = "unlink_coins:${state.title}",
        title = resources.getString(R.string.shiba_coins_unlink_title),
        message = resources.getString(R.string.shiba_coins_unlink_message, state.title),
        confirmLabel = resources.getString(R.string.shiba_coins_unlink_confirm),
        destructive = true,
        onConfirm = onConfirm,
        onCancel = onCancel,
    )
}

// ── Header ────────────────────────────────────────────────────────────────────

/**
 * The page's own two-line header. Deliberately not [PfpDetailBreadcrumb]: the title is larger here
 * (25sp against the breadcrumb's 20sp) and a second line of stats hangs below it, and changing the
 * shared breadcrumb would restyle the library and Game Detail along with it.
 */
@Composable
private fun ShibaCoinsHeader(
    state: ShibaCoinsUiState,
    palette: DetailPalette,
    onBack: () -> Unit,
    onSelectSource: (AchievementProvider) -> Unit,
    // Touch mode's Options, at the header's right; null hides it (controller mode, menu open).
    onOptions: (() -> Unit)? = null,
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
            Text(
                text = headerSubtitle(state).asString(),
                color = palette.textMuted,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (onOptions != null) {
                Spacer(Modifier.width(12.dp))
                DetailOptionsKebab(onClick = onOptions)
            }
        }
        // A game with two sets (Steam and Local Steam): one chip each, the shown one filled. L1/R1
        // move between them; a tap picks one.
        if (state.hasSourceSwitch) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(start = DetailContentPadding + HeaderTitleIndent, bottom = 6.dp),
            ) {
                state.sources.forEach { source ->
                    SourceTag(
                        label = "${providerLabel(source.provider)} ${source.earned}/${source.total}",
                        palette = palette,
                        selected = source.provider == state.provider,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab,
                            onClick = { onSelectSource(source.provider) },
                        ),
                    )
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = DetailContentPadding + HeaderTitleIndent, end = DetailContentPadding, bottom = 10.dp),
        ) {
            val summary = state.summary
            HeaderStat(
                label = stringResource(R.string.shiba_coins_stat_completion),
                value = if (summary == null) "—" else "${(summary.progress * 100).roundToInt()}%",
                palette = palette,
            ) {
                if (summary != null) {
                    Spacer(Modifier.height(3.dp))
                    ProgressLine(summary.progress, palette, Modifier.width(HeaderBarWidth), height = 4.dp)
                }
            }
            HeaderStat(
                label = stringResource(R.string.shiba_coins_stat_earned),
                value = if (summary == null) "—" else "${summary.earned.total} / ${summary.total.total}",
                palette = palette,
            )
            if (summary != null) {
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    CoinOrder.forEach { tier ->
                        HeaderTierCell(tier, state.tierEarned(tier), state.tierTotal(tier), palette)
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.divider))
    }
}

/**
 * The right of header line 1: where these coins come from and how fresh they are. A game that was
 * never synced says so rather than showing a stale-looking blank.
 */
private fun headerSubtitle(state: ShibaCoinsUiState): UiText {
    val freshness = when {
        state.lastSyncedAt != null -> UiText.res(R.string.shiba_coins_synced, relativeTime(state.lastSyncedAt))
        state.showLinkPanel -> UiText.res(R.string.shiba_coins_not_linked)
        else -> UiText.res(R.string.shiba_coins_never_synced)
    }
    val source = listOfNotNull(
        state.platformLabel,
        // Two sets share the platform, so the line names which one is on screen.
        UiText.raw(providerLabel(state.provider)).takeIf { state.hasSourceSwitch },
        ownershipTag(state),
    )
    return UiText.Joined(source + freshness, " · ")
}

/** The Platinum is the set-completion award, so its cell counts the crown, not individual coins. */
private fun ShibaCoinsUiState.tierEarned(tier: ShibaTier): Int = when (tier) {
    ShibaTier.PLATINUM -> if (isMastered) 1 else 0
    ShibaTier.GOLD -> summary?.earned?.gold ?: 0
    ShibaTier.SILVER -> summary?.earned?.silver ?: 0
    ShibaTier.BRONZE -> summary?.earned?.bronze ?: 0
}

private fun ShibaCoinsUiState.tierTotal(tier: ShibaTier): Int = when (tier) {
    ShibaTier.PLATINUM -> 1
    ShibaTier.GOLD -> summary?.total?.gold ?: 0
    ShibaTier.SILVER -> summary?.total?.silver ?: 0
    ShibaTier.BRONZE -> summary?.total?.bronze ?: 0
}

@Composable
private fun HeaderStat(
    label: String,
    value: String,
    palette: DetailPalette,
    below: @Composable () -> Unit = {},
) {
    Column {
        Text(label, color = palette.textMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, lineHeight = 13.sp, maxLines = 1, softWrap = false)
        Text(value, color = palette.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.Medium, lineHeight = 23.sp, maxLines = 1, softWrap = false)
        below()
    }
}

@Composable
private fun HeaderTierCell(tier: ShibaTier, earned: Int, total: Int, palette: DetailPalette) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ShibaCoinIcon(tier, Modifier.size(HeaderTierIconSize))
        Spacer(Modifier.width(6.dp))
        Column {
            Text("$earned", color = palette.textPrimary, fontSize = 14.sp, lineHeight = 16.sp, maxLines = 1, softWrap = false)
            Text("/$total", color = palette.textMuted, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1, softWrap = false)
        }
    }
}

// ── View tabs ─────────────────────────────────────────────────────────────────

/**
 * All / Earned / Locked, in the right half of the pinned Search row. The counts come from the whole
 * set, not the displayed list, so they hold still while you type.
 */
@Composable
internal fun ShibaCoinsViewTabs(
    active: CoinFilter,
    counts: CoinViewCounts,
    palette: DetailPalette,
    showKeyCaps: Boolean,
    onSelect: (CoinFilter) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxHeight(),
    ) {
        // The shoulder glyphs are drawn by the shared prompt so they follow the user's controller
        // family (L1/R1, LB/RB, L/R); the tabs beside them carry the meaning, so they take no label.
        if (showKeyCaps) ControllerPrompt(action = GamepadAction.PREV_CATEGORY, label = "", glyphSize = 18.dp)
        CoinFilter.entries.forEach { view ->
            ViewTab(
                label = stringResource(view.tabRes, counts.forView(view)),
                selected = view == active,
                palette = palette,
                onClick = remember(view) { { onSelect(view) } },
            )
        }
        if (showKeyCaps) ControllerPrompt(action = GamepadAction.NEXT_CATEGORY, label = "", glyphSize = 18.dp)
    }
}

@Composable
private fun ViewTab(label: String, selected: Boolean, palette: DetailPalette, onClick: () -> Unit) {
    // The pill is small, but its tap target fills the 48dp Search row so touch has something to hit.
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxHeight()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            ),
    ) {
        Text(
            text = label,
            color = if (selected) palette.textPrimary else palette.unselectedLabel(),
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

// ── Notice line ───────────────────────────────────────────────────────────────

/** The one-line result of a sync or a match. The next controller press or a tap clears it. */
@Composable
private fun NoticeLine(message: String, palette: DetailPalette, onDismiss: () -> Unit) {
    Text(
        text = message,
        color = palette.textMuted,
        fontSize = 13.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .padding(horizontal = DetailContentPadding + 10.dp, vertical = 8.dp),
    )
}

// ── Rows ──────────────────────────────────────────────────────────────────────

@Composable
internal fun CoinListRow(
    coin: CoinRow,
    revealed: Boolean,
    focused: Boolean,
    palette: DetailPalette,
    onClick: () -> Unit,
) {
    // A hidden coin stays redacted until earned — unless the user chose to reveal it (confirm/tap
    // toggles; the same action hides it again).
    val redacted = coin.isHideable && !revealed
    ShibaCoinsRow(
        // A redacted coin never shows its badge: the artwork alone can give the coin away.
        iconUrl = if (redacted) null else coin.iconUrl,
        tier = coin.tier,
        dimmed = !coin.isEarned,
        title = if (redacted) stringResource(R.string.shiba_coin_hidden_title) else coin.title,
        titleItalic = redacted,
        description = when {
            redacted -> stringResource(R.string.shiba_coin_hidden_hint)
            // Steam's Web API never returns a hidden achievement's description; the sync fills it from
            // Steam Hunters or a community page. This shows only when neither source had it.
            coin.isHidden && coin.description.isBlank() -> stringResource(R.string.shiba_coin_description_secret)
            else -> coin.description
        },
        // A negative rarity is the "provider reported no percentage" sentinel.
        metricValue = if (coin.globalRarity < 0) "—" else String.format(Locale.US, "%.1f%%", coin.globalRarity),
        metricFraction = if (coin.globalRarity < 0) 0f else (coin.globalRarity / 100.0).toFloat(),
        metricLabel = stringResource(R.string.shiba_coin_metric_of_players),
        earned = coin.isEarned,
        statusDetail = coin.earnedAt?.let { PfpDateFormat.format(PfpDateStyle.Date, it) },
        focused = focused,
        palette = palette,
        onClick = onClick,
    )
}

/**
 * The set-completion award, pinned above the coins. It is not one of the provider's achievements,
 * so it has nothing to open — it can hold focus, and Confirm does nothing on it.
 */
@Composable
private fun PlatinumCrownRow(
    row: CoinListItem.Platinum,
    focused: Boolean,
    palette: DetailPalette,
    onClick: () -> Unit,
) {
    ShibaCoinsRow(
        iconUrl = null,
        tier = ShibaTier.PLATINUM,
        dimmed = !row.isMastered,
        title = stringResource(R.string.shiba_platinum_crown_title),
        titleItalic = false,
        description = stringResource(R.string.shiba_platinum_crown_description),
        metricValue = "${row.earned} / ${row.total}",
        metricFraction = if (row.total == 0) 0f else row.earned.toFloat() / row.total,
        metricLabel = stringResource(R.string.shiba_platinum_crown_metric),
        earned = row.isMastered,
        statusDetail = null,
        focused = focused,
        palette = palette,
        onClick = onClick,
    )
}

/**
 * The one row geometry every list row shares: art, the name and its explanation, a number with its
 * bar, the earned/locked mark, and the tier. Fixed at [RowHeight] focused or not, so nothing shifts.
 */
@Composable
private fun ShibaCoinsRow(
    iconUrl: String?,
    tier: ShibaTier,
    dimmed: Boolean,
    title: String,
    titleItalic: Boolean,
    description: String,
    metricValue: String,
    metricFraction: Float,
    metricLabel: String,
    earned: Boolean,
    statusDetail: String?,
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
            CoinArt(
                iconUrl = iconUrl,
                tier = tier,
                dimmed = dimmed,
                cornerRadius = CoinArtCorner,
                modifier = Modifier.size(CoinArtSize),
            )
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
                Text(
                    text = description,
                    color = palette.textMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(16.dp))
            MetricColumn(metricValue, metricFraction, metricLabel, palette)
            Spacer(Modifier.width(12.dp))
            StatusColumn(earned, statusDetail, palette)
            TierColumn(tier, palette)
        }
        Separator(palette)
    }
}

@Composable
private fun MetricColumn(value: String, fraction: Float, label: String, palette: DetailPalette) {
    Column(Modifier.width(MetricColumnWidth), horizontalAlignment = Alignment.End) {
        Text(value, color = palette.textPrimary, fontSize = 16.sp, lineHeight = 19.sp, maxLines = 1, softWrap = false)
        Spacer(Modifier.height(3.dp))
        ProgressLine(fraction, palette, Modifier.width(RarityBarWidth))
        Spacer(Modifier.height(2.dp))
        Text(label, color = palette.textMuted, fontSize = 9.sp, lineHeight = 11.sp, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun StatusColumn(earned: Boolean, detail: String?, palette: DetailPalette) {
    Column(Modifier.width(StatusColumnWidth)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (earned) {
                PfpCheckMark(EarnedColor, size = 14.dp)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.shiba_coin_status_earned), color = EarnedColor, fontSize = 13.sp, maxLines = 1, softWrap = false)
            } else {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = palette.iconMuted, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.shiba_coin_status_locked), color = palette.textMuted, fontSize = 13.sp, maxLines = 1, softWrap = false)
            }
        }
        if (earned && detail != null) {
            Text(detail, color = palette.textMuted, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun TierColumn(tier: ShibaTier, palette: DetailPalette) {
    Column(Modifier.width(TierColumnWidth), horizontalAlignment = Alignment.CenterHorizontally) {
        ShibaCoinIcon(tier, Modifier.size(24.dp))
        Text(
            text = stringResource(tier.shibaTierNameRes),
            color = palette.textMuted,
            fontSize = 9.sp,
            lineHeight = 11.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
        )
    }
}

// ── Unlinked game ─────────────────────────────────────────────────────────────

/**
 * An unlinked game has no coins to list, so its whole list area is this one focusable panel: what
 * the provider needs in order to match, and the Auto-Match flow itself. The logic is unchanged from
 * the previous page — only the styling follows the new rows.
 */
@Composable
private fun LinkPanelRow(
    state: ShibaCoinsUiState,
    focused: Boolean,
    palette: DetailPalette,
    viewModel: ShibaCoinsViewModel,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shibaFocus(focused, palette)
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        when (state.autoMatchStep) {
            null -> when (state.provider) {
                AchievementProvider.STEAM -> {
                    PanelTitle(stringResource(R.string.shiba_link_not_linked_yet_game), palette)
                    PanelBody(stringResource(R.string.shiba_link_steam_body), palette)
                    PanelActions {
                        PanelButton(stringResource(R.string.shiba_link_auto_match), palette, enabled = !state.isMatching, highlighted = focused) {
                            viewModel.startAutoMatch()
                        }
                        if (state.isMatching) PanelSpinner(palette)
                    }
                }
                AchievementProvider.RETRO_ACHIEVEMENTS -> {
                    PanelTitle(stringResource(R.string.shiba_link_not_linked_yet_game), palette)
                    PanelBody(stringResource(R.string.shiba_link_retro_body), palette)
                    PanelActions {
                        PanelButton(
                            label = stringResource(if (state.isMatching) R.string.shiba_link_matching else R.string.shiba_link_auto_match),
                            palette = palette,
                            enabled = !state.isMatching,
                            highlighted = focused,
                        ) { viewModel.autoMatchRaByHash() }
                    }
                }
                // Local Steam links from a folder the user points at — which is something they can
                // do right here, so the panel offers it instead of sending them to settings.
                AchievementProvider.LOCAL_STEAM -> {
                    PanelTitle(stringResource(R.string.shiba_link_not_linked_yet), palette)
                    PanelBody(stringResource(R.string.shiba_link_local_steam_body), palette)
                    PanelActions {
                        PanelButton(
                            label = stringResource(if (state.isMatching) R.string.shiba_link_matching else R.string.shiba_link_pick_game_folder),
                            palette = palette,
                            enabled = !state.isMatching,
                            highlighted = focused,
                        ) { viewModel.chooseAutoMatch(legit = false) }
                    }
                }
                AchievementProvider.VITA_TROPHY -> {
                    PanelTitle(stringResource(R.string.shiba_link_not_linked_yet), palette)
                    PanelBody(stringResource(R.string.shiba_link_vita_body), palette)
                }
                // PS3 is the one local provider with a per-game action: the trophy id comes from
                // the game's own disc, so Auto-Match can link it here and now.
                AchievementProvider.PS3_TROPHY -> {
                    PanelTitle(stringResource(R.string.shiba_link_not_linked_yet), palette)
                    PanelBody(stringResource(R.string.shiba_link_ps3_body), palette)
                    PanelActions {
                        PanelButton(
                            label = stringResource(if (state.isMatching) R.string.shiba_link_matching else R.string.shiba_link_auto_match),
                            palette = palette,
                            enabled = !state.isMatching,
                            highlighted = focused,
                        ) { viewModel.autoMatchPs3() }
                    }
                }
                // Xbox 360 links by the title ID in the game's own file, so it matches here too.
                AchievementProvider.X360_ACHIEVEMENT -> {
                    PanelTitle(stringResource(R.string.shiba_link_not_linked_yet), palette)
                    PanelBody(stringResource(R.string.shiba_link_x360_body), palette)
                    PanelActions {
                        PanelButton(
                            label = stringResource(if (state.isMatching) R.string.shiba_link_matching else R.string.shiba_link_auto_match),
                            palette = palette,
                            enabled = !state.isMatching,
                            highlighted = focused,
                        ) { viewModel.autoMatchX360() }
                    }
                }
            }
            AutoMatchStep.CONFIRM_COPY -> {
                PanelTitle(stringResource(R.string.shiba_link_copy_title), palette)
                PanelBody(stringResource(R.string.shiba_link_copy_body), palette)
                PanelActions {
                    PanelButton(stringResource(R.string.shiba_action_yes), palette, enabled = true, highlighted = state.autoMatchYes) {
                        viewModel.chooseAutoMatch(true)
                    }
                    PanelButton(stringResource(R.string.shiba_action_no), palette, enabled = true, highlighted = !state.autoMatchYes) {
                        viewModel.chooseAutoMatch(false)
                    }
                }
            }
            // The picker owns the screen while it is up; the panel says what PFP is waiting for.
            AutoMatchStep.PICK_FOLDER -> {
                PanelTitle(stringResource(R.string.shiba_link_folder_title), palette)
                PanelBody(stringResource(R.string.shiba_link_folder_body), palette)
                PanelActions {
                    PanelButton(stringResource(R.string.shiba_action_cancel), palette, enabled = true) { viewModel.cancelAutoMatch() }
                    PanelSpinner(palette)
                }
            }

            // The picker panel renders over the page; this is what shows behind it while the app id
            // is still being worked out.
            AutoMatchStep.IDENTIFY -> {
                PanelTitle(
                    stringResource(if (state.steamPick) R.string.shiba_link_identify_title_steam else R.string.shiba_link_identify_title_appid),
                    palette,
                )
                PanelBody(
                    stringResource(if (state.steamPick) R.string.shiba_link_identify_body_steam else R.string.shiba_link_identify_body_appid),
                    palette,
                )
                PanelActions {
                    PanelButton(stringResource(R.string.shiba_action_cancel), palette, enabled = true) { viewModel.cancelAutoMatch() }
                    if (state.isMatching) PanelSpinner(palette)
                }
            }

            AutoMatchStep.CONFIRM_KIT -> {
                val prompt = state.kitPrompt
                PanelTitle(stringResource(R.string.shiba_link_kit_title), palette)
                PanelBody(
                    if (prompt == null) {
                        stringResource(R.string.shiba_link_kit_body_generic)
                    } else {
                        stringResource(R.string.shiba_link_kit_body, prompt.folderName, prompt.appId)
                    },
                    palette,
                )
                if (prompt != null && !prompt.installerEnabled) {
                    PanelBody(stringResource(R.string.shiba_link_kit_installer_off), palette)
                }
                PanelActions {
                    PanelButton(
                        label = stringResource(R.string.shiba_link_kit_install),
                        palette = palette,
                        enabled = prompt?.installerEnabled == true && !state.isMatching,
                        highlighted = prompt?.installSelected == true,
                    ) { viewModel.installKit() }
                    PanelButton(
                        label = stringResource(R.string.shiba_link_kit_link_only),
                        palette = palette,
                        enabled = !state.isMatching,
                        highlighted = prompt?.installSelected == false,
                    ) { viewModel.linkWithoutKit() }
                    PanelButton(stringResource(R.string.shiba_action_cancel), palette, enabled = true) { viewModel.cancelAutoMatch() }
                    if (state.isMatching) PanelSpinner(palette)
                }
            }

            // Terminal, and it names the actual cause rather than listing setup steps that would not
            // have helped.
            AutoMatchStep.NO_EMU_DATA -> {
                PanelTitle(stringResource(R.string.shiba_link_no_emu_title), palette)
                PanelBody(
                    state.noEmuDataReason?.asString() ?: stringResource(R.string.shiba_link_no_emu_default),
                    palette,
                )
                PanelActions {
                    PanelButton(stringResource(R.string.shiba_link_try_another_folder), palette, enabled = true) { viewModel.startAutoMatch() }
                    PanelButton(stringResource(R.string.shiba_action_close), palette, enabled = true) { viewModel.cancelAutoMatch() }
                }
            }

            AutoMatchStep.ENTER_APPID -> {
                var draft by remember { mutableStateOf("") }
                PanelTitle(stringResource(R.string.shiba_link_appid_title), palette)
                PanelBody(stringResource(R.string.shiba_link_appid_body), palette)
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text(stringResource(R.string.shiba_link_appid_field), color = palette.textMuted) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = palette.textPrimary,
                        unfocusedTextColor = palette.textPrimary,
                        focusedBorderColor = palette.focus,
                        unfocusedBorderColor = palette.rowEdge,
                        cursorColor = palette.focus,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                PanelActions {
                    PanelButton(stringResource(R.string.shiba_action_link), palette, enabled = draft.isNotBlank() && !state.isMatching) {
                        viewModel.submitManualAppId(draft)
                    }
                    PanelButton(stringResource(R.string.shiba_action_cancel), palette, enabled = true) { viewModel.cancelAutoMatch() }
                    if (state.isMatching) PanelSpinner(palette)
                }
            }
        }
    }
}

@Composable
private fun PanelTitle(text: String, palette: DetailPalette) {
    Text(text, color = palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun PanelBody(text: String, palette: DetailPalette) {
    Spacer(Modifier.height(6.dp))
    Text(text, color = palette.textMuted, fontSize = 13.sp, lineHeight = 18.sp)
}

@Composable
private fun PanelActions(content: @Composable () -> Unit) {
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { content() }
}

@Composable
private fun PanelSpinner(palette: DetailPalette) {
    PfpBusyRing(size = 16.dp, color = palette.focus)
}

@Composable
private fun PanelButton(
    label: String,
    palette: DetailPalette,
    enabled: Boolean,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Text(
        text = label,
        color = if (enabled) palette.textPrimary else palette.unselectedLabel(),
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(shape)
            .background(if (highlighted) palette.focus.copy(alpha = 0.28f) else palette.rowFill, shape)
            .border(1.dp, if (highlighted) palette.focus else palette.rowEdge, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            // Android's minimum touch height, so the panel's buttons stay tappable.
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

// The LOCAL_STEAM ownership readout, carried over from the old page's sync line and shortened to a
// tag that fits beside the platform. Wording stays neutral — the owned-list signal can never prove
// piracy (family sharing, alternate accounts, unplayed free games all look unowned), so "cracked" is
// never said; an unknown state stays silent rather than guessing.
private fun ownershipTag(state: ShibaCoinsUiState): UiText? {
    if (state.provider != AchievementProvider.LOCAL_STEAM) return null
    return when (state.ownership) {
        LocalCopyOwnership.OWNED -> UiText.res(R.string.shiba_coins_owned_on_steam)
        LocalCopyOwnership.NOT_IN_LIBRARY -> UiText.res(R.string.shiba_coins_tracked_locally)
        null -> null
    }
}
