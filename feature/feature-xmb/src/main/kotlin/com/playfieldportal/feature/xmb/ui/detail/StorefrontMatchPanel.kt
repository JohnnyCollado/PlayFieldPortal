package com.playfieldportal.feature.xmb.ui.detail

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.playfieldportal.core.ui.components.PfpBusyRing
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.playfieldportal.core.domain.text.UiText
import com.playfieldportal.core.ui.components.XmbHeaderPill
import com.playfieldportal.core.ui.text.asString
import com.playfieldportal.core.ui.theme.themedSubText
import com.playfieldportal.core.ui.theme.themedText
import com.playfieldportal.feature.artwork.match.MatchConfidence
import com.playfieldportal.feature.artwork.match.MatchSignal
import com.playfieldportal.feature.xmb.R

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
    onStoreClick: (Int) -> Unit,
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
                    stringResource(R.string.det_store_match_title),
                    color = themedText(TextPrimary),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (ui.hasOtherStores) {
                    // One store on screen at a time; these say which, and which others are waiting.
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ui.stores.forEachIndexed { index, tab ->
                            StoreChip(
                                label = tab.label,
                                shown = index == ui.storeIndex,
                                edge = focusEdge,
                                onClick = { onStoreClick(index) },
                            )
                        }
                    }
                } else {
                    ui.storeLabel?.let {
                        Text(it, color = themedSubText(TextMuted), fontSize = 12.sp)
                    }
                }
            }

            if (!showTouchControls) {
                Text(
                    if (ui.hasOtherStores) stringResource(R.string.det_store_hint_multi)
                    else stringResource(R.string.det_store_hint_single),
                    color = themedSubText(TextMuted.copy(alpha = 0.55f)),
                    fontSize = 10.sp,
                )
            }

            if (ui.loading) {
                Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
                    PfpBusyRing(color = focusEdge)
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

            // One scroll region for everything below the header, taking all the space the action row
            // leaves. See the note in StorefrontRematchPanel for why a `fill = false` weighted list
            // with siblings below it lands them on top of its last row — the picker had the same
            // shape and only escaped it by usually returning two candidates.
            //
            // The strip and the notices scroll WITH the rows rather than sitting pinned above them:
            // on a landscape handheld the card is barely 400dp tall, and pinned they left the list
            // room for about one row.
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LocalGameStrip(ui)

                storefrontOtherStoresNote(ui)?.let { Notice(it.asString(), AttentionAmber) }

                when {
                    ui.confidence == MatchConfidence.AMBIGUOUS && ui.tiedOnExactTitle -> Notice(
                        stringResource(R.string.det_store_notice_tied),
                        AttentionAmber,
                    )
                    // Below the bar PFP would ever link on. Said plainly, because these rows would
                    // otherwise read as recommendations: `Bravely Default` finds `BRAVELY DEFAULT II`,
                    // which is a different game, and only the user knows whether one of these is theirs.
                    ui.confidence == MatchConfidence.LOW -> Notice(
                        stringResource(R.string.det_store_notice_low),
                        AttentionAmber,
                    )
                    else -> Unit
                }

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

/**
 * What an empty picker says, as a headline and the line under it.
 *
 * Pure, so the wording a user acts on can be pinned without a composition. The order is the
 * precedence: a store that could not be reached outranks everything, because "didn't answer" and
 * "doesn't have it" lead to opposite actions.
 */
internal fun storefrontEmptyMessage(ui: StorefrontMatchUi): Pair<UiText, UiText> = when {
    ui.unavailableStores.isNotEmpty() ->
        UiText.res(R.string.det_store_empty_unavailable_title, ui.unavailableStores.joinToString(", ")) to
            UiText.res(R.string.det_store_empty_unavailable_detail)
    ui.notApplicable ->
        UiText.res(R.string.det_store_empty_not_windows_title) to
            UiText.res(R.string.det_store_empty_not_windows_detail)
    ui.settledLabel != null ->
        UiText.res(R.string.det_store_empty_settled_title) to
            UiText.res(R.string.det_store_empty_settled_detail, ui.settledLabel)
    // A name the user typed found nothing. Named, so they can see what was asked for, and pointed
    // back at the search bar rather than at the title: the bar exists so the title can stay.
    ui.typedQuery != null ->
        UiText.res(R.string.det_store_empty_typed_title, ui.typedQuery) to
            UiText.res(R.string.det_store_empty_typed_detail)
    else ->
        UiText.res(R.string.det_store_empty_none_title) to
            UiText.res(R.string.det_store_empty_none_detail)
}

/**
 * What the stores NOT on this list have to say, or null when every store asked is in the tabs.
 *
 * One store having candidates must not speak for the others. A store that timed out is named
 * first, because its absence reads as "that store doesn't have the game" and means the opposite;
 * a store already linked is named so it is not taken for one that was skipped.
 */
internal fun storefrontOtherStoresNote(ui: StorefrontMatchUi): UiText? {
    val unreachable = ui.unavailableStores.takeIf { it.isNotEmpty() && ui.rows.isNotEmpty() }
        ?.joinToString(", ")
        ?.let { names -> UiText.res(R.string.det_store_note_unreachable, names, ui.storeLabel.orEmpty()) }
    val linked = ui.settledStores.takeIf { it.isNotEmpty() }
        ?.joinToString(", ")
        ?.let { names -> UiText.res(R.string.det_store_note_linked, names) }
    val parts = listOfNotNull(unreachable, linked)
    return if (parts.size > 1) UiText.Joined(parts, " ") else parts.firstOrNull()
}

@Composable
private fun EmptyState(ui: StorefrontMatchUi) {
    val (headline, detail) = storefrontEmptyMessage(ui)
    Text(
        headline.asString(),
        color = themedText(TextPrimary),
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(detail.asString(), color = themedSubText(TextMuted), fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 4.dp))
}

/** One store in the header. The shown one is outlined; the others are taps away, or L1/R1. */
@Composable
private fun StoreChip(label: String, shown: Boolean, edge: Color, onClick: () -> Unit) {
    Text(
        label,
        color = themedText(if (shown) TextPrimary else TextMuted),
        fontSize = 12.sp,
        fontWeight = if (shown) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (shown) Color(0x33FFFFFF) else Color(0x14FFFFFF))
            .then(if (shown) Modifier.border(1.dp, edge, RoundedCornerShape(50)) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
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
            Text(stringResource(R.string.det_store_your_game), color = themedSubText(TextMuted), fontSize = 11.sp)
            Text(
                ui.gameTitle,
                color = themedText(TextPrimary),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.det_store_searched_as), color = themedSubText(TextMuted), fontSize = 11.sp)
            Text(
                stringResource(R.string.det_store_quoted, ui.query),
                color = themedText(TextPrimary),
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
        Text(text, color = themedText(TextPrimary), fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CandidateRow(
    row: StorefrontCandidateRow,
    focused: Boolean,
    focusFill: Color,
    focusEdge: Color,
    onClick: () -> Unit,
) {
    // Keyed on the candidate too: switching store keeps the cursor on row 0, and row 0 of the new
    // store must still be framed, from wherever the last store's list was scrolled to.
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(focused, row.store, row.storeId) { if (focused) requester.bringIntoView() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester)
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
                Text(row.storeLabel, color = themedSubText(TextMuted.copy(alpha = 0.6f)), fontSize = 9.sp)
            }
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                row.title,
                color = themedText(TextPrimary),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            row.subtitle?.let {
                Text(it, color = themedSubText(TextMuted), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (row.strongSignals.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.strongSignals.forEach { SignalChip(it.asString()) }
                }
            }
        }

        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.idLabel.asString(), color = themedSubText(TextMuted), fontSize = 11.sp)
            Text(stringResource(R.string.det_store_score, row.score), color = themedSubText(TextMuted.copy(alpha = 0.6f)), fontSize = 10.sp)
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoMatchRow(
    focused: Boolean,
    title: String,
    focusFill: Color,
    focusEdge: Color,
    onClick: () -> Unit,
) {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(focused) { if (focused) requester.bringIntoView() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) focusFill else RowFill)
            .then(if (focused) Modifier.border(1.5.dp, focusEdge, RoundedCornerShape(8.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(stringResource(R.string.det_store_no_match), color = themedText(TextPrimary), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            stringResource(R.string.det_store_no_match_detail, title),
            color = themedSubText(TextMuted),
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
            XmbHeaderPill(label = stringResource(R.string.det_store_choose), onClick = onChoose)
            XmbHeaderPill(label = stringResource(R.string.det_store_more_info), onClick = onMoreInfo)
        }
        Spacer(Modifier.weight(1f))
        XmbHeaderPill(label = stringResource(R.string.det_cancel), onClick = onClose)
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
                    stringResource(R.string.det_store_more_info),
                    color = themedText(TextPrimary),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text("${row.storeLabel}  •  ${row.idLabel.asString()}", color = themedSubText(TextMuted), fontSize = 11.sp)
            }

            if (!showTouchControls) {
                Text(
                    stringResource(R.string.det_store_hint_more_info),
                    color = themedSubText(TextMuted.copy(alpha = 0.55f)),
                    fontSize = 10.sp,
                )
            }

            Text(row.title, color = themedText(TextPrimary), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            row.subtitle?.let { Text(it, color = themedSubText(TextMuted), fontSize = 12.sp) }
            row.description?.let {
                Text(it, color = themedSubText(TextMuted), fontSize = 12.sp, lineHeight = 17.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }

            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x14FFFFFF)))

            Text(stringResource(R.string.det_store_why_ranked), color = themedText(TextPrimary), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

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
                        line.label.asString(),
                        color = themedText(if (line.counted) TextPrimary else TextMuted),
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        line.points,
                        color = if (line.counted) SignalGreen else themedSubText(TextMuted.copy(alpha = 0.6f)),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
                Text(stringResource(R.string.det_store_total), color = themedSubText(TextMuted), fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("${row.score}", color = themedText(TextPrimary), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }

            if (tiedOnExactTitle && rivalStoreId != null) {
                Notice(
                    stringResource(R.string.det_store_notice_rival, rivalStoreId),
                    AttentionAmber,
                )
            }

            Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showTouchControls) {
                    XmbHeaderPill(label = stringResource(R.string.det_store_choose_this), onClick = onChoose)
                    XmbHeaderPill(label = stringResource(R.string.det_back), onClick = onBack)
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
                        Text(stringResource(R.string.det_store_choose_this), color = themedText(TextPrimary), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
                        Text(stringResource(R.string.det_store_back_to_matches), color = themedText(TextPrimary), fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/** One line of the More Information ledger. */
data class SignalLine(val label: UiText, val points: String, val counted: Boolean)

/** Human wording for a scoring signal, so the enum never reaches the screen. */
fun MatchSignal.describe(): UiText = UiText.res(
    when (this) {
        MatchSignal.AUTHORITATIVE_ID -> R.string.det_signal_desc_store_id
        MatchSignal.EXACT_TITLE -> R.string.det_signal_desc_exact_title
        MatchSignal.DEVELOPER -> R.string.det_signal_desc_developer
        MatchSignal.PUBLISHER -> R.string.det_signal_desc_publisher
        MatchSignal.RELEASE_YEAR -> R.string.det_signal_desc_year
        MatchSignal.EDITION_STRIPPED_TITLE -> R.string.det_signal_desc_other_edition
        MatchSignal.PARTIAL_TITLE -> R.string.det_signal_desc_partial_title
        MatchSignal.RELEASE_YEAR_CONFLICT -> R.string.det_signal_desc_year_conflict
    },
)
