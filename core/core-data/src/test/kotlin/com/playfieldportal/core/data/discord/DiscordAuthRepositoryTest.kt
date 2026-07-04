package com.playfieldportal.core.data.discord

import com.playfieldportal.core.domain.discord.DeviceAuthChallenge
import com.playfieldportal.core.domain.discord.DeviceLoginState
import com.playfieldportal.core.domain.discord.DeviceTokens
import com.playfieldportal.core.domain.discord.DiscordSessionActivator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DiscordAuthRepositoryTest {

    private class FakeActivator : DiscordSessionActivator {
        var activatedToken: String? = null
        var deactivated = false
        override suspend fun activate(accessToken: String): Boolean {
            activatedToken = accessToken
            return true
        }
        override suspend fun deactivate() { deactivated = true }
    }

    private val challenge = DeviceAuthChallenge(
        userCode = "WXYZ-1234",
        verificationUri = "https://discord.com/activate",
        verificationUriComplete = "https://discord.com/activate?user_code=WXYZ-1234",
        deviceCode = "DEV123",
        expiresInSeconds = 300,
        pollIntervalSeconds = 1,
    )
    private val tokens = DeviceTokens("AT-abc", "RT-xyz", 604800, "openid sdk.social_layer")

    @Test
    fun `approval after pending emits Success, persists tokens and activates the session`() = runTest {
        val client = mockk<DiscordDeviceAuthClient>()
        val store = mockk<DiscordTokenStore>(relaxed = true)
        val activator = FakeActivator()
        coEvery { client.requestDeviceCode(any()) } returns challenge
        coEvery { client.pollForToken("DEV123") } returnsMany
            listOf(TokenPollResult.Pending, TokenPollResult.Approved(tokens))

        val states = DiscordAuthRepository(client, store, activator).loginWithDeviceQr().toList()

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

        val states = DiscordAuthRepository(client, store, FakeActivator()).loginWithDeviceQr().toList()

        assertEquals(DeviceLoginState.Denied, states.last())
        coVerify(exactly = 0) { store.save(any(), any()) }
    }

    @Test
    fun `expired poll emits Expired`() = runTest {
        val client = mockk<DiscordDeviceAuthClient>()
        coEvery { client.requestDeviceCode(any()) } returns challenge
        coEvery { client.pollForToken(any()) } returns TokenPollResult.Expired

        val states = DiscordAuthRepository(client, mockk(relaxed = true), FakeActivator())
            .loginWithDeviceQr().toList()

        assertEquals(DeviceLoginState.Expired, states.last())
    }

    @Test
    fun `code lifetime elapsing while pending emits Expired`() = runTest {
        val client = mockk<DiscordDeviceAuthClient>()
        // Short-lived code so the poll loop hits the deadline quickly under virtual time.
        coEvery { client.requestDeviceCode(any()) } returns challenge.copy(expiresInSeconds = 2, pollIntervalSeconds = 1)
        coEvery { client.pollForToken(any()) } returns TokenPollResult.Pending

        val states = DiscordAuthRepository(client, mockk(relaxed = true), FakeActivator())
            .loginWithDeviceQr().toList()

        assertEquals(DeviceLoginState.Expired, states.last())
    }

    @Test
    fun `failure requesting the device code emits Error`() = runTest {
        val client = mockk<DiscordDeviceAuthClient>()
        coEvery { client.requestDeviceCode(any()) } throws RuntimeException("boom")

        val states = DiscordAuthRepository(client, mockk(relaxed = true), FakeActivator())
            .loginWithDeviceQr().toList()

        assertEquals(DeviceLoginState.Requesting, states.first())
        val error = assertIs<DeviceLoginState.Error>(states.last())
        assertEquals("boom", error.message)
    }
}
