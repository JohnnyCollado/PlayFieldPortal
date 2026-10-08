package com.playfieldportal.feature.artwork.api

import android.net.Uri
import android.provider.DocumentsContract
import com.playfieldportal.core.data.repository.RomRootRepository

/** Which prompt the folder state calls for. A pending foreign library always wins. */
object ArtworkFolderPrompts {

    /** At app launch: honours the deferral, and stays silent when there is nothing to move. */
    fun onLaunch(
        state: ArtworkFolderState,
        deferred: Boolean,
        pending: ForeignLibrary?,
    ): ArtworkFolderPrompt? {
        pending?.let { return it.toPrompt() }
        if (deferred) return null
        return when (state) {
            is ArtworkFolderState.Unavailable -> ArtworkFolderPrompt.Unavailable(state.name)
            is ArtworkFolderState.NotLinked ->
                if (state.internalFiles > 0) ArtworkFolderPrompt.MoveArtwork(state.internalFiles) else null
            is ArtworkFolderState.Ready, ArtworkFolderState.Unknown -> null
        }
    }

    /** When a scrape or pick needs the folder: ignores the deferral. */
    fun onNeed(state: ArtworkFolderState, pending: ForeignLibrary?): ArtworkFolderPrompt? {
        pending?.let { return it.toPrompt() }
        return when (state) {
            is ArtworkFolderState.Unavailable -> ArtworkFolderPrompt.Unavailable(state.name)
            is ArtworkFolderState.NotLinked ->
                if (state.internalFiles > 0) ArtworkFolderPrompt.MoveArtwork(state.internalFiles)
                else ArtworkFolderPrompt.ChooseFolder
            is ArtworkFolderState.Ready, ArtworkFolderState.Unknown -> null
        }
    }

    private fun ForeignLibrary.toPrompt() = ArtworkFolderPrompt.ForeignLibrary(name, fileCount)
}

/** What linking a picked folder means, given the stored library UUID and the folder's manifest UUID. */
object LibraryLinkDecision {
    enum class Outcome {
        /** The folder has no manifest. */
        NEW_LIBRARY,

        /** The manifest matches the stored UUID. */
        SAME_LIBRARY,

        /** The folder has a manifest and nothing is stored: take it. */
        ADOPT,

        /** The folder has a manifest that differs from the stored UUID. */
        FOREIGN,
    }

    fun decide(storedUuid: String?, manifestUuid: String?): Outcome {
        val manifest = manifestUuid?.takeIf { it.isNotBlank() } ?: return Outcome.NEW_LIBRARY
        val stored = storedUuid?.takeIf { it.isNotBlank() } ?: return Outcome.ADOPT
        return if (stored == manifest) Outcome.SAME_LIBRARY else Outcome.FOREIGN
    }
}

/** Where the artwork folder picker opens (`EXTRA_INITIAL_URI`). */
object ArtworkFolderPickerUris {

    /** The primary storage volume's root. */
    fun deviceRoot(): Uri =
        DocumentsContract.buildDocumentUri(RomRootRepository.EXTERNAL_STORAGE_AUTHORITY, "primary:")

    /** The stored tree's root document, so a relink opens on the old folder. */
    fun forRelink(treeUri: Uri): Uri {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        val authority = treeUri.authority
        return if (docId == null || authority == null) deviceRoot()
        else DocumentsContract.buildDocumentUri(authority, docId)
    }
}
