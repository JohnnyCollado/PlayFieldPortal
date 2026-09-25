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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.feature.artwork.match.MatchProvider
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
    onEditField: (MetadataField) -> Unit,
    onRevertField: (MetadataField) -> Unit,
    onEditTextChanged: (String) -> Unit,
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onApply: () -> Unit,
    onConfirmTitleReplace: () -> Unit,
    onCancelTitleReplace: () -> Unit,
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
                when {
                    ui.nothingFound -> "Select or B  Close"
                    ui.isManual ->
                        "Up/Down  Rows  •  Left/Right  Policy  •  L1/R1  Source  •  Select  Edit / Apply  •  B  Cancel"
                    else ->
                        "Up/Down  Rows  •  Left/Right  Policy  •  L1/R1  Source  •  Select  Toggle / Apply  •  B  Cancel"
                },
                color = TextMuted.copy(alpha = 0.5f),
                fontSize = 10.sp,
            )

            if (ui.loading) {
                Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = focusEdge)
                }
                return@Column
            }

            // Nothing could be read at all — not even this game's own values, so there is nothing
            // to edit against either. Say why in the overlay the user opened, and leave closing to
            // them. (A game that simply no provider recognised is NOT this case: it reads fine and
            // gets the Manual column below.)
            if (ui.nothingFound) {
                Text(
                    if (ui.failed) "The metadata sources didn't answer." else "This game couldn't be read.",
                    color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    if (ui.failed) "Nothing was changed. Check the connection and try again."
                    else "Nothing was changed. Try reopening this game.",
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

            // "No source recognised this game" is a note here, not a dead end: the Manual column
            // below is exactly what that game needs.
            if (ui.noProviderFound) {
                Text(
                    "No source recognised this game — fill the fields you want by hand.",
                    color = TextMuted,
                    fontSize = 11.sp,
                )
            }

            // Source: one chip per provider that returned text, then the user, who always has.
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
                Chip(
                    label = MatchProvider.MANUAL.label,
                    selected = ui.isManual,
                    focusFill = focusFill,
                    focusEdge = focusEdge,
                    onClick = { onCycleSource(ui.presets.size - ui.presetIndex) },
                )
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
                Text(
                    if (ui.isManual) "Your value" else "Incoming",
                    color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f),
                )
            }

            val willWrite = ui.willWrite
            val rowCount = ui.applyIndex
            val scrollState = rememberScrollState()
            val stepPx = with(LocalDensity.current) { RowScrollStep.roundToPx() }
            LaunchedEffect(ui.focus) {
                if (ui.focus < rowCount) scrollState.animateScrollTo(ui.focus * stepPx)
            }
            Column(
                modifier = Modifier.weight(1f, fill = false).verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (ui.isManual) {
                    // Every field, including the empty ones — an empty field is the one the user
                    // most needs to reach, and a provider column can never show it.
                    ui.manualRows.forEachIndexed { index, row ->
                        ManualRow(
                            row = row,
                            focused = ui.focus == index,
                            writes = row.field in willWrite,
                            showCheck = ui.policy == MetadataApplyPolicy.CHOOSE_FIELDS,
                            checked = row.field in ui.chosen,
                            focusFill = focusFill,
                            focusEdge = focusEdge,
                            onClick = { onEditField(row.field) },
                            onRevert = { onRevertField(row.field) },
                        )
                    }
                } else {
                    ui.rows.forEachIndexed { index, row ->
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

        // Topmost of all: the yes/no on replacing a hand-typed title, which outranks even the
        // editor — it is the last thing between the user and losing what they typed.
        ui.titleReplace?.let { confirm ->
            TitleReplaceDialog(
                confirm = confirm,
                focusEdge = focusEdge,
                onConfirm = onConfirmTitleReplace,
                onCancel = onCancelTitleReplace,
            )
            return@Box
        }

        // Topmost inside the overlay: one field's text editor.
        ui.editingField?.let { field ->
            MetadataFieldEditor(
                field = field,
                text = ui.editText,
                overridden = field in ui.overridden,
                scraped = ui.current[field],
                focusEdge = focusEdge,
                onChange = onEditTextChanged,
                onSave = onSaveEdit,
                onRevert = { onRevertField(field) },
                onCancel = onCancelEdit,
            )
        }
    }
}

/**
 * The title confirm: the field editor's chrome with the text swapped for two lines — what the user
 * typed and what will replace it — so a destructive apply looks like every other dialog here.
 */
@Composable
private fun TitleReplaceDialog(
    confirm: TitleReplaceConfirm,
    focusEdge: Color,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(onClick = onCancel),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xF20A0A14))
                .clickable(enabled = false) {}
                .padding(16.dp),
        ) {
            Text(
                "Replace your title?",
                color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(
                "You set this title by hand. Applying the new one clears yours.",
                color = TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )
            TitleReplaceLine("Yours", confirm.current, TextMuted)
            Spacer(Modifier.height(4.dp))
            TitleReplaceLine("New", confirm.incoming, ChangeGreen)
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onCancel) { Text("Keep Mine", color = TextMuted) }
                TextButton(onClick = onConfirm) {
                    Text("Replace", color = focusEdge, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun TitleReplaceLine(label: String, value: String, valueColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextMuted, fontSize = 11.sp, modifier = Modifier.width(52.dp))
        Text(
            value.ifBlank { "—" },
            color = valueColor,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * One field's text entry, over the table. Deliberately the same shape as Edit Title: a text field,
 * Cancel / Save, and a revert that is offered only when there is something to revert to.
 */
@Composable
private fun MetadataFieldEditor(
    field: MetadataField,
    text: String,
    overridden: Boolean,
    scraped: Any?,
    focusEdge: Color,
    onChange: (String) -> Unit,
    onSave: () -> Unit,
    onRevert: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(onClick = onCancel),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xF20A0A14))
                .clickable(enabled = false) {}
                .padding(16.dp),
        ) {
            Text(
                "Edit ${field.label}",
                color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Your value is kept when this game is scraped again. Clear it to use the scraped one.",
                color = TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            OutlinedTextField(
                value = text,
                onValueChange = onChange,
                label = { Text(field.label, color = TextMuted) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = focusEdge,
                    unfocusedBorderColor = Color(0x44FFFFFF),
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = focusEdge,
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (field.isNumeric) KeyboardType.Number else KeyboardType.Text,
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onSave() }),
                singleLine = field != MetadataField.DESCRIPTION,
                maxLines = if (field == MetadataField.DESCRIPTION) 6 else 1,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                // Only where there is a scraped value to fall back to; otherwise reverting would
                // just empty the field, which clearing the text already does.
                if (overridden) {
                    TextButton(onClick = onRevert) {
                        Text(
                            "Revert to ${formatMetadataValue(scraped) ?: "empty"}",
                            color = TextMuted, fontSize = 12.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                Row {
                    TextButton(onClick = onCancel) { Text("Cancel", color = TextMuted) }
                    TextButton(onClick = onSave) {
                        Text("Save", color = focusEdge, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** Number-only fields, so the on-screen keyboard opens on the right page. */
private val MetadataField.isNumeric: Boolean
    get() = this == MetadataField.RELEASE_YEAR || this == MetadataField.COMMUNITY_RATING

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
                com.playfieldportal.core.ui.components.PfpCheckbox(
                    checked = checked,
                    color = TextPrimary,
                    markColor = RowFill,
                    size = 13.dp,
                    modifier = Modifier.padding(end = 6.dp),
                )
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

/**
 * One editable line. Same shape as [FieldRow] so the two columns read as one table, with the
 * incoming cell showing what the user has typed and a revert marker where a value is hand-set.
 */
@Composable
private fun ManualRow(
    row: ManualFieldRow,
    focused: Boolean,
    writes: Boolean,
    showCheck: Boolean,
    checked: Boolean,
    focusFill: Color,
    focusEdge: Color,
    onClick: () -> Unit,
    onRevert: () -> Unit,
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
                com.playfieldportal.core.ui.components.PfpCheckbox(
                    checked = checked,
                    color = TextPrimary,
                    markColor = RowFill,
                    size = 13.dp,
                    modifier = Modifier.padding(end = 6.dp),
                )
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
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.text.ifBlank { "Tap to type" },
                color = when {
                    writes -> ChangeGreen
                    row.text.isBlank() -> TextMuted.copy(alpha = 0.5f)
                    else -> TextPrimary
                },
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // A field already set by hand carries its own revert, so undoing one does not mean
            // remembering what the scraper said.
            if (row.overridden) {
                Text(
                    "↺",
                    color = TextMuted,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(onClick = onRevert)
                        .padding(horizontal = 4.dp),
                )
            }
        }
    }
}

/** Community rating is stored normalized 0..1 (ScreenScraper's /20); everything else prints as-is. */
private fun formatMetadataValue(value: Any?): String? = when (value) {
    null -> null
    is Float -> "${(value * 100).roundToInt()}%"
    else -> value.toString()
}
