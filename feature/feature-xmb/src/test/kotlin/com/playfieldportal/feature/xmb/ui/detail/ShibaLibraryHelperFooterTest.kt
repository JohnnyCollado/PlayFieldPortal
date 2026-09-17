package com.playfieldportal.feature.xmb.ui.detail

import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.feature.xmb.viewmodel.ShibaLibraryMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The helper footer is the library's controller documentation (design §13), so it is a pure
 * function of the state: Confirm is named for what it would actually do, and a modal replaces the
 * page hints rather than adding to them.
 */
class ShibaLibraryHelperFooterTest {

    private val trackedRow = row(id = "STEAM:1", title = "Half-Life 2", reason = null, target = ShibaCoinsTarget.LibraryGame(1L))
    private val matchableRow = row(id = "untracked:2", title = "Sonic Adventure 2", reason = "Not found on Steam", target = ShibaCoinsTarget.LibraryGame(2L))
    private val unmatchableRow = row(id = "untracked:3", title = "Gravity Rush", reason = "System not supported", target = null)

    private fun labels(state: ShibaLibraryUiState) = shibaLibraryHelperItems(state).map { it.label }

    @Test
    fun `a tracked game documents View Achievements, Search, Options, Change View and Back`() {
        val state = ShibaLibraryUiState(rows = listOf(trackedRow), focusedRowId = trackedRow.id)

        assertEquals(listOf("View Achievements", "Search", "Options", "Change View", "Back"), labels(state))
        assertEquals(
            listOf(
                listOf(GamepadAction.SELECT),
                listOf(GamepadAction.CHANGE_SORT),
                listOf(GamepadAction.OPEN_CONTEXT_MENU),
                listOf(GamepadAction.PREV_CATEGORY, GamepadAction.NEXT_CATEGORY),
                listOf(GamepadAction.BACK),
            ),
            shibaLibraryHelperItems(state).map { it.actions },
        )
    }

    @Test
    fun `a matchable untracked game says Attempt Match`() {
        val state = ShibaLibraryUiState(mode = ShibaLibraryMode.UNTRACKED, rows = listOf(matchableRow), focusedRowId = matchableRow.id)

        assertEquals("Attempt Match", labels(state).first())
    }

    @Test
    fun `an untracked game nothing can match offers no Confirm prompt`() {
        val state = ShibaLibraryUiState(mode = ShibaLibraryMode.UNTRACKED, rows = listOf(unmatchableRow), focusedRowId = unmatchableRow.id)

        assertEquals(listOf("Search", "Options", "Change View", "Back"), labels(state))
    }

    @Test
    fun `a focused Search says Confirm types`() {
        val state = ShibaLibraryUiState(rows = listOf(trackedRow), focusedRowId = null)

        assertEquals("Type", labels(state).first())
    }

    @Test
    fun `text entry and the Options menu replace the page hints`() {
        assertEquals(listOf("Done"), labels(ShibaLibraryUiState(searchEditing = true)))
        assertEquals(listOf("Select", "Close"), labels(ShibaLibraryUiState(options = LibraryOptionsMenu())))
    }

    @Test
    fun `the Options root names each list with its current choice, like Icon Display`() {
        val state = ShibaLibraryUiState(sortField = LibrarySortField.PROGRESS, sortAscending = false, providerFilter = LibraryProviderFilter.STEAM)

        val rows = libraryOptionRows(state)

        assertEquals(listOf("Filter (Progress Highest First)", "Provider (Steam)"), rows.map { it.label })
        assertEquals(
            listOf(LibraryOption.OpenGroup(LibraryOptionGroup.FILTER), LibraryOption.OpenGroup(LibraryOptionGroup.PROVIDER)),
            rows.map { it.option },
        )
    }

    @Test
    fun `the Provider list offers every provider PFP supports and checks the active one`() {
        val state = ShibaLibraryUiState(options = LibraryOptionsMenu(group = LibraryOptionGroup.PROVIDER))

        val rows = libraryOptionRows(state)

        assertEquals(listOf("All", "RetroAchievements", "Steam", "Local Steam", "PS Vita"), rows.map { it.label })
        assertEquals(listOf("All"), rows.filter { it.checked }.map { it.label })
    }

    @Test
    fun `empty messages keep the shell and explain the empty list`() {
        assertEquals("No tracked games yet.", ShibaLibraryUiState().emptyMessage)
        assertEquals("Every eligible game is tracked.", ShibaLibraryUiState(mode = ShibaLibraryMode.UNTRACKED).emptyMessage)
        assertEquals("No games match \"Final Fantasy\".", ShibaLibraryUiState(query = "Final Fantasy").emptyMessage)
    }

    private fun row(id: String, title: String, reason: String?, target: ShibaCoinsTarget?) = ShibaLibraryRow(
        id = id,
        coinsTarget = target,
        title = title,
        platformLabel = "Steam",
        provider = null,
        platformSortKey = "Windows",
        icon0Uri = null,
        progress = 0f,
        coins = LibraryCoinCounts(),
        reason = reason,
    )
}
