package com.playfieldportal.feature.xmb.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.remember
import com.playfieldportal.core.ui.detail.PfpDetailBreadcrumb
import com.playfieldportal.core.ui.detail.PfpDetailHelperFooter
import com.playfieldportal.core.ui.detail.PfpDetailScaffold
import com.playfieldportal.core.ui.detail.PfpDetailSectionLabel
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import com.playfieldportal.core.ui.components.PfpBusyRing
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.ui.theme.themedSubText
import com.playfieldportal.core.ui.theme.themedText
import com.playfieldportal.feature.artwork.match.MatchProvider
import com.playfieldportal.feature.artwork.match.MetadataApplyPolicy
import com.playfieldportal.feature.artwork.match.MetadataField
import com.playfieldportal.feature.artwork.match.MetadataFieldRow
import com.playfieldportal.feature.xmb.R
import com.playfieldportal.core.domain.model.ControllerIcon
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.components.ControllerPromptItem
import kotlin.math.roundToInt

// ── Update Metadata: Current vs Incoming, as its own page (C16 task 3.2) ─────────────────────────
// GameDetailViewModel owns the state and routes controller input (Up/Down rows, Left/Right policy,
// L1/R1 source, Select toggles a row or applies, Back leaves without writing); this renders it and
// forwards taps. A page rather than a pop-up since 23 Nov 2026 (owner's call): the table needs the
// screen's height, which a prompt band cannot give it on a 360dp-tall handheld.

private val TextPrimary = Color(0xFFEEEEEE)
private val TextMuted = Color(0xAAEEEEEE)
private val RowFill = Color(0xFF1B1B26)
// A chip or button that is not chosen: an outline on the page, no fill.
private val UnselectedEdge = Color(0x55FFFFFF)
private val ChangeGreen = Color(0xFF45C46A)

@Composable
fun MetadataUpdatePage(
    ui: MetadataPreviewUi,
    gameTitle: String,
    focusFill: Color,
    focusEdge: Color,
    onSelectPolicy: (MetadataApplyPolicy) -> Unit,
    onCycleSource: (Int) -> Unit,
    onToggleField: (MetadataField) -> Unit,
    onEditField: (MetadataField) -> Unit,
    onRevertField: (MetadataField) -> Unit,
    onApply: () -> Unit,
    onClose: () -> Unit,
    showHints: Boolean = true,
) {
    PfpDetailScaffold(
        header = {
            PfpDetailBreadcrumb(title = stringResource(R.string.det_meta_title), subtitle = gameTitle, onBack = onClose)
        },
        footer = { PfpDetailHelperFooter(items = metadataHints(ui), visible = showHints) },
    ) {
        // The page's top is where the cursor's first row takes the view: the first field row is the
        // top of the cursor's range, and above it are Source and Policy, which a controller user also
        // needs to see. Every other row frames itself.
        val pageTop = remember { BringIntoViewRequester() }
        LaunchedEffect(ui.focus, ui.loading) { if (ui.focus == 0) pageTop.bringIntoView() }
        Box(Modifier.fillMaxWidth().height(1.dp).bringIntoViewRequester(pageTop))
        Spacer(Modifier.height(13.dp))
        if (ui.loading) {
            Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                PfpBusyRing(color = focusEdge)
            }
            return@PfpDetailScaffold
        }

        // Nothing could be read at all — not even this game's own values, so there is nothing to edit
        // against either. Say why on the page the user opened, and leave closing to them. (A game that
        // simply no provider recognised is NOT this case: it reads fine and gets the Manual column.)
        if (ui.nothingFound) {
            Text(
                if (ui.failed) stringResource(R.string.det_meta_failed_title) else stringResource(R.string.det_meta_unreadable_title),
                color = themedText(TextPrimary), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (ui.failed) stringResource(R.string.det_meta_failed_detail) else stringResource(R.string.det_meta_unreadable_detail),
                color = themedSubText(TextMuted), fontSize = 13.sp,
            )
            Spacer(Modifier.height(16.dp))
            PageButton(stringResource(R.string.det_close), focused = true, focusFill = focusFill, focusEdge = focusEdge, onClick = onClose)
            return@PfpDetailScaffold
        }

        // "No source recognised this game" is a note here, not a dead end: the Manual column below is
        // exactly what that game needs.
        if (ui.noProviderFound) {
            Text(stringResource(R.string.det_meta_no_source), color = themedSubText(TextMuted), fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
        }

        // Source: one chip per provider that returned text, then the user, who always has.
        PfpDetailSectionLabel(stringResource(R.string.det_meta_prompt_source))
        Spacer(Modifier.height(6.dp))
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
        Spacer(Modifier.height(12.dp))
        // Policy: the four ways to apply.
        PfpDetailSectionLabel(stringResource(R.string.det_meta_prompt_policy))
        Spacer(Modifier.height(6.dp))
        ChipRow {
            MetadataApplyPolicy.entries.forEach { policy ->
                Chip(
                    label = stringResource(policy.labelRes),
                    selected = policy == ui.policy,
                    focusFill = focusFill,
                    focusEdge = focusEdge,
                    onClick = { onSelectPolicy(policy) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
            Text(stringResource(R.string.det_meta_col_field), color = themedSubText(TextMuted), fontSize = 11.sp, modifier = Modifier.width(FieldColumn))
            Text(stringResource(R.string.det_meta_col_current), color = themedSubText(TextMuted), fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text(
                if (ui.isManual) stringResource(R.string.det_meta_col_yours) else stringResource(R.string.det_meta_col_incoming),
                color = themedSubText(TextMuted), fontSize = 11.sp, modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(4.dp))

        val willWrite = ui.willWrite
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (ui.isManual) {
                // Every field, including the empty ones — an empty field is the one the user most
                // needs to reach, and a provider column can never show it.
                ui.manualRows.forEachIndexed { index, row ->
                    ManualRow(
                        row = row,
                        focused = ui.focus == index,
                        first = index == 0,
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
                        first = index == 0,
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
        Spacer(Modifier.height(14.dp))

        val applyLabel = when {
            ui.applying -> stringResource(R.string.det_meta_applying)
            ui.policy == MetadataApplyPolicy.KEEP_CURRENT -> stringResource(R.string.det_meta_keep_close)
            willWrite.isEmpty() -> stringResource(R.string.det_meta_nothing_to_change)
            else -> pluralStringResource(R.plurals.det_meta_apply_changes, willWrite.size, willWrite.size)
        }
        PageButton(applyLabel, focused = ui.focus >= ui.applyIndex, focusFill = focusFill, focusEdge = focusEdge, onClick = onApply)
        // Its dialogs — Replace your title?, and one field's text entry — are Game Detail's shared
        // modals (see metadataModalSpec), drawn above this page.
    }
}

/** The page's one button (Apply, or Close when nothing could be read), framed into view when focused. */
@Composable
private fun PageButton(label: String, focused: Boolean, focusFill: Color, focusEdge: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .framedWhenFocused(focused)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) focusFill else Color.Transparent)
            .border(if (focused) 1.5.dp else 1.dp, if (focused) focusEdge else UnselectedEdge, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = themedText(TextPrimary), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Keeps the focused row on screen by its own geometry, not scroll arithmetic — this app's convention
 * for controller focus. The page scrolls; the rows do not.
 */
@Composable
private fun Modifier.framedWhenFocused(focused: Boolean): Modifier {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(focused) { if (focused) requester.bringIntoView() }
    return bringIntoViewRequester(requester)
}

/** What the page's buttons do, for the helper footer. */
@Composable
private fun metadataHints(ui: MetadataPreviewUi): List<ControllerPromptItem> =
    if (ui.nothingFound || ui.loading) {
        listOf(ControllerPromptItem(GamepadAction.BACK, stringResource(R.string.det_close)))
    } else {
        listOf(
            ControllerPromptItem.fixed(listOf(ControllerIcon.DPAD_UP, ControllerIcon.DPAD_DOWN), stringResource(R.string.det_meta_prompt_rows)),
            ControllerPromptItem.fixed(listOf(ControllerIcon.DPAD_LEFT, ControllerIcon.DPAD_RIGHT), stringResource(R.string.det_meta_prompt_policy)),
            ControllerPromptItem(listOf(GamepadAction.PREV_CATEGORY, GamepadAction.NEXT_CATEGORY), stringResource(R.string.det_meta_prompt_source)),
            ControllerPromptItem(
                GamepadAction.SELECT,
                stringResource(if (ui.isManual) R.string.det_meta_prompt_edit_apply else R.string.det_meta_prompt_toggle_apply),
            ),
            ControllerPromptItem(GamepadAction.BACK, stringResource(R.string.det_cancel)),
        )
    }

/** Number-only fields, so the on-screen keyboard opens on the right page. */
internal val MetadataField.isNumeric: Boolean
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
        color = themedText(TextPrimary),
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) focusFill else Color.Transparent)
            .border(1.dp, if (selected) focusEdge else UnselectedEdge, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun FieldRow(
    row: MetadataFieldRow,
    focused: Boolean,
    // The first row leaves framing to the page's top (see MetadataUpdatePage).
    first: Boolean,
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
            .framedWhenFocused(focused && !first)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) focusFill else Color.Transparent)
            .then(if (focused) Modifier.border(1.5.dp, focusEdge, RoundedCornerShape(8.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 10.dp),
    ) {
        Row(Modifier.width(FieldColumn), verticalAlignment = Alignment.CenterVertically) {
            if (showCheck) {
                com.playfieldportal.core.ui.components.PfpCheckbox(
                    checked = checked,
                    color = themedText(TextPrimary),
                    markColor = RowFill,
                    size = 13.dp,
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
            Text(stringResource(row.field.labelRes), color = themedText(TextPrimary), fontSize = 12.sp, maxLines = 1)
        }
        Text(
            formatMetadataValue(row.current) ?: "—",
            color = themedSubText(TextMuted),
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
                !row.differs -> themedSubText(TextMuted.copy(alpha = 0.6f))
                else -> themedText(TextPrimary)
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
    // The first row leaves framing to the page's top (see MetadataUpdatePage).
    first: Boolean,
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
            .framedWhenFocused(focused && !first)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) focusFill else Color.Transparent)
            .then(if (focused) Modifier.border(1.5.dp, focusEdge, RoundedCornerShape(8.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 10.dp),
    ) {
        Row(Modifier.width(FieldColumn), verticalAlignment = Alignment.CenterVertically) {
            if (showCheck) {
                com.playfieldportal.core.ui.components.PfpCheckbox(
                    checked = checked,
                    color = themedText(TextPrimary),
                    markColor = RowFill,
                    size = 13.dp,
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
            Text(stringResource(row.field.labelRes), color = themedText(TextPrimary), fontSize = 12.sp, maxLines = 1)
        }
        Text(
            formatMetadataValue(row.current) ?: "—",
            color = themedSubText(TextMuted),
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.text.ifBlank { stringResource(R.string.det_meta_tap_to_type) },
                color = when {
                    writes -> ChangeGreen
                    row.text.isBlank() -> themedSubText(TextMuted.copy(alpha = 0.5f))
                    else -> themedText(TextPrimary)
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
                    color = themedSubText(TextMuted),
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
internal fun formatMetadataValue(value: Any?): String? = when (value) {
    null -> null
    is Float -> "${(value * 100).roundToInt()}%"
    else -> value.toString()
}
