package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.data.repository.CustomIconStore
import com.playfieldportal.core.data.repository.GameBootPreferences
import com.playfieldportal.core.data.repository.LockScreenImage
import com.playfieldportal.core.data.repository.PfpThemeStore
import com.playfieldportal.core.data.repository.PtfThemeImporter
import com.playfieldportal.core.data.repository.ThemePrefKeys
import com.playfieldportal.core.data.repository.ThemeTiers
import com.playfieldportal.core.data.repository.UiMediaStore
import com.playfieldportal.core.domain.model.NotificationAction
import com.playfieldportal.core.domain.model.NotificationSeverity
import com.playfieldportal.core.domain.model.PFPTheme
import com.playfieldportal.core.domain.model.UiMediaSlot
import com.playfieldportal.core.ui.notification.BackgroundTaskCenter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

data class ThemesSettingsUiState(
    // Name of the theme applied through PfpThemeStore ("Default" = stock look).
    val activeThemeName: String = "Default",
    val isInstalling: Boolean = false,
    // Custom-theme cascade state (docs/theme-format.md): the imported/custom accent
    // that supersedes the preset scheme, and the unified icon tint (null = default white).
    // Note: import/create/reset OUTCOMES no longer live here — they post to the notification tray.
    // isInstalling stays: the in-screen progress bar is kept, only the result row is gone.
    val accentOverrideArgb: Long? = null,
    val iconColorArgb: Long? = null,
    // The user's saved .pfptheme library (imports + Quick Create).
    val savedThemes: List<PfpThemeStore.SavedTheme> = emptyList(),
    // Installed .xmbtheme themes from the ThemeRepository (built-in + user-installed).
    val installedThemes: List<PFPTheme> = emptyList(),
    // Raised when a saved theme is picked (or imported): nothing is applied until it is answered.
    // It also offers what applying would replace or switch on. Null = nothing to ask.
    val applyConfirmation: ThemeApplyConfirmation? = null,
    // After applying a theme that carries a lock screen image: offer it, opt-in. Null = not asking.
    val lockScreenOffer: ThemeApplyConfirmation? = null,
    // Customize XMB Icons' value: the user's own picks, "None custom" / "N custom".
    val customIconsValue: String = CustomIconsRowText.value(emptySet()),
)

@HiltViewModel
class ThemesSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ptfImporter: PtfThemeImporter,
    private val themeStore: PfpThemeStore,
    private val tasks: BackgroundTaskCenter,
    private val uiMediaStore: UiMediaStore,
    private val gameBootPreferences: GameBootPreferences,
    private val customIconStore: CustomIconStore,
    private val themeTiers: ThemeTiers,
    private val lockScreen: LockScreenImage,
) : ViewModel() {

    private val _extra = MutableStateFlow(ThemesSettingsUiState())

    val uiState: StateFlow<ThemesSettingsUiState> = combine(
        context.pfpDataStore.data,
        themeStore.themes,
        customIconStore.observeStoredKeys(),
        _extra,
    ) { prefs, saved, iconKeys, extra ->
        extra.copy(
            customIconsValue   = CustomIconsRowText.value(iconKeys),
            activeThemeName    = prefs[ThemePrefKeys.APPLIED_THEME_NAME] ?: "Default",
            accentOverrideArgb = prefs[KEY_ACCENT_OVERRIDE],
            iconColorArgb      = prefs[KEY_ICON_COLOR],
            savedThemes        = saved,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemesSettingsUiState())

    // Theme import/create/reset outcomes land in the tray (row + notification cue), keyed so
    // a repeat replaces its row. INFO for plain results, ERROR when the store reported a failure.
    private fun reportTheme(message: String, severity: NotificationSeverity = NotificationSeverity.SUCCESS) {
        tasks.report(
            id = "themes_result",
            label = "Themes",
            message = message,
            severity = severity,
            action = NotificationAction.OpenSettingsScreen("settings_themes"),
        )
    }

    // ── Custom theme cascade ─────────────────────────────────────────────────

    /** Imports a user-picked official PSP theme (.ptf): wallpaper + derived accent. */
    fun importPtfTheme(uri: Uri) {
        viewModelScope.launch {
            _extra.update { it.copy(isInstalling = true) }
            val result = ptfImporter.import(uri)
            val message = when (result) {
                is PtfThemeImporter.Result.Success ->
                    "Imported \"${result.themeName}\" — wallpaper applied" +
                        if (result.accentArgb != null) " with its color" else ""
                PtfThemeImporter.Result.CxmbNotSupported ->
                    "CXMB (.ctf) themes aren't supported — only official .ptf themes"
                is PtfThemeImporter.Result.Failed -> result.reason
            }
            Timber.i("PTF import: %s", message)
            _extra.update { it.copy(isInstalling = false) }
            reportTheme(
                message,
                if (result is PtfThemeImporter.Result.Success) NotificationSeverity.SUCCESS
                else NotificationSeverity.ERROR,
            )
        }
    }

    /** Sets the unified icon tint; null restores the default (white / icon art's own color). */
    fun setIconColor(argb: Long?) {
        viewModelScope.launch {
            context.pfpDataStore.edit { prefs ->
                if (argb != null) prefs[KEY_ICON_COLOR] = argb else prefs.remove(KEY_ICON_COLOR)
            }
        }
    }

    /** Sets a custom accent color override; null clears it and returns to the preset scheme. */
    fun setAccentColor(argb: Long?) {
        viewModelScope.launch {
            context.pfpDataStore.edit { prefs ->
                if (argb != null) prefs[KEY_ACCENT_OVERRIDE] = argb else prefs.remove(KEY_ACCENT_OVERRIDE)
            }
        }
    }

    /** Clears an imported/custom accent so the preset color scheme applies again. */
    fun clearAccentOverride() {
        viewModelScope.launch { context.pfpDataStore.edit { it.remove(KEY_ACCENT_OVERRIDE) } }
    }

    /**
     * Full reset to stock: the applied theme (wallpaper, colours, legibility, layout, its icons
     * and media) AND the user's own Customize Icons picks. Sounds and clips the user assigned
     * themselves are not a theme concern and stay — Interface ▸ Sound owns their reset.
     */
    fun resetTheme() {
        viewModelScope.launch {
            themeStore.resetApplied()
            customIconStore.clearAll()
            // A lock screen a theme set goes with the theme; one the user chose stays.
            lockScreen.clearIfFromTheme()
            Timber.i("Theme reset to default")
            reportTheme("Theme reset — back to the default look")
        }
    }

    // ── Saved-theme library (Quick Create + imports) ─────────────────────────

    /** Quick Create: a picked photo becomes a saved+applied theme, accent auto-derived. */
    fun createThemeFromPhoto(uri: Uri) {
        viewModelScope.launch {
            _extra.update { it.copy(isInstalling = true) }
            val saved = themeStore.createFromImage(uri)
            if (saved != null) {
                applyAndReport(saved.id)
                reportTheme(
                    "Created \"${saved.name}\"" +
                        if (saved.accentArgb != null) " — color derived from the photo" else "",
                )
            } else {
                reportTheme("Could not read that image", NotificationSeverity.ERROR)
            }
            _extra.update { it.copy(isInstalling = false) }
        }
    }

    /**
     * Picking a saved theme asks first ([ThemeApplyConfirmation]). What it would replace is read
     * from the saved bundle's entry list and the user's own tiers, before anything changes.
     */
    fun applySavedTheme(id: String) {
        viewModelScope.launch { requestApply(id) }
    }

    private suspend fun requestApply(id: String) {
        val theme = themeStore.themes.value.firstOrNull { it.id == id } ?: return
        val prefs = context.pfpDataStore.data.first()
        val confirmation = withContext(Dispatchers.IO) {
            // An unreadable bundle still gets a plain question; applying it then reports the failure.
            val contents = themeStore.contentsOf(id)
            ThemeApplyConfirmation.of(
                themeId = id,
                themeName = theme.name,
                themeMedia = contents?.mediaKeys.orEmpty().mapNotNullTo(HashSet()) { UiMediaSlot.fromKey(it) },
                userMedia = themeTiers.mediaSlots(ThemeTiers.Tier.USER),
                themeIcons = contents?.iconKeys.orEmpty(),
                userIcons = themeTiers.iconKeys(ThemeTiers.Tier.USER),
                gameBootEnabled = GameBootPreferences.resolve(prefs),
                bootEnabled = prefs[KEY_SHOW_BOOT] ?: true,
                themeHasLockScreen = contents?.hasLockScreen == true,
            )
        }
        _extra.update { it.copy(applyConfirmation = confirmation) }
    }

    /**
     * "Apply": applies the theme, and with [ThemeApplyConfirmation.USE_THEMES] also clears the
     * user's media and icons it replaces and switches on what it needs.
     */
    fun confirmApply(choice: Int) {
        val confirmation = _extra.value.applyConfirmation ?: return
        _extra.update { it.copy(applyConfirmation = null) }
        viewModelScope.launch {
            if (!applyAndReport(confirmation.themeId)) {
                reportTheme("Could not apply the theme", NotificationSeverity.ERROR)
                return@launch
            }
            if (confirmation.hasChoices && choice == ThemeApplyConfirmation.USE_THEMES) {
                confirmation.replace.forEach { uiMediaStore.clear(it) }
                confirmation.replaceIcons.forEach { customIconStore.clear(it) }
                if (confirmation.turnOnGameBoot) gameBootPreferences.setGameBootEnabled(true)
                if (confirmation.turnOnBoot) context.pfpDataStore.edit { it[KEY_SHOW_BOOT] = true }
            }
            if (confirmation.offersLockScreen) _extra.update { it.copy(lockScreenOffer = confirmation) }
        }
    }

    /** "Set Lock Screen": the applied theme's lock screen image goes on the device lock screen. */
    fun confirmLockScreenOffer() {
        val offer = _extra.value.lockScreenOffer ?: return
        _extra.update { it.copy(lockScreenOffer = null) }
        viewModelScope.launch {
            val bytes = themeStore.lockScreenOf(offer.themeId)
            val result = if (bytes == null) {
                LockScreenImage.Result.Failed("This theme's lock screen image could not be read")
            } else {
                lockScreen.set(bytes, LockScreenImage.Source.THEME)
            }
            when (result) {
                LockScreenImage.Result.Set -> reportTheme("Lock screen set from \"${offer.themeName}\"")
                is LockScreenImage.Result.Failed -> reportTheme(result.reason, NotificationSeverity.ERROR)
            }
        }
    }

    /** "Not Now": the device lock screen is left as it is. */
    fun dismissLockScreenOffer() {
        _extra.update { it.copy(lockScreenOffer = null) }
    }

    /** "Cancel": the theme is not applied and nothing changes. */
    fun cancelApply() {
        _extra.update { it.copy(applyConfirmation = null) }
    }

    /**
     * Applies theme [id] and reports clips the install gate refused in the tray, with the reason.
     * False when the theme did not apply.
     */
    private suspend fun applyAndReport(id: String): Boolean {
        val result = themeStore.applyDetailed(id) ?: return false
        UiMediaRowText.droppedReport(result.droppedMedia)?.let { body ->
            tasks.report(
                id = "themes_media_dropped",
                label = "Themes",
                message = body,
                severity = NotificationSeverity.WARNING,
                action = NotificationAction.OpenSettingsScreen("settings_themes"),
            )
        }
        return true
    }

    /**
     * Exports the device's current look (icons, wallpaper, colors, motion, geometry) into the
     * library as a user-created theme — the Themes-side entry point beside the icon editor's
     * "Save as Theme…". One implementation: PfpThemeStore.saveCurrentLook.
     */
    fun saveCurrentLookAsTheme(name: String) {
        viewModelScope.launch {
            val saved = themeStore.saveCurrentLook(name)
            if (saved != null) reportTheme("Saved \"${saved.name}\"")
            else reportTheme("Could not save the theme", NotificationSeverity.ERROR)
        }
    }

    /** Rewrites an older-format saved theme in the current format; the outcome lands in the tray. */
    fun updateThemeFile(id: String) {
        viewModelScope.launch {
            val name = themeStore.themes.value.firstOrNull { it.id == id }?.name ?: "theme"
            if (themeStore.upgradeInPlace(id)) reportTheme("Updated \"$name\" to the current theme format")
            else reportTheme("Could not update \"$name\" — the original file was left as it was", NotificationSeverity.ERROR)
        }
    }

    fun deleteSavedTheme(id: String) {
        viewModelScope.launch { themeStore.delete(id) }
    }

    fun renameSavedTheme(id: String, name: String) {
        viewModelScope.launch { themeStore.rename(id, name) }
    }

    /** Exports the bundle to shareable cache and opens the system share sheet. */
    fun shareSavedTheme(id: String) {
        viewModelScope.launch {
            val file = themeStore.exportForShare(id)
            if (file == null) {
                reportTheme("Could not export the theme", NotificationSeverity.ERROR)
                return@launch
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(send, "Share theme").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Imports a shared `.pfptheme` bundle into the library, then asks before applying it. */
    fun importPfpTheme(uri: Uri) {
        viewModelScope.launch {
            _extra.update { it.copy(isInstalling = true) }
            val result = themeStore.importBundleDetailed(uri)
            if (result is PfpThemeStore.ImportResult.Success) requestApply(result.theme.id)
            _extra.update { it.copy(isInstalling = false) }
            reportTheme(
                messageFor(result),
                if (result is PfpThemeStore.ImportResult.Success) NotificationSeverity.SUCCESS
                else NotificationSeverity.ERROR,
            )
        }
    }

    /**
     * User-facing copy for each import outcome.
     *
     * The store deliberately does not carry these strings — it reports what happened, the UI
     * decides how to say it. Note that "too large" and "out of memory" are different failures
     * and must not be merged: the first is a file this build refuses outright, the second is a
     * legitimate bundle this device could not hold, which is fixable by shrinking the motion
     * wallpaper rather than by re-exporting.
     */
    private fun messageFor(result: PfpThemeStore.ImportResult): String = when (result) {
        is PfpThemeStore.ImportResult.Success -> "Imported \"${result.theme.name}\""
        is PfpThemeStore.ImportResult.Unreadable -> "Could not open that file"
        PfpThemeStore.ImportResult.TooLarge -> "That theme is too large to import"
        PfpThemeStore.ImportResult.OutOfMemory ->
            "Not enough memory to import that theme — its motion wallpaper is too big"
        PfpThemeStore.ImportResult.NotABundle -> "Not a valid .pfptheme file"
        PfpThemeStore.ImportResult.DamagedWallpaper -> "That theme's wallpaper is damaged"
        is PfpThemeStore.ImportResult.NotSaved -> "Could not save the imported theme"
    }

    private companion object {
        val KEY_ACCENT_OVERRIDE = ThemePrefKeys.ACCENT_OVERRIDE
        val KEY_ICON_COLOR      = ThemePrefKeys.ICON_COLOR
    }
}
