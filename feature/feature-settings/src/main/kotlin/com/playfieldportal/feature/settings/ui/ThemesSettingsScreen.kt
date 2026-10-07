package com.playfieldportal.feature.settings.ui

import com.playfieldportal.core.ui.components.PfpChoiceOption
import com.playfieldportal.core.ui.components.PfpModalSpec
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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
import com.playfieldportal.core.ui.components.HsvPickerNav
import com.playfieldportal.core.ui.components.HsvPickerState
import com.playfieldportal.core.data.repository.PfpThemeStore
import com.playfieldportal.core.ui.preview.CombinedPreviews
import com.playfieldportal.core.ui.preview.PfpPreview
import com.playfieldportal.core.ui.sound.LocalMenuSounds
import com.playfieldportal.core.ui.sound.MenuSound
import com.playfieldportal.feature.settings.viewmodel.ThemeApplyConfirmation
import com.playfieldportal.feature.settings.viewmodel.ThemesSettingsUiState
import com.playfieldportal.feature.settings.viewmodel.ThemesSettingsViewModel

@Composable
fun ThemesSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenColorSchemePicker: () -> Unit = {},
    onOpenCustomIcons: () -> Unit = {},
    viewModel: ThemesSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    ThemesSettingsContent(
        state = state,
        onBack = onBack,
        onOpenColorSchemePicker = onOpenColorSchemePicker,
        onOpenCustomIcons = onOpenCustomIcons,
        onImportPtfTheme = { viewModel.importPtfTheme(it) },
        onCreateThemeFromPhoto = { viewModel.createThemeFromPhoto(it) },
        onImportPfpTheme = { viewModel.importPfpTheme(it) },
        onApplySavedTheme = { viewModel.applySavedTheme(it) },
        onShareSavedTheme = { viewModel.shareSavedTheme(it) },
        onRenameSavedTheme = { id, name -> viewModel.renameSavedTheme(id, name) },
        onDeleteSavedTheme = { viewModel.deleteSavedTheme(it) },
        onUpdateThemeFile = { viewModel.updateThemeFile(it) },
        onSetIconColor = { viewModel.setIconColor(it) },
        onClearAccentOverride = { viewModel.clearAccentOverride() },
        onResetTheme = { viewModel.resetTheme() },
        onSaveCurrentLook = { viewModel.saveCurrentLookAsTheme(it) },
        onConfirmApply = { viewModel.confirmApply(it) },
        onCancelApply = { viewModel.cancelApply() },
        onConfirmLockScreenOffer = { viewModel.confirmLockScreenOffer() },
        onDismissLockScreenOffer = { viewModel.dismissLockScreenOffer() },
        modifier = modifier
    )
}

@Composable
private fun ThemesSettingsContent(
    state: ThemesSettingsUiState,
    onBack: () -> Unit,
    onOpenColorSchemePicker: () -> Unit,
    onOpenCustomIcons: () -> Unit,
    onImportPtfTheme: (Uri) -> Unit,
    onCreateThemeFromPhoto: (Uri) -> Unit,
    onImportPfpTheme: (Uri) -> Unit,
    onApplySavedTheme: (String) -> Unit,
    onShareSavedTheme: (String) -> Unit,
    onRenameSavedTheme: (String, String) -> Unit,
    onDeleteSavedTheme: (String) -> Unit,
    onSetIconColor: (Long?) -> Unit,
    onClearAccentOverride: () -> Unit,
    onResetTheme: () -> Unit,
    onSaveCurrentLook: (String) -> Unit = {},
    onUpdateThemeFile: (String) -> Unit = {},
    onConfirmApply: (Int) -> Unit = {},
    onCancelApply: () -> Unit = {},
    onConfirmLockScreenOffer: () -> Unit = {},
    onDismissLockScreenOffer: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val ptfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { onImportPtfTheme(it) } }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { onCreateThemeFromPhoto(it) } }
    val pfpPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { onImportPfpTheme(it) } }

    // "Save Current Look as Theme" name entry, through the shared text entry modal.
    var showSaveNameDialog by remember { mutableStateOf(false) }
    val saveNameModal = rememberSettingsModal(
        if (showSaveNameDialog) {
            PfpModalSpec.TextEntry(
                key = "save_current_look",
                title = "Save Current Look as Theme",
                placeholder = "Theme name",
                onConfirm = { name ->
                    showSaveNameDialog = false
                    onSaveCurrentLook(name)
                },
                onCancel = { showSaveNameDialog = false },
            )
        } else {
            null
        },
    )

    // Rename Theme, from the saved theme's menu, asks for the name through the shared text entry.
    var pendingRename by remember { mutableStateOf<PfpThemeStore.SavedTheme?>(null) }
    val renameModal = rememberSettingsModal(
        pendingRename?.let { theme ->
            renameThemeSpec(
                theme,
                onConfirm = { name -> pendingRename = null; onRenameSavedTheme(theme.id, name) },
                onCancel = { pendingRename = null },
            )
        },
    )

    // Delete Theme, from the menu or the card's own button, asks here first.
    var pendingDelete by remember { mutableStateOf<PfpThemeStore.SavedTheme?>(null) }
    val deleteModal = rememberSettingsModal(
        pendingDelete?.let { theme ->
            deleteThemeConfirmSpec(
                theme,
                onConfirm = { pendingDelete = null; onDeleteSavedTheme(theme.id) },
                onCancel = { pendingDelete = null },
            )
        },
    )

    // Before an apply: "Apply this theme?", plus — when the theme would replace the user's own
    // media or icons, or needs GameBoot / the boot sequence on — the choice of what else to change.
    // The view model decides what to ask; this only asks.
    val applyModal = rememberSettingsModal(
        state.applyConfirmation?.let { confirm ->
            if (confirm.hasChoices) {
                PfpModalSpec.Choice(
                    key = confirm,
                    title = confirm.title,
                    message = confirm.message,
                    options = confirm.options.map { PfpChoiceOption(it) },
                    confirmLabel = confirm.applyLabel,
                    cancelLabel = confirm.cancelLabel,
                    onConfirm = onConfirmApply,
                    onCancel = onCancelApply,
                )
            } else {
                PfpModalSpec.Confirm(
                    key = confirm,
                    title = confirm.title,
                    message = confirm.message,
                    confirmLabel = confirm.applyLabel,
                    cancelLabel = confirm.cancelLabel,
                    onConfirm = { onConfirmApply(ThemeApplyConfirmation.APPLY_ONLY) },
                    onCancel = onCancelApply,
                )
            }
        },
    )

    // After applying a theme with a lock screen image: opt-in, so it opens on Not Now.
    val lockOfferModal = rememberSettingsModal(
        state.lockScreenOffer?.let { offer ->
            PfpModalSpec.Confirm(
                key = "lock:${offer.themeId}",
                title = "Set Lock Screen?",
                message = "\"${offer.themeName}\" has a lock screen image. Use it as the device's lock screen? " +
                    "Resetting the theme puts the lock screen back.",
                confirmLabel = "Set Lock Screen",
                cancelLabel = "Not Now",
                openOnCancel = true,
                onConfirm = onConfirmLockScreenOffer,
                onCancel = onDismissLockScreenOffer,
            )
        },
    )

    val itemMenu = rememberSettingsItemMenu()
    var myThemesFocused by remember { mutableStateOf(false) }
    var cardIndex by remember { mutableStateOf(0) }
    var iconStripFocused by remember { mutableStateOf(false) }
    val iconStripRequester = remember { FocusRequester() }
    var iconIndex by remember { mutableStateOf(0) }
    // Icon color picker state
    var picker by remember { mutableStateOf<HsvPickerState?>(null) }
    val pickerNav = remember { HsvPickerNav() }
    val customIndex = PfpColorChoices.size
    // Every branch of this screen's interceptor moves a cursor the scaffold cannot see — a strip
    // index, a menu index, an HSV channel — so the cue is voiced alongside each move. A consumed
    // action is invisible to the scaffold by design; that is what makes this the screen's job.
    val menuSounds = LocalMenuSounds.current

    fun openIconPicker() {
        picker = HsvPickerState.fromArgb(state.iconColorArgb ?: 0xFFFFFFFFL)
    }

    fun openMenuForSavedTheme(theme: PfpThemeStore.SavedTheme) {
        itemMenu.show(
            theme.name,
            savedThemeMenuRows(
                theme,
                onShare = onShareSavedTheme,
                onRequestRename = { pendingRename = it },
                onRequestDelete = { pendingDelete = it },
                onUpdate = onUpdateThemeFile,
            ),
        )
    }

    Box(modifier = modifier) {
        SettingsScaffold(
            title    = "Settings",
            subtitle = "Themes",
            onBack   = onBack,
            modifier = Modifier.fillMaxSize(),
            modalOpen = saveNameModal.open || renameModal.open || deleteModal.open || applyModal.open || lockOfferModal.open ||
                picker != null,
            onInterceptAction = { action ->
                when {
                    // A modal is a hard input boundary: nothing behind it sees a press.
                    saveNameModal.intercept(action) -> true
                    renameModal.intercept(action) -> true
                    deleteModal.intercept(action) -> true
                    applyModal.intercept(action) -> true
                    lockOfferModal.intercept(action) -> true
                    picker != null -> {
                        val open = picker!!
                        var closed = false
                        val next = pickerNav.handle(
                            state = open,
                            action = action,
                            sounds = menuSounds,
                            onApply = { argb -> closed = true; onSetIconColor(argb) },
                            onCancel = { closed = true },
                        )
                        picker = if (closed) null else next
                        true
                    }
                    itemMenu.intercept(action) -> true
                    myThemesFocused && action == GamepadAction.NAVIGATE_LEFT -> {
                        val next = (cardIndex - 1).coerceAtLeast(0)
                        if (next != cardIndex) menuSounds.play(MenuSound.SCROLL)
                        cardIndex = next; true
                    }
                    myThemesFocused && action == GamepadAction.NAVIGATE_RIGHT -> {
                        val next = (cardIndex + 1).coerceAtMost((state.savedThemes.size - 1).coerceAtLeast(0))
                        if (next != cardIndex) menuSounds.play(MenuSound.SCROLL)
                        cardIndex = next; true
                    }
                    iconStripFocused && action == GamepadAction.NAVIGATE_LEFT -> {
                        val next = (iconIndex - 1).coerceAtLeast(0)
                        if (next != iconIndex) menuSounds.play(MenuSound.SCROLL)
                        iconIndex = next
                        runCatching { iconStripRequester.requestFocus() }
                        true
                    }
                    iconStripFocused && action == GamepadAction.NAVIGATE_RIGHT -> {
                        val next = (iconIndex + 1).coerceAtMost(customIndex)
                        if (next != iconIndex) menuSounds.play(MenuSound.SCROLL)
                        iconIndex = next
                        runCatching { iconStripRequester.requestFocus() }
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

                SettingsGroup("Icons")
                SettingsValueRow(
                    label    = "Customize XMB Icons",
                    value    = state.customIconsValue,
                    sublabel = "Crossbar, items, consoles and physical media — in XMB order, live over the XMB",
                    onClick  = onOpenCustomIcons,
                )

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
                        // Triangle: the saved theme's menu (the scaffold shows "Options" in the footer).
                        onLongPress = {
                            state.savedThemes.getOrNull(cardIndex)?.let { openMenuForSavedTheme(it) }
                        },
                    ) { stripFocused ->
                        SavedThemeCardRow(
                            themes       = state.savedThemes,
                            focusedIndex = if (stripFocused) cardIndex else null,
                            onApply      = onApplySavedTheme,
                            onDelete     = { id -> pendingDelete = state.savedThemes.firstOrNull { it.id == id } },
                            onShare      = onShareSavedTheme,
                            onUpdate     = onUpdateThemeFile,
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
                    sublabel = "Uses the theme's wallpaper, color and matching icons",
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

            }
        }

        itemMenu.Content()

        picker?.let { open ->
            HsvColorPickerDialog(
                title = "Custom Icon Color",
                state = open,
                onStateChange = { picker = it },
                accent = SettingsAccent,
                subtext = SettingsSubtext,
                onConfirm = {
                    onSetIconColor(open.argb)
                    picker = null
                },
                onCancel = { picker = null },
                showHints = LocalSettingsShowControllerHint.current,
            )
        }

        saveNameModal.Content()
        renameModal.Content()
        deleteModal.Content()
        applyModal.Content()
        lockOfferModal.Content()
    }
}

/**
 * A saved theme's menu. No Apply: X on the card already applies it, and this menu is only reachable
 * by Triangle. Rename Theme asks for a name; Delete Theme is red and last, and only asks: the delete waits for a confirm.
 * Update Theme File appears only for a theme saved in an older format; a current one has nothing to update.
 */
internal fun savedThemeMenuRows(
    theme: PfpThemeStore.SavedTheme,
    onShare: (String) -> Unit,
    onRequestRename: (PfpThemeStore.SavedTheme) -> Unit,
    onRequestDelete: (PfpThemeStore.SavedTheme) -> Unit,
    onUpdate: (String) -> Unit = {},
): List<SettingsMenuItem> = buildList {
    add(SettingsMenuItem("Share") { onShare(theme.id) })
    add(SettingsMenuItem("Rename Theme") { onRequestRename(theme) })
    if (theme.isOlderFormat) add(SettingsMenuItem("Update Theme File") { onUpdate(theme.id) })
    add(SettingsMenuItem("Delete Theme", destructive = true) { onRequestDelete(theme) })
}

internal fun renameThemeSpec(
    theme: PfpThemeStore.SavedTheme,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
): PfpModalSpec.TextEntry = PfpModalSpec.TextEntry(
    key = "rename_theme_${theme.id}",
    title = "Rename Theme",
    initial = theme.name,
    placeholder = "Theme name",
    onConfirm = onConfirm,
    onCancel = onCancel,
)

internal fun deleteThemeConfirmSpec(
    theme: PfpThemeStore.SavedTheme,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
): PfpModalSpec.Confirm = PfpModalSpec.Confirm(
    key = "delete_theme_${theme.id}",
    title = "Delete Theme?",
    message = "\"${theme.name}\" will be deleted. This can't be undone.",
    confirmLabel = "Delete",
    destructive = true,
    onConfirm = onConfirm,
    onCancel = onCancel,
)

@Composable
private fun FocusableStrip(
    focusRequester: FocusRequester? = null,
    onFocusChange: (Boolean) -> Unit,
    onSelect: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val focusTracker  = LocalSettingsFocusTracker.current
    val registerFirst = LocalSettingsRegisterFirstFocusable.current
    val rowPositions  = LocalSettingsRowPositions.current
    val rowSizes      = LocalSettingsRowSizes.current
    val navigationOrder = LocalSettingsNavigationOrder.current
    val reportFocused = LocalSettingsReportFocused.current
    val reportRemoved = LocalSettingsReportRemoved.current
    val menuSounds = LocalMenuSounds.current
    var isFocused by remember { mutableStateOf(false) }
    val fr = focusRequester ?: remember { FocusRequester() }
    // A strip is a navigation node but not a SettingsRow, so it carries the activation cue itself
    // — the same place a row carries it, for the same reason. One wrapped lambda for both the node
    // and the focus-tracker fallback, so they cannot sound different.
    val activate: () -> Unit = { menuSounds.play(MenuSound.SELECT); onSelect() }
    val navItem = ControllerNavItem(
        key        = "strip-${System.identityHashCode(fr)}",
        focusable  = true,
        selectable = true,
        enabled    = true,
        onSelect   = activate,
        onLongPress = onLongPress,
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
                    focusTracker(activate)
                    reportFocused(fr)
                }
            }
            .focusable(),
    ) { content(isFocused) }
}


@Composable
private fun SavedThemeCardRow(themes: List<PfpThemeStore.SavedTheme>, focusedIndex: Int? = null, onApply: (String) -> Unit, onDelete: (String) -> Unit, onShare: (String) -> Unit, onUpdate: (String) -> Unit) {
    // Hand-rolled tap targets rather than settings rows, so they carry their own cues. Applying a
    // theme repaints the whole launcher and Delete Theme asks to delete a saved one: both are commits, not
    // descents, so they take the confirm cue. Share hands off to another app, which is an ordinary
    // activation.
    val menuSounds = LocalMenuSounds.current
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 10.dp)) {
        themes.forEachIndexed { index, theme ->
            val cardFocused = focusedIndex == index
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(modifier = Modifier.size(width = 168.dp, height = 96.dp).clip(RoundedCornerShape(10.dp)).background(Color(theme.accentArgb?.let { it and 0xFFFFFFFFL } ?: 0xFF20304AL)).border(width = if (cardFocused) 3.dp else 1.dp, color = if (cardFocused) SettingsAccent else Color(0x55FFFFFF), shape = RoundedCornerShape(10.dp)).clickable { menuSounds.play(MenuSound.CONFIRM); onApply(theme.id) }) {
                    theme.previewPath?.let { path -> AsyncImage(model = path, contentDescription = theme.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    theme.accentArgb?.let { accent -> Box(modifier = Modifier.padding(6.dp).size(14.dp).clip(CircleShape).background(Color(accent and 0xFFFFFFFFL)).border(1.dp, Color(0x88FFFFFF), CircleShape).align(Alignment.TopEnd)) }
                    if (theme.isOlderFormat) {
                        Text(text = "Older format", color = Color.White, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(6.dp).align(Alignment.BottomStart).clip(RoundedCornerShape(4.dp)).background(Color(0xCC000000)).padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
                Text(text = theme.name, color = if (cardFocused) SettingsAccent else SettingsSubtext, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = "Share", color = SettingsAccent, fontSize = 12.sp, modifier = Modifier.clickable { menuSounds.play(MenuSound.SELECT); onShare(theme.id) }.padding(horizontal = 10.dp, vertical = 8.dp))
                    if (theme.isOlderFormat) {
                        Text(text = "Update", color = SettingsAccent, fontSize = 12.sp, modifier = Modifier.clickable { menuSounds.play(MenuSound.CONFIRM); onUpdate(theme.id) }.padding(horizontal = 10.dp, vertical = 8.dp))
                    }
                    Text(text = "Delete Theme", color = SettingsAccent, fontSize = 12.sp, modifier = Modifier.clickable { menuSounds.play(MenuSound.CONFIRM); onDelete(theme.id) }.padding(horizontal = 10.dp, vertical = 8.dp))
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
            onOpenCustomIcons = {},
            onImportPtfTheme = {},
            onCreateThemeFromPhoto = {},
            onImportPfpTheme = {},
            onApplySavedTheme = {},
            onShareSavedTheme = {},
            onRenameSavedTheme = { _, _ -> },
            onDeleteSavedTheme = {},
            onSetIconColor = {},
    onClearAccentOverride = {},
    onResetTheme = {},
)
    }
}
