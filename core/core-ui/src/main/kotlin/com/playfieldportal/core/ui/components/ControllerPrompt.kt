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
import com.playfieldportal.core.domain.model.ControllerIcon
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
    ControllerPrompt(
        actions = listOf(action),
        label = label,
        modifier = modifier,
        style = style,
        labelColor = labelColor,
        labelStyle = labelStyle,
        glyphSize = glyphSize,
        spacing = spacing,
    )
}

/**
 * A prompt naming several inputs under one label — "◀▶ Seek", "L1/R1 Prev / Next".
 *
 * The alternative, one prompt per input, doubles the width of an already tight
 * media footer and reads as two unrelated actions rather than one range.
 *
 * Same contract as the single-action form: [actions] that are not bound to a
 * physical button drop out, and when none of them resolve the prompt renders
 * nothing at all.
 */
@Composable
fun ControllerPrompt(
    actions: List<GamepadAction>,
    label: String,
    modifier: Modifier = Modifier,
    style: ControllerPromptStyle = LocalControllerPromptStyle.current,
    labelColor: Color = Color.White.copy(alpha = 0.75f),
    labelStyle: TextStyle = TextStyle.Default,
    glyphSize: Dp = 22.dp,
    spacing: Dp = 4.dp,
    /** Gap between the glyphs themselves — tighter than [spacing], so a pair reads as one unit. */
    glyphSpacing: Dp = 2.dp,
) {
    ControllerPromptGlyphs(
        icons = style.mappings.iconsFor(actions),
        label = label,
        modifier = modifier,
        family = style.family,
        labelColor = labelColor,
        labelStyle = labelStyle,
        glyphSize = glyphSize,
        spacing = spacing,
        glyphSpacing = glyphSpacing,
    )
}

/**
 * The renderer every prompt form funnels into, taking positions that are already
 * resolved.
 *
 * Public because a few prompts name an input that no setting can remap and that
 * therefore has no action to resolve: the D-pad as a whole ([ControllerIcon.DPAD_ALL]),
 * and a raw-keycode escape hatch such as the PTT capture's cancel button, which
 * reads the physical button before any mapping is applied. Naming the position
 * directly is the truthful thing there; everywhere else, name the action and let
 * the mappings decide.
 *
 * Renders nothing for an empty [icons], so an unresolvable prompt disappears
 * rather than showing a bare label.
 */
@Composable
fun ControllerPromptGlyphs(
    icons: List<ControllerIcon>,
    label: String,
    modifier: Modifier = Modifier,
    family: ControllerDisplayType = LocalControllerPromptStyle.current.family,
    labelColor: Color = Color.White.copy(alpha = 0.75f),
    labelStyle: TextStyle = TextStyle.Default,
    glyphSize: Dp = 22.dp,
    spacing: Dp = 4.dp,
    glyphSpacing: Dp = 2.dp,
) {
    if (icons.isEmpty()) return
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(glyphSpacing),
        ) {
            for (icon in icons) {
                ControllerIconGlyph(icon = icon, family = family, size = glyphSize)
            }
        }
        Text(
            text = label,
            color = labelColor,
            style = labelStyle,
        )
    }
}

/**
 * One entry in a [ControllerPromptBar]. [actions] names a single input or a set
 * that share a label.
 *
 * [fixedIcons], when set, is drawn instead of resolving [actions] — see
 * [ControllerPromptGlyphs] for when that is the honest choice. Build those with
 * [fixed] rather than by hand.
 */
@Immutable
data class ControllerPromptItem(
    val actions: List<GamepadAction>,
    val label: String,
    val fixedIcons: List<ControllerIcon>? = null,
) {
    constructor(action: GamepadAction, label: String) : this(listOf(action), label)

    companion object {
        /** A prompt for a position no setting remaps (the D-pad, a raw-keycode escape). */
        fun fixed(icon: ControllerIcon, label: String) =
            ControllerPromptItem(emptyList(), label, listOf(icon))
    }
}

/**
 * A row of prompts — the shape every command bar and footer hint wants.
 *
 * [items] is ordered; unbound actions drop out silently, so a bar stays coherent
 * rather than showing a gap. An item may name one input or several, which is what
 * the media footers need: "Seek" is a D-pad pair, "Play" one face button.
 *
 * Takes items rather than action-to-label pairs because a `List<Pair<..>>` and a
 * `List<ControllerPromptItem>` overload erase to the same JVM signature.
 */
@Composable
fun ControllerPromptBar(
    items: List<ControllerPromptItem>,
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
        for (item in items) {
            ControllerPromptGlyphs(
                icons = item.fixedIcons ?: style.mappings.iconsFor(item.actions),
                label = item.label,
                family = style.family,
                labelColor = labelColor,
                labelStyle = labelStyle,
                glyphSize = glyphSize,
            )
        }
    }
}
