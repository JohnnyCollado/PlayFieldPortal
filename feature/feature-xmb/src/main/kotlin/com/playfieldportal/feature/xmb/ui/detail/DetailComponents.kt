package com.playfieldportal.feature.xmb.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The slim ◀ + title + subtitle header the full-page secondary screens share (Shiba Coins, Player
// Status). The primary entry pages — Game Detail and App Detail — render the richer
// `PfpDetailBreadcrumb` from core-ui instead: the same ◀ + title + subtitle shape, pinned above the
// scrolling body with a thin divider under it.

private val TextPrimary = Color(0xFFEEEEEE)
private val TextMuted = Color(0xAAEEEEEE)

// Same shape as the App Drawer's: ◀ + title + subtitle. Always visible — it replaces the old
// touch-only Back/Options pills as the way back off the page.
@Composable
internal fun DetailBreadcrumb(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Arrow AND title stack both trigger back — one tap target, no press highlight.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onBack,
            ),
        ) {
            Text(
                text     = "◀",
                color    = TextMuted,
                fontSize = 16.sp,
                modifier = Modifier.padding(end = 16.dp),
            )
            Column {
                Text(title, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = TextMuted, fontSize = 12.sp)
            }
        }
    }
}
