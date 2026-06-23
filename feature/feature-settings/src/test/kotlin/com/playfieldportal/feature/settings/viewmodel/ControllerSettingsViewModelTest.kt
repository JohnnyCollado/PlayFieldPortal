package com.playfieldportal.feature.settings.viewmodel

import android.view.KeyEvent
import app.cash.turbine.test
import com.playfieldportal.core.data.repository.ControllerMappingRepository
import com.playfieldportal.core.data.repository.RemapCoordinator
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.GamepadBinding
import com.playfieldportal.core.domain.model.GamepadMappings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ControllerSettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mappingRepository: ControllerMappingRepository
    private lateinit var remapCoordinator: RemapCoordinator
    private lateinit var viewModel: ControllerSettingsViewModel

    private val defaultMappings = GamepadMappings(
        bindings = listOf(
            GamepadBinding(KeyEvent.KEYCODE_BUTTON_A, GamepadAction.SELECT),
            GamepadBinding(KeyEvent.KEYCODE_BUTTON_B, GamepadAction.BACK),
            GamepadBinding(KeyEvent.KEYCODE_DPAD_UP,   GamepadAction.NAVIGATE_UP),
        )
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mappingRepository = mockk(relaxed = true)
        remapCoordinator  = RemapCoordinator()
        coEvery { mappingRepository.mappings } returns flowOf(defaultMappings)
        viewModel = ControllerSettingsViewModel(mappingRepository, remapCoordinator)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Mapping display ───────────────────────────────────────────────────

    @Test
    fun `initial state shows default mappings`() = runTest {
        viewModel.uiState.test {
            // uiState is a WhileSubscribed stateIn: the first emission is the empty initial
            // value, the mappings arrive on a later emission once the upstream is collected.
            var state = awaitItem()
            while (state.mappings.isEmpty()) state = awaitItem()
            assertEquals(defaultMappings.bindings.size, state.mappings.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Remap flow ────────────────────────────────────────────────────────

    @Test
    fun `startRemap sets remappingAction`() = runTest {
        viewModel.startRemap(GamepadAction.SELECT)
        viewModel.uiState.test {
            // Skip the empty initial emission and await the one reflecting the remap state.
            var state = awaitItem()
            while (state.remappingAction == null) state = awaitItem()
            assertEquals(GamepadAction.SELECT, state.remappingAction)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onKeyPressedDuringRemap calls repository remap and clears pending action`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.startRemap(GamepadAction.SELECT)
        testDispatcher.scheduler.advanceUntilIdle()

        coEvery { mappingRepository.remap(any(), any()) } returns Unit
        viewModel.onKeyPressedDuringRemap(KeyEvent.KEYCODE_BUTTON_X)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { mappingRepository.remap(GamepadAction.SELECT, KeyEvent.KEYCODE_BUTTON_X) }

        viewModel.uiState.test {
            val state = awaitItem()
            assertNull("remappingAction should be null after remap", state.remappingAction)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onKeyPressedDuringRemap does nothing when no pending action`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onKeyPressedDuringRemap(KeyEvent.KEYCODE_BUTTON_X)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { mappingRepository.remap(any(), any()) }
    }

    @Test
    fun `cancelRemap clears remappingAction`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.startRemap(GamepadAction.BACK)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.cancelRemap()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertNull(state.remappingAction)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── RemapCoordinator wiring ───────────────────────────────────────────

    @Test
    fun `startRemap registers captureNextKey on RemapCoordinator`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.startRemap(GamepadAction.SELECT)
        assert(remapCoordinator.captureNextKey != null) { "captureNextKey should be set after startRemap" }
    }

    @Test
    fun `cancelRemap clears captureNextKey on RemapCoordinator`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.startRemap(GamepadAction.SELECT)
        viewModel.cancelRemap()
        assert(remapCoordinator.captureNextKey == null) { "captureNextKey should be null after cancelRemap" }
    }

    @Test
    fun `physical Back key via coordinator cancels remap without assigning`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.startRemap(GamepadAction.SELECT)
        testDispatcher.scheduler.advanceUntilIdle()

        // Simulate physical Back key arriving through RemapCoordinator
        remapCoordinator.captureNextKey?.invoke(KeyEvent.KEYCODE_BACK)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { mappingRepository.remap(any(), any()) }

        viewModel.uiState.test {
            val state = awaitItem()
            assertNull("remappingAction must be null after Back-key cancel", state.remappingAction)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `B button via coordinator assigns B and does not navigate back`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.startRemap(GamepadAction.SELECT)
        testDispatcher.scheduler.advanceUntilIdle()

        coEvery { mappingRepository.remap(any(), any()) } returns Unit

        // Simulate B button arriving through RemapCoordinator
        remapCoordinator.captureNextKey?.invoke(KeyEvent.KEYCODE_BUTTON_B)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { mappingRepository.remap(GamepadAction.SELECT, KeyEvent.KEYCODE_BUTTON_B) }

        viewModel.uiState.test {
            val state = awaitItem()
            assertNull("remappingAction must be null after successful assignment", state.remappingAction)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Reset ─────────────────────────────────────────────────────────────

    @Test
    fun `resetToDefaults calls repository resetToDefaults`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.resetToDefaults()
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify { mappingRepository.resetToDefaults() }
    }
}
