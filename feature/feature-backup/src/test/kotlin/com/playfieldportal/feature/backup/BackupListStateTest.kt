package com.playfieldportal.feature.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.playfieldportal.core.data.database.ListStateBackfiller
import com.playfieldportal.core.data.database.dao.BackupDao
import com.playfieldportal.core.data.database.dao.CategoryDao
import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.database.dao.PlaySessionDao
import com.playfieldportal.core.data.database.entity.AppUsageEntity
import com.playfieldportal.core.data.database.entity.CategoryEntity
import com.playfieldportal.core.data.database.entity.GameEntity
import com.playfieldportal.core.data.database.entity.ListItemEntity
import com.playfieldportal.core.data.database.entity.ListSettingEntity
import com.playfieldportal.core.data.database.entity.UmdSlotEntity
import com.playfieldportal.core.data.repository.BackupFolderRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backups carry how each list is arranged — its Custom order and pinned games, its own sort, the
 * game in each column's UMD slot, and app launch recency — and restore it after the games and
 * categories it points at. An archive made before any of it existed is brought forward the way
 * the database migration brings an old install forward, rather than restored as "nothing arranged".
 */
class BackupListStateTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val context = mockk<Context>(relaxed = true)
    private val gameDao = mockk<GameDao>(relaxed = true)
    private val categoryDao = mockk<CategoryDao>(relaxed = true)
    private val playSessionDao = mockk<PlaySessionDao>(relaxed = true)
    private val backupDao = mockk<BackupDao>(relaxed = true)
    private val backupFolderRepository = mockk<BackupFolderRepository>(relaxed = true)
    private val backfiller = mockk<ListStateBackfiller>(relaxed = true)
    private lateinit var exportDir: File

    private val item = ListItemEntity(listKey = "root:games", itemKey = "card:gba", position = 0)
    private val setting = ListSettingEntity(listKey = "root:games", sortMode = "CUSTOM")
    private val slot = UmdSlotEntity(columnId = "games", gameId = 1L, insertedAt = 5L)
    private val usage = AppUsageEntity(packageName = "com.netflix", lastLaunchedAt = 9L, launchCount = 2)
    private val gamesCategory = CategoryEntity(
        id = "games", name = "Game", iconKey = "ic_games", type = "BUILT_IN", position = 4, isGamingCategory = true,
    )

    @Before
    fun setUp() {
        mockkStatic(Uri::class)
        every { Uri.fromFile(any()) } returns mockk(relaxed = true)
        coEvery { backupFolderRepository.get() } returns "content://backup/tree"
        every { context.filesDir } returns tempFolder.newFolder("filesDir")
        every { context.cacheDir } returns tempFolder.newFolder("cache")
        exportDir = tempFolder.newFolder("backups")

        coEvery { gameDao.getAll() } returns listOf(game())
        coEvery { categoryDao.getAll() } returns listOf(gamesCategory)
        coEvery { categoryDao.getAllItems() } returns emptyList()
        coEvery { playSessionDao.getAll() } returns emptyList()
        coEvery { backupDao.getListItems() } returns listOf(item)
        coEvery { backupDao.getListSettings() } returns listOf(setting)
        coEvery { backupDao.getUmdSlots() } returns listOf(slot)
        coEvery { backupDao.getAppUsage() } returns listOf(usage)
    }

    @After fun tearDown() = unmockkAll()

    private inner class Manager : BackupManager(
        context, gameDao, categoryDao, playSessionDao, backupDao, backupFolderRepository,
        mockk(relaxed = true), mockk(relaxed = true), backfiller,
    ) {
        var lastExported: File? = null
        override suspend fun exportToBackupFolder(treeUri: String, source: File, name: String): Uri {
            val dest = File(exportDir, name)
            source.copyTo(dest, overwrite = true)
            lastExported = dest
            return Uri.fromFile(dest)
        }
        override suspend fun readSettingsSnapshot(): SettingsSnapshot = SettingsSnapshot()
        override suspend fun restoreSettingsSnapshot(snapshot: SettingsSnapshot) = Unit
    }

    private fun game() = GameEntity(
        id = 1L, title = "Chrono Trigger", platformId = "snes", romPath = null,
        packageName = null, emulatorPackage = null, artworkUri = null, heroUri = null,
        logoUri = null, description = null, developer = null, publisher = null,
        releaseYear = null, genre = null, steamGridDbId = null, createdAt = 0L,
    )

    private fun entriesOf(file: File): Map<String, String> = ZipInputStream(file.inputStream()).use { zip ->
        buildMap {
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
            }
        }
    }

    private fun restoreFrom(mgr: Manager, file: File) = runTest {
        val resolver = mockk<ContentResolver>()
        every { context.contentResolver } returns resolver
        every { resolver.openInputStream(any()) } returns file.inputStream()
        mgr.restoreBackup(Uri.fromFile(file))
    }

    @Test
    fun `a backup carries list order, list sorts, umd slots and app usage`() = runTest {
        val mgr = Manager()
        assertTrue(mgr.createBackup(1, "1", 10L) is BackupResult.Success)

        val entries = entriesOf(mgr.lastExported!!)
        listOf(BackupEntry.LIST_ITEMS, BackupEntry.LIST_SETTINGS, BackupEntry.UMD_SLOTS, BackupEntry.APP_USAGE)
            .forEach { name -> assertTrue("missing $name", entries.containsKey(name)) }
        assertTrue(entries.getValue(BackupEntry.LIST_ITEMS).contains("card:gba"))
        assertTrue(entries.getValue(BackupEntry.LIST_SETTINGS).contains("CUSTOM"))
        assertTrue(entries.getValue(BackupEntry.APP_USAGE).contains("com.netflix"))
    }

    @Test
    fun `restore puts list state back after the games and categories it points at`() {
        val mgr = Manager()
        runTest { mgr.createBackup(1, "1", 10L) }

        restoreFrom(mgr, mgr.lastExported!!)

        coVerifyOrder {
            gameDao.insertAllReplace(any())
            categoryDao.upsert(gamesCategory)
            backupDao.replaceListState(listOf(item), listOf(setting), listOf(slot), listOf(usage))
        }
        verify(exactly = 0) { backfiller.run() }
    }

    @Test
    fun `an older backup without list state is brought forward by the backfill`() {
        val file = File(exportDir, "old$BACKUP_FILE_EXTENSION")
        val json = Json { prettyPrint = false }
        val manifest = BackupManifest(appVersionCode = 1, appVersionName = "1.0", createdAt = 0L, gameCount = 0, sessionCount = 0, categoryCount = 0)
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(BackupEntry.MANIFEST))
            zip.write(json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
            zip.closeEntry()
        }

        restoreFrom(Manager(), file)

        // What this device had arranged is stale against the restored library, so it is cleared
        // before the archive's old-shape pins and card order are carried forward.
        coVerifyOrder {
            backupDao.replaceListState(emptyList(), emptyList(), emptyList(), emptyList())
            backfiller.run()
        }
        coVerify(exactly = 1) { backupDao.replaceListState(any(), any(), any(), any()) }
    }

    @Test
    fun `an older backup restores category items with an unknown date added`() {
        val file = File(exportDir, "old-items$BACKUP_FILE_EXTENSION")
        val json = Json { prettyPrint = false }
        val manifest = BackupManifest(appVersionCode = 1, appVersionName = "1.0", createdAt = 0L, gameCount = 0, sessionCount = 0, categoryCount = 0)
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(BackupEntry.MANIFEST))
            zip.write(json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
            zip.closeEntry()
            // Shaped as a pre-v54 archive wrote it: no addedAt field.
            zip.putNextEntry(ZipEntry(BackupEntry.CATEGORY_ITEMS))
            zip.write("""[{"categoryId":"games","itemId":"7","itemType":"game"}]""".toByteArray())
            zip.closeEntry()
        }

        restoreFrom(Manager(), file)

        coVerify { categoryDao.addItem(match { it.itemId == "7" && it.addedAt == 0L }) }
    }

    @Test
    fun `a umd slot is restored only when its game and its category both came back`() {
        val slots = listOf(
            UmdSlotEntity("games", gameId = 1, insertedAt = 0),
            UmdSlotEntity("games_gone_game", gameId = 99, insertedAt = 0),
            UmdSlotEntity("custom_deleted_7", gameId = 1, insertedAt = 0),
        )

        val kept = BackupManager.restorableUmdSlots(
            slots = slots,
            gameIds = setOf(1L),
            categoryIds = setOf("games", "games_gone_game"),
        )

        assertEquals(listOf("games"), kept.map { it.columnId })
    }
}
