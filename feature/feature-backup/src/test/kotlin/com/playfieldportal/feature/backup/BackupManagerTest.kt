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
    private lateinit var manager: BackupManager

    @Before
    fun setUp() {
        context        = mockk(relaxed = true)
        gameDao        = mockk(relaxed = true)
        categoryDao    = mockk(relaxed = true)
        playSessionDao = mockk(relaxed = true)
        backupDao      = mockk(relaxed = true)

        // android.net.Uri isn't available in plain JVM unit tests — mock its factory so the
        // restore tests (which take a Uri) can run.
        mockkStatic(Uri::class)
        every { Uri.fromFile(any()) } returns mockk(relaxed = true)

        // Point backup dir to the temp folder so files can actually be created
        every { context.getExternalFilesDir(any()) } returns tempFolder.root
        // filesDir backs the v2 asset bundling / restore staging; a real dir keeps those paths sane.
        every { context.filesDir } returns tempFolder.newFolder("filesDir_default")

        manager = BackupManager(context, gameDao, categoryDao, playSessionDao, backupDao)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ── createBackup ────────────────────────────────────────────────────

    @Test
    fun `createBackup returns Success and zip file exists`() = runTest {
        coEvery { gameDao.getAll() }        returns listOf(fakeGame())
        coEvery { categoryDao.getAll() }    returns emptyList()
        coEvery { categoryDao.getAllItems() } returns emptyList()
        coEvery { playSessionDao.getAll() } returns emptyList()

        // Override backupDir to use temp folder
        val backupDir = tempFolder.newFolder("backups")
        val mgr = object : BackupManager(context, gameDao, categoryDao, playSessionDao, backupDao) {
            override fun backupDir(): File = backupDir

            override suspend fun readSettingsSnapshot(): SettingsSnapshot = SettingsSnapshot()

            override suspend fun restoreSettingsSnapshot(snapshot: SettingsSnapshot) = Unit
        }

        val result = mgr.createBackup(
            appVersionCode = 1,
            appVersionName = "0.1.0",
            createdAt = 1_000_000L,
        )

        assertTrue("Expected Success but got $result", result is BackupResult.Success)
        val file = (result as BackupResult.Success).file
        assertTrue("Backup file should exist", file.exists())
        assertTrue("File name should end with $BACKUP_FILE_EXTENSION", file.name.endsWith(BACKUP_FILE_EXTENSION))
    }

    @Test
    fun `createBackup reads all DAOs`() = runTest {
        coEvery { gameDao.getAll() }         returns listOf(fakeGame(), fakeGame(id = 2))
        coEvery { categoryDao.getAll() }     returns listOf(fakeCategory())
        coEvery { categoryDao.getAllItems() } returns emptyList()
        coEvery { playSessionDao.getAll() }  returns listOf(fakeSession())

        val backupDir = tempFolder.newFolder("backups2")
        val mgr = object : BackupManager(context, gameDao, categoryDao, playSessionDao, backupDao) {
            override fun backupDir(): File = backupDir

            override suspend fun readSettingsSnapshot(): SettingsSnapshot = SettingsSnapshot()

            override suspend fun restoreSettingsSnapshot(snapshot: SettingsSnapshot) = Unit
        }

        mgr.createBackup(appVersionCode = 1, appVersionName = "0.1", createdAt = 2_000_000L)

        coVerify { gameDao.getAll() }
        coVerify { categoryDao.getAll() }
        coVerify { categoryDao.getAllItems() }
        coVerify { playSessionDao.getAll() }
    }

    @Test
    fun `createBackup writes settings snapshot to zip`() = runTest {
        coEvery { gameDao.getAll() }         returns emptyList()
        coEvery { categoryDao.getAll() }     returns emptyList()
        coEvery { categoryDao.getAllItems() } returns emptyList()
        coEvery { playSessionDao.getAll() }  returns emptyList()

        val backupDir = tempFolder.newFolder("settings_backup")
        val mgr = object : BackupManager(context, gameDao, categoryDao, playSessionDao, backupDao) {
            override fun backupDir(): File = backupDir

            override suspend fun readSettingsSnapshot(): SettingsSnapshot =
                SettingsSnapshot(
                    mapOf(
                        "sgdb_api_key" to "secret-api-key",
                        "display_show_boot" to "false",
                    )
                )
        }

        val result = mgr.createBackup(appVersionCode = 1, appVersionName = "0.1", createdAt = 2_500_000L)
        val backupFile = (result as BackupResult.Success).file

        val entries = readZipEntries(backupFile)
        val settingsJson = entries.getValue(BackupEntry.SETTINGS)
        val snapshot = Json.decodeFromString(SettingsSnapshot.serializer(), settingsJson)

        assertEquals("secret-api-key", snapshot.entries["sgdb_api_key"])
        assertEquals("false", snapshot.entries["display_show_boot"])
    }

    // ── restoreBackup ───────────────────────────────────────────────────

    @Test
    fun `restoreBackup returns Failure when URI cannot be opened`() = runTest {
        val contentResolver = mockk<ContentResolver>()
        every { context.contentResolver } returns contentResolver
        every { contentResolver.openInputStream(any()) } returns null

        val result = manager.restoreBackup(mockk(relaxed = true))

        assertTrue(result is RestoreResult.Failure)
        assertTrue((result as RestoreResult.Failure).reason.contains("open"))
    }

    @Test
    fun `restoreBackup returns Failure when format version is too new`() = runTest {
        // Build a valid ZIP with a manifest that has a future format version
        val backupDir = tempFolder.newFolder("restore_test")
        val mgr = object : BackupManager(context, gameDao, categoryDao, playSessionDao, backupDao) {
            override fun backupDir(): File = backupDir

            override suspend fun readSettingsSnapshot(): SettingsSnapshot = SettingsSnapshot()
        }

        coEvery { gameDao.getAll() }         returns emptyList()
        coEvery { categoryDao.getAll() }     returns emptyList()
        coEvery { categoryDao.getAllItems() } returns emptyList()
        coEvery { playSessionDao.getAll() }  returns emptyList()

        // Create a real backup first so we have a valid ZIP to tamper with
        val createResult = mgr.createBackup(
            appVersionCode = 1,
            appVersionName = "0.1",
            createdAt = 3_000_000L,
        )
        assertTrue(createResult is BackupResult.Success)

        // Build a ZIP that claims to be format version 999
        val tamperedFile = File(backupDir, "tampered$BACKUP_FILE_EXTENSION")
        buildTamperedZip(tamperedFile, futureVersion = 999)

        val uri = Uri.fromFile(tamperedFile)
        val contentResolver = mockk<ContentResolver>()
        every { context.contentResolver } returns contentResolver
        every { contentResolver.openInputStream(uri) } returns tamperedFile.inputStream()

        val result = mgr.restoreBackup(uri)
        assertTrue(result is RestoreResult.Failure)
        assertTrue((result as RestoreResult.Failure).reason.contains("newer"))
    }

    @Test
    fun `restoreBackup calls deleteAll and insertAllReplace for a valid backup`() = runTest {
        val backupDir = tempFolder.newFolder("restore_valid")
        val mgr = object : BackupManager(context, gameDao, categoryDao, playSessionDao, backupDao) {
            override fun backupDir(): File = backupDir

            override suspend fun readSettingsSnapshot(): SettingsSnapshot = SettingsSnapshot()

            override suspend fun restoreSettingsSnapshot(snapshot: SettingsSnapshot) = Unit
        }

        coEvery { gameDao.getAll() }         returns listOf(fakeGame())
        coEvery { categoryDao.getAll() }     returns emptyList()
        coEvery { categoryDao.getAllItems() } returns emptyList()
        coEvery { playSessionDao.getAll() }  returns emptyList()

        val createResult = mgr.createBackup(1, "0.1", 4_000_000L)
        val backupFile = (createResult as BackupResult.Success).file

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
        val backupDir = tempFolder.newFolder("restore_settings")
        val backupFile = File(backupDir, "with_settings$BACKUP_FILE_EXTENSION")
        val expected = SettingsSnapshot(
            mapOf(
                "sgdb_api_key" to "restored-api-key",
                "library_setup_complete" to "true",
            )
        )
        buildSettingsZip(backupFile, expected)

        var restored: SettingsSnapshot? = null
        val mgr = object : BackupManager(context, gameDao, categoryDao, playSessionDao, backupDao) {
            override suspend fun restoreSettingsSnapshot(snapshot: SettingsSnapshot) {
                restored = snapshot
            }
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
        // A v2 backup whose game art was written under a DIFFERENT package's filesDir, plus the
        // bundled art file itself.
        val filesDir = tempFolder.newFolder("filesDir")
        every { context.filesDir } returns filesDir

        val oldPath = "/data/user/0/com.other.pkg/files/artwork/7/hero.jpg"
        val backupFile = File(tempFolder.newFolder("v2"), "v2$BACKUP_FILE_EXTENSION")
        buildV2ArtworkZip(backupFile, gameId = 7L, heroPath = oldPath)

        val contentResolver = mockk<ContentResolver>()
        every { context.contentResolver } returns contentResolver
        every { contentResolver.openInputStream(any()) } returns backupFile.inputStream()

        val inserted = slot<List<GameEntity>>()
        coEvery { gameDao.deleteAll() }                       returns Unit
        coEvery { playSessionDao.deleteAll() }               returns Unit
        coEvery { gameDao.insertAllReplace(capture(inserted)) } returns Unit

        val mgr = BackupManager(context, gameDao, categoryDao, playSessionDao, backupDao)
        val result = mgr.restoreBackup(Uri.fromFile(backupFile))

        assertTrue("Expected Success, got $result", result is RestoreResult.Success)
        val hero = inserted.captured.single().heroUri!!
        val expected = File(filesDir, "artwork/7/hero.jpg")
        // The rewrite always emits '/' separators (correct on Android); normalise for the Windows CI.
        assertEquals(expected.absolutePath.replace('\\', '/'), hero.replace('\\', '/'))
        assertTrue("Bundled art should have been extracted", expected.exists())
    }

    // ── listBackupFiles ──────────────────────────────────────────────────

    @Test
    fun `listBackupFiles returns only pfpbackup files sorted newest first`() = runTest {
        val backupDir = tempFolder.newFolder("list_test")
        val mgr = object : BackupManager(context, gameDao, categoryDao, playSessionDao, backupDao) {
            override fun backupDir(): File = backupDir
        }

        val old = File(backupDir, "old$BACKUP_FILE_EXTENSION").also {
            it.createNewFile()
            it.setLastModified(1_000L)
        }
        val newer = File(backupDir, "newer$BACKUP_FILE_EXTENSION").also {
            it.createNewFile()
            it.setLastModified(2_000L)
        }
        File(backupDir, "unrelated.txt").createNewFile()

        val files = mgr.listBackupFiles()
        assertEquals(2, files.size)
        assertEquals(newer.name, files[0].name)
        assertEquals(old.name, files[1].name)
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun fakeGame(id: Long = 1L) = GameEntity(
        id         = id,
        title      = "Test Game $id",
        platformId = "psx",
        romPath    = null,
        packageName     = null,
        emulatorPackage = null,
        artworkUri = null,
        heroUri    = null,
        logoUri    = null,
        description = null,
        developer   = null,
        publisher   = null,
        releaseYear = null,
        genre       = null,
        steamGridDbId = null,
        createdAt   = 0L,
    )

    private fun fakeCategory() = CategoryEntity(
        id       = "cat1",
        name     = "Favorites",
        iconKey  = "star",
        type     = "BUILTIN",
        position = 0,
    )

    private fun fakeSession() = PlaySessionEntity(
        id             = 1L,
        gameId         = 1L,
        platformId     = "psx",
        launchedAt     = 1_000L,
        durationMillis = 60_000L,
    )

    private fun buildTamperedZip(dest: File, futureVersion: Int) {
        val json = Json { prettyPrint = false }
        val manifest = BackupManifest(
            formatVersion  = futureVersion,
            appVersionCode = 99,
            appVersionName = "99.0",
            createdAt      = 0L,
            gameCount      = 0,
            sessionCount   = 0,
            categoryCount  = 0,
        )
        ZipOutputStream(dest.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(BackupEntry.MANIFEST))
            zip.write(json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
            zip.closeEntry()
        }
    }

    // A v2 backup with one game whose hero art lives under another package's filesDir, plus the
    // bundled art file at the matching relative path.
    private fun buildV2ArtworkZip(dest: File, gameId: Long, heroPath: String) {
        val json = Json { prettyPrint = false }
        val manifest = BackupManifest(
            appVersionCode = 2,
            appVersionName = "2.0",
            createdAt = 0L,
            gameCount = 1,
            sessionCount = 0,
            categoryCount = 0,
        )
        val game = fakeGame(gameId).copy(heroUri = heroPath)
        ZipOutputStream(dest.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(BackupEntry.MANIFEST))
            zip.write(json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(BackupEntry.GAMES))
            zip.write(
                json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(GameEntity.serializer()),
                    listOf(game),
                ).toByteArray()
            )
            zip.closeEntry()
            // The bundled art file, keyed by its path relative to filesDir.
            zip.putNextEntry(ZipEntry("${BACKUP_FILES_PREFIX}artwork/$gameId/hero.jpg"))
            zip.write(byteArrayOf(1, 2, 3, 4))
            zip.closeEntry()
        }
    }

    private fun buildSettingsZip(dest: File, settings: SettingsSnapshot) {
        val json = Json { prettyPrint = false }
        val manifest = BackupManifest(
            appVersionCode = 1,
            appVersionName = "0.1",
            createdAt = 0L,
            gameCount = 0,
            sessionCount = 0,
            categoryCount = 0,
        )
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
                if (!entry.isDirectory) {
                    entries[entry.name] = zip.bufferedReader().readText()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return entries
    }
}
