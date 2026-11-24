package com.playfieldportal.feature.xmb.ui.detail

import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.text.UiText
import com.playfieldportal.core.ui.components.PfpModalSpec
import com.playfieldportal.feature.xmb.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Triangle Options menu for a game's coins page (mockup 4): Sort always, carrying its current
 * choice as a value; Update Achievements wherever there is a provider identity to update against;
 * Change Match only for the one match the user supplied; Unlink Game red and last.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShibaCoinsOptionsTest {

    private val res get() = RuntimeEnvironment.getApplication().resources

    private fun texts(vararg ids: Int) = ids.map { UiText.res(it) }

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
    fun `a linked RetroAchievements game offers Sort, Update Achievements and Unlink`() {
        assertEquals(
            texts(R.string.shiba_options_sort, R.string.shiba_coins_options_update, R.string.shiba_coins_options_unlink),
            labels(state()),
        )
    }

    @Test
    fun `a linked Steam game adds Change Match`() {
        val rows = labels(state(provider = AchievementProvider.STEAM))
        assertEquals(
            texts(
                R.string.shiba_options_sort,
                R.string.shiba_coins_options_update,
                R.string.shiba_coins_options_change_match,
                R.string.shiba_coins_options_unlink,
            ),
            rows,
        )
    }

    @Test
    fun `an account entry can sync but has no match to change or link to remove`() {
        val rows = labels(state(provider = AchievementProvider.STEAM, linked = false, accountOnly = true))
        assertEquals(texts(R.string.shiba_options_sort, R.string.shiba_coins_options_update), rows)
    }

    @Test
    fun `an unlinked game offers Sort only`() {
        assertEquals(texts(R.string.shiba_options_sort), labels(state(linked = false)))
    }

    @Test
    fun `Unlink Game is the last row and the only destructive one`() {
        val rows = coinOptionRows(state(provider = AchievementProvider.STEAM))
        assertEquals(UiText.res(R.string.shiba_coins_options_unlink), rows.last().label)
        assertEquals(listOf(false, false, false, true), rows.map { it.isDestructive })
    }

    @Test
    fun `the Unlink confirm is a destructive modal that names the game`() {
        val spec = coinUnlinkModalSpec(
            resources = res,
            state = state().copy(title = "Halo", unlinkConfirm = true),
            onConfirm = {},
            onCancel = {},
        ) as PfpModalSpec.Confirm
        assertTrue(spec.destructive)
        assertEquals("Unlink", spec.confirmLabel)
        assertTrue("Halo" in spec.message)
    }

    @Test
    fun `no modal is built unless an unlink is being asked`() {
        assertNull(coinUnlinkModalSpec(res, state(), onConfirm = {}, onCancel = {}))
    }

    @Test
    fun `the Sort row carries the active sort as its value and opens a list`() {
        val sort = coinOptionRows(state(sort = CoinSort.RAREST)).first()
        assertEquals(UiText.res(R.string.shiba_options_sort), sort.label)
        assertEquals(UiText.res(R.string.shiba_sort_rarest), sort.value)
        assertTrue(sort.opensMenu)
    }

    @Test
    fun `Change Match opens a menu and Update Achievements does not`() {
        val rows = coinOptionRows(state(provider = AchievementProvider.STEAM)).associateBy { it.label }
        assertTrue(rows.getValue(UiText.res(R.string.shiba_coins_options_change_match)).opensMenu)
        assertEquals(false, rows.getValue(UiText.res(R.string.shiba_coins_options_update)).opensMenu)
        assertEquals(false, rows.getValue(UiText.res(R.string.shiba_coins_options_unlink)).opensMenu)
    }

    @Test
    fun `a sync in flight says so`() {
        assertTrue(UiText.res(R.string.shiba_options_updating) in labels(state(isSyncing = true)))
    }

    @Test
    fun `the Sort list checks the active sort`() {
        val rows = coinOptionRows(state(sort = CoinSort.EARNED, group = CoinOptionGroup.SORT))
        assertEquals(texts(R.string.shiba_sort_tier, R.string.shiba_sort_earned, R.string.shiba_sort_rarest), rows.map { it.label })
        assertEquals(listOf(false, true, false), rows.map { it.checked })
    }

    @Test
    fun `every Sort row carries its own sort`() {
        val rows = coinOptionRows(state(group = CoinOptionGroup.SORT))
        assertEquals(CoinSort.entries.map { CoinOption.Sort(it) }, rows.map { it.option })
    }
}
