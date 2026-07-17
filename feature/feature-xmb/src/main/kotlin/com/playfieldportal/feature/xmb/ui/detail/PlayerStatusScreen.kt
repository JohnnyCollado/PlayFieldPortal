package com.playfieldportal.feature.xmb.ui.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
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
import com.playfieldportal.core.ui.achievement.BoneGlyph
import com.playfieldportal.core.ui.theme.LocalPFPColors
import com.playfieldportal.core.ui.theme.menuCursorEdge

private val Bronze = Color(0xFFC07C46)
private val Silver = Color(0xFFB9C0C7)
private val Gold = Color(0xFFE1B12C)
private val Platinum = Color(0xFF6F9BF5)
private val TextPrimary = Color(0xFFEEEEEE)
private val TextMuted = Color(0x99EEEEEE)
private val TextDim = Color(0x66EEEEEE)
private val CardFill = Color(0xFF1B1B26)
private val Track = Color(0x33FFFFFF)

// One DOWN/UP press scrolls the page by this much while browsing (before focus enters the feed).
private val PAGE_SCROLL_STRIDE = 200.dp

private fun metalOf(tier: ShibaTier) = when (tier) {
    ShibaTier.BRONZE -> Bronze
    ShibaTier.SILVER -> Silver
    ShibaTier.GOLD -> Gold
    ShibaTier.PLATINUM -> Platinum
}

/**
 * The account-wide player status view: Shiba level and rank, the XP curve toward the next level,
 * the tiered coin (achievement) wallet, recent unlocks, and the single rarest coin earned. Opened
 * from the XMB Shiba Coin player card and the Settings player card. Read-only and offline — every
 * field comes from the cached [PlayerStatusUiState]; activating a recent or rarest row opens that
 * game's Shiba Coins overlay.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayerStatusScreen(
    onClose: () -> Unit,
    onOpenCoins: (ShibaCoinsTarget) -> Unit,
    modifier: Modifier = Modifier,
    pendingGamepadAction: GamepadAction? = null,
    onGamepadActionConsumed: () -> Unit = {},
    viewModel: PlayerStatusViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(state.closed) {
        if (state.closed) { onClose(); viewModel.onClosedHandled() }
    }
    LaunchedEffect(state.openCoins) {
        state.openCoins?.let { target -> onOpenCoins(target); viewModel.onOpenHandled() }
    }
    LaunchedEffect(pendingGamepadAction) {
        if (pendingGamepadAction != null) {
            viewModel.handleGamepadAction(pendingGamepadAction)
            onGamepadActionConsumed()
        }
    }

    // Controller scroll-to-focus: each recent row (and the rarest card) owns a BringIntoViewRequester;
    // moving focus scrolls that element fully into view (never clipped). Focusing the very first
    // recent row returns to the absolute top, so the breadcrumb and XP panel above the list stay
    // reachable (bringIntoView alone would pin the first row to the top edge).
    val scrollState = rememberScrollState()
    val recentRequesters = remember { mutableStateMapOf<Int, BringIntoViewRequester>() }
    val rarestRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(state.recentIndex, state.onRarest) {
        when {
            state.onRarest -> runCatching { rarestRequester.bringIntoView() }
            state.recentIndex == 0 -> scrollState.animateScrollTo(0)
            else -> runCatching { recentRequesters[state.recentIndex]?.bringIntoView() }
        }
    }
    // While the rarest card holds focus, UP/DOWN nudge the PAGE: the ViewModel accumulates a
    // counter and each delta becomes a relative scroll (relative because the card's position
    // came from bringIntoView, not a known offset).
    val stridePx = with(LocalDensity.current) { PAGE_SCROLL_STRIDE.roundToPx() }
    LaunchedEffect(Unit) {
        var last = 0
        snapshotFlow { state.rarestPageNudge }.collect { nudge ->
            val delta = nudge - last
            last = nudge
            if (delta != 0) scrollState.animateScrollBy(delta * stridePx.toFloat())
        }
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 1100.dp)
                .align(Alignment.TopCenter)
                .verticalScroll(scrollState)
                .padding(horizontal = 28.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DetailBreadcrumb(title = "Player Status", subtitle = "Shiba Coins", onBack = viewModel::close)

            // Top row: the level + rank + XP panel, full width.
            LevelRankCard(state, Modifier.fillMaxWidth())

            // Lower band: the recent feed on the left; the wallet and rarest card stacked on the
            // right, their tops aligned with the recent feed.
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                RecentCard(state, viewModel, Modifier.weight(1f), recentRequesters)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    WalletCard(state, Modifier.fillMaxWidth())
                    RarestCardView(state, viewModel, Modifier.fillMaxWidth(), rarestRequester)
                }
            }
        }
    }
}

@Composable
private fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(CardFill)
            .padding(20.dp),
    ) { content() }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun LevelRankCard(state: PlayerStatusUiState, modifier: Modifier) {
    val accent = menuCursorEdge()
    SectionCard(modifier) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LevelBadge(state.level, accent)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    // "<Rank> • <bones> [bone glyph]"
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(state.rankLabel, color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (state.bones > 0) {
                            Spacer(Modifier.width(8.dp))
                            Text("•  ${state.bones}", color = accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(5.dp))
                            BoneGlyph(tint = accent, size = 18.dp)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Shiba standing", color = TextMuted, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${"%,d".format(state.xpIntoLevel)} / ${"%,d".format(state.xpForNextLevel)} XP",
                    color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { state.levelFraction },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = accent, trackColor = Track,
            )
            Spacer(Modifier.height(8.dp))
            val nextLabel = if (state.nextIsBone) "your next Bone" else "next level"
            Text("${"%,d".format(state.xpToNext)} XP to $nextLabel", color = TextMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun LevelBadge(level: Int, accent: Color) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(accent.copy(alpha = 0.18f))
            .border(2.dp, accent, RoundedCornerShape(18.dp)),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("LEVEL", color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text("$level", color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun WalletCard(state: PlayerStatusUiState, modifier: Modifier) {
    SectionCard(modifier) {
        Column {
            SectionLabel("SHIBA COIN WALLET")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                TierPill(ShibaTier.BRONZE, "Bronze", state.bronze, Modifier.weight(1f))
                TierPill(ShibaTier.SILVER, "Silver", state.silver, Modifier.weight(1f))
                TierPill(ShibaTier.GOLD, "Gold", state.gold, Modifier.weight(1f))
                TierPill(ShibaTier.PLATINUM, "Platinum", state.platinum, Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("TOTAL XP", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                Text("%,d".format(state.totalXp), color = Gold, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${"%,d".format(state.coinsEarned)} / ${"%,d".format(state.coinsAvailable)} coins  •  " +
                    "${state.gamesTracked} tracked  •  ${state.gamesMastered} mastered",
                color = TextDim, fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun TierPill(tier: ShibaTier, label: String, count: Int, modifier: Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        ShibaCoinIcon(tier, Modifier.size(44.dp))
        Spacer(Modifier.height(6.dp))
        Text("%,d".format(count), color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(label, color = metalOf(tier), fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentCard(
    state: PlayerStatusUiState,
    viewModel: PlayerStatusViewModel,
    modifier: Modifier,
    requesters: MutableMap<Int, BringIntoViewRequester>,
) {
    SectionCard(modifier) {
        Column {
            SectionLabel("RECENT ACHIEVEMENTS")
            if (state.recent.isEmpty()) {
                Text("No unlocks yet — sync your games to fill this in.", color = TextMuted, fontSize = 13.sp)
                return@Column
            }
            state.recent.forEachIndexed { i, row ->
                val requester = remember(i) { BringIntoViewRequester() }
                DisposableEffect(i) {
                    requesters[i] = requester
                    onDispose { requesters.remove(i) }
                }
                RecentRowView(
                    row,
                    focused = state.recentFocused(i),
                    modifier = Modifier.bringIntoViewRequester(requester),
                ) { viewModel.onRecentClick(i) }
                if (i < state.recent.lastIndex) Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun RecentRowView(row: RecentRow, focused: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val accent = menuCursorEdge()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(if (focused) Modifier.border(2.dp, accent, RoundedCornerShape(10.dp)) else Modifier)
            .then(if (row.coinsTarget != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(8.dp),
    ) {
        CoinArt(row.iconUrl, row.tier, Modifier.size(44.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(row.coinTitle, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${row.gameTitle}  •  ${relativeTime(row.earnedAt)}",
                color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RarestCardView(
    state: PlayerStatusUiState,
    viewModel: PlayerStatusViewModel,
    modifier: Modifier,
    requester: BringIntoViewRequester,
) {
    val rarest = state.rarest
    val accent = menuCursorEdge()
    val focused = state.rarestFocused
    SectionCard(modifier.bringIntoViewRequester(requester)) {
        Column {
            SectionLabel("RAREST ACHIEVEMENT UNLOCKED")
            if (rarest == null) {
                Text("No rarity data yet.", color = TextMuted, fontSize = 13.sp)
                return@Column
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .then(if (focused) Modifier.border(2.dp, accent, RoundedCornerShape(12.dp)) else Modifier)
                    .then(if (rarest.coinsTarget != null) Modifier.clickable { viewModel.onRarestClick() } else Modifier)
                    .padding(8.dp),
            ) {
                CoinArt(rarest.iconUrl, rarest.tier, Modifier.size(72.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(rarest.coinTitle, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(rarest.gameTitle, color = TextMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Unlocked by ${rarityText(rarest.globalRarity)} of players",
                        color = accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun CoinArt(iconUrl: String?, tier: ShibaTier, modifier: Modifier) {
    if (iconUrl != null) {
        AsyncImage(
            model = iconUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(8.dp)),
        )
    } else {
        ShibaCoinIcon(tier, modifier)
    }
}

private fun rarityText(globalRarity: Double): String =
    if (globalRarity < 0) "an unknown share" else String.format(java.util.Locale.US, "%.2f%%", globalRarity)

// A short, human unlock age: "Just now", "5 min ago", "3 hr ago", "Yesterday", "5 days ago".
private fun relativeTime(earnedAtMillis: Long, now: Long = System.currentTimeMillis()): String {
    val diff = (now - earnedAtMillis).coerceAtLeast(0)
    val minutes = diff / 60_000
    val hours = diff / 3_600_000
    val days = diff / 86_400_000
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "$minutes min ago"
        hours < 24 -> "$hours hr ago"
        days == 1L -> "Yesterday"
        else -> "$days days ago"
    }
}
