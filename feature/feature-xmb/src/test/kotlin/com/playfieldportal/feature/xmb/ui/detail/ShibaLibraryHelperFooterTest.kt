package com.playfieldportal.feature.xmb.ui.detail

import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.text.UiText
import com.playfieldportal.feature.xmb.R
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
    private val matchableRow = row(id = "untracked:2", title = "Sonic Adventure 2", reason = UiText.raw("Not found on Steam"), target = ShibaCoinsTarget.LibraryGame(2L))
    private val unmatchableRow = row(id = "untracked:3", title = "Gravity Rush", reason = UiText.raw("System not supported"), target = null)

    private fun labels(state: ShibaLibraryUiState) = shibaLibraryHelperItems(state).map { it.label }

    private fun texts(vararg ids: Int) = ids.map { UiText.res(it) }

    @Test
    fun `a tracked game documents View Achievements, Search, Options, Change View and Back`() {
        val state = ShibaLibraryUiState(rows = listOf(trackedRow), focusedRowId = trackedRow.id)

        assertEquals(
            texts(
                R.string.shiba_prompt_view_achievements,
                R.string.shiba_prompt_search,
                R.string.shiba_prompt_options,
                R.string.shiba_prompt_change_view,
                R.string.shiba_prompt_back,
            ),
            labels(state),
        )
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

        assertEquals(UiText.res(R.string.shiba_prompt_attempt_match), labels(state).first())
    }

    @Test
    fun `an untracked game nothing can match offers no Confirm prompt`() {
        val state = ShibaLibraryUiState(mode = ShibaLibraryMode.UNTRACKED, rows = listOf(unmatchableRow), focusedRowId = unmatchableRow.id)

        assertEquals(texts(R.string.shiba_prompt_search, R.string.shiba_prompt_options, R.string.shiba_prompt_change_view, R.string.shiba_prompt_back), labels(state))
    }

    @Test
    fun `a focused Search says Confirm types`() {
        val state = ShibaLibraryUiState(rows = listOf(trackedRow), focusedRowId = null)

        assertEquals(UiText.res(R.string.shiba_prompt_type), labels(state).first())
    }

    @Test
    fun `text entry and the Options menu replace the page hints`() {
        assertEquals(texts(R.string.shiba_prompt_done), labels(ShibaLibraryUiState(searchEditing = true)))
        assertEquals(texts(R.string.shiba_prompt_select, R.string.shiba_prompt_close), labels(ShibaLibraryUiState(options = LibraryOptionsMenu())))
    }

    @Test
    fun `the Options root names each list with its current choice, like Icon Display`() {
        val state = ShibaLibraryUiState(sortField = LibrarySortField.PROGRESS, sortAscending = false, providerFilter = LibraryProviderFilter.STEAM)

        val rows = libraryOptionRows(state)

        assertEquals(
            texts(R.string.shiba_options_sort, R.string.shiba_options_provider, R.string.shiba_options_update_installed),
            rows.map { it.label },
        )
        assertEquals(listOf(UiText.res(R.string.shiba_library_sort_progress_desc), UiText.raw("Steam"), null), rows.map { it.value })
        assertEquals(
            listOf(LibraryOption.OpenGroup(LibraryOptionGroup.SORT), LibraryOption.OpenGroup(LibraryOptionGroup.PROVIDER), LibraryOption.SyncAll),
            rows.map { it.option },
        )
    }

    @Test
    fun `the Provider list offers every provider PFP supports and checks the active one`() {
        val state = ShibaLibraryUiState(options = LibraryOptionsMenu(group = LibraryOptionGroup.PROVIDER))

        val rows = libraryOptionRows(state)

        assertEquals(
            listOf(UiText.res(R.string.shiba_filter_all)) +
                listOf("RetroAchievements", "Steam", "Local Steam", "PS Vita", "PS3", "Xbox 360").map { UiText.raw(it) },
            rows.map { it.label },
        )
        assertEquals(listOf(UiText.res(R.string.shiba_filter_all)), rows.filter { it.checked }.map { it.label })
    }

    @Test
    fun `the pinned Search online row names Confirm for what it opens`() {
        val searchOnline = ShibaLibraryRow(
            id = SEARCH_ONLINE_ROW_ID,
            coinsTarget = null,
            title = "Search online",
            platformLabel = UiText.res(R.string.shiba_library_search_online_subtitle),
            provider = null,
            platformSortKey = "",
            icon0Uri = null,
            progress = 0f,
            coins = LibraryCoinCounts(),
            reason = null,
            action = LibraryRowAction.SEARCH_ONLINE,
        )
        val state = ShibaLibraryUiState(rows = listOf(searchOnline), focusedRowId = searchOnline.id)

        assertEquals(UiText.res(R.string.shiba_prompt_search_online), labels(state).first())
        assertEquals("an action row is not a tracked game", false, searchOnline.isTracked)
    }

    @Test
    fun `empty messages keep the shell and explain the empty list`() {
        assertEquals(UiText.res(R.string.shiba_library_empty_tracked), ShibaLibraryUiState().emptyMessage)
        assertEquals(UiText.res(R.string.shiba_library_empty_untracked), ShibaLibraryUiState(mode = ShibaLibraryMode.UNTRACKED).emptyMessage)
        assertEquals(UiText.res(R.string.shiba_library_empty_no_match, "Final Fantasy"), ShibaLibraryUiState(query = "Final Fantasy").emptyMessage)
    }

    private fun row(id: String, title: String, reason: UiText?, target: ShibaCoinsTarget?) = ShibaLibraryRow(
        id = id,
        coinsTarget = target,
        title = title,
        platformLabel = UiText.raw("Steam"),
        provider = null,
        platformSortKey = "Windows",
        icon0Uri = null,
        progress = 0f,
        coins = LibraryCoinCounts(),
        reason = reason,
    )
}
