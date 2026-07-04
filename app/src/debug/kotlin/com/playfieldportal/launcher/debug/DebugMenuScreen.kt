package com.playfieldportal.launcher.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.layout.Box
import kotlinx.coroutines.launch

// Accessed by long-pressing the Settings category icon in debug builds.
// Zero release footprint — entire file excluded from release APK.
@Composable
fun DebugMenuScreen(
    onDismiss: () -> Unit,
    viewModel: DebugMenuViewModel = hiltViewModel(),
) {
    val state by viewModel.debugState.collectAsState()
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.95f)),
    ) {
        // ── Left panel — controls ─────────────────────────────────────────
        Column(
            modifier = Modifier
                .width(480.dp)
                .fillMaxHeight()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DebugHeader(onDismiss = onDismiss)
            HorizontalDivider(color = Color.White.copy(alpha = 0.15f))

            // ── Data source ───────────────────────────────────────────────
            DebugSection("DATA SOURCE")
            DebugToggle(
                label    = "Use fake data",
                sublabel = "Off = real Room DB",
                checked  = state.useFakeData,
                onToggle = { viewModel.setUseFakeData(it) },
            )

            // ── Scenarios ─────────────────────────────────────────────────
            DebugSection("SCENARIO")
            DebugScenario.values().forEach { scenario ->
                DebugChip(
                    label     = scenario.label,
                    isSelected = state.scenario == scenario,
                    onClick   = {
                        scope.launch { viewModel.reseed(scenario) }
                    },
                )
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.15f))

            // ── Wave control ──────────────────────────────────────────────
            DebugSection("WAVE RENDER MODE")
            ForceWaveMode.values().forEach { mode ->
                DebugChip(
                    label      = mode.label,
                    isSelected = state.forceWaveMode == mode,
                    onClick    = { viewModel.setForceWaveMode(mode) },
                )
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.15f))

            // ── Thermal simulation ────────────────────────────────────────
            DebugSection("THERMAL SIMULATION")
            SimulatedThermal.values().forEach { thermal ->
                DebugChip(
                    label      = thermal.label,
                    isSelected = state.simulatedThermalStatus == thermal,
                    onClick    = { viewModel.setSimulatedThermal(thermal) },
                )
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.15f))

            // ── UI triggers ───────────────────────────────────────────────
            DebugSection("UI TRIGGERS")
            DebugToggle(
                label    = "Show task tray",
                checked  = state.showTaskTray,
                onToggle = { viewModel.setShowTaskTray(it) },
            )
            DebugToggle(
                label    = "Show boot on resume",
                checked  = state.showBootOnNextLaunch,
                onToggle = { viewModel.setShowBootOnNextLaunch(it) },
            )
            DebugToggle(
                label    = "Performance overlay",
                checked  = state.showPerfOverlay,
                onToggle = { viewModel.setShowPerfOverlay(it) },
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.15f))

            // ── Reset ─────────────────────────────────────────────────────
            Button(
                onClick = { viewModel.reset() },
                colors  = ButtonDefaults.buttonColors(containerColor = Color(0xFF880000)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Reset All Debug Settings", color = Color.White)
            }

            Spacer(modifier = Modifier.height(32.dp))
        }

        // ── Right panel — live XMB preview ────────────────────────────────
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(16.dp),
        ) {
            Text(
                text     = "LIVE PREVIEW",
                color    = Color.White.copy(alpha = 0.4f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            // Live XMB shell preview driven by current debug state
            com.playfieldportal.feature.xmb.ui.XMBShell(
                uiState = viewModel.previewState,
            )
        }
    }

    }
}

// ── Sub-components ─────────────────────────────────────────────────────────────

@Composable
private fun DebugHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text       = "PFP DEBUG MENU",
                color      = Color(0xFFFFCC00),
                fontSize   = 16.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text     = "Long-press Settings to access · Debug builds only",
                color    = Color.White.copy(alpha = 0.4f),
                fontSize = 10.sp,
            )
        }
        Text(
            text     = "✕ Close",
            color    = Color.White.copy(alpha = 0.6f),
            fontSize = 12.sp,
            modifier = Modifier.clickable { onDismiss() },
        )
    }
}

@Composable
private fun DebugSection(title: String) {
    Text(
        text       = title,
        color      = Color(0xFFFFCC00).copy(alpha = 0.7f),
        fontSize   = 10.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 1.sp,
        modifier   = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun DebugToggle(
    label: String,
    sublabel: String? = null,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(label, color = Color.White, fontSize = 13.sp)
            if (!sublabel.isNullOrBlank()) {
                Text(sublabel, color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp)
            }
        }
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}

@Composable
private fun DebugChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = if (isSelected) "▶ $label" else "   $label",
        color = if (isSelected) Color(0xFFFFCC00) else Color.White.copy(alpha = 0.6f),
        fontSize = 12.sp,
        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 3.dp, horizontal = 4.dp),
    )
}
