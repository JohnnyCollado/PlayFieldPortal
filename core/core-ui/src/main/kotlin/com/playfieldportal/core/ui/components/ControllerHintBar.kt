package com.playfieldportal.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Idle controller hint pill ────────────────────────────────────────────────
//
// The small dark rounded pill the XMB and the App Drawer both use to name *actions* once the
// user has been idle: glyphs track the controller family and X/Y swap through
// [ControllerPromptBar], so the pill can never disagree with the pad. Lives in core-ui because
// two feature modules render it (feature-xmb's ContextMenuHint and the App Drawer's hint bar) and
// features must not depend on each other.

/**
 * A rounded black pill of controller prompts, faded in by the caller when the user has been idle.
 *
 * Surface-level chrome ([shape], [background], [arrangement]) is parameterized; the inner prompt
 * look is fixed — 14sp SemiBold white labels with the classic drop shadow and 20dp glyphs — so
 * every pill in the app reads as one system. Renders nothing for an empty [items] (the caller may
 * skip the call instead — both are safe).
 */
@Composable
fun ControllerHintBar(
    items: List<ControllerPromptItem>,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    background: Color = Color.Black.copy(alpha = 0.5f),
    arrangement: Arrangement.Horizontal = Arrangement.spacedBy(16.dp),
) {
    if (items.isEmpty()) return
    ControllerPromptBar(
        items = items,
        modifier = modifier
            .background(
                color = background,
                shape = shape,
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
        arrangement = arrangement,
    )
}
