package com.playfieldportal.feature.xmb.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import androidx.compose.ui.platform.LocalContext
import com.playfieldportal.feature.xmb.viewmodel.AppPickerState

private val PickerScrim = Color(0xF00A0A14)
private val PickerText = Color.White
private val PickerSubtext = Color(0xFFC9C7E8)
private val PickerCheck = Color(0xFF7CE5A2)

// Reusable multi-select installed-app picker. Used by the Android Library ("Find Games")
// and by app sections like Video / Music ("Add Apps"). Selection/commit is driven entirely
// by the ViewModel so controller and touch behave identically.
@Composable
fun InstalledAppPicker(
    state: AppPickerState,
    onActivateAt: (Int) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.selectedIndex) {
        listState.animateScrollToItem(state.selectedIndex.coerceAtLeast(0))
    }

    val pfpColors = com.playfieldportal.core.ui.theme.LocalPFPColors.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to pfpColors.backgroundTop.copy(alpha = 0.94f),
                    1f to pfpColors.backgroundBottom.copy(alpha = 0.94f),
                )
            )
            .clickable(onClick = onDismiss),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp)) {
            Text(state.title, color = PickerText, fontSize = 22.sp, fontWeight = FontWeight.Light)
            Text(
                "${state.selected.size} selected  ·  A to toggle  ·  Start to add  ·  B to cancel",
                color = PickerSubtext,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
            )

            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                // Index 0 = Confirm row.
                item {
                    PickerRow(
                        selected = state.selectedIndex == 0,
                        modifier = Modifier.clickable { onConfirm() },
                    ) {
                        Text(
                            text = if (state.selected.isEmpty()) "Done" else "Add ${state.selected.size} app(s)",
                            color = PickerText,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                itemsIndexed(state.apps, key = { _, app -> app.packageName }) { index, app ->
                    val rowIndex = index + 1
                    val checked = app.packageName in state.selected
                    PickerRow(
                        selected = state.selectedIndex == rowIndex,
                        modifier = Modifier.clickable { onActivateAt(rowIndex) },
                    ) {
                        PickerAppIcon(app.packageName)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = app.label,
                            color = PickerText,
                            fontSize = 15.sp,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = if (checked) "✓" else "",
                            color = PickerCheck,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(
    selected: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) com.playfieldportal.core.ui.theme.menuCursorFill() else Color.Transparent)
            .then(
                if (selected) Modifier.border(1.dp, com.playfieldportal.core.ui.theme.menuCursorEdge(), RoundedCornerShape(7.dp))
                else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        content = content,
    )
}

@Composable
private fun PickerAppIcon(packageName: String) {
    val context = LocalContext.current
    val drawable = remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
    }
    if (drawable != null) {
        Image(
            painter = rememberDrawablePainter(drawable),
            contentDescription = null,
            modifier = Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)),
        )
    } else {
        Spacer(Modifier.size(30.dp))
    }
}
