package com.playfieldportal.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.XYLayout
import com.playfieldportal.core.ui.components.ControllerPromptItem

/**
 * One user-media assignment row: what it is, what is currently assigned, and the two
 * controller-reachable actions that act on it. Selecting the row opens the system picker.
 *
 * Extracted from Interface ▸ Sound so Display ▸ Boot Sequence and Display ▸ GameBoot are the SAME
 * row rather than a copy of it — the three screens differ only in which slot they hand it. A user
 * who has learned the shortcuts on one screen already knows them on the others.
 *
 * [onUseDefault]'s action only appears while something custom is assigned: an action that would do
 * nothing is worse than no action for controller navigation, which has to step through every one.
 * [onPreview] is always offered, because previewing the bundled default is exactly how a user
 * decides whether they want to replace it.
 */
@Composable
fun MediaAssignmentRow(
    label: String,
    focusKey: String,
    value: String,
    isAssigned: Boolean,
    onPick: () -> Unit,
    onPreview: () -> Unit,
    onUseDefault: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    sublabel: String? = null,
) {
    SettingsRow(
        label = label,
        sublabel = sublabel,
        focusKey = focusKey,
        onClick = onPick,
        onFocusChangedExternal = onFocusChanged,
        trailing = {
            Text(
                text = value,
                color = SettingsAccent,
                fontSize = 13.sp,
                style = TextStyle(shadow = SettingsTextShadow),
            )
        },
        actions = buildList {
            add(
                SettingsRowAction(
                    "Preview $label", onPreview,
                    actionFocusBackgroundColor = lerp(SettingsAccent, Color.Black, 0.50f),
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Preview $label",
                        tint = SettingsAccent,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.1f), RoundedCornerShape(6.dp))
                            .padding(4.dp),
                    )
                }
            )
            if (isAssigned) {
                add(
                    SettingsRowAction(
                        "Use the PFP default for $label", onUseDefault,
                        actionFocusBackgroundColor = lerp(Color(0xFFE55353), Color.Black, 0.50f),
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Use the PFP default for $label",
                            tint = Color(0xFFE55353),
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.1f), RoundedCornerShape(6.dp))
                                .padding(4.dp),
                        )
                    }
                )
            }
        },
    )
}

/**
 * The face-button shortcuts that act on a focused [MediaAssignmentRow], bound to PHYSICAL
 * positions so they survive the user's X/Y layout setting: the **north**-facing button (Y on
 * Xbox/PS pads, X on Nintendo) resets the row to the PFP default, and the **west**-facing button
 * (X on Xbox/PS, Y on Nintendo) plays its preview. Neither button has another role on the screens
 * that use these rows, so no layout can collide with one.
 *
 * Mechanically: XMBViewModel forwards both presses into the settings layer as
 * OPEN_CONTEXT_MENU / CHANGE_SORT ("treated identically", per its own comment), and these map them
 * back to physical positions through the user's [XYLayout]. Kept here beside the row so the three
 * screens using it cannot drift into different bindings.
 */
object MediaRowShortcuts {

    /** The action the NORTH-facing button emits under [layout] — "use the default". */
    fun northFace(layout: XYLayout): GamepadAction = when (layout) {
        XYLayout.STANDARD -> GamepadAction.OPEN_CONTEXT_MENU
        XYLayout.SWAPPED -> GamepadAction.CHANGE_SORT
    }

    /** The action the WEST-facing button emits under [layout] — "preview". */
    fun westFace(layout: XYLayout): GamepadAction = when (layout) {
        XYLayout.STANDARD -> GamepadAction.CHANGE_SORT
        XYLayout.SWAPPED -> GamepadAction.OPEN_CONTEXT_MENU
    }

    fun isNorthFace(action: GamepadAction, layout: XYLayout): Boolean = action == northFace(layout)

    fun isWestFace(action: GamepadAction, layout: XYLayout): Boolean = action == westFace(layout)

    /**
     * The prompt-bar entries for a focused row. [isAssigned] gates the reset prompt so the bar
     * never advertises an action the row would ignore.
     *
     * The actions handed to the prompts are the SAME position-resolved ones the interceptor
     * matches on, so the glyph the user sees is the button they actually have to press under
     * their current X/Y layout.
     */
    fun promptsFor(
        layout: XYLayout,
        isAssigned: Boolean,
        resetLabel: String = "Use Default",
        previewLabel: String = "Preview",
    ): List<ControllerPromptItem> = buildList {
        if (isAssigned) add(ControllerPromptItem(action = northFace(layout), label = resetLabel))
        add(ControllerPromptItem(action = westFace(layout), label = previewLabel))
    }
}
