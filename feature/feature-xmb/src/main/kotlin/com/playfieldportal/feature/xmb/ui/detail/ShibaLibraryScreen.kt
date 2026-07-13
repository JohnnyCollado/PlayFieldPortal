package com.playfieldportal.feature.xmb.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.theme.LocalPFPColors
import com.playfieldportal.core.ui.theme.menuCursorEdge
import com.playfieldportal.feature.xmb.viewmodel.ShibaLibraryMode
import kotlin.math.roundToInt

private val Reward = Color(0xFF9B6FE0)
private val TextPrimary = Color(0xFFEEEEEE)
private val TextMuted = Color(0x99EEEEEE)
private val TextDim = Color(0x66EEEEEE)
private val PanelFill = Color(0x33101018)
private val Track = Color(0x33FFFFFF)

@Composable
fun ShibaLibraryScreen(
    mode: ShibaLibraryMode,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    pendingGamepadAction: GamepadAction? = null,
    onGamepadActionConsumed: () -> Unit = {},
    viewModel: ShibaLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(mode) { viewModel.load(mode) }
    LaunchedEffect(state.closed) {
        if (state.closed) { onClose(); viewModel.onClosedHandled() }
    }
    LaunchedEffect(pendingGamepadAction) {
        if (pendingGamepadAction != null) {
            viewModel.handleGamepadAction(pendingGamepadAction)
            onGamepadActionConsumed()
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(state.focusIndex, state.rows.size) {
        val vh = listState.layoutInfo.viewportSize.height
        if (vh > 0) listState.animateScrollToItem(state.focusIndex, scrollOffset = -(vh / 3))
    }

    val pfp = LocalPFPColors.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to pfp.backgroundTop.copy(alpha = 0.80f),
                    1f to pfp.backgroundBottom.copy(alpha = 0.94f),
                ),
            ),
    ) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            // Sibling views (All Tracked <-> Untracked) — switch with LEFT/RIGHT or by tapping.
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                state.siblings.forEach { sib ->
                    val active = sib == state.mode
                    val count = if (sib == ShibaLibraryMode.TRACKED) state.trackedCount else state.untrackedCount
                    val label = if (sib == ShibaLibraryMode.TRACKED) "All Tracked" else "Untracked"
                    Text(
                        text = "$label  $count",
                        color = if (active) menuCursorEdge() else TextDim,
                        fontSize = if (active) 22.sp else 16.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.clickable { viewModel.setMode(sib) },
                    )
                }
            }
            Spacer(Modifier.height(14.dp))

            SearchField(query = state.query, onQueryChange = viewModel::setQuery)
            Spacer(Modifier.height(14.dp))

            Row(Modifier.fillMaxSize()) {
                // ── Master list ─────────────────────────────────────────────────
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(0.62f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.rows.isEmpty()) {
                        item {
                            val message = when {
                                state.query.isNotBlank() -> "No games match \"${state.query}\""
                                state.mode == ShibaLibraryMode.TRACKED -> "No tracked games yet"
                                else -> "Every game is tracked"
                            }
                            Text(message, color = TextMuted, fontSize = 15.sp, modifier = Modifier.padding(top = 24.dp))
                        }
                    }
                    itemsIndexed(state.rows, key = { _, r -> r.gameId }) { i, row ->
                        GameListRow(row, focused = i == state.focusIndex, accent = menuCursorEdge()) { viewModel.setFocus(i) }
                    }
                }

                Spacer(Modifier.width(20.dp))

                // ── Detail panel ────────────────────────────────────────────────
                DetailPanel(state, accent = menuCursorEdge(), modifier = Modifier.weight(0.38f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        label = { Text("Search games", color = TextMuted) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = TextMuted) },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
            focusedBorderColor = menuCursorEdge(), unfocusedBorderColor = Color(0x44FFFFFF),
            cursorColor = menuCursorEdge(),
        ),
        modifier = Modifier.fillMaxWidth(0.6f),
    )
}

@Composable
private fun GameListRow(row: ShibaLibraryRow, focused: Boolean, accent: Color, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (focused) Modifier.background(Color(0x22FFFFFF)).border(2.dp, accent, RoundedCornerShape(12.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        BoxArt(row.artworkUri, Modifier.size(width = 52.dp, height = 52.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(row.title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(row.platformLabel, color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            if (row.isTracked) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { row.progress },
                        modifier = Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp)),
                        color = accent, trackColor = Track,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("${(row.progress * 100).roundToInt()}%", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CoinTally(ShibaTier.BRONZE, row.bronzeEarned)
                    CoinTally(ShibaTier.SILVER, row.silverEarned)
                    CoinTally(ShibaTier.GOLD, row.goldEarned)
                    CoinTally(ShibaTier.PLATINUM, if (row.mastered) 1 else 0)
                }
            } else {
                Text(row.reason.orEmpty(), color = TextDim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun CoinTally(tier: ShibaTier, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ShibaCoinIcon(tier, Modifier.size(18.dp))
        Spacer(Modifier.width(5.dp))
        Text("$count", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DetailPanel(state: ShibaLibraryUiState, accent: Color, modifier: Modifier = Modifier) {
    val row = state.focused
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(PanelFill)
            .padding(20.dp),
    ) {
        if (row == null) {
            Text("Nothing selected", color = TextMuted, fontSize = 14.sp)
            return
        }

        // Logo (falls back to the title) + platform.
        if (row.logoUri != null) {
            AsyncImage(model = row.logoUri, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(64.dp))
        } else {
            Text(row.title, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(4.dp))
        Text(row.platformLabel, color = accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(20.dp))

        if (row.isTracked) {
            SectionLabel("PROGRESS")
            Text("${(row.progress * 100).roundToInt()}%", color = TextPrimary, fontSize = 38.sp, fontWeight = FontWeight.Bold)
            LinearProgressIndicator(
                progress = { row.progress },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = accent, trackColor = Track,
            )
            Spacer(Modifier.height(24.dp))

            SectionLabel("SHIBA COINS")
            CoinBreakdown("Bronze Coins", ShibaTier.BRONZE, row.bronzeEarned, row.bronzeTotal)
            CoinBreakdown("Silver Coins", ShibaTier.SILVER, row.silverEarned, row.silverTotal)
            CoinBreakdown("Gold Coins", ShibaTier.GOLD, row.goldEarned, row.goldTotal)
            CoinBreakdown("Platinum Coins", ShibaTier.PLATINUM, if (row.mastered) 1 else 0, if (row.bronzeTotal + row.silverTotal + row.goldTotal > 0) 1 else 0)
            Spacer(Modifier.height(20.dp))

            TotalScoreCard(state)
        } else {
            SectionLabel("NOT TRACKED")
            Text(row.reason.orEmpty(), color = TextPrimary, fontSize = 15.sp)
            Spacer(Modifier.height(24.dp))
            TotalScoreCard(state)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun CoinBreakdown(label: String, tier: ShibaTier, earned: Int, total: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        ShibaCoinIcon(tier, Modifier.size(30.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text("$earned", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(" / $total", color = TextMuted, fontSize = 13.sp)
    }
}

@Composable
private fun TotalScoreCard(state: ShibaLibraryUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x22000000))
            .padding(16.dp),
    ) {
        SectionLabel("TOTAL COIN SCORE")
        Text("%,d".format(state.totalCoinScore), color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("NEXT REWARD: ${"%,d".format(state.coinsForNextLevel)} POINTS", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { state.levelFraction },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = Reward, trackColor = Track,
        )
    }
}

@Composable
private fun BoxArt(uri: String?, modifier: Modifier) {
    Box(modifier = modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF1B1B27)), contentAlignment = Alignment.Center) {
        if (uri != null) {
            AsyncImage(model = uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}
