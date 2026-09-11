package com.playfieldportal.feature.xmb.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.feature.artwork.match.MetadataApplyPolicy
import com.playfieldportal.feature.artwork.match.MetadataField
import com.playfieldportal.feature.artwork.match.MetadataFieldRow
import kotlin.math.roundToInt

// ── Current-vs-Incoming metadata preview (C16 task 3.2) ──────────────────────
// GameDetailViewModel owns the state and routes controller input (Up/Down rows, Left/Right policy,
// L1/R1 source, Select toggles a row or applies, Back closes without writing); this renders it and
// forwards taps.

private val TextPrimary = Color(0xFFEEEEEE)
private val TextMuted = Color(0xAAEEEEEE)
private val RowFill = Color(0xFF1B1B26)
private val ChangeGreen = Color(0xFF45C46A)
private val RowScrollStep = 52.dp

@Composable
fun MetadataPreviewPanel(
    ui: MetadataPreviewUi,
    focusFill: Color,
    focusEdge: Color,
    onSelectPolicy: (MetadataApplyPolicy) -> Unit,
    onCycleSource: (Int) -> Unit,
    onToggleField: (MetadataField) -> Unit,
    onApply: () -> Unit,
    onClose: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 320.dp, max = 680.dp)
                .fillMaxWidth(0.92f)
                .heightIn(max = 540.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xF20A0A14))
                .clickable(enabled = false) {}
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Update Metadata", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (ui.nothingFound) "Select or B  Close"
                else "Up/Down  Rows  •  Left/Right  Policy  •  L1/R1  Source  •  Select  Toggle / Apply  •  B  Cancel",
                color = TextMuted.copy(alpha = 0.5f),
                fontSize = 10.sp,
            )

            if (ui.loading) {
                Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = focusEdge)
                }
                return@Column
            }

            // Nothing to preview: say why in the overlay the user opened, and leave closing to them.
            if (ui.nothingFound) {
                Text(
                    if (ui.failed) "The metadata sources didn't answer." else "No source recognised this game.",
                    color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    if (ui.failed) {
                        "Nothing was changed. Check the connection and try again."
                    } else {
                        // Presets come from ScreenScraper (by its saved id) and TheGamesDB (by title),
                        // so a ScreenScraper Change Match is what gives this game a source.
                        "Nothing was changed. To identify it, open Artwork, choose ScreenScraper and " +
                            "use Change Match, then update metadata again."
                    },
                    color = TextMuted, fontSize = 12.sp,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(focusFill)
                        .border(1.5.dp, focusEdge, RoundedCornerShape(8.dp))
                        .clickable(onClick = onClose)
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Close", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                return@Column
            }

            // Source: one chip per provider that returned text.
            ChipRow {
                ui.presets.forEachIndexed { index, preset ->
                    Chip(
                        label = preset.provider.label,
                        selected = index == ui.presetIndex,
                        focusFill = focusFill,
                        focusEdge = focusEdge,
                        onClick = { onCycleSource(index - ui.presetIndex) },
                    )
                }
            }
            // Policy: the four ways to apply.
            ChipRow {
                MetadataApplyPolicy.entries.forEach { policy ->
                    Chip(
                        label = policy.label,
                        selected = policy == ui.policy,
                        focusFill = focusFill,
                        focusEdge = focusEdge,
                        onClick = { onSelectPolicy(policy) },
                    )
                }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
                Text("Field", color = TextMuted, fontSize = 11.sp, modifier = Modifier.width(FieldColumn))
                Text("Current", color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text("Incoming", color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            }

            val rows = ui.rows
            val willWrite = ui.willWrite
            val scrollState = rememberScrollState()
            val stepPx = with(LocalDensity.current) { RowScrollStep.roundToPx() }
            LaunchedEffect(ui.focus) {
                if (ui.focus < rows.size) scrollState.animateScrollTo(ui.focus * stepPx)
            }
            Column(
                modifier = Modifier.weight(1f, fill = false).verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                rows.forEachIndexed { index, row ->
                    FieldRow(
                        row = row,
                        focused = ui.focus == index,
                        writes = row.field in willWrite,
                        showCheck = ui.policy == MetadataApplyPolicy.CHOOSE_FIELDS,
                        checked = row.field in ui.chosen,
                        focusFill = focusFill,
                        focusEdge = focusEdge,
                        onClick = { onToggleField(row.field) },
                    )
                }
            }

            val applyFocused = ui.focus >= ui.applyIndex
            val applyLabel = when {
                ui.applying -> "Applying…"
                ui.policy == MetadataApplyPolicy.KEEP_CURRENT -> "Keep Current & Close"
                willWrite.isEmpty() -> "Nothing to Change"
                willWrite.size == 1 -> "Apply 1 Change"
                else -> "Apply ${willWrite.size} Changes"
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (applyFocused) focusFill else RowFill)
                    .then(if (applyFocused) Modifier.border(1.5.dp, focusEdge, RoundedCornerShape(8.dp)) else Modifier)
                    .clickable(onClick = onApply)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(applyLabel, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private val FieldColumn = 104.dp

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    focusFill: Color,
    focusEdge: Color,
    onClick: () -> Unit,
) {
    Text(
        label,
        color = TextPrimary,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) focusFill else RowFill)
            .then(if (selected) Modifier.border(1.dp, focusEdge, RoundedCornerShape(12.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun FieldRow(
    row: MetadataFieldRow,
    focused: Boolean,
    writes: Boolean,
    showCheck: Boolean,
    checked: Boolean,
    focusFill: Color,
    focusEdge: Color,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) focusFill else RowFill)
            .then(if (focused) Modifier.border(1.5.dp, focusEdge, RoundedCornerShape(8.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 10.dp),
    ) {
        Row(Modifier.width(FieldColumn), verticalAlignment = Alignment.CenterVertically) {
            if (showCheck) {
                Text(if (checked) "☑" else "☐", color = TextPrimary, fontSize = 13.sp, modifier = Modifier.padding(end = 6.dp))
            }
            Text(row.field.label, color = TextPrimary, fontSize = 12.sp, maxLines = 1)
        }
        Text(
            formatMetadataValue(row.current) ?: "—",
            color = TextMuted,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        Text(
            formatMetadataValue(row.incoming).orEmpty(),
            // Green is exactly "this policy writes it"; an equal value reads dimmed.
            color = when {
                writes -> ChangeGreen
                !row.differs -> TextMuted.copy(alpha = 0.6f)
                else -> TextPrimary
            },
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Community rating is stored normalized 0..1 (ScreenScraper's /20); everything else prints as-is. */
private fun formatMetadataValue(value: Any?): String? = when (value) {
    null -> null
    is Float -> "${(value * 100).roundToInt()}%"
    else -> value.toString()
}
