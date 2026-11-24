package com.playfieldportal.feature.xmb.ui.detail

import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.ui.components.PfpModalSpec
import com.playfieldportal.feature.artwork.match.MatchProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The two Artwork Studio confirms that go through the shared PFP confirm modal. They read their
 * wording from resources, so they run under Robolectric (moved here from ArtworkStudioViewModelTest,
 * which runs without an Android resource table).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StudioDestructiveModalSpecTest {

    private val res get() = RuntimeEnvironment.getApplication().resources

    private val game = Game(
        id = 1L,
        title = "cr4sh bandicoot (u) [!]",
        platformId = "psx",
        romPath = "/roms/psx/cr4sh bandicoot (u) [!].bin",
        steamGridDbId = null,
    )

    @Test
    fun `the Clear confirm is destructive, opens on Cancel and names the backup that goes too`() {
        val state = ArtworkStudioUiState(game = game, destructiveConfirm = StudioDestructive.CLEAR_ARTWORK)
        val events = mutableListOf<String>()

        val spec = studioDestructiveModalSpec(state, { events += "confirm" }, { events += "cancel" }, res)
            as PfpModalSpec.Confirm

        assertTrue(spec.destructive)
        assertTrue(spec.openOnCancel)
        assertEquals("Clear Artwork?", spec.title)
        assertTrue(spec.message, "backup" in spec.message)
        assertTrue(spec.message, res.getString(STUDIO_TABS[0].labelRes) in spec.message)
        spec.onConfirm()
        spec.onCancel()
        assertEquals(listOf("confirm", "cancel"), events)
    }

    @Test
    fun `the Forget confirm names the provider and no confirm shows otherwise`() {
        val forget = ArtworkStudioUiState(
            game = game, matchProvider = MatchProvider.STEAMGRIDDB,
            destructiveConfirm = StudioDestructive.FORGET_MATCH,
        )

        val spec = studioDestructiveModalSpec(forget, {}, {}, res) as PfpModalSpec.Confirm

        assertEquals("Forget Match?", spec.title)
        assertTrue(spec.message, MatchProvider.STEAMGRIDDB.label in spec.message)
        assertNull(studioDestructiveModalSpec(ArtworkStudioUiState(game = game), {}, {}, res))
    }
}
