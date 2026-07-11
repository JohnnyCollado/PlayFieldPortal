package com.playfieldportal.feature.artwork.store

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ArtworkStore] backed by internal app storage at `{filesDir}/artwork/{gameId}/` — the exact
 * layout pre-seam builds used, so existing installs keep every file.
 *
 * Write discipline: bytes land in a cache temp file first, are sniffed to confirm they're really
 * an image, and only then move under the final name — a partial or bogus download is never
 * visible at a real artwork path.
 */
@Singleton
class InternalArtworkStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: HttpClient,
) : ArtworkStore {

    private val root: File
        get() = File(context.filesDir, "artwork")

    // ── Saves ─────────────────────────────────────────────────────────────────

    override suspend fun saveFromUrl(gameId: Long, kind: ArtworkKind, url: String): String? =
        withContext(Dispatchers.IO) {
            val tmp = downloadToTemp(url, kind) ?: return@withContext null
            commit(tmp, gameId, ArtworkFileNaming.fixedName(kind))
        }

    override suspend fun saveVersionedFromUrl(gameId: Long, kind: ArtworkKind, url: String): String? =
        withContext(Dispatchers.IO) {
            val tmp = downloadToTemp(url, kind) ?: return@withContext null
            val ext = sniffExt(tmp) ?: "jpg"
            commit(tmp, gameId, ArtworkFileNaming.versionedName(kind, ext))
                ?.also { prune(gameId, kind, keepPath = it) }
        }

    override suspend fun saveVersionedFromUri(gameId: Long, kind: ArtworkKind, uri: Uri): String? =
        withContext(Dispatchers.IO) {
            val ext = extensionForUri(uri) ?: run {
                Timber.w("Artwork pick rejected — unsupported type for $uri")
                return@withContext null
            }
            val tmp = runCatching {
                val stream = context.contentResolver.openInputStream(uri) ?: return@runCatching null
                stream.use { copyToTemp(it, kind) }
            }.onFailure { Timber.e(it, "Failed to read picked artwork $uri") }.getOrNull()
                ?: return@withContext null
            commit(tmp, gameId, ArtworkFileNaming.versionedName(kind, ext))
                ?.also { prune(gameId, kind, keepPath = it) }
        }

    override suspend fun saveFromFile(gameId: Long, kind: ArtworkKind, tempFile: java.io.File): String? =
        withContext(Dispatchers.IO) {
            if (!PayloadCheck.accepts(kind, ArtworkTempIO.headerOf(tempFile))) {
                Timber.w("Local save rejected — wrong payload for ${kind.name}")
                tempFile.delete()
                return@withContext null
            }
            commit(tempFile, gameId, ArtworkFileNaming.fixedName(kind))
        }

    // ── Validation / deletion ─────────────────────────────────────────────────

    override fun isValidRef(ref: String?): Boolean {
        if (ref.isNullOrBlank()) return false
        if (ref.startsWith("http", ignoreCase = true)) return true
        // Portable-library references. A ref is valid when the document still opens under our
        // persisted grant — File() checks would misjudge every content:// ref as stale, which
        // made scrape-missing wipe imported artwork.
        if (ref.startsWith("content://", ignoreCase = true)) {
            return runCatching {
                context.contentResolver.openAssetFileDescriptor(Uri.parse(ref), "r")
                    ?.use { it.length != 0L } ?: false
            }.getOrDefault(false)
        }
        return runCatching { File(ref).let { it.exists() && it.length() > 0 } }.getOrDefault(false)
    }

    override suspend fun find(gameId: Long, kind: ArtworkKind): String? = withContext(Dispatchers.IO) {
        File(File(root, gameId.toString()), ArtworkFileNaming.fixedName(kind))
            .takeIf { it.exists() && it.length() > 0 }?.absolutePath
    }

    override suspend fun deleteAll() {
        withContext(Dispatchers.IO) {
            runCatching { root.deleteRecursively() }
                .onFailure { Timber.e(it, "Failed to delete artwork root") }
        }
    }

    // ── Legacy migration (M-F2) ───────────────────────────────────────────────

    data class LegacyAsset(
        val gameId: Long,
        val kind: ArtworkKind,
        val file: File,
        val userPick: Boolean,   // versioned files came from a user pick → migrate as locked
        val sizeBytes: Long,
    )

    /**
     * Every internal asset the migration worker should move to the portable library — at most
     * one file per (game, kind): the newest versioned user pick wins over the scraper's fixed
     * file (that mirrors what the game columns reference today).
     */
    suspend fun enumerateForMigration(): List<LegacyAsset> = withContext(Dispatchers.IO) {
        val out = mutableListOf<LegacyAsset>()
        root.listFiles()?.forEach { dir ->
            val gameId = dir.name.toLongOrNull() ?: return@forEach
            if (!dir.isDirectory) return@forEach
            val files = dir.listFiles()?.filter { it.isFile && it.length() > 0 } ?: return@forEach
            for (kind in ArtworkKind.entries) {
                val prefix = "${kind.name.lowercase(Locale.US)}_"
                val versioned = files.filter { it.name.startsWith(prefix) }.maxByOrNull { it.name }
                val chosen = versioned ?: files.firstOrNull { it.name == ArtworkFileNaming.fixedName(kind) } ?: continue
                out += LegacyAsset(gameId, kind, chosen, userPick = versioned != null, sizeBytes = chosen.length())
            }
        }
        out
    }

    /** Deletes every internal file of [kind] for the game (fixed + versioned), then the empty dir. */
    suspend fun deleteKind(gameId: Long, kind: ArtworkKind) = withContext(Dispatchers.IO) {
        val dir = File(root, gameId.toString())
        dir.listFiles()?.forEach { f ->
            if (ArtworkFileNaming.isPruneCandidate(kind, f.name)) f.delete()
        }
        if (dir.listFiles()?.isEmpty() == true) dir.delete()
    }

    /** (files, bytes) currently stored internally — drives the migration offer in settings. */
    suspend fun footprint(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        var count = 0
        var bytes = 0L
        root.listFiles()?.forEach { dir ->
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.length() > 0) { count++; bytes += f.length() }
            }
        }
        count to bytes
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private suspend fun downloadToTemp(url: String, kind: ArtworkKind): File? =
        ArtworkTempIO.downloadToTemp(httpClient, context.cacheDir, kind, url)

    private fun copyToTemp(input: InputStream, kind: ArtworkKind): File? =
        ArtworkTempIO.copyToTemp(input, context.cacheDir, kind)

    /** Moves a verified temp file to `artwork/{gameId}/{name}`, returning the absolute path. */
    private fun commit(tmp: File, gameId: Long, name: String): String? = runCatching {
        val dir = File(root, gameId.toString()).also { it.mkdirs() }
        val dest = File(dir, name)
        if (!tmp.renameTo(dest)) {           // cross-volume fallback (both live in /data, so rare)
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        dest.absolutePath.takeIf { dest.length() > 0 }
    }.onFailure {
        Timber.e(it, "Artwork commit failed for game $gameId / $name")
        tmp.delete()
    }.getOrNull()

    /** Deletes older files of [kind] for this game, keeping only [keepPath]. */
    private fun prune(gameId: Long, kind: ArtworkKind, keepPath: String) {
        runCatching {
            File(root, gameId.toString()).listFiles()?.forEach { f ->
                if (ArtworkFileNaming.isPruneCandidate(kind, f.name) && f.absolutePath != keepPath) {
                    f.delete()
                }
            }
        }.onFailure { Timber.w(it, "Failed to prune old artwork for game $gameId $kind") }
    }

    private fun sniffExt(file: File): String? = ImageFormat.sniff(ArtworkTempIO.headerOf(file))?.ext

    private fun extensionForUri(uri: Uri): String? {
        val mime = context.contentResolver.getType(uri)?.lowercase(Locale.US)
        return when (mime) {
            "image/png"               -> "png"
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp"              -> "webp"
            else -> {
                val path = uri.lastPathSegment.orEmpty().lowercase(Locale.US)
                when {
                    path.endsWith(".png")                           -> "png"
                    path.endsWith(".jpg") || path.endsWith(".jpeg") -> "jpg"
                    path.endsWith(".webp")                          -> "webp"
                    else                                            -> null
                }
            }
        }
    }
}
