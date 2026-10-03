package com.playfieldportal.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.studio.ExportCheck
import com.playfieldportal.studio.IconPicker
import com.playfieldportal.studio.PreviewAdjustStore
import com.playfieldportal.studio.StudioDialog
import com.playfieldportal.studio.StudioState
import com.playfieldportal.studio.StudioViewModel
import com.playfieldportal.studio.UpgradeBanner
import com.playfieldportal.studio.io.FileDialogs
import com.playfieldportal.studio.preview.AdjustOverlay
import com.playfieldportal.studio.preview.AdjustOverlayState
import com.playfieldportal.studio.preview.AdjustStep
import com.playfieldportal.studio.preview.BootKind
import com.playfieldportal.studio.preview.BootPlayback
import com.playfieldportal.studio.preview.BootPlaybackState
import com.playfieldportal.studio.preview.GifFrames
import com.playfieldportal.studio.preview.PreviewLiveSpec
import com.playfieldportal.studio.preview.PreviewNav
import com.playfieldportal.studio.preview.PreviewNavState
import com.playfieldportal.studio.preview.PreviewScreen
import com.playfieldportal.studio.preview.PreviewRenderer
import com.playfieldportal.studio.preview.XmbPreviewCanvas
import com.playfieldportal.studio.preview.toPreviewModel
import com.playfieldportal.studio.ui.sections.BackgroundSection
import com.playfieldportal.studio.ui.sections.BootSection
import com.playfieldportal.studio.ui.sections.ColorSection
import com.playfieldportal.studio.ui.sections.ExportCheckSection
import com.playfieldportal.studio.ui.sections.InfoSection
import com.playfieldportal.studio.ui.sections.LayoutSection
import com.playfieldportal.studio.ui.sections.LegibilitySection
import com.playfieldportal.studio.ui.sections.SoundsSection
import com.playfieldportal.themekit.PfpThemeCodec
import java.awt.Frame
import java.io.File

// ── Toolbar action table ─────────────────────────────────────────────────────

/** Every toolbar command. The table below maps each to the same ViewModel call it always made. */
enum class ToolbarAction(val label: String) {
    NEW("New"),
    OPEN("Open…"),
    UPGRADE_FOLDER("Upgrade folder…"),
    CONVERT_PTF("Convert .ptf…"),
    BATCH_CONVERT("Batch convert…"),
    UNPACK_ASSETS("Unpack assets…"),
    UNDO("Undo"),
    REDO("Redo"),
    EXPORT("Export .pfptheme…"),
    ;

    fun isEnabled(canUndo: Boolean, canRedo: Boolean, busy: Boolean): Boolean = when (this) {
        UNDO -> canUndo && !busy
        REDO -> canRedo && !busy
        else -> !busy
    }
}

/** The native pickers the toolbar needs, behind an interface so the action table is testable. */
interface ToolbarFiles {
    fun open(title: String, extensions: Set<String>): File?
    fun save(title: String, suggestedName: String, extension: String): File?
    fun folder(title: String): File?
}

fun windowToolbarFiles(window: Frame?): ToolbarFiles = object : ToolbarFiles {
    override fun open(title: String, extensions: Set<String>) = FileDialogs.openFile(window, title, extensions)
    override fun save(title: String, suggestedName: String, extension: String) =
        FileDialogs.saveFile(window, title, suggestedName, extension)
    override fun folder(title: String) = FileDialogs.pickDirectory(title)
}

private val PTF_EXTENSIONS = setOf("ptf", "ctf")

fun runToolbarAction(action: ToolbarAction, vm: StudioViewModel, files: ToolbarFiles) {
    when (action) {
        ToolbarAction.NEW -> vm.newTheme()
        ToolbarAction.OPEN ->
            files.open("Open theme", PTF_EXTENSIONS + PfpThemeCodec.FILE_EXTENSION)?.let(vm::openFile)
        // Opening a .ptf converts it into an editable theme - Convert is Open scoped to PSP files.
        ToolbarAction.CONVERT_PTF -> files.open("PSP theme to convert", PTF_EXTENSIONS)?.let(vm::openFile)
        ToolbarAction.UPGRADE_FOLDER ->
            files.folder("Folder of .pfptheme files to upgrade")?.let { vm.upgradeFolder(it) }
        ToolbarAction.BATCH_CONVERT -> {
            val input = files.folder("Folder with .ptf files") ?: return
            val output = files.folder("Output folder for .pfptheme files") ?: return
            vm.batchConvert(input, output) { bundle -> runCatching { PreviewRenderer.renderPreviewPng(bundle) }.getOrNull() }
        }
        ToolbarAction.UNPACK_ASSETS -> {
            val ptf = files.open("PSP theme to unpack", PTF_EXTENSIONS) ?: return
            val output = files.folder("Folder for unpacked assets") ?: return
            vm.unpackPtf(ptf, output)
        }
        ToolbarAction.UNDO -> vm.undo()
        ToolbarAction.REDO -> vm.redo()
        ToolbarAction.EXPORT -> {
            val name = vm.state.value.name.ifBlank { "theme" }
            files.save("Export theme", "$name.${PfpThemeCodec.FILE_EXTENSION}", PfpThemeCodec.FILE_EXTENSION)
                ?.let { file -> vm.exportTo(file) { s -> PreviewRenderer.renderPreviewPng(s) } }
        }
    }
}

/** Ctrl+Z undo, Ctrl+Shift+Z / Ctrl+Y redo. Pure so the mapping is testable. */
fun shortcutAction(key: Key, ctrl: Boolean, shift: Boolean): ToolbarAction? = when {
    !ctrl -> null
    key == Key.Z -> if (shift) ToolbarAction.REDO else ToolbarAction.UNDO
    key == Key.Y -> ToolbarAction.REDO
    else -> null
}

/**
 * Window-level key handler (wired in Main). Runs only for events no focused control consumed, so
 * a focused text field keeps its own Ctrl+Z text undo.
 */
fun handleShellKey(event: KeyEvent, vm: StudioViewModel): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val action = shortcutAction(event.key, ctrl = event.isCtrlPressed || event.isMetaPressed, shift = event.isShiftPressed)
        ?: return false
    if (!action.isEnabled(vm.canUndo.value, vm.canRedo.value, vm.state.value.busy)) return true
    runToolbarAction(action, vm, files = object : ToolbarFiles {
        override fun open(title: String, extensions: Set<String>): File? = null
        override fun save(title: String, suggestedName: String, extension: String): File? = null
        override fun folder(title: String): File? = null
    })
    return true
}

// ── Chrome ───────────────────────────────────────────────────────────────────

/** Neutral Studio chrome: deliberately fed no theme colour, so any accent leaves it unchanged. */
private fun studioChromeColors() = darkColorScheme(
    primary = Color(0xFFC4C9D4),
    onPrimary = Color(0xFF1B1F27),
    secondary = Color(0xFF9AA3B2),
    tertiary = Color(0xFFE0B96B),
    background = Color(0xFF18181A),
    surface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFF2A2A2E),
    onSurface = Color(0xFFE6E6EA),
    onSurfaceVariant = Color(0xFFB4B4BC),
    outline = Color(0xFF8A8A92),
    outlineVariant = Color(0xFF3A3A40),
)

@Composable
fun StudioApp(viewModel: StudioViewModel, window: Frame) {
    val state by viewModel.state.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    val canRedo by viewModel.canRedo.collectAsState()
    var sectionId by rememberSaveable { mutableStateOf(StudioSection.DEFAULT.id) }
    val section = StudioSection.fromId(sectionId)
    val check = remember(state) { ExportCheck.of(state) }
    val files = remember(window) { windowToolbarFiles(window) }
    // Preview-only layout adjust: outside the document, remembered per install (read by the preview).
    val adjustStore = remember { PreviewAdjustStore() }
    val adjustOn by adjustStore.enabled.collectAsState()
    val adjust by adjustStore.adjust.collectAsState()
    // The preview cursor lives here so the icon picker's "On screen" filter can follow it.
    var nav by remember { mutableStateOf(PreviewNavState.HOME) }
    // The launcher screen the preview shows ("Open"); XMB is the interactive crossbar.
    var screen by remember { mutableStateOf(PreviewScreen.XMB) }
    // "Adjust on preview": the draft lives here until Save writes the store; Cancel just drops it.
    var adjustOverlay by remember { mutableStateOf<AdjustOverlayState?>(null) }
    // The boot / GameBoot sequence playing over the preview. It ends the moment the clip it plays is
    // cleared, replaced, or the document is swapped (reconcile), so the decoder never outlives its file.
    var bootPlayback by remember { mutableStateOf(BootPlaybackState.IDLE) }
    val bootNow = BootPlayback.reconcile(bootPlayback) { kind -> state.mediaFiles[kind.slotKey] }
    LaunchedEffect(bootNow) { if (bootNow != bootPlayback) bootPlayback = bootNow }
    val live = remember(state.iconOverrides, state.iconExtensions, state.motionFile, state.motionCrop, bootNow) {
        PreviewLiveSpec(
            iconGifs = GifFrames.animatedIcons(state),
            motionFile = state.motionFile,
            motionCrop = state.motionCrop,
            boot = bootNow,
        )
    }
    val focusManager = LocalFocusManager.current
    fun run(action: ToolbarAction) = runToolbarAction(action, viewModel, files)

    MaterialTheme(colorScheme = studioChromeColors()) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Toolbar(state, canUndo, canRedo, onAction = ::run)
                HorizontalDivider()
                UpgradeBannerRow(state.upgradeBanner, onSeeChanges = { sectionId = StudioSection.EXPORT_CHECK.id })

                Row(Modifier.weight(1f)) {
                    // ── Left rail ──
                    SectionRail(
                        selected = section,
                        state = state,
                        check = check,
                        onSelect = { sectionId = it.id },
                        modifier = Modifier.width(200.dp).fillMaxHeight().releasePreviewFocus(focusManager),
                    )
                    VerticalDivider()

                    // ── Center: preview + "In this file" ──
                    Column(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF141414))) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                        ) {
                            XmbPreviewCanvas(
                                // While adjusting, the preview follows the draft live.
                                state.toPreviewModel(adjustOverlay?.draft ?: adjust.takeIf { adjustOn }),
                                nav,
                                onNav = { nav = PreviewNav.reduce(nav, it) },
                                modifier = Modifier.fillMaxSize(),
                                live = live,
                                onBootFinished = { bootPlayback = BootPlayback.finish() },
                                screen = screen,
                                onCloseScreen = { screen = PreviewScreen.XMB },
                                adjustOverlay = adjustOverlay,
                                onAdjust = { action ->
                                    val open = adjustOverlay ?: return@XmbPreviewCanvas
                                    when (val step = AdjustOverlay.step(open, action)) {
                                        is AdjustStep.Editing -> adjustOverlay = step.state
                                        is AdjustStep.Saved -> {
                                            AdjustOverlay.commit(adjustStore, step.adjust)
                                            adjustOverlay = null
                                        }
                                        AdjustStep.Cancelled -> adjustOverlay = null
                                    }
                                },
                            )
                        }
                        PlayBootRow(
                            playing = bootNow,
                            onToggle = { kind -> bootPlayback = BootPlayback.toggle(bootNow, kind, state.mediaFiles[kind.slotKey]) },
                            screen = screen,
                            onOpenScreen = { screen = it },
                        )
                        HorizontalDivider()
                        FileContentsStrip(check, Modifier.fillMaxWidth())
                    }
                    VerticalDivider()

                    // ── Right inspector host ──
                    Column(Modifier.width(360.dp).fillMaxHeight().releasePreviewFocus(focusManager)) {
                        Text(
                            section.label,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp),
                        )
                        HorizontalDivider()
                        SectionHost(
                            section, state, check, viewModel, window, adjustStore, nav,
                            onAdjustOnPreview = { adjustOverlay = AdjustOverlay.open(adjustStore) },
                            onPlayClip = { kind, clip -> bootPlayback = BootPlayback.play(kind, clip) },
                        )
                    }
                }
                HorizontalDivider()

                // ── Status line ──
                Text(
                    text = state.batchProgress?.let { "Converting ${it.current}  (${it.done}/${it.total})" }
                        ?: state.statusMessage
                        ?: "",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            StudioDialogs(viewModel)
        }
    }
}

@Composable
private fun Toolbar(state: StudioState, canUndo: Boolean, canRedo: Boolean, onAction: (ToolbarAction) -> Unit) {
    fun enabled(a: ToolbarAction) = a.isEnabled(canUndo, canRedo, state.busy)
    @Composable
    fun Tool(a: ToolbarAction) = OutlinedButton(onClick = { onAction(a) }, enabled = enabled(a)) { Text(a.label) }

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Tool(ToolbarAction.NEW)
        Tool(ToolbarAction.OPEN)
        Tool(ToolbarAction.UPGRADE_FOLDER)

        var menuOpen by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { menuOpen = true }, enabled = !state.busy) { Text("Import PSP theme ▾") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                listOf(ToolbarAction.CONVERT_PTF, ToolbarAction.BATCH_CONVERT, ToolbarAction.UNPACK_ASSETS).forEach { a ->
                    DropdownMenuItem(text = { Text(a.label) }, onClick = { menuOpen = false; onAction(a) })
                }
            }
        }

        Tool(ToolbarAction.UNDO)
        Tool(ToolbarAction.REDO)

        Box(Modifier.weight(1f))
        if (state.busy) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Text("Working…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(onClick = { onAction(ToolbarAction.EXPORT) }, enabled = enabled(ToolbarAction.EXPORT)) {
            Text(ToolbarAction.EXPORT.label)
        }
    }
}

/**
 * Under the preview: "Open" picks the launcher screen it shows (the XMB, or another screen with the
 * theme applied); "Play boot" / "Play GameBoot" play the built-in sequence or the theme's own clip,
 * and pressing the playing one stops it.
 */
@Composable
private fun PlayBootRow(
    playing: BootPlaybackState,
    onToggle: (BootKind) -> Unit,
    screen: PreviewScreen,
    onOpenScreen: (PreviewScreen) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        var openMenu by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { openMenu = true }) {
                Text(if (screen == PreviewScreen.XMB) "Open ▾" else "Open: ${screen.label} ▾", fontSize = 12.sp)
            }
            DropdownMenu(expanded = openMenu, onDismissRequest = { openMenu = false }) {
                PreviewScreen.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(if (option == PreviewScreen.XMB) "XMB (back to the crossbar)" else option.label) },
                        onClick = { openMenu = false; onOpenScreen(option) },
                    )
                }
            }
        }
        BootKind.entries.forEach { kind ->
            OutlinedButton(onClick = { onToggle(kind) }) {
                Text(if (playing.kind == kind) "Stop ${kind.label}" else "Play ${kind.label}", fontSize = 12.sp)
            }
        }
        Text(
            "Video only — sound plays on the device",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UpgradeBannerRow(banner: UpgradeBanner, onSeeChanges: () -> Unit) {
    if (banner == UpgradeBanner.None) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        when (banner) {
            is UpgradeBanner.Available -> {
                Text(
                    "This theme uses an older format. Saving upgrades it to the current one.",
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onSeeChanges) { Text("See changes") }
            }
            is UpgradeBanner.NewerVersion -> Text(
                "This theme was made by a newer version (format v${banner.schemaVersion}). " +
                    "Parts this version doesn't know are kept when you save.",
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            UpgradeBanner.None -> Unit
        }
    }
}

/** Hosts the panel for [section] in the right-hand inspector. */
@Composable
private fun SectionHost(
    section: StudioSection,
    state: StudioState,
    check: ExportCheck,
    viewModel: StudioViewModel,
    window: Frame,
    adjustStore: PreviewAdjustStore,
    nav: PreviewNavState,
    onAdjustOnPreview: () -> Unit,
    onPlayClip: (BootKind, File) -> Unit,
) {
    when (section) {
        StudioSection.INFO -> InfoSection(state, viewModel)
        StudioSection.COLOR -> ColorSection(state, viewModel)
        StudioSection.LEGIBILITY -> LegibilitySection(state, viewModel)
        StudioSection.LAYOUT -> LayoutSection(state, viewModel, adjustStore, onAdjustOnPreview)
        StudioSection.BACKGROUND -> BackgroundSection(state, viewModel, window)
        // "On screen" follows the preview's selected category, the rows it is showing, and an open menu.
        StudioSection.ICONS -> IconEditorPanel(
            state = state,
            viewModel = viewModel,
            window = window,
            onScreenKeys = remember(nav) {
                IconPicker.onScreenKeys(PreviewNav.categoryKey(nav), PreviewNav.shownSlotKeys(nav))
            },
        )
        StudioSection.EXPORT_CHECK -> ExportCheckSection(
            check = check,
            schemaVersion = state.schemaVersion,
            exportEnabled = check.canExport && !state.busy,
            onExport = { runToolbarAction(ToolbarAction.EXPORT, viewModel, windowToolbarFiles(window)) },
        )
        StudioSection.SOUNDS -> SoundsSection(state, viewModel, window)
        StudioSection.BOOT -> BootSection(
            state, viewModel, window,
            onPlayInPreview = { key, clip -> BootKind.entries.firstOrNull { it.slotKey == key }?.let { onPlayClip(it, clip) } },
        )
    }
}

@Composable
private fun StudioDialogs(viewModel: StudioViewModel) {
    val state by viewModel.state.collectAsState()
    when (val dialog = state.dialog) {
        null -> Unit
        StudioDialog.CxmbRejected -> AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            confirmButton = { Button(onClick = viewModel::dismissDialog) { Text("OK") } },
            title = { Text("CXMB theme") },
            text = {
                Text(
                    "This file is a CXMB custom firmware theme (.ctf). Those replace PSP system " +
                        "files rather than describing wallpaper and colors, so they can't be " +
                        "converted. Official PSP themes (.ptf) open fine.",
                )
            },
        )
        is StudioDialog.Error -> AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            confirmButton = { Button(onClick = viewModel::dismissDialog) { Text("OK") } },
            title = { Text("Something went wrong") },
            text = { Text(dialog.message) },
        )
        is StudioDialog.Notice -> AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            confirmButton = { Button(onClick = viewModel::dismissDialog) { Text("OK") } },
            title = { Text(dialog.title) },
            text = { Text(dialog.message) },
        )
        is StudioDialog.BatchDone -> AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            confirmButton = { Button(onClick = viewModel::dismissDialog) { Text("OK") } },
            title = { Text("Batch conversion finished") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val s = dialog.summary
                    Text("Converted: ${s.converted.size}")
                    if (s.warnings.isNotEmpty()) {
                        Text("Converted with caveats: ${s.warnings.size}")
                        s.warnings.forEach { (name, reason) -> Text("  • $name — $reason", fontSize = 12.sp) }
                    }
                    if (s.skippedCxmb.isNotEmpty()) {
                        Text("Skipped (CXMB): ${s.skippedCxmb.size} — ${s.skippedCxmb.joinToString()}")
                    }
                    if (s.failed.isNotEmpty()) {
                        Text("Failed: ${s.failed.size}")
                        s.failed.forEach { (name, reason) -> Text("  • $name — $reason", fontSize = 12.sp) }
                    }
                }
            },
        )
        is StudioDialog.UpgradeDone -> AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            confirmButton = { Button(onClick = viewModel::dismissDialog) { Text("OK") } },
            title = { Text("Theme upgrade finished") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val s = dialog.summary
                    Text("Upgraded: ${s.upgraded.size} (originals kept as .bak)")
                    if (s.alreadyCurrent.isNotEmpty()) Text("Already current: ${s.alreadyCurrent.size}")
                    if (s.newerVersion.isNotEmpty()) {
                        Text("Made by a newer version, left alone: ${s.newerVersion.joinToString()}")
                    }
                    if (s.failed.isNotEmpty()) {
                        Text("Failed: ${s.failed.size}")
                        s.failed.forEach { (name, reason) -> Text("  • $name — $reason", fontSize = 12.sp) }
                    }
                    s.reportFile?.let { Text("Report: ${it.name}", fontSize = 12.sp) }
                }
            },
        )
    }
}

/**
 * Any press in this region hands the keyboard back from the preview (its arrow keys are only for
 * the preview while it is focused). Initial pass, so a text field pressed next still takes focus after.
 */
private fun Modifier.releasePreviewFocus(focusManager: FocusManager): Modifier = pointerInput(focusManager) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press) focusManager.clearFocus()
        }
    }
}
