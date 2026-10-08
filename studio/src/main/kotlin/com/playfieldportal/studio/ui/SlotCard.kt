package com.playfieldportal.studio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.studio.IconColorChoice
import com.playfieldportal.studio.IconPicker
import com.playfieldportal.studio.SlotCardModel
import com.playfieldportal.studio.StudioState
import com.playfieldportal.studio.preview.StudioIconSet
import com.playfieldportal.studio.ui.sections.MutedText
import com.playfieldportal.studio.ui.sections.SectionHeading
import com.playfieldportal.themekit.IconSlot
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image as SkiaImage
import androidx.compose.ui.graphics.toComposeImageBitmap

/** Alpha-checkerboard backdrop so transparent icon regions are visible. */
@Composable
fun Checkerboard(sizeDp: Int) {
    Canvas(Modifier.size(sizeDp.dp)) {
        val cell = 8f
        val cols = (size.width / cell).toInt() + 1
        val rows = (size.height / cell).toInt() + 1
        for (r in 0 until rows) for (c in 0 until cols) {
            drawRect(
                color = if ((r + c) % 2 == 0) Color(0xFF3A3A3A) else Color(0xFF2C2C2C),
                topLeft = Offset(c * cell, r * cell),
                size = Size(cell, cell),
            )
        }
    }
}

/** A slot's current art at [sizeDp]: the override when there is one, else the tinted built-in. */
@Composable
fun SlotArt(slot: IconSlot, custom: ImageBitmap?, tint: Color, sizeDp: Int, modifier: Modifier = Modifier) {
    if (custom != null) {
        Image(bitmap = custom, contentDescription = slot.displayName, modifier = modifier.size(sizeDp.dp))
    } else {
        Image(
            painter = StudioIconSet.defaultPainter(slot.key),
            contentDescription = slot.displayName,
            colorFilter = if (StudioIconSet.isFullColour(slot.key)) null else ColorFilter.tint(tint, BlendMode.SrcIn),
            modifier = modifier.size(sizeDp.dp),
        )
    }
}

/** The theme's icon colour as the preview tints built-ins (Auto renders white, like the preview). */
fun StudioState.iconTintColor(): Color = when (val c = iconColor) {
    IconColorChoice.Auto -> Color.White
    is IconColorChoice.Custom -> Color(c.argb)
}

/** Up to [max] frames of an animated GIF for the strip; empty when the bytes cannot be decoded. */
internal fun decodeGifFrames(bytes: ByteArray, max: Int = 12): List<ImageBitmap> = runCatching {
    val codec = Codec.makeFromData(Data.makeFromBytes(bytes))
    try {
        (0 until minOf(codec.frameCount, max)).mapNotNull { index ->
            val bitmap = Bitmap().apply { allocPixels(codec.imageInfo) }
            runCatching {
                codec.readPixels(bitmap, index)
                SkiaImage.makeFromBitmap(bitmap).toComposeImageBitmap()
            }.getOrNull()
        }
    } finally {
        codec.close()
    }
}.getOrDefault(emptyList())

/**
 * The selected slot: name and status, key · group · template size, real-size renderings over the
 * current wallpaper, the frame strip for an animated GIF, and Local file / From theme / Reset / Export template.
 */
@Composable
fun SlotCard(
    slot: IconSlot,
    state: StudioState,
    onLocalFile: () -> Unit,
    onFromTheme: () -> Unit,
    onReset: () -> Unit,
    onExportTemplate: () -> Unit,
) {
    val extension = state.iconExtensions[slot.key]
    val bytes = state.iconOverrides[slot.key]
    val custom = state.iconBitmaps[slot.key]
    val card: SlotCardModel = remember(slot, extension, bytes) { IconPicker.cardModel(slot, extension, bytes) }

    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeading(card.name)
                Text(
                    card.status,
                    fontSize = 11.sp,
                    color = if (card.isCustom) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MutedText(card.detail, fontSize = 11)

            // Real sizes over the wallpaper the theme ships (the XMB's own backdrop).
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier.fillMaxWidth().height(104.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF202024)),
            ) {
                state.wallpaperBitmap?.let {
                    Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    val tint = state.iconTintColor()
                    IconPicker.previewSizes(slot).forEach { size ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SlotArt(slot, custom, tint, size.dp, Modifier.alpha(size.alpha))
                            Text(size.label, fontSize = 9.sp, color = Color.White.copy(alpha = 0.85f))
                        }
                    }
                }
            }

            if (card.frameCount > 1 && bytes != null) {
                val frames = remember(bytes) { decodeGifFrames(bytes) }
                if (frames.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        frames.forEach { frame ->
                            Box(Modifier.size(40.dp).clip(RoundedCornerShape(4.dp))) {
                                Checkerboard(40)
                                Image(bitmap = frame, contentDescription = null, modifier = Modifier.size(40.dp))
                            }
                        }
                    }
                    if (card.frameCount > frames.size) MutedText("Showing ${frames.size} of ${card.frameCount} frames", fontSize = 10)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onLocalFile) { Text("Local file…", fontSize = 12.sp) }
                OutlinedButton(onClick = onFromTheme, enabled = IconPicker.fromThemeAvailable(state)) {
                    Text("From theme…", fontSize = 12.sp)
                }
                if (card.isCustom) TextButton(onClick = onReset) { Text("Reset", fontSize = 12.sp) }
                TextButton(onClick = onExportTemplate) { Text("Export template", fontSize = 12.sp) }
            }
        }
    }
}
