package com.playfieldportal.core.data.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.database.PFPDatabase
import com.playfieldportal.core.data.database.entity.CategoryEntity
import com.playfieldportal.core.data.database.entity.GameEntity
import com.playfieldportal.core.data.database.entity.ListItemEntity
import com.playfieldportal.core.data.database.entity.ListSettingEntity
import com.playfieldportal.core.data.database.entity.UmdSlotEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The per-list state tables: a list's custom order and pins (`list_items`), its sort override
 * (`list_settings`), a gaming column's inserted game (`umd_slots`) and app launch recency
 * (`app_usage`). Order and pin are independent, and a row that carries neither is not kept.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ListStateDaoTest {

    private lateinit var db: PFPDatabase
    private lateinit var dao: ListStateDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PFPDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.listStateDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun order(listKey: String): List<String> =
        dao.getItems(listKey).filter { it.position != null }.sortedBy { it.position }.map { it.itemKey }

    // ── Custom order ──────────────────────────────────────────────────────────

    @Test
    fun `replaceOrder writes positions 0 to n-1 in the given order`() = runTest {
        dao.replaceOrder("root:games", listOf("card:gba", "row:all_games", "collection:4"))

        assertEquals(
            listOf("card:gba=0", "row:all_games=1", "collection:4=2"),
            dao.getItems("root:games").sortedBy { it.position }.map { "${it.itemKey}=${it.position}" },
        )
    }

    @Test
    fun `replaceOrder replaces the previous order and drops items no longer listed`() = runTest {
        dao.replaceOrder("all_games", listOf("game:1", "game:2", "game:3"))

        dao.replaceOrder("all_games", listOf("game:3", "game:1"))

        assertEquals(listOf("game:3", "game:1"), order("all_games"))
        assertNull(dao.getItem("all_games", "game:2"))
    }

    @Test
    fun `replaceOrder keeps a pin on an item it reorders or drops`() = runTest {
        dao.setPinned("all_games", "game:2", pinned = true)
        dao.setPinned("all_games", "game:9", pinned = true)

        dao.replaceOrder("all_games", listOf("game:1", "game:2"))

        assertEquals(true, dao.getItem("all_games", "game:2")?.pinned)
        assertEquals(1, dao.getItem("all_games", "game:2")?.position)
        // Not in the new order, but still pinned: the row survives without a position.
        assertEquals(true, dao.getItem("all_games", "game:9")?.pinned)
        assertNull(dao.getItem("all_games", "game:9")?.position)
    }

    @Test
    fun `replaceOrder leaves other lists alone`() = runTest {
        dao.replaceOrder("card:gba", listOf("game:1", "game:2"))

        dao.replaceOrder("card:nds", listOf("game:5"))

        assertEquals(listOf("game:1", "game:2"), order("card:gba"))
    }

    // ── Pins ──────────────────────────────────────────────────────────────────

    @Test
    fun `setPinned does not touch the position`() = runTest {
        dao.replaceOrder("all_games", listOf("game:1", "game:2"))

        dao.setPinned("all_games", "game:2", pinned = true)

        assertEquals(ListItemEntity("all_games", "game:2", position = 1, pinned = true), dao.getItem("all_games", "game:2"))
    }

    @Test
    fun `unpinning an item with no position removes its row`() = runTest {
        dao.setPinned("all_games", "game:2", pinned = true)

        dao.setPinned("all_games", "game:2", pinned = false)

        assertNull(dao.getItem("all_games", "game:2"))
    }

    // ── Clean-up ──────────────────────────────────────────────────────────────

    @Test
    fun `deleteLists removes the items and the sort setting of each list`() = runTest {
        dao.replaceOrder("collection:5", listOf("game:1"))
        dao.upsertSetting(ListSettingEntity("collection:5", "CUSTOM"))
        dao.replaceOrder("collection:6", listOf("game:1"))
        dao.upsertSetting(ListSettingEntity("collection:6", "TITLE"))

        dao.deleteLists(listOf("collection:5"))

        assertEquals(emptyList<ListItemEntity>(), dao.getItems("collection:5"))
        assertNull(dao.getSetting("collection:5"))
        assertEquals(listOf("game:1"), order("collection:6"))
        assertEquals("TITLE", dao.getSetting("collection:6")?.sortMode)
    }

    @Test
    fun `deleteItemEverywhere removes the item from every list`() = runTest {
        dao.replaceOrder("root:games", listOf("collection:5", "row:all_games"))
        dao.replaceOrder("root:custom_a_1", listOf("collection:5"))

        dao.deleteItemEverywhere("collection:5")

        assertEquals(listOf("row:all_games"), order("root:games"))
        assertEquals(emptyList<String>(), order("root:custom_a_1"))
    }

    // ── UMD slot ──────────────────────────────────────────────────────────────

    @Test
    fun `a umd slot is removed with its category`() = runTest {
        db.categoryDao().upsert(category("custom_rpg_9"))
        db.umdSlotDao().upsert(UmdSlotEntity(columnId = "custom_rpg_9", gameId = 7, insertedAt = 1))

        db.categoryDao().deleteById("custom_rpg_9")

        assertNull(db.umdSlotDao().get("custom_rpg_9"))
    }

    @Test
    fun `inserting a second game replaces the column's slot`() = runTest {
        db.categoryDao().upsert(category("games"))
        db.umdSlotDao().upsert(UmdSlotEntity(columnId = "games", gameId = 7, insertedAt = 1))

        db.umdSlotDao().upsert(UmdSlotEntity(columnId = "games", gameId = 8, insertedAt = 2))

        assertEquals(8L, db.umdSlotDao().get("games")?.gameId)
    }

    // ── Launch recency ────────────────────────────────────────────────────────

    @Test
    fun `markLaunched sets last played and nothing else`() = runTest {
        val id = db.gameDao().upsert(game("Chrono Trigger"))
        val before = db.gameDao().getById(id)!!

        db.gameDao().markLaunched(id, playedAt = 1_800_000_000_000)

        assertEquals(before.copy(lastPlayedAt = 1_800_000_000_000), db.gameDao().getById(id))
    }

    @Test
    fun `markLaunched stamps every disc of the launched set and no other game`() = runTest {
        val disc1 = db.gameDao().upsert(game("FF7 Disc 1").copy(discSetKey = "ff7", isDiscPrimary = true))
        val disc2 = db.gameDao().upsert(game("FF7 Disc 2").copy(discSetKey = "ff7"))
        val other = db.gameDao().upsert(game("Chrono Trigger"))

        db.gameDao().markLaunched(disc2, playedAt = 500)

        assertEquals(500L, db.gameDao().getById(disc1)?.lastPlayedAt)
        assertEquals(500L, db.gameDao().getById(disc2)?.lastPlayedAt)
        assertNull(db.gameDao().getById(other)?.lastPlayedAt)
    }

    @Test
    fun `recordLaunch creates the row then counts each launch`() = runTest {
        val usage = db.appUsageDao()

        usage.recordLaunch("com.netflix", at = 100)
        usage.recordLaunch("com.netflix", at = 250)

        val row = usage.getAll().single()
        assertEquals("com.netflix", row.packageName)
        assertEquals(250L, row.lastLaunchedAt)
        assertEquals(2, row.launchCount)
    }

    private fun category(id: String) = CategoryEntity(
        id = id,
        name = id,
        iconKey = "ic_games",
        type = "MANUAL",
        position = 0,
        isGamingCategory = true,
    )

    private fun game(title: String) = GameEntity(
        title = title,
        platformId = "snes",
        romPath = "/roms/snes/$title.sfc",
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
    )
}
