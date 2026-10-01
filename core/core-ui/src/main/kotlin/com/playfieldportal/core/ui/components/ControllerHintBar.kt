package com.playfieldportal.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
 *
 * [compact] is the XMB's corner chip: the crossbar draws at its own enlarged scale, where the
 * regular pill reads far bigger than it does on Settings or a detail screen, so the chips there
 * step everything down a size — 12sp labels, 15dp glyphs, tighter padding — to sit in the corner
 * like the PSP's own small prompt rather than a bar.
 */
@Composable
fun ControllerHintBar(
    items: List<ControllerPromptItem>,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    shape: Shape = RoundedCornerShape(if (compact) 8.dp else 10.dp),
    background: Color = Color.Black.copy(alpha = 0.5f),
    arrangement: Arrangement.Horizontal = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
) {
    if (items.isEmpty()) return
    ControllerPromptBar(
        items = items,
        modifier = modifier
            .background(
                color = background,
                shape = shape,
            )
            .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 5.dp else 8.dp),
        labelColor = Color.White,
        labelStyle = TextStyle(
            fontSize = if (compact) 12.sp else 14.sp,
            fontWeight = FontWeight.SemiBold,
            shadow = Shadow(
                color = Color.Black.copy(alpha = 0.75f),
                offset = Offset(0f, 2f),
                blurRadius = 4f,
            ),
        ),
        glyphSize = if (compact) 15.dp else 20.dp,
        arrangement = arrangement,
    )
}

/**
 * The opacity of an idle hint, on the crossbar pill's timing: it fades in over 200ms once
 * [visible] turns true, and is gone on the very frame it turns false. A hint that faded out would
 * still be on screen over whatever the press that dismissed it just moved to.
 *
 * Apply it with `Modifier.alpha`, so the hint keeps its space and nothing shifts when it comes
 * and goes.
 */
@Composable
fun idleHintAlpha(visible: Boolean): Float {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = if (visible) tween(200) else snap(),
        label = "idleHint",
    )
    return alpha
}
