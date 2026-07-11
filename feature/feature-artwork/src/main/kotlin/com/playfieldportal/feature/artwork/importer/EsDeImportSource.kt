package com.playfieldportal.feature.artwork.importer

import android.content.Context
import android.net.Uri
import com.playfieldportal.core.data.platform.PlatformFolderHintResolver
import com.playfieldportal.core.data.saf.SafChild
import com.playfieldportal.core.data.saf.isIgnoredDir
import com.playfieldportal.core.data.saf.querySafChildren
import com.playfieldportal.feature.artwork.portable.ArtworkPathResolver
import com.playfieldportal.feature.artwork.store.ArtworkKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Recognizes and enumerates artwork from other launchers dropped under `import/<Launcher>`.
 * Detection is structural (folder shapes), never trusting the folder's name — the name is only
 * the label shown to the user.
 */
interface ArtworkImportSource {
    val sourceId: String
    /** Non-null when [folder] (a child of `import/`) has this source's layout. */
    suspend fun detect(treeUri: Uri, folder: SafChild): DetectedImportSource?
    /** Every importable artwork file in the source. Cheap rows only — no image is decoded. */
    suspend fun enumerate(treeUri: Uri, source: DetectedImportSource): EnumerationResult
}

data class EnumerationResult(
    val candidates: List<ImportCandidate>,
    val unknownSystemFolders: List<String>,
)

/**
 * ES-DE `downloaded_media` layout:
 *
 *   downloaded_media/{system}/{mediaType}/(subdirs mirroring the ROM tree)/Game.png
 *
 * Tolerated drops: `import/ES-DE/downloaded_media/{systems}` or the contents directly
 * (`import/ES-DE/{systems}`). System names resolve through [PlatformFolderHintResolver]
 * (PFP platform ids are ES-DE canonical names). Media folders enumerate recursively because
 * ES-DE mirrors ROM subdirectories inside each media folder.
 */
@Singleton
class EsDeImportSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val platformResolver: PlatformFolderHintResolver,
) : ArtworkImportSource {

    override val sourceId = "esde"

    override suspend fun detect(treeUri: Uri, folder: SafChild): DetectedImportSource? =
        withContext(Dispatchers.IO) {
            // Either the folder itself holds system dirs, or a downloaded_media child does.
            val direct = systemFolders(treeUri, folder.documentId)
            val chosen = if (direct.systems.isNotEmpty()) {
                folder.documentId to direct
            } else {
                context.contentResolver.querySafChildren(treeUri, folder.documentId)
                    .firstOrNull { it.isDirectory && it.name.equals("downloaded_media", ignoreCase = true) }
                    ?.let { it.documentId to systemFolders(treeUri, it.documentId) }
                    ?: (folder.documentId to direct)
            }
            val (systemsRoot, scan) = chosen
            if (scan.systems.isEmpty()) return@withContext null
            DetectedImportSource(
                sourceId = sourceId,
                label = folder.name,
                folderDocId = folder.documentId,
                systemsRootDocId = systemsRoot,
                systems = scan.systems,
                gamelistDocIds = findGamelists(treeUri, folder.documentId, systemsRoot, scan.systems),
            )
        }

    override suspend fun enumerate(treeUri: Uri, source: DetectedImportSource): EnumerationResult =
        withContext(Dispatchers.IO) {
            val candidates = mutableListOf<ImportCandidate>()
            val unknown = systemFolders(treeUri, source.systemsRootDocId).unknownDirs
            for (system in source.systems) {
                for (mediaDir in context.contentResolver.querySafChildren(treeUri, system.docId)) {
                    if (!mediaDir.isDirectory) continue
                    val kind = ArtworkPathResolver.kindForMediaDir(mediaDir.name)
                        ?.takeIf { it in ArtworkPathResolver.importedKinds } ?: continue
                    collectRecursively(treeUri, mediaDir.documentId, system.platformId, kind, candidates, depth = 0)
                }
            }
            Timber.i("ES-DE enumerate: ${candidates.size} candidates across ${source.systems.size} systems")
            EnumerationResult(candidates, unknown)
        }

    // ── Internals ─────────────────────────────────────────────────────────────

    // ES-DE keeps text metadata in gamelists/{system}/gamelist.xml, a sibling of
    // downloaded_media in its home folder. Accepted drop shapes, checked in order:
    //   import/ES-DE/gamelists/{system}/gamelist.xml   (user copied the gamelists folder)
    //   {system media dir}/gamelist.xml                (some exports keep it beside the media)
    private fun findGamelists(
        treeUri: Uri,
        folderDocId: String,
        systemsRootDocId: String,
        systems: List<DetectedImportSource.SystemFolder>,
    ): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val gamelistsDir = listOf(folderDocId, systemsRootDocId).distinct().firstNotNullOfOrNull { parent ->
            context.contentResolver.querySafChildren(treeUri, parent)
                .firstOrNull { it.isDirectory && it.name.equals("gamelists", ignoreCase = true) }
        }
        if (gamelistsDir != null) {
            for (systemDir in context.contentResolver.querySafChildren(treeUri, gamelistsDir.documentId)) {
                if (!systemDir.isDirectory) continue
                val platformId = platformResolver.detectFromFolderName(systemDir.name) ?: continue
                context.contentResolver.querySafChildren(treeUri, systemDir.documentId)
                    .firstOrNull { !it.isDirectory && it.name.equals("gamelist.xml", ignoreCase = true) }
                    ?.let { out[platformId] = it.documentId }
            }
        }
        // Fallback per system: a gamelist.xml sitting inside the system's media folder.
        for (system in systems) {
            if (system.platformId in out) continue
            context.contentResolver.querySafChildren(treeUri, system.docId)
                .firstOrNull { !it.isDirectory && it.name.equals("gamelist.xml", ignoreCase = true) }
                ?.let { out[system.platformId] = it.documentId }
        }
        if (out.isNotEmpty()) Timber.i("ES-DE gamelists found for ${out.keys}")
        return out
    }

    private data class SystemScan(
        val systems: List<DetectedImportSource.SystemFolder>,
        val unknownDirs: List<String>,
    )

    // A directory is a system folder when its name maps to a platform AND it contains at least
    // one known media-type folder (structural check — a stray "gba" dir with no covers/ etc.
    // is not evidence of an ES-DE layout).
    private fun systemFolders(treeUri: Uri, parentDocId: String): SystemScan {
        val systems = mutableListOf<DetectedImportSource.SystemFolder>()
        val unknown = mutableListOf<String>()
        for (child in context.contentResolver.querySafChildren(treeUri, parentDocId)) {
            if (!child.isDirectory || child.isIgnoredDir()) continue
            val hasMediaDirs = context.contentResolver.querySafChildren(treeUri, child.documentId)
                .any { it.isDirectory && ArtworkPathResolver.isMediaDirName(it.name) }
            if (!hasMediaDirs) continue
            val platformId = platformResolver.detectFromFolderName(child.name)
            if (platformId != null) {
                systems += DetectedImportSource.SystemFolder(platformId, child.documentId, child.name)
            } else {
                unknown += child.name
            }
        }
        return SystemScan(systems, unknown)
    }

    private suspend fun collectRecursively(
        treeUri: Uri,
        dirDocId: String,
        platformId: String,
        kind: ArtworkKind,
        out: MutableList<ImportCandidate>,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH) return   // hostile/cyclic trees can't recurse unboundedly
        coroutineContext.ensureActive()
        for (child in context.contentResolver.querySafChildren(treeUri, dirDocId)) {
            when {
                child.isIgnoredDir() -> continue
                child.isDirectory ->
                    collectRecursively(treeUri, child.documentId, platformId, kind, out, depth + 1)
                isAcceptedName(kind, child.name) -> out += ImportCandidate(
                    platformId = platformId,
                    kind = kind.name,
                    documentId = child.documentId,
                    displayName = child.name,
                    sizeBytes = child.sizeBytes ?: 0L,
                )
            }
        }
    }

    private fun isAcceptedName(kind: ArtworkKind, name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (kind) {
            ArtworkKind.MANUAL -> ext == "pdf"
            // Containers the snap transcoder's extractor handles (payload is sniffed again
            // before anything is stored).
            ArtworkKind.VIDEO -> ext == "mp4" || ext == "m4v" || ext == "webm" || ext == "mkv"
            else -> ext == "png" || ext == "jpg" || ext == "jpeg" || ext == "webp"
        }
    }

    companion object {
        private const val MAX_DEPTH = 6
        // The media-dir ↔ kind mapping is single-sourced in ArtworkPathResolver — ES-DE's
        // folder names ARE the library layout v2 names.
    }
}
