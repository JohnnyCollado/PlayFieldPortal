package com.playfieldportal.feature.settings.viewmodel

import java.io.File
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import com.playfieldportal.themekit.ThemeIconChoices
import com.playfieldportal.themekit.PtfIcons
import com.playfieldportal.core.ui.icons.UserCardIconKeys
import com.playfieldportal.core.ui.icons.CustomIcon
import com.playfieldportal.core.data.repository.ThemeTiers
import com.playfieldportal.core.data.repository.CustomIconStore
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.repository.CollectionRepository
import com.playfieldportal.core.data.repository.CategoryRepositoryImpl
import com.playfieldportal.core.domain.model.cardCategories
import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GameCollection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CollectionsSettingsViewModel @Inject constructor(
    private val collectionRepository: CollectionRepository,
    private val categoryRepository: CategoryRepositoryImpl,
    // A card's own image lives in the custom icon store under usercard_<id>, beside category images.
    private val customIconStore: CustomIconStore,
    private val themeTiers: ThemeTiers,
) : ViewModel() {

    /** Which icon-store keys hold a file: "does this card have its own image?" without a decode. */
    val storedIconKeys: StateFlow<Set<String>> =
        customIconStore.observeStoredKeys()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _iconMessage = MutableStateFlow<String?>(null)
    /** Why the last image did not land (the store's own words), or null. */
    val iconMessage: StateFlow<String?> = _iconMessage.asStateFlow()

    private val _themeIcons = MutableStateFlow<List<CardThemeIcon>>(emptyList())
    /** The applied theme's icons, offered as a card icon; empty when the theme has none. */
    val themeIcons: StateFlow<List<CardThemeIcon>> = _themeIcons.asStateFlow()

    val collections: StateFlow<List<GameCollection>> =
        collectionRepository.observeCollections()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Every category a new card can go in: gaming ones, then app ones (Network, App Store, custom
     * non-gaming). Gaming-only here meant a non-gaming card could not be made from Settings at all
     * (survey L28, docs/audits/codebase-survey-2026-10-22.md).
     */
    val cardCategories: StateFlow<List<Category>> =
        categoryRepository.observeAll()
            .map(::cardCategories)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun gamesIn(collectionId: Long): Flow<List<Game>> =
        collectionRepository.observeGames(collectionId)

    fun create(name: String, categoryId: String = "games") {
        if (name.isBlank()) return
        viewModelScope.launch { collectionRepository.create(name, categoryId) }
    }

    fun rename(id: Long, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { collectionRepository.rename(id, name) }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            collectionRepository.delete(id)
            // The card's own image goes with it (the startup sweep would catch it too, later).
            UserCardIconKeys.keyFor(id)?.let { customIconStore.clear(it) }
        }
    }

    /**
     * Sets the card's built-in icon (a key from the shared category icon catalog; null is the
     * default memory-card art). Choosing one clears the card's own image, so only one is active.
     */
    fun setIcon(id: Long, iconKey: String?) {
        viewModelScope.launch {
            collectionRepository.setIcon(id, iconKey)
            UserCardIconKeys.keyFor(id)?.let { customIconStore.clear(it) }
        }
    }

    /** Uses the picked file (still or animated) as the card's own image; a rejection says why. */
    fun importCardImage(collectionId: Long, uri: Uri, mime: String?) {
        val key = UserCardIconKeys.keyFor(collectionId) ?: return
        viewModelScope.launch {
            val result = customIconStore.import(key, uri, mime)
            _iconMessage.value = if (result.ok) null else result.message ?: "That image could not be used."
        }
    }

    /** Uses one of the applied theme's icons: copied into the card's own slot, so it outlives the theme. */
    fun chooseThemeIcon(collectionId: Long, file: File) {
        importCardImage(collectionId, Uri.fromFile(file), iconMimeFor(file))
    }

    /** Drops the card's own image; the built-in choice (or the memory-card default) shows again. */
    fun removeCardImage(collectionId: Long) {
        val key = UserCardIconKeys.keyFor(collectionId) ?: return
        viewModelScope.launch { customIconStore.clear(key) }
    }

    fun dismissIconMessage() {
        _iconMessage.value = null
    }

    /** Reads the applied theme's icons for the Change Icon page; called when it opens. */
    fun loadThemeIcons() {
        viewModelScope.launch {
            _themeIcons.value = withContext(Dispatchers.IO) {
                val tier = ThemeTiers.Tier.THEME
                val slotFiles = themeTiers.iconKeys(tier)
                    .mapNotNull { key -> themeTiers.iconFile(tier, key)?.let { key to it } }.toMap()
                val slotIcons = themeTiers.loadIcons(tier)
                    .mapNotNull { (key, icon) -> slotFiles[key]?.let { key to (it to icon) } }.toMap()
                val ptfFiles = themeTiers.ptfIconFiles()
                val ptfIcons = themeTiers.loadPtfIcons()
                    .mapNotNull { (ref, icon) -> ptfFiles[ref]?.let { ref to (it to icon) } }.toMap()
                cardThemeIconRows(slotIcons, ptfIcons)
            }
        }
    }

    fun moveUp(id: Long) {
        viewModelScope.launch { collectionRepository.move(id, up = true) }
    }

    fun moveDown(id: Long) {
        viewModelScope.launch { collectionRepository.move(id, up = false) }
    }

    fun removeGame(collectionId: Long, gameId: Long) {
        viewModelScope.launch { collectionRepository.removeGame(collectionId, gameId) }
    }
}

/** One of the applied theme's icons offered as a card icon: its name, its image, and the file to copy. */
data class CardThemeIcon(val label: String, val icon: CustomIcon, val file: File)

/**
 * The applied theme's icons as card icon choices, named and ordered by [ThemeIconChoices], the
 * list the XMB icon editor's theme grid shows. Identical images (a PSP theme's Memory Stick art
 * fills several slots) are offered once.
 */
internal fun cardThemeIconRows(
    slotIcons: Map<String, Pair<File, CustomIcon>>,
    ptfIcons: Map<PtfIcons.SlotRef, Pair<File, CustomIcon>>,
): List<CardThemeIcon> {
    val seen = HashSet<String>()
    fun firstOfItsImage(file: File): Boolean = seen.add(
        runCatching {
            java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { b -> "%02x".format(b) }
        }.getOrDefault(file.absolutePath),
    )
    return ThemeIconChoices.sections(slotIcons.keys, ptfIcons.keys)
        .flatMap { it.choices }
        .mapNotNull { choice ->
            val entry = when (val src = choice.source) {
                is ThemeIconChoices.Source.Slot -> slotIcons[src.key]
                is ThemeIconChoices.Source.Ptf -> ptfIcons[src.ref]
            } ?: return@mapNotNull null
            if (!firstOfItsImage(entry.first)) return@mapNotNull null
            CardThemeIcon(choice.label, entry.second, entry.first)
        }
}

/** The MIME the icon store expects for a stored icon file, by its extension. */
internal fun iconMimeFor(file: File): String? = when (file.extension.lowercase()) {
    "png" -> "image/png"
    "gif" -> "image/gif"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "bmp" -> "image/bmp"
    "heif" -> "image/heif"
    else -> null
}
