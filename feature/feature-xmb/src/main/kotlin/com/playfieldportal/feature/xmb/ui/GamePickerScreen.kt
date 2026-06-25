package com.playfieldportal.feature.xmb.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun GamePickerScreen(
    onConfirm: (selectedGameIds: Set<Long>, selectedCollectionIds: Set<Long>) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GamePickerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    if (state.isLoading) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
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
                modifier = Modifier.weight(1f),
            )
        }

        // Content
        LazyColumn(modifier = Modifier.weight(1f)) {
            // Platform groups
            items(state.platformGroups) { group ->
                PlatformGroupItem(
                    group = group,
                    isExpanded = state.platformExpandedStates[group.platform.platformId] ?: true,
                    onToggleExpanded = { viewModel.togglePlatformExpanded(group.platform.platformId) },
                    onToggleSelectAll = { selectAll ->
                        viewModel.togglePlatformAllSelection(group.platform.platformId, selectAll)
                    },
                    onGameToggled = { gameId ->
                        viewModel.toggleGameSelection(gameId)
                    },
                    selectedGameIds = state.selectedGameIds,
                )
            }

            // PC Shortcuts section
            if (state.pcShortcuts.isNotEmpty()) {
                item {
                    Text(
                        text = "PC Shortcuts",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                    )
                }

                items(state.pcShortcuts) { collection ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 32.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = collection.id in state.selectedCollectionIds,
                            onCheckedChange = { viewModel.toggleCollectionSelection(collection.id) },
                        )
                        Text(
                            text = collection.name,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp),
                        )
                    }
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
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            ) {
                Text("Cancel")
            }

            TextButton(
                onClick = {
                    val (gameIds, collectionIds) = viewModel.getSelectedItems()
                    onConfirm(gameIds, collectionIds)
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("Add (${state.selectedGameIds.size + state.selectedCollectionIds.size})")
            }
        }
    }
}

@Composable
private fun PlatformGroupItem(
    group: PlatformGameGroup,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onToggleSelectAll: (Boolean) -> Unit,
    onGameToggled: (Long) -> Unit,
    selectedGameIds: Set<Long>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // Platform header with select all toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = group.isAllSelected || group.selectedCount > 0,
                onCheckedChange = { selectAll -> onToggleSelectAll(selectAll) },
            )

            Text(
                text = group.platform.displayName,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )

            Text(
                text = "${group.selectedCount}/${group.games.size}",
                fontSize = 12.sp,
                modifier = Modifier.padding(end = 8.dp),
            )

            TextButton(onClick = onToggleExpanded) {
                Text(if (isExpanded) "▼" else "▶", fontSize = 12.sp)
            }
        }

        // Expanded game list
        if (isExpanded && group.games.isNotEmpty()) {
            Column {
                group.games.forEach { game ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 48.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = game.id in selectedGameIds,
                            onCheckedChange = { onGameToggled(game.id) },
                        )
                        Text(
                            text = game.displayTitle,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp),
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }
    }
}
