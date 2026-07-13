package com.playfieldportal.feature.xmb.ui.detail

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.domain.achievement.GameCoins
import com.playfieldportal.core.ui.theme.menuCursorEdge
import kotlin.math.roundToInt

// Tier metals are fixed identity — never themed, so a gold coin always reads gold. Chrome (the
// progress fill, header icon) follows the active theme accent via menuCursorEdge().
private val Bronze = Color(0xFFC07C46)
private val Silver = Color(0xFFB9C0C7)
private val Gold = Color(0xFFE1B12C)
private val Platinum = Color(0xFF6F9BF5)
private val StripFill = Color(0xFF1B1B26)
private val TextPrimary = Color(0xFFEEEEEE)
private val TextMuted = Color(0x88EEEEEE)

/**
 * The Game Detail glance strip: coin-weighted progress, the Bronze/Silver/Gold tally, and the
 * Platinum crown (lit only on 100% mastery). Renders a quiet "not tracked" line when the game has
 * no coins yet. Display-only for now; the drill-in door lands with the dedicated coins screen.
 */
@Composable
internal fun ShibaCoinStrip(coins: GameCoins?, modifier: Modifier = Modifier) {
    val accent = menuCursorEdge()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(StripFill)
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
            CoinTally(Bronze, coins.earned.bronze, coins.total.bronze)
            CoinTally(Silver, coins.earned.silver, coins.total.silver)
            CoinTally(Gold, coins.earned.gold, coins.total.gold)
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Filled.EmojiEvents,
                contentDescription = if (coins.isMastered) "Platinum earned" else "Platinum locked",
                tint = if (coins.isMastered) Platinum else Platinum.copy(alpha = 0.30f),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun CoinTally(metal: Color, earned: Int, total: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(metal))
        Spacer(Modifier.width(6.dp))
        Text("$earned", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text("/$total", color = TextMuted, fontSize = 11.sp)
    }
}
