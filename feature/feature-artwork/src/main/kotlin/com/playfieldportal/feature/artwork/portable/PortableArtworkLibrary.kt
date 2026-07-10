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
        ensureDir(treeUri, rootDocId, ArtworkLibraryManifest.DIR_GAMES, ArtworkLibraryManifest.DIR_GAMES)
            ?: return@withContext null
        ensureDir(treeUri, rootDocId, ArtworkLibraryManifest.DIR_IMPORT, ArtworkLibraryManifest.DIR_IMPORT)
        val manifest = ArtworkLibraryManifest(
            libraryUuid = UUID.randomUUID().toString(),
            createdAt = System.currentTimeMillis(),
            appVersion = appVersion,
        )
        val ok = writeText(
            treeUri, rootDocId, ArtworkLibraryManifest.FILE_NAME, "application/json",
            ArtworkLibraryManifest.encode(manifest),
        )
        if (ok) manifest else null
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

    // ── Entry writes ──────────────────────────────────────────────────────────

    /** Resolves (creating as needed) `games/{platformId}/{slug}`, cached per run. */
    suspend fun entryDirDocId(treeUri: Uri, platformId: String, slug: String): String? =
        withContext(Dispatchers.IO) {
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val gamesId = ensureDir(treeUri, rootDocId, ArtworkLibraryManifest.DIR_GAMES, "games")
                ?: return@withContext null
            val platformDirId = ensureDir(treeUri, gamesId, platformId, "games/$platformId")
                ?: return@withContext null
            ensureDir(treeUri, platformDirId, slug, "games/$platformId/$slug")
        }

    /**
     * Brings [source] (a file inside this same tree's import zone) into [entryDirDocId] under
     * [kind]'s fixed base name. Validates the payload header first; rejects non-images.
     * [existingNames] is the entry directory's current child listing (one cursor, supplied by
     * the caller) used to pre-delete a same-kind file so names never collide.
     */
    suspend fun saveIntoEntry(
        treeUri: Uri,
        entryDirDocId: String,
        kind: ArtworkKind,
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
        val base = ArtworkFileNaming.baseName(kind)
        val destName = "$base.$ext"

        // Pre-delete any existing asset of this kind (any extension) so create/rename can't
        // silently suffix the name ("icon (1).jpg" would be invisible to the resolver).
        existingNames.values
            .filter { !it.isDirectory && it.name.substringBeforeLast('.').equals(base, ignoreCase = true) }
            .forEach { runCatching { DocumentsContract.deleteDocument(resolver, it.uri) } }

        val entryDirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, entryDirDocId)
        val movedOrCopied: Uri? = when (transfer) {
            Transfer.MOVE -> moveInto(treeUri, source, entryDirUri, destName)
            Transfer.COPY -> copyInto(source, entryDirUri, destName, ext)
        }
        movedOrCopied?.let {
            SavedAsset(kind, it.toString(), destName, source.sizeBytes ?: 0L)
        }
    }

    suspend fun readEntryMetadata(treeUri: Uri, entryDirDocId: String): ArtworkEntryMetadata? =
        withContext(Dispatchers.IO) {
            val child = findChild(treeUri, entryDirDocId, ArtworkEntryMetadata.FILE_NAME) ?: return@withContext null
            readTextCapped(child.uri, ArtworkEntryMetadata.MAX_BYTES)?.let { ArtworkEntryMetadata.parse(it) }
        }

    suspend fun writeEntryMetadata(treeUri: Uri, entryDirDocId: String, metadata: ArtworkEntryMetadata): Boolean =
        withContext(Dispatchers.IO) {
            writeText(
                treeUri, entryDirDocId, ArtworkEntryMetadata.FILE_NAME, "application/json",
                ArtworkEntryMetadata.encode(metadata),
            )
        }

    fun clearDirCache() = dirCache.clear()

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
