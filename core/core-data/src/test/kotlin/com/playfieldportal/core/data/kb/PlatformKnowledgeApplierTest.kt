package com.playfieldportal.core.data.kb

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.database.PFPDatabase
import com.playfieldportal.core.data.database.entity.MemoryCardEntity
import com.playfieldportal.core.data.database.entity.PlatformEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PlatformKnowledgeApplierTest {

    @get:Rule val tmp = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        PFPDatabase::class.java,
    ).allowMainThreadQueries().build()

    private val platforms = db.platformDao()
    private val cards = db.memoryCardDao()

    // The folder does not exist yet, as on a fresh install.
    private val appliedFile get() = File(tmp.root, "emulator_kb/platform_applied.json")
    private fun applier() = PlatformKnowledgeApplier(db, appliedFile)

    @After fun tearDown() = db.close()

    private suspend fun seedPsx(platformExts: String = SEED, cardExts: String? = SEED) {
        platforms.upsert(PlatformEntity("psp", "PlayStation Portable", "PSP", null, 0L, romExtensions = platformExts))
        if (cardExts != null) cards.upsert(MemoryCardEntity("psp", "PlayStation Portable", supportedExtensions = cardExts))
    }

    @Test fun `an untouched platform and card both gain the extension`() = runTest {
        seedPsx()

        applier().apply(mapOf("psp" to listOf("iso", "chd")))

        assertEquals("iso,cso,pbp,chd", platforms.getById("psp")!!.romExtensions)
        assertEquals("iso,cso,pbp,chd", cards.getById("psp")!!.supportedExtensions)
    }

    @Test fun `a customized card gains new KB extensions and keeps its own edits`() = runTest {
        seedPsx(cardExts = "iso,cso,pbp,7z")

        applier().apply(mapOf("psp" to listOf("chd")))

        assertEquals("iso,cso,pbp,chd", platforms.getById("psp")!!.romExtensions)
        assertEquals("iso,cso,pbp,7z,chd", cards.getById("psp")!!.supportedExtensions)
    }

    @Test fun `an extension the user removed from a card is not brought back`() = runTest {
        seedPsx(cardExts = "iso,cso")   // the user removed pbp

        applier().apply(mapOf("psp" to listOf("pbp", "chd")))

        assertEquals("iso,cso,chd", cards.getById("psp")!!.supportedExtensions)
    }

    @Test fun `a platform without a card still updates`() = runTest {
        seedPsx(cardExts = null)

        val gained = applier().apply(mapOf("psp" to listOf("chd")))

        assertEquals("iso,cso,pbp,chd", platforms.getById("psp")!!.romExtensions)
        assertEquals(listOf("psp"), gained.map { it.platformId })
    }

    @Test fun `the returned list names the console and what it gained`() = runTest {
        seedPsx()

        val gained = applier().apply(mapOf("psp" to listOf("iso", "chd")))

        assertEquals(listOf(PlatformGain("psp", listOf("chd"))), gained)
    }

    @Test fun `a second run writes nothing and gains nothing`() = runTest {
        seedPsx()
        applier().apply(mapOf("psp" to listOf("chd")))
        val recorded = appliedFile.readText()
        appliedFile.setLastModified(1_000L)

        val gained = applier().apply(mapOf("psp" to listOf("chd")))

        assertTrue(gained.isEmpty())
        assertEquals(recorded, appliedFile.readText())
        assertEquals(1_000L, appliedFile.lastModified())
    }

    @Test fun `unknown platform ids are skipped`() = runTest {
        seedPsx()

        val gained = applier().apply(mapOf("nope" to listOf("chd")))

        assertTrue(gained.isEmpty())
        assertEquals(SEED, platforms.getById("psp")!!.romExtensions)
        assertFalse(appliedFile.exists())
    }

    @Test fun `the applied set is recorded and lets a later update through`() = runTest {
        seedPsx()
        applier().apply(mapOf("psp" to listOf("chd")))

        // The row now equals the recorded set, not the seed: it is still untouched.
        val gained = applier().apply(mapOf("psp" to listOf("chd", "wbfs")))

        assertEquals(listOf(PlatformGain("psp", listOf("wbfs"))), gained)
        assertEquals("iso,cso,pbp,chd,wbfs", cards.getById("psp")!!.supportedExtensions)
    }

    @Test fun `a Room failure leaves the applied file unchanged`() = runTest {
        seedPsx()
        applier().apply(mapOf("psp" to listOf("chd")))
        val recorded = appliedFile.readText()
        db.close()

        assertFailsWith<Exception> { applier().apply(mapOf("psp" to listOf("wbfs"))) }

        assertEquals(recorded, appliedFile.readText())
    }

    private companion object {
        const val SEED = "iso,cso,pbp"
    }
}
