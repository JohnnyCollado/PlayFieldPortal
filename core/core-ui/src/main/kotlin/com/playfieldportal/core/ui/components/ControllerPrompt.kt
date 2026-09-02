package com.playfieldportal.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.playfieldportal.core.domain.model.ControllerDisplayType
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.GamepadMappings

// ── Controller prompts ───────────────────────────────────────────────────────
//
// One way for any screen to say "this action is on that button". Callers name
// the *action*; which physical button performs it, and which family's art draws
// it, are resolved here from the same GamepadMappings the input handler
// consumes. A footer therefore cannot disagree with the pad — not by
// convention, but because both read one table.
//
// Prompts are ambient chrome rather than screen state, and they are needed by
// footers in four feature modules, so they travel by CompositionLocal instead
// of through every screen signature.

/** The controller identity every prompt on screen renders against. */
@Immutable
data class ControllerPromptStyle(
    /** Which pad's art to draw — Settings ▸ Controller ▸ Type. */
    val family: ControllerDisplayType = ControllerDisplayType.XBOX,
    /** The live bindings, reflecting the Confirm/Back and X/Y layout settings. */
    val mappings: GamepadMappings = GamepadMappings(),
)

/**
 * Ambient controller identity. The default is the stock Xbox layout so
 * `@Preview` and tests render something sensible without a provider; the real
 * value is supplied once at the app root from ControllerLayoutRepository.
 *
 * Deliberately not `staticCompositionLocalOf`: this value always changes at
 * least once per cold start, when DataStore resolves and the default is
 * replaced by the user's real settings. A static local would invalidate the
 * whole subtree — the entire app — at that moment; this one invalidates only
 * the handful of footers that actually read it.
 */
val LocalControllerPromptStyle = compositionLocalOf { ControllerPromptStyle() }

/**
 * A single "button — what it does" prompt.
 *
 * Renders nothing when [action] is not bound to a physical button under the
 * current layout: a prompt that cannot be honoured is worse than no prompt.
 */
@Composable
fun ControllerPrompt(
    action: GamepadAction,
    label: String,
    modifier: Modifier = Modifier,
    style: ControllerPromptStyle = LocalControllerPromptStyle.current,
    labelColor: Color = Color.White.copy(alpha = 0.75f),
    labelStyle: TextStyle = TextStyle.Default,
    glyphSize: Dp = 22.dp,
    spacing: Dp = 4.dp,
) {
    val icon = style.mappings.iconFor(action) ?: return
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        ControllerIconGlyph(icon = icon, family = style.family, size = glyphSize)
        Text(
            text = label,
            color = labelColor,
            style = labelStyle,
        )
    }
}

/**
 * A row of prompts — the shape every command bar and footer hint wants.
 *
 * [prompts] is ordered action-to-label; unbound actions drop out silently, so a
 * bar stays coherent rather than showing a gap.
 */
@Composable
fun ControllerPromptBar(
    prompts: List<Pair<GamepadAction, String>>,
    modifier: Modifier = Modifier,
    style: ControllerPromptStyle = LocalControllerPromptStyle.current,
    labelColor: Color = Color.White.copy(alpha = 0.75f),
    labelStyle: TextStyle = TextStyle.Default,
    glyphSize: Dp = 22.dp,
    arrangement: Arrangement.Horizontal = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = arrangement,
    ) {
        for ((action, label) in prompts) {
            ControllerPrompt(
                action = action,
                label = label,
                style = style,
                labelColor = labelColor,
                labelStyle = labelStyle,
                glyphSize = glyphSize,
            )
        }
    }
}
