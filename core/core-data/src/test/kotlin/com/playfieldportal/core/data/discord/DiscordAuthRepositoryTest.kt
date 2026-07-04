package com.playfieldportal.core.data.discord

import com.playfieldportal.core.data.network.NetworkMonitor
import com.playfieldportal.core.domain.discord.DeviceAuthChallenge
import com.playfieldportal.core.domain.discord.DeviceLoginState
import com.playfieldportal.core.domain.discord.DeviceTokens
import com.playfieldportal.core.domain.discord.DiscordFriend
import com.playfieldportal.core.domain.discord.DiscordSession
import com.playfieldportal.core.domain.discord.DiscordSessionActivator
import com.playfieldportal.core.domain.discord.DiscordUser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DiscordAuthRepositoryTest {

    private class FakeActivator : DiscordSessionActivator {
        var activatedToken: String? = null
        var deactivated = false
        override suspend fun activate(accessToken: String): Boolean {
            activatedToken = accessToken
            return true
        }
        override suspend fun deactivate() { deactivated = true }
        override suspend fun currentUser(): DiscordUser? = null
        override suspend fun friends(): List<DiscordFriend> = emptyList()
        override fun connectionStatus(): Int = 3
    }

    private fun onlineMonitor(online: Boolean = true) =
        mockk<NetworkMonitor> { every { isOnline() } returns online }

    private fun repo(
        client: DiscordDeviceAuthClient,
        store: DiscordTokenStore = mockk(relaxed = true),
        activator: DiscordSessionActivator = FakeActivator(),
        monitor: NetworkMonitor = onlineMonitor(),
    ) = DiscordAuthRepository(client, store, activator, monitor)

    private val challenge = DeviceAuthChallenge(
        userCode = "WXYZ-1234",
        verificationUri = "https://discord.com/activate",
        verificationUriComplete = "https://discord.com/activate?user_code=WXYZ-1234",
        deviceCode = "DEV123",
        expiresInSeconds = 300,
        pollIntervalSeconds = 1,
    )
    private val tokens = DeviceTokens("AT-abc", "RT-xyz", 604800, "openid sdk.social_layer")

    // ── QR login ─────────────────────────────────────────────────────────────

    @Test
    fun `approval after pending emits Success, persists tokens and activates the session`() = runTest {
        val client = mockk<DiscordDeviceAuthClient>()
        val store = mockk<DiscordTokenStore>(relaxed = true)
        val activator = FakeActivator()
        coEvery { client.requestDeviceCode(any()) } returns challenge
        coEvery { client.pollForToken("DEV123") } returnsMany
            listOf(TokenPollResult.Pending, TokenPollResult.Approved(tokens))

        val states = repo(client, store, activator).loginWithDeviceQr().toList()

        assertEquals(DeviceLoginState.Requesting, states.first())
        assertIs<DeviceLoginState.AwaitingApproval>(states[1])
        assertIs<DeviceLoginState.Success>(states.last())
        assertEquals("AT-abc", activator.activatedToken)
        coVerify { store.save(tokens, any()) }
    }

    @Test
    fun `denied poll emits Denied and never persists`() = runTest {
        val client = mockk<DiscordDeviceAuthClient>()
        val store = mockk<DiscordTokenStore>(relaxed = true)
        coEvery { client.requestDeviceCode(any()) } returns challenge
        coEvery { client.pollForToken(any()) } returns TokenPollResult.Denied

        val states = repo(client, store).loginWithDeviceQr().toList()

        assertEquals(DeviceLoginState.Denied, states.last())
        coVerify(exactly = 0) { store.save(any(), any()) }
    }

    @Test
    fun `expired poll emits Expired`() = runTest {
        val client = mockk<DiscordDeviceAuthClient>()
        coEvery { client.requestDeviceCode(any()) } returns challenge
        coEvery { client.pollForToken(any()) } returns TokenPollResult.Expired

        val states = repo(client).loginWithDeviceQr().toList()

        assertEquals(DeviceLoginState.Expired, states.last())
    }

    @Test
    fun `code lifetime elapsing while pending emits Expired`() = runTest {
        val client = mockk<DiscordDeviceAuthClient>()
        coEvery { client.requestDeviceCode(any()) } returns challenge.copy(expiresInSeconds = 2, pollIntervalSeconds = 1)
        coEvery { client.pollForToken(any()) } returns TokenPollResult.Pending

        val states = repo(client).loginWithDeviceQr().toList()

        assertEquals(DeviceLoginState.Expired, states.last())
    }

    @Test
    fun `failure requesting the device code emits Error`() = runTest {
        val client = mockk<DiscordDeviceAuthClient>()
        coEvery { client.requestDeviceCode(any()) } throws RuntimeException("boom")

        val states = repo(client).loginWithDeviceQr().toList()

        assertEquals(DeviceLoginState.Requesting, states.first())
        val error = assertIs<DeviceLoginState.Error>(states.last())
        assertEquals("boom", error.message)
    }

    @Test
    fun `offline emits an offline Error without touching the network`() = runTest {
        val client = mockk<DiscordDeviceAuthClient>()

        val states = repo(client, monitor = onlineMonitor(online = false)).loginWithDeviceQr().toList()

        assertIs<DeviceLoginState.Error>(states.last())
        coVerify(exactly = 0) { client.requestDeviceCode(any()) }
    }

    // ── Session restore + refresh-token exchange ───────────────────────────────

    @Test
    fun `restoreSession activates a still-valid token without refreshing`() = runTest {
        val client = mockk<DiscordDeviceAuthClient>()
        val store = mockk<DiscordTokenStore>(relaxed = true)
        val activator = FakeActivator()
        coEvery { store.load() } returns
            DiscordSession("AT-live", "RT", System.currentTimeMillis() + 60_000, "openid")

        assertTrue(repo(client, store, activator).restoreSession())
        assertEquals("AT-live", activator.activatedToken)
        coVerify(exactly = 0) { client.refreshTokens(any()) }
    }

    @Test
    fun `restoreSession refreshes an expired token, persists and activates the new one`() = runTest {
        val client = mockk<DiscordDeviceAuthClient>()
        val store = mockk<DiscordTokenStore>(relaxed = true)
        val activator = FakeActivator()
        coEvery { store.load() } returns DiscordSession("AT-old", "RT-xyz", 0L, "openid")
        val fresh = DeviceTokens("AT-new", "RT-new", 604800, "openid")
        coEvery { client.refreshTokens("RT-xyz") } returns TokenPollResult.Approved(fresh)

        assertTrue(repo(client, store, activator).restoreSession())
        assertEquals("AT-new", activator.activatedToken)
        coVerify { store.save(fresh, any()) }
    }

    @Test
    fun `restoreSession leaves the session in place when the refresh fails`() = runTest {
        val client = mockk<DiscordDeviceAuthClient>()
        val store = mockk<DiscordTokenStore>(relaxed = true)
        coEvery { store.load() } returns DiscordSession("AT-old", "RT", 0L, "openid")
        coEvery { client.refreshTokens(any()) } returns TokenPollResult.Error("invalid_grant")

        assertFalse(repo(client, store).restoreSession())
        coVerify(exactly = 0) { store.save(any(), any()) }
        coVerify(exactly = 0) { store.clear() }
    }

    @Test
    fun `restoreSession does not attempt a refresh while offline`() = runTest {
        val client = mockk<DiscordDeviceAuthClient>()
        val store = mockk<DiscordTokenStore>(relaxed = true)
        coEvery { store.load() } returns DiscordSession("AT-old", "RT", 0L, "openid")

        assertFalse(repo(client, store, monitor = onlineMonitor(online = false)).restoreSession())
        coVerify(exactly = 0) { client.refreshTokens(any()) }
    }
}
