package com.playfieldportal.feature.xmb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.domain.model.ControllerDisplayType
import com.playfieldportal.core.ui.icons.contextHintButtonDrawable
import com.playfieldportal.core.ui.preview.CombinedPreviews
import com.playfieldportal.core.ui.preview.PfpPreview

// ── Idle context-menu hint pill ───────────────────────────────────────────────
//
// A small black rounded pill shown in the XMB's bottom-right (stacked above the App
// Drawer button) after the user has been idle for a moment over an item that has a
// context menu. It shows the face-button glyph for the user's controller display
// style (△ / Y / X) beside the word "Options", mirroring the reference PSP UI.
//
// Visibility is driven entirely by XMBUiState.showContextMenuHint (the shell/detail screens
// fade it in but remove it immediately when it becomes ineligible); this composable only renders
// its content.

@Composable
fun ContextMenuHint(
    displayType: ControllerDisplayType,
    modifier: Modifier = Modifier,
) {
    val glyph = painterResource(displayType.contextHintButtonDrawable())
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(
                color = Color.Black.copy(alpha = 0.5f),
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Image(
            painter = glyph,
            contentDescription = null, // the adjacent "Options" text is the label
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = "Options",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            style = TextStyle(
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.75f),
                    offset = Offset(0f, 2f),
                    blurRadius = 4f,
                ),
            ),
        )
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@CombinedPreviews
@Composable
fun ContextMenuHintPreview() {
    PfpPreview {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ContextMenuHint(ControllerDisplayType.PLAYSTATION)
            Spacer(Modifier.size(8.dp))
            ContextMenuHint(ControllerDisplayType.XBOX)
            Spacer(Modifier.size(8.dp))
            ContextMenuHint(ControllerDisplayType.NINTENDO)
        }
    }
}
