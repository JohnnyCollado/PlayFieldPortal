package com.playfieldportal.feature.xmb.ui.detail

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.Video
import com.playfieldportal.core.domain.repository.VideoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject

// A row in the video's Options (△) menu. RESUME is only offered when there's a saved position.
enum class VideoDetailAction(val label: String) {
    PLAY("Play"),
    RESUME("Resume"),
    RESTART("Start from Beginning"),
    FAVORITE("Favorite"),
    PLAYLIST("Add to Playlist"),
    RENAME("Rename Title"),
    THUMBNAIL("Change Thumbnail"),
    INFO("Information"),
    LOCATION("Open File Location"),
    REMOVE("Remove From Library"),
}

// One playlist choice in the "Add to Playlist" picker; checked = the video is already a member.
data class VideoPlaylistOption(val id: Long, val name: String, val checked: Boolean)

// Shown while an external player is being launched (themed overlay), until PFP regains focus.
data class ExternalLaunch(val thumbnailUri: String?, val playerLabel: String)

data class VideoDetailUiState(
    val video: Video? = null,
    val siblings: List<Video> = emptyList(),
    val isLoading: Boolean = true,
    // Primary buttons: [Play] (+ [Resume] when a position is saved) + [Options].
    val mainFocus: Int = 0,
    val showOptions: Boolean = false,
    val optionsIndex: Int = 0,
    val confirmRemove: Boolean = false,
    val isEditingTitle: Boolean = false,
    val titleText: String = "",
    val infoVisible: Boolean = false,
    val actionMessage: String? = null,
    // Non-null triggers the system image picker for a custom thumbnail.
    val pickThumbnail: Boolean = false,
    // Add-to-playlist picker.
    val showPlaylistPicker: Boolean = false,
    val playlistOptions: List<VideoPlaylistOption> = emptyList(),
    val playlistPickerIndex: Int = 0,
    val creatingPlaylist: Boolean = false,
    val newPlaylistName: String = "",
    // Playback overlay.
    val playing: Boolean = false,
    val playStartPositionMs: Long = 0,
    // External-player hand-off: overlay while launching, and a hard error dialog on failure.
    val externalLaunch: ExternalLaunch? = null,
    val launchError: String? = null,
    val closed: Boolean = false,
) {
    val hasResume: Boolean get() = (video?.resumePositionMs ?: 0) > 0
    // Primary buttons: a watched video leads with Resume + Start from Beginning; an unwatched one
    // just shows Play. (Options is appended by the screen.)
    val primaryActions: List<VideoDetailAction>
        get() = if (hasResume) listOf(VideoDetailAction.RESUME, VideoDetailAction.RESTART)
                else listOf(VideoDetailAction.PLAY)
    // Options list, kept free of redundancy: Play only when there's no resume point; Resume + Start
    // from Beginning only when there is.
    val optionsActions: List<VideoDetailAction>
        get() = VideoDetailAction.entries.filter {
            when (it) {
                VideoDetailAction.PLAY    -> !hasResume
                VideoDetailAction.RESUME  -> hasResume
                VideoDetailAction.RESTART -> hasResume
                else                      -> true
            }
        }
}

// Treat playback as "finished" when it ends within this window of the duration — clears resume so
// the next open starts fresh instead of jumping to the last few seconds.
private const val RESUME_END_EPSILON_MS = 5_000L

@HiltViewModel
class VideoDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val videoRepository: VideoRepository,
    private val intentResolver: com.playfieldportal.core.data.video.VideoIntentResolver,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VideoDetailUiState())
    val uiState: StateFlow<VideoDetailUiState> = _uiState.asStateFlow()

    fun loadVideo(id: String) {
        viewModelScope.launch {
            _uiState.update { VideoDetailUiState(isLoading = true) }
            val video = videoRepository.getVideo(id)
            val siblings = video?.let { videoRepository.getVideosForLibrary(it.libraryId) }
                ?.sortedBy { it.displayTitle.lowercase() }
                ?: emptyList()
            _uiState.update {
                it.copy(video = video, siblings = siblings, isLoading = false, mainFocus = 0)
            }
        }
    }

    // ── Controller input ──────────────────────────────────────────────────────
    // While the player overlay is up the screen forwards input straight to it, so this only runs
    // for the detail page itself.
    fun handleGamepadAction(action: GamepadAction) {
        val s = _uiState.value
        // The launch overlay swallows input; a launch error dialog dismisses on A/B.
        if (s.externalLaunch != null) return
        if (s.launchError != null) {
            if (action == GamepadAction.SELECT || action == GamepadAction.BACK) dismissLaunchError()
            return
        }
        when {
            s.confirmRemove -> when (action) {
                GamepadAction.SELECT -> confirmRemove()
                GamepadAction.BACK   -> _uiState.update { it.copy(confirmRemove = false) }
                else -> Unit
            }
            s.creatingPlaylist -> if (action == GamepadAction.BACK) {
                _uiState.update { it.copy(creatingPlaylist = false, newPlaylistName = "") }
            }
            s.showPlaylistPicker -> {
                val count = s.playlistOptions.size + 1  // +1 for "Create New Playlist"
                when (action) {
                    GamepadAction.NAVIGATE_UP   -> _uiState.update { it.copy(playlistPickerIndex = (it.playlistPickerIndex - 1 + count) % count) }
                    GamepadAction.NAVIGATE_DOWN -> _uiState.update { it.copy(playlistPickerIndex = (it.playlistPickerIndex + 1) % count) }
                    GamepadAction.SELECT        -> activatePlaylistPickerRow(s.playlistPickerIndex)
                    GamepadAction.BACK          -> _uiState.update { it.copy(showPlaylistPicker = false) }
                    else -> Unit
                }
            }
            s.infoVisible -> if (action == GamepadAction.SELECT || action == GamepadAction.BACK) {
                _uiState.update { it.copy(infoVisible = false) }
            }
            s.isEditingTitle -> if (action == GamepadAction.BACK) cancelTitleEdit()
            s.showOptions -> {
                val count = s.optionsActions.size
                when (action) {
                    GamepadAction.NAVIGATE_UP   -> _uiState.update { it.copy(optionsIndex = (it.optionsIndex - 1 + count) % count) }
                    GamepadAction.NAVIGATE_DOWN -> _uiState.update { it.copy(optionsIndex = (it.optionsIndex + 1) % count) }
                    GamepadAction.SELECT        -> activate(s.optionsActions[s.optionsIndex.coerceIn(0, count - 1)])
                    GamepadAction.BACK          -> _uiState.update { it.copy(showOptions = false) }
                    else -> Unit
                }
            }
            else -> {
                // Primary row: just [primaryActions]. Options is reached with Y/Triangle (below) or
                // the touch Options pill — not an inline row.
                val total = s.primaryActions.size.coerceAtLeast(1)
                when (action) {
                    GamepadAction.NAVIGATE_UP   -> _uiState.update { it.copy(mainFocus = (it.mainFocus - 1 + total) % total) }
                    GamepadAction.NAVIGATE_DOWN -> _uiState.update { it.copy(mainFocus = (it.mainFocus + 1) % total) }
                    GamepadAction.SELECT -> s.primaryActions.getOrNull(s.mainFocus)?.let { activate(it) }
                    GamepadAction.BACK -> _uiState.update { it.copy(closed = true) }
                    GamepadAction.BUTTON_Y, GamepadAction.LONG_PRESS -> openOptions()
                    else -> Unit
                }
            }
        }
    }

    fun openOptions() = _uiState.update { it.copy(showOptions = true, optionsIndex = 0) }
    fun closeOptions() = _uiState.update { it.copy(showOptions = false) }

    fun activate(action: VideoDetailAction) {
        _uiState.update { it.copy(showOptions = false) }
        val video = _uiState.value.video ?: return
        when (action) {
            VideoDetailAction.PLAY    -> play(0)
            VideoDetailAction.RESUME  -> play(video.resumePositionMs)
            VideoDetailAction.RESTART -> play(0)
            VideoDetailAction.FAVORITE -> toggleFavorite()
            VideoDetailAction.PLAYLIST -> openPlaylistPicker()
            VideoDetailAction.RENAME  -> startEditTitle()
            VideoDetailAction.THUMBNAIL -> _uiState.update { it.copy(pickThumbnail = true) }
            VideoDetailAction.INFO    -> _uiState.update { it.copy(infoVisible = true) }
            VideoDetailAction.LOCATION -> showMessage(video.relativePath?.let { "In: $it" } ?: video.displayName)
            VideoDetailAction.REMOVE  -> _uiState.update { it.copy(confirmRemove = true) }
        }
    }

    // ── Favorites ───────────────────────────────────────────────────────────────

    fun toggleFavorite() {
        val v = _uiState.value.video ?: return
        viewModelScope.launch {
            val next = !v.isFavorite
            videoRepository.setFavorite(v.id, next)
            _uiState.update { it.copy(video = it.video?.copy(isFavorite = next), actionMessage = if (next) "Added to Favorites" else "Removed from Favorites") }
        }
    }

    // ── Add to playlist ───────────────────────────────────────────────────────────

    fun openPlaylistPicker() {
        val v = _uiState.value.video ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(showPlaylistPicker = true, playlistPickerIndex = 0, playlistOptions = buildPlaylistOptions(v.id)) }
        }
    }

    private suspend fun buildPlaylistOptions(videoId: String): List<VideoPlaylistOption> {
        val memberOf = videoRepository.getPlaylistIdsForVideo(videoId).toSet()
        return videoRepository.observePlaylists().first().map {
            VideoPlaylistOption(id = it.id, name = it.name, checked = it.id in memberOf)
        }
    }

    fun onPlaylistRowClick(index: Int) {
        _uiState.update { it.copy(playlistPickerIndex = index) }
        activatePlaylistPickerRow(index)
    }

    private fun activatePlaylistPickerRow(index: Int) {
        val s = _uiState.value
        val v = s.video ?: return
        if (index >= s.playlistOptions.size) {
            // "Create New Playlist" row.
            _uiState.update { it.copy(creatingPlaylist = true, newPlaylistName = "") }
            return
        }
        val option = s.playlistOptions.getOrNull(index) ?: return
        viewModelScope.launch {
            videoRepository.toggleVideoInPlaylist(option.id, v.id)
            _uiState.update { it.copy(playlistOptions = buildPlaylistOptions(v.id)) }
        }
    }

    fun closePlaylistPicker() = _uiState.update { it.copy(showPlaylistPicker = false) }

    fun onNewPlaylistNameChange(text: String) = _uiState.update { it.copy(newPlaylistName = text) }

    fun confirmCreatePlaylist() {
        val v = _uiState.value.video ?: return
        val name = _uiState.value.newPlaylistName.trim()
        if (name.isBlank()) { _uiState.update { it.copy(creatingPlaylist = false) }; return }
        viewModelScope.launch {
            val id = videoRepository.createPlaylist(name)
            videoRepository.addVideoToPlaylist(id, v.id)
            _uiState.update { it.copy(creatingPlaylist = false, newPlaylistName = "", playlistOptions = buildPlaylistOptions(v.id)) }
        }
    }

    fun cancelCreatePlaylist() = _uiState.update { it.copy(creatingPlaylist = false, newPlaylistName = "") }

    // ── Playback ──────────────────────────────────────────────────────────────

    // Routes a play request to the built-in player, an external app, or the system chooser based on
    // the Default Player setting. External hand-off is validated first, shows a themed launch
    // overlay, and surfaces a real error dialog (never fails silently, never crashes).
    fun play(positionMs: Long) {
        val video = _uiState.value.video ?: return
        viewModelScope.launch {
            val pref = videoRepository.getDefaultVideoPlayer()
            if (pref == null || pref == "builtin") { startPlayback(positionMs); return@launch }

            val ask = pref == "ask"
            val pkg = if (ask) null else pref
            // Verify the file, uri and a resolving activity BEFORE we fade out / hand off.
            intentResolver.validate(video, pkg)?.let { err ->
                _uiState.update { it.copy(showOptions = false, launchError = err) }
                return@launch
            }
            val label = if (ask) "an external player" else (intentResolver.playerLabel(pref) ?: "external player")
            _uiState.update {
                it.copy(showOptions = false, externalLaunch = ExternalLaunch(video.effectiveThumbnailUri, label))
            }
            // Best-effort: record that it was opened now so it appears under Recently Watched.
            markWatchedExternally(video)
            val err = if (ask) intentResolver.launchChooser(video) else intentResolver.launch(video, pref)
            if (err != null) _uiState.update { it.copy(externalLaunch = null, launchError = err) }
        }
    }

    // Called when PFP regains focus after an external launch: drop the overlay and refresh just this
    // video's metadata (resume / last-watched) — no library rescan, no focus/scroll reset.
    fun onReturnedFromExternal() {
        if (_uiState.value.externalLaunch == null) return
        _uiState.update { it.copy(externalLaunch = null) }
        val id = _uiState.value.video?.id ?: return
        viewModelScope.launch {
            val fresh = videoRepository.getVideo(id)
            _uiState.update { it.copy(video = fresh ?: it.video) }
        }
    }

    // Defensive: if the hand-off never backgrounded us (rare), clear the overlay so it can't stick.
    fun clearExternalOverlay() = _uiState.update { it.copy(externalLaunch = null) }

    fun dismissLaunchError() = _uiState.update { it.copy(launchError = null) }

    // Clears the one-shot close flag once the screen has popped, so the retained ViewModel doesn't
    // re-close the detail on the next open.
    fun onClosedHandled() = _uiState.update { it.copy(closed = false) }

    fun startPlayback(positionMs: Long) {
        _uiState.update { it.copy(playing = true, playStartPositionMs = positionMs, showOptions = false) }
    }

    // External playback position can't be tracked, so just stamp lastWatchedAt (keeping any resume
    // position) so the video still shows up under Recently Watched.
    private suspend fun markWatchedExternally(video: Video) {
        videoRepository.setResumePosition(video.id, video.resumePositionMs, System.currentTimeMillis())
    }

    fun onPlaybackExit() {
        // Reload so the detail reflects the freshly-saved resume position.
        _uiState.update { it.copy(playing = false) }
        _uiState.value.video?.id?.let { loadVideo(it) }
    }

    fun saveResume(videoId: String, positionMs: Long, durationMs: Long) {
        viewModelScope.launch {
            val finished = durationMs > 0 && positionMs >= durationMs - RESUME_END_EPSILON_MS
            if (finished || positionMs <= 0) videoRepository.clearResumePosition(videoId)
            else videoRepository.setResumePosition(videoId, positionMs, System.currentTimeMillis())
        }
    }

    // ── Title ───────────────────────────────────────────────────────────────

    fun startEditTitle() {
        val v = _uiState.value.video ?: return
        _uiState.update { it.copy(isEditingTitle = true, titleText = v.displayTitle) }
    }
    fun onTitleChanged(text: String) = _uiState.update { it.copy(titleText = text) }
    fun saveTitle() {
        val v = _uiState.value.video ?: return
        val newTitle = _uiState.value.titleText.trim().ifEmpty { null }
        viewModelScope.launch {
            videoRepository.setCustomTitle(v.id, newTitle)
            _uiState.update { it.copy(video = videoRepository.getVideo(v.id) ?: it.video, isEditingTitle = false) }
        }
    }
    fun cancelTitleEdit() = _uiState.update { it.copy(isEditingTitle = false) }

    // ── Thumbnail ─────────────────────────────────────────────────────────────

    fun consumeThumbnailPick() = _uiState.update { it.copy(pickThumbnail = false) }

    fun onThumbnailPicked(uri: Uri) {
        val v = _uiState.value.video ?: return
        viewModelScope.launch {
            val path = copyThumbnail(uri, v.id)
            if (path == null) { showMessage("Could not import image"); return@launch }
            videoRepository.setCustomThumbnail(v.id, path)
            _uiState.update { it.copy(video = videoRepository.getVideo(v.id) ?: it.video, actionMessage = "Thumbnail updated") }
        }
    }

    private suspend fun copyThumbnail(uri: Uri, videoId: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, "video_thumbs").apply { mkdirs() }
            val dest = File(dir, "custom_${videoId}_${System.currentTimeMillis()}.img")
            context.contentResolver.openInputStream(uri)?.use { inp ->
                dest.outputStream().use { out -> inp.copyTo(out) }
            } ?: return@runCatching null
            Uri.fromFile(dest).toString().takeIf { dest.length() > 0 }
        }.getOrElse { Timber.w(it, "Thumbnail import failed"); null }
    }

    // ── Remove ────────────────────────────────────────────────────────────────

    fun confirmRemove() {
        val v = _uiState.value.video ?: return
        viewModelScope.launch {
            videoRepository.removeVideo(v.id)
            _uiState.update { it.copy(confirmRemove = false, closed = true) }
        }
    }

    fun dismissMessage() = _uiState.update { it.copy(actionMessage = null) }
    private fun showMessage(msg: String) = _uiState.update { it.copy(actionMessage = msg) }
}
