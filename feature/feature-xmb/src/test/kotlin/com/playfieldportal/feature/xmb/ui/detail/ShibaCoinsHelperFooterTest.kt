package com.playfieldportal.feature.xmb.ui.detail

import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.core.domain.model.GamepadAction
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

    @Test
    fun `an open Options menu documents only Select and Close`() {
        val state = state(platinum, focusedRowId = PLATINUM_ROW_ID).copy(options = CoinOptionsMenu())

        assertEquals(listOf("Select", "Close"), labels(state))
        assertEquals(
            listOf(listOf(GamepadAction.SELECT), listOf(GamepadAction.BACK)),
            shibaCoinsHelperItems(state).map { it.actions },
        )
    }

    @Test
    fun `text entry documents only Done`() {
        val state = state(platinum).copy(searchEditing = true)

        assertEquals(listOf("Done"), labels(state))
    }

    @Test
    fun `the pinned Search row says Type`() {
        val state = state(platinum, coin("a"), focusedRowId = null)

        assertEquals(listOf("Type", "Search", "Options", "Change View", "Back"), labels(state))
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
    fun `a hidden unearned coin offers Reveal, and Hide once revealed`() {
        val hidden = coin("secret", hidden = true)
        val focused = state(hidden, focusedRowId = "secret")

        assertEquals("Reveal", labels(focused).first())
        assertEquals("Hide", labels(focused.copy(revealedIds = setOf("secret"))).first())
    }

    @Test
    fun `an ordinary coin has no Confirm`() {
        val state = state(coin("plain"), focusedRowId = "plain")

        assertEquals(listOf("Search", "Options", "Change View", "Back"), labels(state))
    }

    @Test
    fun `an earned hidden coin has no Confirm - there is nothing left to reveal`() {
        val state = state(coin("done", hidden = true, earned = true), focusedRowId = "done")

        assertEquals(listOf("Search", "Options", "Change View", "Back"), labels(state))
    }

    @Test
    fun `the Platinum Crown has no Confirm`() {
        val state = state(platinum, focusedRowId = PLATINUM_ROW_ID)

        assertEquals(listOf("Search", "Options", "Change View", "Back"), labels(state))
    }

    @Test
    fun `an unlinked game that can match offers Auto-Match, Options and Back`() {
        val state = ShibaCoinsUiState(
            rows = listOf(CoinListItem.LinkPanel),
            focusedRowId = LINK_ROW_ID,
            provider = AchievementProvider.RETRO_ACHIEVEMENTS,
        )

        assertEquals(listOf("Auto-Match", "Options", "Back"), labels(state))
    }

    @Test
    fun `an unlinked game with no match flow offers no Confirm`() {
        val state = ShibaCoinsUiState(
            rows = listOf(CoinListItem.LinkPanel),
            focusedRowId = LINK_ROW_ID,
            provider = AchievementProvider.LOCAL_STEAM,
        )

        assertEquals(listOf("Options", "Back"), labels(state))
    }
}
