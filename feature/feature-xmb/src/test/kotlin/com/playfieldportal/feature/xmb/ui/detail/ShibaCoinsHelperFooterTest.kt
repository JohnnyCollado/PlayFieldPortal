package com.playfieldportal.feature.xmb.ui.detail

import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.text.UiText
import com.playfieldportal.feature.xmb.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The coins page's helper footer is its controller documentation, so it is a pure function of the
 * state: Confirm is named for what it would actually do on the focused row and is left out when it
 * would do nothing, and a modal (Options, text entry) replaces the page hints rather than adding
 * to them.
 */
class ShibaCoinsHelperFooterTest {

    private val platinum = CoinListItem.Platinum(earned = 3, total = 10, isMastered = false)

    private fun coin(id: String, hidden: Boolean = false, earned: Boolean = false) = CoinListItem.Coin(
        CoinRow(
            id = id,
            tier = ShibaTier.BRONZE,
            title = id,
            description = "",
            globalRarity = 10.0,
            iconUrl = null,
            isHidden = hidden,
            isEarned = earned,
            earnedAt = null,
        ),
    )

    private fun state(vararg rows: CoinListItem, focusedRowId: String? = null) =
        ShibaCoinsUiState(rows = rows.toList(), focusedRowId = focusedRowId, linked = true)

    private fun labels(state: ShibaCoinsUiState) = shibaCoinsHelperItems(state).map { it.label }

    private fun texts(vararg ids: Int) = ids.map { UiText.res(it) }

    @Test
    fun `an open Options menu documents only Select and Close`() {
        val state = state(platinum, focusedRowId = PLATINUM_ROW_ID).copy(options = CoinOptionsMenu())

        assertEquals(texts(R.string.shiba_prompt_select, R.string.shiba_prompt_close), labels(state))
        assertEquals(
            listOf(listOf(GamepadAction.SELECT), listOf(GamepadAction.BACK)),
            shibaCoinsHelperItems(state).map { it.actions },
        )
    }

    @Test
    fun `text entry documents only Done`() {
        val state = state(platinum).copy(searchEditing = true)

        assertEquals(texts(R.string.shiba_prompt_done), labels(state))
    }

    @Test
    fun `the pinned Search row says Type`() {
        val state = state(platinum, coin("a"), focusedRowId = null)

        assertEquals(
            texts(R.string.shiba_prompt_type, R.string.shiba_prompt_search, R.string.shiba_prompt_options, R.string.shiba_prompt_change_view, R.string.shiba_prompt_back),
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
            shibaCoinsHelperItems(state).map { it.actions },
        )
    }

    @Test
    fun `a game with two sets gives L and R to the source and moves the view to the D-pad`() {
        val state = state(platinum, coin("a"), focusedRowId = null).copy(
            sources = listOf(
                CoinSource(AchievementProvider.LOCAL_STEAM, earned = 1, total = 2),
                CoinSource(AchievementProvider.STEAM, earned = 2, total = 2),
            ),
        )

        assertEquals(
            texts(
                R.string.shiba_prompt_type,
                R.string.shiba_prompt_search,
                R.string.shiba_prompt_options,
                R.string.shiba_prompt_switch_source,
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
                listOf(GamepadAction.NAVIGATE_LEFT, GamepadAction.NAVIGATE_RIGHT),
                listOf(GamepadAction.BACK),
            ),
            shibaCoinsHelperItems(state).map { it.actions },
        )
    }

    @Test
    fun `a hidden unearned coin offers Reveal, and Hide once revealed`() {
        val hidden = coin("secret", hidden = true)
        val focused = state(hidden, focusedRowId = "secret")

        assertEquals(UiText.res(R.string.shiba_prompt_reveal), labels(focused).first())
        assertEquals(UiText.res(R.string.shiba_prompt_hide), labels(focused.copy(revealedIds = setOf("secret"))).first())
    }

    @Test
    fun `an ordinary coin has no Confirm`() {
        val state = state(coin("plain"), focusedRowId = "plain")

        assertEquals(texts(R.string.shiba_prompt_search, R.string.shiba_prompt_options, R.string.shiba_prompt_change_view, R.string.shiba_prompt_back), labels(state))
    }

    @Test
    fun `an earned hidden coin has no Confirm - there is nothing left to reveal`() {
        val state = state(coin("done", hidden = true, earned = true), focusedRowId = "done")

        assertEquals(texts(R.string.shiba_prompt_search, R.string.shiba_prompt_options, R.string.shiba_prompt_change_view, R.string.shiba_prompt_back), labels(state))
    }

    @Test
    fun `the Platinum Crown has no Confirm`() {
        val state = state(platinum, focusedRowId = PLATINUM_ROW_ID)

        assertEquals(texts(R.string.shiba_prompt_search, R.string.shiba_prompt_options, R.string.shiba_prompt_change_view, R.string.shiba_prompt_back), labels(state))
    }

    @Test
    fun `an unlinked game that can match offers Auto-Match, Options and Back`() {
        val state = ShibaCoinsUiState(
            rows = listOf(CoinListItem.LinkPanel),
            focusedRowId = LINK_ROW_ID,
            provider = AchievementProvider.RETRO_ACHIEVEMENTS,
        )

        assertEquals(texts(R.string.shiba_prompt_auto_match, R.string.shiba_prompt_options, R.string.shiba_prompt_back), labels(state))
    }

    @Test
    fun `an unlinked game with no match flow offers no Confirm`() {
        val state = ShibaCoinsUiState(
            rows = listOf(CoinListItem.LinkPanel),
            focusedRowId = LINK_ROW_ID,
            provider = AchievementProvider.VITA_TROPHY,
        )

        assertEquals(texts(R.string.shiba_prompt_options, R.string.shiba_prompt_back), labels(state))
    }

    // Survey P3 (docs/audits/codebase-survey-2026-10-22.md): the Local Steam panel draws a focused
    // "Pick Game Folder" button, so Confirm must press it and the footer must say so.
    @Test
    fun `an unlinked Local Steam game offers Pick Game Folder on Confirm`() {
        val state = ShibaCoinsUiState(
            rows = listOf(CoinListItem.LinkPanel),
            focusedRowId = LINK_ROW_ID,
            provider = AchievementProvider.LOCAL_STEAM,
        )

        assertEquals(texts(R.string.shiba_prompt_pick_game_folder, R.string.shiba_prompt_options, R.string.shiba_prompt_back), labels(state))
    }
}
