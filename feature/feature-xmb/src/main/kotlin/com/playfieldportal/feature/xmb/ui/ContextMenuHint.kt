package com.playfieldportal.feature.xmb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.domain.model.ControllerDisplayType
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.components.ControllerPrompt
import com.playfieldportal.core.ui.components.ControllerPromptStyle
import com.playfieldportal.core.ui.components.LocalControllerPromptStyle
import com.playfieldportal.core.ui.preview.CombinedPreviews
import com.playfieldportal.core.ui.preview.PfpPreview

// ── Idle context-menu hint pill ───────────────────────────────────────────────
//
// A small black rounded pill shown in the XMB's bottom-right (stacked above the App
// Drawer button) after the user has been idle for a moment over an item that has a
// context menu. It names the OPEN_CONTEXT_MENU *action*, so the glyph tracks both the
// controller display style and a swapped X/Y layout, beside the word "Options",
// mirroring the reference PSP UI.
//
// Visibility is driven entirely by XMBUiState.showContextMenuHint (the shell/detail screens
// fade it in but remove it immediately when it becomes ineligible); this composable only renders
// its content.

@Composable
fun ContextMenuHint(
    modifier: Modifier = Modifier,
) {
    ControllerPrompt(
        action = GamepadAction.OPEN_CONTEXT_MENU,
        label = "Options",
        modifier = modifier
            .background(
                color = Color.Black.copy(alpha = 0.5f),
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        labelColor = Color.White,
        labelStyle = TextStyle(
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            shadow = Shadow(
                color = Color.Black.copy(alpha = 0.75f),
                offset = Offset(0f, 2f),
                blurRadius = 4f,
            ),
        ),
        glyphSize = 20.dp,
        spacing = 8.dp,
    )
}

// ── Previews ──────────────────────────────────────────────────────────────────

@CombinedPreviews
@Composable
fun ContextMenuHintPreview() {
    PfpPreview {
        // One pill per family, each given its own ambient style so the preview
        // still shows △ / Y / X side by side now that the hint reads context.
        Row(verticalAlignment = Alignment.CenterVertically) {
            for (family in ControllerDisplayType.entries) {
                CompositionLocalProvider(
                    LocalControllerPromptStyle provides ControllerPromptStyle(family = family),
                ) {
                    ContextMenuHint()
                }
                Spacer(Modifier.size(8.dp))
            }
        }
    }
}
