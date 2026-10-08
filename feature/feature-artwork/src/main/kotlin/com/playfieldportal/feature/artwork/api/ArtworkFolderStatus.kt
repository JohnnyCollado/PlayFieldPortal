package com.playfieldportal.feature.artwork.api

import android.net.Uri
import com.playfieldportal.core.data.repository.ArtworkFolderRepository
import com.playfieldportal.core.data.repository.RomRootRepository
import com.playfieldportal.feature.artwork.portable.PortableArtworkLibrary
import com.playfieldportal.feature.artwork.store.InternalArtworkStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Where the required artwork folder stands. [treeUri] is the stored tree URI string. */
sealed interface ArtworkFolderState {
    /** Before the first [ArtworkFolderStatus.refresh]. */
    data object Unknown : ArtworkFolderState

    /** The folder is set, its grant is live and its root resolves: new artwork can be written. */
    data class Ready(val treeUri: String, val name: String) : ArtworkFolderState

    /** The folder is set but its grant is gone or its root is unreachable: new writes pause. */
    data class Unavailable(val treeUri: String, val name: String) : ArtworkFolderState

    /** No folder is set. [internalFiles] is the artwork still stored inside the app. */
    data class NotLinked(val internalFiles: Int) : ArtworkFolderState
}

/** The one line every scrape surface shows when a run was paused by [ArtworkFolderState]. */
const val ARTWORK_PAUSED_MESSAGE = "Artwork paused. Relink your artwork folder to save new art."

/** What needed the folder: a scrape-family write or a user pick. */
enum class FolderNeedTrigger { SCRAPE, PICK }

/** Emitted when an operation could not proceed because the folder is not [ArtworkFolderState.Ready]. */
data class FolderNeed(val trigger: FolderNeedTrigger)

/** A picked folder that already holds a different install's library, awaiting the user's choice. */
data class ForeignLibrary(val pickedTree: String, val name: String, val fileCount: Int)

/** Which shell prompt, if any, the artwork folder state calls for. */
sealed interface ArtworkFolderPrompt {
    data class Unavailable(val name: String) : ArtworkFolderPrompt
    data class MoveArtwork(val internalFiles: Int) : ArtworkFolderPrompt
    data class ForeignLibrary(val name: String, val fileCount: Int) : ArtworkFolderPrompt

    /** Not linked and nothing stored internally: just ask for a folder. */
    data object ChooseFolder : ArtworkFolderPrompt
}

/**
 * The single observable source of artwork-folder availability. Starts [ArtworkFolderState.Unknown]
 * and changes only through [refresh]. Probe: stored tree URI, then a live persisted read+write
 * grant, then the tree's root document resolving.
 */
@Singleton
class ArtworkFolderStatus @Inject constructor(
    private val repository: ArtworkFolderRepository,
    private val library: PortableArtworkLibrary,
    private val internalStore: InternalArtworkStore,
) {
    private val _state = MutableStateFlow<ArtworkFolderState>(ArtworkFolderState.Unknown)
    val state: StateFlow<ArtworkFolderState> = _state.asStateFlow()

    private val _folderNeeded = MutableSharedFlow<FolderNeed>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val folderNeeded: SharedFlow<FolderNeed> = _folderNeeded.asSharedFlow()

    private val _pendingForeignLibrary = MutableStateFlow<ForeignLibrary?>(null)
    val pendingForeignLibrary: StateFlow<ForeignLibrary?> = _pendingForeignLibrary.asStateFlow()

    /** Re-probes the folder, publishes and returns the result. Never throws. */
    suspend fun refresh(): ArtworkFolderState {
        var tree: String? = null
        val next = try {
            tree = repository.getTreeUri()?.takeIf { it.isNotBlank() }
            if (tree == null) {
                ArtworkFolderState.NotLinked(internalStore.footprint().first)
            } else {
                val name = RomRootRepository.displayNameOfTree(tree)
                if (repository.hasLiveGrant() && library.isRootReachable(Uri.parse(tree))) {
                    ArtworkFolderState.Ready(tree, name)
                } else {
                    ArtworkFolderState.Unavailable(tree, name)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Artwork folder probe failed")
            tree?.let { ArtworkFolderState.Unavailable(it, RomRootRepository.displayNameOfTree(it)) }
                ?: ArtworkFolderState.NotLinked(0)
        }
        _state.value = next
        if (next is ArtworkFolderState.Ready) clearDeferral()
        return next
    }

    /** Signals that [trigger]'s operation was blocked by the folder. Non-suspending, drop-oldest. */
    fun reportBlocked(trigger: FolderNeedTrigger) {
        _folderNeeded.tryEmit(FolderNeed(trigger))
    }

    /** Holder only: the link flow fills and clears it. */
    fun setPendingForeignLibrary(pending: ForeignLibrary?) {
        _pendingForeignLibrary.value = pending
    }

    suspend fun isDeferred(): Boolean = repository.isPromptDeferred()

    suspend fun defer() = repository.setPromptDeferred(true)

    suspend fun clearDeferral() {
        if (repository.isPromptDeferred()) repository.setPromptDeferred(false)
    }
}
