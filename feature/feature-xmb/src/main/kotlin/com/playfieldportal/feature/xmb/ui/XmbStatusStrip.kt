package com.playfieldportal.feature.xmb.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.feature.xmb.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── Status bar colours ────────────────────────────────────────────────────────

private val StripPrimary = Color(0xFFEEEEEE)
private val StripMuted   = Color(0xAAEEEEEE)
private val StripSep     = Color(0x55FFFFFF)

// ── Centralized asset mapping ─────────────────────────────────────────────────
//
// All status-bar drawables come from anthonycaccese/xmb-menu-es-de _inc/images/.
// Never reference these resource IDs outside this file; go through XmbStatusIcons.

object XmbStatusIcons {
    @DrawableRes val bluetooth: Int = R.drawable.ic_status_bluetooth

    /**
     * Tier thresholds live in [batterySlotKey] and the key→drawable table in [forSlotKey];
     * delegating through both keeps the mapping single-sourced — [forSlotKey]'s
     * compile-time-checked drawable refs plus DefaultSlotGlyphTest guard the pair.
     */
    @DrawableRes fun battery(level: Int, charging: Boolean): Int =
        requireNotNull(forSlotKey(batterySlotKey(level, charging))) {
            "batterySlotKey produced a key outside the status strip"
        }

    /** Themeable icon slot (theme-kit IconSlots key) matching [battery]'s tiers. */
    fun batterySlotKey(level: Int, charging: Boolean): String = when {
        charging       -> "status_battery_charging"
        level >= 76    -> "status_battery_full"
        level >= 51    -> "status_battery_high"
        level >= 26    -> "status_battery_medium"
        else           -> "status_battery_low"
    }

    /**
     * Built-in drawable behind a `status_*` slot key, or null when [slotKey] is not a status
     * slot. The icon customizer previews slot defaults through this rather than reaching for
     * the resource IDs directly, which stay private to this file.
     */
    @DrawableRes fun forSlotKey(slotKey: String): Int? = when (slotKey) {
        "status_bluetooth"         -> R.drawable.ic_status_bluetooth
        "status_battery_charging"  -> R.drawable.ic_status_battery_charging
        "status_battery_full"      -> R.drawable.ic_status_battery_full
        "status_battery_high"      -> R.drawable.ic_status_battery_high
        "status_battery_medium"    -> R.drawable.ic_status_battery_medium
        "status_battery_low"       -> R.drawable.ic_status_battery_low
        else                       -> null
    }
}

// ── PSP-style full-width status strip ────────────────────────────────────────
//
// Layout:  DATE  ┊  TIME  ┊  SORT        [🔔] [Ctrl] [BT] [WiFi] [Signal] [Bat] %
//
// The bell LEADS the right group, and that placement is the point rather than an accident: every
// icon behind it is conditional — a controller connects, Bluetooth goes off, cellular drops — so
// any later position would slide around as hardware comes and goes. First means fixed, which is
// what a button needs and a status readout does not.
//
// (The left group's old comment promised a "[bg-task badge]" slot that was never implemented and
// is not what this plan used; the notification button is the right group's, not the left's.)

@Composable
fun XmbPspStatusStrip(
    sortLabel: String? = null,
    // True when the last input was touch. Both tappable things in this bar are gated on it: the
    // sort label becomes a chip that cycles the order, and the bell becomes a button that opens
    // the notification panel. On a controller both fall back to plain readouts, because X cycles
    // the sort and START opens the panel — and the idle hint pill names both.
    showTouchControls: Boolean = false,
    onSortTapped: () -> Unit = {},
    // Unread history entries, and whether any background work is running right now — the bell's
    // two "something happened" states. See [NotificationButton].
    unreadNotifications: Int = 0,
    notificationsRunning: Boolean = false,
    onNotificationsTapped: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var batteryLevel   by remember { mutableIntStateOf(0) }
    var isCharging     by remember { mutableStateOf(false) }
    var dateString     by remember { mutableStateOf(currentDateString()) }
    var timeString     by remember { mutableStateOf(currentTimeString()) }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val level  = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale  = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                batteryLevel = if (level >= 0 && scale > 0) (level * 100 / scale) else 0
                isCharging   = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                               status == BatteryManager.BATTERY_STATUS_FULL
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    // The clock was a 30s poll, which made it wrong in two ways. A timezone or manual time change
    // was invisible until the next tick, and — permanently — the poll ran at whatever phase the
    // composable happened to start at, so the displayed minute rolled over up to 30s late even
    // when nothing changed.
    //
    // ACTION_TIME_TICK is the platform's answer to both: the system broadcasts it ON the minute
    // boundary to registered receivers only (it cannot be declared in a manifest), which is what
    // the system status bar itself listens to. TIME_SET and TIMEZONE_CHANGED land an explicit
    // change immediately; LOCALE_CHANGED keeps the 12/24-hour and date formats honest.
    DisposableEffect(Unit) {
        val clockReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                dateString = currentDateString()
                timeString = currentTimeString()
            }
        }
        ContextCompat.registerReceiver(
            context,
            clockReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_LOCALE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(clockReceiver) }
    }

    // TIME_TICK is not delivered while the process is backgrounded, so the strip can come back
    // holding a value up to a minute stale and then wait a further minute for the next tick. The
    // composition survives ON_STOP (this is the home app), so a DisposableEffect keyed on Unit
    // would not re-run to cover it — the resume itself has to recompute.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                dateString = currentDateString()
                timeString = currentTimeString()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(XmbStatusStripHeight)
            .padding(horizontal = XmbStatusStripSidePadding),
        verticalAlignment    = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // ── Left: date  ┊  time  [bg task badge] ──────────────────────────
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(dateString, color = StripMuted,   fontSize = StripFontSize, fontWeight = FontWeight.Normal)
            StripSeparator()
            Text(timeString, color = StripPrimary, fontSize = StripFontSize, fontWeight = FontWeight.Medium)
            // Current sort mode — shown only on sortable lists. Touch: a tappable chip that cycles
            // the sort order; controller: a plain label (X / Square cycles it).
            if (sortLabel != null) {
                StripSeparator()
                if (showTouchControls) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .clip(PillShape)
                            .background(PillFill)
                            .clickable(onClick = onSortTapped)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("⇅", color = StripPrimary, fontSize = StripFontSize, fontWeight = FontWeight.Medium)
                        Text(sortLabel, color = StripPrimary, fontSize = StripFontSize, fontWeight = FontWeight.Medium)
                    }
                } else {
                    Text(sortLabel, color = StripPrimary, fontSize = StripFontSize, fontWeight = FontWeight.Medium)
                }
            }
        }

        // ── Right: [controller] [BT] [WiFi] [Signal] [Battery] ─────────────
        // Every status icon except battery is conditional: shown only when that hardware is
        // present/active (controller connected, Bluetooth on, Wi-Fi connected, cellular service),
        // and Wi-Fi/Signal reflect live strength. Battery is always shown.
        val sys = rememberSystemStatus()
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            NotificationButton(
                unread = unreadNotifications,
                running = notificationsRunning,
                tappable = showTouchControls,
                onTap = onNotificationsTapped,
            )
            if (sys.controllerConnected) {
                Icon(
                    imageVector        = Icons.Filled.SportsEsports,
                    contentDescription = "Controller connected",
                    tint               = StripMuted,
                    modifier           = Modifier.size(15.dp),
                )
            }
            if (sys.bluetoothOn) {
                StatusIcon(
                    XmbStatusIcons.bluetooth, "Bluetooth", Modifier.size(width = 9.dp, height = 13.dp),
                    slotKey = "status_bluetooth",
                )
            }
            sys.wifiLevel?.let { level ->
                WifiMeter(level, Modifier.size(width = 16.dp, height = 13.dp))
            }
            sys.cellularLevel?.let { level ->
                SignalBars(level, Modifier.size(width = 14.dp, height = 13.dp))
            }
            StatusIcon(
                res         = XmbStatusIcons.battery(batteryLevel, isCharging),
                description = "Battery",
                modifier    = Modifier.size(width = 24.dp, height = 11.dp),
                tint        = if (batteryLevel <= 20 && !isCharging) LowBatteryTint else StripMuted,
                slotKey     = XmbStatusIcons.batterySlotKey(batteryLevel, isCharging),
            )
            Text(
                text       = "$batteryLevel%",
                color      = if (batteryLevel <= 20 && !isCharging) LowBatteryTint else StripPrimary,
                fontSize   = StripFontSize,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * The bell: a tappable pill under touch, a plain readout under a controller.
 *
 * Exactly the sort chip's rule, for the same reason. A pill says *pressable* before the glyph
 * inside it says anything — which is what this needs on touch, where it is the only interactive
 * element in a group of passive indicators. But under a controller there is nothing to press: the
 * panel opens on START, so a permanent button-looking surface would be advertising a target the
 * user cannot aim at, which is what the sort chip's own gate exists to avoid.
 *
 * The controller half of the affordance is the idle hint pill, which names START ▸ Notifications.
 *
 * Never hidden in either mode. At zero unread with nothing running the bell sits dimmed at
 * [StripMuted] — present and quiet. Hiding it would remove the only thing advertising that the
 * panel exists, and a user on a HOME-screen device has no shade habit to fall back on.
 *
 * The glyph is Material's bell for now; `status_notifications` becomes a themeable slot alongside
 * the other `status_*` icons once its art lands (plan section 5).
 */
@Composable
private fun NotificationButton(
    unread: Int,
    running: Boolean,
    tappable: Boolean,
    onTap: () -> Unit,
) {
    // The count wins when both apply: "3 unread" is more actionable than "something is running",
    // and the running state is already spelled out by a live bar inside the panel.
    val active = unread > 0 || running
    val tint = if (active) StripPrimary else StripMuted
    // Idle sits a step under the sort chip's 0x24 so a bell with nothing to say recedes without
    // losing its shape; active matches the chip exactly.
    val fill = if (active) PillFill else PillFillIdle
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = if (tappable) {
            Modifier
                .clip(PillShape)
                .background(fill)
                .clickable(onClick = onTap)
                .padding(horizontal = 8.dp, vertical = 2.dp)
        } else {
            // No surface and no click target: it sits with the other status icons, spaced by the
            // group's own 7.dp rather than carrying the chip's internal padding.
            Modifier
        },
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Icon(
                imageVector        = Icons.Filled.Notifications,
                contentDescription = if (unread > 0) "Notifications, $unread unread" else "Notifications",
                tint               = tint,
                modifier           = Modifier.size(13.dp),
            )
            // The activity mark: a small dot riding the bell while work is in flight, shown only
            // when there is no count to show instead.
            if (running && unread == 0) {
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(StripPrimary),
                )
            }
        }
        if (unread > 0) {
            Text(
                text       = unread.toString(),
                color      = StripPrimary,
                fontSize   = StripFontSize,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

// ── Signal-strength meters (theme-neutral white, level-aware) ──────────────────
//
// Both draw [level] (0..4) as filled vs dimmed segments so the strength reads at a glance. Drawn on
// Canvas rather than shipping five drawables each, and tinted from the strip palette so they sit
// with the rest of the bar.

private val MeterActive   = StripPrimary
private val MeterInactive = Color(0x40EEEEEE)

// Four ascending vertical bars — the classic cellular meter.
@Composable
private fun SignalBars(level: Int, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val bars = 4
        val gap = size.width * 0.14f
        val barWidth = (size.width - gap * (bars - 1)) / bars
        for (i in 0 until bars) {
            val barHeight = size.height * (0.35f + 0.65f * (i + 1) / bars)
            val x = i * (barWidth + gap)
            val top = size.height - barHeight
            drawRect(
                color = if (i < level) MeterActive else MeterInactive,
                topLeft = Offset(x, top),
                size = Size(barWidth, barHeight),
            )
        }
    }
}

// Wi-Fi "fan": a base dot plus three nested arcs; segments above [level] are dimmed. Level maps as
// dot = 1, +arc = 2, ++arc = 3, +++arc = 4 (0 = all dimmed).
@Composable
private fun WifiMeter(level: Int, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height * 0.92f
        val maxR = size.height * 0.9f
        val stroke = size.height * 0.11f

        fun color(threshold: Int) = if (level >= threshold) MeterActive else MeterInactive

        // Base dot (level ≥ 1).
        drawCircle(color = color(1), radius = stroke * 1.1f, center = Offset(cx, cy))
        // Three arcs sweeping upward, growing outward (levels 2, 3, 4).
        for (i in 1..3) {
            val r = maxR * i / 3f
            drawArc(
                color = color(i + 1),
                startAngle = 225f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(cx - r, cy - r),
                size = Size(r * 2, r * 2),
                style = Stroke(width = stroke),
            )
        }
    }
}

@Composable
private fun StatusIcon(
    @DrawableRes res: Int,
    description: String,
    modifier: Modifier = Modifier,
    tint: Color = StripMuted,
    // Themeable slot: a theme's custom status icon renders as-authored (untinted), like
    // every other icon slot. Null = not themeable (meters drawn on Canvas have no slot).
    slotKey: String? = null,
) {
    val override = slotKey?.let { key ->
        com.playfieldportal.core.ui.icons.LocalCustomIcons.current[key]
            ?: com.playfieldportal.core.ui.icons.LocalXmbIconOverrides.current[key]
    }
    if (override != null) {
        com.playfieldportal.core.ui.icons.CustomIconSurface(
            icon = override,
            contentDescription = description,
            modifier = modifier,
        )
        return
    }
    Image(
        painter            = painterResource(res),
        contentDescription = description,
        colorFilter        = ColorFilter.tint(tint),
        modifier           = modifier,
    )
}

@Composable
private fun StripSeparator() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(10.dp)
            .background(StripSep),
    )
}

/**
 * Height of the status strip, measured from the top of the screen — it sits flush there, so this
 * is also the Y of the first pixel below it.
 *
 * Internal rather than private because an overlay that wants to sit just clear of the strip
 * (the Games search field) should derive its inset from the real height instead of copying the
 * number, which is how two values drift apart.
 */
internal val XmbStatusStripHeight = 28.dp

/**
 * The strip's side inset — where its content actually starts and ends. Shared for the same reason
 * as the height: an overlay tucked under the strip lines its edge up with the strip's, and two
 * right edges that nearly agree look like a bug rather than a decision.
 */
internal val XmbStatusStripSidePadding = 20.dp
private val StripFontSize = 12.sp

// The strip's pill: one shape and one fill, shared by the sort chip and the notification button so
// the two tappable things in this bar cannot drift into looking like different kinds of control.
private val PillShape    = RoundedCornerShape(6.dp)
private val PillFill     = Color(0x24FFFFFF)
private val PillFillIdle = Color(0x14FFFFFF)
private val LowBatteryTint = Color(0xFFFF6B6B)

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun currentTimeString(): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())

private fun currentDateString(): String =
    SimpleDateFormat("MM/dd/yyyy", Locale.getDefault()).format(Date())
