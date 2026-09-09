package com.playfieldportal.feature.xmb.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.ui.components.HsvColorPickerDialog
import com.playfieldportal.feature.xmb.viewmodel.ColorSchemeOption
import com.playfieldportal.feature.xmb.viewmodel.ColorSchemePickerState
import com.playfieldportal.feature.xmb.viewmodel.CustomColorPickerState

private val PickerWidth = 320.dp

private val PickerTextShadow = Shadow(
    color = Color.Black.copy(alpha = 0.75f),
    offset = Offset(0f, 2f),
    blurRadius = 4f,
)

@Composable
fun ColorSchemePickerOverlay(
    state: ColorSchemePickerState,
    onHighlightedAt: (Int) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(state.selectedIndex) {
        if (state.options.isNotEmpty()) {
            listState.animateScrollToItem(state.selectedIndex.coerceIn(0, state.options.lastIndex))
        }
    }

    val highlightSwatch = state.options.getOrNull(state.selectedIndex)?.swatch ?: 0xFF1B3A66
    val backdrop by animateColorAsState(
        targetValue = Color(highlightSwatch).copy(alpha = 0.70f),
        label = "colorSchemeBackdrop",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0x33000000))
            .clickable(onClick = onDismiss),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(PickerWidth)
                .background(backdrop)
                .clickable {}
                .padding(start = 28.dp, end = 36.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Color Scheme",
                fontSize = 19.sp,
                fontWeight = FontWeight.Light,
                color = Color.White.copy(alpha = 0.92f),
                style = TextStyle(shadow = PickerTextShadow),
                modifier = Modifier.padding(bottom = 10.dp),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(end = 8.dp)
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.30f)),
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                itemsIndexed(state.options) { index, option ->
                    ColorSchemeRow(
                        option = option,
                        isSelected = index == state.selectedIndex,
                        onClick = { if (index == state.selectedIndex) onConfirm() else onHighlightedAt(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorSchemeRow(
    option: ColorSchemeOption,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) {
                    Brush.horizontalGradient(
                        0f to Color.Transparent,
                        1f to Color.White.copy(alpha = 0.22f),
                    )
                } else {
                    Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Transparent)
                },
            )
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color(option.swatch)),
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = option.label,
                    fontSize = if (isSelected) 16.sp else 15.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) Color.White else Color.White.copy(alpha = 0.66f),
                    style = TextStyle(shadow = PickerTextShadow),
                )
                Text(
                    text = option.sublabel,
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = if (isSelected) 0.78f else 0.5f),
                    style = TextStyle(shadow = PickerTextShadow),
                )
            }
        }
    }
}

/**
 * Thin adapter onto the shared [HsvColorPickerDialog]. This file used to carry its own copy of the
 * whole 440dp panel; only the state shape is XMB-specific now.
 */
@Composable
fun CustomColorPickerOverlay(
    state: CustomColorPickerState,
    onChannelFraction: (Int, Float) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        HsvColorPickerDialog(
            title = "Custom Color",
            hue = state.hue,
            saturation = state.saturation,
            brightness = state.brightness,
            selectedChannel = state.selectedChannel,
            onChannelFraction = onChannelFraction,
            onConfirm = onConfirm,
            onCancel = onCancel,
        )
    }
}
