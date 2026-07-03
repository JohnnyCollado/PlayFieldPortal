package com.playfieldportal.feature.backup

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.playfieldportal.core.data.database.dao.BackupDao
import com.playfieldportal.core.data.database.dao.CategoryDao
import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.database.dao.PlaySessionDao
import com.playfieldportal.core.data.database.entity.AppOverrideEntity
import com.playfieldportal.core.data.database.entity.CategoryEntity
import com.playfieldportal.core.data.database.entity.CategoryItemEntity
import com.playfieldportal.core.data.database.entity.CollectionEntity
import com.playfieldportal.core.data.database.entity.CollectionGameEntity
import com.playfieldportal.core.data.database.entity.GameEntity
import com.playfieldportal.core.data.database.entity.HiddenPlacementEntity
import com.playfieldportal.core.data.database.entity.MemoryCardEntity
import com.playfieldportal.core.data.database.entity.MusicFolderEntity
import com.playfieldportal.core.data.database.entity.MusicTrackEntity
import com.playfieldportal.core.data.database.entity.PhotoEntity
import com.playfieldportal.core.data.database.entity.PhotoLibraryEntity
import com.playfieldportal.core.data.database.entity.PlatformEntity
import com.playfieldportal.core.data.database.entity.PlaylistEntity
import com.playfieldportal.core.data.database.entity.PlaylistTrackEntity
import com.playfieldportal.core.data.database.entity.PlaySessionEntity
import com.playfieldportal.core.data.database.entity.ThemeEntity
import com.playfieldportal.core.data.database.entity.VideoEntity
import com.playfieldportal.core.data.database.entity.VideoLibraryEntity
import com.playfieldportal.core.data.database.entity.VideoPlaylistEntity
import com.playfieldportal.core.data.database.entity.VideoPlaylistItemEntity
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
    private val backupDao: BackupDao,
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

            // v2 tables
            zip.writeJson(BackupEntry.PLATFORMS,            json.encodeToString(listSerializer<PlatformEntity>(),          backupDao.getPlatforms()))
            zip.writeJson(BackupEntry.MEMORY_CARDS,         json.encodeToString(listSerializer<MemoryCardEntity>(),        backupDao.getMemoryCards()))
            zip.writeJson(BackupEntry.APP_OVERRIDES,        json.encodeToString(listSerializer<AppOverrideEntity>(),       backupDao.getAppOverrides()))
            zip.writeJson(BackupEntry.COLLECTIONS,          json.encodeToString(listSerializer<CollectionEntity>(),        backupDao.getCollections()))
            zip.writeJson(BackupEntry.COLLECTION_GAMES,     json.encodeToString(listSerializer<CollectionGameEntity>(),    backupDao.getCollectionGames()))
            zip.writeJson(BackupEntry.THEMES,               json.encodeToString(listSerializer<ThemeEntity>(),             backupDao.getThemes()))
            zip.writeJson(BackupEntry.HIDDEN_PLACEMENTS,    json.encodeToString(listSerializer<HiddenPlacementEntity>(),   backupDao.getHiddenPlacements()))
            zip.writeJson(BackupEntry.MUSIC_FOLDERS,        json.encodeToString(listSerializer<MusicFolderEntity>(),       backupDao.getMusicFolders()))
            zip.writeJson(BackupEntry.MUSIC_TRACKS,         json.encodeToString(listSerializer<MusicTrackEntity>(),        backupDao.getMusicTracks()))
            zip.writeJson(BackupEntry.PLAYLISTS,            json.encodeToString(listSerializer<PlaylistEntity>(),          backupDao.getPlaylists()))
            zip.writeJson(BackupEntry.PLAYLIST_TRACKS,      json.encodeToString(listSerializer<PlaylistTrackEntity>(),     backupDao.getPlaylistTracks()))
            zip.writeJson(BackupEntry.VIDEO_LIBRARIES,      json.encodeToString(listSerializer<VideoLibraryEntity>(),      backupDao.getVideoLibraries()))
            zip.writeJson(BackupEntry.VIDEOS,               json.encodeToString(listSerializer<VideoEntity>(),             backupDao.getVideos()))
            zip.writeJson(BackupEntry.VIDEO_PLAYLISTS,      json.encodeToString(listSerializer<VideoPlaylistEntity>(),     backupDao.getVideoPlaylists()))
            zip.writeJson(BackupEntry.VIDEO_PLAYLIST_ITEMS, json.encodeToString(listSerializer<VideoPlaylistItemEntity>(), backupDao.getVideoPlaylistItems()))
            zip.writeJson(BackupEntry.PHOTO_LIBRARIES,      json.encodeToString(listSerializer<PhotoLibraryEntity>(),      backupDao.getPhotoLibraries()))
            zip.writeJson(BackupEntry.PHOTOS,               json.encodeToString(listSerializer<PhotoEntity>(),             backupDao.getPhotos()))

            // Bundled internal-storage assets. Absolute paths in the DB point into filesDir; storing
            // them relative to filesDir lets restore relocate them into whatever package/data-dir the
            // backup lands in.
            val filesDir = context.filesDir
            BUNDLED_FILE_ROOTS.forEach { root -> zip.bundleTree(filesDir, root) }
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

        // Extract JSON into memory and stage any bundled files into a temp dir. Nothing is committed
        // to the live filesDir until the manifest is validated below.
        val filesDir = context.filesDir
        val staging  = File(filesDir, RESTORE_STAGING_DIR)
        staging.deleteRecursively()

        val entries = stream.use { readBackup(it, staging) }

        val manifest = entries[BackupEntry.MANIFEST]?.let {
            json.decodeFromString(BackupManifest.serializer(), it)
        } ?: run {
            staging.deleteRecursively()
            return RestoreResult.Failure("Backup is missing manifest")
        }

        if (manifest.formatVersion > BACKUP_FORMAT_VERSION) {
            staging.deleteRecursively()
            return RestoreResult.Failure(
                "Backup format v${manifest.formatVersion} is newer than this app supports (v$BACKUP_FORMAT_VERSION)"
            )
        }

        // ── Decode all tables ───────────────────────────────────────────
        val games          = entries.decodeList<GameEntity>(BackupEntry.GAMES)
        val categories     = entries.decodeList<CategoryEntity>(BackupEntry.CATEGORIES)
        val catItems       = entries.decodeList<CategoryItemEntity>(BackupEntry.CATEGORY_ITEMS)
        val sessions       = entries.decodeList<PlaySessionEntity>(BackupEntry.PLAY_SESSIONS)
        val platforms      = entries.decodeList<PlatformEntity>(BackupEntry.PLATFORMS)
        val memoryCards    = entries.decodeList<MemoryCardEntity>(BackupEntry.MEMORY_CARDS)
        val appOverrides   = entries.decodeList<AppOverrideEntity>(BackupEntry.APP_OVERRIDES)
        val collections    = entries.decodeList<CollectionEntity>(BackupEntry.COLLECTIONS)
        val collectionGames = entries.decodeList<CollectionGameEntity>(BackupEntry.COLLECTION_GAMES)
        val themes         = entries.decodeList<ThemeEntity>(BackupEntry.THEMES)
        val hiddenPlaces   = entries.decodeList<HiddenPlacementEntity>(BackupEntry.HIDDEN_PLACEMENTS)
        val musicFolders   = entries.decodeList<MusicFolderEntity>(BackupEntry.MUSIC_FOLDERS)
        val musicTracks    = entries.decodeList<MusicTrackEntity>(BackupEntry.MUSIC_TRACKS)
        val playlists      = entries.decodeList<PlaylistEntity>(BackupEntry.PLAYLISTS)
        val playlistTracks = entries.decodeList<PlaylistTrackEntity>(BackupEntry.PLAYLIST_TRACKS)
        val videoLibraries = entries.decodeList<VideoLibraryEntity>(BackupEntry.VIDEO_LIBRARIES)
        val videos         = entries.decodeList<VideoEntity>(BackupEntry.VIDEOS)
        val videoPlaylists = entries.decodeList<VideoPlaylistEntity>(BackupEntry.VIDEO_PLAYLISTS)
        val videoPlItems   = entries.decodeList<VideoPlaylistItemEntity>(BackupEntry.VIDEO_PLAYLIST_ITEMS)
        val photoLibraries = entries.decodeList<PhotoLibraryEntity>(BackupEntry.PHOTO_LIBRARIES)
        val photos         = entries.decodeList<PhotoEntity>(BackupEntry.PHOTOS)

        val settings = entries[BackupEntry.SETTINGS]?.let {
            json.decodeFromString(SettingsSnapshot.serializer(), it)
        }

        // ── Commit bundled files (only when the backup actually carried some) ─
        val filesDirPath = filesDir.absolutePath
        commitStagedFiles(staging, filesDir)

        // ── Rewrite internal-storage paths onto THIS package's filesDir ──
        val remappedGames = games.map { g ->
            g.copy(
                artworkUri = rewriteFilesPath(g.artworkUri, filesDirPath),
                heroUri    = rewriteFilesPath(g.heroUri, filesDirPath),
                logoUri    = rewriteFilesPath(g.logoUri, filesDirPath),
                iconUri    = rewriteFilesPath(g.iconUri, filesDirPath),
            )
        }

        // ── Apply to the database ────────────────────────────────────────
        // Games / sessions: full replace.
        gameDao.deleteAll()
        playSessionDao.deleteAll()

        // Child rows first so parents can be re-inserted cleanly.
        backupDao.clearCollectionGames()
        backupDao.clearCollections()
        backupDao.clearPlaylistTracks()
        backupDao.clearPlaylists()
        backupDao.clearMusicTracks()
        backupDao.clearMusicFolders()
        backupDao.clearVideoPlaylistItems()
        backupDao.clearVideoPlaylists()
        backupDao.clearVideos()
        backupDao.clearVideoLibraries()
        backupDao.clearPhotos()
        backupDao.clearPhotoLibraries()
        backupDao.clearMemoryCards()
        backupDao.clearAppOverrides()
        backupDao.clearHiddenPlacements()
        backupDao.clearCategoryItems()

        if (remappedGames.isNotEmpty()) gameDao.insertAllReplace(remappedGames)
        if (sessions.isNotEmpty())      playSessionDao.insertAll(sessions)

        // Categories: upsert (REPLACE) so backed-up name/position/visibility overwrite the seeded
        // built-ins instead of being ignored; items were wiped above and are re-added fresh.
        categories.forEach { categoryDao.upsert(it) }
        catItems.forEach   { categoryDao.addItem(it) }

        backupDao.insertMemoryCards(memoryCards)
        backupDao.insertAppOverrides(appOverrides)
        backupDao.insertCollections(collections)
        backupDao.insertCollectionGames(collectionGames)
        backupDao.insertHiddenPlacements(hiddenPlaces)
        backupDao.insertMusicFolders(musicFolders)
        backupDao.insertMusicTracks(musicTracks)
        backupDao.insertPlaylists(playlists)
        backupDao.insertPlaylistTracks(playlistTracks)
        backupDao.insertVideoLibraries(videoLibraries)
        backupDao.insertVideos(videos)
        backupDao.insertVideoPlaylists(videoPlaylists)
        backupDao.insertVideoPlaylistItems(videoPlItems)
        backupDao.insertPhotoLibraries(photoLibraries)
        backupDao.insertPhotos(photos)

        // Platforms: merge only the user-editable columns onto the existing seeded catalog so an
        // older backup can never wipe platform definitions this build added.
        platforms.forEach { p ->
            backupDao.restorePlatformPrefs(p.id, p.preferredEmulatorPackage, p.isPinnedToBar, p.barPosition)
        }

        // Themes: upsert user + built-in rows, then re-assert the single active one.
        backupDao.insertThemes(themes)
        themes.firstOrNull { it.isActive }?.let { backupDao.setActiveTheme(it.id) }

        // Settings last, with the wallpaper path remapped onto this filesDir.
        if (settings != null) restoreSettingsSnapshot(settings.remapWallpaper(filesDirPath))

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

    // Reads the ZIP once: JSON entries are returned as a name→text map; entries under
    // BACKUP_FILES_PREFIX are streamed into [staging], preserving their relative path.
    private fun readBackup(stream: InputStream, staging: File): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val stagingCanonical = staging.canonicalPath
        ZipInputStream(stream.buffered()).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory) {
                    if (name.startsWith(BACKUP_FILES_PREFIX)) {
                        val dest = File(staging, name.removePrefix(BACKUP_FILES_PREFIX))
                        // Zip-slip guard: never let an entry escape the staging root.
                        if (dest.canonicalPath.startsWith(stagingCanonical + File.separator)) {
                            dest.parentFile?.mkdirs()
                            dest.outputStream().use { out -> zip.copyTo(out) }
                        }
                    } else {
                        map[name] = zip.readBytes().toString(Charsets.UTF_8)
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return map
    }

    // Moves staged files into the live filesDir. When the backup carried bundled assets we first
    // clear the managed roots so the restore is a true replace, not a merge.
    private fun commitStagedFiles(staging: File, filesDir: File) {
        val staged = staging.takeIf { it.exists() }?.walkTopDown()?.filter { it.isFile }?.toList().orEmpty()
        if (staged.isEmpty()) {
            staging.deleteRecursively()
            return
        }
        BUNDLED_FILE_ROOTS.forEach { root -> File(filesDir, root).deleteRecursively() }
        staged.forEach { src ->
            val rel  = src.relativeTo(staging).invariantSeparatorsPath
            val dest = File(filesDir, rel)
            dest.parentFile?.mkdirs()
            src.copyTo(dest, overwrite = true)
        }
        staging.deleteRecursively()
    }

    private fun ZipOutputStream.bundleTree(filesDir: File, root: String) {
        val dir = File(filesDir, root)
        if (!dir.exists()) return
        dir.walkTopDown().filter { it.isFile }.forEach { f ->
            val entryName = BACKUP_FILES_PREFIX + f.relativeTo(filesDir).invariantSeparatorsPath
            putNextEntry(ZipEntry(entryName))
            f.inputStream().use { it.copyTo(this) }
            closeEntry()
        }
    }

    private fun ZipOutputStream.writeJson(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private inline fun <reified T> Map<String, String>.decodeList(name: String): List<T> =
        this[name]?.let { json.decodeFromString(listSerializer<T>(), it) } ?: emptyList()

    // Repoints a "…/files/<rel>" path onto this package's filesDir. Non-filesDir paths (SAF content
    // URIs, shared-storage ROM/theme paths) are returned unchanged.
    private fun rewriteFilesPath(path: String?, filesDirPath: String): String? {
        if (path.isNullOrEmpty()) return path
        val idx = path.indexOf(FILES_MARKER)
        if (idx < 0) return path
        return filesDirPath.trimEnd('/') + "/" + path.substring(idx + FILES_MARKER.length)
    }

    private fun SettingsSnapshot.remapWallpaper(filesDirPath: String): SettingsSnapshot {
        val current = entries[KEY_CUSTOM_WALLPAPER] ?: return this
        val remapped = rewriteFilesPath(current, filesDirPath) ?: return this
        if (remapped == current) return this
        return copy(entries = entries + (KEY_CUSTOM_WALLPAPER to remapped))
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
        private const val RESTORE_STAGING_DIR = ".pfp_restore_tmp"
        private const val FILES_MARKER = "/files/"
        private const val KEY_CUSTOM_WALLPAPER = "display_custom_wallpaper"

        // filesDir sub-trees bundled into the backup and replaced wholesale on restore.
        private val BUNDLED_FILE_ROOTS = listOf(
            "artwork",            // game hero/logo/icon/box art
            "wallpaper",          // custom XMB wallpaper
            "emulator_profiles",  // user-defined / user-modified emulator profiles
        )

        private val BACKED_UP_STRING_KEYS = listOf(
            // Display
            stringPreferencesKey("display_wave_mode"),
            stringPreferencesKey("display_wave_style"),
            stringPreferencesKey("display_icon_style"),
            stringPreferencesKey("display_color_scheme"),
            stringPreferencesKey("display_custom_wallpaper"),
            // Controller
            stringPreferencesKey("controller_mappings_v1"),
            stringPreferencesKey("controller_confirm_back_layout"),
            stringPreferencesKey("controller_xy_layout"),
            stringPreferencesKey("controller_display_type"),
            // Interface / touch
            stringPreferencesKey("interface_touch_nav_button"),
            stringPreferencesKey("interface_touch_sensitivity"),
            // Default players
            stringPreferencesKey("music_default_player_package"),
            stringPreferencesKey("video_default_player"),
            // Library
            stringPreferencesKey("library_root_path"),
            // Scraper credentials
            stringPreferencesKey("sgdb_api_key"),
            stringPreferencesKey("igdb_client_id"),
            stringPreferencesKey("igdb_client_secret"),
            stringPreferencesKey("tgdb_api_key"),
        )

        private val BACKED_UP_BOOLEAN_KEYS = listOf(
            // Display
            booleanPreferencesKey("display_auto_reduce"),
            booleanPreferencesKey("display_show_boot"),
            booleanPreferencesKey("display_boot_on_resume"),
            booleanPreferencesKey("display_thermal_aware"),
            booleanPreferencesKey("display_battery_saver"),
            // Sound
            booleanPreferencesKey("sound_menu_enabled"),
            // Artwork download preferences
            booleanPreferencesKey("pref_dl_clear_logos"),
            booleanPreferencesKey("pref_dl_heroes"),
            booleanPreferencesKey("pref_sgdb_heroes"),
            // Library
            booleanPreferencesKey("library_setup_complete"),
            // Seed flag — kept so a restore over a fresh install doesn't re-seed on top of the
            // restored data.
            booleanPreferencesKey("db_seeded_v1"),
        )
    }
}
