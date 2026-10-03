package com.playfieldportal.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.studio.BudgetKind
import com.playfieldportal.studio.ExportCheck
import java.util.Locale

/** One kind's slice of the bundle. [share] is its fraction of the total payload (0..1). */
data class BudgetSegment(val kind: BudgetKind, val bytes: Long, val share: Float)

/** The "In this file" strip as plain data. */
data class ContentsModel(
    val segments: List<BudgetSegment>,
    val totalBytes: Long,
    /** How full the bundle is against the hard cap, clamped to 0..1. */
    val usedOfHard: Float,
    /** Launchers from before this format refuse files above 64 MB (plan A8). */
    val overPreV4Limit: Boolean,
    val overHardLimit: Boolean,
)

fun contentsModel(check: ExportCheck): ContentsModel {
    val total = check.totalBytes
    val segments = BudgetKind.entries
        .mapNotNull { kind -> check.bytesByKind[kind]?.takeIf { it > 0 }?.let { kind to it } }
        .map { (kind, bytes) -> BudgetSegment(kind, bytes, bytes.toFloat() / total) }
    return ContentsModel(
        segments = segments,
        totalBytes = total,
        usedOfHard = (total.toFloat() / ExportCheck.HARD_MAX_BYTES).coerceIn(0f, 1f),
        overPreV4Limit = total > ExportCheck.PRE_V4_MAX_BYTES,
        overHardLimit = total > ExportCheck.HARD_MAX_BYTES,
    )
}

/** "12 KB", "1.5 MB" - KB below one MB, MB above. */
fun formatBytes(bytes: Long): String {
    val kb = bytes / 1024.0
    return when {
        bytes <= 0 -> "0 KB"
        kb < 10 -> String.format(Locale.ROOT, "%.1f KB", kb)
        kb < 1024 -> String.format(Locale.ROOT, "%.0f KB", kb)
        else -> String.format(Locale.ROOT, "%.1f MB", kb / 1024)
    }
}

// Neutral, accent-independent: the strip is chrome, so it must not recolor with the theme.
private val SEGMENT_COLORS = mapOf(
    BudgetKind.WALLPAPER to Color(0xFF7C8FA6),
    BudgetKind.PREVIEW to Color(0xFF8E9AAF),
    BudgetKind.ICONS to Color(0xFFA3B18A),
    BudgetKind.MOTION to Color(0xFFB08968),
    BudgetKind.SOUNDS to Color(0xFF9D8DB5),
    BudgetKind.AMBIENCE to Color(0xFF8FB3B0),
    BudgetKind.BOOT to Color(0xFFC09A8A),
    BudgetKind.GAMEBOOT to Color(0xFFB5A27A),
    BudgetKind.OTHER to Color(0xFF777777),
)

/** Center-bottom "In this file" strip: a proportional bar plus a legend and the total against the cap. */
@Composable
fun FileContentsStrip(check: ExportCheck, modifier: Modifier = Modifier) {
    val model = contentsModel(check)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("In this file", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
            Box(Modifier.weight(1f))
            val warn = model.overPreV4Limit
            Text(
                "${formatBytes(model.totalBytes)} of ${ExportCheck.HARD_MAX_BYTES / (1024 * 1024)} MB" +
                    if (warn && !model.overHardLimit) " - older launchers refuse over 64 MB" else "",
                fontSize = 11.sp,
                color = if (model.overHardLimit) MaterialTheme.colorScheme.error
                else if (warn) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            // The filled part is the used share of the cap; segments split that part by kind.
            if (model.segments.isNotEmpty()) {
                Row(Modifier.weight(model.usedOfHard.coerceAtLeast(0.02f))) {
                    model.segments.forEach { seg ->
                        Box(
                            Modifier.weight(seg.share.coerceAtLeast(0.001f)).height(8.dp)
                                .background(SEGMENT_COLORS.getValue(seg.kind)),
                        )
                    }
                }
                val rest = 1f - model.usedOfHard.coerceAtLeast(0.02f)
                if (rest > 0f) Box(Modifier.weight(rest))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            if (model.segments.isEmpty()) {
                Text("Nothing embedded yet", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
            }
            model.segments.forEach { seg ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(SEGMENT_COLORS.getValue(seg.kind)))
                    Text(
                        "${seg.kind.label} ${formatBytes(seg.bytes)}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
