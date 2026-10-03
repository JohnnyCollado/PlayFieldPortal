package com.playfieldportal.feature.settings.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.repository.CategoryRepositoryImpl
import com.playfieldportal.core.data.repository.CollectionRepository
import com.playfieldportal.core.data.repository.CollectionsOnDelete
import com.playfieldportal.core.data.repository.CustomIconStore
import com.playfieldportal.core.domain.model.BuiltInCategory
import com.playfieldportal.core.domain.model.CategoryType
import com.playfieldportal.core.ui.icons.CATEGORY_ICON_CATALOG
import com.playfieldportal.core.ui.icons.FALLBACK_CATEGORY_ICON
import com.playfieldportal.core.ui.icons.UserCategoryIconKeys
import com.playfieldportal.core.ui.icons.categoryIconFor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class CategoryStep { LIST, PICK_ICON, PICK_TYPE, DETAIL }

// Hidden holder categories some older builds created to store Music/Video/Photo "Apps" picks. The
// app sections now use the real built-in media categories, but a legacy row may still exist; it must
// never be shown as an editable category.
private val LEGACY_APP_PSEUDO_CATEGORY_IDS = setOf("music_apps", "video_apps", "photo_apps")

data class CategoryRow(
    val id: String,
    val name: String,
    val iconKey: String,
    val visible: Boolean,
    val protected: Boolean,
    val isGamingCategory: Boolean = false,
    // Settings is the only route back into category management, so it can never be hidden from the
    // XMB bar — the "Show On Bar" toggle is suppressed for it.
    val canHide: Boolean = true,
    // Custom memory cards homed in this category — deleting it has to say what becomes of them.
    val customCardCount: Int = 0,
    // The column those cards would move to if the category is deleted and they are kept.
    val cardHomeName: String = "",
    // A picked device image is assigned by file presence (the store's directory is the truth); the
    // row's iconKey stays as the built-in it falls back to when the image is removed.
    val hasImage: Boolean = false,
    // What the detail "Change Icon" value and the list sublabel print instead of the raw key.
    val iconLabel: String = "",
    // The store key a device image for this category lives under; null for protected categories and
    // ids that don't fit the key pattern, which are never offered "From Your Device".
    val deviceImageKey: String? = null,
)

/** Readable value for a category's icon: the image when it has one, else the catalog label. */
fun iconValueLabel(iconKey: String, hasImage: Boolean): String =
    if (hasImage) "Your Image" else categoryIconFor(iconKey).label

data class IconOption(val key: String, val label: String)

// Selectable category icons, sourced from the shared core-ui catalog (the 7 XMB glyphs plus every
// bundled console icon). No sprite sheet — each entry renders from its own resource.
val ICON_OPTIONS: List<IconOption> = CATEGORY_ICON_CATALOG.map { IconOption(it.key, it.label) }

data class CategoryManagerUiState(
    val step: CategoryStep = CategoryStep.LIST,
    val categories: List<CategoryRow> = emptyList(),
    val iconOptions: List<IconOption> = ICON_OPTIONS,
    val detailId: String? = null,
    // Create flow scratch
    val pendingName: String? = null,
    val pendingIconKey: String? = null,
    val pickingIconForCreate: Boolean = false,
    val pendingIsGamingCategory: Boolean = false,
    val pickingTypeForCreate: Boolean = false,
    // The create flow's image lives under the draft key until the category has an id.
    val pendingHasImage: Boolean = false,
    // Dialogs
    val showCreateNameDialog: Boolean = false,
    val renameTargetId: String? = null,
    val returnFocusKey: String? = null,
    // Why the last device-image pick was rejected; shown under the "From Your Device" group.
    val message: String? = null,
) {
    val detail: CategoryRow? get() = categories.firstOrNull { it.id == detailId }
}

const val CREATE_CATEGORY_FOCUS_KEY = "create_category"

enum class CategoryManagerTargetAction { RENAME, CHANGE_ICON }

/** Where the XMB's category menu sends Category Manager: one category, with one of its edits already up. */
data class CategoryManagerTarget(val categoryId: String, val action: CategoryManagerTargetAction)

@HiltViewModel
class CategoryManagerViewModel @Inject constructor(
    private val categoryRepository: CategoryRepositoryImpl,
    private val collectionRepository: CollectionRepository,
    private val customIconStore: CustomIconStore,
) : ViewModel() {

    private val _scratch = MutableStateFlow(CategoryManagerUiState())

    val uiState: StateFlow<CategoryManagerUiState> = combine(
        categoryRepository.observeAll(),
        collectionRepository.observeCollections(),
        customIconStore.observeStoredKeys(),
        _scratch,
    ) { categories, collections, storedKeys, scratch ->
        val cardCounts = collections.groupingBy { it.categoryId }.eachCount()
        val names = categories.associate { it.id to it.name }
        scratch.copy(
            // Legacy hidden "*_apps" pseudo-categories (from older builds) are never user-editable —
            // keep them out of the manager so they can't be renamed/deleted/toggled.
            categories = categories.filterNot { it.id in LEGACY_APP_PSEUDO_CATEGORY_IDS }.map {
                val protected = categoryRepository.isProtected(it.id)
                val deviceImageKey = if (protected) null else UserCategoryIconKeys.keyFor(it.id)
                val hasImage = deviceImageKey != null && deviceImageKey in storedKeys
                CategoryRow(
                    id                 = it.id,
                    name               = it.name,
                    iconKey            = it.iconKey,
                    visible            = it.isVisible,
                    protected          = protected,
                    isGamingCategory   = it.isGamingCategory,
                    canHide            = it.id != BuiltInCategory.SETTINGS,
                    customCardCount    = cardCounts[it.id] ?: 0,
                    cardHomeName       = CategoryRepositoryImpl.collectionHomeFor(it.isGamingCategory)
                        .let { home -> names[home] ?: if (it.isGamingCategory) "Game" else "App Store" },
                    hasImage           = hasImage,
                    iconLabel          = iconValueLabel(it.iconKey, hasImage),
                    deviceImageKey     = deviceImageKey,
                )
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoryManagerUiState())

    fun onBack(): Boolean {
        val s = _scratch.value
        if (s.step == CategoryStep.LIST) return false
        if (s.step == CategoryStep.PICK_TYPE) {
            _scratch.update {
                it.copy(step = CategoryStep.PICK_ICON, pickingTypeForCreate = false)
            }
            return true
        }
        // Cancelling the create flow abandons its draft image; the startup sweep covers a killed process.
        if (s.pickingIconForCreate && s.pendingHasImage) {
            viewModelScope.launch { customIconStore.clear(UserCategoryIconKeys.DRAFT_KEY) }
        }
        _scratch.update {
            it.copy(
                step = CategoryStep.LIST,
                pendingName = null,
                pendingIconKey = null,
                pendingHasImage = false,
                message = null,
                pickingIconForCreate = false,
                pickingTypeForCreate = false,
                detailId = null,
                returnFocusKey = it.returnFocusKey,
            )
        }
        return true
    }

    // ── Create flow ───────────────────────────────────────────────────────────────

    fun startCreate() = _scratch.update { it.copy(showCreateNameDialog = true, returnFocusKey = CREATE_CATEGORY_FOCUS_KEY) }
    fun cancelCreateName() = _scratch.update { it.copy(showCreateNameDialog = false) }

    fun confirmCreateName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) { _scratch.update { it.copy(showCreateNameDialog = false) }; return }
        _scratch.update {
            it.copy(
                showCreateNameDialog = false,
                pendingName          = trimmed,
                pickingIconForCreate = true,
                step                 = CategoryStep.PICK_ICON,
            )
        }
    }

    fun chooseIcon(iconKey: String) {
        val s = _scratch.value
        viewModelScope.launch {
            // A built-in pick replaces any device image � only one active choice ever.
            if (s.pickingIconForCreate) {
                if (s.pendingHasImage) customIconStore.clear(UserCategoryIconKeys.DRAFT_KEY)
                _scratch.update {
                    it.copy(
                        step = CategoryStep.PICK_TYPE,
                        pendingIconKey = iconKey,
                        pendingHasImage = false,
                        message = null,
                        pickingTypeForCreate = true,
                    )
                }
            } else {
                val id = s.detailId ?: return@launch
                categoryRepository.setIcon(id, iconKey)
                UserCategoryIconKeys.keyFor(id)?.let { customIconStore.clear(it) }
                _scratch.update { it.copy(step = CategoryStep.DETAIL, message = null) }
            }
        }
    }

    /**
     * The picked file goes through the store's own gate. In the create flow it lands on the draft key
     * (the category has no id yet) so a rejection is reported on this step; in Change Icon it lands on
     * the category's key. A rejection keeps whatever icon was already in place.
     */
    fun onDeviceImagePicked(uri: Uri, mime: String?) {
        val s = _scratch.value
        val key = deviceImageTargetKey(s) ?: return
        viewModelScope.launch {
            val result = customIconStore.import(key, uri, mime)
            if (!result.ok) {
                _scratch.update { it.copy(message = result.message) }
                return@launch
            }
            _scratch.update {
                if (s.pickingIconForCreate) {
                    it.copy(
                        step = CategoryStep.PICK_TYPE,
                        pendingHasImage = true,
                        message = null,
                        pickingTypeForCreate = true,
                    )
                } else {
                    it.copy(step = CategoryStep.DETAIL, message = null)
                }
            }
        }
    }

    /** Drops the device image so the category shows its built-in icon again. */
    fun removeDeviceImage() {
        val s = _scratch.value
        val key = deviceImageTargetKey(s) ?: return
        viewModelScope.launch {
            customIconStore.clear(key)
            _scratch.update {
                if (s.pickingIconForCreate) it.copy(pendingHasImage = false, message = null)
                else it.copy(step = CategoryStep.DETAIL, message = null)
            }
        }
    }

    // Where a device image for the current picker target is stored; null when the target must not
    // get one (a protected category, an id that doesn't fit the key pattern, no category open).
    private fun deviceImageTargetKey(s: CategoryManagerUiState): String? {
        if (s.pickingIconForCreate) return UserCategoryIconKeys.DRAFT_KEY
        val id = s.detailId ?: return null
        if (categoryRepository.isProtected(id)) return null
        return UserCategoryIconKeys.keyFor(id)
    }

    fun chooseType(isGaming: Boolean) {
        val s = _scratch.value
        viewModelScope.launch {
            if (s.pickingTypeForCreate) {
                val name = s.pendingName ?: return@launch
                // An image-only category keeps the catalog's own fallback glyph underneath.
                val iconKey = s.pendingIconKey ?: FALLBACK_CATEGORY_ICON.key
                val newId = categoryRepository.createCustomCategory(name, iconKey, isGaming)
                var message: String? = null
                if (s.pendingHasImage) {
                    val target = UserCategoryIconKeys.keyFor(newId)
                    val moved = target != null && customIconStore.move(UserCategoryIconKeys.DRAFT_KEY, target)
                    if (!moved) {
                        // The category exists either way; don't leave the draft behind for the sweep.
                        customIconStore.clear(UserCategoryIconKeys.DRAFT_KEY)
                        message = "Category created, but its image could not be saved"
                    }
                }
                _scratch.update {
                    it.copy(
                        step = CategoryStep.LIST,
                        pendingName = null,
                        pendingIconKey = null,
                        pendingHasImage = false,
                        message = message,
                        pickingIconForCreate = false,
                        pickingTypeForCreate = false,
                        pendingIsGamingCategory = false,
                    )
                }
            }
        }
    }

    // ── Detail / edit ───────────────────────────────────────────────────────────────

    fun openDetail(id: String) = _scratch.update { it.copy(step = CategoryStep.DETAIL, detailId = id, returnFocusKey = id) }

    /** Lands on [target]'s detail page with its rename or icon picker up; the rest of the manager is unchanged. */
    fun openTarget(target: CategoryManagerTarget) {
        openDetail(target.categoryId)
        when (target.action) {
            CategoryManagerTargetAction.RENAME -> beginRename(target.categoryId)
            CategoryManagerTargetAction.CHANGE_ICON -> startChangeIcon()
        }
    }

    fun startChangeIcon() = _scratch.update { it.copy(step = CategoryStep.PICK_ICON, pickingIconForCreate = false, message = null) }

    fun beginRename(id: String) = _scratch.update { it.copy(renameTargetId = id) }
    fun cancelRename() = _scratch.update { it.copy(renameTargetId = null) }
    fun confirmRename(name: String) {
        val id = _scratch.value.renameTargetId ?: return
        viewModelScope.launch {
            if (name.isNotBlank()) categoryRepository.rename(id, name.trim())
            _scratch.update { it.copy(renameTargetId = null) }
        }
    }

    fun toggleVisible(id: String, visible: Boolean) {
        viewModelScope.launch { categoryRepository.setVisible(id, visible) }
    }

    /** Deletes the category; [collections] is the user's answer for its custom memory cards. */
    fun delete(id: String, collections: CollectionsOnDelete = CollectionsOnDelete.MOVE) {
        viewModelScope.launch {
            categoryRepository.delete(id, collections)
            if (_scratch.value.detailId == id) {
                _scratch.update { it.copy(step = CategoryStep.LIST, detailId = null) }
            }
        }
    }
}
