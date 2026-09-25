package com.playfieldportal.feature.xmb.ui.detail

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.playfieldportal.core.ui.components.XmbHeaderPill
import com.playfieldportal.feature.artwork.match.MatchConfidence
import com.playfieldportal.feature.artwork.match.MatchSignal

// ── Storefront match picker (C23 T6, Phase 10) ───────────────────────────────
// GameDetailViewModel owns the state and routes controller input (Up/Down rows, Select chooses,
// Triangle opens More Information, Back cancels); this renders it and forwards taps.
//
// The same tokens the metadata preview uses, for the reason the two panels sit side by side in the
// same screen: a user should not be able to tell which of them was written later.

private val TextPrimary = Color(0xFFEEEEEE)
private val TextMuted = Color(0xAAEEEEEE)
private val RowFill = Color(0xFF1B1B26)
private val SignalGreen = Color(0xFF45C46A)
private val AttentionAmber = Color(0xFFE0B341)
private val CapsuleFill = Color(0xFF101018)

/** Steam's `tiny_image` is a WIDE capsule (~120×45), not box art — so the slot is shaped like one. */
private val CapsuleWidth = 92.dp
private val CapsuleHeight = 35.dp

@Composable
fun StorefrontMatchPanel(
    ui: StorefrontMatchUi,
    focusFill: Color,
    focusEdge: Color,
    showTouchControls: Boolean,
    onRowClick: (Int) -> Unit,
    onMoreInfo: () -> Unit,
    onCloseMoreInfo: () -> Unit,
    onChooseFocused: () -> Unit,
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
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "Match Game",
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                ui.storeLabel?.let {
                    Text(it, color = TextMuted, fontSize = 12.sp)
                }
            }

            if (!showTouchControls) {
                Text(
                    "Up/Down  Rows  •  Select  Choose  •  △  More Information  •  B  Cancel",
                    color = TextMuted.copy(alpha = 0.55f),
                    fontSize = 10.sp,
                )
            }

            if (ui.loading) {
                Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = focusEdge)
                }
                return@Column
            }

            // Nothing to choose between. Said plainly, with the reason, and never as a dead end:
            // an unreachable store and a store that simply lacks the game lead to opposite actions.
            if (ui.rows.isEmpty()) {
                EmptyState(ui)
                TouchActions(showTouchControls, choosable = false, onChooseFocused, onMoreInfo, onClose)
                return@Column
            }

            LocalGameStrip(ui)

            when {
                ui.confidence == MatchConfidence.AMBIGUOUS && ui.tiedOnExactTitle -> Notice(
                    "Two store entries carry this exact title. A tie on an identical name can't be " +
                        "broken by evidence, so nothing is linked until you choose.",
                    AttentionAmber,
                )
                // Below the bar PFP would ever link on. Said plainly, because these rows would
                // otherwise read as recommendations: `Bravely Default` finds `BRAVELY DEFAULT II`,
                // which is a different game, and only the user knows whether one of these is theirs.
                ui.confidence == MatchConfidence.LOW -> Notice(
                    "None of these is a confident match — the names only partly agree. Check the " +
                        "year and the developer before choosing, or leave it unlinked.",
                    AttentionAmber,
                )
                else -> Unit
            }

            // One scroll region for the list, taking all the space the action row leaves. See the
            // note in StorefrontRematchPanel for why a `fill = false` weighted list with siblings
            // below it lands them on top of its last row — the picker had the same shape and only
            // escaped it by usually returning two candidates.
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ui.rows.forEachIndexed { index, row ->
                    CandidateRow(
                        row = row,
                        focused = ui.focus == index,
                        focusFill = focusFill,
                        focusEdge = focusEdge,
                        onClick = { onRowClick(index) },
                    )
                }

                // Always last, always present. The brief's rule: never force a choice.
                NoMatchRow(
                    focused = ui.focus == ui.noMatchIndex,
                    title = ui.gameTitle,
                    focusFill = focusFill,
                    focusEdge = focusEdge,
                    onClick = { onRowClick(ui.noMatchIndex) },
                )
            }

            TouchActions(showTouchControls, ui.focusedCandidate != null, onChooseFocused, onMoreInfo, onClose)
        }

        // Topmost inside the overlay: why one candidate ranked where it did.
        if (ui.moreInfoOpen) {
            ui.focusedCandidate?.let { candidate ->
                StorefrontMoreInfoPanel(
                    row = candidate,
                    tiedOnExactTitle = ui.tiedOnExactTitle,
                    rivalStoreId = ui.rows.firstOrNull { it !== candidate }?.storeId,
                    focusFill = focusFill,
                    focusEdge = focusEdge,
                    showTouchControls = showTouchControls,
                    onChoose = onChooseFocused,
                    onBack = onCloseMoreInfo,
                )
            }
        }
    }
}

@Composable
private fun EmptyState(ui: StorefrontMatchUi) {
    val (headline, detail) = when {
        ui.unavailableStores.isNotEmpty() ->
            "${ui.unavailableStores.joinToString(", ")} didn't answer." to
                "Nothing was changed. This is a connection problem, not a missing game — try again later."
        ui.notApplicable ->
            "This isn't a Windows game." to
                "Storefront identities only exist for PC games. Console ROMs are matched by their file."
        ui.settledLabel != null ->
            "Already matched." to
                "${ui.settledLabel} — there was nothing to choose between. Use Rematch Storefront to change it."
        else ->
            "No store has this game." to
                "Nothing was changed, and nothing is linked. You can try again after editing the title."
    }
    Text(
        headline,
        color = TextPrimary,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(detail, color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 4.dp))
}

@Composable
private fun LocalGameStrip(ui: StorefrontMatchUi) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(RowFill)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Your game", color = TextMuted, fontSize = 11.sp)
            Text(
                ui.gameTitle,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Searched as", color = TextMuted, fontSize = 11.sp)
            Text(
                "“${ui.query}”",
                color = TextPrimary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Notice(text: String, dotColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x0DFFFFFF))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.padding(top = 5.dp).size(6.dp).clip(CircleShape).background(dotColor))
        Text(text, color = TextPrimary, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun CandidateRow(
    row: StorefrontCandidateRow,
    focused: Boolean,
    focusFill: Color,
    focusEdge: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) focusFill else RowFill)
            .then(if (focused) Modifier.border(1.5.dp, focusEdge, RoundedCornerShape(8.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(CapsuleWidth, CapsuleHeight).clip(RoundedCornerShape(4.dp)).background(CapsuleFill),
            contentAlignment = Alignment.Center,
        ) {
            if (row.thumbUrl != null) {
                AsyncImage(model = row.thumbUrl, contentDescription = null, modifier = Modifier.fillMaxSize())
            } else {
                Text(row.storeLabel, color = TextMuted.copy(alpha = 0.6f), fontSize = 9.sp)
            }
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                row.title,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            row.subtitle?.let {
                Text(it, color = TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (row.strongSignals.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.strongSignals.forEach { SignalChip(it) }
                }
            }
        }

        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.idLabel, color = TextMuted, fontSize = 11.sp)
            Text("score ${row.score}", color = TextMuted.copy(alpha = 0.6f), fontSize = 10.sp)
        }
    }
}

@Composable
private fun SignalChip(label: String) {
    Text(
        label,
        color = SignalGreen,
        fontSize = 10.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(SignalGreen.copy(alpha = 0.18f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
private fun NoMatchRow(
    focused: Boolean,
    title: String,
    focusFill: Color,
    focusEdge: Color,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) focusFill else RowFill)
            .then(if (focused) Modifier.border(1.5.dp, focusEdge, RoundedCornerShape(8.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("No correct match", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Leave $title unlinked. Nothing is written, and you can try again later.",
            color = TextMuted,
            fontSize = 11.sp,
        )
    }
}

/**
 * The action row in touch mode.
 *
 * With `showTouchControls` the glyph hint line is gone entirely and these pills ARE the affordance
 * — the house rule for every screen that offers both input styles.
 */
@Composable
private fun TouchActions(
    showTouchControls: Boolean,
    choosable: Boolean,
    onChoose: () -> Unit,
    onMoreInfo: () -> Unit,
    onClose: () -> Unit,
) {
    if (!showTouchControls) return
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (choosable) {
            XmbHeaderPill(label = "Choose", onClick = onChoose)
            XmbHeaderPill(label = "More Information", onClick = onMoreInfo)
        }
        Spacer(Modifier.weight(1f))
        XmbHeaderPill(label = "Cancel", onClick = onClose)
    }
}

/**
 * Why one candidate ranked where it did (Phase 10's "More Information").
 *
 * The scoring signals are shown because the picker is asking the user to arbitrate, and an
 * arbitrator with no evidence is just being made to guess. A signal that was NOT available is
 * listed too — "not compared, your copy has no year" is the line that explains why an otherwise
 * obvious match is still being questioned.
 */
@Composable
private fun StorefrontMoreInfoPanel(
    row: StorefrontCandidateRow,
    tiedOnExactTitle: Boolean,
    rivalStoreId: String?,
    focusFill: Color,
    focusEdge: Color,
    showTouchControls: Boolean,
    onChoose: () -> Unit,
    onBack: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(onClick = onBack),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 320.dp, max = 620.dp)
                .fillMaxWidth(0.92f)
                .heightIn(max = 520.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xF20A0A14))
                .clickable(enabled = false) {}
                .padding(14.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "More Information",
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text("${row.storeLabel}  •  ${row.idLabel}", color = TextMuted, fontSize = 11.sp)
            }

            if (!showTouchControls) {
                Text(
                    "Select  Choose this game  •  B  Back",
                    color = TextMuted.copy(alpha = 0.55f),
                    fontSize = 10.sp,
                )
            }

            Text(row.title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            row.subtitle?.let { Text(it, color = TextMuted, fontSize = 12.sp) }
            row.description?.let {
                Text(it, color = TextMuted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }

            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x14FFFFFF)))

            Text("Why it ranked here", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

            row.signalLines.forEach { line ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (line.counted) RowFill else Color(0x08FFFFFF))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        line.label,
                        color = if (line.counted) TextPrimary else TextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        line.points,
                        color = if (line.counted) SignalGreen else TextMuted.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
                Text("Total", color = TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("${row.score}", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }

            if (tiedOnExactTitle && rivalStoreId != null) {
                Notice(
                    "Still not linked automatically: another store entry ($rivalStoreId) carries the " +
                        "same exact title, and an identical name is not something points are allowed to settle.",
                    AttentionAmber,
                )
            }

            Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showTouchControls) {
                    XmbHeaderPill(label = "Choose this game", onClick = onChoose)
                    XmbHeaderPill(label = "Back", onClick = onBack)
                } else {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(focusFill)
                            .border(1.5.dp, focusEdge, RoundedCornerShape(8.dp))
                            .clickable(onClick = onChoose)
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Choose this game", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(RowFill)
                            .clickable(onClick = onBack)
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Back to matches", color = TextPrimary, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/** One line of the More Information ledger. */
data class SignalLine(val label: String, val points: String, val counted: Boolean)

/** Human wording for a scoring signal, so the enum never reaches the screen. */
fun MatchSignal.describe(): String = when (this) {
    MatchSignal.AUTHORITATIVE_ID -> "Store id — the launcher reported this id itself"
    MatchSignal.EXACT_TITLE -> "Exact title — the normalized names are identical"
    MatchSignal.DEVELOPER -> "Developer — matches the one on your copy"
    MatchSignal.PUBLISHER -> "Publisher — matches the one on your copy"
    MatchSignal.RELEASE_YEAR -> "Release year — matches the one on your copy"
    MatchSignal.EDITION_STRIPPED_TITLE -> "Same game, different edition"
    MatchSignal.PARTIAL_TITLE -> "Partial title — one name contains the other"
    MatchSignal.RELEASE_YEAR_CONFLICT -> "Release year — disagrees with your copy"
}
