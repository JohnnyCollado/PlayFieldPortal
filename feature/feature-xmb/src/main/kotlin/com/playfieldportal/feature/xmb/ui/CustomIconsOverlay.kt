package com.playfieldportal.feature.xmb.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import com.playfieldportal.core.domain.model.ControllerIcon
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.components.ControllerPromptBar
import com.playfieldportal.core.ui.components.ControllerPromptItem
import com.playfieldportal.core.ui.icons.CustomIcon
import com.playfieldportal.core.ui.icons.CustomIconSurface
import com.playfieldportal.core.ui.icons.LocalIconAnimating
import com.playfieldportal.feature.xmb.viewmodel.CustomIconSession
import com.playfieldportal.themekit.CustomizableIcons
import com.playfieldportal.themekit.IconSlot

/**
 * Live "Customize XMB Icons" editor, drawn OVER the real XMB (which keeps rendering — and
 * animating — behind it). Shape-for-shape the Adjust XMB Layout chrome: a light consuming
 * scrim, a bottom-anchored panel, D-pad control plus touch buttons.
 *
 * Edits apply IMMEDIATELY through the VM into CustomIconStore — there is deliberately no
 * Save/Cancel pair. Unlike layout adjust there is no coherent draft to discard (each pick is
 * independently complete), and Reset / Reset All are the undo. Don't "fix" this into a draft
 * model: the whole point of a live editor is that the XMB behind updates as each pick lands.
 *
 * The centre strip previews each slot THROUGH the real render pipeline — CustomIconSurface
 * with LocalIconAnimating provided for the focused slot — so what the user sees here (matte,
 * animation) is exactly what the XMB will draw.
 */
@Composable
fun CustomIconsOverlay(
    session: CustomIconSession,
    customIcons: Map<String, CustomIcon>,
    themeIcons: Map<String, CustomIcon>,
    onSlotFocused: (Int) -> Unit,
    onIconPicked: (String, android.net.Uri) -> Unit,
    onResetSlot: (String) -> Unit,
    onResetAll: () -> Unit,
    onSaveAsTheme: () -> Unit,
    onGroupMove: (Int) -> Unit,
    onSlotMove: (Int) -> Unit,
    onDone: () -> Unit,
    forwardedAction: GamepadAction? = null,
    onActionConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // SAF pick for the focused slot. OpenDocument returns a content URI we copy from
    // immediately — no persistence grant needed. Both control paths funnel here: the touch
    // Pick button and the pad's SELECT (via [forwardedAction], forwarded by the VM).
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        session.focusedSlot?.let { slot -> uri?.let { onIconPicked(slot.key, it) } }
    }
    val launchPicker = { picker.launch(PICK_MIME) }

    // Forwarded pad actions: SELECT = Pick, OPTIONS = Reset focused, BACK = Done.
    LaunchedEffect(forwardedAction) {
        when (forwardedAction) {
            GamepadAction.SELECT -> launchPicker()
            GamepadAction.OPEN_CONTEXT_MENU -> session.focusedSlot?.let { onResetSlot(it.key) }
            GamepadAction.BACK -> onDone()
            else -> Unit
        }
        if (forwardedAction != null) onActionConsumed()
    }

    val slots = remember(session.groupIndex) { CustomizableIcons.group(session.group) }
    val stripState = rememberLazyListState()
    LaunchedEffect(session.groupIndex, session.slotIndex) {
        if (session.slotIndex in slots.indices) {
            stripState.animateScrollToItem(session.slotIndex)
        }
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        // Consuming scrim: keeps the editor modal so taps above the panel never fall through
        // to the XMB rows behind it (the columns stay fully visible, only faintly dimmed).
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x22000000))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { /* swallow */ },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .background(Color(0xF20B1220), RoundedCornerShape(16.dp))
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Customize XMB Icons",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            // Group tabs (L/R on the pad).
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((index, group) in session.groups.withIndex()) {
                    val selected = index == session.groupIndex
                    Text(
                        text = groupLabel(group),
                        color = if (selected) Color.White else Color(0xFFB9C6DC),
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .background(
                                if (selected) Color(0xFF3A82F6) else Color.Transparent,
                                RoundedCornerShape(8.dp),
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onGroupMove(if (index > session.groupIndex) +1 else -1) }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }

            // The focused slot's current source, enlarged.
            val focused = slots.getOrNull(session.slotIndex)
            if (focused != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CompositionLocalProvider(LocalIconAnimating provides true) {
                        SlotPreview(
                            slot = focused,
                            icon = customIcons[focused.key] ?: themeIcons[focused.key],
                            modifier = Modifier.size(56.dp),
                        )
                    }
                    Column {
                        Text(focused.displayName, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = when {
                                customIcons.containsKey(focused.key) -> "Your pick"
                                themeIcons.containsKey(focused.key) -> "From theme"
                                else -> "Default"
                            },
                            color = Color(0xFFB9C6DC),
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            // The group's slots, rendered through the real pipeline. The focused one animates
            // (LocalIconAnimating=true) exactly as the XMB will draw it.
            LazyRow(
                state = stripState,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(slots, key = { it.key }, contentType = { "slot" }) { slot ->
                    val index = slots.indexOf(slot)
                    val selected = index == session.slotIndex
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .width(84.dp)
                            .background(
                                if (selected) Color(0x33203A5A) else Color.Transparent,
                                RoundedCornerShape(10.dp),
                            )
                            .border(
                                width = if (selected) 1.dp else 0.dp,
                                color = if (selected) Color(0xFF3A82F6) else Color.Transparent,
                                shape = RoundedCornerShape(10.dp),
                            )
                            .clickable { onSlotFocused(index) }
                            .padding(8.dp),
                    ) {
                        CompositionLocalProvider(LocalIconAnimating provides selected) {
                            SlotPreview(
                                slot = slot,
                                icon = customIcons[slot.key] ?: themeIcons[slot.key],
                                modifier = Modifier.size(40.dp),
                            )
                        }
                        Text(
                            text = slot.displayName,
                            color = if (selected) Color.White else Color(0xFFB9C6DC),
                            fontSize = 10.sp,
                            maxLines = 1,
                        )
                    }
                }
            }

            session.message?.let { message ->
                Text(text = message, color = Color(0xFFFFB4A2), fontSize = 12.sp)
            }

            // Controller hints.
            ControllerPromptBar(
                items = listOf(
                    ControllerPromptItem.fixed(ControllerIcon.DPAD_ALL, "Move"),
                    ControllerPromptItem(
                        listOf(GamepadAction.PREV_CATEGORY, GamepadAction.NEXT_CATEGORY),
                        "Group",
                    ),
                    ControllerPromptItem(GamepadAction.SELECT, "Pick"),
                    ControllerPromptItem(GamepadAction.OPEN_CONTEXT_MENU, "Reset"),
                    ControllerPromptItem(GamepadAction.BACK, "Done"),
                ),
                labelColor = Color(0x99B9C6DC),
                labelStyle = TextStyle(fontSize = 11.sp),
                glyphSize = 15.dp,
                arrangement = Arrangement.spacedBy(14.dp),
            )

            // Touch controls. Select launches the SAF picker for the focused slot; SELECT on
            // the pad is forwarded by the VM to the overlay's action consumer, which calls it.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = launchPicker,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A82F6)),
                ) { Text("Pick") }
                // Reset clears the USER tier only, so it can act only on a slot the user has
                // picked — greying it everywhere else is what stops "Reset" reading as broken
                // on a slot whose icon comes from the theme (or from the built-in set). The
                // pad's OPTIONS stays live and answers with a message instead, so the reason
                // is available on controller too.
                OutlinedButton(
                    onClick = { focused?.let { onResetSlot(it.key) } },
                    enabled = focused != null && customIcons.containsKey(focused.key),
                ) { Text("Reset") }
                OutlinedButton(onClick = onResetAll, enabled = customIcons.isNotEmpty()) { Text("Reset All") }
                OutlinedButton(onClick = onSaveAsTheme) { Text("Save as Theme…") }
                Box(Modifier.width(1.dp)) // spacer flex
                OutlinedButton(onClick = onDone) { Text("Done") }
            }
        }
    }
}

/** The picker's accepted set — the plan's still formats plus GIF. */
private val PICK_MIME = arrayOf(
    "image/png",
    "image/jpeg",
    "image/webp",
    "image/gif",
    "image/bmp",
    "image/heif",
)

private fun groupLabel(group: IconSlot.Group): String = when (group) {
    IconSlot.Group.CATEGORY_BAR -> "Category Bar"
    IconSlot.Group.ITEMS -> "Items"
    IconSlot.Group.STATUS -> "Status"
    IconSlot.Group.CONSOLE -> "Consoles"
}

/**
 * Draws a slot's current icon through the real pipeline: user pick > theme icon > the slot's
 * built-in glyph via [DefaultSlotGlyph]. Because the strip sits inside the shell's
 * CompositionLocalProvider tree, LocalIconLegibility and the theme's icon tint apply exactly
 * as on the XMB itself — so an untouched slot previews as the row it will replace, not as a
 * placeholder.
 */
@Composable
private fun SlotPreview(slot: IconSlot, icon: CustomIcon?, modifier: Modifier = Modifier) {
    if (icon != null) {
        CustomIconSurface(icon = icon, contentDescription = slot.displayName, modifier = modifier)
        return
    }
    if (DefaultSlotGlyph(slot = slot, contentDescription = slot.displayName, modifier = modifier)) return
    // Unreachable for a registered slot (DefaultSlotGlyphTest is the guard), but a slot added
    // without built-in art still gets a readable plate rather than an empty cell.
    Box(
        modifier = modifier
            .border(1.dp, Color(0x66B9C6DC), RoundedCornerShape(6.dp))
            .padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = slot.displayName.take(1), color = Color(0xFFB9C6DC), fontSize = 18.sp)
    }
}
