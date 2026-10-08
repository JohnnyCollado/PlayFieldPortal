package com.playfieldportal.feature.xmb.ui

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.TouchGesture
import com.playfieldportal.core.ui.components.ControllerPromptBar
import com.playfieldportal.core.ui.components.ControllerPromptItem
import com.playfieldportal.core.ui.components.TouchPromptBar
import com.playfieldportal.core.ui.components.TouchPromptItem
import com.playfieldportal.core.ui.components.XmbHeaderPill
import com.playfieldportal.core.ui.icons.CustomIcon
import com.playfieldportal.core.ui.icons.CustomIconSurface
import com.playfieldportal.core.ui.motion.LocalIconFocused
import com.playfieldportal.core.ui.theme.LocalPFPColors
import com.playfieldportal.core.ui.theme.LocalPfpTextColors
import com.playfieldportal.core.ui.theme.menuCursorEdge
import com.playfieldportal.core.ui.theme.menuCursorFill
import com.playfieldportal.feature.xmb.viewmodel.THEME_ICON_GRID_COLUMNS
import com.playfieldportal.feature.xmb.viewmodel.ThemeIconGridState

/**
 * The applied theme's icons as a grid, drawn over the Customize XMB Icons editor, for choosing one
 * to copy onto [ThemeIconGridState.slotKey]. The tiles are drawn through [CustomIconSurface], like
 * the editor's own strip. The view model owns the cursor ([ThemeIconGridState.cursor]); one input
 * family is on screen at a time ([showTouchControls]): the pad's footer with the focused tile
 * ringed, or the Cancel pill, a touch prompt and tappable tiles. Any finger reports [onTouchInput].
 */
@Composable
fun ThemeIconGridOverlay(
    grid: ThemeIconGridState,
    onChoose: (Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    showTouchControls: Boolean = false,
    onTouchInput: () -> Unit = {},
) {
    val currentOnTouchInput by rememberUpdatedState(onTouchInput)
    val pfpColors = LocalPFPColors.current
    val textColors = LocalPfpTextColors.current

    Box(
        modifier = modifier
            .fillMaxSize()
            // Any finger reports touch input. requireUnconsumed = false so a tile's own tap still reports.
            .pointerInput(Unit) {
                awaitEachGesture { awaitFirstDown(requireUnconsumed = false); currentOnTouchInput() }
            },
    ) {
        // Consuming backdrop, a sibling of the content so it does not merge the content's
        // semantics: nothing falls through to the editor underneath.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to pfpColors.backgroundTop.copy(alpha = 0.96f),
                        1f to pfpColors.backgroundBottom.copy(alpha = 0.96f),
                    )
                )
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
        )
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = grid.title,
                    color = textColors.primary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Light,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (showTouchControls) {
                    XmbHeaderPill(
                        label = "Cancel",
                        leadingGlyph = "◀",
                        onClick = onBack,
                        modifier = Modifier.testTag(ThemeIconGridTags.CANCEL),
                    )
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(THEME_ICON_GRID_COLUMNS),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp),
            ) {
                var flat = 0
                for (section in grid.sections) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "section:${section.title}") {
                        Text(
                            text = section.title,
                            color = textColors.secondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    for (choice in section.choices) {
                        val index = flat++
                        item(key = "tile:$index") {
                            IconTile(
                                label = choice.label,
                                icon = grid.icons[index],
                                focused = !showTouchControls && index == grid.cursor,
                                modifier = Modifier.testTag(ThemeIconGridTags.tile(index)).clickable { onChoose(index) },
                            )
                        }
                    }
                }
            }

            // One input family at a time (ARCHITECTURE.md, Conventions). The pad's footer reads
            // A Use this icon, then B Back (the repo's standard order); touch has the Cancel pill
            // above, so its prompt carries only the tap.
            if (showTouchControls) {
                TouchPromptBar(
                    items = listOf(TouchPromptItem(TouchGesture.TAP, "Use this icon")),
                    labelColor = textColors.secondary,
                    labelStyle = TextStyle(fontSize = 12.sp),
                    glyphSize = 16.dp,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag(ThemeIconGridTags.TOUCH_PROMPTS),
                )
            } else {
                ControllerPromptBar(
                    items = listOf(
                        ControllerPromptItem(GamepadAction.SELECT, "Use this icon"),
                        ControllerPromptItem(GamepadAction.BACK, "Back"),
                    ),
                    labelColor = textColors.secondary,
                    labelStyle = TextStyle(fontSize = 12.sp),
                    glyphSize = 16.dp,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag(ThemeIconGridTags.PROMPTS),
                )
            }
        }
    }
}

/**
 * One tile: the icon through the real render pipeline, with its label. The focused tile frames
 * itself by geometry ([BringIntoViewRequester]) rather than scroll arithmetic, this app's
 * convention for controller focus, and is the one a GIF animates on (as in the editor's strip).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IconTile(
    label: String,
    icon: CustomIcon,
    focused: Boolean,
    modifier: Modifier = Modifier,
) {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(focused) { if (focused) requester.bringIntoView() }
    val shape = RoundedCornerShape(10.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester)
            .clip(shape)
            .background(if (focused) menuCursorFill() else Color.Transparent)
            .then(if (focused) Modifier.border(1.dp, menuCursorEdge(), shape) else Modifier)
            .padding(8.dp),
    ) {
        CompositionLocalProvider(LocalIconFocused provides focused) {
            CustomIconSurface(icon = icon, contentDescription = label, modifier = Modifier.size(56.dp))
        }
        Text(
            text = label,
            color = LocalPfpTextColors.current.primary,
            fontSize = 11.sp,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Test tags for the grid's input-family split and its tiles. */
object ThemeIconGridTags {
    const val CANCEL = "themeIconGrid:cancel"
    const val PROMPTS = "themeIconGrid:prompts"
    const val TOUCH_PROMPTS = "themeIconGrid:touchPrompts"
    fun tile(index: Int) = "themeIconGrid:tile:$index"
}
