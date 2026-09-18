package com.playfieldportal.feature.xmb.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.components.PspContextMenuOverlay
import com.playfieldportal.core.ui.components.PspMenuRow
import com.playfieldportal.core.ui.components.XmbHeaderPill
import com.playfieldportal.core.ui.achievement.BoneGlyph
import com.playfieldportal.core.ui.detail.DetailContentPadding
import com.playfieldportal.core.ui.detail.DetailPalette
import com.playfieldportal.core.ui.detail.PfpDetailBackground
import com.playfieldportal.core.ui.detail.PfpDetailBreadcrumb
import com.playfieldportal.core.ui.detail.PfpDetailHelperFooter
import com.playfieldportal.core.ui.detail.detailPalette

@Composable
fun PlayerStatusScreen(
    onClose: () -> Unit,
    onOpenCoins: (ShibaCoinsTarget) -> Unit,
    modifier: Modifier = Modifier,
    pendingGamepadAction: GamepadAction? = null,
    onGamepadActionConsumed: () -> Unit = {},
    showTouchControls: Boolean = false,
    onTouchInput: () -> Unit = {},
    viewModel: PlayerStatusViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(state.closed) {
        if (state.closed) {
            onClose()
            viewModel.onClosedHandled()
        }
    }
    LaunchedEffect(state.openCoins) {
        state.openCoins?.let { onOpenCoins(it); viewModel.onOpenHandled() }
    }
    LaunchedEffect(pendingGamepadAction) {
        pendingGamepadAction?.let {
            viewModel.handleGamepadAction(it)
            onGamepadActionConsumed()
        }
    }

    val palette = detailPalette()
    val listState = rememberLazyListState()
    LaunchedEffect(state.focusedId, state.onRarest, state.recent) {
        val index = if (state.onRarest) 0 else state.recent.indexOfFirst { it.id == state.focusedId }.coerceAtLeast(0) + 1
        runCatching { listState.animateScrollToItem(index) }
    }

    // Report touch at the page boundary without consuming it, so row clicks, scrolling, and modal
    // controls continue through the same paths used by controller input.
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
            PlayerStatusHeader(state, palette, viewModel::close)
            state.message?.let { message ->
                Text(
                    message,
                    color = palette.textMuted,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = viewModel::dismissMessage,
                        )
                        .padding(horizontal = DetailContentPadding, vertical = 8.dp),
                )
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = DetailContentPadding),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    item(key = "player-status:rarest") {
                        RarestRow(state, palette, viewModel::onRarestClick)
                    }
                    items(state.recent, key = { it.id }) { row ->
                        RecentAchievementRow(
                            row = row,
                            focused = !state.onRarest && row.id == state.focusedId,
                            palette = palette,
                            onClick = { viewModel.onRecentClick(row.id) },
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                PfpDetailHelperFooter(items = playerStatusHelperItems(state), visible = !showTouchControls)
                if (showTouchControls && state.options == null) {
                    XmbHeaderPill(label = "Options", onClick = viewModel::openOptions)
                }
            }
        }
        state.options?.let { menu ->
            PspContextMenuOverlay(
                title = menu.title,
                rows = state.optionRows.map { PspMenuRow(label = it.label, checked = it.checked) },
                selectedIndex = menu.selectedIndex,
                onRowActivated = viewModel::onOptionActivated,
                onDismiss = viewModel::closeOptions,
            )
        }
    }
}

@Composable
private fun PlayerStatusHeader(
    state: PlayerStatusUiState,
    palette: DetailPalette,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(headerShade(palette))) {
        PfpDetailBreadcrumb(
            title = "Player Status",
            subtitle = "Shiba Coins",
            onBack = onBack,
            modifier = Modifier,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(state.rankLabel, color = palette.focus, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    if (state.bones > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            BoneGlyph(tint = palette.focus, size = 18.dp)
                            Text(state.bones.toString(), color = palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = DetailContentPadding + 52.dp, end = DetailContentPadding, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            StatBlock("SHIBA LEVEL", state.level.toString(), palette)
            Column {
                Text("NEXT LEVEL", color = palette.textMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text("${(state.levelFraction * 100).toInt()}%", color = palette.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                ProgressLine(state.levelFraction, palette, Modifier.width(74.dp), height = 4.dp)
            }
            StatBlock("TOTAL COINS", "%,d".format(state.totalXp), palette)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TierStat(ShibaTier.PLATINUM, state.platinum, palette)
                TierStat(ShibaTier.GOLD, state.gold, palette)
                TierStat(ShibaTier.SILVER, state.silver, palette)
                TierStat(ShibaTier.BRONZE, state.bronze, palette)
            }
        }
    }
}

@Composable
private fun StatBlock(label: String, value: String, palette: DetailPalette) {
    Column {
        Text(label, color = palette.textMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(value, color = palette.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TierStat(tier: ShibaTier, count: Int, palette: DetailPalette) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ShibaCoinIcon(tier, Modifier.size(22.dp))
        Spacer(Modifier.width(5.dp))
        Text(count.toString(), color = palette.textPrimary, fontSize = 13.sp)
    }
}

@Composable
private fun RarestRow(state: PlayerStatusUiState, palette: DetailPalette, onClick: () -> Unit) {
    val rarest = state.rarest
    Column {
        if (rarest == null) {
            Text("No rarest achievement yet.", color = palette.textMuted, fontSize = 14.sp, modifier = Modifier.padding(12.dp))
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .shibaFocus(state.onRarest, palette)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoinArt(rarest.iconUrl, rarest.tier, Modifier.size(72.dp), cornerRadius = 6.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("RAREST UNLOCKED", color = palette.textMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                    Text(rarest.coinTitle, color = palette.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(rarest.gameTitle, color = palette.textMuted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(if (rarest.globalRarity < 0) "—" else String.format(java.util.Locale.US, "%.1f%%", rarest.globalRarity), color = palette.focus, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("of players have this", color = palette.textMuted, fontSize = 11.sp)
                }
            }
        }
        Separator(palette)
    }
}

@Composable
private fun RecentAchievementRow(
    row: RecentRow,
    focused: Boolean,
    palette: DetailPalette,
    onClick: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .shibaFocus(focused, palette)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoinArt(row.iconUrl, row.tier, Modifier.size(48.dp), dimmed = false, cornerRadius = 6.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(row.coinTitle, color = palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${row.gameTitle} · ${relativeTime(row.earnedAt)}", color = palette.textMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(row.tier.name, color = palette.textMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        Separator(palette)
    }
}
