package com.playfieldportal.feature.xmb.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.annotation.DrawableRes
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.delay
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

    @DrawableRes fun battery(level: Int, charging: Boolean): Int = when {
        charging       -> R.drawable.ic_status_battery_charging
        level >= 76    -> R.drawable.ic_status_battery_full
        level >= 51    -> R.drawable.ic_status_battery_high
        level >= 26    -> R.drawable.ic_status_battery_medium
        else           -> R.drawable.ic_status_battery_low
    }

    /** Themeable icon slot (theme-kit IconSlots key) matching [battery]'s tiers. */
    fun batterySlotKey(level: Int, charging: Boolean): String = when {
        charging       -> "status_battery_charging"
        level >= 76    -> "status_battery_full"
        level >= 51    -> "status_battery_high"
        level >= 26    -> "status_battery_medium"
        else           -> "status_battery_low"
    }
}

// ── PSP-style full-width status strip ────────────────────────────────────────
//
// Layout:  DATE  ┊  TIME  [bg-task badge]          [BT] [WiFi] [Signal] [Bat] %

@Composable
fun XmbPspStatusStrip(
    sortLabel: String? = null,
    // When the last input was touch, the sort label becomes a tappable chip that cycles the sort
    // order; on controller it stays a plain label (X / Square cycles it).
    showSortButton: Boolean = false,
    onSortTapped: () -> Unit = {},
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
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            dateString = currentDateString()
            timeString = currentTimeString()
            delay(30_000L)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(StripHeight)
            .padding(horizontal = 20.dp),
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
                if (showSortButton) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0x24FFFFFF))
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
    val override = slotKey?.let { com.playfieldportal.core.ui.icons.LocalXmbIconOverrides.current[it] }
    if (override != null) {
        Image(bitmap = override, contentDescription = description, modifier = modifier)
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

private val StripHeight   = 28.dp
private val StripFontSize = 12.sp
private val LowBatteryTint = Color(0xFFFF6B6B)

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun currentTimeString(): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())

private fun currentDateString(): String =
    SimpleDateFormat("MM/dd/yyyy", Locale.getDefault()).format(Date())
