package com.playfieldportal.feature.xmb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.components.ControllerHintBar
import com.playfieldportal.core.ui.components.ControllerPromptItem

// ── Move and multi-select bars ────────────────────────────────────────────────
//
// While a row is being moved, or games are being marked, the buttons mean something else, so the
// bar that says what they mean is up for the whole of it rather than fading in after an idle
// moment like the hint pill. On a controller it is the shared hint bar; on touch the same actions
// become buttons, because there is nothing else on screen to tap for them.

private val BarShape = RoundedCornerShape(10.dp)
private val BarBackground = Color.Black.copy(alpha = 0.5f)

/**
 * What the controls do while a row is lifted: place it, or put it back. On a controller it is the
 * idle pill's corner chip, PSP-style, and names only Place and Cancel — the chevrons set into the
 * lifted row's outline already say the D-pad slides it. On touch there is no D-pad, so the slide
 * gets buttons of its own.
 */
@Composable
fun MoveModeBar(
    touch: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onPlace: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    // A category on the crossbar slides left / right, so its touch nudges point that way.
    // [onMoveUp] / [onMoveDown] then mean one slot left / right.
    horizontal: Boolean = false,
) {
    if (!touch) {
        ControllerHintBar(
            items = listOf(
                ControllerPromptItem(GamepadAction.SELECT, "Place"),
                ControllerPromptItem(GamepadAction.BACK, "Cancel"),
            ),
            modifier = modifier,
            compact = true,
        )
        return
    }
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (horizontal) {
            BarButton("◀", onMoveUp, contentDescription = "Move left")
            BarButton("▶", onMoveDown, contentDescription = "Move right")
        } else {
            BarButton("▲", onMoveUp, contentDescription = "Move up")
            BarButton("▼", onMoveDown, contentDescription = "Move down")
        }
        BarButton("Place", onPlace)
        BarButton("Cancel", onCancel)
    }
}

/** What the controls do while games are being marked, and how many are marked so far. */
@Composable
fun MarkModeBar(
    count: Int,
    touch: Boolean,
    onAddToCard: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = 25.dp)
                .background(BarBackground, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Sized to the compact chip beside it on a controller.
            Text("$count marked", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        if (!touch) {
            ControllerHintBar(
                items = listOf(
                    ControllerPromptItem(GamepadAction.SELECT, "Mark"),
                    ControllerPromptItem(GamepadAction.OPEN_CONTEXT_MENU, "Add to Card"),
                    ControllerPromptItem(GamepadAction.BACK, "Done"),
                ),
                compact = true,
            )
        } else {
            BarButton("Add to Card", onAddToCard, enabled = count > 0)
            BarButton("Done", onDone)
        }
    }
}

// One touch button in the hint bar's own chrome: the same black pill, sized for a finger.
@Composable
private fun BarButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    Box(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .widthIn(min = 52.dp)
            .clip(BarShape)
            .background(BarBackground)
            .clickable(
                enabled = enabled,
                onClickLabel = contentDescription ?: label,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.4f),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
