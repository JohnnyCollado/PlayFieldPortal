package com.playfieldportal.feature.xmb.ui.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.artwork.api.SgdbArtType
import com.playfieldportal.feature.artwork.api.SteamGridDbApi
import com.playfieldportal.feature.artwork.api.SgdbApiKeyProvider
import com.playfieldportal.feature.artwork.card.CardArtworkProcessor
import com.playfieldportal.feature.xmb.ui.detail.ArtPickerItem
import com.playfieldportal.feature.xmb.ui.detail.ArtworkType
import com.playfieldportal.feature.xmb.ui.detail.displayLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.InputStream
import javax.inject.Inject

// Number of focusable actions on the main screen (Launch, Rename, Icon, Hero, Background, Reset All)
const val APP_ACTION_COUNT = 6

data class AppDetailUiState(
    val game: Game? = null,
    val isLoading: Boolean = true,
    val mainFocus: Int = 0,
    // Artwork picker overlay
    val showArtworkPicker: Boolean = false,
    val artworkPickerType: ArtworkType = ArtworkType.ICON,
    val artworkPickerLoading: Boolean = false,
    val artworkPickerItems: List<ArtPickerItem> = emptyList(),
    val artworkPickerFocus: Int = 0,
    val artworkPickerError: String? = null,
    val artworkIsProcessing: Boolean = false,
    val artworkMessage: String? = null,
    val artworkPendingLocal: ArtworkType? = null,
    val isEditingName: Boolean = false,
    val nameText: String = "",
    val closed: Boolean = false,
)

@HiltViewModel
class AppDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gameRepository: GameRepository,
    private val steamGridDb: SteamGridDbApi,
    private val sgdbKeyProvider: SgdbApiKeyProvider,
    private val cardProcessor: CardArtworkProcessor,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppDetailUiState())
    val uiState: StateFlow<AppDetailUiState> = _uiState.asStateFlow()

    fun prepareForOpen() {
        _uiState.value = AppDetailUiState()
    }

    fun loadApp(gameId: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val game = gameRepository.getById(gameId)
            _uiState.update { it.copy(game = game, isLoading = false) }
        }
    }

    fun launchApp() {
        val pkg = _uiState.value.game?.packageName ?: return
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                ?: Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setPackage(pkg)
                }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Timber.w(e, "Could not launch app: $pkg")
            _uiState.update { it.copy(artworkMessage = "Could not launch app") }
        }
    }

    fun openArtworkPickerFor(type: ArtworkType) {
        _uiState.update {
            it.copy(
                showArtworkPicker    = true,
                artworkPickerType    = type,
                artworkPickerItems   = emptyList(),
                artworkPickerFocus   = 0,
                artworkPickerError   = null,
                artworkPickerLoading = false,
                artworkMessage       = null,
            )
        }
        loadSgdbForType(type)
    }

    private fun loadSgdbForType(type: ArtworkType) {
        val game = _uiState.value.game ?: return
        val searchName = game.displayTitle
        viewModelScope.launch {
            _uiState.update { it.copy(artworkPickerLoading = true, artworkPickerError = null) }
            val sgdbKey = sgdbKeyProvider.getKey()
            if (sgdbKey.isNullOrBlank()) {
                _uiState.update {
                    it.copy(
                        artworkPickerLoading = false,
                        artworkPickerError   = "SteamGridDB API key not configured — add one in Artwork Settings",
                    )
                }
                return@launch
            }
            val match = steamGridDb.searchGame(searchName).getOrElse { e ->
                Timber.w(e, "SGDB search failed for '$searchName'")
                _uiState.update {
                    it.copy(artworkPickerLoading = false, artworkPickerError = "Search failed: ${e.message}")
                }
                return@launch
            }.firstOrNull()

            if (match == null) {
                _uiState.update {
                    it.copy(
                        artworkPickerLoading = false,
                        artworkPickerError   = "No results for \"$searchName\" on SteamGridDB",
                    )
                }
                return@launch
            }

            val arts = steamGridDb.getArt(match.id, type.toSgdbArtType()).getOrElse { e ->
                Timber.w(e, "SGDB getArt failed for ${type.displayLabel}")
                _uiState.update {
                    it.copy(artworkPickerLoading = false, artworkPickerError = "Failed to load artwork: ${e.message}")
                }
                return@launch
            }

            val items = arts.map { ArtPickerItem(url = it.url, thumbUrl = it.thumb) }
            if (items.isEmpty()) {
                _uiState.update {
                    it.copy(
                        artworkPickerLoading = false,
                        artworkPickerError   = "No ${type.displayLabel} art found on SteamGridDB",
                    )
                }
                return@launch
            }
            _uiState.update {
                it.copy(artworkPickerLoading = false, artworkPickerItems = items, artworkPickerFocus = 0)
            }
        }
    }

    fun closeArtworkPicker() {
        _uiState.update { it.copy(showArtworkPicker = false, artworkPickerItems = emptyList()) }
    }

    fun onSgdbArtSelected(url: String) {
        val game = _uiState.value.game ?: return
        val type = _uiState.value.artworkPickerType
        viewModelScope.launch {
            _uiState.update { it.copy(artworkIsProcessing = true, artworkPickerItems = emptyList()) }
            val localPath = cardProcessor.downloadRaw(game.id, url, artworkFileName(type))
            if (localPath == null) {
                _uiState.update {
                    it.copy(artworkIsProcessing = false, artworkMessage = "Could not download ${type.displayLabel}")
                }
                return@launch
            }
            saveArtwork(game.id, type, localPath)
            val updated = gameRepository.getById(game.id)
            _uiState.update {
                it.copy(
                    game               = updated ?: it.game,
                    artworkIsProcessing = false,
                    artworkMessage      = "${type.displayLabel} updated",
                    showArtworkPicker   = false,
                )
            }
        }
    }

    fun requestLocalFilePick(type: ArtworkType) {
        _uiState.update { it.copy(artworkPendingLocal = type) }
    }

    fun consumeLocalFilePick() {
        _uiState.update { it.copy(artworkPendingLocal = null) }
    }

    fun onLocalFilePicked(uri: Uri, type: ArtworkType) {
        val game = _uiState.value.game ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(artworkIsProcessing = true) }
            val localPath = copyToAppStorage(uri, game.id, type)
            if (localPath == null) {
                _uiState.update {
                    it.copy(artworkIsProcessing = false, artworkMessage = "Could not import ${type.displayLabel}")
                }
                return@launch
            }
            saveArtwork(game.id, type, localPath)
            val updated = gameRepository.getById(game.id)
            _uiState.update {
                it.copy(
                    game               = updated ?: it.game,
                    artworkIsProcessing = false,
                    artworkMessage      = "${type.displayLabel} updated",
                    showArtworkPicker   = false,
                )
            }
        }
    }

    fun clearArtwork(type: ArtworkType) {
        val gameId = _uiState.value.game?.id ?: return
        viewModelScope.launch {
            saveArtwork(gameId, type, null)
            val updated = gameRepository.getById(gameId)
            _uiState.update {
                it.copy(
                    game              = updated ?: it.game,
                    artworkMessage    = "${type.displayLabel} reset",
                    showArtworkPicker = false,
                )
            }
        }
    }

    fun clearAllArtwork() {
        val gameId = _uiState.value.game?.id ?: return
        viewModelScope.launch {
            gameRepository.updateIconArt(gameId, null)
            gameRepository.updateHeroArt(gameId, null)
            gameRepository.updateBoxArt(gameId, null)
            val updated = gameRepository.getById(gameId)
            _uiState.update {
                it.copy(game = updated ?: it.game, artworkMessage = "All artwork reset")
            }
        }
    }

    // ── Display name editing ──────────────────────────────────────────────────

    fun startEditingName() {
        val current = _uiState.value.game?.displayTitle ?: ""
        _uiState.update { it.copy(isEditingName = true, nameText = current) }
    }

    fun onNameTextChanged(text: String) {
        _uiState.update { it.copy(nameText = text) }
    }

    fun confirmNameEdit() {
        val gameId = _uiState.value.game?.id ?: return
        val newName = _uiState.value.nameText.trim()
        viewModelScope.launch {
            gameRepository.updateUserTitleOverride(gameId, newName.ifBlank { null })
            val updated = gameRepository.getById(gameId)
            _uiState.update { it.copy(game = updated ?: it.game, isEditingName = false) }
        }
    }

    fun resetNameToDefault() {
        val gameId = _uiState.value.game?.id ?: return
        viewModelScope.launch {
            gameRepository.updateUserTitleOverride(gameId, null)
            val updated = gameRepository.getById(gameId)
            _uiState.update { it.copy(game = updated ?: it.game, isEditingName = false) }
        }
    }

    fun cancelNameEdit() {
        _uiState.update { it.copy(isEditingName = false) }
    }

    // ── Gamepad ───────────────────────────────────────────────────────────────

    fun handleGamepadAction(action: GamepadAction) {
        if (_uiState.value.isEditingName) {
            if (action == GamepadAction.BACK) cancelNameEdit()
            return
        }
        if (_uiState.value.showArtworkPicker) {
            handlePickerGamepad(action)
        } else {
            handleMainGamepad(action)
        }
    }

    private fun handleMainGamepad(action: GamepadAction) {
        when (action) {
            GamepadAction.NAVIGATE_DOWN -> _uiState.update {
                it.copy(mainFocus = (it.mainFocus + 1).coerceAtMost(APP_ACTION_COUNT - 1))
            }
            GamepadAction.NAVIGATE_UP -> _uiState.update {
                it.copy(mainFocus = (it.mainFocus - 1).coerceAtLeast(0))
            }
            GamepadAction.SELECT -> activateMainFocus()
            GamepadAction.BACK   -> close()
            else -> Unit
        }
    }

    private fun handlePickerGamepad(action: GamepadAction) {
        val items = _uiState.value.artworkPickerItems
        when (action) {
            GamepadAction.NAVIGATE_LEFT -> _uiState.update {
                it.copy(artworkPickerFocus = (it.artworkPickerFocus - 1).coerceAtLeast(0))
            }
            GamepadAction.NAVIGATE_RIGHT -> _uiState.update {
                it.copy(artworkPickerFocus = (it.artworkPickerFocus + 1).coerceAtMost((items.size - 1).coerceAtLeast(0)))
            }
            GamepadAction.SELECT -> {
                val item = items.getOrNull(_uiState.value.artworkPickerFocus) ?: return
                onSgdbArtSelected(item.url)
            }
            GamepadAction.BACK -> closeArtworkPicker()
            else -> Unit
        }
    }

    private fun activateMainFocus() {
        when (_uiState.value.mainFocus) {
            0 -> launchApp()
            1 -> startEditingName()
            2 -> openArtworkPickerFor(ArtworkType.ICON)
            3 -> openArtworkPickerFor(ArtworkType.HERO)
            4 -> openArtworkPickerFor(ArtworkType.BACKGROUND)
            5 -> clearAllArtwork()
        }
    }

    fun close() {
        _uiState.update { it.copy(closed = true) }
    }

    private suspend fun saveArtwork(gameId: Long, type: ArtworkType, path: String?) {
        when (type) {
            ArtworkType.ICON       -> gameRepository.updateIconArt(gameId, path)
            ArtworkType.HERO       -> gameRepository.updateHeroArt(gameId, path)
            ArtworkType.BACKGROUND -> gameRepository.updateBoxArt(gameId, path)
        }
    }

    private fun artworkFileName(type: ArtworkType): String = when (type) {
        ArtworkType.ICON       -> "icon.jpg"
        ArtworkType.HERO       -> "hero.jpg"
        ArtworkType.BACKGROUND -> "background.jpg"
    }

    private fun ArtworkType.toSgdbArtType() = when (this) {
        ArtworkType.ICON       -> SgdbArtType.GRID
        ArtworkType.HERO       -> SgdbArtType.HERO
        ArtworkType.BACKGROUND -> SgdbArtType.HERO
    }

    private fun copyToAppStorage(uri: Uri, gameId: Long, type: ArtworkType): String? = try {
        val dest = cardProcessor.rawFile(gameId, artworkFileName(type))
        dest.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri)?.use { input: InputStream ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        dest.absolutePath
    } catch (e: Exception) {
        Timber.w(e, "Failed to copy local artwork for gameId=$gameId type=$type")
        null
    }
}
