package com.playfieldportal.feature.xmb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.theme.menuCursor

@Composable
fun GamePickerScreen(
    onConfirm: (selectedGameIds: Set<Long>, selectedCollectionIds: Set<Long>) -> Unit,
    onCancel: () -> Unit,
    pendingGamepadAction: GamepadAction? = null,
    onGamepadActionConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: GamePickerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()

    // Confirm/cancel both clear the picker — the ViewModel is retained across open/close, so
    // selections must not carry over to the next time the picker is opened.
    val confirmAndClear: () -> Unit = {
        val (gameIds, collectionIds) = viewModel.getSelectedItems()
        onConfirm(gameIds, collectionIds)
        viewModel.clearSelection()
    }
    val cancelAndClear: () -> Unit = {
        viewModel.clearSelection()
        onCancel()
    }

    // Scroll to keep selected item visible
    LaunchedEffect(state.selectedItemId) {
        if (state.selectedItemId != null) {
            val itemIds = buildPickerItemIds(state)
            val index = itemIds.indexOf(state.selectedItemId)
            if (index >= 0) {
                listState.animateScrollToItem(
                    index = index,
                    scrollOffset = -80,
                )
            }
        }
    }

    // Handle gamepad input
    LaunchedEffect(pendingGamepadAction) {
        if (pendingGamepadAction != null) {
            when (pendingGamepadAction) {
                GamepadAction.NAVIGATE_UP -> viewModel.moveSelection(-1)
                GamepadAction.NAVIGATE_DOWN -> viewModel.moveSelection(+1)
                GamepadAction.SELECT -> viewModel.activateSelection()
                GamepadAction.BUTTON_Y -> viewModel.toggleSelectedPlatform()
                GamepadAction.BACK -> cancelAndClear()
                GamepadAction.HOME -> confirmAndClear()
                else -> {} // Other actions handled by parent
            }
            onGamepadActionConsumed()
        }
    }

    if (state.isLoading) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val pfpColors = com.playfieldportal.core.ui.theme.LocalPFPColors.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to pfpColors.backgroundTop.copy(alpha = 0.94f),
                    1f to pfpColors.backgroundBottom.copy(alpha = 0.94f),
                )
            )
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Add Games to Category",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
        }

        Text(
            text = "${state.selectedGameIds.size + state.selectedCollectionIds.size} selected  ·  A to toggle  ·  Y to expand  ·  Start to add  ·  B to cancel",
            fontSize = 12.sp,
            color = Color(0xFFC9C7E8),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 0.dp),
        )

        // Content
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
        ) {
            // Platform groups - use identity-based selection
            for ((groupIndex, group) in state.platformGroups.withIndex()) {
                val platformId = group.platform.platformId

                item {
                    PlatformGroupHeader(
                        group = group,
                        isExpanded = state.platformExpandedStates[platformId] ?: true,
                        isSelected = pickerPlatformId(platformId) == state.selectedItemId,
                        onToggleExpanded = { viewModel.togglePlatformExpanded(platformId) },
                        onToggleSelectAll = { selectAll ->
                            viewModel.togglePlatformAllSelection(platformId, selectAll)
                        },
                    )
                }

                // Games in this platform
                if (state.platformExpandedStates[platformId] == true) {
                    items(group.games) { game ->
                        GamePickerRow(
                            title = game.displayTitle,
                            isSelected = pickerGameId(game.id) == state.selectedItemId,
                            isChecked = game.id in state.selectedGameIds,
                            onToggle = { viewModel.toggleGameSelection(game.id) },
                            modifier = Modifier.padding(start = 48.dp),
                        )
                    }
                }
            }

            // Collections section
            if (state.pcShortcuts.isNotEmpty()) {
                item {
                    Text(
                        text = "Collections",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
                            .background(
                                if (PICKER_COLLECTIONS_HEADER == state.selectedItemId)
                                    Color(0xFF574DDB).copy(alpha = 0.2f)
                                else
                                    Color.Transparent
                            ),
                    )
                }

                items(state.pcShortcuts) { collection ->
                    GamePickerRow(
                        title = collection.name,
                        isSelected = pickerCollectionId(collection.id) == state.selectedItemId,
                        isChecked = collection.id in state.selectedCollectionIds,
                        onToggle = { viewModel.toggleCollectionSelection(collection.id) },
                        modifier = Modifier.padding(start = 32.dp),
                    )
                }
            }

        }

        // Bottom buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            TextButton(
                onClick = cancelAndClear,
                modifier = Modifier.weight(1f),
            ) {
                Text("Cancel", color = Color.White)
            }

            TextButton(
                onClick = confirmAndClear,
                modifier = Modifier.weight(1f),
            ) {
                Text("Add (${state.selectedGameIds.size + state.selectedCollectionIds.size})", color = Color.White)
            }
        }
    }
}

@Composable
private fun PlatformGroupHeader(
    group: PlatformGameGroup,
    isExpanded: Boolean,
    isSelected: Boolean,
    onToggleExpanded: () -> Unit,
    onToggleSelectAll: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .menuCursor(isSelected),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = group.isAllSelected || group.selectedCount > 0,
            onCheckedChange = { selectAll -> onToggleSelectAll(selectAll) },
        )

        Text(
            text = group.platform.displayName,
            fontSize = if (isSelected) 15.sp else 14.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.8f),
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        )

        Text(
            text = "${group.selectedCount}/${group.games.size}",
            fontSize = 12.sp,
            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.7f),
            modifier = Modifier.padding(end = 8.dp),
        )

        TextButton(onClick = onToggleExpanded) {
            Text(if (isExpanded) "▼" else "▶", fontSize = 12.sp, color = Color.White)
        }
    }
}

@Composable
private fun GamePickerRow(
    title: String,
    isSelected: Boolean,
    isChecked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(end = 16.dp, top = 4.dp, bottom = 4.dp)
            .menuCursor(isSelected),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = isChecked,
            onCheckedChange = { onToggle() },
        )
        Text(
            text = title,
            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.8f),
            fontSize = if (isSelected) 14.sp else 13.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        )
    }
}
