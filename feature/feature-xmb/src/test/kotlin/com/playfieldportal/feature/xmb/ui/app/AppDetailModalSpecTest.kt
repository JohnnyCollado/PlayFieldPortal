package com.playfieldportal.feature.xmb.ui.app

import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.ui.components.PfpModalSpec
import com.playfieldportal.feature.xmb.ui.collection.CollectionPickerUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which shared modal App Detail shows for a given UI state. The display name may be cleared (back
 * to the app's own label) while a collection name never can.
 */
class AppDetailModalSpecTest {

    private val events = mutableListOf<String>()

    private val app = Game(id = 3L, title = "PPSSPP Gold", platformId = "android", packageName = "org.ppsspp.ppssppgold")
    private val baseState = AppDetailUiState(game = app, isLoading = false)

    private fun specFor(state: AppDetailUiState): PfpModalSpec? = appDetailModalSpec(
        state = state,
        onSaveName = { events += "name:$it" },
        onCancelName = { events += "cancel-name" },
        onCreateCollection = { events += "collection:$it" },
        onCancelCreateCollection = { events += "cancel-collection" },
    )

    @Test
    fun `nothing is shown on the plain page`() {
        assertNull(specFor(baseState))
    }

    @Test
    fun `change display name starts from the name on screen and may be cleared back to the app label`() {
        val renamed = app.copy(userTitleOverride = "PPSSPP")

        val spec = specFor(baseState.copy(game = renamed, isEditingName = true)) as PfpModalSpec.TextEntry

        assertEquals("Change Display Name", spec.title)
        assertEquals("PPSSPP", spec.initial)
        assertTrue(spec.allowBlank)
        assertEquals("PPSSPP Gold", spec.placeholder)

        spec.onConfirm("")
        spec.onCancel()
        assertEquals(listOf("name:", "cancel-name"), events)
    }

    @Test
    fun `a new collection starts empty and can never be blank`() {
        val picker = CollectionPickerUi(visible = true, showCreateDialog = true)

        val spec = specFor(baseState.copy(collectionPicker = picker)) as PfpModalSpec.TextEntry

        assertEquals("New Collection", spec.title)
        assertEquals("", spec.initial)
        assertEquals("Create", spec.confirmLabel)
        assertFalse(spec.allowBlank)

        spec.onConfirm("Emulators")
        spec.onCancel()
        assertEquals(listOf("collection:Emulators", "cancel-collection"), events)
    }

    @Test
    fun `the name entry wins over the collection prompt, as it does for presses`() {
        val both = baseState.copy(
            isEditingName = true,
            collectionPicker = CollectionPickerUi(visible = true, showCreateDialog = true),
        )

        assertEquals("Change Display Name", (specFor(both) as PfpModalSpec.TextEntry).title)
    }
}
