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
import com.playfieldportal.feature.artwork.api.ArtworkFolderPrompt
import com.playfieldportal.feature.xmb.viewmodel.CollectionNameDialogState
import com.playfieldportal.feature.xmb.viewmodel.CustomIconSession
import com.playfieldportal.feature.xmb.viewmodel.InfoDialogState
import com.playfieldportal.feature.xmb.viewmodel.NotificationPanelState
import com.playfieldportal.feature.xmb.viewmodel.StopConfirmState
import com.playfieldportal.core.domain.playlist.PlaylistKind
import com.playfieldportal.feature.xmb.viewmodel.PlaylistImportQueue
import com.playfieldportal.feature.xmb.viewmodel.PlaylistImportReport
import com.playfieldportal.feature.xmb.viewmodel.PlaylistNameDialogState
import com.playfieldportal.feature.xmb.viewmodel.XMBUiState
import com.playfieldportal.feature.xmb.viewmodel.XmbConfirm
import com.playfieldportal.feature.xmb.viewmodel.sourceChooserFor
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

    // ── Playlist import sheet ─────────────────────────────────────────────────

    private val importCalls = mutableListOf<String>()
    private val importCallbacks = PlaylistImportCallbacks(
        onOpen = { importCalls += "open:${it.playlistId}" },
        onClose = { importCalls += "close" },
        onCopy = { importCalls += "copy" },
    )

    private fun importReport(playlistId: Long? = 7, error: String? = null) = PlaylistImportReport(
        kind = PlaylistKind.MUSIC, fileName = "road.m3u", playlistName = "Road Trip",
        playlistId = playlistId, outcomes = emptyList(), error = error,
    )

    private fun specWithImport(state: XMBUiState): PfpModalSpec? = shellModalSpec(
        uiState = state,
        onConfirmAppRename = {}, onCancelAppRename = {},
        onConfirmCollectionName = {}, onCancelCollectionName = {},
        onConfirmPlaylistName = {}, onCancelPlaylistName = {},
        onConfirmSaveAsTheme = {}, onDismissSaveAsTheme = {},
        onDismissInfoDialog = {},
        onWindowsSetupConfirm = {}, onWindowsSetupDismiss = {},
        notificationCallbacks = notificationCallbacks,
        playlistImportCallbacks = importCallbacks,
    )

    @Test
    fun `an import sheet is the results sheet with Open Playlist`() {
        val spec = specWithImport(
            XMBUiState(playlistImportQueue = PlaylistImportQueue(listOf(importReport()))),
        ) as PfpModalSpec.Results

        assertEquals("Imported \"Road Trip\"", spec.title)
        assertEquals("Open Playlist", spec.actionLabel)
        assertNull("a single file has no position", spec.meta)
        spec.onAction()
        spec.onCopy("text")
        spec.onClose()
        assertEquals(listOf("open:7", "copy", "close"), importCalls)
    }

    @Test
    fun `an import that created nothing offers no Open Playlist`() {
        val spec = specWithImport(
            XMBUiState(playlistImportQueue = PlaylistImportQueue(listOf(importReport(playlistId = null)))),
        ) as PfpModalSpec.Results

        assertNull(spec.actionLabel)
    }

    @Test
    fun `a queued import names its place in the batch`() {
        val queue = PlaylistImportQueue(listOf(importReport(), importReport(), importReport())).advance()!!
        val spec = specWithImport(XMBUiState(playlistImportQueue = queue)) as PfpModalSpec.Results

        assertEquals("2 of 3", spec.meta)
    }

    @Test
    fun `a notification layer still wins over the import sheet`() {
        val row = notification(5, NotificationDetail.Results(items = emptyList()))
        val spec = specWithImport(
            XMBUiState(
                notifications = listOf(row),
                notificationPanel = NotificationPanelState(cursor = 1, sheetNotificationId = 5),
                playlistImportQueue = PlaylistImportQueue(listOf(importReport())),
            ),
        ) as PfpModalSpec.Results

        assertEquals("Couldn't launch Ape Escape", spec.title)
    }

    // ── Menu confirms ─────────────────────────────────────────────────────────

    private val confirmCalls = mutableListOf<String>()
    private val menuConfirmCallbacks = XmbConfirmCallbacks(
        onConfirm = { confirmCalls += "confirm:${it::class.simpleName}" },
        onCancel = { confirmCalls += "cancel" },
    )

    private fun specWithConfirm(state: XMBUiState): PfpModalSpec? = shellModalSpec(
        uiState = state,
        onConfirmAppRename = {}, onCancelAppRename = {},
        onConfirmCollectionName = {}, onCancelCollectionName = {},
        onConfirmPlaylistName = {}, onCancelPlaylistName = {},
        onConfirmSaveAsTheme = {}, onDismissSaveAsTheme = {},
        onDismissInfoDialog = {},
        onWindowsSetupConfirm = {}, onWindowsSetupDismiss = {},
        notificationCallbacks = notificationCallbacks,
        playlistImportCallbacks = importCallbacks,
        confirmCallbacks = menuConfirmCallbacks,
    )

    @Test
    fun `a pending confirm is a destructive confirm that opens on cancel`() {
        val spec = specWithConfirm(
            XMBUiState(pendingConfirm = XmbConfirm.RemoveGame(gameId = 7, title = "Ape Escape")),
        ) as PfpModalSpec.Confirm

        assertTrue(spec.destructive)
        assertTrue("a stray press must never remove", spec.openOnCancel)
        assertEquals("Remove", spec.confirmLabel)
        spec.onConfirm()
        spec.onCancel()
        assertEquals(listOf("confirm:RemoveGame", "cancel"), confirmCalls)
    }

    @Test
    fun `leaving a category opens on cancel without being drawn destructive`() {
        val spec = specWithConfirm(
            XMBUiState(
                pendingConfirm = XmbConfirm.RemoveFromCategory(
                    gameId = 7, categoryId = "gaming", categoryName = "Gaming",
                    title = "Ape Escape", cardNames = listOf("Co-op"),
                ),
            ),
        ) as PfpModalSpec.Confirm

        assertFalse(spec.destructive)
        assertTrue(spec.openOnCancel)
    }

    @Test
    fun `a pending confirm wins over a notification layer and every other modal`() {
        val row = notification(5, NotificationDetail.Results(items = emptyList()))
        val spec = specWithConfirm(
            XMBUiState(
                notifications = listOf(row),
                notificationPanel = NotificationPanelState(cursor = 1, sheetNotificationId = 5),
                infoDialog = InfoDialogState("t", "m"),
                pendingConfirm = XmbConfirm.DeleteCard(collectionId = 3, title = "RPGs"),
            ),
        ) as PfpModalSpec.Confirm

        assertEquals("Delete Custom Card", spec.confirmLabel)
    }

    @Test
    fun `a name entry wins over the info notice, matching the order presses are forwarded in`() {
        val both = XMBUiState(
            collectionNameDialog = CollectionNameDialogState(title = "New Collection"),
            infoDialog = InfoDialogState("t", "m"),
        )

        assertTrue(specFor(both) is PfpModalSpec.TextEntry)
    }

    // ── Artwork folder prompts ────────────────────────────────────────────────

    private val artworkCalls = mutableListOf<String>()
    private val artworkCallbacks = ArtworkFolderCallbacks(
        onConfirm = { artworkCalls += "confirm" },
        onCancel = { artworkCalls += "cancel" },
    )

    private fun specWithArtwork(state: XMBUiState): PfpModalSpec? = shellModalSpec(
        uiState = state,
        onConfirmAppRename = {}, onCancelAppRename = {},
        onConfirmCollectionName = {}, onCancelCollectionName = {},
        onConfirmPlaylistName = {}, onCancelPlaylistName = {},
        onConfirmSaveAsTheme = {}, onDismissSaveAsTheme = {},
        onDismissInfoDialog = {},
        onWindowsSetupConfirm = {}, onWindowsSetupDismiss = {},
        notificationCallbacks = notificationCallbacks,
        confirmCallbacks = menuConfirmCallbacks,
        artworkFolderCallbacks = artworkCallbacks,
    )

    private fun artworkConfirm(prompt: ArtworkFolderPrompt): PfpModalSpec.Confirm =
        specWithArtwork(XMBUiState(artworkFolderPrompt = prompt)) as PfpModalSpec.Confirm

    @Test
    fun `no artwork prompt means no artwork modal`() {
        assertNull(specWithArtwork(XMBUiState(artworkFolderPrompt = null)))
    }

    @Test
    fun `the unavailable prompt names the folder and offers Relink folder or Not now`() {
        val spec = artworkConfirm(ArtworkFolderPrompt.Unavailable("Artwork"))

        assertEquals("Artwork folder unavailable", spec.title)
        assertEquals(
            "PFP can't reach Artwork. New artwork is paused until you relink it. " +
                "Art you already have still shows.",
            spec.message,
        )
        assertEquals("Relink folder", spec.confirmLabel)
        assertEquals("Not now", spec.cancelLabel)
        assertFalse(spec.destructive)
        spec.onConfirm()
        spec.onCancel()
        assertEquals(listOf("confirm", "cancel"), artworkCalls)
    }

    @Test
    fun `the move prompt carries the image count`() {
        val spec = artworkConfirm(ArtworkFolderPrompt.MoveArtwork(42))

        assertEquals("Move your artwork to a folder you own", spec.title)
        assertEquals(
            "42 images are stored inside the app and would be lost if you uninstall. " +
                "Pick a folder and PFP moves them there.",
            spec.message,
        )
        assertEquals("Choose artwork folder", spec.confirmLabel)
        assertEquals("Later", spec.cancelLabel)
    }

    @Test
    fun `a single stored image or library file reads in the singular`() {
        assertEquals(
            "1 image is stored inside the app and would be lost if you uninstall. " +
                "Pick a folder and PFP moves it there.",
            artworkConfirm(ArtworkFolderPrompt.MoveArtwork(1)).message,
        )
        assertEquals(
            "Roms Art was set up by a different install (1 file). Use it and PFP relinks " +
                "your games to its art, or pick another folder.",
            artworkConfirm(ArtworkFolderPrompt.ForeignLibrary("Roms Art", 1)).message,
        )
    }

    @Test
    fun `the choose prompt asks for a folder when nothing is linked or stored`() {
        val spec = artworkConfirm(ArtworkFolderPrompt.ChooseFolder)

        assertEquals("Choose an artwork folder", spec.title)
        assertEquals(
            "New artwork is saved to a folder you own. Pick one and PFP saves art there.",
            spec.message,
        )
        assertEquals("Choose artwork folder", spec.confirmLabel)
        assertEquals("Later", spec.cancelLabel)
    }

    @Test
    fun `the foreign library prompt names the folder and its file count`() {
        val spec = artworkConfirm(ArtworkFolderPrompt.ForeignLibrary("Roms Art", 1200))

        assertEquals("This folder holds another artwork library", spec.title)
        assertEquals(
            "Roms Art was set up by a different install (1200 files). Use it and PFP relinks " +
                "your games to its art, or pick another folder.",
            spec.message,
        )
        assertEquals("Use this library", spec.confirmLabel)
        assertEquals("Choose another folder", spec.cancelLabel)
    }

    @Test
    fun `each artwork prompt is its own modal so the host resets between them`() {
        val keys = listOf(
            ArtworkFolderPrompt.Unavailable("A"),
            ArtworkFolderPrompt.MoveArtwork(1),
            ArtworkFolderPrompt.ChooseFolder,
            ArtworkFolderPrompt.ForeignLibrary("A", 1),
        ).map { artworkConfirm(it).key }

        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `a menu confirm and a notification layer win over the artwork prompt`() {
        val prompt = ArtworkFolderPrompt.ChooseFolder
        val confirm = specWithArtwork(
            XMBUiState(
                artworkFolderPrompt = prompt,
                pendingConfirm = XmbConfirm.DeleteCard(collectionId = 3, title = "RPGs"),
            ),
        ) as PfpModalSpec.Confirm
        assertEquals("Delete Custom Card", confirm.confirmLabel)

        val row = notification(5, NotificationDetail.Results(items = emptyList()))
        val sheet = specWithArtwork(
            XMBUiState(
                artworkFolderPrompt = prompt,
                notifications = listOf(row),
                notificationPanel = NotificationPanelState(cursor = 1, sheetNotificationId = 5),
            ),
        )
        assertTrue(sheet is PfpModalSpec.Results)
    }

    @Test
    fun `the artwork prompt precedes a shortcut review and the windows prompt`() {
        val request = com.playfieldportal.core.data.repository.PendingShortcutRequest(
            id = "1a2b", name = "Gmail", intentUri = "intent:x", hostLabel = "Chrome", requestedAt = 0,
        )
        val spec = specWithArtwork(
            XMBUiState(
                artworkFolderPrompt = ArtworkFolderPrompt.ChooseFolder,
                shortcutReview = request,
                showWindowsSetupPrompt = true,
            ),
        ) as PfpModalSpec.Confirm

        assertEquals("Choose an artwork folder", spec.title)
    }

    @Test
    fun `an artwork prompt blocks the crossbar like every other overlay`() {
        val idle = XMBUiState(showBootSequence = false)
        assertFalse(idle.hasBlockingOverlay)
        assertTrue(idle.copy(artworkFolderPrompt = ArtworkFolderPrompt.ChooseFolder).hasBlockingOverlay)
    }

    // ── The icon editor's Pick source chooser ─────────────────────────────────

    private val chooserCalls = mutableListOf<String>()
    private val chooserCallbacks = SourceChooserCallbacks(
        onTheme = { chooserCalls += "theme" },
        onDevice = { chooserCalls += "device" },
        onCancel = { chooserCalls += "cancel" },
    )

    private fun specWithChooser(state: XMBUiState): PfpModalSpec? = shellModalSpec(
        uiState = state,
        onConfirmAppRename = {}, onCancelAppRename = {},
        onConfirmCollectionName = {}, onCancelCollectionName = {},
        onConfirmPlaylistName = {}, onCancelPlaylistName = {},
        onConfirmSaveAsTheme = {}, onDismissSaveAsTheme = {},
        onDismissInfoDialog = {},
        onWindowsSetupConfirm = {}, onWindowsSetupDismiss = {},
        notificationCallbacks = notificationCallbacks,
        sourceChooserCallbacks = chooserCallbacks,
    )

    private fun chooserState(count: Int = 3, extra: XMBUiState.() -> XMBUiState = { this }) = XMBUiState(
        customIconSession = CustomIconSession(
            sourceChooser = sourceChooserFor("catbar_music", "Music", "ModNation Racers", count),
        ),
    ).extra()

    @Test
    fun `the pick chooser is a choice with the approved copy`() {
        val spec = specWithChooser(chooserState()) as PfpModalSpec.Choice

        assertEquals("Pick an icon for Music", spec.title)
        assertEquals("Use one from the applied theme, or an image on your device.", spec.message)
        assertEquals(listOf("From the applied theme", "From your device"), spec.options.map { it.label })
        assertEquals(listOf("ModNation Racers · 3 icons", "PNG or GIF"), spec.options.map { it.detail })
        assertEquals("Continue", spec.confirmLabel)
    }

    @Test
    fun `one theme icon reads in the singular`() {
        val spec = specWithChooser(chooserState(count = 1)) as PfpModalSpec.Choice

        assertEquals("ModNation Racers · 1 icon", spec.options[0].detail)
    }

    @Test
    fun `confirm routes each option and cancel closes the chooser`() {
        val spec = specWithChooser(chooserState()) as PfpModalSpec.Choice

        spec.onConfirm(0)
        spec.onConfirm(1)
        spec.onCancel()

        assertEquals(listOf("theme", "device", "cancel"), chooserCalls)
    }

    @Test
    fun `no chooser in the session means no modal`() {
        assertNull(specWithChooser(XMBUiState(customIconSession = CustomIconSession())))
    }

    @Test
    fun `the chooser follows a menu confirm, a notification layer and a name entry`() {
        val row = notification(5, NotificationDetail.Results(items = emptyList()))
        val confirm = specWithChooser(
            chooserState { copy(pendingConfirm = XmbConfirm.DeleteCard(collectionId = 3, title = "RPGs")) },
        )
        assertTrue(confirm is PfpModalSpec.Confirm)

        val sheet = specWithChooser(
            chooserState {
                copy(notifications = listOf(row), notificationPanel = NotificationPanelState(cursor = 1, sheetNotificationId = 5))
            },
        )
        assertTrue(sheet is PfpModalSpec.Results)

        val name = specWithChooser(chooserState { copy(renameAppTarget = "com.example.app") })
        assertTrue(name is PfpModalSpec.TextEntry)
    }
}
