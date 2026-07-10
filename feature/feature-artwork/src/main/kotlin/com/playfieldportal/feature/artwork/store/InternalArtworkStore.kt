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

    // ── Internals ─────────────────────────────────────────────────────────────

    private suspend fun downloadToTemp(url: String, kind: ArtworkKind): File? = runCatching {
        val response = httpClient.get(url)
        if (!response.status.isSuccess()) {
            Timber.w("Artwork download failed (${response.status.value}) for $url")
            return null
        }
        response.bodyAsChannel().toInputStream().use { copyToTemp(it, kind) }
    }.onFailure { Timber.w(it, "Artwork download error for $url") }.getOrNull()

    /** Streams [input] into a cache temp file; null if empty or the wrong payload type for [kind]. */
    private fun copyToTemp(input: InputStream, kind: ArtworkKind): File? {
        val tmp = File.createTempFile("artwork_", ".part", context.cacheDir)
        val ok = runCatching {
            tmp.outputStream().use { input.copyTo(it) }
            tmp.length() > 0 && PayloadCheck.accepts(kind, headerOf(tmp))
        }.getOrDefault(false)
        if (!ok) {
            Timber.w("Artwork payload rejected — empty or wrong type for $kind")
            tmp.delete()
            return null
        }
        return tmp
    }

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

    private fun headerOf(file: File): ByteArray = runCatching {
        val header = ByteArray(12)
        val read = file.inputStream().use { it.read(header) }
        if (read <= 0) ByteArray(0) else header.copyOf(read)
    }.getOrDefault(ByteArray(0))

    private fun sniffExt(file: File): String? = ImageFormat.sniff(headerOf(file))?.ext

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
