package com.playfieldportal.feature.xmb.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.domain.model.ControllerIcon
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.components.ControllerPromptBar
import com.playfieldportal.core.ui.components.ControllerPromptItem
import com.playfieldportal.core.ui.icons.CategoryIconGlyph
import com.playfieldportal.core.ui.icons.CustomIcon
import com.playfieldportal.core.ui.icons.CustomIconSurface
import com.playfieldportal.core.ui.theme.SECONDARY_TEXT_WEIGHT
import com.playfieldportal.core.ui.theme.themedSubText
import com.playfieldportal.core.ui.theme.themedText
import com.playfieldportal.feature.xmb.viewmodel.CustomIconSession
import com.playfieldportal.feature.xmb.viewmodel.UserCategoryIconSlot
import com.playfieldportal.themekit.IconColumnRun
import com.playfieldportal.themekit.IconSlot

/**
 * Live "Customize XMB Icons" editor, drawn OVER the real XMB (which keeps rendering — and
 * animating — behind it). Shape-for-shape the Adjust XMB Layout chrome: a light consuming
 * scrim, a bottom-anchored panel, D-pad control or touch buttons — one family on screen at a time
 * ([showTouchControls]), each reaching every command ([customIconsPadCommand]). Any finger on the
 * editor reports [onTouchInput], so AUTO touch mode brings the buttons up.
 *
 * Edits apply IMMEDIATELY through the VM into CustomIconStore — there is deliberately no
 * Save/Cancel pair. Unlike layout adjust there is no coherent draft to discard (each pick is
 * independently complete), and Reset / Reset All are the undo. Don't "fix" this into a draft
 * model: the whole point of a live editor is that the XMB behind updates as each pick lands.
 *
 * The centre strip previews each slot THROUGH the real render pipeline — CustomIconSurface
 * with LocalIconFocused provided for the focused slot — so what the user sees here (matte,
 * animation) is exactly what the XMB will draw.
 */
@Composable
fun CustomIconsOverlay(
    session: CustomIconSession,
    icons: com.playfieldportal.core.ui.icons.XmbIcons,
    onSlotFocused: (Int) -> Unit,
    onIconPicked: (String, android.net.Uri) -> Unit,
    onResetSlot: (String) -> Unit,
    onResetAll: () -> Unit,
    onSaveAsTheme: () -> Unit,
    onTabMove: (Int) -> Unit,
    onDone: () -> Unit,
    forwardedAction: GamepadAction? = null,
    onActionConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
    showTouchControls: Boolean = false,
    onTouchInput: () -> Unit = {},
) {
    // SAF pick for the focused slot. OpenDocument returns a content URI we copy from
    // immediately — no persistence grant needed. Both control paths funnel here: the touch
    // Pick button and the pad's SELECT (via [forwardedAction], forwarded by the VM).
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        session.focusedSlot?.let { slot -> uri?.let { onIconPicked(slot.key, it) } }
    }
    val launchPicker = { picker.launch(PICK_MIME) }

    // Forwarded pad actions, one per touch button (see customIconsPadCommand).
    LaunchedEffect(forwardedAction) {
        when (forwardedAction?.let(::customIconsPadCommand)) {
            CustomIconsCommand.PICK -> launchPicker()
            CustomIconsCommand.RESET_SLOT -> session.focusedSlot?.let { onResetSlot(it.key) }
            // The touch button greys out with nothing to reset; the pad just does nothing.
            CustomIconsCommand.RESET_ALL -> if (icons.userKeys.isNotEmpty()) onResetAll()
            CustomIconsCommand.SAVE_AS_THEME -> onSaveAsTheme()
            CustomIconsCommand.DONE -> onDone()
            null -> Unit
        }
        if (forwardedAction != null) onActionConsumed()
    }
    val currentOnTouchInput by rememberUpdatedState(onTouchInput)

    val slots = remember(session.tabIndex, session.barKeys, session.userCategorySlots) { session.slots() }
    // The Items tab's XMB columns, each captioned over its run of the strip.
    val runs = remember(session.tabIndex, session.barKeys) { session.runs() }
    val runOfIndex = remember(runs) { runs.flatMapIndexed { r, run -> List(run.slots.size) { r } } }
    // A user slot with no image previews as the bar draws it: the category's catalog glyph.
    val userSlots = remember(session.userCategorySlots) { session.userCategorySlots.associateBy { it.key } }
    val stripState = rememberLazyListState()
    LaunchedEffect(session.tabIndex, session.slotIndex) {
        if (session.slotIndex in slots.indices) {
            stripState.animateScrollToItem(session.slotIndex)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // requireUnconsumed = false: the scrim, strip and buttons consume their own taps.
            .pointerInput(Unit) {
                awaitEachGesture { awaitFirstDown(requireUnconsumed = false); currentOnTouchInput() }
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
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
                color = themedText(Color.White),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            // Tabs (L1/R1 on the pad), shared with the Theme Studio's picker.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((index, tab) in session.tabs.withIndex()) {
                    val selected = index == session.tabIndex
                    Text(
                        text = tab.label,
                        // The selected tab sits on its solid blue pill and keeps white; the rest
                        // are Main text dimmed (#B9C6DC → the font colour at 0.72).
                        color = if (selected) Color.White else themedText(Color(0xFFB9C6DC), SECONDARY_TEXT_WEIGHT),
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
                            ) { if (index != session.tabIndex) onTabMove(index - session.tabIndex) }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }

            // The focused slot's current source, enlarged.
            val focused = slots.getOrNull(session.slotIndex)
            if (focused != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CompositionLocalProvider(com.playfieldportal.core.ui.motion.LocalIconFocused provides true) {
                        SlotPreview(
                            slot = focused,
                            icon = icons[focused.key],
                            userSlot = userSlots[focused.key],
                            modifier = Modifier.size(56.dp),
                        )
                    }
                    Column {
                        Text(focused.displayName, color = themedText(Color.White), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        val source = when (icons.tierOf(focused.key)) {
                            com.playfieldportal.core.ui.icons.IconTier.USER -> "Your pick"
                            com.playfieldportal.core.ui.icons.IconTier.THEME -> "From theme"
                            null -> "Default"
                        }
                        Text(
                            text = runs.getOrNull(session.focusedRunIndex)?.let { "$source · ${it.label} column" } ?: source,
                            color = themedSubText(Color(0xFFB9C6DC), SECONDARY_TEXT_WEIGHT),
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            // The group's slots, rendered through the real pipeline. The focused one animates
            // (LocalIconFocused=true) exactly as the XMB will draw it, under Animated Images.
            LazyRow(
                state = stripState,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(slots, key = { it.key }, contentType = { "slot" }) { slot ->
                    val index = slots.indexOf(slot)
                    val selected = index == session.slotIndex
                    Column {
                        if (runs.isNotEmpty()) {
                            val run = runOfIndex.getOrNull(index)
                            ColumnCaption(
                                label = run?.takeIf { index == 0 || runOfIndex.getOrNull(index - 1) != it }?.let { runs[it] },
                                active = run == session.focusedRunIndex,
                                continuesRight = run != null && runOfIndex.getOrNull(index + 1) == run,
                            )
                        }
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
                            CompositionLocalProvider(com.playfieldportal.core.ui.motion.LocalIconFocused provides selected) {
                                SlotPreview(
                                    slot = slot,
                                    icon = icons[slot.key],
                                    userSlot = userSlots[slot.key],
                                    modifier = Modifier.size(40.dp),
                                )
                            }
                            Text(
                                text = slot.displayName,
                                color = themedText(if (selected) Color.White else Color(0xFFB9C6DC), if (selected) 1f else SECONDARY_TEXT_WEIGHT),
                                fontSize = 10.sp,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            session.message?.let { message ->
                Text(text = message, color = Color(0xFFFFB4A2), fontSize = 12.sp)
            }

            // Controller hints.
            if (!showTouchControls) ControllerPromptBar(
                items = listOfNotNull(
                    if (runs.isEmpty()) {
                        ControllerPromptItem.fixed(ControllerIcon.DPAD_ALL, "Move")
                    } else {
                        ControllerPromptItem.fixed(listOf(ControllerIcon.DPAD_LEFT, ControllerIcon.DPAD_RIGHT), "Move")
                    },
                    runs.takeIf { it.isNotEmpty() }?.let {
                        ControllerPromptItem.fixed(listOf(ControllerIcon.DPAD_UP, ControllerIcon.DPAD_DOWN), "Column")
                    },
                    ControllerPromptItem(
                        listOf(GamepadAction.PREV_CATEGORY, GamepadAction.NEXT_CATEGORY),
                        "Tab",
                    ),
                    ControllerPromptItem(GamepadAction.SELECT, "Pick"),
                    ControllerPromptItem(GamepadAction.OPEN_CONTEXT_MENU, "Reset"),
                    ControllerPromptItem(GamepadAction.CHANGE_SORT, "Reset All"),
                    ControllerPromptItem(GamepadAction.HOME, "Save as Theme"),
                    ControllerPromptItem(GamepadAction.BACK, "Done"),
                ),
                labelColor = themedSubText(Color(0x99B9C6DC)),
                labelStyle = TextStyle(fontSize = 11.sp),
                glyphSize = 15.dp,
                arrangement = Arrangement.spacedBy(14.dp),
            )

            // Touch controls. Select launches the SAF picker for the focused slot; SELECT on
            // the pad is forwarded by the VM to the overlay's action consumer, which calls it.
            if (showTouchControls) Row(
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
                    enabled = focused != null && icons.tierOf(focused.key) == com.playfieldportal.core.ui.icons.IconTier.USER,
                ) { Text("Reset") }
                OutlinedButton(onClick = onResetAll, enabled = icons.userKeys.isNotEmpty()) { Text("Reset All") }
                OutlinedButton(onClick = onSaveAsTheme) { Text("Save as Theme…") }
                Box(Modifier.width(1.dp)) // spacer flex
                OutlinedButton(onClick = onDone) { Text("Done") }
            }
        }
    }
}

/** What a forwarded pad press does in the editor: one command per touch button. */
internal enum class CustomIconsCommand { PICK, RESET_SLOT, RESET_ALL, SAVE_AS_THEME, DONE }

/**
 * The command a pad [action] runs, or null for the presses the view model handles itself (slot
 * cursor and tabs). SELECT picks, △ resets the focused slot, □ resets all, START saves the set as a
 * theme, ○ is done — so a pad reaches every touch button, and touch mode can hide the prompts.
 */
internal fun customIconsPadCommand(action: GamepadAction): CustomIconsCommand? = when (action) {
    GamepadAction.SELECT -> CustomIconsCommand.PICK
    GamepadAction.OPEN_CONTEXT_MENU -> CustomIconsCommand.RESET_SLOT
    GamepadAction.CHANGE_SORT -> CustomIconsCommand.RESET_ALL
    GamepadAction.HOME -> CustomIconsCommand.SAVE_AS_THEME
    GamepadAction.BACK -> CustomIconsCommand.DONE
    else -> null
}

/** The Items tab's column caption line: the label's line box and the row it sits in, as one number. */
private val CAPTION_LINE = 14.sp

/** The picker's accepted set — the plan's still formats plus GIF. */
private val PICK_MIME = arrayOf(
    "image/png",
    "image/jpeg",
    "image/webp",
    "image/gif",
    "image/bmp",
    "image/heif",
    "image/heic",
)

/**
 * One strip cell's slice of the Items tab's column caption: the first cell of a run carries the
 * column's name (it runs on over the run's other cells), and every cell draws its share of the
 * underline, bridging the gap to the next cell of the same run. The focused slot's column is
 * white over a blue line; the rest are muted.
 */
@Composable
private fun ColumnCaption(label: IconColumnRun?, active: Boolean, continuesRight: Boolean) {
    val cellWidth = 84.dp
    val gap = 10.dp
    Column(
        modifier = Modifier.width(cellWidth).padding(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        // The caption row is the same height in every cell (so the underlines line up across
        // cells that carry no name), and the label's line box is pinned to exactly that height:
        // left to its default, Android's font padding makes a 10 sp line taller than 14 dp and the
        // underline row clips the bottom of the letters.
        val captionHeight = with(LocalDensity.current) { CAPTION_LINE.toDp() }
        Box(Modifier.fillMaxWidth().height(captionHeight)) {
            if (label != null) {
                val runWidth = cellWidth * label.slots.size + gap * (label.slots.size - 1)
                Text(
                    text = label.label.uppercase(),
                    color = if (active) Color.White else Color(0xFF7E8CA6),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    lineHeight = CAPTION_LINE,
                    style = TextStyle(
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .wrapContentWidth(Alignment.Start, unbounded = true)
                        .width(runWidth),
                )
            }
        }
        Box(
            Modifier
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .width(if (continuesRight) cellWidth + gap else cellWidth)
                .height(2.dp)
                .background(if (active) Color(0xFF3A82F6) else Color(0xFF2A3346), RoundedCornerShape(1.dp)),
        )
    }
}

/**
 * Draws a slot's current icon through the real pipeline: user pick > theme icon > the slot's
 * built-in glyph via [DefaultSlotGlyph]. Because the strip sits inside the shell's
 * CompositionLocalProvider tree, LocalIconLegibility and the theme's icon tint apply exactly
 * as on the XMB itself — so an untouched slot previews as the row it will replace, not as a
 * placeholder.
 */
@Composable
private fun SlotPreview(
    slot: IconSlot,
    icon: CustomIcon?,
    userSlot: UserCategoryIconSlot? = null,
    modifier: Modifier = Modifier,
) {
    if (icon != null) {
        CustomIconSurface(icon = icon, contentDescription = slot.displayName, modifier = modifier)
        return
    }
    if (userSlot != null) {
        CategoryIconGlyph(
            iconKey = userSlot.iconKey,
            contentDescription = slot.displayName,
            modifier = modifier,
            categoryId = userSlot.categoryId,
        )
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
