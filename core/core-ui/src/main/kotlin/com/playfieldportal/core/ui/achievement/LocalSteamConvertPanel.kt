package com.playfieldportal.core.ui.achievement

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.ui.components.PfpCheckMark
import com.playfieldportal.core.ui.components.XmbHeaderPill

// ── Convert-detected-games picker ─────────────────────────────────────────────
//
// The same tokens and geometry the storefront match panel uses, for the same reason: these two
// panels can open from adjacent steps of one flow, and a user should not be able to tell which of
// them was written later. Replaces the Material AlertDialog this screen used to be, which had no
// focus model and no controller input path at all.
//
// Pure UI. The caller owns the rows, the focus index and every callback, so one panel serves the XMB
// Windows card, the Library Manager and the batch matcher without any of them importing the others.

private val TextPrimary = Color(0xFFEEEEEE)
private val TextMuted = Color(0xAAEEEEEE)
private val RowFill = Color(0xFF1B1B26)
private val PanelFill = Color(0xF20A0A14)
private val WarningAmber = Color(0xFFE0B341)
private val CheckGreen = Color(0xFF45C46A)

/** One row in the convert picker; position in the list is the toggle key. */
data class LocalSteamConvertRow(
    val folderName: String,
    /** The quiet second line: the coin count, or why this row cannot be converted. */
    val note: String,
    val selected: Boolean,
    /** True when the row cannot be converted at all — rendered dimmed and refusing input. */
    val unselectable: Boolean = false,
)

/**
 * The "install the achievement kit?" multi-select picker.
 *
 * Lists the game folders that carry a Steam app id but no achievement list, and converts every
 * checked one on [onConfirm]: PFP writes the achievement and stat files, points the emulator's save
 * redirect back into the game folder, and swaps in the bundled emulator over the game's original
 * `steam_api` DLL (backed up alongside it). That is the step the save-backup warning exists for,
 * which is why the panel states it and why nothing here is pre-authorised.
 *
 * [onSkip] links the folders that were already ready and writes into no game folder at all — a real
 * exit, not a dead end.
 */
@Composable
fun LocalSteamConvertPanel(
    rows: List<LocalSteamConvertRow>,
    focus: Int,
    loading: Boolean,
    canConfirm: Boolean,
    focusFill: Color,
    focusEdge: Color,
    showTouchControls: Boolean,
    onRowClick: (index: Int) -> Unit,
    onSelectAllNone: () -> Unit,
    onConfirm: () -> Unit,
    onSkip: () -> Unit,
    onCancel: () -> Unit,
) {
    val selectedCount = rows.count { it.selected && !it.unselectable }
    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(onClick = onCancel),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 360.dp, max = 760.dp)
                .fillMaxWidth(0.92f)
                .heightIn(max = 560.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(PanelFill)
                // Swallows taps on the panel itself, so only the scrim dismisses.
                .clickable(enabled = false) {}
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "Install achievement tracking?",
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (selectedCount > 0) "$selectedCount of ${rows.size}" else "${rows.size} found",
                    color = TextMuted,
                    fontSize = 12.sp,
                )
            }

            if (!showTouchControls) {
                Text(
                    "Up/Down  Rows  •  Select  Toggle  •  △  All/None  •  Start  Install  •  B  Cancel",
                    color = TextMuted.copy(alpha = 0.55f),
                    fontSize = 10.sp,
                )
            }

            // What will be written, in the files' own names. A user authorising a DLL swap is owed
            // the list, not a euphemism for it.
            WriteNotice()

            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rows.forEachIndexed { index, row ->
                    ConvertRow(
                        row = row,
                        focused = focus == index,
                        focusFill = focusFill,
                        focusEdge = focusEdge,
                        onClick = { onRowClick(index) },
                    )
                }
            }

            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(color = focusEdge, modifier = Modifier.size(14.dp))
                    Text("Reading each game's achievement list from Steam…", color = TextMuted, fontSize = 11.sp)
                }
            }

            if (showTouchControls) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (canConfirm) XmbHeaderPill(label = "Install ($selectedCount)", onClick = onConfirm)
                    XmbHeaderPill(label = if (selectedCount > 0) "None" else "All", onClick = onSelectAllNone)
                    Spacer(Modifier.weight(1f))
                    XmbHeaderPill(label = "Skip & Sync", onClick = onSkip)
                    XmbHeaderPill(label = "Cancel", onClick = onCancel)
                }
            }
        }
    }
}

/**
 * The statement of what a conversion writes.
 *
 * Deliberately concrete. "Bring the game up to the current setup" is what this used to say, and it
 * does not tell anyone that their `steam_api64.dll` is about to be replaced and their save location
 * moved.
 */
@Composable
private fun WriteNotice() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x0DFFFFFF))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            "Into each checked game's folder PFP writes achievements.json, stats.json and " +
                "configs.user.ini, creates a saves folder, and renames steam_api64.dll to " +
                "steam_api64_o.dll so the bundled emulator can load through it.",
            color = TextPrimary,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
        Text(
            "Back up the save files of any game you already played before converting it — the " +
                "emulator will start reading from the new save location.",
            color = WarningAmber,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConvertRow(
    row: LocalSteamConvertRow,
    focused: Boolean,
    focusFill: Color,
    focusEdge: Color,
    onClick: () -> Unit,
) {
    // Framed by geometry, never by scroll arithmetic: the requester asks the scroll parent to bring
    // this exact node into view, so a row of any height lands correctly and a re-ordered list cannot
    // desynchronise from a counted offset.
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(focused) { if (focused) bringIntoView.bringIntoView() }

    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoView)
            .clip(shape)
            .background(if (focused) focusFill else RowFill)
            .then(if (focused) Modifier.border(1.5.dp, focusEdge, shape) else Modifier)
            .clickable(enabled = !row.unselectable, role = Role.Checkbox, onClick = onClick)
            // Android's minimum touch height, so every row stays tappable.
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckBox(checked = row.selected, enabled = !row.unselectable)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                row.folderName,
                color = if (row.unselectable) TextMuted else TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.note,
                color = if (row.unselectable) WarningAmber.copy(alpha = 0.8f) else TextMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A box with PFP's own check mark, rather than Material's — this panel draws its own controls. */
@Composable
private fun CheckBox(checked: Boolean, enabled: Boolean) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(shape)
            .background(if (checked) CheckGreen.copy(alpha = 0.22f) else Color(0xFF101018))
            .border(1.dp, if (checked && enabled) CheckGreen else TextMuted.copy(alpha = 0.35f), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) PfpCheckMark(CheckGreen, size = 11.dp)
    }
}
