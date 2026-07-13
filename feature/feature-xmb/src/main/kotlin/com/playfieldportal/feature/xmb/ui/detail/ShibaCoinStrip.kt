package com.playfieldportal.feature.xmb.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.domain.achievement.GameCoins
import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.core.ui.theme.menuCursorEdge
import kotlin.math.roundToInt

// Chrome (the progress fill, header icon) follows the active theme accent via menuCursorEdge();
// the coin tallies use the bundled Shiba medallion art.
private val StripFill = Color(0xFF1B1B26)
private val TextPrimary = Color(0xFFEEEEEE)
private val TextMuted = Color(0x88EEEEEE)

/**
 * The Game Detail glance strip: coin-weighted progress, the Bronze/Silver/Gold tally, and the
 * Platinum crown (lit only on 100% mastery). Renders a quiet "not tracked" line when the game has
 * no coins yet. Display-only for now; the drill-in door lands with the dedicated coins screen.
 */
@Composable
internal fun ShibaCoinStrip(
    coins: GameCoins?,
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val accent = menuCursorEdge()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(StripFill)
            .then(if (focused) Modifier.border(2.dp, accent, RoundedCornerShape(12.dp)) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.EmojiEvents, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Shiba Coins", color = TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            if (coins != null) {
                Text("${(coins.progress * 100).roundToInt()}%", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Open Shiba Coins", tint = TextMuted, modifier = Modifier.size(20.dp))
        }

        if (coins == null) {
            Spacer(Modifier.height(6.dp))
            Text("Not tracked yet", color = TextMuted, fontSize = 13.sp)
            return
        }

        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { coins.progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = accent,
            trackColor = Color(0x33FFFFFF),
        )

        Spacer(Modifier.height(12.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            CoinTally(ShibaTier.BRONZE, coins.earned.bronze, coins.total.bronze)
            CoinTally(ShibaTier.SILVER, coins.earned.silver, coins.total.silver)
            CoinTally(ShibaTier.GOLD, coins.earned.gold, coins.total.gold)
            Spacer(Modifier.weight(1f))
            // The Platinum medallion dims until the whole set is mastered.
            ShibaCoinIcon(
                ShibaTier.PLATINUM,
                Modifier.size(24.dp).alpha(if (coins.isMastered) 1f else 0.30f),
            )
        }
    }
}

@Composable
private fun CoinTally(tier: ShibaTier, earned: Int, total: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ShibaCoinIcon(tier, Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("$earned", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text("/$total", color = TextMuted, fontSize = 11.sp)
    }
}
