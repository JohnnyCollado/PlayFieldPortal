package com.playfieldportal.feature.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.playfieldportal.core.data.database.dao.BackupDao
import com.playfieldportal.core.data.database.dao.CategoryDao
import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.database.dao.PlaySessionDao
import com.playfieldportal.core.data.database.entity.AccountAchievementEntity
import com.playfieldportal.core.data.database.entity.AccountAchievementSetEntity
import com.playfieldportal.core.data.database.entity.AchievementProviderSyncStateEntity
import com.playfieldportal.core.data.database.entity.AchievementTrackedIdentityEntity
import com.playfieldportal.core.data.database.entity.GameEntity
import com.playfieldportal.core.data.database.entity.ProviderGameLinkEntity
import com.playfieldportal.core.data.repository.BackupFolderRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
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
 * Selective sync, Task 2: backups carry the achievement records — the confirmed-local-match ledger,
 * per-provider sync state, sets, coins and provider links — and restore them after the games they
 * link to. An archive made before these entries existed restores exactly as before and leaves the
 * device's achievement records alone.
 */
class BackupAchievementsTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val context = mockk<Context>(relaxed = true)
    private val gameDao = mockk<GameDao>(relaxed = true)
    private val categoryDao = mockk<CategoryDao>(relaxed = true)
    private val playSessionDao = mockk<PlaySessionDao>(relaxed = true)
    private val backupDao = mockk<BackupDao>(relaxed = true)
    private val backupFolderRepository = mockk<BackupFolderRepository>(relaxed = true)
    private lateinit var exportDir: File

    private val identity = AchievementTrackedIdentityEntity(
        provider = "RETRO_ACHIEVEMENTS", providerGameId = "319", title = "Chrono Trigger",
        firstMatchedAt = 1L, lastMatchedAt = 2L, isPresent = true, summarySnapshot = "ra:v1:1,1,1,1,1,1",
    )
    private val state = AchievementProviderSyncStateEntity(provider = "RETRO_ACHIEVEMENTS", lastCheckedAt = 5L, cohortNext = 3)
    private val set = AccountAchievementSetEntity(provider = "RETRO_ACHIEVEMENTS", providerGameId = "319", title = "Chrono Trigger", bronzeEarned = 1)
    private val coin = AccountAchievementEntity(
        provider = "RETRO_ACHIEVEMENTS", providerGameId = "319", providerAchievementId = "a",
        title = "A", description = "", tier = "BRONZE", globalRarity = 5.0, isEarned = true,
    )
    private val link = ProviderGameLinkEntity(1L, "RETRO_ACHIEVEMENTS", "319", "MANUAL", 0L)

    @Before
    fun setUp() {
        mockkStatic(Uri::class)
        every { Uri.fromFile(any()) } returns mockk(relaxed = true)
        coEvery { backupFolderRepository.get() } returns "content://backup/tree"
        every { context.filesDir } returns tempFolder.newFolder("filesDir")
        every { context.cacheDir } returns tempFolder.newFolder("cache")
        exportDir = tempFolder.newFolder("backups")

        coEvery { gameDao.getAll() } returns listOf(game())
        coEvery { categoryDao.getAll() } returns emptyList()
        coEvery { categoryDao.getAllItems() } returns emptyList()
        coEvery { playSessionDao.getAll() } returns emptyList()
        coEvery { backupDao.getAchievementIdentities() } returns listOf(identity)
        coEvery { backupDao.getAchievementSyncStates() } returns listOf(state)
        coEvery { backupDao.getAchievementSets() } returns listOf(set)
        coEvery { backupDao.getAchievementCoins() } returns listOf(coin)
        coEvery { backupDao.getProviderGameLinks() } returns listOf(link)
    }

    @After fun tearDown() = unmockkAll()

    private inner class Manager : BackupManager(
        context, gameDao, categoryDao, playSessionDao, backupDao, backupFolderRepository,
        mockk(relaxed = true), mockk(relaxed = true),
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
    fun `a backup carries the ledger, sync state, sets, coins and links`() = runTest {
        val mgr = Manager()
        assertTrue(mgr.createBackup(1, "1", 10L) is BackupResult.Success)

        val entries = entriesOf(mgr.lastExported!!)
        listOf(
            BackupEntry.ACHIEVEMENT_IDENTITIES,
            BackupEntry.ACHIEVEMENT_SYNC_STATE,
            BackupEntry.ACHIEVEMENT_SETS,
            BackupEntry.ACHIEVEMENT_COINS,
            BackupEntry.PROVIDER_GAME_LINKS,
        ).forEach { name -> assertTrue("missing $name", entries.containsKey(name)) }
        assertTrue(entries.getValue(BackupEntry.ACHIEVEMENT_IDENTITIES).contains("\"319\""))
    }

    @Test
    fun `restore puts achievement records back after the games they link to`() {
        val mgr = Manager()
        runTest { mgr.createBackup(1, "1", 10L) }

        restoreFrom(mgr, mgr.lastExported!!)

        coVerifyOrder {
            gameDao.insertAllReplace(any())
            backupDao.replaceAchievementRecords(
                listOf(identity), listOf(state), listOf(set), listOf(coin), listOf(link),
            )
        }
    }

    @Test
    fun `an older backup without achievement entries leaves them untouched`() {
        val file = File(exportDir, "old$BACKUP_FILE_EXTENSION")
        val json = Json { prettyPrint = false }
        val manifest = BackupManifest(appVersionCode = 1, appVersionName = "1.0", createdAt = 0L, gameCount = 1, sessionCount = 0, categoryCount = 0)
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(BackupEntry.MANIFEST))
            zip.write(json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
            zip.closeEntry()
        }

        restoreFrom(Manager(), file)

        coVerify(exactly = 0) { backupDao.replaceAchievementRecords(any(), any(), any(), any(), any()) }
    }
}
