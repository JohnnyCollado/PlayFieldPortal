package com.playfieldportal.core.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.datastore.pfpDataStore
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The `controller_haptic_feedback` preference: OFF by default, read from an absent key. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ControllerHapticPrefTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val key = booleanPreferencesKey("controller_haptic_feedback")
    private lateinit var repository: ControllerLayoutRepository

    @Before
    fun setUp() {
        runBlocking { context.pfpDataStore.edit { it.clear() } }
        repository = ControllerLayoutRepository(context, mockk(relaxed = true))
    }

    @Test
    fun `an absent key reads as off, with nothing written`() = runTest {
        assertFalse(repository.prefs.first().hapticFeedback)
        assertFalse(repository.enabled.first())
        assertNull(context.pfpDataStore.data.first()[key])
    }

    @Test
    fun `it round-trips on and off, and the player-facing flow follows`() = runTest {
        repository.setHapticFeedback(true)
        assertTrue(repository.prefs.first().hapticFeedback)
        assertTrue(repository.enabled.first())

        repository.setHapticFeedback(false)
        assertFalse(repository.enabled.first())
    }

    @Test
    fun `reset returns it to off by removing the key`() = runTest {
        repository.setHapticFeedback(true)
        repository.resetAllPrefs()
        assertFalse(repository.prefs.first().hapticFeedback)
        assertNull(context.pfpDataStore.data.first()[key])
    }
}
