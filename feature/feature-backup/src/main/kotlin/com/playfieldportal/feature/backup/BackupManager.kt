package com.playfieldportal.feature.backup

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.playfieldportal.core.data.database.dao.CategoryDao
import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.database.dao.PlaySessionDao
import com.playfieldportal.core.data.database.entity.CategoryEntity
import com.playfieldportal.core.data.database.entity.CategoryItemEntity
import com.playfieldportal.core.data.database.entity.GameEntity
import com.playfieldportal.core.data.database.entity.PlaySessionEntity
import com.playfieldportal.core.data.datastore.pfpDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

sealed class BackupResult {
    data class Success(val file: File) : BackupResult()
    data class Failure(val reason: String, val cause: Throwable? = null) : BackupResult()
}

sealed class RestoreResult {
    object Success : RestoreResult()
    data class Failure(val reason: String, val cause: Throwable? = null) : RestoreResult()
}

@Singleton
open class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gameDao: GameDao,
    private val categoryDao: CategoryDao,
    private val playSessionDao: PlaySessionDao,
) {
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    // ── Export ──────────────────────────────────────────────────────────

    suspend fun createBackup(
        appVersionCode: Int,
        appVersionName: String,
        createdAt: Long,
    ): BackupResult = runCatching {
        val games        = gameDao.getAll()
        val categories   = categoryDao.getAll()
        val items        = categoryDao.getAllItems()
        val sessions     = playSessionDao.getAll()
        val settings     = readSettingsSnapshot()

        val manifest = BackupManifest(
            appVersionCode = appVersionCode,
            appVersionName = appVersionName,
            createdAt = createdAt,
            gameCount = games.size,
            sessionCount = sessions.size,
            categoryCount = categories.size,
        )

        val outDir = backupDir()
        outDir.mkdirs()

        val fileName = "pfp_backup_${createdAt}${BACKUP_FILE_EXTENSION}"
        val outFile  = File(outDir, fileName)

        ZipOutputStream(outFile.outputStream().buffered()).use { zip ->
            zip.writeJson(BackupEntry.MANIFEST,       json.encodeToString(BackupManifest.serializer(), manifest))
            zip.writeJson(BackupEntry.GAMES,          json.encodeToString(listSerializer<GameEntity>(), games))
            zip.writeJson(BackupEntry.CATEGORIES,     json.encodeToString(listSerializer<CategoryEntity>(), categories))
            zip.writeJson(BackupEntry.CATEGORY_ITEMS, json.encodeToString(listSerializer<CategoryItemEntity>(), items))
            zip.writeJson(BackupEntry.PLAY_SESSIONS,  json.encodeToString(listSerializer<PlaySessionEntity>(), sessions))
            zip.writeJson(BackupEntry.SETTINGS,       json.encodeToString(SettingsSnapshot.serializer(), settings))
        }

        outFile
    }.fold(
        onSuccess = { BackupResult.Success(it) },
        onFailure = { BackupResult.Failure(it.message ?: "Unknown error", it) },
    )

    // ── Import ──────────────────────────────────────────────────────────

    suspend fun restoreBackup(uri: Uri): RestoreResult = runCatching {
        val stream = context.contentResolver.openInputStream(uri)
            ?: return RestoreResult.Failure("Could not open backup file")

        val entries = readZipEntries(stream)

        val manifest = entries[BackupEntry.MANIFEST]?.let {
            json.decodeFromString(BackupManifest.serializer(), it)
        } ?: return RestoreResult.Failure("Backup is missing manifest")

        if (manifest.formatVersion > BACKUP_FORMAT_VERSION) {
            return RestoreResult.Failure(
                "Backup format v${manifest.formatVersion} is newer than this app supports (v$BACKUP_FORMAT_VERSION)"
            )
        }

        val games    = entries[BackupEntry.GAMES]?.let {
            json.decodeFromString(listSerializer<GameEntity>(), it)
        } ?: emptyList()

        val categories = entries[BackupEntry.CATEGORIES]?.let {
            json.decodeFromString(listSerializer<CategoryEntity>(), it)
        } ?: emptyList()

        val catItems = entries[BackupEntry.CATEGORY_ITEMS]?.let {
            json.decodeFromString(listSerializer<CategoryItemEntity>(), it)
        } ?: emptyList()

        val sessions = entries[BackupEntry.PLAY_SESSIONS]?.let {
            json.decodeFromString(listSerializer<PlaySessionEntity>(), it)
        } ?: emptyList()

        val settings = entries[BackupEntry.SETTINGS]?.let {
            json.decodeFromString(SettingsSnapshot.serializer(), it)
        }

        // Wipe current data and re-insert
        gameDao.deleteAll()
        playSessionDao.deleteAll()

        if (games.isNotEmpty())      gameDao.insertAllReplace(games)
        if (categories.isNotEmpty()) categoryDao.insertAll(categories)
        if (catItems.isNotEmpty())   catItems.forEach { categoryDao.addItem(it) }
        if (sessions.isNotEmpty())   playSessionDao.insertAll(sessions)
        if (settings != null)        restoreSettingsSnapshot(settings)

    }.fold(
        onSuccess = { RestoreResult.Success },
        onFailure = { RestoreResult.Failure(it.message ?: "Unknown error", it) },
    )

    // ── Helpers ─────────────────────────────────────────────────────────

    open fun backupDir(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        BACKUP_FOLDER,
    )

    fun listBackupFiles(): List<File> =
        backupDir().listFiles { f -> f.name.endsWith(BACKUP_FILE_EXTENSION) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    private fun readZipEntries(stream: InputStream): Map<String, String> {
        val map = mutableMapOf<String, String>()
        ZipInputStream(stream.buffered()).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    map[entry.name] = zip.bufferedReader().readText()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return map
    }

    private fun ZipOutputStream.writeJson(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    protected open suspend fun readSettingsSnapshot(): SettingsSnapshot {
        val prefs = context.pfpDataStore.data.first()
        val entries = mutableMapOf<String, String>()

        BACKED_UP_STRING_KEYS.forEach { key ->
            prefs[key]?.let { entries[key.name] = it }
        }
        BACKED_UP_BOOLEAN_KEYS.forEach { key ->
            prefs[key]?.let { entries[key.name] = it.toString() }
        }

        return SettingsSnapshot(entries)
    }

    protected open suspend fun restoreSettingsSnapshot(snapshot: SettingsSnapshot) {
        context.pfpDataStore.edit { prefs ->
            prefs.clear()

            BACKED_UP_STRING_KEYS.forEach { key ->
                snapshot.entries[key.name]?.let { prefs[key] = it }
            }
            BACKED_UP_BOOLEAN_KEYS.forEach { key ->
                snapshot.entries[key.name]?.toBooleanStrictOrNull()?.let { prefs[key] = it }
            }
        }
    }

    // Inline reified helper for list serializers — avoids allocating KType reflectively
    private inline fun <reified T> listSerializer() =
        kotlinx.serialization.builtins.ListSerializer(
            kotlinx.serialization.serializer<T>()
        )

    companion object {
        private val BACKED_UP_STRING_KEYS = listOf(
            stringPreferencesKey("display_wave_mode"),
            stringPreferencesKey("display_icon_style"),
            stringPreferencesKey("library_root_path"),
            stringPreferencesKey("sgdb_api_key"),
            stringPreferencesKey("controller_mappings_v1"),
        )

        private val BACKED_UP_BOOLEAN_KEYS = listOf(
            booleanPreferencesKey("display_auto_reduce"),
            booleanPreferencesKey("display_show_boot"),
            booleanPreferencesKey("display_boot_on_resume"),
            booleanPreferencesKey("display_thermal_aware"),
            booleanPreferencesKey("display_battery_saver"),
            booleanPreferencesKey("library_setup_complete"),
            booleanPreferencesKey("db_seeded_v1"),
        )
    }
}
