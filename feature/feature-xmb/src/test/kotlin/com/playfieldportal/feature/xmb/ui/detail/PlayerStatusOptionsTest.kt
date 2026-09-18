package com.playfieldportal.feature.xmb.ui.detail

import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.achievement.ShibaTier
import org.junit.Test
import kotlin.test.assertEquals

class PlayerStatusOptionsTest {
    private fun state(
        sort: PlayerStatusSort = PlayerStatusSort.NEWEST,
        provider: PlayerStatusProviderFilter = PlayerStatusProviderFilter.ALL,
        group: PlayerStatusOptionGroup? = null,
    ) = PlayerStatusUiState(
        sort = sort,
        providerFilter = provider,
        options = PlayerStatusOptionsMenu(group = group),
    )

    @Test
    fun `root options expose sort provider and sync`() {
        assertEquals(
            listOf("Sort (Newest)", "Provider (All)", "Sync All Games"),
            playerStatusOptionRows(state()).map { it.label },
        )
    }

    @Test
    fun `sort and provider groups check active values`() {
        val sort = playerStatusOptionRows(state(sort = PlayerStatusSort.RAREST, group = PlayerStatusOptionGroup.SORT))
        assertEquals(listOf(false, true), sort.map { it.checked })

        val providers = playerStatusOptionRows(state(provider = PlayerStatusProviderFilter.STEAM, group = PlayerStatusOptionGroup.PROVIDER))
        assertEquals("Steam", providers.first { it.checked }.label)
        assertEquals(AchievementProvider.STEAM, PlayerStatusProviderFilter.STEAM.provider)
    }

    @Test
    fun `options replace page helper actions`() {
        assertEquals(listOf("Select", "Close"), playerStatusHelperItems(state()).map { it.label })
    }

    @Test
    fun `a focused game row offers View Game`() {
        val row = RecentRow("recent", "Coin", "Game", ShibaTier.GOLD, null, 1L, -1.0, AchievementProvider.STEAM, ShibaCoinsTarget.LibraryGame(1L))
        val state = PlayerStatusUiState(recent = listOf(row), focusedId = row.id)
        assertEquals(listOf("View Game", "Options", "Back"), playerStatusHelperItems(state).map { it.label })
    }
}
