package com.playfieldportal.core.data.kb

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.database.PFPDatabase
import com.playfieldportal.core.data.database.entity.GameEntity
import com.playfieldportal.core.data.database.entity.MemoryCardEntity
import com.playfieldportal.core.data.database.entity.PlatformEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class LegacyEmulatorIdRewriterTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        PFPDatabase::class.java,
    ).allowMainThreadQueries().build()

    private val rewriter = LegacyEmulatorIdRewriter(db)
    private val games = db.gameDao()
    private val cards = db.memoryCardDao()
    private val platforms = db.platformDao()

    @After fun tearDown() = db.close()

    private val map = mapOf(
        "duckstation" to "auto_com_github_stenzek_duckstation",
        "ppsspp_gold" to "auto_org_ppsspp_ppssppgold",
    )

    private suspend fun seedCard(platformId: String, emulatorId: String?) {
        platforms.upsert(PlatformEntity(platformId, platformId, platformId, null, 0L, preferredEmulatorPackage = emulatorId))
        cards.upsert(MemoryCardEntity(platformId, platformId, emulatorId = emulatorId))
    }

    private suspend fun seedGame(path: String, emulator: String?): Long =
        games.upsert(
            GameEntity(
                title = path,
                platformId = "psx",
                romPath = path,
                packageName = null,
                emulatorPackage = emulator,
                artworkUri = null,
                heroUri = null,
                logoUri = null,
                description = null,
                developer = null,
                publisher = null,
                releaseYear = null,
                genre = null,
                steamGridDbId = null,
            ),
        )

    @Test fun `a card holding a legacy id is rewritten`() = runTest {
        seedCard("psx", "duckstation")

        rewriter.run(map, emptySet())

        assertEquals("auto_com_github_stenzek_duckstation", cards.getById("psx")!!.emulatorId)
    }

    @Test fun `a game override holding a legacy id is rewritten`() = runTest {
        seedCard("psp", null)
        val id = seedGame("a.iso", "ppsspp_gold")

        rewriter.run(map, emptySet())

        assertEquals("auto_org_ppsspp_ppssppgold", games.getById(id)!!.emulatorPackage)
    }

    @Test fun `a platform preference holding a legacy id is rewritten`() = runTest {
        seedCard("psx", "duckstation")

        rewriter.run(map, emptySet())

        assertEquals("auto_com_github_stenzek_duckstation", platforms.getById("psx")!!.preferredEmulatorPackage)
    }

    @Test fun `a kept id is untouched`() = runTest {
        seedCard("psx", "duckstation")
        val id = seedGame("a.iso", "duckstation")

        rewriter.run(map, setOf("duckstation"))

        assertEquals("duckstation", cards.getById("psx")!!.emulatorId)
        assertEquals("duckstation", platforms.getById("psx")!!.preferredEmulatorPackage)
        assertEquals("duckstation", games.getById(id)!!.emulatorPackage)
    }

    @Test fun `a second run makes no changes`() = runTest {
        seedCard("psx", "duckstation")
        seedGame("a.iso", "ppsspp_gold")

        val first = rewriter.run(map, emptySet())
        val second = rewriter.run(map, emptySet())

        assertEquals(3, first)
        assertEquals(0, second)
    }

    @Test fun `unrelated values are untouched`() = runTest {
        seedCard("psx", "auto_org_other_emu")
        val id = seedGame("a.iso", "some-uuid")
        val none = seedGame("b.iso", null)

        val changed = rewriter.run(map, emptySet())

        assertEquals(0, changed)
        assertEquals("auto_org_other_emu", cards.getById("psx")!!.emulatorId)
        assertEquals("some-uuid", games.getById(id)!!.emulatorPackage)
        assertEquals(null, games.getById(none)!!.emulatorPackage)
    }

    @Test fun `an empty map writes nothing`() = runTest {
        seedCard("psx", "duckstation")

        assertEquals(0, rewriter.run(emptyMap(), emptySet()))
        assertEquals("duckstation", cards.getById("psx")!!.emulatorId)
    }
}
