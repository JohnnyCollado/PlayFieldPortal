package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.data.repository.ControllerLayoutRepository
import com.playfieldportal.core.data.repository.GameBootPreferences
import com.playfieldportal.core.data.repository.UiMediaStore
import com.playfieldportal.core.data.wallpaper.WallpaperLuminanceProbe
import com.playfieldportal.core.data.wallpaper.WallpaperLuminanceProbe.clearWallpaperLuma
import com.playfieldportal.core.data.wallpaper.WallpaperLuminanceProbe.setWallpaperLuma
import com.playfieldportal.core.domain.model.UiMediaKind
import com.playfieldportal.core.domain.model.UiMediaSlot
import com.playfieldportal.core.domain.model.IconLegibilityStyle
import com.playfieldportal.core.domain.model.TextLegibilityStyle
import com.playfieldportal.core.domain.model.XmbColorScheme
import com.playfieldportal.core.domain.model.lightBackgroundAnchors
import com.playfieldportal.core.domain.model.resolve
import com.playfieldportal.core.domain.model.TouchNavButtonMode
import com.playfieldportal.core.domain.model.TouchSensitivity
import com.playfieldportal.core.domain.model.XYLayout
import com.playfieldportal.core.ui.theme.TextContrastRole
import com.playfieldportal.core.ui.theme.clampLightnessForContrast
import com.playfieldportal.core.ui.theme.composite
import com.playfieldportal.core.ui.theme.solveScrimColor
import com.playfieldportal.core.ui.wave.WaveStyle
import com.playfieldportal.themekit.MotionLimits
import com.playfieldportal.themekit.UiMediaLimits
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject

private val KEY_WAVE_STYLE         = stringPreferencesKey("display_wave_style")
private val KEY_SHOW_BOOT          = booleanPreferencesKey("display_show_boot")
private val KEY_BOOT_ON_RESUME     = booleanPreferencesKey("display_boot_on_resume")
private val KEY_THERMAL_AWARE      = booleanPreferencesKey("display_thermal_aware")
private val KEY_RESPECT_BATTERY    = booleanPreferencesKey("display_battery_saver")
// Must match XMBViewModel.KEY_TOUCH_NAV_BUTTON — both read/write this same pref.
private val KEY_TOUCH_NAV_BUTTON   = stringPreferencesKey("interface_touch_nav_button")
// Must match XMBViewModel.KEY_CONTEXT_MENU_HINT — both read/write this same pref.
private val KEY_CONTEXT_MENU_HINT  = booleanPreferencesKey("interface_context_menu_hint")
private val KEY_CONTEXT_MENU_HINT_DELAY_SECONDS = floatPreferencesKey("interface_context_menu_hint_delay_seconds")
// Must match XMBViewModel.KEY_TOUCH_SENSITIVITY — both read/write this same pref.
private val KEY_TOUCH_SENSITIVITY  = stringPreferencesKey("interface_touch_sensitivity")
// Must match GameLaunchPreferences.KEY_DIRECT_LAUNCH — both read/write this same pref.
private val KEY_DIRECT_LAUNCH      = booleanPreferencesKey("pref_direct_game_launch")
// Must match XMBViewModel.KEY_ICON_LEGIBILITY — both read/write this same pref.
private val KEY_ICON_LEGIBILITY    = stringPreferencesKey("display_icon_legibility")
// Must match XMBViewModel.KEY_SOLID_UNFOCUSED_ICONS — both read/write this same pref.
private val KEY_SOLID_UNFOCUSED_ICONS = booleanPreferencesKey("display_solid_unfocused_icons")
// Must match XMBViewModel.KEY_TEXT_SHADOW — both read/write this same pref.
private val KEY_TEXT_SHADOW = booleanPreferencesKey("display_text_shadow")
// ── Font colour (Display ▸ Font Colour) ──────────────────────────────────────
// Must match XMBViewModel.KEY_TEXT_COLOR — both read/write this same pref.
// Absent = inherit the theme's own text colour (white on every preset).
private val KEY_TEXT_COLOR = longPreferencesKey("display_text_color")
// "Use my exact colour": skip the lightness clamp. Protection still applies — a plate can rescue
// a colour without repainting it, which is the whole reason the two are separate settings.
private val KEY_TEXT_COLOR_EXACT = booleanPreferencesKey("display_text_color_exact")
// Must match XMBViewModel — the resolved text-protection instrument (AUTO by default).
private val KEY_TEXT_LEGIBILITY = stringPreferencesKey("display_text_legibility")
// "Don't warn again": adjustment continues silently, only the notice stops.
private val KEY_TEXT_NOTICE_SUPPRESSED = booleanPreferencesKey("display_text_contrast_notice_suppressed")
// Read-only here: the theme owns these, this screen only needs them to know which backdrop the
// picked font colour will land on. Must match ThemesSettingsViewModel / XMBViewModel.
private val KEY_ACCENT_OVERRIDE = longPreferencesKey("theme_accent_override")
private val KEY_COLOR_SCHEME    = stringPreferencesKey("display_color_scheme")
internal val KEY_CUSTOM_WALLPAPER  = stringPreferencesKey("display_custom_wallpaper")
// Motion wallpaper (looping MP4/WebM/GIF). Must match XMBViewModel — shared cascade pref.
// INVARIANT: never set without KEY_CUSTOM_WALLPAPER — the poster is the fallback for both the
// freeze paths and decode failure, so a motion file without one is an unrenderable state.
// Enforced at the two write sites (import, clear) and on read ("motion set, poster missing"
// degrades to "no motion").
internal val KEY_MOTION_WALLPAPER  = stringPreferencesKey("display_motion_wallpaper")
// Scale & Layout now live in the XMB's on-screen "Adjust XMB Layout" editor (see XMBViewModel);
// this screen only launches it, so the old scale/bar prefs and steppers were removed here.

private val SUPPORTED_WALLPAPER_MIME = setOf("image/png", "image/jpeg", "image/webp") + MotionLimits.SUPPORTED_MIME

/** Mimes routed to the motion (looping) path rather than the plain still path. */
private val MOTION_WALLPAPER_MIME = MotionLimits.SUPPORTED_MIME

private val TOUCH_NAV_BUTTON_LABELS = mapOf(
    TouchNavButtonMode.AUTO        to "Auto",
    TouchNavButtonMode.ALWAYS_SHOW to "Always Show",
    TouchNavButtonMode.ALWAYS_HIDE to "Always Hide",
)

private val TOUCH_SENSITIVITY_LABELS = mapOf(
    TouchSensitivity.LOW    to "Low",
    TouchSensitivity.NORMAL to "Normal",
    TouchSensitivity.HIGH   to "High",
)

private val WAVE_STYLE_LABELS = mapOf(
    WaveStyle.ANIMATED       to "Animated",
    WaveStyle.REDUCED        to "Reduced",
    WaveStyle.STATIC         to "Static",
    WaveStyle.REDUCED_STATIC to "Reduced + Static",
)

/**
 * The four transient facts that ride together through the nested [combine] — [combine] takes at
 * most five typed sources, and the outer one is already full of DataStore and import state.
 */
private data class Transient(
    val bootPreviewVisible: Boolean,
    val gameBootPreviewVisible: Boolean,
    val textContrastNotice: String?,
    val xyLayout: XYLayout,
)

data class DisplaySettingsUiState(
    val waveStyle: WaveStyle = WaveStyle.ANIMATED,
    val showBootSequence: Boolean = true,
    val showBootOnResume: Boolean = false,
    val thermalThrottleAware: Boolean = true,
    val respectBatterySaver: Boolean = true,
    val touchNavButtonMode: TouchNavButtonMode = TouchNavButtonMode.AUTO,
    // Icon legibility treatment for XMB silhouette glyphs (None / Offset Shadow / Contour…).
    val iconLegibility: IconLegibilityStyle = IconLegibilityStyle.DEFAULT,
    // Draw unselected XMB icons at full opacity (selection reads by size and label).
    val solidUnfocusedIcons: Boolean = false,
    // Directional drop shadow behind XMB row subtitles, so helper text stays readable over
    // bright wallpaper regions. Default on — the shadow is subtle; without it the flat gray
    // subtitle is the one label that washes out.
    val textShadow: Boolean = true,
    // ── Font colour ──────────────────────────────────────────────────────────
    /** User-picked text colour, or null to inherit the theme's. */
    val textColorArgb: Long? = null,
    /** Render the pick verbatim, skipping the lightness clamp (protection still applies). */
    val textColorExact: Boolean = false,
    val textLegibility: TextLegibilityStyle = TextLegibilityStyle.DEFAULT,
    val textContrastNoticeSuppressed: Boolean = false,
    /** Transient "we adjusted your colour" notice; null when there is nothing to say. */
    val textContrastNotice: String? = null,
    // Show the idle "Options" hint pill over XMB items with a context menu. Default on.
    val contextMenuHintDelaySeconds: Float = 2.5f,
    val touchSensitivity: TouchSensitivity = TouchSensitivity.NORMAL,
    // Confirm on a game launches it directly (true) or opens Game Detail first (false).
    val directLaunch: Boolean = false,
    val customWallpaperPath: String? = null,
    // Absolute path of the looping motion file, when one is set (and its poster exists).
    val motionWallpaperPath: String? = null,
    val wallpaperMessage: String? = null,
    val contextMenuHintEnabled: Boolean = true,
    val wallpaperImporting: Boolean = false,
    val wallpaperPreviewVisible: Boolean = false,
    // ── Boot Sequence media (Display ▸ Boot Sequence) ────────────────────────
    // ONE field, exactly like GameBoot: the boot sequence is the built-in logo animation until
    // the user replaces the whole thing with a clip of their own. Boot SOUND is not here — it is
    // the seventh row of Interface ▸ Sound, which owns every sound in the app.
    val bootVideoLabel: String = UI_MEDIA_DEFAULT_LABEL,
    val bootVideoAssigned: Boolean = false,
    val bootPreviewVisible: Boolean = false,
    // ── GameBoot (Display ▸ GameBoot) ────────────────────────────────────────
    // One switch and one replaceable asset: on/off, plus the user's own clip when they have one.
    val gameBootEnabled: Boolean = true,
    val gameBootVideoLabel: String = UI_MEDIA_DEFAULT_LABEL,
    val gameBootVideoAssigned: Boolean = false,
    val gameBootPreviewVisible: Boolean = false,
    /**
     * Which physical face button does what on a focused media row — the north/west shortcuts are
     * bound to positions, so they need the user's X/Y layout to resolve. See MediaRowShortcuts.
     */
    val xyLayout: XYLayout = XYLayout.STANDARD,
    /** The layout saved for this screen size is the PSP preset, so Biblically Accurate PSP XMB greys out. */
    val pspLayoutApplied: Boolean = false,
) {
    val waveStyleLabel: String get() = WAVE_STYLE_LABELS[waveStyle] ?: waveStyle.name
}

/** Row summary for a UI-media slot with no user assignment. */
const val UI_MEDIA_DEFAULT_LABEL = "PFP Default"

@HiltViewModel
class DisplaySettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val uiMediaStore: UiMediaStore,
    private val gameBootPreferences: GameBootPreferences,
    private val menuSound: com.playfieldportal.core.ui.sound.MenuSoundPlayer,
    private val controllerLayout: ControllerLayoutRepository,
) : ViewModel() {

    private val _wallpaperMessage  = MutableStateFlow<String?>(null)
    private val _wallpaperImporting = MutableStateFlow(false)
    private val _wallpaperPreviewVisible = MutableStateFlow(false)
    private val _bootPreviewVisible = MutableStateFlow(false)
    private val _gameBootPreviewVisible = MutableStateFlow(false)
    /**
     * Transient font-colour notice, on the same channel idiom as [_wallpaperMessage]: raised by a
     * pick that had to be adjusted, cleared by Dismiss or by "Don't warn again".
     */
    private val _textContrastNotice = MutableStateFlow<String?>(null)

    /** The slot the UI-media picker was launched for, read back when the Uri arrives. */
    private var pendingUiMediaSlot: UiMediaSlot? = null

    val uiState: StateFlow<DisplaySettingsUiState> = combine(
        context.pfpDataStore.data,
        _wallpaperMessage,
        _wallpaperImporting,
        _wallpaperPreviewVisible,
        // combine tops out at five typed sources, so everything transient rides together — the
        // same nesting the two boot previews already used, now carrying the controller layout as
        // well (it maps over the same DataStore, so this costs no extra read).
        combine(
            _bootPreviewVisible,
            _gameBootPreviewVisible,
            _textContrastNotice,
            controllerLayout.prefs,
        ) { boot, gameBoot, notice, layout ->
            Transient(boot, gameBoot, notice, layout.xyLayout)
        },
    ) { prefs, msg, importing, previewVisible, transient ->
        // Every UI-media fact below comes from the same DataStore emission plus one directory
        // listing, so the rows follow an import or a clear without a second flow.
        val assigned = uiMediaStore.assignments()
        fun label(slot: UiMediaSlot): String = when {
            slot !in assigned -> UI_MEDIA_DEFAULT_LABEL
            else -> prefs[UiMediaStore.displayNameKey(slot)]
                ?: if (slot.kind == UiMediaKind.VIDEO) "Custom video" else "Custom sound"
        }
        DisplaySettingsUiState(
            waveStyle            = runCatching {
                WaveStyle.valueOf(prefs[KEY_WAVE_STYLE] ?: WaveStyle.ANIMATED.name)
            }.getOrDefault(WaveStyle.ANIMATED),
            showBootSequence     = prefs[KEY_SHOW_BOOT]       ?: true,
            showBootOnResume     = prefs[KEY_BOOT_ON_RESUME]  ?: false,
            thermalThrottleAware = prefs[KEY_THERMAL_AWARE]   ?: true,
            respectBatterySaver  = prefs[KEY_RESPECT_BATTERY] ?: true,
            touchNavButtonMode   = TouchNavButtonMode.fromName(prefs[KEY_TOUCH_NAV_BUTTON]),
            iconLegibility       = IconLegibilityStyle.fromName(prefs[KEY_ICON_LEGIBILITY]),
            solidUnfocusedIcons  = prefs[KEY_SOLID_UNFOCUSED_ICONS] ?: false,
            textShadow           = prefs[KEY_TEXT_SHADOW] ?: true,
            textColorArgb        = prefs[KEY_TEXT_COLOR],
            textColorExact       = prefs[KEY_TEXT_COLOR_EXACT] ?: false,
            textLegibility       = TextLegibilityStyle.fromName(prefs[KEY_TEXT_LEGIBILITY]),
            textContrastNoticeSuppressed = prefs[KEY_TEXT_NOTICE_SUPPRESSED] ?: false,
            textContrastNotice   = transient.textContrastNotice,
            contextMenuHintEnabled = prefs[KEY_CONTEXT_MENU_HINT] ?: true,
            contextMenuHintDelaySeconds = (prefs[KEY_CONTEXT_MENU_HINT_DELAY_SECONDS] ?: 2.5f).coerceIn(1f, 5f),
            touchSensitivity     = TouchSensitivity.fromName(prefs[KEY_TOUCH_SENSITIVITY]),
            directLaunch         = prefs[KEY_DIRECT_LAUNCH]   ?: false,
            customWallpaperPath  = prefs[KEY_CUSTOM_WALLPAPER],
            motionWallpaperPath  = prefs[KEY_MOTION_WALLPAPER],
            wallpaperMessage     = msg,
            wallpaperImporting   = importing,
            wallpaperPreviewVisible = previewVisible,
            bootVideoLabel       = label(UiMediaSlot.BOOT_VIDEO),
            bootVideoAssigned    = UiMediaSlot.BOOT_VIDEO in assigned,
            bootPreviewVisible   = transient.bootPreviewVisible,
            // Same read-time migration the gate uses, so the row can never disagree with what
            // will actually play at launch.
            gameBootEnabled      = GameBootPreferences.resolve(prefs),
            gameBootVideoLabel   = label(UiMediaSlot.GAMEBOOT_VIDEO),
            gameBootVideoAssigned = UiMediaSlot.GAMEBOOT_VIDEO in assigned,
            gameBootPreviewVisible = transient.gameBootPreviewVisible,
            xyLayout             = transient.xyLayout,
            // Re-derived on every DataStore emission, so a save from the Adjust XMB Layout editor
            // (a reset to default included) re-enables the row the moment it lands.
            pspLayoutApplied     = PspXmbLayout.isApplied(prefs, PspXmbLayout.forWindow(context)),
        )
    }
        // uiMediaStore.assignments() is a directory listing — cheap, but still file IO.
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DisplaySettingsUiState())

    // ── Boot / GameBoot media ─────────────────────────────────────────────────

    /** Records which media row the picker was launched for. Called just before launching it. */
    fun onUiMediaPickerLaunchedFor(slot: UiMediaSlot) {
        pendingUiMediaSlot = slot
    }

    /**
     * Imports the picked file for the pending boot/GameBoot slot. A rejection surfaces its reason
     * through the same message channel the wallpaper uses and leaves the previous assignment
     * playing — the store stages the copy and only commits it once the gate passes.
     */
    fun onUiMediaPicked(uri: Uri) {
        val slot = pendingUiMediaSlot ?: return
        pendingUiMediaSlot = null
        viewModelScope.launch {
            _wallpaperImporting.value = true
            val result = try {
                uiMediaStore.import(slot, uri)
            } finally {
                _wallpaperImporting.value = false
            }
            if (!result.ok) {
                // A refused import is an ERROR event, not just a dialog — the user did
                // something and the launcher said no.
                menuSound.play(com.playfieldportal.core.ui.sound.MenuSound.ERROR)
                _wallpaperMessage.value = result.message
            }
        }
    }

    fun clearUiMedia(slot: UiMediaSlot) = viewModelScope.launch { uiMediaStore.clear(slot) }

    /** Display ▸ GameBoot — the one switch for the whole presentation. */
    fun setGameBootEnabled(enabled: Boolean) = viewModelScope.launch {
        gameBootPreferences.setGameBootEnabled(enabled)
    }

    fun showBootPreview() { _bootPreviewVisible.value = true }
    fun hideBootPreview() { _bootPreviewVisible.value = false }
    fun showGameBootPreview() { _gameBootPreviewVisible.value = true }
    fun hideGameBootPreview() { _gameBootPreviewVisible.value = false }

    /** MIME arrays for the two pickers, straight off the import gate so they cannot disagree. */
    fun uiMediaPickerMime(slot: UiMediaSlot): Array<String> =
        if (slot.kind == UiMediaKind.VIDEO) UiMediaLimits.VIDEO_MIME.toTypedArray()
        else UiMediaLimits.AUDIO_MIME.toTypedArray()

    fun cycleWaveStyle() {
        val styles = WaveStyle.values()
        val next = styles[(styles.indexOf(uiState.value.waveStyle) + 1) % styles.size]
        save { it[KEY_WAVE_STYLE] = next.name }
    }

    /** Cycles None → Offset Shadow → Contour (Dark/Light/Auto) → None, persisting the enum name. */
    fun cycleIconLegibility() {
        // entries, not the deprecated values() cycleWaveStyle still uses.
        val styles = IconLegibilityStyle.entries
        val next = styles[(styles.indexOf(uiState.value.iconLegibility) + 1) % styles.size]
        save { it[KEY_ICON_LEGIBILITY] = next.name }
    }

    fun setSolidUnfocusedIcons(v: Boolean) = save { it[KEY_SOLID_UNFOCUSED_ICONS] = v }

    fun setTextShadow(v: Boolean) = save { it[KEY_TEXT_SHADOW] = v }

    /** Display ▸ XMB Layout ▸ Biblically Accurate PSP XMB: saves the PSP preset for this screen size. */
    fun applyPspLayout() = save { PspXmbLayout.write(it, PspXmbLayout.forWindow(context)) }

    // ── Font colour ───────────────────────────────────────────────────────────

    /**
     * Persist the picked colour (null clears back to the theme's own), then measure it against the
     * backdrop it will actually land on and raise the notice if it had to be adjusted.
     *
     * The measurement happens here rather than at render time because this is where the user is
     * looking: telling them at the moment of the pick is an explanation, telling them later is a
     * mystery.
     */
    fun setTextColor(argb: Long?) {
        viewModelScope.launch {
            context.pfpDataStore.edit { prefs ->
                if (argb != null) prefs[KEY_TEXT_COLOR] = argb else prefs.remove(KEY_TEXT_COLOR)
            }
            _textContrastNotice.value = noticeFor(argb)
        }
    }

    /** Render the pick verbatim. Protection (shadow/plate) still applies — the dialog says so. */
    fun setTextColorExact(v: Boolean) {
        viewModelScope.launch {
            context.pfpDataStore.edit { it[KEY_TEXT_COLOR_EXACT] = v }
            // The standing notice described a clamp that no longer happens.
            if (v) _textContrastNotice.value = null
        }
    }

    fun cycleTextLegibility() {
        val styles = TextLegibilityStyle.entries
        val next = styles[(styles.indexOf(uiState.value.textLegibility) + 1) % styles.size]
        save { it[KEY_TEXT_LEGIBILITY] = next.name }
    }

    /** Transient dismiss — the notice comes back on the next pick that needs it. */
    fun dismissTextContrastNotice() {
        _textContrastNotice.value = null
    }

    /** Permanent: adjustment carries on, the user just stops hearing about it. */
    fun suppressTextContrastNotice() {
        _textContrastNotice.value = null
        save { it[KEY_TEXT_NOTICE_SUPPRESSED] = true }
    }

    /**
     * The notice text for [argb], or null when there is nothing to say — no pick, "use my exact
     * colour", the notice suppressed, or the colour simply passes.
     */
    private suspend fun noticeFor(argb: Long?): String? {
        if (argb == null) return null
        val prefs = context.pfpDataStore.data.first()
        if (prefs[KEY_TEXT_COLOR_EXACT] == true) return null
        if (prefs[KEY_TEXT_NOTICE_SUPPRESSED] == true) return null

        val picked = androidx.compose.ui.graphics.Color(argb and 0xFFFFFFFFL)
        val worst = settingsBackdropAnchors(prefs)
            .minByOrNull { clampLightnessForContrast(picked, it).achievedRatio } ?: return null
        val resolved = clampLightnessForContrast(picked, worst, TextContrastRole.BODY.threshold)

        return when {
            !resolved.adjusted && resolved.meetsTarget -> null
            resolved.adjusted -> "Adjusted for readability — the colour you picked reads at " +
                String.format("%.1f:1", contrastOf(picked, worst)) + " here, below the 4.5:1 bar."
            else -> "That colour can't reach 4.5:1 on this background, so text will get a " +
                "contrast plate behind it."
        }
    }

    /**
     * The two backdrops Settings actually paints — the solved scrim anchors over a worst-case
     * bright wallpaper. Reading the live theme (rather than assuming the default) is what keeps
     * the reported ratio honest on a custom accent.
     */
    private fun settingsBackdropAnchors(
        prefs: androidx.datastore.preferences.core.Preferences,
    ): List<androidx.compose.ui.graphics.Color> {
        val accentOverride = prefs[KEY_ACCENT_OVERRIDE]
        val anchors: Pair<Long, Long> = if (accentOverride != null) {
            lightBackgroundAnchors(accentOverride and 0xFFFFFFFFL)
        } else {
            val scheme = runCatching {
                XmbColorScheme.valueOf(
                    prefs[KEY_COLOR_SCHEME] ?: XmbColorScheme.CLASSIC_BLUE.name,
                )
            }.getOrDefault(XmbColorScheme.CLASSIC_BLUE)
            val palette = scheme.resolve(java.time.LocalDate.now().monthValue)
            palette.backgroundTop to palette.backgroundBottom
        }
        fun backdrop(argb: Long, alpha: Float) = composite(
            solveScrimColor(androidx.compose.ui.graphics.Color(argb and 0xFFFFFFFFL), alpha)
                .copy(alpha = alpha),
            androidx.compose.ui.graphics.Color.White,
        )
        return listOf(backdrop(anchors.first, 0.72f), backdrop(anchors.second, 0.90f))
    }

    private fun contrastOf(
        fg: androidx.compose.ui.graphics.Color,
        bg: androidx.compose.ui.graphics.Color,
    ): Float = com.playfieldportal.core.ui.theme.contrastRatio(fg, bg).toFloat()

    fun setShowBootSequence(v: Boolean)      = save { it[KEY_SHOW_BOOT]       = v }
    fun setShowBootOnResume(v: Boolean)      = save { it[KEY_BOOT_ON_RESUME]  = v }
    fun setThermalThrottleAware(v: Boolean)  = save { it[KEY_THERMAL_AWARE]   = v }
    fun setRespectBatterySaver(v: Boolean)   = save { it[KEY_RESPECT_BATTERY] = v }
    fun setDirectLaunch(v: Boolean)          = save { it[KEY_DIRECT_LAUNCH]   = v }
    fun setContextMenuHintEnabled(v: Boolean) = save { it[KEY_CONTEXT_MENU_HINT] = v }
    fun setContextMenuHintDelaySeconds(v: Float) = save {
        it[KEY_CONTEXT_MENU_HINT_DELAY_SECONDS] = v.coerceIn(1f, 5f)
    }

    fun cycleTouchNavButtonMode() {
        val modes = TouchNavButtonMode.entries
        val next = modes[(modes.indexOf(uiState.value.touchNavButtonMode) + 1) % modes.size]
        save { it[KEY_TOUCH_NAV_BUTTON] = next.name }
    }

    fun touchNavButtonLabel(): String =
        TOUCH_NAV_BUTTON_LABELS[uiState.value.touchNavButtonMode] ?: uiState.value.touchNavButtonMode.name

    fun cycleTouchSensitivity() {
        val levels = TouchSensitivity.entries
        val next = levels[(levels.indexOf(uiState.value.touchSensitivity) + 1) % levels.size]
        save { it[KEY_TOUCH_SENSITIVITY] = next.name }
    }

    fun touchSensitivityLabel(): String =
        TOUCH_SENSITIVITY_LABELS[uiState.value.touchSensitivity] ?: uiState.value.touchSensitivity.name

    // ── Wallpaper ─────────────────────────────────────────────────────────────

    fun onWallpaperPicked(uri: Uri) {
        viewModelScope.launch {
            val mime = context.contentResolver.getType(uri)
            if (mime != null && mime !in SUPPORTED_WALLPAPER_MIME) {
                _wallpaperMessage.value = "Unsupported format — use PNG, JPG, WEBP, MP4, WEBM, or GIF"
                return@launch
            }
            _wallpaperImporting.value = true
            try {
                if (mime in MOTION_WALLPAPER_MIME) {
                    importMotionWallpaper(uri, mime!!)
                } else {
                    importStillWallpaper(uri)
                }
            } finally {
                _wallpaperImporting.value = false
            }
        }
    }

    /**
     * Today's still-image path, unchanged except that it clears a previous motion file —
     * setting a still poster must not leave an orphaned looping video behind it.
     */
    private suspend fun importStillWallpaper(uri: Uri) {
        val dir = wallpaperDir()
        // Write each wallpaper to a UNIQUE file. A fixed filename kept the stored path string
        // identical across replacements, so neither the state (same value) nor Coil's
        // path-keyed image cache ever updated — the old wallpaper stayed on screen. A unique
        // path changes the value (triggering recomposition) and is a fresh Coil key.
        val stamp = System.currentTimeMillis()
        val dest = File(dir, "wallpaper_$stamp.jpg")
        val ok = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            } != null && isDecodableImage(dest)
        }.getOrDefault(false)
        if (ok) {
            // Survey the new wallpaper BEFORE opening the transaction: edit{}'s transform may be
            // re-run under contention, and a bitmap decode is not something to repeat under a lock.
            // Free here — we are already off the main thread behind the import spinner.
            val luma = withContext(Dispatchers.IO) {
                WallpaperLuminanceProbe.survey(dest.absolutePath)
            }
            // All three keys together — even though a still import never sets the motion key, a
            // previous motion file must go when its poster is replaced, and a survey of the old
            // wallpaper must never outlive the wallpaper it describes.
            save {
                it[KEY_CUSTOM_WALLPAPER] = dest.absolutePath
                it.remove(KEY_MOTION_WALLPAPER)
                it.setWallpaperLuma(luma)
            }
            pruneWallpaperDir(keep = listOf(dest))
            _wallpaperMessage.value = "Wallpaper applied"
        } else {
            runCatching { dest.delete() }
            _wallpaperMessage.value = "Couldn't read that file — try a different one"
        }
    }

    /**
     * The motion path: gate BEFORE any decode/copy work where possible — playback discipline
     * cannot rescue a file that should never have been accepted — and on success write the
     * poster still and the motion file as a PAIR (unique stamp, same dir, so the dir prune
     * keeps or drops both members together).
     */
    private suspend fun importMotionWallpaper(uri: Uri, mime: String) {
        val dir = wallpaperDir()
        val stamp = System.currentTimeMillis()

        // GIF / animated WebP keep their container (ExoPlayer can't play them; the global Coil
        // ImageLoader's AnimatedImageDecoder animates them through AsyncImage instead).
        val motionExt = when (mime) {
            "video/webm" -> "webm"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            else -> "mp4"
        }
        val motionDest = File(dir, "wallpaper_$stamp.$motionExt")
        val posterDest = File(dir, "wallpaper_$stamp.jpg")

        // Size pre-check straight off the descriptor when the provider reports one: a 200 MB
        // pick is rejected without transferring a byte.
        val knownSize = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()?.takeIf { it > 0 }
        if (knownSize != null && knownSize > MotionLimits.MAX_BYTES) {
            _wallpaperMessage.value = MotionLimits.MSG_TOO_LARGE_BYTES
            return
        }

        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                motionDest.outputStream().use { out -> input.copyTo(out) }
            } != null
        }.getOrDefault(false)
        if (!copied) {
            runCatching { motionDest.delete() }
            _wallpaperMessage.value = MotionLimits.MSG_UNDECODABLE
            return
        }

        // Probe the copied file (authoritative size even when the provider hid it).
        val probe = probeMotionFile(motionDest, mime)
        val rejection = probe?.let { MotionLimits.validate(it) }
        if (probe == null || rejection != null) {
            runCatching { motionDest.delete() }
            _wallpaperMessage.value = rejection ?: MotionLimits.MSG_UNDECODABLE
            return
        }

        // Poster extraction at IMPORT time, not render time: the frozen paths become literally
        // the existing still-wallpaper composable (Coil AsyncImage of a JPEG), battery-saver
        // costs what a static wallpaper costs, and a corrupt file degrades to a still instead
        // of a black screen.
        val poster = extractPoster(motionDest, mime)
        if (poster == null) {
            runCatching { motionDest.delete() }
            _wallpaperMessage.value = MotionLimits.MSG_UNDECODABLE
            return
        }
        val posterOk = runCatching {
            posterDest.outputStream().use { poster.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            true
        }.getOrDefault(false)
        poster.recycle()
        if (!posterOk) {
            runCatching { motionDest.delete() }
            _wallpaperMessage.value = MotionLimits.MSG_UNDECODABLE
            return
        }

        // Surveyed from the written poster rather than the in-memory bitmap above: that one is
        // full-size, so getPixels() on it would cost more than re-decoding the poster at
        // inSampleSize. The poster is what the XMB paints under its text while a motion wallpaper
        // loads, and live frames drift from it — protection is biased one step stronger at render
        // time when KEY_MOTION_WALLPAPER is set, rather than by fudging the numbers here.
        val luma = withContext(Dispatchers.IO) {
            WallpaperLuminanceProbe.survey(posterDest.absolutePath)
        }

        // THE invariant, enforced at the write site: motion is never set without its poster.
        save {
            it[KEY_CUSTOM_WALLPAPER] = posterDest.absolutePath
            it[KEY_MOTION_WALLPAPER] = motionDest.absolutePath
            it.setWallpaperLuma(luma)
        }
        pruneWallpaperDir(keep = listOf(motionDest, posterDest))
        _wallpaperMessage.value = "Motion wallpaper applied"
    }

    /**
     * Runs the import gate on the copied file. Videos are probed with MediaMetadataRetriever;
     * GIF/animated-WebP via BitmapFactory bounds (duration is unknowable cheaply for them and
     * animated images are looped short-form content by nature — the size and resolution caps
     * are doing the guarding).
     */
    private fun probeMotionFile(file: File, mime: String): MotionLimits.Probe? = runCatching {
        if (mime == "image/gif" || mime == "image/webp") {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
            MotionLimits.Probe(
                mime = mime,
                width = bounds.outWidth,
                height = bounds.outHeight,
                durationMs = 0L,
                bytes = file.length(),
            )
        } else {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(file.absolutePath)
                MotionLimits.Probe(
                    mime = mime,
                    width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                    height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
                    durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                    bytes = file.length(),
                )
            }
        }
    }.getOrNull()

    /**
     * Poster still for the freeze paths. Videos: frame at ~1 s (the first frame of a fade-in
     * loop is often black), falling back to frame 0 for very short clips. GIF/animated WebP:
     * decode the first frame through ImageDecoder.
     */
    private fun extractPoster(motionFile: File, mime: String): Bitmap? = runCatching {
        if (mime == "image/gif" || mime == "image/webp") {
            val source = ImageDecoder.createSource(motionFile)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.setTargetSampleSize(
                    maxOf(1, maxOf(info.size.width, info.size.height) / MotionLimits.MAX_HEIGHT),
                )
            }
        } else {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(motionFile.absolutePath)
                retriever.getFrameAtTime(1_000_000L)
                    ?: retriever.getFrameAtTime(0L)
            }
        }
    }.getOrNull()

    /** Cheap decode check so a corrupt still degrades to a message, not a broken image. */
    private fun isDecodableImage(file: File): Boolean {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
        return opts.outWidth > 0 && opts.outHeight > 0
    }

    private fun wallpaperDir(): File = File(context.filesDir, "wallpaper").apply { mkdirs() }

    /** Deletes every file in the wallpaper dir except the ones just applied (pair-safe). */
    private suspend fun pruneWallpaperDir(keep: List<File>) {
        val keepNames = keep.map { it.name }.toSet()
        withContext(Dispatchers.IO) {
            wallpaperDir().listFiles()?.forEach { f ->
                if (f.name !in keepNames) runCatching { f.delete() }
            }
        }
    }

    fun clearWallpaper() {
        viewModelScope.launch {
            val current = context.pfpDataStore.data.first()
            val poster = current[KEY_CUSTOM_WALLPAPER]
            val motion = current[KEY_MOTION_WALLPAPER]
            // Clear BOTH keys — a leftover motion path with a cleared poster is the invalid
            // state the poster invariant exists to prevent — and the survey with them, since a
            // map describing a deleted wallpaper is worse than no map at all.
            save {
                it.remove(KEY_CUSTOM_WALLPAPER)
                it.remove(KEY_MOTION_WALLPAPER)
                it.clearWallpaperLuma()
            }
            // Then the files (prefs gone first, so nothing references them while they delete).
            withContext(Dispatchers.IO) {
                poster?.let { runCatching { File(it).delete() } }
                motion?.let { runCatching { File(it).delete() } }
            }
            _wallpaperMessage.value = "Wallpaper reset to default"
        }
    }

    // Hidden-app management now lives in its own screen (AppVisibilityViewModel), reached from
    // Display ▸ Hidden Apps — it lists every app with a per-app show/hide toggle.

    fun dismissWallpaperMessage() {
        _wallpaperMessage.value = null
    }

    fun showWallpaperPreview() {
        if (uiState.value.customWallpaperPath != null) {
            _wallpaperPreviewVisible.value = true
        } else {
            _wallpaperMessage.value = "No wallpaper selected yet"
        }
    }

    fun hideWallpaperPreview() {
        _wallpaperPreviewVisible.value = false
    }

    private fun save(block: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        viewModelScope.launch { context.pfpDataStore.edit { block(it) } }
    }
}
