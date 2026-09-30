package com.playfieldportal.feature.xmb.ui

import com.playfieldportal.core.ui.components.PfpModalSpec
import com.playfieldportal.feature.xmb.viewmodel.CollectionNameDialogState
import com.playfieldportal.feature.xmb.viewmodel.InfoDialogState
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

    @Test
    fun `a name entry wins over the info notice, matching the order presses are forwarded in`() {
        val both = XMBUiState(
            collectionNameDialog = CollectionNameDialogState(title = "New Collection"),
            infoDialog = InfoDialogState("t", "m"),
        )

        assertTrue(specFor(both) is PfpModalSpec.TextEntry)
    }
}
