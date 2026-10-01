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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `controller_virtual_keyboard` preference (Virtual Keyboard plan 6.1): on by default, read
 * from an absent key so existing installs get PFP's keyboard without a migration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ControllerVirtualKeyboardPrefTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val key = booleanPreferencesKey("controller_virtual_keyboard")
    private lateinit var repository: ControllerLayoutRepository

    @Before
    fun setUp() {
        runBlocking { context.pfpDataStore.edit { it.clear() } }
        repository = ControllerLayoutRepository(context, mockk(relaxed = true))
    }

    @Test
    fun `an absent key reads as on, with nothing written`() = runTest {
        assertTrue(repository.prefs.first().virtualKeyboard)
        assertNull(context.pfpDataStore.data.first()[key])
    }

    @Test
    fun `it round-trips off and back on`() = runTest {
        repository.setVirtualKeyboard(false)
        assertFalse(repository.prefs.first().virtualKeyboard)
        assertEquals(false, context.pfpDataStore.data.first()[key])

        repository.setVirtualKeyboard(true)
        assertTrue(repository.prefs.first().virtualKeyboard)
    }

    @Test
    fun `reset returns it to on by removing the key`() = runTest {
        repository.setVirtualKeyboard(false)
        repository.resetAllPrefs()

        assertTrue(repository.prefs.first().virtualKeyboard)
        assertNull(context.pfpDataStore.data.first()[key], "reset must remove the key, not write true over it")
    }

    @Test
    fun `it is independent of Left Backs Out`() = runTest {
        repository.setVirtualKeyboard(false)
        assertTrue(repository.prefs.first().leftBacksOut)

        repository.setVirtualKeyboard(true)
        repository.setLeftBacksOut(false)
        assertTrue(repository.prefs.first().virtualKeyboard)
    }
}
