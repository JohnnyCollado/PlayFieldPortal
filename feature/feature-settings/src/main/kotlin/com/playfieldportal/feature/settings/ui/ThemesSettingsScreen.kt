package com.playfieldportal.feature.settings.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.components.ColorSwatchRow
import com.playfieldportal.core.ui.components.HsvColorPickerDialog
import com.playfieldportal.core.ui.components.PfpColorChoices
import com.playfieldportal.core.ui.components.PspContextMenuOverlay
import com.playfieldportal.core.ui.components.hsvToArgbLong
import com.playfieldportal.core.ui.components.PspMenuRow
import com.playfieldportal.core.data.repository.PfpThemeStore
import com.playfieldportal.core.ui.preview.CombinedPreviews
import com.playfieldportal.core.ui.preview.PfpPreview
import com.playfieldportal.feature.settings.viewmodel.ThemesSettingsUiState
import com.playfieldportal.feature.settings.viewmodel.ThemesSettingsViewModel

@Composable
fun ThemesSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenColorSchemePicker: () -> Unit = {},
    viewModel: ThemesSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    ThemesSettingsContent(
        state = state,
        onBack = onBack,
        onOpenColorSchemePicker = onOpenColorSchemePicker,
        onImportPtfTheme = { viewModel.importPtfTheme(it) },
        onCreateThemeFromPhoto = { viewModel.createThemeFromPhoto(it) },
        onImportPfpTheme = { viewModel.importPfpTheme(it) },
        onApplySavedTheme = { viewModel.applySavedTheme(it) },
        onShareSavedTheme = { viewModel.shareSavedTheme(it) },
        onDeleteSavedTheme = { viewModel.deleteSavedTheme(it) },
        onSetIconColor = { viewModel.setIconColor(it) },
        onClearAccentOverride = { viewModel.clearAccentOverride() },
        onResetTheme = { viewModel.resetTheme() },
        onDismissMessage = { viewModel.dismissMessage() },
        onSaveCurrentLook = { viewModel.saveCurrentLookAsTheme(it) },
        modifier = modifier
    )
}

@Composable
private fun ThemesSettingsContent(
    state: ThemesSettingsUiState,
    onBack: () -> Unit,
    onOpenColorSchemePicker: () -> Unit,
    onImportPtfTheme: (Uri) -> Unit,
    onCreateThemeFromPhoto: (Uri) -> Unit,
    onImportPfpTheme: (Uri) -> Unit,
    onApplySavedTheme: (String) -> Unit,
    onShareSavedTheme: (String) -> Unit,
    onDeleteSavedTheme: (String) -> Unit,
    onSetIconColor: (Long?) -> Unit,
    onClearAccentOverride: () -> Unit,
    onResetTheme: () -> Unit,
    onDismissMessage: () -> Unit,
    onSaveCurrentLook: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val ptfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { onImportPtfTheme(it) } }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { onCreateThemeFromPhoto(it) } }
    val pfpPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { onImportPfpTheme(it) } }

    // "Save Current Look as Theme" name entry (reuses the app's rename-dialog pattern).
    var showSaveNameDialog by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }
    if (showSaveNameDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showSaveNameDialog = false },
            title = { androidx.compose.material3.Text("Save Current Look as Theme") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = saveName,
                    onValueChange = { saveName = it },
                    singleLine = true,
                    placeholder = { androidx.compose.material3.Text("Theme name") },
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        showSaveNameDialog = false
                        onSaveCurrentLook(saveName)
                        saveName = ""
                    },
                ) { androidx.compose.material3.Text("Save") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showSaveNameDialog = false }) {
                    androidx.compose.material3.Text("Cancel")
                }
            },
        )
    }

    var menu by remember { mutableStateOf<ThemeMenu?>(null) }
    var menuIndex by remember { mutableStateOf(0) }
    var myThemesFocused by remember { mutableStateOf(false) }
    var cardIndex by remember { mutableStateOf(0) }
    var iconStripFocused by remember { mutableStateOf(false) }
    val iconStripRequester = remember { FocusRequester() }
    var iconIndex by remember { mutableStateOf(0) }
    // Icon color picker state
    var customPicker by remember { mutableStateOf(false) }
    var pickerHue by remember { mutableStateOf(0f) }
    var pickerSat by remember { mutableStateOf(0f) }
    var pickerVal by remember { mutableStateOf(1f) }
    var pickerChannel by remember { mutableStateOf(0) }
    val customIndex = PfpColorChoices.size

    fun openIconPicker() {
        val argb = state.iconColorArgb ?: 0xFFFFFFFFL
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV((argb and 0xFFFFFFFFL).toInt(), hsv)
        pickerHue = hsv[0]; pickerSat = hsv[1]; pickerVal = hsv[2]; pickerChannel = 0
        customPicker = true
    }

    fun openMenuForSavedTheme(theme: PfpThemeStore.SavedTheme) {
        menuIndex = 0
        menu = ThemeMenu(theme.name, listOf(
            ThemeMenuOption("Apply")  { onApplySavedTheme(theme.id) },
            ThemeMenuOption("Share")  { onShareSavedTheme(theme.id) },
            ThemeMenuOption("Remove", destructive = true) { onDeleteSavedTheme(theme.id) },
        ))
    }

    Box(modifier = modifier) {
        SettingsScaffold(
            title    = "Settings",
            subtitle = "Themes",
            onBack   = onBack,
            modifier = Modifier.fillMaxSize(),
            onInterceptAction = { action ->
                val m = menu
                when {
                    customPicker -> {
                        when (action) {
                            GamepadAction.NAVIGATE_UP   -> pickerChannel = (pickerChannel + 2) % 3
                            GamepadAction.NAVIGATE_DOWN -> pickerChannel = (pickerChannel + 1) % 3
                            GamepadAction.NAVIGATE_LEFT -> when (pickerChannel) {
                                0 -> pickerHue = ((pickerHue - 6f) % 360f + 360f) % 360f
                                1 -> pickerSat = (pickerSat - 0.04f).coerceIn(0f, 1f)
                                else -> pickerVal = (pickerVal - 0.04f).coerceIn(0f, 1f)
                            }
                            GamepadAction.NAVIGATE_RIGHT -> when (pickerChannel) {
                                0 -> pickerHue = ((pickerHue + 6f) % 360f + 360f) % 360f
                                1 -> pickerSat = (pickerSat + 0.04f).coerceIn(0f, 1f)
                                else -> pickerVal = (pickerVal + 0.04f).coerceIn(0f, 1f)
                            }
                            GamepadAction.SELECT -> {
                                onSetIconColor(hsvToArgbLong(pickerHue, pickerSat, pickerVal))
                                customPicker = false
                            }
                            GamepadAction.BACK -> customPicker = false
                            else -> Unit
                        }
                        true
                    }
                    m != null -> {
                        when (action) {
                            GamepadAction.NAVIGATE_UP   -> menuIndex = (menuIndex - 1).coerceAtLeast(0)
                            GamepadAction.NAVIGATE_DOWN -> menuIndex = (menuIndex + 1).coerceAtMost(m.options.size - 1)
                            GamepadAction.SELECT        -> { m.options.getOrNull(menuIndex)?.action?.invoke(); menu = null }
                            GamepadAction.BACK,
                            GamepadAction.OPEN_CONTEXT_MENU      -> menu = null
                            else -> Unit
                        }
                        true
                    }
                    myThemesFocused && action == GamepadAction.NAVIGATE_LEFT -> {
                        cardIndex = (cardIndex - 1).coerceAtLeast(0); true
                    }
                    myThemesFocused && action == GamepadAction.NAVIGATE_RIGHT -> {
                        cardIndex = (cardIndex + 1).coerceAtMost((state.savedThemes.size - 1).coerceAtLeast(0)); true
                    }
                    iconStripFocused && action == GamepadAction.NAVIGATE_LEFT -> {
                        iconIndex = (iconIndex - 1).coerceAtLeast(0)
                        runCatching { iconStripRequester.requestFocus() }
                        true
                    }
                    iconStripFocused && action == GamepadAction.NAVIGATE_RIGHT -> {
                        iconIndex = (iconIndex + 1).coerceAtMost(customIndex)
                        runCatching { iconStripRequester.requestFocus() }
                        true
                    }
                    action == GamepadAction.OPEN_CONTEXT_MENU -> {
                        if (myThemesFocused) {
                            state.savedThemes.getOrNull(cardIndex)?.let { openMenuForSavedTheme(it) }
                        }
                        true
                    }
                    else -> false
                }
            },
        ) {
            val scrollState = rememberScrollState()
            LocalSettingsScrollStateRegistrar.current(scrollState)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
            ) {
                SettingsGroup("Appearance")
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
                        onClick  = onClearAccentOverride,
                        trailing = { ColorDot(argb = accent) },
                    )
                }

                SettingsGroup("Icon Color")
                FocusableStrip(
                    focusRequester = iconStripRequester,
                    onFocusChange = { focused ->
                        iconStripFocused = focused
                        if (focused) iconIndex = iconIndex.coerceIn(0, customIndex)
                    },
                    onSelect = {
                        if (iconIndex == customIndex) openIconPicker()
                        else onSetIconColor(PfpColorChoices[iconIndex].second)
                    },
                ) { stripFocused ->
                    ColorSwatchRow(
                        selectedArgb   = state.iconColorArgb,
                        focusedIndex   = if (stripFocused) iconIndex else null,
                        accent         = SettingsAccent,
                        subtext        = SettingsSubtext,
                        onSelectPreset = onSetIconColor,
                        onSelectCustom = { openIconPicker() },
                    )
                }

                SettingsGroup("My Themes")
                SettingsRow(
                    label    = "New Theme from Photo",
                    sublabel = "Pick a picture — wallpaper and color are set from it",
                    onClick  = if (state.isInstalling) null else ({ photoPicker.launch(arrayOf("image/*")) }),
                )

                if (state.savedThemes.isNotEmpty()) {
                    FocusableStrip(
                        onFocusChange = { focused ->
                            myThemesFocused = focused
                            if (focused) cardIndex = cardIndex.coerceIn(0, state.savedThemes.size - 1)
                        },
                        onSelect = {
                            state.savedThemes.getOrNull(cardIndex)?.let { onApplySavedTheme(it.id) }
                        },
                    ) { stripFocused ->
                        SavedThemeCardRow(
                            themes       = state.savedThemes,
                            focusedIndex = if (stripFocused) cardIndex else null,
                            onApply      = onApplySavedTheme,
                            onDelete     = onDeleteSavedTheme,
                            onShare      = onShareSavedTheme,
                        )
                    }
                }

                SettingsGroup("Active Theme")
                SettingsValueRow(label = "Current Theme", value = state.activeThemeName)
                SettingsRow(
                    label    = "Reset to Default",
                    sublabel = "Remove the applied wallpaper, theme colors, and custom icons",
                    onClick  = onResetTheme,
                )

                SettingsGroup("Install")
                SettingsRow(
                    label    = "Save Current Look as Theme",
                    sublabel = "Bundle your icons, wallpaper, colors and motion into a shareable .pfptheme",
                    onClick  = { showSaveNameDialog = true },
                )
                SettingsRow(
                    label    = "Import PSP Theme (.ptf)",
                    sublabel = "Uses the theme's wallpaper and color — icons stay ours",
                    onClick  = if (state.isInstalling) null else ({ ptfPicker.launch(arrayOf("*/*")) }),
                )
                SettingsRow(
                    label    = "Import Theme (.pfptheme)",
                    sublabel = "A theme shared from PlayFieldPortal",
                    onClick  = if (state.isInstalling) null else ({ pfpPicker.launch(arrayOf("*/*")) }),
                )

                if (state.isInstalling) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 4.dp),
                    )
                }

                state.installMessage?.let { msg ->
                    SettingsRow(
                        label    = msg,
                        sublabel = "Tap to dismiss",
                        onClick  = onDismissMessage,
                    )
                }
            }
        }

        menu?.let { m ->
            PspContextMenuOverlay(
                title          = m.title,
                rows           = m.options.map { PspMenuRow(it.label, it.destructive) },
                selectedIndex  = menuIndex.coerceIn(0, (m.options.size - 1).coerceAtLeast(0)),
                onRowActivated = { index -> m.options.getOrNull(index)?.action?.invoke(); menu = null },
                onDismiss      = { menu = null },
            )
        }

        if (customPicker) {
            HsvColorPickerDialog(
                title = "Custom Icon Color",
                hue             = pickerHue,
                saturation      = pickerSat,
                brightness      = pickerVal,
                selectedChannel = pickerChannel,
                accent = SettingsAccent,
                subtext = SettingsSubtext,
                onChannelFraction = { channel, fraction ->
                    pickerChannel = channel
                    when (channel) {
                        0 -> pickerHue = (fraction * 360f).coerceIn(0f, 360f)
                        1 -> pickerSat = fraction.coerceIn(0f, 1f)
                        else -> pickerVal = fraction.coerceIn(0f, 1f)
                    }
                },
                onConfirm = {
                    onSetIconColor(hsvToArgbLong(pickerHue, pickerSat, pickerVal))
                    customPicker = false
                },
                onCancel = { customPicker = false },
            )
        }
    }
}

private data class ThemeMenuOption(val label: String, val destructive: Boolean = false, val action: () -> Unit)
private data class ThemeMenu(val title: String, val options: List<ThemeMenuOption>)

@Composable
private fun FocusableStrip(
    focusRequester: FocusRequester? = null,
    onFocusChange: (Boolean) -> Unit,
    onSelect: () -> Unit,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val focusTracker  = LocalSettingsFocusTracker.current
    val registerFirst = LocalSettingsRegisterFirstFocusable.current
    val rowPositions  = LocalSettingsRowPositions.current
    val rowSizes      = LocalSettingsRowSizes.current
    val navigationOrder = LocalSettingsNavigationOrder.current
    val reportFocused = LocalSettingsReportFocused.current
    val reportRemoved = LocalSettingsReportRemoved.current
    var isFocused by remember { mutableStateOf(false) }
    val fr = focusRequester ?: remember { FocusRequester() }
    val navItem = ControllerNavItem(
        key        = "strip-${System.identityHashCode(fr)}",
        focusable  = true,
        selectable = true,
        enabled    = true,
        onSelect   = onSelect,
    )

    DisposableEffect(Unit) {
        navigationOrder?.add(fr to navItem)
        registerFirst(fr)
        onDispose {
            navigationOrder?.removeAll { it.first === fr }
            rowPositions?.remove(fr)
            rowSizes?.remove(fr)
            reportRemoved(fr)
        }
    }

    SideEffect {
        val list = navigationOrder ?: return@SideEffect
        val index = list.indexOfFirst { it.first === fr }
        if (index >= 0) list[index] = fr to navItem
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(fr)
            .onGloballyPositioned {
                rowPositions?.put(fr, it.localToRoot(Offset.Zero).y)
                rowSizes?.put(fr, it.size.height.toFloat())
            }
            .onFocusChanged { st ->
                isFocused = st.isFocused
                onFocusChange(st.isFocused)
                if (st.isFocused) {
                    focusTracker(onSelect)
                    reportFocused(fr)
                }
            }
            .focusable(),
    ) { content(isFocused) }
}


@Composable
private fun SavedThemeCardRow(themes: List<PfpThemeStore.SavedTheme>, focusedIndex: Int? = null, onApply: (String) -> Unit, onDelete: (String) -> Unit, onShare: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 10.dp)) {
        themes.forEachIndexed { index, theme ->
            val cardFocused = focusedIndex == index
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(modifier = Modifier.size(width = 168.dp, height = 96.dp).clip(RoundedCornerShape(10.dp)).background(Color(theme.accentArgb?.let { it and 0xFFFFFFFFL } ?: 0xFF20304AL)).border(width = if (cardFocused) 3.dp else 1.dp, color = if (cardFocused) SettingsAccent else Color(0x55FFFFFF), shape = RoundedCornerShape(10.dp)).clickable { onApply(theme.id) }) {
                    theme.previewPath?.let { path -> AsyncImage(model = path, contentDescription = theme.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    theme.accentArgb?.let { accent -> Box(modifier = Modifier.padding(6.dp).size(14.dp).clip(CircleShape).background(Color(accent and 0xFFFFFFFFL)).border(1.dp, Color(0x88FFFFFF), CircleShape).align(Alignment.TopEnd)) }
                }
                Text(text = theme.name, color = if (cardFocused) SettingsAccent else SettingsSubtext, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = "Share", color = SettingsAccent, fontSize = 12.sp, modifier = Modifier.clickable { onShare(theme.id) }.padding(horizontal = 10.dp, vertical = 8.dp))
                    Text(text = "Remove", color = SettingsAccent, fontSize = 12.sp, modifier = Modifier.clickable { onDelete(theme.id) }.padding(horizontal = 10.dp, vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun ColorDot(argb: Long) {
    Box(modifier = Modifier.size(22.dp).clip(CircleShape).background(Color(argb and 0xFFFFFFFFL)).border(1.dp, Color(0x66FFFFFF), CircleShape))
}

@CombinedPreviews
@Composable
fun ThemesSettingsScreenPreview() {
    val mockState = ThemesSettingsUiState(
        activeThemeName = "Classic Blue",
        savedThemes = listOf(
            PfpThemeStore.SavedTheme("1", "Summer Trip", 0xFFFF7070L, null),
            PfpThemeStore.SavedTheme("2", "Neon Night", 0xFF7FD8D8L, null),
        )
    )
    PfpPreview {
        ThemesSettingsContent(
            state = mockState,
            onBack = {},
            onOpenColorSchemePicker = {},
            onImportPtfTheme = {},
            onCreateThemeFromPhoto = {},
            onImportPfpTheme = {},
            onApplySavedTheme = {},
            onShareSavedTheme = {},
            onDeleteSavedTheme = {},
            onSetIconColor = {},
    onClearAccentOverride = {},
    onResetTheme = {},
    onDismissMessage = {},
)
    }
}
