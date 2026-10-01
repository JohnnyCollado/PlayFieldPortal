package com.playfieldportal.feature.xmb.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.PfpNotification
import com.playfieldportal.core.ui.components.ControllerHintBar
import com.playfieldportal.core.ui.components.ControllerPromptItem
import com.playfieldportal.core.ui.theme.LocalPFPColors
import com.playfieldportal.core.domain.model.BackgroundTaskInfo
import com.playfieldportal.core.domain.model.NotificationDetail
import com.playfieldportal.feature.xmb.viewmodel.NotificationPanelState
import com.playfieldportal.feature.xmb.viewmodel.PanelSelection
import com.playfieldportal.feature.xmb.viewmodel.panelSelection
import kotlinx.coroutines.delay

/**
 * The Vita-style notification list: an inset panel dropped from the status strip, holding what is
 * running now and what already happened.
 *
 * It is an overlay anchored under [XmbPspStatusStrip], not a crossbar category — the strip stays
 * visible above it, which is the whole point of dropping from there. Drawn from [LocalPFPColors]
 * like the shared PSP context menu, never from a hardcoded black: the task tray this replaces was
 * pinned to `Color.Black.copy(alpha = 0.88f)` and read as off-theme against every colour scheme.
 */
@Composable
fun NotificationPanel(
    state: NotificationPanelState,
    running: List<BackgroundTaskInfo>,
    history: List<PfpNotification>,
    onRowTapped: (index: Int) -> Unit,
    onOptionsTapped: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** Fades in the controller hint after the idle delay. See `shouldShowNotificationHint`. */
    showHint: Boolean = false,
) {
    val colors = LocalPFPColors.current
    val rows = remember(running, history) { buildNotificationRows(running, history) }
    val listState = rememberLazyListState()

    // One ticking clock for every timestamp in the panel rather than one per row. A minute is
    // exactly the resolution the relative format has, so nothing visibly lags behind it.
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(60_000L)
        }
    }

    LaunchedEffect(state.cursor) {
        if (state.cursor in rows.indices) listState.animateScrollToItem(state.cursor)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0x40000000))
            .clickable(onClick = onDismiss),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                // Clears the status strip: it stays readable above the panel, the way the Vita's
                // list slides down over the home screen and leaves the bar alone.
                .padding(top = PanelTopInset, start = 56.dp, end = 56.dp)
                .fillMaxWidth()
                .height(PanelHeight)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.waveColor.copy(alpha = 0.82f))
                .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(10.dp))
                .clickable(onClick = {}) // consume taps inside the panel so the scrim is not hit
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(rows) { index, row ->
                    when (row) {
                        is NotificationRow.Header -> SectionHeader(row.title)
                        is NotificationRow.Running -> RunningRow(
                            task = row.task,
                            isSelected = index == state.cursor && row.isSelectable,
                            onTap = { onRowTapped(index) },
                        )
                        is NotificationRow.History -> HistoryRow(
                            notification = row.notification,
                            isSelected = index == state.cursor,
                            now = nowMillis,
                            onTap = { onRowTapped(index) },
                        )
                        NotificationRow.Empty -> EmptyRow()
                    }
                }
            }

            // The "(...)" on the panel's lower edge — the touch equivalent of the Options button,
            // opening the same list-level menu (Mark All Read / Clear Read / Clear All).
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Icon(
                    imageVector = Icons.Outlined.MoreHoriz,
                    contentDescription = "Notification options",
                    tint = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onOptionsTapped)
                        .padding(6.dp)
                        .size(20.dp),
                )
            }
        }

        // Bottom-right of the SCREEN, not of the panel: the crossbar's pill is there, so a user
        // who has learned where help appears finds it in the same place here. Nothing on screen
        // otherwise says that Confirm opens a row, that Options marks it read, or that the list
        // has a menu of its own — and the panel is reached by a button most people press once out
        // of curiosity.
        //
        // Same clock, same setting and same 200ms fade as the crossbar's; exit is instant, because
        // a pill lingering through the press it just prompted reads as lag.
        AnimatedVisibility(
            visible = showHint,
            enter = fadeIn(tween(200)),
            exit = ExitTransition.None,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                // The panel is a blocking overlay, so the touch App Drawer button is never up
                // beside it — this pill always takes the lower slot.
                .padding(bottom = HintPillBottomPadding, end = HintPillEndPadding),
        ) {
            NotificationPanelHint(selection = panelSelection(rows, state.cursor))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        color = Color.White.copy(alpha = 0.55f),
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun EmptyRow() {
    Text(
        text = EMPTY_PANEL_MESSAGE,
        color = Color.White.copy(alpha = 0.55f),
        fontSize = 13.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
    )
}

/**
 * A live task: glyph, label, the `n / m` its producer reports, and a bar.
 *
 * A readout unless the producer can stop it: then the cursor may land here and ✕ asks to stop.
 * Once a stop is under way the row greys out and its bar freezes until the producer settles.
 */
@Composable
private fun RunningRow(task: BackgroundTaskInfo, isSelected: Boolean, onTap: () -> Unit) {
    val stopping = task.stopping
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (isSelected) Color.White.copy(alpha = 0.14f) else Color.Transparent)
            .then(if (task.stoppable && !stopping) Modifier.clickable(onClick = onTap) else Modifier)
            .padding(horizontal = 6.dp, vertical = 5.dp),
    ) {
        KindRing(
            glyph = { tint ->
                Icon(
                    imageVector = taskGlyph(task.kind),
                    contentDescription = null,
                    tint = if (stopping) Color.White.copy(alpha = 0.55f) else tint,
                    modifier = Modifier.size(17.dp),
                )
            },
            ringColor = Color.White.copy(alpha = if (stopping) 0.22f else 0.35f),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when {
                        stopping -> "${task.label.trimEnd('…', '.', ' ')} — Stopping…"
                        task.detail != null -> "${task.label} — ${task.detail}"
                        else -> task.label
                    },
                    color = Color.White.copy(alpha = if (stopping) 0.55f else if (isSelected) 0.95f else 0.92f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                task.countLabel?.let {
                    Spacer(Modifier.width(10.dp))
                    Text(it, color = Color.White.copy(alpha = if (stopping) 0.45f else 0.70f), fontSize = 12.sp, maxLines = 1)
                }
            }
            Spacer(Modifier.height(5.dp))
            ProgressBar(task.fraction, frozen = stopping)
        }
    }
}

/**
 * Determinate when the producer reports counts, a sweeping band when it does not.
 *
 * File scanning is the one producer with no total at all — a tree walk does not know its size
 * until it has walked it — so its bar is honestly indeterminate rather than faked (plan section
 * 4.3).
 */
@Composable
private fun ProgressBar(fraction: Float?, frozen: Boolean = false) {
    val track = Color.White.copy(alpha = if (frozen) 0.10f else 0.18f)
    val fill = if (frozen) Color.White.copy(alpha = 0.40f) else LocalPFPColors.current.accentColor
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(track),
    ) {
        // A frozen bar with no count holds a fixed band rather than sweeping: nothing is moving.
        if (frozen && fraction == null) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(BAND_FRACTION).background(fill))
        } else if (fraction != null) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(fill),
            )
        } else {
            val transition = rememberInfiniteTransition(label = "indeterminate")
            val head by transition.animateFloat(
                initialValue = 0f,
                targetValue = 0.72f,
                animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Restart),
                label = "sweep",
            )
            // The band is a fraction of the track that slides across it; laid out as
            // [gap][band] so it needs no measured width of its own.
            Row(Modifier.fillMaxSize()) {
                if (head > 0f) Spacer(Modifier.fillMaxHeight().weight(head))
                Box(
                    Modifier
                        .fillMaxHeight()
                        .weight(BAND_FRACTION)
                        .background(fill),
                )
                val tail = (1f - head - BAND_FRACTION).coerceAtLeast(0.0001f)
                Spacer(Modifier.fillMaxHeight().weight(tail))
            }
        }
    }
}

@Composable
private fun HistoryRow(
    notification: PfpNotification,
    isSelected: Boolean,
    now: Long,
    onTap: () -> Unit,
) {
    val severity = severityColor(notification.severity)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (isSelected) Color.White.copy(alpha = 0.14f) else Color.Transparent)
            // Tap is the whole interaction. There is no held-row menu here: the row does one
            // thing, and this is it.
            .clickable(onClick = onTap)
            .padding(horizontal = 6.dp, vertical = 7.dp),
    ) {
        KindRing(
            glyph = { tint ->
                Icon(
                    imageVector = notificationGlyph(notification.kind),
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(17.dp),
                )
            },
            ringColor = severity,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = notification.displayTitle,
            // Read rows step back rather than disappearing: the history is the record, and a row
            // the user has already seen is still one they may need to find again.
            color = Color.White.copy(alpha = if (notification.isRead) 0.62f else 0.95f),
            fontWeight = if (notification.isRead) FontWeight.Normal else FontWeight.Medium,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // What Confirm opens: a page mark for a Notes sheet, a tally for a Results sheet.
        when (val detail = notification.detail) {
            is NotificationDetail.Notes -> {
                Spacer(Modifier.width(8.dp))
                NotesMark()
            }
            is NotificationDetail.Results -> {
                Spacer(Modifier.width(8.dp))
                TallyChip(detail, read = notification.isRead)
            }
            null -> Unit
        }
        if (!notification.isRead) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(severity),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = notificationTimestamp(notification.createdAt, now),
            color = Color.White.copy(alpha = 0.60f),
            fontSize = 11.sp,
            maxLines = 1,
        )
    }
}

/**
 * The panel's idle prompt bar, on the shared [ControllerHintBar] chrome the crossbar and the App
 * Drawer already use.
 *
 * Open is conditional on there being a history row under the cursor — a pill promising an action
 * that does nothing is worse than a smaller pill, and with only running work on screen the cursor
 * has nowhere to be. Options (the list menu) and Close are always true.
 */
@Composable
private fun NotificationPanelHint(selection: PanelSelection) {
    val items = buildList {
        when (selection) {
            PanelSelection.HISTORY -> add(ControllerPromptItem(GamepadAction.SELECT, "Open"))
            PanelSelection.RUNNING -> add(ControllerPromptItem(GamepadAction.SELECT, "Stop"))
            PanelSelection.NONE -> Unit
        }
        add(ControllerPromptItem(GamepadAction.OPEN_CONTEXT_MENU, "Options"))
        add(ControllerPromptItem(GamepadAction.BACK, "Close"))
    }
    ControllerHintBar(items = items, arrangement = Arrangement.spacedBy(12.dp))
}

/** The circular-ring look of the icon set at list scale: a thin ring around a single-weight glyph. */
@Composable
private fun KindRing(
    glyph: @Composable (Color) -> Unit,
    ringColor: Color,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(ringColor.copy(alpha = 0.22f))
            .border(1.dp, ringColor, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        glyph(Color.White.copy(alpha = 0.92f))
    }
}

// Clears the 28.dp status strip with a little air beneath it.
private val PanelTopInset = 34.dp
private val PanelHeight = 300.dp
private const val BAND_FRACTION = 0.28f
