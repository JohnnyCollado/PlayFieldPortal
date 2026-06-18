package com.playfieldportal.feature.xmb.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.ui.theme.LocalPFPColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask

@Composable
fun XMBStatusBar(
    backgroundTaskCount: Int,
    onTaskBadgeTapped: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = LocalPFPColors.current
    val context = LocalContext.current

    var timeString by remember { mutableStateOf(currentTimeString()) }
    var batteryLevel by remember { mutableIntStateOf(0) }
    var isCharging by remember { mutableStateOf(false) }

    // Clock — updates every 30 seconds, cheap timer
    DisposableEffect(Unit) {
        val timer = Timer()
        timer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() { timeString = currentTimeString() }
        }, 0L, 30_000L)
        onDispose { timer.cancel() }
    }

    // Battery — listen to system broadcast
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                batteryLevel = if (level >= 0 && scale > 0) (level * 100 / scale) else 0
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                             status == BatteryManager.BATTERY_STATUS_FULL
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Background task badge
        if (backgroundTaskCount > 0) {
            BadgedBox(
                badge = {
                    Badge { Text(backgroundTaskCount.toString(), fontSize = 9.sp) }
                },
                modifier = Modifier.clickable { onTaskBadgeTapped() },
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Background tasks",
                    tint = colors.textPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        // Battery indicator
        Text(
            text = "${if (isCharging) "⚡" else "🔋"} $batteryLevel%",
            color = colors.textSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Normal,
        )

        // Clock — styled like PSP XMB top-right clock
        Text(
            text = timeString,
            color = colors.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun currentTimeString(): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
