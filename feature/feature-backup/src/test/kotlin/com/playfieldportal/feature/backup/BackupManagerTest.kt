package com.playfieldportal.feature.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.playfieldportal.core.data.database.dao.BackupDao
import com.playfieldportal.core.data.database.dao.CategoryDao
import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.database.dao.PlaySessionDao
import com.playfieldportal.core.data.database.entity.CategoryEntity
import com.playfieldportal.core.data.database.entity.CategoryItemEntity
import com.playfieldportal.core.data.database.entity.GameEntity
import com.playfieldportal.core.data.database.entity.PlaySessionEntity
import com.playfieldportal.core.data.repository.BackupFolderRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
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

class BackupManagerTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var gameDao: GameDao
    private lateinit var categoryDao: CategoryDao
    private lateinit var playSessionDao: PlaySessionDao
    private lateinit var backupDao: BackupDao
    private lateinit var backupFolderRepository: BackupFolderRepository
    private lateinit var exportDir: File

    @Before
    fun setUp() {
        context        = mockk(relaxed = true)
        gameDao        = mockk(relaxed = true)
        categoryDao    = mockk(relaxed = true)
        playSessionDao = mockk(relaxed = true)
        backupDao      = mockk(relaxed = true)
        backupFolderRepository = mockk(relaxed = true)

        // android.net.Uri isn't available in plain JVM unit tests — mock its factory so the
        // restore tests (which take a Uri) and the export stub can run.
        mockkStatic(Uri::class)
        every { Uri.fromFile(any()) } returns mockk(relaxed = true)

        // A backup folder is configured (the SAF export is stubbed via ExportingBackupManager).
        coEvery { backupFolderRepository.get() } returns "content://backup/tree"

        // filesDir backs the v2 asset bundling / restore staging; cacheDir backs the temp ZIP.
        every { context.filesDir } returns tempFolder.newFolder("filesDir_default")
        every { context.cacheDir } returns tempFolder.newFolder("cache")

        exportDir = tempFolder.newFolder("backups")
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // Test double: exports the built ZIP into a real temp dir instead of a SAF tree, so the tests
    // can read the resulting file back. Records the last exported file.
    private open inner class ExportingBackupManager :
        BackupManager(context, gameDao, categoryDao, playSessionDao, backupDao, backupFolderRepository) {
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

    // ── createBackup ────────────────────────────────────────────────────

    @Test
    fun `createBackup returns Success and exported file exists`() = runTest {
        coEvery { gameDao.getAll() }          returns listOf(fakeGame())
        coEvery { categoryDao.getAll() }      returns emptyList()
        coEvery { categoryDao.getAllItems() } returns emptyList()
        coEvery { playSessionDao.getAll() }   returns emptyList()

        val mgr = ExportingBackupManager()
        val result = mgr.createBackup(appVersionCode = 1, appVersionName = "0.1.0", createdAt = 1_000_000L)

        assertTrue("Expected Success but got $result", result is BackupResult.Success)
        val name = (result as BackupResult.Success).displayName
        assertTrue("File name should end with $BACKUP_FILE_EXTENSION", name.endsWith(BACKUP_FILE_EXTENSION))
        assertTrue("Exported backup should exist", mgr.lastExported?.exists() == true)
    }

    @Test
    fun `createBackup returns Failure when no backup folder is set`() = runTest {
        coEvery { backupFolderRepository.get() } returns null
        coEvery { gameDao.getAll() } returns emptyList()

        val mgr = ExportingBackupManager()
        val result = mgr.createBackup(1, "0.1", 1L)

        assertTrue(result is BackupResult.Failure)
        assertTrue((result as BackupResult.Failure).reason.contains("backup folder", ignoreCase = true))
    }

    @Test
    fun `createBackup reads all DAOs`() = runTest {
        coEvery { gameDao.getAll() }          returns listOf(fakeGame(), fakeGame(id = 2))
        coEvery { categoryDao.getAll() }      returns listOf(fakeCategory())
        coEvery { categoryDao.getAllItems() } returns emptyList()
        coEvery { playSessionDao.getAll() }   returns listOf(fakeSession())

        ExportingBackupManager().createBackup(appVersionCode = 1, appVersionName = "0.1", createdAt = 2_000_000L)

        coVerify { gameDao.getAll() }
        coVerify { categoryDao.getAll() }
        coVerify { categoryDao.getAllItems() }
        coVerify { playSessionDao.getAll() }
    }

    @Test
    fun `createBackup writes settings snapshot to zip`() = runTest {
        coEvery { gameDao.getAll() }          returns emptyList()
        coEvery { categoryDao.getAll() }      returns emptyList()
        coEvery { categoryDao.getAllItems() } returns emptyList()
        coEvery { playSessionDao.getAll() }   returns emptyList()

        val mgr = object : ExportingBackupManager() {
            override suspend fun readSettingsSnapshot(): SettingsSnapshot =
                SettingsSnapshot(mapOf("sgdb_api_key" to "secret-api-key", "display_show_boot" to "false"))
        }

        val result = mgr.createBackup(appVersionCode = 1, appVersionName = "0.1", createdAt = 2_500_000L)
        assertTrue(result is BackupResult.Success)

        val entries = readZipEntries(mgr.lastExported!!)
        val snapshot = Json.decodeFromString(SettingsSnapshot.serializer(), entries.getValue(BackupEntry.SETTINGS))
        assertEquals("secret-api-key", snapshot.entries["sgdb_api_key"])
        assertEquals("false", snapshot.entries["display_show_boot"])
    }

    // ── restoreBackup ───────────────────────────────────────────────────

    @Test
    fun `restoreBackup returns Failure when URI cannot be opened`() = runTest {
        val contentResolver = mockk<ContentResolver>()
        every { context.contentResolver } returns contentResolver
        every { contentResolver.openInputStream(any()) } returns null

        val result = ExportingBackupManager().restoreBackup(mockk(relaxed = true))

        assertTrue(result is RestoreResult.Failure)
        assertTrue((result as RestoreResult.Failure).reason.contains("open"))
    }

    @Test
    fun `restoreBackup returns Failure when format version is too new`() = runTest {
        val tamperedFile = File(exportDir, "tampered$BACKUP_FILE_EXTENSION")
        buildTamperedZip(tamperedFile, futureVersion = 999)

        val uri = Uri.fromFile(tamperedFile)
        val contentResolver = mockk<ContentResolver>()
        every { context.contentResolver } returns contentResolver
        every { contentResolver.openInputStream(uri) } returns tamperedFile.inputStream()

        val result = ExportingBackupManager().restoreBackup(uri)
        assertTrue(result is RestoreResult.Failure)
        assertTrue((result as RestoreResult.Failure).reason.contains("newer"))
    }

    @Test
    fun `restoreBackup calls deleteAll and insertAllReplace for a valid backup`() = runTest {
        coEvery { gameDao.getAll() }          returns listOf(fakeGame())
        coEvery { categoryDao.getAll() }      returns emptyList()
        coEvery { categoryDao.getAllItems() } returns emptyList()
        coEvery { playSessionDao.getAll() }   returns emptyList()

        val mgr = ExportingBackupManager()
        val createResult = mgr.createBackup(1, "0.1", 4_000_000L)
        assertTrue(createResult is BackupResult.Success)
        val backupFile = mgr.lastExported!!

        val contentResolver = mockk<ContentResolver>()
        every { context.contentResolver } returns contentResolver
        every { contentResolver.openInputStream(any()) } returns backupFile.inputStream()

        coEvery { gameDao.deleteAll() }             returns Unit
        coEvery { gameDao.insertAllReplace(any()) } returns Unit
        coEvery { playSessionDao.deleteAll() }      returns Unit

        val result = mgr.restoreBackup(Uri.fromFile(backupFile))

        assertTrue("Expected Success, got $result", result is RestoreResult.Success)
        coVerify { gameDao.deleteAll() }
        coVerify { playSessionDao.deleteAll() }
        coVerify { gameDao.insertAllReplace(any()) }
    }

    @Test
    fun `restoreBackup applies settings snapshot when present`() = runTest {
        val backupFile = File(exportDir, "with_settings$BACKUP_FILE_EXTENSION")
        val expected = SettingsSnapshot(mapOf("sgdb_api_key" to "restored-api-key", "library_setup_complete" to "true"))
        buildSettingsZip(backupFile, expected)

        var restored: SettingsSnapshot? = null
        val mgr = object : ExportingBackupManager() {
            override suspend fun restoreSettingsSnapshot(snapshot: SettingsSnapshot) { restored = snapshot }
        }

        val contentResolver = mockk<ContentResolver>()
        every { context.contentResolver } returns contentResolver
        every { contentResolver.openInputStream(any()) } returns backupFile.inputStream()

        coEvery { gameDao.deleteAll() }        returns Unit
        coEvery { playSessionDao.deleteAll() } returns Unit

        val result = mgr.restoreBackup(Uri.fromFile(backupFile))

        assertTrue("Expected Success, got $result", result is RestoreResult.Success)
        assertEquals(expected, restored)
    }

    @Test
    fun `restoreBackup re-homes bundled artwork onto this filesDir`() = runTest {
        val filesDir = tempFolder.newFolder("filesDir")
        every { context.filesDir } returns filesDir

        val oldPath = "/data/user/0/com.other.pkg/files/artwork/7/hero.jpg"
        val backupFile = File(exportDir, "v2$BACKUP_FILE_EXTENSION")
        buildV2ArtworkZip(backupFile, gameId = 7L, heroPath = oldPath)

        val contentResolver = mockk<ContentResolver>()
        every { context.contentResolver } returns contentResolver
        every { contentResolver.openInputStream(any()) } returns backupFile.inputStream()

        val inserted = slot<List<GameEntity>>()
        coEvery { gameDao.deleteAll() }                         returns Unit
        coEvery { playSessionDao.deleteAll() }                  returns Unit
        coEvery { gameDao.insertAllReplace(capture(inserted)) } returns Unit

        val result = ExportingBackupManager().restoreBackup(Uri.fromFile(backupFile))

        assertTrue("Expected Success, got $result", result is RestoreResult.Success)
        val hero = inserted.captured.single().heroUri!!
        val expected = File(filesDir, "artwork/7/hero.jpg")
        assertEquals(expected.absolutePath.replace('\\', '/'), hero.replace('\\', '/'))
        assertTrue("Bundled art should have been extracted", expected.exists())
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun fakeGame(id: Long = 1L) = GameEntity(
        id = id, title = "Test Game $id", platformId = "psx", romPath = null,
        packageName = null, emulatorPackage = null, artworkUri = null, heroUri = null,
        logoUri = null, description = null, developer = null, publisher = null,
        releaseYear = null, genre = null, steamGridDbId = null, createdAt = 0L,
    )

    private fun fakeCategory() = CategoryEntity(id = "cat1", name = "Favorites", iconKey = "star", type = "BUILTIN", position = 0)

    private fun fakeSession() = PlaySessionEntity(id = 1L, gameId = 1L, platformId = "psx", launchedAt = 1_000L, durationMillis = 60_000L)

    private fun buildTamperedZip(dest: File, futureVersion: Int) {
        val json = Json { prettyPrint = false }
        val manifest = BackupManifest(
            formatVersion = futureVersion, appVersionCode = 99, appVersionName = "99.0",
            createdAt = 0L, gameCount = 0, sessionCount = 0, categoryCount = 0,
        )
        ZipOutputStream(dest.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(BackupEntry.MANIFEST))
            zip.write(json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
            zip.closeEntry()
        }
    }

    private fun buildV2ArtworkZip(dest: File, gameId: Long, heroPath: String) {
        val json = Json { prettyPrint = false }
        val manifest = BackupManifest(appVersionCode = 2, appVersionName = "2.0", createdAt = 0L, gameCount = 1, sessionCount = 0, categoryCount = 0)
        val game = fakeGame(gameId).copy(heroUri = heroPath)
        ZipOutputStream(dest.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(BackupEntry.MANIFEST))
            zip.write(json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(BackupEntry.GAMES))
            zip.write(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(GameEntity.serializer()), listOf(game)).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("${BACKUP_FILES_PREFIX}artwork/$gameId/hero.jpg"))
            zip.write(byteArrayOf(1, 2, 3, 4))
            zip.closeEntry()
        }
    }

    private fun buildSettingsZip(dest: File, settings: SettingsSnapshot) {
        val json = Json { prettyPrint = false }
        val manifest = BackupManifest(appVersionCode = 1, appVersionName = "0.1", createdAt = 0L, gameCount = 0, sessionCount = 0, categoryCount = 0)
        ZipOutputStream(dest.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(BackupEntry.MANIFEST))
            zip.write(json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(BackupEntry.SETTINGS))
            zip.write(json.encodeToString(SettingsSnapshot.serializer(), settings).toByteArray())
            zip.closeEntry()
        }
    }

    private fun readZipEntries(file: File): Map<String, String> {
        val entries = mutableMapOf<String, String>()
        ZipInputStream(file.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) entries[entry.name] = zip.bufferedReader().readText()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return entries
    }
}
