package com.playfieldportal.feature.settings.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.playfieldportal.feature.settings.viewmodel.AppVisibilityViewModel

@Composable
fun AppVisibilitySettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AppVisibilityViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    SettingsScaffold(
        title    = "Settings",
        subtitle = "Hidden Apps",
        onBack   = onBack,
        modifier = modifier,
    ) {
        if (state.loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = SettingsAccent)
            }
            return@SettingsScaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsGroup("App Visibility")

            Text(
                text = "Turn an app off to hide it from every XMB category; turn it back on to show " +
                    "it again. ${state.hiddenCount} app${if (state.hiddenCount == 1) "" else "s"} hidden.",
                color = SettingsSubtext,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 48.dp, end = 48.dp, bottom = 8.dp),
            )

            state.apps.forEach { app ->
                SettingsToggleRow(
                    label    = app.label,
                    sublabel = if (app.hidden) "Hidden" else null,
                    leading  = {
                        AsyncImage(
                            model              = app.icon,
                            contentDescription = null,
                            contentScale       = ContentScale.Fit,
                            modifier           = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                    },
                    // Switch reads as "visible": ON = shown, OFF = hidden.
                    checked  = !app.hidden,
                    onToggle = { visible -> viewModel.setHidden(app.packageName, !visible) },
                )
            }

            if (state.apps.isEmpty()) {
                Text(
                    text = "No apps found.",
                    color = SettingsSubtext,
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(48.dp),
                )
            }
        }
    }
}
