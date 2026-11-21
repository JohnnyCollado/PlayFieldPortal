package com.playfieldportal.feature.settings.viewmodel

import com.playfieldportal.core.domain.model.ImageMotion
import kotlinx.coroutines.flow.first
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.data.repository.GameBootPreferences
import com.playfieldportal.core.data.repository.ScreenOrientationPreferences
import com.playfieldportal.core.data.repository.ThemeTiers
import com.playfieldportal.core.data.repository.UiMediaStore
import com.playfieldportal.core.domain.model.IconLegibilityStyle
import com.playfieldportal.core.domain.model.ScreenOrientationMode
import com.playfieldportal.core.domain.model.UmdSlotMode
import com.playfieldportal.core.ui.wave.WaveStyle
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
 * Display's option rows pick a value from a dialog: the dialog opens on the current value, and a
 * pick stores that exact value rather than stepping to "the next one".
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DisplaySettingsViewModelOptionChoiceTest {

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
    fun `the dialog opens on the value in use`() = runTest(dispatcher) {
        observeUntilSettled(vm.uiState)

        assertEquals(vm.uiState.value.umdSlotMode.ordinal, vm.selectedChoice(DisplayOption.UMD_SLOT))
        assertEquals(ScreenOrientationMode.LANDSCAPE.ordinal, vm.selectedChoice(DisplayOption.SCREEN_ORIENTATION))
    }

    @Test
    fun `a pick stores that value, however far it is from the current one`() = runTest(dispatcher) {
        observeUntilSettled(vm.uiState)

        // Contour (Auto) is five steps from None: one pick, not five presses.
        vm.choose(DisplayOption.ICON_LEGIBILITY, IconLegibilityStyle.CONTOUR_AUTO.ordinal)
        eventually("icon legibility surfaced") {
            vm.uiState.value.iconLegibility == IconLegibilityStyle.CONTOUR_AUTO
        }
        assertEquals(IconLegibilityStyle.CONTOUR_AUTO.ordinal, vm.selectedChoice(DisplayOption.ICON_LEGIBILITY))

        vm.choose(DisplayOption.UMD_SLOT, UmdSlotMode.OFF.ordinal)
        eventually("umd slot surfaced") { vm.uiState.value.umdSlotMode == UmdSlotMode.OFF }
    }

    @Test
    fun `motion starts reduced and a pick is stored under the animated images key`() = runTest(dispatcher) {
        observeUntilSettled(vm.uiState)
        assertEquals(ImageMotion.REDUCED, vm.uiState.value.motion)
        assertEquals(ImageMotion.REDUCED.ordinal, vm.selectedChoice(DisplayOption.MOTION))

        vm.choose(DisplayOption.MOTION, ImageMotion.STATIC.ordinal)
        eventually("motion surfaced") { vm.uiState.value.motion == ImageMotion.STATIC }
        // The same stored value the Artwork screen's Animated images row used: nobody's choice moves.
        val stored = context.pfpDataStore.data.first()[
            androidx.datastore.preferences.core.stringPreferencesKey("pref_image_motion"),
        ]
        assertEquals("STATIC", stored)
    }

    @Test
    fun `wave style and background motion are one setting`() = runTest(dispatcher) {
        observeUntilSettled(vm.uiState)

        vm.choose(DisplayOption.BACKGROUND_MOTION, WaveStyle.STATIC.ordinal)
        eventually("wave style surfaced") { vm.uiState.value.waveStyle == WaveStyle.STATIC }
        assertEquals(WaveStyle.STATIC.ordinal, vm.selectedChoice(DisplayOption.WAVE_STYLE))
    }

    @Test
    fun `orientation is stored under the key MainActivity reads`() = runTest(dispatcher) {
        observeUntilSettled(vm.uiState)

        vm.choose(DisplayOption.SCREEN_ORIENTATION, ScreenOrientationMode.FOLLOW_DEVICE.ordinal)
        eventually("follow device persisted") {
            context.pfpDataStore.data.first()[ScreenOrientationPreferences.KEY_MODE] ==
                ScreenOrientationMode.FOLLOW_DEVICE.name
        }
    }

    @Test
    fun `an index outside the list changes nothing`() = runTest(dispatcher) {
        observeUntilSettled(vm.uiState)
        val before = vm.uiState.value.umdSlotMode

        vm.choose(DisplayOption.UMD_SLOT, 99)
        vm.choose(DisplayOption.UMD_SLOT, -1)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(before, vm.uiState.value.umdSlotMode)
    }
}
