package com.playfieldportal.feature.xmb.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.theme.LocalPFPColors
import com.playfieldportal.core.ui.theme.menuCursorEdge
import com.playfieldportal.core.ui.theme.menuCursorFill
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val Bronze = Color(0xFFC07C46)
private val Silver = Color(0xFFB9C0C7)
private val Gold = Color(0xFFE1B12C)
private val Platinum = Color(0xFF6F9BF5)
private val TextPrimary = Color(0xFFEEEEEE)
private val TextMuted = Color(0x99EEEEEE)
private val TextDim = Color(0x66EEEEEE)
private val CardFill = Color(0xFF1B1B26)
private val DATE_FMT = SimpleDateFormat("MMM d, yyyy", Locale.US)

private fun metalOf(tier: ShibaTier) = when (tier) {
    ShibaTier.BRONZE -> Bronze
    ShibaTier.SILVER -> Silver
    ShibaTier.GOLD -> Gold
    ShibaTier.PLATINUM -> Platinum
}

@Composable
fun ShibaCoinsScreen(
    gameId: Long,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    pendingGamepadAction: GamepadAction? = null,
    onGamepadActionConsumed: () -> Unit = {},
    viewModel: ShibaCoinsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(gameId) { viewModel.load(gameId) }
    LaunchedEffect(state.closed) {
        if (state.closed) {
            onClose()
            viewModel.onClosedHandled()
        }
    }
    LaunchedEffect(pendingGamepadAction) {
        if (pendingGamepadAction != null) {
            viewModel.handleGamepadAction(pendingGamepadAction)
            onGamepadActionConsumed()
        }
    }

    val listState = rememberLazyListState()
    // Center the focused element: the header controls pin to the top, coin rows scroll to mid-view
    // so the highlighted row is always comfortably reachable.
    LaunchedEffect(state.focusIndex) {
        if (state.focusIndex < 3) {
            listState.animateScrollToItem(0)
        } else {
            val viewportH = listState.layoutInfo.viewportSize.height
            listState.animateScrollToItem(1 + (state.focusIndex - 3), scrollOffset = -(viewportH / 3))
        }
    }

    val pfp = LocalPFPColors.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to pfp.backgroundTop.copy(alpha = 0.72f),
                    1f to pfp.backgroundBottom.copy(alpha = 0.90f),
                )
            ),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 920.dp)
                .align(Alignment.TopCenter)
                .padding(horizontal = 28.dp),
        ) {
            item(key = "header") { HeaderSection(state, viewModel) }
            itemsIndexed(state.displayed, key = { _, c -> c.id }) { i, coin ->
                CoinListRow(coin, focused = state.focusIndex == 3 + i)
            }
            if (state.linked && state.displayed.isEmpty()) {
                item { Text("No coins to show.", color = TextMuted, modifier = Modifier.padding(vertical = 16.dp)) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun HeaderSection(state: ShibaCoinsUiState, viewModel: ShibaCoinsViewModel) {
    Column(Modifier.fillMaxWidth()) {
        DetailBreadcrumb(title = state.title, subtitle = "Shiba Coins", onBack = viewModel::close)
        SummaryHeader(state)
        if (!state.linked) LinkSection(state, viewModel, focused = state.focusIndex == 2)
        SyncRow(state, viewModel, focused = state.focusIndex == 2 && state.linked)
        SortFilterChips(state, viewModel)
        state.message?.let { msg ->
            Text(
                "$msg  (tap to dismiss)",
                color = menuCursorEdge(),
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.dismissMessage() }
                    .padding(vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun SummaryHeader(state: ShibaCoinsUiState) {
    val summary = state.summary
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Progress", color = TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(
                "${((summary?.progress ?: 0f) * 100).roundToInt()}%",
                color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { summary?.progress ?: 0f },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = menuCursorEdge(),
            trackColor = Color(0x33FFFFFF),
        )
        Spacer(Modifier.height(12.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(CardFill)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(if (summary?.isMastered == true) Platinum else Platinum.copy(alpha = 0.28f)))
            Spacer(Modifier.width(10.dp))
            Text(
                if (summary?.isMastered == true) "Platinum crown earned" else "Master every coin to earn the Platinum crown",
                color = if (summary?.isMastered == true) TextPrimary else TextMuted,
                fontSize = 13.sp, fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun LinkSection(state: ShibaCoinsUiState, viewModel: ShibaCoinsViewModel, focused: Boolean) {
    val edge = menuCursorEdge()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(if (focused) Modifier.border(2.dp, edge, RoundedCornerShape(10.dp)) else Modifier)
            .padding(vertical = 10.dp, horizontal = if (focused) 8.dp else 0.dp),
    ) {
        if (state.provider == AchievementProvider.STEAM) {
            SteamLinkControls(state, viewModel)
        } else {
            // RetroAchievements is hash-only — no manual / user-provided linking.
            Text("Not recognised by RetroAchievements", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text(
                "RetroAchievements games link automatically by ROM hash. If this ROM's hash isn't a registered RetroAchievements hash, it can't be tracked.",
                color = TextMuted,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun SteamLinkControls(state: ShibaCoinsUiState, viewModel: ShibaCoinsViewModel) {
    var draft by remember { mutableStateOf("") }
    Text("This game isn't linked yet", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        label = { Text("Steam appid", color = TextMuted) },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
            focusedBorderColor = menuCursorEdge(), unfocusedBorderColor = Color(0x44FFFFFF),
            cursorColor = menuCursorEdge(),
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PillButton("Link and sync", enabled = draft.isNotBlank()) { viewModel.link(draft) }
        PillButton("Match by title", enabled = state.title.isNotBlank()) { viewModel.resolveByTitle() }
    }
    SteamFinder(state, viewModel)
}

// Manual "Find on Steam" picker: search the app list by name and pick the right game.
@Composable
private fun SteamFinder(state: ShibaCoinsUiState, viewModel: ShibaCoinsViewModel) {
    var query by remember(state.title) { mutableStateOf(state.title) }
    Spacer(Modifier.height(12.dp))
    Text("Or find it on Steam", color = TextMuted, fontSize = 12.sp)
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text("Search Steam by name", color = TextMuted) },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
            focusedBorderColor = menuCursorEdge(), unfocusedBorderColor = Color(0x44FFFFFF),
            cursorColor = menuCursorEdge(),
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PillButton("Search", enabled = query.isNotBlank()) { viewModel.searchSteam(query) }
        if (state.isSearchingSteam) CircularProgressIndicator(color = menuCursorEdge(), modifier = Modifier.size(16.dp))
    }
    state.steamResults.forEach { candidate ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.linkSteamAppId(candidate.appId) }
                .padding(vertical = 8.dp),
        ) {
            Text(candidate.name, color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("#${candidate.appId}", color = TextMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SyncRow(state: ShibaCoinsUiState, viewModel: ShibaCoinsViewModel, focused: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
    ) {
        Text("Synced from ${state.provider.name.lowercase().replace('_', ' ')}", color = TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        if (state.isSyncing) {
            CircularProgressIndicator(color = menuCursorEdge(), modifier = Modifier.size(18.dp))
        } else if (state.linked) {
            // "Change match" is user-provided matching — Steam only; RetroAchievements is hash-only.
            if (state.provider == AchievementProvider.STEAM) {
                Text(
                    "Change match",
                    color = TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clickable { viewModel.changeLink() }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            PillButton("Sync now", enabled = true, focused = focused) { viewModel.sync() }
        }
    }
}

@Composable
private fun SortFilterChips(state: ShibaCoinsUiState, viewModel: ShibaCoinsViewModel) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = focusRing(state.focusIndex == 0),
        ) {
            Text("Sort", color = TextDim, fontSize = 11.sp)
            Chip("Tier", state.sort == CoinSort.TIER) { viewModel.setSort(CoinSort.TIER) }
            Chip("Earned", state.sort == CoinSort.EARNED) { viewModel.setSort(CoinSort.EARNED) }
            Chip("Rarest", state.sort == CoinSort.RAREST) { viewModel.setSort(CoinSort.RAREST) }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = focusRing(state.focusIndex == 1),
        ) {
            Text("Show", color = TextDim, fontSize = 11.sp)
            Chip("All", state.filter == CoinFilter.ALL) { viewModel.setFilter(CoinFilter.ALL) }
            Chip("Earned", state.filter == CoinFilter.EARNED) { viewModel.setFilter(CoinFilter.EARNED) }
            Chip("Locked", state.filter == CoinFilter.LOCKED) { viewModel.setFilter(CoinFilter.LOCKED) }
        }
    }
}

@Composable
private fun focusRing(focused: Boolean): Modifier {
    val edge = menuCursorEdge()
    return if (focused) Modifier
        .clip(RoundedCornerShape(999.dp))
        .border(2.dp, edge, RoundedCornerShape(999.dp))
        .padding(4.dp)
    else Modifier
}

@Composable
private fun CoinListRow(coin: CoinRow, focused: Boolean) {
    val redacted = coin.isHidden && !coin.isEarned
    val dot = if (coin.isEarned) metalOf(coin.tier) else metalOf(coin.tier).copy(alpha = 0.28f)
    val edge = menuCursorEdge()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(if (focused) Modifier.border(2.dp, edge, RoundedCornerShape(10.dp)) else Modifier)
            .padding(vertical = 9.dp, horizontal = 8.dp),
    ) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (redacted) "Hidden coin" else coin.title,
                color = if (coin.isEarned) TextPrimary else TextMuted,
                fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (redacted) "Keep playing to reveal" else coin.description,
                color = TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(String.format(Locale.US, "%.1f%%", coin.globalRarity), color = metalOf(coin.tier), fontSize = 12.sp)
            Text(
                if (coin.isEarned) coin.earnedAt?.let { DATE_FMT.format(Date(it)) } ?: "Earned" else "Locked",
                color = TextDim, fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) Color.White else TextMuted,
        fontSize = 12.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) menuCursorFill() else Color(0x22FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun PillButton(label: String, enabled: Boolean, focused: Boolean = false, onClick: () -> Unit) {
    val edge = menuCursorEdge()
    Text(
        label,
        color = if (enabled) Color.White else TextDim,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) menuCursorFill() else Color(0x18FFFFFF))
            .then(if (focused) Modifier.border(2.dp, edge, RoundedCornerShape(8.dp)) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}
