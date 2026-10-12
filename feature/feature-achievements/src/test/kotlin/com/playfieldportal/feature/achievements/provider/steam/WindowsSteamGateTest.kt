package com.playfieldportal.feature.achievements.provider.steam

import com.playfieldportal.core.domain.achievement.LocalCopyOwnership
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.WindowsGameLabel
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamOwnership
import com.playfieldportal.feature.artwork.match.Storefront
import com.playfieldportal.feature.artwork.match.StorefrontIdentityRecord
import com.playfieldportal.feature.artwork.match.StorefrontMetadataResolver
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a Windows game's Steam app id may become a Steam achievement link.
 *
 * Steam only serves achievements for a game on the account, so a link to one the user does not own
 * never syncs — the copy is a local one, and its progress lives in its own folder. An empty owned
 * list is not evidence either way: the user is asked instead of being guessed for.
 */
class WindowsSteamGateTest {

    private val ownership = mockk<LocalSteamOwnership>()
    private val storefronts = mockk<StorefrontMetadataResolver> {
        coEvery { linkedIdentities(any()) } returns emptyList()
    }
    private val games = mockk<GameRepository> {
        coEvery { getById(any()) } returns null
    }
    private val gate = WindowsSteamGate(ownership, storefronts, games)

    @Test
    fun `an app id in the owned list is owned`() = runTest {
        coEvery { ownership.derive("220") } returns LocalCopyOwnership.OWNED

        assertEquals(WindowsSteamGate.Verdict.OWNED, gate.verdict("220"))
    }

    @Test
    fun `an app id missing from a populated owned list is a local copy`() = runTest {
        coEvery { ownership.derive("1984270") } returns LocalCopyOwnership.NOT_IN_LIBRARY

        assertEquals(WindowsSteamGate.Verdict.LOCAL, gate.verdict("1984270"))
    }

    @Test
    fun `an empty owned list decides nothing`() = runTest {
        coEvery { ownership.derive("220") } returns null

        assertEquals(WindowsSteamGate.Verdict.UNKNOWN, gate.verdict("220"))
    }

    // -- Which Steam id a game already carries -----------------------------------

    private val windows = Game(id = 5L, title = "Digimon Story Time Stranger", platformId = "windows")

    @Test
    fun `the id a GameNative Steam shortcut launches by`() = runTest {
        val game = windows.copy(
            launchIntentUri = "intent:#Intent;action=app.gamenative.LAUNCH_GAME;i.app_id=1984270;S.game_source=STEAM;end",
        )

        assertEquals("1984270", gate.knownSteamAppId(game))
    }

    @Test
    fun `the Steam id captured at import`() = runTest {
        assertEquals("1984270", gate.knownSteamAppId(windows.copy(storefront = "STEAM", storefrontGameId = "1984270")))
        // Another store's id is not a Steam appid.
        assertNull(gate.knownSteamAppId(windows.copy(storefront = "GOG", storefrontGameId = "1984270")))
    }

    @Test
    fun `the Steam id Store Match linked`() = runTest {
        coEvery { storefronts.linkedIdentities(5L) } returns listOf(
            StorefrontIdentityRecord(Storefront.GOG, "1207658930"),
            StorefrontIdentityRecord(Storefront.STEAM, "1984270"),
        )

        assertEquals("1984270", gate.knownSteamAppId(windows))
    }

    @Test
    fun `a Windows game is a local copy only when its known id is not owned`() = runTest {
        val game = windows.copy(storefront = "STEAM", storefrontGameId = "1984270")
        coEvery { ownership.derive("1984270") } returns LocalCopyOwnership.NOT_IN_LIBRARY
        assertTrue(gate.isLocalCopy(game))

        coEvery { ownership.derive("1984270") } returns null
        assertFalse(gate.isLocalCopy(game))

        // No Steam id to check: nothing is known, so it is not called local.
        assertFalse(gate.isLocalCopy(windows))
    }

    // -- The user's label beats the owned list ------------------------------------

    private fun labelled(label: WindowsGameLabel?) {
        coEvery { games.getById(windows.id) } returns windows.copy(windowsLabel = label)
    }

    @Test
    fun `a Steam label is owned whatever the owned list says`() = runTest {
        labelled(WindowsGameLabel.STEAM)
        coEvery { ownership.derive("220") } returns LocalCopyOwnership.NOT_IN_LIBRARY

        assertEquals(WindowsSteamGate.Verdict.OWNED, gate.steamVerdictFor(5L, "220"))
    }

    @Test
    fun `a Local label is a local copy whatever the owned list says`() = runTest {
        labelled(WindowsGameLabel.LOCAL)
        coEvery { ownership.derive("220") } returns LocalCopyOwnership.OWNED

        assertEquals(WindowsSteamGate.Verdict.LOCAL, gate.steamVerdictFor(5L, "220"))
    }

    @Test
    fun `a Non-Steam label refuses Steam`() = runTest {
        labelled(WindowsGameLabel.NON_STEAM)
        coEvery { ownership.derive("220") } returns LocalCopyOwnership.OWNED

        assertEquals(WindowsSteamGate.Verdict.NOT_STEAM, gate.steamVerdictFor(5L, "220"))
    }

    @Test
    fun `no label falls back to the owned list`() = runTest {
        labelled(null)
        coEvery { ownership.derive("220") } returns LocalCopyOwnership.OWNED
        assertEquals(WindowsSteamGate.Verdict.OWNED, gate.steamVerdictFor(5L, "220"))

        coEvery { ownership.derive("220") } returns null
        assertEquals(WindowsSteamGate.Verdict.UNKNOWN, gate.steamVerdictFor(5L, "220"))
    }

    @Test
    fun `a missing game falls back to the owned list`() = runTest {
        coEvery { ownership.derive("220") } returns LocalCopyOwnership.NOT_IN_LIBRARY

        assertEquals(WindowsSteamGate.Verdict.LOCAL, gate.steamVerdictFor(99L, "220"))
    }

    @Test
    fun `isLocalCopy honours a Local label even with no known app id`() = runTest {
        assertFalse(gate.isLocalCopy(windows))

        assertTrue(gate.isLocalCopy(windows.copy(windowsLabel = WindowsGameLabel.LOCAL)))
    }

    @Test
    fun `isLocalCopy is false for Steam and Non-Steam labels`() = runTest {
        val notOwned = windows.copy(storefront = "STEAM", storefrontGameId = "1984270")
        coEvery { ownership.derive("1984270") } returns LocalCopyOwnership.NOT_IN_LIBRARY

        assertFalse(gate.isLocalCopy(notOwned.copy(windowsLabel = WindowsGameLabel.STEAM)))
        assertFalse(gate.isLocalCopy(notOwned.copy(windowsLabel = WindowsGameLabel.NON_STEAM)))
    }

    @Test
    fun `autoLabel maps owned to Steam and not-in-library to Local`() = runTest {
        val game = windows.copy(
            storefront = "STEAM",
            storefrontGameId = "1984270",
            windowsLabel = WindowsGameLabel.NON_STEAM,
        )
        coEvery { ownership.derive("1984270") } returns LocalCopyOwnership.OWNED
        assertEquals(WindowsGameLabel.STEAM, gate.autoLabel(game))

        coEvery { ownership.derive("1984270") } returns LocalCopyOwnership.NOT_IN_LIBRARY
        assertEquals(WindowsGameLabel.LOCAL, gate.autoLabel(game))
    }

    @Test
    fun `autoLabel is null without a known app id or with an empty owned list`() = runTest {
        assertNull(gate.autoLabel(windows))

        coEvery { ownership.derive("1984270") } returns null
        assertNull(gate.autoLabel(windows.copy(storefront = "STEAM", storefrontGameId = "1984270")))
    }
}
