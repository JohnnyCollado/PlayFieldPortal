package com.playfieldportal.core.data.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.database.PFPDatabase
import com.playfieldportal.core.data.database.entity.GameEntity
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
 * [GameDao.getAppEntry] is the row that IS an Android app — the one Mark as Game, Unmark, the
 * Android library's Remove and every app card's artwork resolve to.
 *
 * A launcher's exported games share its package name. A harvested shortcut carries a shortcut id,
 * but a GameNative export carries a launch intent instead, and with only the shortcut id excluded
 * the GameNative app resolved to whichever of its games was imported first: its card showed that
 * game's artwork, and Mark as Game moved that game onto the Android platform.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class GameDaoAppEntryTest {

    private lateinit var db: PFPDatabase
    private lateinit var dao: GameDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PFPDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.gameDao()
    }

    @After
    fun tearDown() = db.close()

    private fun row(
        title: String,
        platformId: String,
        shortcutId: String? = null,
        intentUri: String? = null,
    ) = GameEntity(
        title = title,
        platformId = platformId,
        romPath = null,
        packageName = "app.gamenative",
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
        launchShortcutId = shortcutId,
        launchIntentUri = intentUri,
    )

    private val ffviIntent =
        "intent:#Intent;action=app.gamenative.LAUNCH_GAME;component=app.gamenative/.MainActivity;" +
            "i.app_id=37746091;S.game_source=CUSTOM_GAME;end"

    @Test
    fun `a launcher's exported game is not the launcher app`() = runTest {
        dao.upsert(row("FINAL FANTASY VI", "windows", intentUri = ffviIntent))

        assertNull(dao.getAppEntry("app.gamenative"))
    }

    @Test
    fun `a harvested launcher shortcut is not the launcher app`() = runTest {
        dao.upsert(row("Some Game", "windows", shortcutId = "shortcut-1"))

        assertNull(dao.getAppEntry("app.gamenative"))
    }

    @Test
    fun `the app's own row is found even when its games were imported first`() = runTest {
        dao.upsert(row("FINAL FANTASY VI", "windows", intentUri = ffviIntent))
        val appId = dao.upsert(row("GameNative", "android"))

        assertEquals(appId, dao.getAppEntry("app.gamenative")?.id)
    }
}
