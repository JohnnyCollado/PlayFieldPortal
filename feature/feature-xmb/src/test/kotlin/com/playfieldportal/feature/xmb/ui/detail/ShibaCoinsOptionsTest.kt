package com.playfieldportal.feature.xmb.ui.detail

import com.playfieldportal.core.domain.achievement.AchievementProvider
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Triangle Options menu for a game's coins page: Sort always, Sync Now wherever there is a
 * provider identity to sync against, and Change Match only for the one match the user supplied.
 */
class ShibaCoinsOptionsTest {

    private fun state(
        provider: AchievementProvider = AchievementProvider.RETRO_ACHIEVEMENTS,
        linked: Boolean = true,
        accountOnly: Boolean = false,
        sort: CoinSort = CoinSort.TIER,
        isSyncing: Boolean = false,
        group: CoinOptionGroup? = null,
    ) = ShibaCoinsUiState(
        provider = provider,
        linked = linked,
        accountOnly = accountOnly,
        sort = sort,
        isSyncing = isSyncing,
        options = CoinOptionsMenu(group = group),
    )

    private fun labels(state: ShibaCoinsUiState) = coinOptionRows(state).map { it.label }

    @Test
    fun `a linked RetroAchievements game offers Sort and Sync Now`() {
        assertEquals(listOf("Sort (Tier)", "Refresh this game"), labels(state()))
    }

    @Test
    fun `a linked Steam game adds Change Match`() {
        val rows = labels(state(provider = AchievementProvider.STEAM))
        assertEquals(listOf("Sort (Tier)", "Refresh this game", "Change Match"), rows)
    }

    @Test
    fun `an account entry can sync but has no match to change`() {
        val rows = labels(state(provider = AchievementProvider.STEAM, linked = false, accountOnly = true))
        assertEquals(listOf("Sort (Tier)", "Refresh this game"), rows)
    }

    @Test
    fun `an unlinked game offers Sort only`() {
        assertEquals(listOf("Sort (Tier)"), labels(state(linked = false)))
    }

    @Test
    fun `the root row names the active sort`() {
        assertEquals("Sort (Rarest)", labels(state(sort = CoinSort.RAREST)).first())
    }

    @Test
    fun `a sync in flight says so`() {
        assertTrue("Refreshing…" in labels(state(isSyncing = true)))
    }

    @Test
    fun `the Sort list checks the active sort`() {
        val rows = coinOptionRows(state(sort = CoinSort.EARNED, group = CoinOptionGroup.SORT))
        assertEquals(listOf("Tier", "Earned", "Rarest"), rows.map { it.label })
        assertEquals(listOf(false, true, false), rows.map { it.checked })
    }

    @Test
    fun `every Sort row carries its own sort`() {
        val rows = coinOptionRows(state(group = CoinOptionGroup.SORT))
        assertEquals(CoinSort.entries.map { CoinOption.Sort(it) }, rows.map { it.option })
    }
}
