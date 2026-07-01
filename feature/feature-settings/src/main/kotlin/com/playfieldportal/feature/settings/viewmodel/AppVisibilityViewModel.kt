package com.playfieldportal.feature.settings.viewmodel

import android.graphics.drawable.Drawable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.database.dao.AppOverrideDao
import com.playfieldportal.feature.appbar.AppCategoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// One installed app plus whether the user has hidden it from every XMB category.
data class AppVisibilityRow(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    val hidden: Boolean,
)

data class AppVisibilityUiState(
    val loading: Boolean = true,
    val apps: List<AppVisibilityRow> = emptyList(),
    val hiddenCount: Int = 0,
)

// Backs the "Hidden Apps" manager: lists every launchable app with a per-app Hidden toggle so the
// user can hide new apps or unhide existing ones individually. The XMB observes app-override changes
// reactively (AppCategoryRepository.setHidden → app_overrides), so toggles take effect with no restart.
@HiltViewModel
class AppVisibilityViewModel @Inject constructor(
    private val appCategoryRepository: AppCategoryRepository,
    private val appOverrideDao: AppOverrideDao,
) : ViewModel() {

    // Installed apps are loaded once (cached in the repository); hidden state comes from the live
    // override flow, so toggling re-emits without reloading the whole app list.
    private val installedApps = MutableStateFlow<List<AppVisibilityRow>>(emptyList())
    private val loading = MutableStateFlow(true)

    init {
        viewModelScope.launch {
            appCategoryRepository.ensureLoaded()
            installedApps.value = appCategoryRepository.allInstalledApps()
                .map { AppVisibilityRow(it.packageName, it.label, it.icon, hidden = false) }
            loading.value = false
        }
    }

    val uiState: StateFlow<AppVisibilityUiState> = combine(
        installedApps,
        appOverrideDao.observeAll(),
        loading,
    ) { apps, overrides, isLoading ->
        val hidden = overrides.filter { it.isHidden }.map { it.packageName }.toSet()
        val rows = apps
            .map { it.copy(hidden = it.packageName in hidden) }
            // Stable alphabetical order so a row never jumps position when toggled.
            .sortedBy { it.label.lowercase() }
        AppVisibilityUiState(
            loading = isLoading,
            apps = rows,
            hiddenCount = rows.count { it.hidden },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppVisibilityUiState())

    fun setHidden(packageName: String, hidden: Boolean) {
        viewModelScope.launch { appCategoryRepository.setHidden(packageName, hidden) }
    }
}
