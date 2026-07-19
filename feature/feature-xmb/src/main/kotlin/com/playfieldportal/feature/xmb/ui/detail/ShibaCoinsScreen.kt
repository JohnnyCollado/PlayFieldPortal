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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
    target: ShibaCoinsTarget,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    pendingGamepadAction: GamepadAction? = null,
    onGamepadActionConsumed: () -> Unit = {},
    viewModel: ShibaCoinsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(target) { viewModel.load(target) }
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
    // Same authoritative snap-follow as the Shiba library list: header focus pins the list to
    // the top; a focused coin row snaps to the 1/3-viewport line (clamped at the list edges).
    // Instant, PSP-style — animated following lagged behind held input. Reacting to the
    // displayed ids as well makes a sort/filter reorder re-snap, dropping the keyed list's
    // viewport anchor to the old rows.
    LaunchedEffect(Unit) {
        snapshotFlow { state.displayed.map { it.id } to state.focusIndex }.collect { (_, focus) ->
            if (focus < FOCUS_COINS_START) {
                listState.scrollToItem(0)
            } else {
                val third = listState.layoutInfo.viewportSize.height / 3
                listState.scrollToItem(1 + (focus - FOCUS_COINS_START), scrollOffset = -third)
            }
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
                CoinListRow(
                    coin,
                    focused = state.focusIndex == FOCUS_COINS_START + i,
                    revealed = coin.id in state.revealedIds,
                    onToggleReveal = { viewModel.toggleReveal(coin) },
                )
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
        // An account entry has nothing to link — it is already keyed to its provider identity.
        if (!state.linked && !state.accountOnly) LinkSection(state, viewModel, focused = state.focusIndex == FOCUS_ACTION)
        SyncRow(state, viewModel, focused = state.focusIndex == FOCUS_ACTION && (state.linked || state.accountOnly))
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
            ShibaCoinIcon(ShibaTier.PLATINUM, Modifier.size(28.dp).alpha(if (summary?.isMastered == true) 1f else 0.28f))
            Spacer(Modifier.width(10.dp))
            Text(
                if (summary?.isMastered == true) "Platinum crown earned" else "Master every coin to earn the Platinum crown",
                color = if (summary?.isMastered == true) TextPrimary else TextMuted,
                fontSize = 13.sp, fontWeight = FontWeight.Medium,
            )
        }
    }
}

// The sync-source line doubles as the LOCAL_STEAM ownership readout. Wording stays neutral —
// the owned-list signal can never prove piracy (family sharing, alternate accounts, unplayed
// free games all look unowned), so "cracked" is never said; unknown states stay silent.
private fun syncSourceLabel(state: ShibaCoinsUiState): String {
    if (state.provider != AchievementProvider.LOCAL_STEAM) {
        return "Synced from ${state.provider.name.lowercase().replace('_', ' ')}"
    }
    return when (state.ownership) {
        com.playfieldportal.core.domain.achievement.LocalCopyOwnership.OWNED ->
            "Local copy — owned on Steam, played offline"
        com.playfieldportal.core.domain.achievement.LocalCopyOwnership.NOT_IN_LIBRARY ->
            "Local copy — not in your Steam library, tracked locally"
        null -> "Local copy — achievements tracked locally"
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
        when (state.provider) {
            AchievementProvider.STEAM -> AutoMatchControls(state, viewModel, focused)
            AchievementProvider.RETRO_ACHIEVEMENTS -> {
                // RetroAchievements is hash-only — Auto-Match hashes the ROM and looks it up;
                // there is no copy question and no manual entry.
                Text("This game isn't linked yet", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "RetroAchievements identifies games by ROM hash. Auto-Match hashes this ROM and looks it up — only a verified dump registered on RetroAchievements can link.",
                    color = TextMuted,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton(
                        if (state.isMatching) "Matching…" else "Auto-Match",
                        enabled = !state.isMatching,
                        focused = focused,
                    ) { viewModel.autoMatchRaByHash() }
                }
            }
            AchievementProvider.LOCAL_STEAM -> {
                // The appid comes from the game folder's steam_settings — nothing to enter by hand.
                Text("Not linked yet", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Local Steam-emu games link from the steam_appid.txt in their game folder. Run Auto-match in Settings ▸ Shiba Coins to link this game.",
                    color = TextMuted,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

// The Auto-Match flow for an unlinked Steam-platform game: one button, a legit-copy prompt that
// picks the Steam-vs-local branch, and a manual appid fallback when neither branch matched.
@Composable
private fun AutoMatchControls(state: ShibaCoinsUiState, viewModel: ShibaCoinsViewModel, focused: Boolean) {
    when (state.autoMatchStep) {
        null -> {
            Text("This game isn't linked yet", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Auto-Match", enabled = !state.isMatching, focused = focused) { viewModel.startAutoMatch() }
                if (state.isMatching) CircularProgressIndicator(color = menuCursorEdge(), modifier = Modifier.size(16.dp))
            }
        }
        AutoMatchStep.CONFIRM_COPY -> {
            Text("Is this a legitimate Steam copy?", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text(
                "A legit copy matches against Steam; anything else scans your game folders for Steam-emu data.",
                color = TextMuted, fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Yes", enabled = true, focused = state.autoMatchYes) { viewModel.chooseAutoMatch(true) }
                PillButton("No", enabled = true, focused = !state.autoMatchYes) { viewModel.chooseAutoMatch(false) }
            }
        }
        AutoMatchStep.ENTER_APPID -> {
            var draft by remember { mutableStateOf("") }
            Text("No automatic match found", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text("Enter the game's Steam app id:", color = TextMuted, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("Steam app id", color = TextMuted) },
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
                PillButton("Link", enabled = draft.isNotBlank() && !state.isMatching) { viewModel.submitManualAppId(draft) }
                PillButton("Cancel", enabled = true) { viewModel.cancelAutoMatch() }
                if (state.isMatching) CircularProgressIndicator(color = menuCursorEdge(), modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun SyncRow(state: ShibaCoinsUiState, viewModel: ShibaCoinsViewModel, focused: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
    ) {
        Text(syncSourceLabel(state), color = TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        if (state.isSyncing) {
            CircularProgressIndicator(color = menuCursorEdge(), modifier = Modifier.size(18.dp))
        } else if (state.linked || state.accountOnly) {
            // "Change match" is user-provided matching — Steam library games only;
            // RetroAchievements is hash-only and account entries have no link to change. On the
            // controller the action row holds both controls: left/right picks, confirm activates.
            val hasChangeMatch = state.provider == AchievementProvider.STEAM && state.linked
            if (hasChangeMatch) {
                val changeFocused = focused && state.actionOnChangeMatch
                val edge = menuCursorEdge()
                Text(
                    "Change match",
                    color = if (changeFocused) Color.White else TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .then(if (changeFocused) Modifier.border(2.dp, edge, RoundedCornerShape(8.dp)) else Modifier)
                        .clickable { viewModel.changeLink() }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            PillButton(
                "Sync now",
                enabled = true,
                focused = focused && !(hasChangeMatch && state.actionOnChangeMatch),
            ) { viewModel.sync() }
        }
    }
}

@Composable
private fun SortFilterChips(state: ShibaCoinsUiState, viewModel: ShibaCoinsViewModel) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = focusRing(state.focusIndex == FOCUS_SORT),
        ) {
            Text("Sort  (X)", color = TextDim, fontSize = 11.sp)
            Chip("Tier", state.sort == CoinSort.TIER) { viewModel.setSort(CoinSort.TIER) }
            Chip("Earned", state.sort == CoinSort.EARNED) { viewModel.setSort(CoinSort.EARNED) }
            Chip("Rarest", state.sort == CoinSort.RAREST) { viewModel.setSort(CoinSort.RAREST) }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = focusRing(state.focusIndex == FOCUS_FILTER),
        ) {
            Text("Show  (Y)", color = TextDim, fontSize = 11.sp)
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
private fun CoinListRow(
    coin: CoinRow,
    focused: Boolean,
    revealed: Boolean = false,
    onToggleReveal: () -> Unit = {},
) {
    // A hidden coin stays redacted until earned — unless the user chose to reveal it (confirm/tap
    // toggles; the same action hides it again).
    val hideable = coin.isHidden && !coin.isEarned
    val redacted = hideable && !revealed
    val edge = menuCursorEdge()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(if (hideable) Modifier.clickable(onClick = onToggleReveal) else Modifier)
            .then(if (focused) Modifier.border(2.dp, edge, RoundedCornerShape(10.dp)) else Modifier)
            .padding(vertical = 9.dp, horizontal = 8.dp),
    ) {
        ShibaCoinIcon(coin.tier, Modifier.size(26.dp).alpha(if (coin.isEarned) 1f else 0.28f))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (redacted) "Hidden coin" else coin.title,
                color = if (coin.isEarned) TextPrimary else TextMuted,
                fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    redacted -> "Keep playing — or press confirm to reveal"
                    // Steam's Web API never returns a hidden achievement's description (even once
                    // earned) — the reveal shows the real title, but there is no how-to to show.
                    coin.isHidden && coin.description.isBlank() -> "Steam keeps this one's description secret"
                    else -> coin.description
                },
                color = TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                // A negative rarity is the "provider reported no percentage" sentinel.
                if (coin.globalRarity < 0) "Rarity unavailable"
                else String.format(Locale.US, "%.1f%%", coin.globalRarity),
                color = metalOf(coin.tier), fontSize = 12.sp,
            )
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
