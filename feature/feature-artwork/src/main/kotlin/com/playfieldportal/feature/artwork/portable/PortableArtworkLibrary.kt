package com.playfieldportal.feature.artwork.portable

import android.content.Context
import android.net.Uri
import android.os.FileUtils
import android.provider.DocumentsContract
import com.playfieldportal.core.data.saf.SafChild
import com.playfieldportal.core.data.saf.querySafChildren
import com.playfieldportal.feature.artwork.store.ArtworkFileNaming
import com.playfieldportal.feature.artwork.store.ArtworkKind
import com.playfieldportal.feature.artwork.store.ImageFormat
import com.playfieldportal.feature.artwork.store.PayloadCheck
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The SAF layer of the user-owned artwork library — all reads/writes of the picked tree go
 * through here, always via `DocumentsContract` against document ids resolved *inside* the
 * granted tree (nothing outside the user's grant is ever reachable), never raw paths.
 *
 * Layout under the user-picked root:
 *   pfp-artwork-library.json                 ← manifest
 *   games/{platformId}/{slug}/{kind}.{ext}   ← one folder per entry + metadata.json
 *   import/{Launcher}/…                      ← user-managed drop zone (read, never indexed)
 *
 * Performance discipline (large imports): directory document-ids are cached so a path is
 * resolved at most once per run; existence checks ride one child-listing cursor per directory;
 * byte copies use [FileUtils.copy] (in-kernel); same-tree moves use `moveDocument` (metadata
 * only, no bytes) with a copy+delete fallback for providers that refuse it.
 *
 * Write discipline (security + integrity): every incoming file's first bytes are sniffed and
 * must be a real image for the kind before anything lands under a final name; destination
 * names are fixed per-kind names (never attacker-controlled); a failed write deletes its
 * partial destination.
 */
@Singleton
class PortableArtworkLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val resolver get() = context.contentResolver

    // path-under-root ("games/psx/slug") → directory document id, per tree. Rebuilt per process.
    private val dirCache = ConcurrentHashMap<String, String>()

    // Serializes find-or-create of directories. Without it, two games imported concurrently on
    // the same platform both miss the cache, both createDocument("gba"), and SAF silently
    // auto-suffixes the loser to "gba (1)" — a duplicate platform folder.
    private val dirCreateLock = Any()

    data class SavedAsset(val kind: ArtworkKind, val uriString: String, val fileName: String, val sizeBytes: Long)

    enum class Transfer { COPY, MOVE }

    // ── Manifest ──────────────────────────────────────────────────────────────

    suspend fun readManifest(treeUri: Uri): ArtworkLibraryManifest? = withContext(Dispatchers.IO) {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val manifest = findChild(treeUri, rootDocId, ArtworkLibraryManifest.FILE_NAME) ?: return@withContext null
        readTextCapped(manifest.uri, ArtworkLibraryManifest.MAX_BYTES)?.let { ArtworkLibraryManifest.parse(it) }
    }

    /**
     * Reads the manifest, creating it (plus `games/` and `import/`) when the folder isn't a
     * library yet. Returns null only when the tree is unwritable (dead grant, read-only provider).
     */
    suspend fun ensureLibrary(treeUri: Uri, appVersion: String): ArtworkLibraryManifest? = withContext(Dispatchers.IO) {
        readManifest(treeUri)?.let { return@withContext it }
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        // Layout v2 creates only the drop zone up front; platform/media dirs appear on demand.
        ensureDir(treeUri, rootDocId, ArtworkLibraryManifest.DIR_IMPORT, ArtworkLibraryManifest.DIR_IMPORT)
            ?: return@withContext null
        val manifest = ArtworkLibraryManifest(
            libraryUuid = UUID.randomUUID().toString(),
            createdAt = System.currentTimeMillis(),
            appVersion = appVersion,
        )
        val ok = writeManifest(treeUri, manifest)
        if (ok) manifest else null
    }

    /** Rewrites the root manifest (once per operation — never per file). */
    suspend fun writeManifest(treeUri: Uri, manifest: ArtworkLibraryManifest): Boolean =
        withContext(Dispatchers.IO) {
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            writeText(
                treeUri, rootDocId, ArtworkLibraryManifest.FILE_NAME, "application/json",
                ArtworkLibraryManifest.encode(manifest),
            )
        }

    // ── Import drop zone ──────────────────────────────────────────────────────

    /** The children of `import/` — each directory is a candidate import source. */
    suspend fun listImportSources(treeUri: Uri): List<SafChild> = withContext(Dispatchers.IO) {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val importDir = findChild(treeUri, rootDocId, ArtworkLibraryManifest.DIR_IMPORT)
            ?: return@withContext emptyList()
        resolver.querySafChildren(treeUri, importDir.documentId).filter { it.isDirectory }
    }

    fun listChildren(treeUri: Uri, dirDocId: String): List<SafChild> =
        resolver.querySafChildren(treeUri, dirDocId)

    // ── Layout v2 writes: {platform}/{mediaDir}/{PortableName}.{ext} ─────────

    /** Resolves (creating as needed) `{platformId}/{mediaDir}` for [kind], cached per run. */
    suspend fun mediaDirDocId(treeUri: Uri, platformId: String, kind: ArtworkKind): String? =
        withContext(Dispatchers.IO) {
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val platformDirId = ensureDir(treeUri, rootDocId, platformId, platformId)
                ?: return@withContext null
            val mediaDir = ArtworkPathResolver.mediaDirFor(kind)
            ensureDir(treeUri, platformDirId, mediaDir, "$platformId/$mediaDir")
        }

    /**
     * Brings [source] (a file inside this same tree's import zone) into
     * `{platformId}/{mediaDir}/` as `{portableName}.{ext}`. Validates the payload header first;
     * rejects wrong types. A same-stem file already in the directory is pre-deleted so
     * create/rename can never silently suffix the name ("Game (1).png" would be invisible).
     */
    suspend fun saveAsset(
        treeUri: Uri,
        platformId: String,
        kind: ArtworkKind,
        portableName: String,
        source: SafChild,
        transfer: Transfer,
        existingNames: Map<String, SafChild>,
    ): SavedAsset? = withContext(Dispatchers.IO) {
        val header = readHeader(source.uri) ?: return@withContext null
        if (!PayloadCheck.accepts(kind, header)) {
            Timber.w("Import payload rejected — not a valid ${kind.name} file: ${source.name}")
            return@withContext null
        }
        val ext = if (kind == ArtworkKind.MANUAL) "pdf"    // header already verified as %PDF above
        else ImageFormat.sniff(header)?.ext ?: return@withContext null
        val destName = "$portableName.$ext"

        val dirDocId = mediaDirDocId(treeUri, platformId, kind) ?: return@withContext null
        existingNames.values
            .filter { !it.isDirectory && it.name.substringBeforeLast('.').equals(portableName, ignoreCase = true) }
            .forEach { runCatching { DocumentsContract.deleteDocument(resolver, it.uri) } }

        val dirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, dirDocId)
        val movedOrCopied: Uri? = when (transfer) {
            Transfer.MOVE -> moveInto(treeUri, source, dirUri, destName)
            Transfer.COPY -> copyInto(source, dirUri, destName, ext)
        }
        movedOrCopied?.let { SavedAsset(kind, it.toString(), destName, source.sizeBytes ?: 0L) }
    }

    /**
     * Writes a validated local temp file (scraper download, user pick copied to cache) into
     * `{platformId}/{mediaDir}/` as `{portableName}.{ext}`. Same discipline as [saveAsset]:
     * header sniffed, same-stem predecessors pre-deleted, kernel copy, failed writes cleaned up.
     * The temp file is always deleted.
     */
    suspend fun saveFromFile(
        treeUri: Uri,
        platformId: String,
        kind: ArtworkKind,
        portableName: String,
        tempFile: java.io.File,
    ): SavedAsset? = withContext(Dispatchers.IO) {
        try {
            val header = runCatching {
                tempFile.inputStream().use { s -> ByteArray(12).let { it.copyOf(s.read(it).coerceAtLeast(0)) } }
            }.getOrDefault(ByteArray(0))
            if (!PayloadCheck.accepts(kind, header)) {
                Timber.w("Portable save rejected — wrong payload for ${kind.name}")
                return@withContext null
            }
            val ext = if (kind == ArtworkKind.MANUAL) "pdf" else ImageFormat.sniff(header)?.ext ?: return@withContext null
            val destName = "$portableName.$ext"
            val dirDocId = mediaDirDocId(treeUri, platformId, kind) ?: return@withContext null

            listChildren(treeUri, dirDocId)
                .filter { !it.isDirectory && it.name.substringBeforeLast('.').equals(portableName, ignoreCase = true) }
                .forEach { runCatching { DocumentsContract.deleteDocument(resolver, it.uri) } }

            val dirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, dirDocId)
            val mime = when (ext) { "png" -> "image/png"; "webp" -> "image/webp"; "pdf" -> "application/pdf"; else -> "image/jpeg" }
            val dest = runCatching { DocumentsContract.createDocument(resolver, dirUri, mime, destName) }.getOrNull()
                ?: return@withContext null
            val ok = runCatching {
                tempFile.inputStream().use { input ->
                    resolver.openFileDescriptor(dest, "w")?.use { output ->
                        FileUtils.copy(input.fd, output.fileDescriptor)
                        true
                    }
                } ?: false
            }.onFailure { Timber.w(it, "Portable save failed for $destName") }.getOrDefault(false)
            if (!ok) {
                runCatching { DocumentsContract.deleteDocument(resolver, dest) }
                return@withContext null
            }
            SavedAsset(kind, dest.toString(), destName, tempFile.length())
        } finally {
            tempFile.delete()
        }
    }

    fun clearDirCache() = dirCache.clear()

    /**
     * Resolves (creating as needed) a directory path under ANY granted tree — used by the
     * exporter to build `{esDeName}/{mediaDir}/` in the user-picked destination. Same cached,
     * serialized find-or-create as library writes.
     */
    suspend fun ensureDirPath(treeUri: Uri, segments: List<String>): String? = withContext(Dispatchers.IO) {
        var parent = DocumentsContract.getTreeDocumentId(treeUri)
        var path = ""
        for (segment in segments) {
            path = if (path.isEmpty()) segment else "$path/$segment"
            parent = ensureDir(treeUri, parent, segment, path) ?: return@withContext null
        }
        parent
    }

    /** Kernel copy of one document into [destDirDocId] under [destName]; skips nothing itself. */
    suspend fun copyDocument(
        sourceUri: Uri,
        destTreeUri: Uri,
        destDirDocId: String,
        destName: String,
        mime: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val destDirUri = DocumentsContract.buildDocumentUriUsingTree(destTreeUri, destDirDocId)
        val dest = runCatching { DocumentsContract.createDocument(resolver, destDirUri, mime, destName) }
            .getOrNull() ?: return@withContext false
        val ok = runCatching {
            resolver.openFileDescriptor(sourceUri, "r")?.use { input ->
                resolver.openFileDescriptor(dest, "w")?.use { output ->
                    FileUtils.copy(input.fileDescriptor, output.fileDescriptor)
                    true
                }
            } ?: false
        }.onFailure { Timber.w(it, "Export copy failed for $destName") }.getOrDefault(false)
        if (!ok) runCatching { DocumentsContract.deleteDocument(resolver, dest) }
        ok
    }

    // ── v1 → v2 migration ─────────────────────────────────────────────────────

    data class MigratedAsset(
        val key: String,             // v1 artwork key from the entry's metadata.json
        val platformId: String,
        val kind: ArtworkKind,
        val portableName: String,
        val fileName: String,
        val uriString: String,
        val sizeBytes: Long,
    )

    data class MigrationResult(val assets: List<MigratedAsset>, val entriesSkipped: Int)

    /**
     * Relocates a v1 library (games/{platform}/{slug}/{kind}.{ext} + metadata.json) into the
     * v2 layout. Same-tree moves — no bytes copied. Each entry's metadata.json supplies the
     * platform and ROM filename (so entries under misnamed folders like "gba (1)" migrate
     * correctly), then the sidecar and emptied directories are removed. Idempotent: a re-run
     * finds no games/ folder and returns empty.
     */
    suspend fun migrateV1Library(treeUri: Uri): MigrationResult = withContext(Dispatchers.IO) {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val gamesDir = findChild(treeUri, rootDocId, ArtworkLibraryManifest.DIR_GAMES)
            ?.takeIf { it.isDirectory }
            ?: return@withContext MigrationResult(emptyList(), 0)

        val out = mutableListOf<MigratedAsset>()
        var skipped = 0
        for (platformDir in listChildren(treeUri, gamesDir.documentId).filter { it.isDirectory }) {
            for (entryDir in listChildren(treeUri, platformDir.documentId).filter { it.isDirectory }) {
                val meta = readV1EntryMetadata(treeUri, entryDir.documentId)
                if (meta == null) { skipped++; continue }
                val portableName = meta.romFileName?.let { PortableNameResolver.fromRomFileName(it) }
                    ?: PortableNameResolver.fromTitle(meta.title.ifBlank { entryDir.name })

                var movedAll = true
                for (child in listChildren(treeUri, entryDir.documentId)) {
                    if (child.isDirectory) { movedAll = false; continue }
                    if (child.name.equals(ArtworkEntryMetadata.FILE_NAME, ignoreCase = true)) continue
                    val base = child.name.substringBeforeLast('.').lowercase(Locale.US)
                    val kind = ArtworkKind.entries.firstOrNull {
                        ArtworkFileNaming.baseName(it).lowercase(Locale.US) == base
                    }
                    if (kind == null) { movedAll = false; continue }   // foreign file — leave it
                    val ext = child.name.substringAfterLast('.', "").ifBlank { "jpg" }
                    val destDirId = mediaDirDocId(treeUri, meta.platformId, kind)
                    if (destDirId == null) { movedAll = false; continue }
                    val destDirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, destDirId)
                    val moved = moveInto(treeUri, child, destDirUri, "$portableName.$ext")
                    if (moved == null) { movedAll = false; continue }
                    out += MigratedAsset(
                        key = meta.key,
                        platformId = meta.platformId,
                        kind = kind,
                        portableName = portableName,
                        fileName = "$portableName.$ext",
                        uriString = moved.toString(),
                        sizeBytes = child.sizeBytes ?: 0L,
                    )
                }
                if (movedAll) {
                    findChild(treeUri, entryDir.documentId, ArtworkEntryMetadata.FILE_NAME)?.let {
                        runCatching { DocumentsContract.deleteDocument(resolver, it.uri) }
                    }
                    deleteIfEmpty(treeUri, entryDir)
                }
            }
            deleteIfEmpty(treeUri, platformDir)
        }
        deleteIfEmpty(treeUri, gamesDir)
        Timber.i("v1→v2 migration: ${out.size} assets moved, $skipped entries skipped")
        MigrationResult(out, skipped)
    }

    private fun readV1EntryMetadata(treeUri: Uri, entryDirDocId: String): ArtworkEntryMetadata? {
        val child = findChild(treeUri, entryDirDocId, ArtworkEntryMetadata.FILE_NAME) ?: return null
        return readTextCapped(child.uri, ArtworkEntryMetadata.MAX_BYTES)?.let { ArtworkEntryMetadata.parse(it) }
    }

    // Only ever deletes a directory verified empty by a fresh listing — SAF deleteDocument is
    // recursive, so this guard is what makes cleanup safe.
    private fun deleteIfEmpty(treeUri: Uri, dir: SafChild) {
        if (listChildren(treeUri, dir.documentId).isEmpty()) {
            runCatching { DocumentsContract.deleteDocument(resolver, dir.uri) }
        }
    }

    // ── Transfer internals ────────────────────────────────────────────────────

    // Same-provider move: metadata-only on the platform ExternalStorageProvider (no bytes),
    // then a rename to the fixed kind name. Falls back to copy + delete-source.
    private fun moveInto(treeUri: Uri, source: SafChild, targetParentUri: Uri, destName: String): Uri? {
        val sourceParentUri = sourceParentUri(treeUri, source)
        if (sourceParentUri != null) {
            val moved = runCatching {
                DocumentsContract.moveDocument(resolver, source.uri, sourceParentUri, targetParentUri)
            }.getOrNull()
            if (moved != null) {
                val renamed = if (source.name.equals(destName, ignoreCase = true)) moved
                else runCatching { DocumentsContract.renameDocument(resolver, moved, destName) }.getOrNull()
                if (renamed != null) return renamed
                // Rename refused: keep the moved file rather than lose it — record its real name.
                return moved
            }
        }
        val ext = destName.substringAfterLast('.')
        val copied = copyInto(source, targetParentUri, destName, ext) ?: return null
        runCatching { DocumentsContract.deleteDocument(resolver, source.uri) }
            .onFailure { Timber.w(it, "Moved by copy but could not delete source ${source.name}") }
        return copied
    }

    private fun copyInto(source: SafChild, targetParentUri: Uri, destName: String, ext: String): Uri? {
        val mime = when (ext.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "pdf" -> "application/pdf"
            else -> "image/jpeg"
        }
        val dest = runCatching {
            DocumentsContract.createDocument(resolver, targetParentUri, mime, destName)
        }.getOrNull() ?: return null
        val ok = runCatching {
            resolver.openFileDescriptor(source.uri, "r")?.use { input ->
                resolver.openFileDescriptor(dest, "w")?.use { output ->
                    // In-kernel copy (sendfile/copy_file_range) — bytes never enter user space.
                    FileUtils.copy(input.fileDescriptor, output.fileDescriptor)
                    true
                }
            } ?: false
        }.onFailure { Timber.w(it, "Copy failed for ${source.name}") }.getOrDefault(false)
        if (!ok) {
            runCatching { DocumentsContract.deleteDocument(resolver, dest) }
            return null
        }
        return dest
    }

    // The parent document uri moveDocument requires. Derivable by string math on tree doc ids
    // ("primary:X/Y/file" → "primary:X/Y"); null for providers with opaque ids → copy fallback.
    private fun sourceParentUri(treeUri: Uri, source: SafChild): Uri? {
        val slash = source.documentId.lastIndexOf('/')
        if (slash <= 0) return null
        val parentId = source.documentId.substring(0, slash)
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
    }

    // ── Document helpers ──────────────────────────────────────────────────────

    private fun findChild(treeUri: Uri, parentDocId: String, name: String): SafChild? =
        resolver.querySafChildren(treeUri, parentDocId).firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun ensureDir(treeUri: Uri, parentDocId: String, name: String, cachePath: String): String? = synchronized(dirCreateLock) {
        val cacheKey = "$treeUri|$cachePath"
        dirCache[cacheKey]?.let { return it }
        val existing = findChild(treeUri, parentDocId, name)
        val docId = when {
            existing != null && existing.isDirectory -> existing.documentId
            existing != null -> {
                Timber.w("Artwork library: '$name' exists but is not a directory")
                return null
            }
            else -> runCatching {
                val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
                DocumentsContract.createDocument(
                    resolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, name,
                )?.let { DocumentsContract.getDocumentId(it) }
            }.onFailure { Timber.w(it, "Could not create directory '$name'") }.getOrNull()
        } ?: return null
        dirCache[cacheKey] = docId
        return docId
    }

    private fun readHeader(uri: Uri): ByteArray? = runCatching {
        resolver.openInputStream(uri)?.use { stream ->
            val header = ByteArray(12)
            val read = stream.read(header)
            if (read <= 0) null else header.copyOf(read)
        }
    }.getOrNull()

    private fun readTextCapped(uri: Uri, maxBytes: Int): String? = runCatching {
        resolver.openInputStream(uri)?.use { stream ->
            val bytes = stream.readNBytes(maxBytes + 1)
            if (bytes.size > maxBytes) {
                Timber.w("Refusing to parse oversized file at $uri")
                null
            } else bytes.toString(Charsets.UTF_8)
        }
    }.getOrNull()

    private fun writeText(treeUri: Uri, parentDocId: String, name: String, mime: String, text: String): Boolean {
        val existing = findChild(treeUri, parentDocId, name)
        val target = existing?.uri ?: runCatching {
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
            DocumentsContract.createDocument(resolver, parentUri, mime, name)
        }.getOrNull() ?: return false
        return runCatching {
            // "wt" truncates — a shorter rewrite must not leave trailing bytes of the old JSON.
            resolver.openOutputStream(target, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)); true } ?: false
        }.onFailure { Timber.w(it, "Could not write $name") }.getOrDefault(false)
    }
}
