package com.playfieldportal.feature.xmb.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
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
    @DrawableRes val wifi:      Int = R.drawable.ic_status_wifi
    @DrawableRes val cellular:  Int = R.drawable.ic_status_cellular

    @DrawableRes fun battery(level: Int, charging: Boolean): Int = when {
        charging       -> R.drawable.ic_status_battery_charging
        level >= 76    -> R.drawable.ic_status_battery_full
        level >= 51    -> R.drawable.ic_status_battery_high
        level >= 26    -> R.drawable.ic_status_battery_medium
        else           -> R.drawable.ic_status_battery_low
    }
}

// ── PSP-style full-width status strip ────────────────────────────────────────
//
// Layout:  DATE  ┊  TIME  [bg-task badge]          [BT] [WiFi] [Signal] [Bat] %

@Composable
fun XmbPspStatusStrip(
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
        }

        // ── Right: BT  WiFi  Signal  Battery ──────────────────────────────
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            StatusIcon(XmbStatusIcons.bluetooth, "Bluetooth",     Modifier.size(width = 9.dp,  height = 13.dp))
            StatusIcon(XmbStatusIcons.wifi,      "Wi-Fi",         Modifier.size(width = 15.dp, height = 13.dp))
            StatusIcon(XmbStatusIcons.cellular,  "Signal",        Modifier.size(width = 12.dp, height = 14.dp))
            StatusIcon(
                res         = XmbStatusIcons.battery(batteryLevel, isCharging),
                description = "Battery",
                modifier    = Modifier.size(width = 24.dp, height = 11.dp),
                tint        = if (batteryLevel <= 20 && !isCharging) LowBatteryTint else StripMuted,
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

@Composable
private fun StatusIcon(
    @DrawableRes res: Int,
    description: String,
    modifier: Modifier = Modifier,
    tint: Color = StripMuted,
) {
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

// ── Legacy standalone composables (preserved for any existing call-sites) ─────

@Composable
fun XMBClock(modifier: Modifier = Modifier) {
    var timeString by remember { mutableStateOf(currentTimeString()) }
    LaunchedEffect(Unit) {
        while (true) {
            timeString = currentTimeString()
            delay(30_000L)
        }
    }
    Text(
        text       = timeString,
        color      = Color.White,
        fontSize   = 16.sp,
        fontWeight = FontWeight.Medium,
        modifier   = modifier,
    )
}

@Composable
fun XMBStatusBar(
    backgroundTaskCount: Int,
    onTaskBadgeTapped: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var batteryLevel by remember { mutableIntStateOf(0) }
    var isCharging   by remember { mutableStateOf(false) }

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

    Row(
        modifier              = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        if (backgroundTaskCount > 0) {
            BadgedBox(
                badge    = { Badge { Text(backgroundTaskCount.toString(), fontSize = 9.sp) } },
                modifier = Modifier.clickable(onClick = onTaskBadgeTapped),
            ) {
                Icon(
                    imageVector        = Icons.Default.Refresh,
                    contentDescription = "Background tasks",
                    tint               = Color.White,
                    modifier           = Modifier.size(16.dp),
                )
            }
        }
        StatusIcon(XmbStatusIcons.bluetooth, "Bluetooth", Modifier.size(width = 11.dp, height = 16.dp))
        StatusIcon(XmbStatusIcons.wifi,      "Wi-Fi",     Modifier.size(16.dp))
        StatusIcon(
            res         = XmbStatusIcons.battery(batteryLevel, isCharging),
            description = "Battery",
            modifier    = Modifier.size(width = 28.dp, height = 13.dp),
        )
        Text(text = "$batteryLevel%", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun currentTimeString(): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())

private fun currentDateString(): String =
    SimpleDateFormat("MM/dd/yyyy", Locale.getDefault()).format(Date())
