package com.playfieldportal.feature.xmb.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

@Composable
fun CustomColorPickerOverlay(
    state: CustomColorPickerState,
    onChannelFraction: (Int, Float) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val preview = Color(android.graphics.Color.HSVToColor(floatArrayOf(state.hue, state.saturation, state.brightness)))
    Box(
        modifier = modifier.fillMaxSize().background(Color(0xCC000000)).clickable(onClick = onCancel),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(440.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF15151F)).clickable {}.padding(24.dp),
        ) {
            Text("Custom Color", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(preview).border(1.dp, Color(0x66FFFFFF), CircleShape))
                Spacer(Modifier.width(16.dp))
                Text(String.format("#%06X", 0xFFFFFF and preview.toArgb()), color = Color.White.copy(alpha = .7f), fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(20.dp))
            PickerChannelBar(0, "Hue", state.hue / 360f, rainbowBrush(), state.selectedChannel == 0, onChannelFraction)
            Spacer(Modifier.height(14.dp))
            PickerChannelBar(1, "Saturation", state.saturation, Brush.horizontalGradient(listOf(hsvColor(state.hue, 0f, state.brightness), hsvColor(state.hue, 1f, state.brightness))), state.selectedChannel == 1, onChannelFraction)
            Spacer(Modifier.height(14.dp))
            PickerChannelBar(2, "Brightness", state.brightness, Brush.horizontalGradient(listOf(Color.Black, Color.White)), state.selectedChannel == 2, onChannelFraction)
            Spacer(Modifier.height(20.dp))
            Text("◄ ► adjust    ▲ ▼ channel    Ⓐ apply    Ⓑ cancel", color = Color.White.copy(alpha = .7f), fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Apply", color = Color.White, modifier = Modifier.clickable(onClick = onConfirm).padding(8.dp))
                Text("Cancel", color = Color.White.copy(alpha = .7f), modifier = Modifier.clickable(onClick = onCancel).padding(8.dp))
            }
        }
    }
}

private fun rainbowBrush(): Brush = Brush.horizontalGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red))
private fun hsvColor(hue: Float, saturation: Float, brightness: Float): Color = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness)))

@Composable
private fun PickerChannelBar(
    channel: Int,
    label: String,
    fraction: Float,
    brush: Brush,
    selected: Boolean,
    onFraction: (Int, Float) -> Unit,
) {
    Text(label, color = if (selected) Color.White else Color.White.copy(alpha = .7f), fontSize = 12.sp)
    Spacer(Modifier.height(6.dp))
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(brush)
            .border(if (selected) 2.dp else 1.dp, if (selected) Color.White else Color.White.copy(alpha = .35f), RoundedCornerShape(14.dp))
            .pointerInput(Unit) { detectTapGestures { position -> onFraction(channel, (position.x / size.width).coerceIn(0f, 1f)) } },
    ) {
        Box(
            Modifier
                .offset(x = maxWidth * fraction.coerceIn(0f, 1f) - 9.dp)
                .align(Alignment.CenterStart)
                .size(18.dp)
                .clip(CircleShape)
                .background(Color.White)
                .border(2.dp, Color.Black, CircleShape),
        )
    }
}
