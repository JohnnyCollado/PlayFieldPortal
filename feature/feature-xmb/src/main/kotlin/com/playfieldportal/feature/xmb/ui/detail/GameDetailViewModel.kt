package com.playfieldportal.feature.xmb.ui.detail

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.database.dao.PlatformDao
import com.playfieldportal.core.data.database.entity.PlatformEntity
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.feature.artwork.api.ArtworkRepository
import com.playfieldportal.feature.artwork.api.SgdbArtItem
import com.playfieldportal.feature.artwork.api.SgdbArtType
import com.playfieldportal.feature.artwork.api.SteamGridDbApi
import com.playfieldportal.feature.artwork.card.CardArtworkProcessor
import com.playfieldportal.feature.launcher.EmulatorIntentResolver
import com.playfieldportal.feature.launcher.EmulatorProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject

enum class ArtworkType { BOX_ART, HERO, LOGO }

data class GameDetailUiState(
    val game: Game? = null,
    val platform: PlatformEntity? = null,
    val isLoading: Boolean = true,
    val isEditingNote: Boolean = false,
    val noteText: String = "",
    val isFetchingArtwork: Boolean = false,
    val artworkMessage: String? = null,
    val launchError: String? = null,
    // Custom artwork panel
    val showCustomArtwork: Boolean = false,
    val isProcessingCustomArtwork: Boolean = false,
    // SteamGridDB inline browser
    val sgdbBrowsingType: ArtworkType? = null,
    val sgdbItems: List<SgdbArtItem> = emptyList(),
    val sgdbIsLoading: Boolean = false,
    val sgdbError: String? = null,
    // ── Steam Deck-style details navigation ───────────────────────────────
    val focusSection: DetailSection = DetailSection.PLAY,
    val actionIndex: Int = 0,                  // index into DetailAction.entries
    val screenshotIndex: Int = 0,
    val mediaUris: List<String> = emptyList(), // hero / box / logo for the media strip
    val emulatorName: String? = null,          // resolved emulator for this game
    val showMediaViewer: Boolean = false,
    val mediaViewerIndex: Int = 0,
    val confirmRemove: Boolean = false,
    val actionMessage: String? = null,         // transient feedback for info-only actions
    val closed: Boolean = false,               // signals the screen to pop back
)

// Page sections the controller moves between with Up/Down.
enum class DetailSection { PLAY, ACTIONS, SCREENSHOTS }

// Action buttons (after Play), navigated Left/Right within the ACTIONS section.
enum class DetailAction(val label: String) {
    FAVORITE("Favorite"),
    SETTINGS("Game Settings"),
    SAVES("Saves"),
    EMULATOR("Emulator"),
    MANUAL("Manual"),
    REFRESH("Refresh"),
    EDIT("Edit Metadata"),
    LOCATION("Open Location"),
    REMOVE("Remove"),
}

@HiltViewModel
class GameDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gameRepository: GameRepository,
    private val platformDao: PlatformDao,
    private val profileRepository: EmulatorProfileRepository,
    private val intentResolver: EmulatorIntentResolver,
    private val artworkRepository: ArtworkRepository,
    private val steamGridDb: SteamGridDbApi,
    private val cardProcessor: CardArtworkProcessor,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GameDetailUiState())
    val uiState: StateFlow<GameDetailUiState> = _uiState.asStateFlow()

    // One-shot launch intent — collected in the composable via LaunchedEffect
    private val _launchEffect = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val launchEffect: SharedFlow<Intent> = _launchEffect.asSharedFlow()

    fun loadGame(id: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val game = gameRepository.getById(id)
            val platform = game?.let { platformDao.getById(it.platformId) }
            val media = mediaOf(game)
            val emulator = game?.let { resolveProfile(it)?.name }
            // Reset all transient navigation/overlay state so each open starts fresh, even if
            // the ViewModel instance is reused (activity-scoped) across opens.
            _uiState.update {
                it.copy(
                    game            = game,
                    platform        = platform,
                    noteText        = game?.userNote ?: "",
                    mediaUris       = media,
                    emulatorName    = emulator,
                    isLoading       = false,
                    focusSection    = DetailSection.PLAY,
                    actionIndex     = 0,
                    screenshotIndex = 0,
                    showMediaViewer = false,
                    confirmRemove   = false,
                    isEditingNote   = false,
                    showCustomArtwork = false,
                    actionMessage   = null,
                    launchError     = null,
                    closed          = false,
                )
            }
        }
    }

    // ── Controller navigation (Steam Deck-style sections) ─────────────────────

    fun handleGamepadAction(action: GamepadAction) {
        // Dialogs / overlays capture input first.
        val s = _uiState.value
        if (s.showMediaViewer) {
            when (action) {
                GamepadAction.NAVIGATE_LEFT  -> stepMediaViewer(-1)
                GamepadAction.NAVIGATE_RIGHT -> stepMediaViewer(+1)
                GamepadAction.BACK, GamepadAction.SELECT -> closeMediaViewer()
                else -> Unit
            }
            return
        }
        if (s.confirmRemove) {
            when (action) {
                GamepadAction.SELECT -> confirmRemoveGame()
                GamepadAction.BACK   -> _uiState.update { it.copy(confirmRemove = false) }
                else -> Unit
            }
            return
        }
        if (s.isEditingNote) {
            if (action == GamepadAction.BACK) cancelNote()
            return
        }
        if (s.showCustomArtwork) {
            if (action == GamepadAction.BACK) {
                if (s.sgdbBrowsingType != null) closeSgdbPicker() else toggleCustomArtworkPanel()
            }
            return
        }

        when (action) {
            GamepadAction.NAVIGATE_UP   -> moveSection(-1)
            GamepadAction.NAVIGATE_DOWN -> moveSection(+1)
            GamepadAction.NAVIGATE_LEFT -> moveWithinSection(-1)
            GamepadAction.NAVIGATE_RIGHT -> moveWithinSection(+1)
            GamepadAction.SELECT        -> activateFocused()
            // Top-level Back closes the Details page (returns to the previous screen).
            GamepadAction.BACK          -> _uiState.update { it.copy(closed = true) }
            else -> Unit
        }
    }

    private fun maxSection(): Int = if (_uiState.value.mediaUris.isNotEmpty()) 2 else 1

    private fun moveSection(delta: Int) {
        val sections = DetailSection.entries
        val current = _uiState.value.focusSection.ordinal
        val next = (current + delta).coerceIn(0, maxSection())
        _uiState.update { it.copy(focusSection = sections[next], actionMessage = null) }
    }

    private fun moveWithinSection(delta: Int) {
        when (_uiState.value.focusSection) {
            DetailSection.ACTIONS -> {
                val count = DetailAction.entries.size
                _uiState.update { it.copy(actionIndex = (it.actionIndex + delta).coerceIn(0, count - 1)) }
            }
            DetailSection.SCREENSHOTS -> {
                val count = _uiState.value.mediaUris.size
                if (count > 0) _uiState.update {
                    it.copy(screenshotIndex = (it.screenshotIndex + delta).coerceIn(0, count - 1))
                }
            }
            DetailSection.PLAY -> Unit
        }
    }

    private fun activateFocused() {
        when (_uiState.value.focusSection) {
            DetailSection.PLAY        -> launch()
            DetailSection.ACTIONS     -> activateAction(DetailAction.entries[_uiState.value.actionIndex])
            DetailSection.SCREENSHOTS -> openMediaViewer(_uiState.value.screenshotIndex)
        }
    }

    // Touch / mouse entry points
    fun focusPlay() = _uiState.update { it.copy(focusSection = DetailSection.PLAY) }
    fun onActionClicked(action: DetailAction) {
        _uiState.update { it.copy(focusSection = DetailSection.ACTIONS, actionIndex = DetailAction.entries.indexOf(action)) }
        activateAction(action)
    }

    fun activateAction(action: DetailAction) {
        when (action) {
            DetailAction.FAVORITE -> toggleFavorite()
            DetailAction.SETTINGS -> toggleCustomArtworkPanel()
            DetailAction.SAVES    -> showActionMessage("Save management isn't available yet")
            DetailAction.EMULATOR -> showActionMessage(
                _uiState.value.emulatorName?.let { "Emulator: $it" } ?: "No emulator installed for this platform"
            )
            DetailAction.MANUAL   -> showActionMessage("No manual available for this game")
            DetailAction.REFRESH  -> fetchArtwork()
            DetailAction.EDIT     -> startEditNote()
            DetailAction.LOCATION -> showActionMessage(
                _uiState.value.game?.romPath
                    ?: _uiState.value.game?.packageName?.let { "Package: $it" }
                    ?: "No file location on record"
            )
            DetailAction.REMOVE   -> _uiState.update { it.copy(confirmRemove = true) }
        }
    }

    private fun showActionMessage(message: String) =
        _uiState.update { it.copy(actionMessage = message) }

    fun dismissActionMessage() = _uiState.update { it.copy(actionMessage = null) }

    // ── Media viewer ──────────────────────────────────────────────────────────

    fun openMediaViewer(index: Int) {
        if (_uiState.value.mediaUris.isEmpty()) return
        _uiState.update { it.copy(showMediaViewer = true, mediaViewerIndex = index.coerceIn(0, it.mediaUris.lastIndex)) }
    }
    fun closeMediaViewer() = _uiState.update { it.copy(showMediaViewer = false) }
    private fun stepMediaViewer(delta: Int) {
        _uiState.update { it.copy(mediaViewerIndex = (it.mediaViewerIndex + delta).coerceIn(0, it.mediaUris.lastIndex)) }
    }

    // ── Remove from library ─────────────────────────────────────────────────────

    fun requestRemove() = _uiState.update { it.copy(confirmRemove = true) }
    fun cancelRemove() = _uiState.update { it.copy(confirmRemove = false) }
    fun confirmRemoveGame() {
        val game = _uiState.value.game ?: return
        viewModelScope.launch {
            gameRepository.delete(game.id)
            _uiState.update { it.copy(confirmRemove = false, closed = true) }
        }
    }

    // ── Launch ────────────────────────────────────────────────────────────

    fun launch() {
        val game = _uiState.value.game ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(launchError = null) }

            val profile = resolveProfile(game)
            if (profile == null) {
                _uiState.update { it.copy(launchError = "No emulator installed for ${game.platformId.uppercase()}") }
                return@launch
            }

            val intent = intentResolver.resolve(game, profile)
            if (intent == null) {
                _uiState.update { it.copy(launchError = "Could not build launch intent for ${profile.name}") }
                return@launch
            }

            Timber.i("Launching ${game.title} via ${profile.name}")
            _launchEffect.emit(intent)
        }
    }

    private fun resolveProfile(game: Game) = when {
        // Prefer user's per-game override
        game.emulatorPackage != null -> {
            profileRepository.getInstalledProfiles()
                .firstOrNull { it.packageName == game.emulatorPackage }
        }
        // Otherwise first installed profile that supports this platform
        else -> profileRepository.getProfilesForPlatform(game.platformId).firstOrNull()
    }

    // ── Favorite ──────────────────────────────────────────────────────────

    fun toggleFavorite() {
        val game = _uiState.value.game ?: return
        viewModelScope.launch {
            val next = !game.isFavorite
            gameRepository.setFavorite(game.id, next)
            _uiState.update { it.copy(game = game.copy(isFavorite = next)) }
        }
    }

    // ── Note editing ──────────────────────────────────────────────────────

    fun startEditNote() {
        _uiState.update { it.copy(isEditingNote = true, noteText = it.game?.userNote ?: "") }
    }

    fun onNoteChanged(text: String) {
        _uiState.update { it.copy(noteText = text) }
    }

    fun saveNote() {
        val game = _uiState.value.game ?: return
        val note = _uiState.value.noteText.trim().ifEmpty { null }
        viewModelScope.launch {
            gameRepository.updateNote(game.id, note)
            _uiState.update { it.copy(game = game.copy(userNote = note), isEditingNote = false) }
        }
    }

    fun cancelNote() {
        _uiState.update { it.copy(isEditingNote = false, noteText = _uiState.value.game?.userNote ?: "") }
    }

    // ── Artwork ───────────────────────────────────────────────────────────

    fun fetchArtwork() {
        val game = _uiState.value.game ?: return
        if (_uiState.value.isFetchingArtwork) return
        viewModelScope.launch {
            _uiState.update { it.copy(isFetchingArtwork = true, artworkMessage = null) }
            val result = artworkRepository.fetchArtworkForGame(game.id, game.title)
            // Reload game so the new artworkUri is reflected in state
            val updated = gameRepository.getById(game.id)
            _uiState.update {
                it.copy(
                    game             = updated ?: it.game,
                    mediaUris        = mediaOf(updated ?: it.game),
                    isFetchingArtwork = false,
                    artworkMessage   = when {
                        result.success  -> "Artwork updated"
                        result.skipped  -> "Already has artwork"
                        else            -> result.errorMessage ?: "Artwork fetch failed"
                    },
                )
            }
        }
    }

    fun dismissArtworkMessage() = _uiState.update { it.copy(artworkMessage = null) }
    fun dismissLaunchError()    = _uiState.update { it.copy(launchError = null) }

    private fun mediaOf(game: Game?): List<String> =
        listOfNotNull(game?.heroUri, game?.artworkUri, game?.logoUri).distinct()

    // ── Custom artwork ────────────────────────────────────────────────────

    fun toggleCustomArtworkPanel() =
        _uiState.update { it.copy(showCustomArtwork = !it.showCustomArtwork) }

    fun onCustomArtworkPicked(uri: Uri, type: ArtworkType) {
        val gameId = _uiState.value.game?.id ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessingCustomArtwork = true, artworkMessage = null) }
            val localPath = copyToAppStorage(uri, gameId, type)
            if (localPath == null) {
                _uiState.update {
                    it.copy(isProcessingCustomArtwork = false, artworkMessage = "Could not copy image")
                }
                return@launch
            }
            when (type) {
                ArtworkType.BOX_ART -> gameRepository.updateBoxArt(gameId, localPath)
                ArtworkType.HERO    -> gameRepository.updateHeroArt(gameId, localPath)
                ArtworkType.LOGO    -> gameRepository.updateLogoArt(gameId, localPath)
            }
            val updated = gameRepository.getById(gameId)
            _uiState.update {
                it.copy(
                    game                    = updated ?: it.game,
                    mediaUris               = mediaOf(updated ?: it.game),
                    isProcessingCustomArtwork = false,
                    artworkMessage          = "${type.label} updated",
                )
            }
        }
    }

    fun clearArtwork(type: ArtworkType) {
        val gameId = _uiState.value.game?.id ?: return
        viewModelScope.launch {
            when (type) {
                ArtworkType.BOX_ART -> gameRepository.updateBoxArt(gameId, null)
                ArtworkType.HERO    -> gameRepository.updateHeroArt(gameId, null)
                ArtworkType.LOGO    -> gameRepository.updateLogoArt(gameId, null)
            }
            val updated = gameRepository.getById(gameId)
            _uiState.update { it.copy(game = updated ?: it.game, mediaUris = mediaOf(updated ?: it.game), artworkMessage = "${type.label} cleared") }
        }
    }

    // ── SteamGridDB inline picker ─────────────────────────────────────────

    fun openSgdbPicker(type: ArtworkType) {
        val game = _uiState.value.game ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(sgdbBrowsingType = type, sgdbIsLoading = true, sgdbItems = emptyList(), sgdbError = null)
            }
            val matches = steamGridDb.searchGame(game.title).getOrElse { e ->
                _uiState.update { it.copy(sgdbIsLoading = false, sgdbError = "Search failed: ${e.message}") }
                return@launch
            }
            val sgdbGame = matches.firstOrNull() ?: run {
                _uiState.update { it.copy(sgdbIsLoading = false, sgdbError = "No results for \"${game.title}\"") }
                return@launch
            }
            val items = steamGridDb.getArt(sgdbGame.id, type.toSgdbArtType()).getOrElse { e ->
                _uiState.update { it.copy(sgdbIsLoading = false, sgdbError = "Could not load artwork: ${e.message}") }
                return@launch
            }
            _uiState.update { it.copy(sgdbIsLoading = false, sgdbItems = items) }
        }
    }

    fun pickSgdbArtwork(url: String, type: ArtworkType) {
        val game = _uiState.value.game ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessingCustomArtwork = true, sgdbBrowsingType = null, sgdbItems = emptyList()) }

            // Box art → composite into platform card template and save as PNG.
            // Hero/logo → download to local file as-is (displayed full-screen / overlay).
            val localPath: String? = when (type) {
                ArtworkType.BOX_ART -> cardProcessor.processBoxArt(game.id, game.platformId, url)
                ArtworkType.HERO    -> cardProcessor.downloadRaw(game.id, url, "hero.jpg")
                ArtworkType.LOGO    -> cardProcessor.downloadRaw(game.id, url, "logo.png", asPng = true)
            }

            val savedPath = localPath ?: url   // fall back to URL if processing failed
            when (type) {
                ArtworkType.BOX_ART -> gameRepository.updateBoxArt(game.id, savedPath)
                ArtworkType.HERO    -> gameRepository.updateHeroArt(game.id, savedPath)
                ArtworkType.LOGO    -> gameRepository.updateLogoArt(game.id, savedPath)
            }
            val updated = gameRepository.getById(game.id)
            _uiState.update {
                it.copy(
                    game                    = updated ?: it.game,
                    mediaUris               = mediaOf(updated ?: it.game),
                    isProcessingCustomArtwork = false,
                    artworkMessage          = "${type.label} updated from SteamGridDB",
                )
            }
        }
    }

    fun closeSgdbPicker() {
        _uiState.update { it.copy(sgdbBrowsingType = null, sgdbItems = emptyList(), sgdbError = null) }
    }

    private fun ArtworkType.toSgdbArtType() = when (this) {
        ArtworkType.BOX_ART -> SgdbArtType.GRID
        ArtworkType.HERO    -> SgdbArtType.HERO
        ArtworkType.LOGO    -> SgdbArtType.LOGO
    }

    private suspend fun copyToAppStorage(uri: Uri, gameId: Long, type: ArtworkType): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.filesDir, "artwork/$gameId").also { it.mkdirs() }
                val dest = File(dir, "${type.fileName}.jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                dest.absolutePath
            }.getOrElse {
                Timber.e(it, "Failed to copy custom artwork for game $gameId type $type")
                null
            }
        }

    private val ArtworkType.label: String
        get() = when (this) {
            ArtworkType.BOX_ART -> "Box art"
            ArtworkType.HERO    -> "Hero banner"
            ArtworkType.LOGO    -> "Logo"
        }

    private val ArtworkType.fileName: String
        get() = when (this) {
            ArtworkType.BOX_ART -> "box_art"
            ArtworkType.HERO    -> "hero"
            ArtworkType.LOGO    -> "logo"
        }
}
