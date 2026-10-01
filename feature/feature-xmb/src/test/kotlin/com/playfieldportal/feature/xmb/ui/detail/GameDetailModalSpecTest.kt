package com.playfieldportal.feature.xmb.ui.detail

import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.ui.components.PfpModalSpec
import com.playfieldportal.feature.xmb.ui.collection.CollectionPickerUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which shared modal Game Detail shows for a given UI state. Edit Title and Edit Note are the same
 * text entry the XMB's own use, with the same rule: a cleared field is a valid answer, and means
 * "back to the default title" or "no note". Removing a game is destructive, so it opens on Cancel.
 */
class GameDetailModalSpecTest {

    private val events = mutableListOf<String>()

    private val game = Game(id = 7L, title = "pe2_usa", platformId = "psx")
    private val baseState = GameDetailUiState(isLoading = false, game = game)

    private fun specFor(state: GameDetailUiState): PfpModalSpec? = gameDetailModalSpec(
        state = state,
        onSaveTitle = { events += "title:$it" },
        onCancelTitle = { events += "cancel-title" },
        onSaveNote = { events += "note:$it" },
        onCancelNote = { events += "cancel-note" },
        onConfirmRemove = { events += "remove" },
        onCancelRemove = { events += "cancel-remove" },
        onCreateCollection = { events += "collection:$it" },
        onCancelCreateCollection = { events += "cancel-collection" },
    )

    @Test
    fun `a new collection starts empty and can never be blank`() {
        val picker = CollectionPickerUi(visible = true, showCreateDialog = true)

        val spec = specFor(baseState.copy(collectionPicker = picker)) as PfpModalSpec.TextEntry

        assertEquals("New Collection", spec.title)
        assertEquals("", spec.initial)
        assertEquals("Create", spec.confirmLabel)
        assertFalse(spec.allowBlank)

        spec.onConfirm("RPGs")
        spec.onCancel()
        assertEquals(listOf("collection:RPGs", "cancel-collection"), events)
    }

    @Test
    fun `nothing is shown on the plain page`() {
        assertNull(specFor(baseState))
    }

    @Test
    fun `edit title starts from the title on screen`() {
        val renamed = game.copy(scrapedTitle = "Parasite Eve II", userTitleOverride = "PE2")

        val spec = specFor(baseState.copy(game = renamed, isEditingTitle = true)) as PfpModalSpec.TextEntry

        assertEquals("Edit Title", spec.title)
        assertEquals("PE2", spec.initial)
    }

    @Test
    fun `a cleared title is allowed, and the empty field shows the default it goes back to`() {
        val scraped = specFor(
            baseState.copy(game = game.copy(scrapedTitle = "Parasite Eve II", userTitleOverride = "PE2"), isEditingTitle = true),
        ) as PfpModalSpec.TextEntry
        val unscraped = specFor(baseState.copy(isEditingTitle = true)) as PfpModalSpec.TextEntry

        assertTrue(scraped.allowBlank)
        assertEquals("Parasite Eve II", scraped.placeholder)
        assertEquals("pe2_usa", unscraped.placeholder)
    }

    @Test
    fun `title save and cancel route to the view model`() {
        val spec = specFor(baseState.copy(isEditingTitle = true)) as PfpModalSpec.TextEntry

        spec.onConfirm("Parasite Eve II")
        spec.onCancel()

        assertEquals(listOf("title:Parasite Eve II", "cancel-title"), events)
    }

    @Test
    fun `edit note starts from the saved note and may be cleared`() {
        val withNote = specFor(
            baseState.copy(game = game.copy(userNote = "Disc 2 save is on card B"), isEditingNote = true),
        ) as PfpModalSpec.TextEntry
        val withoutNote = specFor(baseState.copy(isEditingNote = true)) as PfpModalSpec.TextEntry

        assertEquals("Edit Note", withNote.title)
        assertEquals("Disc 2 save is on card B", withNote.initial)
        assertEquals("", withoutNote.initial)
        assertTrue(withNote.allowBlank)

        withNote.onConfirm("")
        withNote.onCancel()
        assertEquals(listOf("note:", "cancel-note"), events)
    }

    @Test
    fun `removing a game is a destructive confirm that names the game`() {
        val renamed = game.copy(userTitleOverride = "Parasite Eve II")

        val spec = specFor(baseState.copy(game = renamed, confirmRemove = true)) as PfpModalSpec.Confirm

        assertEquals("Remove from Library", spec.title)
        assertTrue(spec.message.contains("Parasite Eve II"))
        assertEquals("Remove", spec.confirmLabel)
        assertTrue(spec.destructive)
        // A stray press must never delete anything.
        assertTrue(spec.openOnCancel)

        spec.onCancel()
        spec.onConfirm()
        assertEquals(listOf("cancel-remove", "remove"), events)
    }

    @Test
    fun `the removal prompt wins over an editor, matching the order the view model checks them`() {
        val both = baseState.copy(confirmRemove = true, isEditingNote = true, isEditingTitle = true)

        assertTrue(specFor(both) is PfpModalSpec.Confirm)
        assertEquals("Edit Note", (specFor(both.copy(confirmRemove = false)) as PfpModalSpec.TextEntry).title)
    }
}
