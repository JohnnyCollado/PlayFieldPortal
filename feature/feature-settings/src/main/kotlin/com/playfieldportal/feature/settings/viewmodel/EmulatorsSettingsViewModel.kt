package com.playfieldportal.feature.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.domain.model.EmulatorProfile
import com.playfieldportal.core.domain.model.IntentType
import com.playfieldportal.feature.launcher.EmulatorProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class ProfileListItem(
    val id: String,
    val name: String,
    val packageName: String,
    val intentType: String,
    val isCustom: Boolean,
)

data class ProfileEditorState(
    val isNew: Boolean,
    val originalId: String?,
    val name: String = "",
    val packageName: String = "",
    val activityClass: String = "",
    val intentType: IntentType = IntentType.ACTION_VIEW,
    val supportedPlatformIds: String = "",  // comma-separated, e.g. "psx,psp"
    val mimeType: String = "",
    val useFileUri: Boolean = true,
    val useSafUri: Boolean = false,
    val customCommand: String = "",
    val notes: String = "",
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
)

// Focus key for the "Add Custom Emulator" row so focus returns to it after the editor closes.
const val ADD_CUSTOM_EMULATOR_FOCUS_KEY = "add_custom_emulator"

data class EmulatorsSettingsUiState(
    val installedProfiles: List<ProfileListItem> = emptyList(),
    val availableProfiles: List<ProfileListItem> = emptyList(),
    val customProfiles: List<ProfileListItem> = emptyList(),
    val editorState: ProfileEditorState? = null,
    // Row to restore focus to when returning from the editor to the list.
    val returnFocusKey: String? = null,
)

@HiltViewModel
class EmulatorsSettingsViewModel @Inject constructor(
    private val profileRepository: EmulatorProfileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(EmulatorsSettingsUiState())
    val uiState: StateFlow<EmulatorsSettingsUiState> = _uiState.asStateFlow()

    private var allProfiles: List<EmulatorProfile> = emptyList()

    init {
        observeProfiles()
    }

    private fun observeProfiles() {
        viewModelScope.launch {
            profileRepository.profiles.collect { all ->
                allProfiles = all
                val installed = profileRepository.getInstalledProfiles()
                val custom    = all.filter { it.isCustom }
                val available = all.filter { p ->
                    installed.none { it.packageName == p.packageName } &&
                    custom.none { it.packageName == p.packageName }
                }
                _uiState.update { it.copy(
                    installedProfiles = installed.map { p -> p.toListItem() },
                    availableProfiles = available.map { p -> p.toListItem() },
                    customProfiles    = custom.map { p -> p.toListItem() },
                ) }
            }
        }
    }

    fun openEditor(profileId: String?) {
        val profile = profileId?.let { id ->
            allProfiles.firstOrNull { it.id == id }
        }
        _uiState.update { it.copy(
            returnFocusKey = profileId ?: ADD_CUSTOM_EMULATOR_FOCUS_KEY,
            editorState = if (profile != null) {
                ProfileEditorState(
                    isNew                = false,
                    originalId           = profile.id,
                    name                 = profile.name,
                    packageName          = profile.packageName,
                    activityClass        = profile.activityClass ?: "",
                    intentType           = profile.intentType,
                    supportedPlatformIds = profile.supportedPlatformIds.joinToString(","),
                    mimeType             = profile.mimeType ?: "",
                    useFileUri           = profile.useFileUri,
                    useSafUri            = profile.useSafUri,
                    customCommand        = profile.customCommand ?: "",
                    notes                = profile.notes ?: "",
                )
            } else {
                ProfileEditorState(isNew = true, originalId = null)
            }
        ) }
    }

    fun closeEditor() {
        _uiState.update { it.copy(editorState = null) }
    }

    fun updateEditorName(value: String)           = updateEditor { copy(name = value, errorMessage = null) }
    fun updateEditorPackageName(value: String)    = updateEditor { copy(packageName = value, errorMessage = null) }
    fun updateEditorActivityClass(value: String)  = updateEditor { copy(activityClass = value) }
    fun updateEditorIntentType(value: IntentType) = updateEditor { copy(intentType = value) }
    fun updateEditorPlatformIds(value: String)    = updateEditor { copy(supportedPlatformIds = value) }
    fun updateEditorMimeType(value: String)       = updateEditor { copy(mimeType = value) }
    fun updateEditorUseFileUri(value: Boolean)    = updateEditor { copy(useFileUri = value) }
    fun updateEditorUseSafUri(value: Boolean)     = updateEditor { copy(useSafUri = value) }
    fun updateEditorCustomCommand(value: String)  = updateEditor { copy(customCommand = value) }
    fun updateEditorNotes(value: String)          = updateEditor { copy(notes = value) }

    private fun updateEditor(block: ProfileEditorState.() -> ProfileEditorState) {
        _uiState.update { state ->
            state.copy(editorState = state.editorState?.block())
        }
    }

    fun saveEditorProfile() {
        val editor = _uiState.value.editorState ?: return
        if (editor.name.isBlank()) {
            updateEditor { copy(errorMessage = "Name is required") }
            return
        }
        if (editor.packageName.isBlank() && editor.intentType != IntentType.CUSTOM_COMMAND) {
            updateEditor { copy(errorMessage = "Package name is required") }
            return
        }
        updateEditor { copy(isSaving = true, errorMessage = null) }
        viewModelScope.launch {
            val profile = EmulatorProfile(
                id                   = editor.originalId ?: UUID.randomUUID().toString(),
                name                 = editor.name.trim(),
                packageName          = editor.packageName.trim(),
                activityClass        = editor.activityClass.trimToNull(),
                intentType           = editor.intentType,
                supportedPlatformIds = editor.supportedPlatformIds
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() },
                mimeType             = editor.mimeType.trimToNull(),
                useFileUri           = editor.useFileUri,
                useSafUri            = editor.useSafUri,
                customCommand        = editor.customCommand.trimToNull(),
                notes                = editor.notes.trimToNull(),
                isCustom             = true,
            )
            profileRepository.saveCustomProfile(profile)
            _uiState.update { it.copy(editorState = null) }
        }
    }

    fun deleteProfile(id: String) {
        viewModelScope.launch {
            profileRepository.deleteCustomProfile(id)
            _uiState.update { it.copy(editorState = null) }
        }
    }

    private fun EmulatorProfile.toListItem() = ProfileListItem(
        id          = id,
        name        = name,
        packageName = packageName,
        intentType  = intentType.name,
        isCustom    = isCustom,
    )

    private fun String.trimToNull() = trim().ifEmpty { null }
}
