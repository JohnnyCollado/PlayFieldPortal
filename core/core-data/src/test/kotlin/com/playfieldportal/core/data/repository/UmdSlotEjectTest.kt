package com.playfieldportal.core.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.database.dao.UmdSlotDao
import com.playfieldportal.core.data.datastore.pfpDataStore
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Ejecting a column's UMD empties the slot until the next game played there, or an insert. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class UmdSlotEjectTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dao = mockk<UmdSlotDao>(relaxed = true)
    private lateinit var repository: UmdSlotRepository

    @Before
    fun setUp() {
        runBlocking { context.pfpDataStore.edit { it.clear() } }
        every { dao.observeAll() } returns flowOf(emptyList())
        repository = UmdSlotRepository(dao, context)
    }

    @Test
    fun `eject removes the inserted game and records when`() = runTest {
        val before = System.currentTimeMillis()

        repository.eject("ps_column")

        coVerify { dao.delete("ps_column") }
        val at = repository.ejectedAt.first()["ps_column"]
        assertNotNull(at)
        assertTrue(at >= before)
    }

    @Test
    fun `inserting clears the eject for that column only`() = runTest {
        repository.eject("ps_column")
        repository.eject("nes_column")

        repository.insert("ps_column", gameId = 7L)

        val ejected = repository.ejectedAt.first()
        assertEquals(setOf("nes_column"), ejected.keys)
    }

    @Test
    fun `a column never ejected has no entry`() = runTest {
        assertTrue(repository.ejectedAt.first().isEmpty())
    }
}
