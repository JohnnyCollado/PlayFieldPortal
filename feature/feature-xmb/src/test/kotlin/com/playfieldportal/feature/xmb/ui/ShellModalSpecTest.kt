package com.playfieldportal.feature.xmb.ui

import com.playfieldportal.core.domain.model.DetailAction
import com.playfieldportal.core.domain.model.NotificationAction
import com.playfieldportal.core.domain.model.NotificationDetail
import com.playfieldportal.core.domain.model.NotificationDetailCodec
import com.playfieldportal.core.domain.model.NotificationKind
import com.playfieldportal.core.domain.model.NotificationSeverity
import com.playfieldportal.core.domain.model.PfpNotification
import com.playfieldportal.core.domain.model.ResultItem
import com.playfieldportal.core.domain.model.ResultOutcome
import com.playfieldportal.core.ui.components.PfpModalSpec
import com.playfieldportal.feature.xmb.viewmodel.CollectionNameDialogState
import com.playfieldportal.feature.xmb.viewmodel.InfoDialogState
import com.playfieldportal.feature.xmb.viewmodel.NotificationPanelState
import com.playfieldportal.feature.xmb.viewmodel.StopConfirmState
import com.playfieldportal.feature.xmb.viewmodel.PlaylistNameDialogState
import com.playfieldportal.feature.xmb.viewmodel.XMBUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which shared modal the XMB shell shows for a given UI state. The mapping carries two rules that
 * are easy to lose: a field that may be cleared (Rename Shortcut, Edit Title, Edit Note) accepts a
 * blank value while a collection or playlist name never does, and the Windows setup prompt keeps
 * its own button words.
 */
class ShellModalSpecTest {

    private val confirmed = mutableListOf<String>()
    private var cancels = 0

    private fun specFor(state: XMBUiState): PfpModalSpec? = shellModalSpec(
        uiState = state,
        onConfirmAppRename = { confirmed += "app:$it" },
        onCancelAppRename = { cancels++ },
        onConfirmCollectionName = { confirmed += "collection:$it" },
        onCancelCollectionName = { cancels++ },
        onConfirmPlaylistName = { confirmed += "playlist:$it" },
        onCancelPlaylistName = { cancels++ },
        onConfirmSaveAsTheme = { confirmed += "theme:$it" },
        onDismissSaveAsTheme = { cancels++ },
        onDismissInfoDialog = { cancels++ },
        onWindowsSetupConfirm = { confirmed += "windows" },
        onWindowsSetupDismiss = { cancels++ },
    )

    @Test
    fun `nothing is shown when no dialog state is set`() {
        assertNull(specFor(XMBUiState()))
    }

    @Test
    fun `rename shortcut starts from the current label and may be cleared`() {
        val spec = specFor(XMBUiState(renameAppTarget = "com.example.app", renameAppCurrent = "Example"))
            as PfpModalSpec.TextEntry

        assertEquals("Rename Shortcut", spec.title)
        assertEquals("Example", spec.initial)
        // Blank reverts to the real app label.
        assertTrue(spec.allowBlank)
        spec.onConfirm("")
        assertEquals(listOf("app:"), confirmed)
    }

    @Test
    fun `a collection name can never be blank`() {
        val spec = specFor(XMBUiState(collectionNameDialog = CollectionNameDialogState(title = "New Collection")))
            as PfpModalSpec.TextEntry

        assertEquals("New Collection", spec.title)
        assertFalse(spec.allowBlank)
    }

    @Test
    fun `edit title and edit note may be cleared`() {
        val title = specFor(
            XMBUiState(
                collectionNameDialog = CollectionNameDialogState(
                    title = "Edit Title", initialText = "Parasite Eve II", editTitleGameId = 7L,
                ),
            ),
        ) as PfpModalSpec.TextEntry
        val note = specFor(
            XMBUiState(collectionNameDialog = CollectionNameDialogState(title = "Edit Note", editNoteGameId = 7L)),
        ) as PfpModalSpec.TextEntry

        assertEquals("Parasite Eve II", title.initial)
        assertTrue(title.allowBlank)
        assertTrue(note.allowBlank)
    }

    @Test
    fun `playlist and theme names route to their own handlers`() {
        val playlist = specFor(XMBUiState(playlistNameDialog = PlaylistNameDialogState(title = "New Playlist")))
            as PfpModalSpec.TextEntry
        val theme = specFor(
            XMBUiState(saveThemeNameDialog = PlaylistNameDialogState(title = "Save Current Look as Theme")),
        ) as PfpModalSpec.TextEntry

        playlist.onConfirm("Road Trip")
        theme.onConfirm("Midnight")

        assertEquals(listOf("playlist:Road Trip", "theme:Midnight"), confirmed)
        assertFalse(playlist.allowBlank)
        assertFalse(theme.allowBlank)
    }

    @Test
    fun `the info dialog is a notice with a close button`() {
        val spec = specFor(XMBUiState(infoDialog = InfoDialogState("Parasite Eve II", "/roms/psx/pe2.cue")))
            as PfpModalSpec.Notice

        assertEquals("Parasite Eve II", spec.title)
        assertEquals("/roms/psx/pe2.cue", spec.message)
        assertEquals("Close", spec.buttonLabel)
        spec.onDismiss()
        assertEquals(1, cancels)
    }

    @Test
    fun `the windows setup prompt keeps its own words and opens on set up`() {
        val spec = specFor(XMBUiState(showWindowsSetupPrompt = true)) as PfpModalSpec.Confirm

        assertEquals("Set Up", spec.confirmLabel)
        assertEquals("Later", spec.cancelLabel)
        // Not a destructive step: A sets up straight away, as it did before.
        assertFalse(spec.openOnCancel)
        spec.onConfirm()
        assertEquals(listOf("windows"), confirmed)
    }

    // ── Notification sheets and the stop confirm ──────────────────────────────

    private val sheetCalls = mutableListOf<String>()
    private val notificationCallbacks = NotificationModalCallbacks(
        onCloseSheet = { sheetCalls += "close" },
        onSheetAction = { sheetCalls += "action:$it" },
        onSheetItemAction = { sheetCalls += "item:${it.typeKey}:${it.arg}" },
        onCopyText = { sheetCalls += "copy" },
        onConfirmStop = { sheetCalls += "stop:$it" },
        onCancelStop = { sheetCalls += "keep" },
    )

    private fun specWithSheets(state: XMBUiState): PfpModalSpec? = shellModalSpec(
        uiState = state,
        onConfirmAppRename = {}, onCancelAppRename = {},
        onConfirmCollectionName = {}, onCancelCollectionName = {},
        onConfirmPlaylistName = {}, onCancelPlaylistName = {},
        onConfirmSaveAsTheme = {}, onDismissSaveAsTheme = {},
        onDismissInfoDialog = {},
        onWindowsSetupConfirm = {}, onWindowsSetupDismiss = {},
        notificationCallbacks = notificationCallbacks,
    )

    private fun notification(id: Long, detail: NotificationDetail, action: NotificationAction = NotificationAction.None) =
        PfpNotification(
            id = id, kind = NotificationKind.LAUNCH, severity = NotificationSeverity.ERROR,
            title = "Couldn't launch Ape Escape", action = action,
            payload = NotificationDetailCodec.encode(detail), createdAt = 0,
        )

    @Test
    fun `an open notes row is a notes sheet that names its action`() {
        val row = notification(4, NotificationDetail.Notes(summary = "Why", code = "LN-4003"), NotificationAction.OpenGame(7))
        val spec = specWithSheets(
            XMBUiState(notifications = listOf(row), notificationPanel = NotificationPanelState(cursor = 1, sheetNotificationId = 4)),
        ) as PfpModalSpec.Notes

        assertEquals("Couldn't launch Ape Escape", spec.title)
        assertEquals("LN-4003", spec.detail.code)
        assertEquals("Go to Game", spec.actionLabel)
        spec.onAction()
        spec.onClose()
        assertEquals(listOf("action:4", "close"), sheetCalls)
    }

    @Test
    fun `an open results row is a results sheet whose items act through the detail action`() {
        val item = ResultItem("PSP", ResultOutcome.FAILED, action = DetailAction("open_memory_card", "psp"))
        val row = notification(5, NotificationDetail.Results(items = listOf(item)))
        val spec = specWithSheets(
            XMBUiState(notifications = listOf(row), notificationPanel = NotificationPanelState(cursor = 1, sheetNotificationId = 5)),
        ) as PfpModalSpec.Results

        assertNull("no row action to fall back to", spec.actionLabel)
        assertEquals("Open Memory Card", spec.itemActionLabel(item))
        spec.onItemAction(item)
        assertEquals(listOf("item:open_memory_card:psp"), sheetCalls)
    }

    @Test
    fun `a stop confirm opens on Keep Running`() {
        val state = XMBUiState(
            notificationPanel = NotificationPanelState(cursor = 1, stopConfirm = StopConfirmState("psx", "Stop \"Scanning\"?", "Kept.")),
        )
        val spec = specWithSheets(state) as PfpModalSpec.Confirm

        assertEquals("Stop", spec.confirmLabel)
        assertEquals("Keep Running", spec.cancelLabel)
        assertTrue(spec.openOnCancel)
        spec.onConfirm()
        spec.onCancel()
        assertEquals(listOf("stop:psx", "keep"), sheetCalls)
    }

    @Test
    fun `a shortcut request asks Add or Ignore and opens on Ignore`() {
        val request = com.playfieldportal.core.data.repository.PendingShortcutRequest(
            id = "1a2b", name = "Gmail", intentUri = "intent:x", hostLabel = "Chrome", requestedAt = 0,
        )
        val callbacks = notificationCallbacks.copy(
            onShortcutAdd = { sheetCalls += "add:$it" },
            onShortcutIgnore = { sheetCalls += "ignore:$it" },
        )
        val spec = shellModalSpec(
            uiState = XMBUiState(shortcutReview = request),
            onConfirmAppRename = {}, onCancelAppRename = {},
            onConfirmCollectionName = {}, onCancelCollectionName = {},
            onConfirmPlaylistName = {}, onCancelPlaylistName = {},
            onConfirmSaveAsTheme = {}, onDismissSaveAsTheme = {},
            onDismissInfoDialog = {},
            onWindowsSetupConfirm = {}, onWindowsSetupDismiss = {},
            notificationCallbacks = callbacks,
        ) as PfpModalSpec.Confirm

        assertEquals("Add Shortcut?", spec.title)
        assertTrue(spec.message.contains("Chrome") && spec.message.contains("Gmail"))
        assertEquals("Add", spec.confirmLabel)
        assertEquals("Ignore", spec.cancelLabel)
        assertTrue("a double press must never add a shortcut", spec.openOnCancel)
        spec.onConfirm()
        spec.onCancel()
        assertEquals(listOf("add:1a2b", "ignore:1a2b"), sheetCalls)
    }

    @Test
    fun `a sheet for a row that is gone shows nothing`() {
        val state = XMBUiState(notificationPanel = NotificationPanelState(cursor = 1, sheetNotificationId = 99))
        assertNull(specWithSheets(state))
    }

    @Test
    fun `a name entry wins over the info notice, matching the order presses are forwarded in`() {
        val both = XMBUiState(
            collectionNameDialog = CollectionNameDialogState(title = "New Collection"),
            infoDialog = InfoDialogState("t", "m"),
        )

        assertTrue(specFor(both) is PfpModalSpec.TextEntry)
    }
}
