package com.playfieldportal.core.data.discord

import android.content.Context
import com.playfieldportal.core.data.network.NetworkMonitor
import com.playfieldportal.core.domain.discord.DiscordSessionActivator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Covers the per-game presence name-selection + sanitization logic in isolation. The two
 * DataStore-backed preference reads are stubbed on a spy so no Android DataStore/Context is needed.
 */
class DiscordPresenceControllerTest {

    private val sessionActivator: DiscordSessionActivator = mockk(relaxed = true)
    private val networkMonitor: NetworkMonitor = mockk(relaxed = true)

    private fun controller(
        share: Boolean = true,
        generic: Boolean = false,
        online: Boolean = true,
    ): DiscordPresenceController {
        coEvery { networkMonitor.isOnline() } returns online
        val spy = spyk(
            DiscordPresenceController(
                context = mockk(relaxed = true),
                sessionActivator = sessionActivator,
                networkMonitor = networkMonitor,
            )
        )
        coEvery { spy.isShareEnabled() } returns share
        coEvery { spy.isGenericMode() } returns generic
        return spy
    }

    @Test
    fun `broadcasts the game title while playing`() = runTest {
        controller().setCurrentGame("Chrono Trigger")
        coVerify { sessionActivator.setActivity("Chrono Trigger", null) }
    }

    @Test
    fun `falls back to the app name when idle`() = runTest {
        val c = controller()
        c.setCurrentGame("Chrono Trigger")
        c.clearCurrentGame()
        coVerify { sessionActivator.setActivity("Playfield Portal", null) }
    }

    @Test
    fun `generic mode hides the title even while playing`() = runTest {
        controller(generic = true).setCurrentGame("Chrono Trigger")
        coVerify { sessionActivator.setActivity("a game", null) }
    }

    @Test
    fun `sharing off clears presence instead of broadcasting`() = runTest {
        controller(share = false).setCurrentGame("Chrono Trigger")
        coVerify { sessionActivator.clearActivity() }
    }

    @Test
    fun `offline never touches the wire`() = runTest {
        controller(online = false).setCurrentGame("Chrono Trigger")
        coVerify(exactly = 0) { sessionActivator.setActivity(any(), any()) }
        coVerify(exactly = 0) { sessionActivator.clearActivity() }
    }

    @Test
    fun `strips control and bidi-override characters from the title`() = runTest {
        // RLO override + a NUL control char embedded in the title.
        controller().setCurrentGame("Do\u202Eom\u0000  II")
        coVerify { sessionActivator.setActivity("Doom II", null) }
    }

    @Test
    fun `clamps an over-long title to Discord's 128-char limit`() = runTest {
        val long = "A".repeat(200)
        controller().setCurrentGame(long)
        coVerify {
            sessionActivator.setActivity(
                withArg { assertTrue(it.length == 128) },
                null,
            )
        }
    }

    @Test
    fun `a blank title after sanitization is treated as idle`() = runTest {
        val c = controller()
        c.setCurrentGame("Zelda")
        c.setCurrentGame("   \u200E ")   // whitespace + bidi only -> blank -> idle
        coVerify { sessionActivator.setActivity("Playfield Portal", null) }
    }
}
