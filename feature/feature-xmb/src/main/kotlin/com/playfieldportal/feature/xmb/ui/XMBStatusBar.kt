package com.playfieldportal.feature.xmb.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val StatusTint = Color.White
private val StatusSecondary = Color(0xFFE1DFF3)

@Composable
fun XMBClock(
    modifier: Modifier = Modifier,
) {
    var timeString by remember { mutableStateOf(currentTimeString()) }

    LaunchedEffect(Unit) {
        while (true) {
            timeString = currentTimeString()
            delay(30_000L)
        }
    }

    Text(
        text = timeString,
        color = StatusTint,
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier,
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

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                batteryLevel = if (level >= 0 && scale > 0) (level * 100 / scale) else 0
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
                    tint = StatusTint,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        XmbBluetoothIcon(
            tint = StatusSecondary,
            modifier = Modifier.size(width = 13.dp, height = 18.dp),
        )
        XmbWifiIcon(
            tint = StatusSecondary,
            modifier = Modifier.size(18.dp),
        )
        XmbBatteryIcon(
            level = batteryLevel,
            tint = StatusSecondary,
            modifier = Modifier.size(width = 28.dp, height = 15.dp),
        )
        Text(
            text = "$batteryLevel%",
            color = StatusTint,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun currentTimeString(): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
