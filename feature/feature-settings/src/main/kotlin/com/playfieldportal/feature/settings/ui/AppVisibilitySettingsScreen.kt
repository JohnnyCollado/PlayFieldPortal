package com.playfieldportal.feature.settings.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.playfieldportal.feature.settings.viewmodel.AppVisibilityViewModel
import com.playfieldportal.feature.settings.viewmodel.HiddenEntry
import com.playfieldportal.feature.settings.viewmodel.HiddenItemGroup

@Composable
fun AppVisibilitySettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AppVisibilityViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    SettingsScaffold(
        title    = "Settings",
        subtitle = "Hidden Items",
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
            SettingsGroup("Hidden Items")

            Text(
                text = "Apps and games you've hidden, grouped by item with the places each is hidden " +
                    "from. Hide items from their △ Options menu; unhide them here.",
                color = SettingsSubtext,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 48.dp, end = 48.dp, bottom = 8.dp),
            )

            if (state.groups.isEmpty()) {
                Text(
                    text = "Nothing is hidden.",
                    color = SettingsSubtext,
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(48.dp),
                )
            } else {
                state.groups.forEach { group ->
                    HiddenItemCard(
                        group = group,
                        onUnhide = { viewModel.unhide(it) },
                        onUnhideAll = { viewModel.unhideAll(group) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HiddenItemCard(
    group: HiddenItemGroup,
    onUnhide: (HiddenEntry) -> Unit,
    onUnhideAll: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        // Item header: icon + label (informational — the actions below are the focus targets).
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (group.icon != null) {
                AsyncImage(
                    model = group.icon,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = group.label,
                color = SettingsText,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
        }
        // One focusable settings row per hidden location, so the controller can walk and
        // activate them like any other settings screen (raw clickable Texts are invisible
        // to the scaffold's focus navigation).
        group.entries.forEach { entry ->
            SettingsValueRow(
                label   = entry.locationLabel,
                value   = "Unhide",
                onClick = { onUnhide(entry) },
            )
        }
        if (group.entries.size > 1) {
            SettingsValueRow(
                label   = "All locations",
                value   = "Unhide all",
                onClick = onUnhideAll,
            )
        }
    }
}
