package com.playfieldportal.feature.settings.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.playfieldportal.core.data.repository.PfpThemeStore
import com.playfieldportal.feature.settings.viewmodel.ThemeListItem
import com.playfieldportal.feature.settings.viewmodel.ThemesSettingsViewModel

@Composable
fun ThemesSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenColorSchemePicker: () -> Unit = {},
    viewModel: ThemesSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    // SAF picker for official PSP .ptf themes (no registered MIME type, so accept any file;
    // the importer validates the magic and rejects non-PTF with a clear message).
    val ptfPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importPtfTheme(it) } }

    // Quick Create: pick any photo — it becomes a saved theme with its color auto-derived.
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.createThemeFromPhoto(it) } }

    // Shared .pfptheme bundles (no registered MIME; the codec validates the contents).
    val pfpPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importPfpTheme(it) } }

    SettingsScaffold(
        title    = "Settings",
        subtitle = "Themes",
        onBack   = onBack,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {

            // ── Appearance ────────────────────────────────────────────────
            SettingsGroup("Appearance")

            // Opens a PSP-style submenu over the live XMB for picking & previewing
            // the background color scheme.
            SettingsRow(
                label    = "Color Scheme",
                sublabel = if (state.accentOverrideArgb != null) {
                    "Custom theme color active — picking a preset replaces it"
                } else "PSP-style background colors — preview live",
                onClick  = onOpenColorSchemePicker,
            )

            state.accentOverrideArgb?.let { accent ->
                SettingsRow(
                    label    = "Custom Theme Color",
                    sublabel = "From an imported theme — tap to remove and return to the color scheme",
                    onClick  = { viewModel.clearAccentOverride() },
                    trailing = { ColorDot(argb = accent) },
                )
            }

            // Unified icon color: one tint across the XMB's icon set (docs/icon-system-plan.md).
            SettingsGroup("Icon Color")
            IconColorSwatchRow(
                selectedArgb = state.iconColorArgb,
                onSelect     = { viewModel.setIconColor(it) },
            )

            // ── Saved themes (Quick Create + PSP imports) ────────────────────
            SettingsGroup("My Themes")

            SettingsRow(
                label    = "New Theme from Photo",
                sublabel = "Pick a picture — wallpaper and color are set from it",
                onClick  = if (state.isInstalling) null else ({ photoPicker.launch(arrayOf("image/*")) }),
            )

            if (state.savedThemes.isNotEmpty()) {
                SavedThemeCardRow(
                    themes   = state.savedThemes,
                    onApply  = { viewModel.applySavedTheme(it) },
                    onDelete = { viewModel.deleteSavedTheme(it) },
                    onShare  = { viewModel.shareSavedTheme(it) },
                )
            }

            // ── Active theme ──────────────────────────────────────────────
            SettingsGroup("Active Theme")

            SettingsValueRow(
                label = "Current Theme",
                value = state.activeThemeName,
            )

            SettingsRow(
                label    = "Reset to Default",
                sublabel = "Remove the applied wallpaper, theme colors, and custom icons",
                onClick  = { viewModel.resetTheme() },
            )

            // ── Installed themes list ─────────────────────────────────────
            SettingsGroup("Installed Themes")

            if (state.themes.isEmpty()) {
                Text(
                    text     = "No themes installed.",
                    color    = SettingsSubtext,
                    modifier = Modifier.padding(horizontal = 48.dp, vertical = 12.dp),
                )
            } else {
                state.themes.forEach { theme ->
                    ThemeRow(
                        theme       = theme,
                        isActive    = theme.id == state.activeThemeId,
                        onApply     = { viewModel.applyTheme(theme.id) },
                        onUninstall = if (!theme.isBuiltIn) ({ viewModel.uninstallTheme(theme.id) }) else null,
                    )
                }
            }

            // ── Install ───────────────────────────────────────────────────
            SettingsGroup("Install")

            // Convert an official PSP theme the user owns: wallpaper + derived color,
            // rendered with our icons (docs/ptf-import-plan.md).
            SettingsRow(
                label    = "Import PSP Theme (.ptf)",
                sublabel = "Uses the theme's wallpaper and color — icons stay ours",
                onClick  = if (state.isInstalling) null else ({ ptfPicker.launch(arrayOf("*/*")) }),
            )

            // A theme shared from another PlayFieldPortal (the Share action on a card).
            SettingsRow(
                label    = "Import Theme (.pfptheme)",
                sublabel = "A theme shared from PlayFieldPortal",
                onClick  = if (state.isInstalling) null else ({ pfpPicker.launch(arrayOf("*/*")) }),
            )

            if (state.isInstalling) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 48.dp, vertical = 4.dp),
                )
            }

            state.installMessage?.let { msg ->
                SettingsRow(
                    label    = msg,
                    sublabel = "Tap to dismiss",
                    onClick  = { viewModel.dismissMessage() },
                )
            }

        }
    }
}

// Preset icon tints. "Default" (null) = white — the icon art's native color. Kept to a small
// curated set; per Sony's own guidance a flat icon color must stay legible over any wallpaper,
// and the wallpaper scrim covers these (docs/icon-system-plan.md).
private val IconColorChoices: List<Pair<String, Long?>> = listOf(
    "Default"  to null,
    "Pink"     to 0xFFFFD6E8L,
    "Gold"     to 0xFFE8C64AL,
    "Aqua"     to 0xFF7FD8D8L,
    "Sky Blue" to 0xFF9DBEF5L,
    "Green"    to 0xFF9FDB9FL,
    "Coral"    to 0xFFE88A8AL,
    "Slate"    to 0xFFAAB2BFL,
)

@Composable
private fun IconColorSwatchRow(
    selectedArgb: Long?,
    onSelect: (Long?) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 10.dp),
    ) {
        IconColorChoices.forEach { (label, argb) ->
            val selected = selectedArgb == argb
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(argb ?: 0xFFFFFFFFL))
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) SettingsAccent else Color(0x66FFFFFF),
                            shape = CircleShape,
                        )
                        .clickable { onSelect(argb) },
                )
                Text(
                    text = label,
                    color = if (selected) SettingsAccent else SettingsSubtext,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** Horizontally-scrolling cards for the saved .pfptheme library: thumbnail, name, actions. */
@Composable
private fun SavedThemeCardRow(
    themes: List<PfpThemeStore.SavedTheme>,
    onApply: (String) -> Unit,
    onDelete: (String) -> Unit,
    onShare: (String) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 10.dp),
    ) {
        themes.forEach { theme ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(width = 168.dp, height = 96.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(theme.accentArgb?.let { it and 0xFFFFFFFFL } ?: 0xFF20304AL))
                        .border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(10.dp))
                        .clickable { onApply(theme.id) },
                ) {
                    theme.previewPath?.let { path ->
                        AsyncImage(
                            model = path,
                            contentDescription = theme.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    // Accent chip in the corner, over the thumbnail.
                    theme.accentArgb?.let { accent ->
                        Box(
                            modifier = Modifier
                                .padding(6.dp)
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(Color(accent and 0xFFFFFFFFL))
                                .border(1.dp, Color(0x88FFFFFF), CircleShape)
                                .align(Alignment.TopEnd),
                        )
                    }
                }
                Text(
                    text = theme.name,
                    color = SettingsSubtext,
                    fontSize = 12.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Padding INSIDE the clickable enlarges the tap target beyond the small text —
                    // these are finger targets on a handheld, not mouse targets.
                    Text(
                        text = "Share",
                        color = SettingsAccent,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clickable { onShare(theme.id) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                    Text(
                        text = "Remove",
                        color = SettingsAccent,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clickable { onDelete(theme.id) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** Small round preview of an ARGB color, used as a row trailing element. */
@Composable
private fun ColorDot(argb: Long) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(Color(argb and 0xFFFFFFFFL))
            .border(1.dp, Color(0x66FFFFFF), CircleShape),
    )
}

@Composable
private fun ThemeRow(
    theme: ThemeListItem,
    isActive: Boolean,
    onApply: () -> Unit,
    onUninstall: (() -> Unit)?,
) {
    SettingsRow(
        label    = theme.name,
        sublabel = if (theme.isBuiltIn) "Built-in" else "Installed",
        trailing = {
            if (isActive) {
                Text("Active", color = SettingsAccent)
            } else if (onUninstall != null) {
                Text(
                    text  = "Remove",
                    color = SettingsAccent,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clickable { onUninstall() },
                )
            }
        },
        onClick = if (!isActive) onApply else null,
    )
}
