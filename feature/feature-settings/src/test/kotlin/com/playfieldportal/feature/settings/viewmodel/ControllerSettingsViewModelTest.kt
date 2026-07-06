package com.playfieldportal.feature.settings.viewmodel

import com.playfieldportal.core.data.repository.ControllerLayoutRepository
import com.playfieldportal.core.data.repository.ControllerMappingRepository
import com.playfieldportal.core.domain.model.ConfirmBackLayout
import com.playfieldportal.core.domain.model.ControllerDisplayType
import com.playfieldportal.core.domain.model.ControllerLayoutPrefs
import com.playfieldportal.core.domain.model.XYLayout
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ControllerSettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mappingRepository: ControllerMappingRepository
    private lateinit var layoutRepository: ControllerLayoutRepository
    private lateinit var viewModel: ControllerSettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mappingRepository = mockk(relaxed = true)
        layoutRepository = mockk(relaxed = true)
        coEvery { layoutRepository.prefs } returns flowOf(ControllerLayoutPrefs())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // uiState is a WhileSubscribed StateFlow and the cycle* methods read uiState.value, so it must be
    // collected before it reflects the repository prefs — mirror the UI by keeping a live collector.
    private fun TestScope.buildActive(): ControllerSettingsViewModel {
        val vm = ControllerSettingsViewModel(mappingRepository, layoutRepository)
        backgroundScope.launch { vm.uiState.collect { } }
        return vm
    }

    @Test
    fun `layout prefs are exposed in uiState`() = runTest(testDispatcher) {
        val prefs = ControllerLayoutPrefs(
            confirmBackLayout = ConfirmBackLayout.REVERSED,
            xyLayout = XYLayout.SWAPPED,
            displayType = ControllerDisplayType.PLAYSTATION,
        )
        coEvery { layoutRepository.prefs } returns flowOf(prefs)
        viewModel = buildActive()
        advanceUntilIdle()

        assertEquals(prefs, viewModel.uiState.value.layoutPrefs)
    }

    @Test
    fun `cycleConfirmBackLayout switches standard to reversed`() = runTest(testDispatcher) {
        viewModel = buildActive()
        advanceUntilIdle()
        viewModel.cycleConfirmBackLayout()
        advanceUntilIdle()

        coVerify { layoutRepository.setConfirmBackLayout(ConfirmBackLayout.REVERSED) }
    }

    @Test
    fun `cycleConfirmBackLayout switches reversed to standard`() = runTest(testDispatcher) {
        coEvery { layoutRepository.prefs } returns flowOf(
            ControllerLayoutPrefs(confirmBackLayout = ConfirmBackLayout.REVERSED)
        )
        viewModel = buildActive()
        advanceUntilIdle()

        viewModel.cycleConfirmBackLayout()
        advanceUntilIdle()

        coVerify { layoutRepository.setConfirmBackLayout(ConfirmBackLayout.STANDARD) }
    }

    @Test
    fun `cycleXYLayout switches standard to swapped`() = runTest(testDispatcher) {
        viewModel = buildActive()
        advanceUntilIdle()
        viewModel.cycleXYLayout()
        advanceUntilIdle()

        coVerify { layoutRepository.setXYLayout(XYLayout.SWAPPED) }
    }

    @Test
    fun `cycleXYLayout switches swapped to standard`() = runTest(testDispatcher) {
        coEvery { layoutRepository.prefs } returns flowOf(
            ControllerLayoutPrefs(xyLayout = XYLayout.SWAPPED)
        )
        viewModel = buildActive()
        advanceUntilIdle()

        viewModel.cycleXYLayout()
        advanceUntilIdle()

        coVerify { layoutRepository.setXYLayout(XYLayout.STANDARD) }
    }

    @Test
    fun `cycleDisplayType advances through the three branded types`() = runTest(testDispatcher) {
        val types = listOf(
            ControllerDisplayType.XBOX,
            ControllerDisplayType.NINTENDO,
            ControllerDisplayType.PLAYSTATION,
        )

        types.forEachIndexed { index, type ->
            coEvery { layoutRepository.prefs } returns flowOf(
                ControllerLayoutPrefs(displayType = type)
            )
            viewModel = buildActive()
            advanceUntilIdle()

            viewModel.cycleDisplayType()
            advanceUntilIdle()

            coVerify { layoutRepository.setDisplayType(types[(index + 1) % types.size]) }
        }
    }

    @Test
    fun `cycleDisplayType treats generic as xbox for next branded option`() = runTest(testDispatcher) {
        coEvery { layoutRepository.prefs } returns flowOf(
            ControllerLayoutPrefs(displayType = ControllerDisplayType.GENERIC)
        )
        viewModel = buildActive()
        advanceUntilIdle()

        viewModel.cycleDisplayType()
        advanceUntilIdle()

        coVerify { layoutRepository.setDisplayType(ControllerDisplayType.NINTENDO) }
    }

    @Test
    fun `resetToDefaults resets mappings and layout prefs`() = runTest(testDispatcher) {
        viewModel = buildActive()
        advanceUntilIdle()
        viewModel.resetToDefaults()
        advanceUntilIdle()

        coVerify { mappingRepository.resetToDefaults() }
        coVerify { layoutRepository.resetAllPrefs() }
    }
}
