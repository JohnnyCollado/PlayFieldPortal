package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.data.repository.GameBootPreferences
import com.playfieldportal.core.data.repository.UiMediaStore
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The GameBoot toggle's contract: it writes the live boolean, it retires the unreleased mode key
 * so a stale value cannot outrank the user's choice, and it reads through the same read-time
 * migration as the launch gate so the row and the gate can never disagree about what will play.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DisplaySettingsViewModelGameBootTest {

    private val dispatcher = StandardTestDispatcher()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var vm: DisplaySettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        runBlocking { context.pfpDataStore.edit { it.clear() } }
        vm = DisplaySettingsViewModel(
            context,
            UiMediaStore(context),
            GameBootPreferences(context),
            io.mockk.mockk(relaxed = true),
            // The layout repo only feeds the media rows' face-button shortcuts. A relaxed mock
            // would hand the combine a flow that never emits, so the state would never build.
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
    fun `a fresh install shows the toggle on and turning it off persists`() = runTest(dispatcher) {
        eventually("fresh install defaults on") { vm.uiState.first().gameBootEnabled }

        vm.setGameBootEnabled(false)

        eventually("the row follows the write") { !vm.uiState.first().gameBootEnabled }
        assertEquals(false, context.pfpDataStore.data.first()[KEY_GAMEBOOT_ENABLED])
    }

    @Test
    fun `toggling retires the unreleased mode key`() = runTest(dispatcher) {
        // A device that ran the three-way build carries this. Without the removal it would keep
        // outranking every later toggle, and the row would appear stuck.
        context.pfpDataStore.edit { it[KEY_GAMEBOOT_MODE] = "SOUND_ONLY" }

        eventually("the stale mode reads as on") { vm.uiState.first().gameBootEnabled }

        vm.setGameBootEnabled(false)

        eventually("the toggle wins") { !vm.uiState.first().gameBootEnabled }
        assertNull(
            context.pfpDataStore.data.first()[KEY_GAMEBOOT_MODE],
            "the retired mode key must not survive a toggle",
        )
    }

    @Test
    fun `an established install with the old boolean surfaces off in the row`() = runTest(dispatcher) {
        // The pre-toggle era: boolean off + first-run marker. The row must show off, the same
        // value the gate will honour at launch.
        context.pfpDataStore.edit {
            it[KEY_GAMEBOOT_ENABLED] = false
            it[KEY_SETUP_SEEN] = true
        }

        eventually("boolean false surfaces as off in the row") { !vm.uiState.first().gameBootEnabled }
    }

    /**
     * Waits until [condition] holds. Drives the test scheduler (the VM's coroutines) and, in
     * the same loop, sleeps on a REAL IO thread (never the scheduler thread) so wall-clock
     * work — DataStore writes — gets time to land. Same pattern as the Legibility test.
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
        // Mirror the (private) preference keys by their string contract.
        val KEY_GAMEBOOT_ENABLED = booleanPreferencesKey("display_gameboot_enabled")
        val KEY_GAMEBOOT_MODE = stringPreferencesKey("display_gameboot_mode")
        val KEY_SETUP_SEEN = booleanPreferencesKey("initial_setup_seen")
    }
}
