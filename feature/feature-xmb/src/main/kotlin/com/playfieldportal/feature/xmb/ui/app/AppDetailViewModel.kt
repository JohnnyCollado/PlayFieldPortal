package com.playfieldportal.feature.xmb.ui.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.repository.CollectionRepository
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.xmb.ui.collection.CollectionPickerOption
import com.playfieldportal.feature.xmb.ui.collection.CollectionPickerUi
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
import java.io.File
import java.io.InputStream
import javax.inject.Inject

// Mirrors the Game Detail page: the main screen has a single Launch button (mainFocus 0) with the
// Options context menu (mainFocus 1 / Y); everything else lives in that menu.
enum class AppDetailOption(val label: String, val isDestructive: Boolean = false) {
    ADD_TO_COLLECTION("Add to Collection"),
    CHANGE_NAME("Change Display Name"),
    CHANGE_ICON("Change Game Icon"),
    CHANGE_HERO("Change Hero Banner"),
    CHANGE_BACKGROUND("Change Background"),
    RESET_ARTWORK("Reset All Artwork", isDestructive = true),
}

data class AppDetailUiState(
    val game: Game? = null,
    val isLoading: Boolean = true,
    // 0 = Launch, 1 = Options (matches the Game Detail page's Play / Options focus model).
    val mainFocus: Int = 0,
    // Options context menu.
    val showOptions: Boolean = false,
    val optionsIndex: Int = 0,
    // Add-to-collection picker
    val collectionPicker: CollectionPickerUi = CollectionPickerUi(),
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
    private val collectionRepository: CollectionRepository,
    private val steamGridDb: SteamGridDbApi,
    private val sgdbKeyProvider: SgdbApiKeyProvider,
    private val cardProcessor: CardArtworkProcessor,
    private val discordPresence: com.playfieldportal.core.data.discord.DiscordPresenceController,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppDetailUiState())
    val uiState: StateFlow<AppDetailUiState> = _uiState.asStateFlow()

    // Home category for collections created from this screen — set by the caller so a collection
    // made from a Network/App Store/custom app lands in that category, not the Main Game default.
    private var collectionCategoryId: String = "games"

    fun prepareForOpen() {
        _uiState.value = AppDetailUiState()
    }

    fun setCollectionCategory(categoryId: String) {
        collectionCategoryId = categoryId
    }

    fun loadApp(gameId: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val game = gameRepository.getById(gameId)
            _uiState.update { it.copy(game = game, isLoading = false) }
        }
    }

    fun launchApp() {
        val game = _uiState.value.game ?: return
        val pkg = game.packageName ?: return
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                ?: Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setPackage(pkg)
                }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            // Mirror the ROM path: reflect the launch in the opt-in Discord presence (no-op unless
            // Discord is connected and sharing is on). Cleared on return via MainActivity.onResume.
            viewModelScope.launch { discordPresence.setCurrentGame(game.title) }
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
            val localPath = cardProcessor.downloadRaw(game.id, url, versionedArtworkFileName(type))
            if (localPath == null) {
                _uiState.update {
                    it.copy(artworkIsProcessing = false, artworkMessage = "Could not download ${type.displayLabel}")
                }
                return@launch
            }
            saveArtwork(game.id, type, localPath)
            pruneOldArtworkFiles(game.id, type, localPath)
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
            pruneOldArtworkFiles(game.id, type, localPath)
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
        if (_uiState.value.collectionPicker.visible) {
            handleCollectionPickerInput(action)
            return
        }
        if (_uiState.value.showArtworkPicker) {
            handlePickerGamepad(action)
            return
        }
        if (_uiState.value.showOptions) {
            handleOptionsGamepad(action)
            return
        }
        handleMainGamepad(action)
    }

    private fun handleMainGamepad(action: GamepadAction) {
        when (action) {
            // Two focus targets, like the Game Detail page: Launch (0) and Options (1).
            GamepadAction.NAVIGATE_UP   -> _uiState.update { it.copy(mainFocus = 0, artworkMessage = null) }
            GamepadAction.NAVIGATE_DOWN -> _uiState.update { it.copy(mainFocus = 1, artworkMessage = null) }
            GamepadAction.SELECT -> if (_uiState.value.mainFocus == 0) launchApp() else openOptions()
            // Y / Triangle opens the Options context menu directly.
            GamepadAction.BUTTON_Y, GamepadAction.LONG_PRESS -> openOptions()
            GamepadAction.BACK   -> close()
            else -> Unit
        }
    }

    private fun handleOptionsGamepad(action: GamepadAction) {
        val count = AppDetailOption.entries.size
        when (action) {
            GamepadAction.NAVIGATE_UP   -> _uiState.update { it.copy(optionsIndex = (it.optionsIndex - 1).coerceIn(0, count - 1)) }
            GamepadAction.NAVIGATE_DOWN -> _uiState.update { it.copy(optionsIndex = (it.optionsIndex + 1).coerceIn(0, count - 1)) }
            GamepadAction.SELECT        -> activateOption(AppDetailOption.entries[_uiState.value.optionsIndex.coerceIn(0, count - 1)])
            GamepadAction.BACK, GamepadAction.BUTTON_Y, GamepadAction.LONG_PRESS -> closeOptions()
            else -> Unit
        }
    }

    fun openOptions()  = _uiState.update { it.copy(showOptions = true, optionsIndex = 0, artworkMessage = null) }
    fun closeOptions() = _uiState.update { it.copy(showOptions = false) }

    /** Activates an Options-menu row: closes the menu, then runs the corresponding action (which may
     *  open its own overlay — the collection picker, name editor, or artwork picker). */
    fun activateOption(option: AppDetailOption) {
        _uiState.update { it.copy(showOptions = false) }
        when (option) {
            AppDetailOption.ADD_TO_COLLECTION -> openCollectionPicker()
            AppDetailOption.CHANGE_NAME       -> startEditingName()
            AppDetailOption.CHANGE_ICON       -> openArtworkPickerFor(ArtworkType.ICON)
            AppDetailOption.CHANGE_HERO       -> openArtworkPickerFor(ArtworkType.HERO)
            AppDetailOption.CHANGE_BACKGROUND -> openArtworkPickerFor(ArtworkType.BACKGROUND)
            AppDetailOption.RESET_ARTWORK     -> clearAllArtwork()
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

    // ── Add-to-collection picker ──────────────────────────────────────────────

    fun onCollectionsClicked() = openCollectionPicker()

    private fun openCollectionPicker() {
        val gameId = _uiState.value.game?.id ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(collectionPicker = CollectionPickerUi(
                    visible = true,
                    options = buildCollectionOptions(gameId),
                    selectedIndex = 0,
                ))
            }
        }
    }

    private suspend fun buildCollectionOptions(gameId: Long): List<CollectionPickerOption> {
        val memberOf = collectionRepository.getCollectionIdsForGame(gameId).toSet()
        return collectionRepository.getAll().map {
            CollectionPickerOption(id = it.id, name = it.name, checked = it.id in memberOf)
        }
    }

    fun onCollectionRowClick(index: Int) {
        _uiState.update { it.copy(collectionPicker = it.collectionPicker.copy(selectedIndex = index)) }
        activateCollectionRow()
    }

    private fun moveCollectionPicker(delta: Int) {
        _uiState.update {
            val cp = it.collectionPicker
            it.copy(collectionPicker = cp.copy(selectedIndex = (cp.selectedIndex + delta).coerceIn(0, cp.rowCount - 1)))
        }
    }

    private fun activateCollectionRow() {
        val cp = _uiState.value.collectionPicker
        val gameId = _uiState.value.game?.id ?: return
        if (cp.isCreateRow) {
            _uiState.update { it.copy(collectionPicker = it.collectionPicker.copy(showCreateDialog = true, createText = "")) }
            return
        }
        val option = cp.options.getOrNull(cp.selectedIndex) ?: return
        viewModelScope.launch {
            collectionRepository.toggleGame(option.id, gameId)
            _uiState.update { it.copy(collectionPicker = it.collectionPicker.copy(options = buildCollectionOptions(gameId))) }
        }
    }

    fun onCreateCollectionTextChanged(text: String) {
        _uiState.update { it.copy(collectionPicker = it.collectionPicker.copy(createText = text)) }
    }

    fun confirmCreateCollection() {
        val gameId = _uiState.value.game?.id ?: return
        val name = _uiState.value.collectionPicker.createText
        if (name.isBlank()) { cancelCreateCollection(); return }
        viewModelScope.launch {
            val id = collectionRepository.create(name, collectionCategoryId)
            collectionRepository.addGame(id, gameId)
            _uiState.update {
                it.copy(collectionPicker = it.collectionPicker.copy(
                    showCreateDialog = false,
                    createText = "",
                    options = buildCollectionOptions(gameId),
                ))
            }
        }
    }

    fun cancelCreateCollection() {
        _uiState.update { it.copy(collectionPicker = it.collectionPicker.copy(showCreateDialog = false, createText = "")) }
    }

    fun closeCollectionPicker() {
        _uiState.update { it.copy(collectionPicker = CollectionPickerUi()) }
    }

    private fun handleCollectionPickerInput(action: GamepadAction) {
        if (_uiState.value.collectionPicker.showCreateDialog) {
            if (action == GamepadAction.BACK) cancelCreateCollection()
            return
        }
        when (action) {
            GamepadAction.NAVIGATE_UP   -> moveCollectionPicker(-1)
            GamepadAction.NAVIGATE_DOWN -> moveCollectionPicker(+1)
            GamepadAction.SELECT        -> activateCollectionRow()
            GamepadAction.BACK          -> closeCollectionPicker()
            else -> Unit
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

    // Versioned filename (matches GameDetailViewModel): a fresh path on every save is what makes
    // the change visible immediately — the DB row, Compose keys, and Coil's cache key all follow
    // the path string, so a stable name like "background.jpg" would never look different.
    private fun versionedArtworkFileName(type: ArtworkType, ext: String = "jpg"): String =
        "${type.name.lowercase()}_${System.currentTimeMillis()}.$ext"

    // Legacy fixed names written by older builds — pruned alongside stale versioned files.
    private fun legacyArtworkFileName(type: ArtworkType): String = when (type) {
        ArtworkType.ICON       -> "icon.jpg"
        ArtworkType.HERO       -> "hero.jpg"
        ArtworkType.BACKGROUND -> "background.jpg"
    }

    /** Deletes older artwork files of [type] for this app, keeping only [keepPath]. Runs after a
     *  successful save so versioned files never accumulate in filesDir/artwork/<gameId>/. */
    private fun pruneOldArtworkFiles(gameId: Long, type: ArtworkType, keepPath: String) {
        runCatching {
            val dir = File(context.filesDir, "artwork/$gameId")
            val prefix = "${type.name.lowercase()}_"
            val legacy = legacyArtworkFileName(type)
            dir.listFiles()?.forEach { f ->
                val stale = (f.name.startsWith(prefix) || f.name == legacy) && f.absolutePath != keepPath
                if (stale) f.delete()
            }
        }.onFailure { Timber.w(it, "Failed to prune old artwork for gameId=$gameId type=$type") }
    }

    private fun ArtworkType.toSgdbArtType() = when (this) {
        ArtworkType.ICON       -> SgdbArtType.GRID
        ArtworkType.HERO       -> SgdbArtType.HERO
        ArtworkType.BACKGROUND -> SgdbArtType.HERO
    }

    private fun copyToAppStorage(uri: Uri, gameId: Long, type: ArtworkType): String? = try {
        val dest = cardProcessor.rawFile(gameId, versionedArtworkFileName(type))
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
