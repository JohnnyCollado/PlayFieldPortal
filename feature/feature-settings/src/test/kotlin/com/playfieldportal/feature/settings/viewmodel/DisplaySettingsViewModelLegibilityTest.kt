package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.domain.model.IconLegibilityStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The icon-legibility pref contract: cycling persists the enum NAME, and a stale/unknown
 * persisted value surfaces as [IconLegibilityStyle.NONE] rather than throwing — the failure
 * mode `cycleWaveStyle` needed a `runCatching` for. [IconLegibilityStyle.fromName] makes that
 * unnecessary; these tests prove it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DisplaySettingsViewModelLegibilityTest {

    private val dispatcher = StandardTestDispatcher()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var vm: DisplaySettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        runBlocking { context.pfpDataStore.edit { it.clear() } }
        vm = DisplaySettingsViewModel(context)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `cycle advances through every style and persists the enum name`() = runTest(dispatcher) {
        val expected = IconLegibilityStyle.entries
        val seen = mutableListOf<IconLegibilityStyle>()

        repeat(expected.size) { index ->
            eventually("style $index visible") {
                // The DataStore-backed state must have settled to the persisted value before we
                // sample it — the initial StateFlow value precedes the first emission.
                vm.uiState.first().iconLegibility == expected[index]
            }
            seen += vm.uiState.first().iconLegibility
            vm.cycleIconLegibility()
            eventually("style ${index + 1} persisted") {
                context.pfpDataStore.data.first()[KEY_ICON_LEGIBILITY] == expected[(index + 1) % expected.size].name
            }
        }

        assertEquals(expected, seen)
    }

    @Test
    fun `an unknown persisted value surfaces as NONE and still cycles`() = runTest(dispatcher) {
        context.pfpDataStore.edit {
            it[KEY_ICON_LEGIBILITY] = "CONTOUR_MEDIUM" // a style that no longer exists
        }

        eventually("stale value tolerated") {
            vm.uiState.first().iconLegibility == IconLegibilityStyle.NONE
        }

        // The pipeline genuinely works with the stale key present: cycling lands on the first
        // entry after NONE, proving the read neither crashed nor wedged the state flow.
        vm.cycleIconLegibility()
        eventually("cycle works after stale value") {
            vm.uiState.first().iconLegibility == IconLegibilityStyle.OFFSET_SHADOW
        }
    }

    @Test
    fun `solid unfocused icons toggles and persists`() = runTest(dispatcher) {
        assertEquals(false, vm.uiState.first().solidUnfocusedIcons)

        vm.setSolidUnfocusedIcons(true)
        eventually("solid icons persisted") {
            context.pfpDataStore.data.first()[KEY_SOLID_UNFOCUSED_ICONS] == true
        }
        eventually("solid icons surfaced") {
            vm.uiState.first().solidUnfocusedIcons
        }
    }

    @Test
    fun `text shadow defaults on, toggles off, and persists`() = runTest(dispatcher) {
        // Default ON: helper text over bright wallpapers is the failure mode this setting exists
        // for, so the out-of-the-box experience ships with the shadow enabled.
        assertEquals(true, vm.uiState.first().textShadow)

        vm.setTextShadow(false)
        eventually("text shadow persisted off") {
            context.pfpDataStore.data.first()[KEY_TEXT_SHADOW] == false
        }
        eventually("text shadow surfaced off") {
            !vm.uiState.first().textShadow
        }

        vm.setTextShadow(true)
        eventually("text shadow surfaced on again") {
            vm.uiState.first().textShadow
        }
    }

    /**
     * Waits until [condition] holds. Drives the test scheduler (the VM's coroutines) and, in
     * the same loop, sleeps on a REAL IO thread (never the scheduler thread) so wall-clock
     * work — DataStore writes — gets time to land. Same pattern as the wallpaper test.
     */
    private suspend fun TestScope.eventually(reason: String, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("condition not met within 10s: $reason")
            }
            advanceUntilIdle()
            withContext(Dispatchers.IO) { Thread.sleep(25) }
        }
        advanceUntilIdle()
    }

    private companion object {
        // Mirror the (private) ViewModel keys by their string contract, like the wallpaper test.
        val KEY_ICON_LEGIBILITY = stringPreferencesKey("display_icon_legibility")
        val KEY_SOLID_UNFOCUSED_ICONS = booleanPreferencesKey("display_solid_unfocused_icons")
        val KEY_TEXT_SHADOW = booleanPreferencesKey("display_text_shadow")
    }
}
