package com.playfieldportal.feature.xmb.ui.detail

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.feature.xmb.ui.SearchGlyph

// ── Rematch Storefront Metadata (C23 T6, Phase 18) ───────────────────────────
// One row per store: what it is linked to, and what can be done about it. Every row is a single
// focus stop; the per-row action is chosen with Left/Right and taken with Select, so a controller
// never has to reach three targets on one line.

private val TextPrimary = Color(0xFFEEEEEE)
private val TextMuted = Color(0xAAEEEEEE)
private val RowFill = Color(0xFF1B1B26)
private val ConfirmedGreen = Color(0xFF45C46A)

@Composable
fun StorefrontRematchPanel(
    ui: StorefrontRematchUi,
    focusFill: Color,
    focusEdge: Color,
    showTouchControls: Boolean,
    onRowClick: (Int) -> Unit,
    onActionClick: (Int, RematchAction) -> Unit,
    onSearchAll: () -> Unit,
    onQueryChange: (String) -> Unit,
    onStartQueryEdit: () -> Unit,
    onSearchByName: () -> Unit,
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
                    "Rematch Storefront Metadata",
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(ui.gameTitle, color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            if (!showTouchControls) {
                Text(
                    // The hints follow the stop: the bar has no Left/Right, and while it is taking
                    // text the keyboard's own Search key is the confirm.
                    when {
                        ui.editingQuery -> "Keyboard Search  Find matches  •  B  Done typing"
                        ui.queryFocused -> "Up/Down  Rows  •  Select  Type a name  •  B  Close"
                        else -> "Up/Down  Rows  •  Left/Right  Action  •  Select  Confirm  •  B  Close"
                    },
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

            // Pinned above the scroll region: it is the way out of a bad match, so it must not
            // scroll away with the rows it sits over.
            NameSearchBar(
                ui = ui,
                focusFill = focusFill,
                focusEdge = focusEdge,
                onQueryChange = onQueryChange,
                onStartEdit = onStartQueryEdit,
                onSearch = onSearchByName,
            )

            // The rows AND the note share one scroll region, and the region takes all the space the
            // pinned button leaves (fill = true, not `fill = false`).
            //
            // The bug this shape fixes: as a sibling BELOW a `fill = false` weighted list, the note
            // was placed at the boundary of the space the list was granted — so as soon as the rows
            // plus the note outgrew the card's 540dp cap, the note landed on top of the LAST row.
            // The first row could never show it, which is why Steam looked right and Epic did not.
            // Explanatory text is content, not chrome, so it belongs in the scroll region with the
            // thing it explains, where it cannot be positioned over one.
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ui.rows.forEachIndexed { index, row ->
                    StoreRow(
                        row = row,
                        focused = !ui.queryFocused && ui.focus == index,
                        focusFill = focusFill,
                        focusEdge = focusEdge,
                        showTouchControls = showTouchControls,
                        onClick = { onRowClick(index) },
                        onAction = { onActionClick(index, it) },
                    )
                }

                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x14FFFFFF)).padding(top = 2.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x0DFFFFFF))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.padding(top = 5.dp).size(6.dp).clip(CircleShape).background(ConfirmedGreen))
                    Text(
                        "Removing a link removes the link and nothing else. Your description, developer and " +
                            "every other field stay exactly as they are — including anything you typed by hand.",
                        color = TextPrimary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                }
            }

            val searchFocused = !ui.queryFocused && ui.focus == ui.searchAllIndex
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (searchFocused) focusFill else RowFill)
                    .then(if (searchFocused) Modifier.border(1.5.dp, focusEdge, RoundedCornerShape(8.dp)) else Modifier)
                    .clickable(onClick = onSearchAll)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (ui.searching) "Searching…" else "Search every store again",
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * A name to search the stores by.
 *
 * The ViewModel owns whether the bar is taking text ([StorefrontRematchUi.editingQuery]); this
 * follows it, raising the keyboard when it starts and dropping it when it ends. Read-only until
 * then, so a controller resting on the bar never opens a keyboard the user did not ask for.
 */
@Composable
private fun NameSearchBar(
    ui: StorefrontRematchUi,
    focusFill: Color,
    focusEdge: Color,
    onQueryChange: (String) -> Unit,
    onStartEdit: () -> Unit,
    onSearch: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val editing by rememberUpdatedState(ui.editingQuery)

    LaunchedEffect(ui.editingQuery) {
        if (ui.editingQuery) {
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
        } else {
            focusManager.clearFocus()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (ui.queryFocused) focusFill else RowFill)
                .then(
                    if (ui.queryFocused) Modifier.border(1.5.dp, focusEdge, RoundedCornerShape(8.dp))
                    else Modifier
                )
                .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchGlyph()
            BasicTextField(
                value = ui.query,
                onValueChange = onQueryChange,
                readOnly = !ui.editingQuery,
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                cursorBrush = SolidColor(focusEdge),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }, onDone = { onSearch() }),
                decorationBox = { inner ->
                    Box {
                        if (ui.query.isEmpty()) {
                            Text("Search by name", color = TextMuted.copy(alpha = 0.6f), fontSize = 14.sp)
                        }
                        inner()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    // A tap focuses the field; that is touch asking to type, so enter edit mode.
                    .onFocusChanged { if (it.isFocused && !editing) onStartEdit() },
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x14FFFFFF))
                    .clickable(enabled = ui.canSearchByName && !ui.searching, onClick = onSearch)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    if (ui.searching) "Searching…" else "Search",
                    color = if (ui.canSearchByName) TextPrimary else TextMuted.copy(alpha = 0.45f),
                    fontSize = 12.sp,
                )
            }
        }
        Text(
            "Searches the stores for this name. Your game's title is not changed.",
            color = TextMuted.copy(alpha = 0.6f),
            fontSize = 10.sp,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StoreRow(
    row: StorefrontRematchRow,
    focused: Boolean,
    focusFill: Color,
    focusEdge: Color,
    showTouchControls: Boolean,
    onClick: () -> Unit,
    onAction: (RematchAction) -> Unit,
) {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(focused) { if (focused) requester.bringIntoView() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) focusFill else RowFill)
            .then(if (focused) Modifier.border(1.5.dp, focusEdge, RoundedCornerShape(8.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(row.storeLabel, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (row.userConfirmed) {
                    Text(
                        "Confirmed by you",
                        color = ConfirmedGreen,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(ConfirmedGreen.copy(alpha = 0.2f))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
            Text(row.detail, color = TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            row.note?.let { Text(it, color = TextMuted.copy(alpha = 0.6f), fontSize = 10.sp) }
        }

        // The actions this row offers. On a controller only the selected one is highlighted —
        // Left/Right moves between them and Select takes it, so the row stays one focus stop.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.actions.forEach { action ->
                val active = focused && action == row.selectedAction
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (active) Color(0x33FFFFFF) else Color(0x14FFFFFF))
                        .then(
                            if (active && !showTouchControls) Modifier.border(1.5.dp, focusEdge, RoundedCornerShape(8.dp))
                            else Modifier
                        )
                        .clickable(enabled = row.enabled) { onAction(action) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        action.label,
                        color = if (row.enabled) TextPrimary else TextMuted.copy(alpha = 0.45f),
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}
