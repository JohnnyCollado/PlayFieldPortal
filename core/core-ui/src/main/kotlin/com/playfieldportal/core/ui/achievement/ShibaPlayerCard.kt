package com.playfieldportal.core.ui.achievement

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.playfieldportal.core.domain.achievement.CoinWallet
import com.playfieldportal.core.domain.achievement.ShibaLevel
import com.playfieldportal.core.ui.theme.menuCursorEdge

// Shared surface tones, matching the Game Detail coin strip. Chrome (medallion ring, progress fill)
// follows the active theme accent via menuCursorEdge(); only the text tones are fixed.
private val CardFill = Color(0xFF1B1B26)
private val TextPrimary = Color(0xFFEEEEEE)
private val TextMuted = Color(0x88EEEEEE)

/**
 * The account-wide Shiba standing: rank title, current Shiba Level, progress toward the next level,
 * and the running coin total. Reads a fully-derived [CoinWallet], so it stays presentation-only and
 * is reused across the connect-accounts screen and the achievements category hub. Accent-driven
 * chrome; theme-agnostic text tones.
 */
@Composable
fun ShibaPlayerCard(
    wallet: CoinWallet,
    modifier: Modifier = Modifier,
) {
    val accent = menuCursorEdge()
    val progress = wallet.levelProgress
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardFill)
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LevelMedallion(level = wallet.level, accent = accent)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(8.dp))
                Text(wallet.rank.label, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                if (wallet.paws > 0) {
                    Spacer(Modifier.width(10.dp))
                    PawBadge(paws = wallet.paws, accent = accent)
                }
                Spacer(Modifier.weight(1f))
                Text("${"%,d".format(wallet.totalCoins)} coins", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = accent,
                trackColor = Color(0x33FFFFFF),
            )

            Spacer(Modifier.height(6.dp))
            // At the top of a cycle the next level IS the next Paw — say so instead of "level 1000".
            val nextLabel =
                if (wallet.level == ShibaLevel.LEVELS_PER_PAW) "your next Paw"
                else "level ${wallet.level + 1}"
            Text(
                text = "${"%,d".format(progress.coinsIntoLevel)} / ${"%,d".format(progress.coinsForNextLevel)} to $nextLabel",
                color = TextMuted,
                fontSize = 11.sp,
            )
        }
    }
}

/** The prestige chip: one Paw per 999 levels earned, permanent once minted. */
@Composable
private fun PawBadge(paws: Int, accent: Color) {
    Text(
        text = if (paws == 1) "1 Paw" else "$paws Paws",
        color = accent,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.16f))
            .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun LevelMedallion(level: Int, accent: Color) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.18f))
            .border(2.dp, accent, CircleShape),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("LV", color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text("$level", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}
