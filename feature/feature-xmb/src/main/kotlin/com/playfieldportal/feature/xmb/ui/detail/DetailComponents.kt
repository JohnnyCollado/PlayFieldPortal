package com.playfieldportal.feature.xmb.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.playfieldportal.core.ui.theme.menuCursorEdge

// The hero-card detail design system, shared by the Game Detail page (ROMs, game apps, PC
// shortcuts) and the App Detail page (standard apps) so every full-page entry detail reads
// identically: breadcrumb → hero card → icon + primary action/squares.
//
// Neutral dark surfaces stay fixed; every accent/focus color comes from the active theme via
// menuCursorFill()/menuCursorEdge() so these screens follow the chosen color scheme. The color
// values are file-private (each detail screen keeps its own copy) because several files in this
// package declare the same names.
private val TextPrimary = Color(0xFFEEEEEE)
private val TextMuted = Color(0xAAEEEEEE)
private val ActionFill = Color(0xFF1B1B26)

internal val HeroBannerHeight: Dp = 220.dp

// Breadcrumb header, same shape as the App Drawer's: ◀ + title + subtitle. Always visible —
// it replaces the old touch-only Back/Options pills as the way back off this page.
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
                interactionSource = androidx.compose.runtime.remember {
                    androidx.compose.foundation.interaction.MutableInteractionSource()
                },
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

// Rounded hero card with the title + platform overlaid bottom-start, per the mockup.
@Composable
internal fun HeroCard(
    uri: String?,
    title: String,
    platform: String,
    accentColor: Color,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(HeroBannerHeight)
            .clip(RoundedCornerShape(14.dp))
            .background(accentColor.copy(alpha = 0.18f)),
        contentAlignment = Alignment.BottomStart,
    ) {
        if (uri != null) {
            AsyncImage(uri, title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
        )
        Column(Modifier.padding(horizontal = 26.dp, vertical = 20.dp)) {
            Text(title, color = TextPrimary, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(platform, color = TextMuted, fontSize = 15.sp, maxLines = 1)
        }
    }
}

/** The 196×110 icon frame. When [content] is provided it fills the frame (e.g. a package-icon
 *  preview); otherwise [uri] is loaded, falling back to the title's initial. */
@Composable
internal fun IconTile(
    uri: String?,
    title: String,
    content: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .width(196.dp)
            .height(110.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(ActionFill)
            .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            content != null -> content()
            uri != null -> AsyncImage(uri, title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else -> Text(title.take(1).uppercase(), color = TextMuted, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun SquareActionButton(
    icon: ImageVector,
    contentDescription: String,
    focused: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(ActionFill)
            .then(if (focused) Modifier.border(2.dp, menuCursorEdge(), RoundedCornerShape(8.dp)) else Modifier)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = TextPrimary, modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun ConsoleButton(
    label: String,
    icon: ImageVector,
    focused: Boolean,
    fill: Color,
    textColor: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(fill)
            .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .focusable()
            .padding(vertical = 15.dp, horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = textColor, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = textColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}
