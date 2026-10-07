package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.data.repository.GameBootPreferences
import com.playfieldportal.core.data.repository.ScreenOrientationPreferences
import com.playfieldportal.core.data.repository.ThemeTiers
import com.playfieldportal.core.data.repository.UiMediaStore
import com.playfieldportal.core.domain.model.ScreenOrientationMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #21: Display ▸ Screen Orientation is a real setting now. It defaults to Landscape, cycles
 * to Follow Device and back, persists the enum name under the key MainActivity reads, and labels
 * itself in words.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DisplaySettingsViewModelOrientationTest {

    private val dispatcher = StandardTestDispatcher()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var vm: DisplaySettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        runBlocking { context.pfpDataStore.edit { it.clear() } }
        vm = DisplaySettingsViewModel(
            context,
            UiMediaStore(context, ThemeTiers(context)),
            GameBootPreferences(context),
            io.mockk.mockk(relaxed = true),
            io.mockk.mockk(relaxed = true) {
                io.mockk.every { prefs } returns kotlinx.coroutines.flow.flowOf(
                    com.playfieldportal.core.domain.model.ControllerLayoutPrefs()
                )
            },
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `defaults to landscape`() = runTest(dispatcher) {
        observeUntilSettled(vm.uiState)
        assertEquals(ScreenOrientationMode.LANDSCAPE, vm.uiState.value.screenOrientation)
        assertEquals("Landscape", vm.screenOrientationLabel())
    }

    @Test
    fun `cycling persists follow device under the key MainActivity reads, then wraps back`() = runTest(dispatcher) {
        observeUntilSettled(vm.uiState)

        vm.cycleScreenOrientation()
        eventually("follow device persisted") {
            context.pfpDataStore.data.first()[ScreenOrientationPreferences.KEY_MODE] ==
                ScreenOrientationMode.FOLLOW_DEVICE.name
        }
        eventually("follow device surfaced") {
            vm.uiState.value.screenOrientation == ScreenOrientationMode.FOLLOW_DEVICE
        }
        assertEquals("Follow Device", vm.screenOrientationLabel())

        vm.cycleScreenOrientation()
        eventually("landscape again") {
            vm.uiState.value.screenOrientation == ScreenOrientationMode.LANDSCAPE
        }
    }
}
