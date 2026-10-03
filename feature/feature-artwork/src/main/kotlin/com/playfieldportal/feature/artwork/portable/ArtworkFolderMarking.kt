package com.playfieldportal.feature.artwork.portable

import com.playfieldportal.core.data.saf.SafChild
import com.playfieldportal.core.data.saf.hasNoMediaMarker
import com.playfieldportal.core.data.saf.isIgnoredDir

/**
 * Leaves a `.nomedia` marker in every artwork folder of a library, so the gallery and PFP's own
 * photo and video libraries never index box art and screenshots as the user's pictures.
 *
 * [PortableArtworkLibrary] marks a folder whenever a write passes through it; this walk is for the
 * rest, run when the library opens: folders from before the marker existed, and ones nothing has
 * written to since. Pure over [children] and [mark], so the walk is tested without a provider.
 */
internal object ArtworkFolderMarking {

    /** The library's own top-level folders. The root itself is the user's, and stays unmarked. */
    private val TOP_LEVEL = listOf(
        ArtworkLibraryManifest.DIR_ARTWORK,
        ArtworkLibraryManifest.DIR_IMPORT,
        ArtworkLibraryManifest.DIR_GAMES,
    )

    /**
     * Marks each unmarked folder under the library's top-level folders, those included, and returns
     * how many markers [mark] created. Iterative, with a visited set, so a deep tree or a provider
     * that surfaces a folder twice cannot run it away. Hidden folders are neither marked nor walked.
     */
    fun markAll(
        rootDocId: String,
        children: (String) -> List<SafChild>,
        mark: (String) -> Boolean,
    ): Int {
        val stack = ArrayDeque<String>()
        children(rootDocId)
            .filter { child -> child.isDirectory && TOP_LEVEL.any { it.equals(child.name, ignoreCase = true) } }
            .forEach { stack.addLast(it.documentId) }
        val visited = HashSet<String>()
        var created = 0
        while (stack.isNotEmpty()) {
            val dirId = stack.removeLast()
            if (!visited.add(dirId)) continue
            val listing = children(dirId)
            if (!listing.hasNoMediaMarker() && mark(dirId)) created++
            listing.filter { it.isDirectory && !it.isIgnoredDir() }.forEach { stack.addLast(it.documentId) }
        }
        return created
    }
}
