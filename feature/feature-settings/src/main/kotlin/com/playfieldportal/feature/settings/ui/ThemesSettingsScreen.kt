package com.playfieldportal.feature.settings.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.font.FontWeight
import coil.compose.AsyncImage
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.data.repository.PfpThemeStore
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

    // ── Controller context-menu state ─────────────────────────────────────────
    // Workflow: hover a theme (card strip or installed row) → options button (Y/△) → menu.
    var menu by remember { mutableStateOf<ThemeMenu?>(null) }
    var menuIndex by remember { mutableStateOf(0) }
    // My Themes card strip: whether the strip holds focus, and which card is selected in it.
    var myThemesFocused by remember { mutableStateOf(false) }
    var cardIndex by remember { mutableStateOf(0) }

    fun openMenuForSavedTheme(theme: PfpThemeStore.SavedTheme) {
        menuIndex = 0
        menu = ThemeMenu(theme.name, listOf(
            ThemeMenuOption("Apply")  { viewModel.applySavedTheme(theme.id) },
            ThemeMenuOption("Share")  { viewModel.shareSavedTheme(theme.id) },
            ThemeMenuOption("Remove", destructive = true) { viewModel.deleteSavedTheme(theme.id) },
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
                // Open menu captures ALL input while visible.
                m != null -> {
                    when (action) {
                        GamepadAction.NAVIGATE_UP   -> menuIndex = (menuIndex - 1).coerceAtLeast(0)
                        GamepadAction.NAVIGATE_DOWN -> menuIndex = (menuIndex + 1).coerceAtMost(m.options.size - 1)
                        GamepadAction.SELECT        -> { m.options.getOrNull(menuIndex)?.action?.invoke(); menu = null }
                        GamepadAction.BACK,
                        GamepadAction.LONG_PRESS    -> menu = null
                        else -> Unit
                    }
                    true
                }
                // Left/Right browse the My Themes card strip while it holds focus.
                myThemesFocused && action == GamepadAction.NAVIGATE_LEFT -> {
                    cardIndex = (cardIndex - 1).coerceAtLeast(0); true
                }
                myThemesFocused && action == GamepadAction.NAVIGATE_RIGHT -> {
                    cardIndex = (cardIndex + 1).coerceAtMost((state.savedThemes.size - 1).coerceAtLeast(0)); true
                }
                // Options button on a hovered theme card opens its context menu.
                action == GamepadAction.LONG_PRESS -> {
                    if (myThemesFocused) {
                        state.savedThemes.getOrNull(cardIndex)?.let { openMenuForSavedTheme(it) }
                    }
                    true
                }
                else -> false
            }
        },
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
                // One focusable container for the whole strip: Up/Down reach it like a row,
                // Left/Right browse cards, A applies, Y/long-press opens Share/Remove.
                FocusableStrip(
                    onFocusChange = { focused ->
                        myThemesFocused = focused
                        if (focused) cardIndex = cardIndex.coerceIn(0, state.savedThemes.size - 1)
                    },
                    onSelect = {
                        state.savedThemes.getOrNull(cardIndex)?.let { viewModel.applySavedTheme(it.id) }
                    },
                ) { stripFocused ->
                    SavedThemeCardRow(
                        themes       = state.savedThemes,
                        focusedIndex = if (stripFocused) cardIndex else null,
                        onApply      = { viewModel.applySavedTheme(it) },
                        onDelete     = { viewModel.deleteSavedTheme(it) },
                        onShare      = { viewModel.shareSavedTheme(it) },
                    )
                }
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

    // Context menu over the whole screen (controller: hover + options button; touch can tap rows).
    menu?.let { m ->
        ThemeContextMenuOverlay(
            menu          = m,
            selectedIndex = menuIndex,
            onPick        = { index -> m.options.getOrNull(index)?.action?.invoke(); menu = null },
            onDismiss     = { menu = null },
        )
    }

    }  // wrapping Box
}

// ── Controller context menu for a theme (Apply / Share / Remove) ────────────────

private data class ThemeMenuOption(
    val label: String,
    val destructive: Boolean = false,
    val action: () -> Unit,
)

private data class ThemeMenu(
    val title: String,
    val options: List<ThemeMenuOption>,
)

@Composable
private fun ThemeContextMenuOverlay(
    menu: ThemeMenu,
    selectedIndex: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xB3000000))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xF20A0A14))
                .clickable(enabled = false) {}
                .padding(vertical = 10.dp),
        ) {
            Text(
                text     = menu.title,
                color    = SettingsSubtext,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            menu.options.forEachIndexed { index, option ->
                val focused = index == selectedIndex
                Text(
                    text     = option.label,
                    color    = when {
                        option.destructive -> Color(0xFFFF8A8A)
                        focused            -> Color.White
                        else               -> SettingsText
                    },
                    fontSize = 14.sp,
                    fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (focused) SettingsSelectedBg else Color.Transparent)
                        .clickable { onPick(index) }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
        }
    }
}

// ── Focusable wrapper for a horizontal card strip ───────────────────────────────
//
// Registers ONE focus target with the SettingsScaffold nav (Up/Down land on the strip like a
// row); the screen handles Left/Right internally to move between cards while focused.
@Composable
private fun FocusableStrip(
    onFocusChange: (Boolean) -> Unit,
    onSelect: () -> Unit,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val focusTracker  = LocalSettingsFocusTracker.current
    val registerFirst = LocalSettingsRegisterFirstFocusable.current
    val rowPositions  = LocalSettingsRowPositions.current
    val reportFocused = LocalSettingsReportFocused.current
    val reportRemoved = LocalSettingsReportRemoved.current
    var isFocused by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }

    DisposableEffect(Unit) {
        registerFirst(fr)
        onDispose {
            rowPositions?.remove(fr)
            reportRemoved(fr)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(fr)
            .onGloballyPositioned { rowPositions?.put(fr, it.localToRoot(Offset.Zero).y) }
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

/** Horizontally-scrolling cards for the saved .pfptheme library: thumbnail, name, actions.
 *  [focusedIndex] highlights the controller-selected card (null while the strip is unfocused). */
@Composable
private fun SavedThemeCardRow(
    themes: List<PfpThemeStore.SavedTheme>,
    focusedIndex: Int? = null,
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
        themes.forEachIndexed { index, theme ->
            val cardFocused = focusedIndex == index
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(width = 168.dp, height = 96.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(theme.accentArgb?.let { it and 0xFFFFFFFFL } ?: 0xFF20304AL))
                        .border(
                            width = if (cardFocused) 3.dp else 1.dp,
                            color = if (cardFocused) SettingsAccent else Color(0x55FFFFFF),
                            shape = RoundedCornerShape(10.dp),
                        )
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
                    color = if (cardFocused) SettingsAccent else SettingsSubtext,
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

