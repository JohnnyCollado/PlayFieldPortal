package com.playfieldportal.feature.xmb.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.ui.theme.LocalPFPColors
import com.playfieldportal.feature.xmb.viewmodel.BackgroundTaskInfo

@Composable
fun BackgroundTaskTray(
    tasks: List<BackgroundTaskInfo>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPFPColors.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.88f))
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Background Tasks",
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "Close",
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.clickable { onDismiss() },
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            val running   = tasks.filter { !it.isCompleted && !it.isFailed }
            val completed = tasks.filter { it.isCompleted || it.isFailed }

            if (running.isNotEmpty()) {
                running.forEach { task -> TaskRow(task = task, colors = colors) }
            }

            if (completed.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Completed",
                    color = colors.textSecondary,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                completed.forEach { task -> TaskRow(task = task, colors = colors) }
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: BackgroundTaskInfo,
    colors: com.playfieldportal.core.ui.theme.PFPColors,
) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val statusIcon = when {
                task.isFailed    -> "✗"
                task.isCompleted -> "✓"
                else             -> "⟳"
            }
            Text(
                text = "$statusIcon ${task.label}",
                color = when {
                    task.isFailed    -> Color(0xFFFF4444)
                    task.isCompleted -> Color(0xFF44BB44)
                    else             -> colors.textPrimary
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )

            if (task.isFailed && task.errorMessage != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = task.errorMessage,
                    color = Color(0xFFFF8888),
                    fontSize = 10.sp,
                )
            }
        }

        // Progress bar — only for running tasks with known progress
        if (!task.isCompleted && !task.isFailed) {
            Spacer(modifier = Modifier.height(4.dp))
            if (task.progress != null) {
                LinearProgressIndicator(
                    progress = { task.progress },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = colors.accentColor,
                    trackColor = colors.accentColor.copy(alpha = 0.2f),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = colors.accentColor,
                    trackColor = colors.accentColor.copy(alpha = 0.2f),
                )
            }
        }
    }
}
