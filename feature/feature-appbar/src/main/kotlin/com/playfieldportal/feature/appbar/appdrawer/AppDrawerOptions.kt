package com.playfieldportal.feature.appbar.appdrawer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.ui.theme.StorefrontColors
import com.playfieldportal.feature.appbar.AppMenuAction
import com.playfieldportal.feature.appbar.InstalledApp

// ── Options mini-menu ─────────────────────────────────────────────────────────
//
// Sharp low-radius rectangular panel, thin border, accent-derived selection row — the same
// PSP-era language the rest of the drawer now speaks. Moved here (as-is) from the previous
// implementation; its behaviour is untouched.

private val PANEL_CORNER = 2.dp
private val PANEL_BORDER = 1.dp

@Composable
internal fun AppDrawerOptions(
    app: InstalledApp,
    actions: List<AppMenuAction>,
    selectedIndex: Int,
    onAction: (AppMenuAction) -> Unit,
    onDismiss: () -> Unit,
    colors: StorefrontColors,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.overlayDim)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(280.dp)
                .clip(RoundedCornerShape(PANEL_CORNER))
                .background(colors.menuPanel)
                .border(PANEL_BORDER, colors.chromeDivider.copy(alpha = 0.4f), RoundedCornerShape(PANEL_CORNER))
                .padding(vertical = 8.dp),
        ) {
            // Menu title
            Text(
                text = app.label,
                color = colors.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            // Thin divider
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .padding(horizontal = 12.dp)
                    .background(colors.chromeDivider.copy(alpha = 0.25f)),
            )
            Spacer(Modifier.height(4.dp))
            // Action rows
            actions.forEachIndexed { i, action ->
                val destructive = action == AppMenuAction.UNINSTALL
                val isSelected = i == selectedIndex
                Text(
                    text = action.label,
                    color = when {
                        isSelected && destructive -> colors.destructive
                        isSelected -> colors.textPrimary
                        destructive -> colors.destructive.copy(alpha = 0.7f)
                        else -> colors.textSecondary
                    },
                    fontSize = 14.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (isSelected) colors.menuRowSelected else Color.Transparent)
                        .clickable { onAction(action) }
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                )
            }
        }
    }
}

// ── Uninstall confirmation ────────────────────────────────────────────────────
//
// The one component still using a raw Material AlertDialog before the redesign; rebuilt as a Box
// scrim + low-radius panel mirroring AppDrawerOptions' construction, with the confirm row tinted
// destructive. Behaviour is unchanged: SELECT confirms, anything else cancels (see
// AppDrawerViewModel.handleGamepadAction).

@Composable
internal fun UninstallConfirmDialog(
    app: InstalledApp,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    colors: StorefrontColors,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.overlayDim)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { onCancel() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(340.dp)
                .clip(RoundedCornerShape(PANEL_CORNER))
                .background(colors.menuPanel)
                .border(PANEL_BORDER, colors.chromeDivider.copy(alpha = 0.4f), RoundedCornerShape(PANEL_CORNER)),
        ) {
            Text(
                text = "Uninstall ${app.label}?",
                color = colors.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            )
            // Thin divider
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .padding(horizontal = 12.dp)
                    .background(colors.chromeDivider.copy(alpha = 0.25f)),
            )
            Text(
                text = "This removes ${app.label} from your device. Android will ask you to confirm.",
                color = colors.textSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Cancel",
                    color = colors.textSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clickable { onCancel() }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
                Text(
                    text = "Uninstall",
                    color = colors.destructive,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(PANEL_CORNER))
                        .background(colors.destructive.copy(alpha = 0.12f))
                        .clickable { onConfirm() }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}
