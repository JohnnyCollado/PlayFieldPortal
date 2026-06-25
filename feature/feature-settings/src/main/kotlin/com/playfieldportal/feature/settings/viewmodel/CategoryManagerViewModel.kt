package com.playfieldportal.feature.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.repository.CategoryRepositoryImpl
import com.playfieldportal.core.domain.model.CategoryType
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

data class CategoryRow(
    val id: String,
    val name: String,
    val iconKey: String,
    val visible: Boolean,
    val protected: Boolean,
    val isGamingCategory: Boolean = false,
)

data class IconOption(val key: String, val label: String)

// Selectable category icons (mapped to the XMB sprite sheet keys).
val ICON_OPTIONS = listOf(
    IconOption("ic_settings", "Settings (toolbox)"),
    IconOption("ic_photos",   "Photo / Share"),
    IconOption("ic_appstore", "App Store (rings)"),
    IconOption("ic_music",    "Music (note)"),
    IconOption("ic_videos",   "Video (film)"),
    IconOption("ic_games",    "Controller"),
    IconOption("ic_network",  "Globe"),
    IconOption("ic_arcade",   "Arcade"),
    IconOption("ic_snes",     "SNES"),
    IconOption("ic_n64",      "Nintendo 64"),
    IconOption("ic_psp",      "PSP"),
    IconOption("ic_ps2",      "PlayStation"),
    IconOption("ic_switch",   "Switch"),
    IconOption("ic_android",  "Android"),
    IconOption("ic_pc",       "PC"),
)

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
    // Dialogs
    val showCreateNameDialog: Boolean = false,
    val renameTargetId: String? = null,
    val returnFocusKey: String? = null,
    val message: String? = null,
) {
    val detail: CategoryRow? get() = categories.firstOrNull { it.id == detailId }
}

const val CREATE_CATEGORY_FOCUS_KEY = "create_category"

@HiltViewModel
class CategoryManagerViewModel @Inject constructor(
    private val categoryRepository: CategoryRepositoryImpl,
) : ViewModel() {

    private val _scratch = MutableStateFlow(CategoryManagerUiState())

    val uiState: StateFlow<CategoryManagerUiState> = combine(
        categoryRepository.observeAll(),
        _scratch,
    ) { categories, scratch ->
        scratch.copy(
            categories = categories.map {
                CategoryRow(
                    id                 = it.id,
                    name               = it.name,
                    iconKey            = it.iconKey,
                    visible            = it.isVisible,
                    protected          = categoryRepository.isProtected(it.id),
                    isGamingCategory   = it.isGamingCategory,
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
        _scratch.update {
            it.copy(
                step = CategoryStep.LIST,
                pendingName = null,
                pendingIconKey = null,
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
            if (s.pickingIconForCreate) {
                _scratch.update {
                    it.copy(
                        step = CategoryStep.PICK_TYPE,
                        pendingIconKey = iconKey,
                        pickingTypeForCreate = true,
                    )
                }
            } else {
                val id = s.detailId ?: return@launch
                categoryRepository.setIcon(id, iconKey)
                _scratch.update { it.copy(step = CategoryStep.DETAIL) }
            }
        }
    }

    fun chooseType(isGaming: Boolean) {
        val s = _scratch.value
        viewModelScope.launch {
            if (s.pickingTypeForCreate) {
                val name = s.pendingName ?: return@launch
                val iconKey = s.pendingIconKey ?: return@launch
                categoryRepository.createCustomCategory(name, iconKey, isGaming)
                _scratch.update {
                    it.copy(
                        step = CategoryStep.LIST,
                        pendingName = null,
                        pendingIconKey = null,
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

    fun startChangeIcon() = _scratch.update { it.copy(step = CategoryStep.PICK_ICON, pickingIconForCreate = false) }

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

    fun setGamingCategory(id: String, isGaming: Boolean) {
        viewModelScope.launch { categoryRepository.setGamingCategory(id, isGaming) }
    }

    fun move(id: String, up: Boolean) {
        viewModelScope.launch { categoryRepository.move(id, up) }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            categoryRepository.delete(id)
            if (_scratch.value.detailId == id) {
                _scratch.update { it.copy(step = CategoryStep.LIST, detailId = null) }
            }
        }
    }
}
