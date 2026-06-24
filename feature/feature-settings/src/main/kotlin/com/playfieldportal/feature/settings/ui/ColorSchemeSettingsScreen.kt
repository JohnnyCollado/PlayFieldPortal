package com.playfieldportal.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.playfieldportal.core.domain.model.XmbColorScheme
import com.playfieldportal.core.domain.model.displayLabel
import com.playfieldportal.core.domain.model.resolve
import com.playfieldportal.feature.settings.viewmodel.ColorSchemeSettingsViewModel
import java.time.Month
import java.util.Locale

@Composable
fun ColorSchemeSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ColorSchemeSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    SettingsScaffold(
        title    = "Settings",
        subtitle = "Color Scheme",
        onBack   = onBack,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsGroup("XMB Color Scheme")

            XmbColorScheme.values().forEach { scheme ->
                val palette = scheme.resolve(state.month)
                val isActive = scheme == state.activeScheme
                val monthName = Month.of(state.month)
                    .getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())

                SettingsRow(
                    label    = scheme.displayLabel(),
                    sublabel = if (scheme == XmbColorScheme.ORIGINAL) {
                        "Changes with the month — currently $monthName"
                    } else {
                        "Fixed color preset"
                    },
                    onClick  = if (isActive) null else ({ viewModel.selectScheme(scheme) }),
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isActive) {
                                Text("Active", color = SettingsAccent)
                                Spacer(Modifier.width(12.dp))
                            }
                            ColorSwatch(Color(palette.waveColor))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ColorSwatch(color: Color) {
    Row(horizontalArrangement = Arrangement.End) {
        Spacer(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}
