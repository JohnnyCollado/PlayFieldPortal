package com.playfieldportal.core.data.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.database.entity.AccountAchievementEntity
import com.playfieldportal.core.data.database.entity.AccountAchievementSetEntity
import com.playfieldportal.core.data.database.entity.AchievementMatchNoteEntity
import com.playfieldportal.core.data.database.entity.AchievementMetadataCacheEntity
import com.playfieldportal.core.data.database.entity.AchievementProviderSyncStateEntity
import com.playfieldportal.core.data.database.entity.GameEntity
import com.playfieldportal.core.data.database.entity.ProviderGameLinkEntity
import com.playfieldportal.core.data.database.entity.SteamNoAchievementsEntity
import com.playfieldportal.core.data.database.entity.SteamOwnedGameEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Selective sync, Task 4: every account-wide projection (tracked list, wallet, rarest and recent
 * feeds) reads only identities in the confirmed-local-match ledger. An old account import is a
 * bare set with no ledger row and must contribute nothing; a removed game keeps its ledger row
 * (not present) and keeps contributing its cached coins. Also pins the atomic set replacement and
 * the Clear all tracked achievements transaction.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AchievementEligibilityDaoTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        PFPDatabase::class.java,
    ).allowMainThreadQueries().build()

    private val sets = db.accountAchievementSetDao()
    private val coins = db.accountAchievementDao()
    private val links = db.providerGameLinkDao()
    private val tracking = db.achievementTrackingDao()
    private val notes = db.achievementMatchNoteDao()
    private val owned = db.steamOwnedGamesDao()

    @After fun tearDown() = db.close()

    private suspend fun seedGame(title: String, missing: Boolean = false): Long = db.gameDao().upsert(
        GameEntity(
            title = title,
            platformId = "snes",
            romPath = null,
            packageName = null,
            emulatorPackage = null,
            artworkUri = null,
            heroUri = null,
            logoUri = null,
            description = null,
            developer = null,
            publisher = null,
            releaseYear = null,
            genre = null,
            steamGridDbId = null,
            isMissing = missing,
        ),
    )

    private suspend fun seedLink(gameId: Long, provider: String, providerGameId: String) =
        links.upsert(ProviderGameLinkEntity(gameId, provider, providerGameId, "MANUAL", 0L))

    private fun set(provider: String, providerGameId: String, bronzeEarned: Int, bronzeTotal: Int = bronzeEarned) =
        AccountAchievementSetEntity(
            provider = provider, providerGameId = providerGameId, title = "Set $providerGameId",
            bronzeTotal = bronzeTotal, bronzeEarned = bronzeEarned, lastSyncedAt = 1L,
        )

    private fun coin(provider: String, providerGameId: String, id: String, rarity: Double, earnedAt: Long?) =
        AccountAchievementEntity(
            provider = provider, providerGameId = providerGameId, providerAchievementId = id,
            title = id, description = "", tier = "BRONZE", globalRarity = rarity,
            isEarned = true, earnedAt = earnedAt,
        )

    /** One confirmed + present RA game, one confirmed but removed Steam game, one orphan import. */
    private suspend fun seedMixedLibrary(): Long {
        val present = seedGame("Chrono Trigger")
        seedLink(present, "RETRO_ACHIEVEMENTS", "319")
        tracking.confirm("RETRO_ACHIEVEMENTS", "319", "Chrono Trigger", now = 10L)
        sets.upsert(set("RETRO_ACHIEVEMENTS", "319", bronzeEarned = 2))
        coins.upsertAll(listOf(coin("RETRO_ACHIEVEMENTS", "319", "a", 5.0, 100L)))

        // Removed: matched once, its game row is gone now (cascade removed the link too).
        tracking.confirm("STEAM", "220", "Half-Life 2", now = 10L)
        tracking.markAbsent("STEAM", "220")
        sets.upsert(set("STEAM", "220", bronzeEarned = 1))
        coins.upsertAll(listOf(coin("STEAM", "220", "b", 1.0, 200L)))

        // An old account-wide import: a set with no ledger row. Never matched locally.
        sets.upsert(set("STEAM", "999", bronzeEarned = 40))
        coins.upsertAll(listOf(coin("STEAM", "999", "c", 0.1, 300L)))
        return present
    }

    @Test
    fun `orphan imports contribute nothing to the wallet, the tracked list or the feeds`() = runTest {
        seedMixedLibrary()

        // 2 bronze (present) + 1 bronze (removed) — the import's 40 bronze never count.
        assertEquals(3 * 15, sets.observeWalletCoins().first())
        val tracked = sets.observeAccountSets().first().map { it.providerGameId }.toSet()
        assertEquals(setOf("319", "220"), tracked)
        assertEquals(listOf("b", "a"), coins.observeRarestEarned(10).first().map { it.title })
        assertEquals(listOf("b", "a"), coins.observeRecentEarned(10).first().map { it.title })
    }

    @Test
    fun `a removed game stays tracked and is labelled not present`() = runTest {
        seedMixedLibrary()

        val rows = sets.observeAccountSets().first().associateBy { it.providerGameId }
        assertTrue(rows.getValue("319").isPresent)
        assertFalse(rows.getValue("220").isPresent)
        assertEquals(1, rows.getValue("220").bronzeEarned)
    }

    @Test
    fun `confirm is keyed by provider identity so a reinstall keeps one history entry`() = runTest {
        tracking.confirm("RETRO_ACHIEVEMENTS", "319", "Chrono Trigger", now = 10L)
        tracking.markAbsent("RETRO_ACHIEVEMENTS", "319")
        tracking.confirm("RETRO_ACHIEVEMENTS", "319", "Chrono Trigger", now = 50L)

        val all = tracking.getAllIdentities()
        assertEquals(1, all.size)
        val row = all.single()
        assertEquals(10L, row.firstMatchedAt)
        assertEquals(50L, row.lastMatchedAt)
        assertTrue(row.isPresent)
    }

    @Test
    fun `link presence reports missing games so they never enter a match or sync queue`() = runTest {
        val here = seedGame("Here")
        val gone = seedGame("Gone", missing = true)
        seedLink(here, "RETRO_ACHIEVEMENTS", "1")
        seedLink(gone, "RETRO_ACHIEVEMENTS", "2")

        val byId = tracking.linksWithPresence().associateBy { it.providerGameId }
        assertFalse(byId.getValue("1").isMissing)
        assertTrue(byId.getValue("2").isMissing)
        assertEquals("Here", byId.getValue("1").title)
    }

    @Test
    fun `replaceSet swaps the coins and the summary together`() = runTest {
        sets.upsert(set("STEAM", "440", bronzeEarned = 1))
        coins.upsertAll(listOf(coin("STEAM", "440", "old", 5.0, 1L)))

        sets.replaceSet(
            set("STEAM", "440", bronzeEarned = 2),
            listOf(coin("STEAM", "440", "new1", 5.0, 2L), coin("STEAM", "440", "new2", 5.0, 3L)),
        )

        assertEquals(listOf("new1", "new2"), coins.getForSet("STEAM", "440").map { it.providerAchievementId }.sorted())
        assertEquals(2, sets.getSet("STEAM", "440")!!.bronzeEarned)
    }

    @Test
    fun `awaiting sync lists present linked games that were never checked`() = runTest {
        val synced = seedGame("Synced")
        val fresh = seedGame("Fresh")
        val checkedEmpty = seedGame("No achievements")
        val gone = seedGame("Gone", missing = true)
        seedLink(synced, "RETRO_ACHIEVEMENTS", "1")
        seedLink(fresh, "RETRO_ACHIEVEMENTS", "2")
        seedLink(checkedEmpty, "STEAM", "3")
        seedLink(gone, "RETRO_ACHIEVEMENTS", "4")
        sets.upsert(set("RETRO_ACHIEVEMENTS", "1", bronzeEarned = 1))
        // A check that found no achievement set is not "awaiting" forever.
        tracking.confirm("STEAM", "3", "No achievements", now = 1L)
        tracking.recordCheck("STEAM", "3", checkedAt = 5L, snapshot = null)

        val awaiting = sets.observeAwaitingSync().first()
        assertEquals(listOf("Fresh"), awaiting.map { it.title })
        assertEquals(fresh, awaiting.single().gameId)
    }

    @Test
    fun `clear all removes every achievement record but keeps games, links and ownership`() = runTest {
        val present = seedMixedLibrary()
        tracking.upsertProviderState(AchievementProviderSyncStateEntity(provider = "STEAM", lastCheckedAt = 5L))
        tracking.upsertMetadata(AchievementMetadataCacheEntity("STEAM", "220", "[]", null, 5L))
        notes.upsert(AchievementMatchNoteEntity(present, "Not found", 1L))
        owned.upsertAll(listOf(SteamOwnedGameEntity("220", "Half-Life 2", 90, syncedPlaytimeMinutes = 90, fetchedAt = 1L)))
        owned.rememberNoAchievements(SteamNoAchievementsEntity("555", 1L))

        tracking.clearAllAchievementRecords()

        assertEquals(0, sets.getAllSets().size)
        assertEquals(0, coins.getForSet("STEAM", "999").size + coins.getForSet("RETRO_ACHIEVEMENTS", "319").size)
        assertEquals(0, tracking.getAllIdentities().size)
        assertNull(tracking.getProviderState("STEAM"))
        assertNull(tracking.getMetadata("STEAM", "220"))
        assertEquals(0, notes.observeAll().first().size)
        assertEquals(0, sets.observeWalletCoins().first())
        // Library, links and the ownership cache survive; only the sync bookmarks are invalidated.
        assertNotNull(db.gameDao().getById(present))
        assertNotNull(links.getForGame(present))
        assertEquals(1, owned.ownedCount())
        assertNull(owned.syncedPlaytime("220"))
        assertTrue(owned.noAchievementAppids().isEmpty())
    }
}
