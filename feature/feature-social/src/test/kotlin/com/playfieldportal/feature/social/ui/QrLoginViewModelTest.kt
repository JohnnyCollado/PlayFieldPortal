package com.playfieldportal.feature.social.ui

import com.playfieldportal.core.data.discord.DiscordAuthRepository
import com.playfieldportal.core.domain.discord.DeviceLoginState
import com.playfieldportal.core.domain.discord.DeviceTokens
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Every opening of the Discord QR overlay starts its own login.
 *
 * Survey L7 (docs/audits/codebase-survey-2026-10-22.md): the ViewModel is activity-scoped and
 * only called `start()` from `init`. Reopening after a sign-out saw the old `Success` and closed at
 * once; reopening after Cancel showed the old QR with nothing polling behind it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QrLoginViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val auth = mockk<DiscordAuthRepository>()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val success = DeviceLoginState.Success(DeviceTokens("a", "r", 60, "s"))

    @Test
    fun `creating the view model does not start a login by itself`() = runTest(dispatcher) {
        every { auth.loginWithDeviceQr(any()) } returns flowOf(success)

        QrLoginViewModel(auth)

        verify(exactly = 0) { auth.loginWithDeviceQr(any()) }
    }

    @Test
    fun `opening again after a sign-in shows a fresh login, not the old success`() = runTest(dispatcher) {
        every { auth.loginWithDeviceQr(any()) } returnsMany listOf(
            flowOf(success),
            flow { emit(DeviceLoginState.Requesting) /* still waiting on Discord */ },
        )
        val vm = QrLoginViewModel(auth)

        vm.start()
        assertEquals(success, vm.state.value)

        vm.start()
        assertEquals(DeviceLoginState.Requesting, vm.state.value)
    }

    @Test
    fun `reopening after cancel polls again`() = runTest(dispatcher) {
        every { auth.loginWithDeviceQr(any()) } returns flowOf(DeviceLoginState.Requesting)
        val vm = QrLoginViewModel(auth)

        vm.start()
        vm.cancel()
        vm.start()

        verify(exactly = 2) { auth.loginWithDeviceQr(any()) }
    }
}

/** Which login screens a controller Confirm retries (survey P1). */
class DeviceLoginRetryableTest {
    @Test
    fun `only the error screens are retried by Confirm`() {
        org.junit.Assert.assertTrue(DeviceLoginState.Expired.isRetryable())
        org.junit.Assert.assertTrue(DeviceLoginState.Denied.isRetryable())
        org.junit.Assert.assertFalse(DeviceLoginState.Requesting.isRetryable())
    }
}
