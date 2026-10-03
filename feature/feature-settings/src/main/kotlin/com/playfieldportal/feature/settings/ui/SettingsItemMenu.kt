package com.playfieldportal.feature.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.components.PspContextMenuOverlay
import com.playfieldportal.core.ui.components.PspMenuCue
import com.playfieldportal.core.ui.components.PspMenuNav
import com.playfieldportal.core.ui.components.PspMenuOutcome
import com.playfieldportal.core.ui.components.PspMenuRow
import com.playfieldportal.core.ui.sound.LocalMenuSounds
import com.playfieldportal.core.ui.sound.MenuSound
import com.playfieldportal.core.ui.sound.MenuSoundSink

// ── A per-item options menu on a settings screen ──────────────────────────────
//
//     val itemMenu = rememberSettingsItemMenu()
//     SettingsRow(..., onLongPress = { itemMenu.show("Title", listOf(SettingsMenuItem("Share") { ... })) })
//     SettingsScaffold(..., onInterceptAction = { itemMenu.intercept(it) }) { ... }   // before the screen's own
//     itemMenu.Content()
//
// Triangle on a focused row runs that row's onLongPress (the scaffold does this and shows "Options"
// in the footer), so a screen's whole contribution is the onLongPress and this menu. The panel is the
// shared PSP one and the rules are PspMenuNav's. The menu is flat: a row commits and the menu closes;
// a row that needs a confirm raises the screen's own modal from its action.

/** One row of a [SettingsItemMenuState]. Commits with the CONFIRM cue unless [silent]. */
internal class SettingsMenuItem(
    val label: String,
    val destructive: Boolean = false,
    val value: String? = null,
    val silent: Boolean = false,
    val action: () -> Unit,
) {
    internal fun toRow() = PspMenuRow(label, isDestructive = destructive, value = value, silent = silent)
}

/** The menu's state and key handling; the composable host is [rememberSettingsItemMenu]. */
internal class SettingsItemMenuState(private val sounds: MenuSoundSink) {
    var title by mutableStateOf("")
        private set
    var items by mutableStateOf<List<SettingsMenuItem>>(emptyList())
        private set
    private var openState by mutableStateOf(false)
    var index by mutableIntStateOf(0)
        private set

    val open: Boolean get() = openState

    /** Opens the menu on its first row. Plays SELECT: opening a list. */
    fun show(title: String, items: List<SettingsMenuItem>) {
        this.title = title
        this.items = items
        index = 0
        openState = true
        sounds.play(MenuSound.SELECT)
    }

    fun dismiss() {
        openState = false
    }

    /** A tap on row [row]; the same path as the pad's SELECT, so touch cannot drift. */
    fun tap(row: Int) = press(GamepadAction.SELECT, row)

    /** Takes every press while open (so nothing behind it moves) and none while closed. */
    fun intercept(action: GamepadAction): Boolean {
        if (!open) return false
        press(action, index)
        return true
    }

    private fun press(action: GamepadAction, row: Int) {
        val outcome = PspMenuNav.handle(
            action, row, items.size, depth = 0,
            cue = items.getOrNull(row)?.toRow()?.cue ?: PspMenuCue.NONE,
            sounds = sounds,
        )
        when (outcome) {
            is PspMenuOutcome.Moved -> index = outcome.index
            PspMenuOutcome.Activate -> {
                val item = items.getOrNull(row)
                dismiss()
                item?.action?.invoke()
            }
            PspMenuOutcome.Up, PspMenuOutcome.Close -> dismiss()
            PspMenuOutcome.Ignored -> Unit
        }
    }

    @Composable
    fun Content() {
        if (!open) return
        PspContextMenuOverlay(
            title = title,
            rows = items.map { it.toRow() },
            selectedIndex = index.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
            onRowActivated = { tap(it) },
            onDismiss = { dismiss() },
        )
    }
}

@Composable
internal fun rememberSettingsItemMenu(): SettingsItemMenuState {
    val sounds = LocalMenuSounds.current
    return remember(sounds) { SettingsItemMenuState(sounds) }
}
