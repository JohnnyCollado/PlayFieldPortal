package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.domain.model.IconLegibilityStyle
import com.playfieldportal.core.domain.model.TouchNavButtonMode
import com.playfieldportal.core.domain.model.TouchSensitivity
import com.playfieldportal.core.ui.motion.MotionWallpaperLimits
import com.playfieldportal.core.ui.wave.WaveStyle
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
internal val KEY_CUSTOM_WALLPAPER  = stringPreferencesKey("display_custom_wallpaper")
// Motion wallpaper (looping MP4/WebM/GIF). Must match XMBViewModel — shared cascade pref.
// INVARIANT: never set without KEY_CUSTOM_WALLPAPER — the poster is the fallback for both the
// freeze paths and decode failure, so a motion file without one is an unrenderable state.
// Enforced at the two write sites (import, clear) and on read ("motion set, poster missing"
// degrades to "no motion").
internal val KEY_MOTION_WALLPAPER  = stringPreferencesKey("display_motion_wallpaper")
// Must match XMBViewModel.KEY_MENU_SOUND_ENABLED — both read/write this same pref.
private val KEY_MENU_SOUND         = booleanPreferencesKey("sound_menu_enabled")
// Scale & Layout now live in the XMB's on-screen "Adjust XMB Layout" editor (see XMBViewModel);
// this screen only launches it, so the old scale/bar prefs and steppers were removed here.

private val SUPPORTED_WALLPAPER_MIME = setOf("image/png", "image/jpeg", "image/webp") + MotionWallpaperLimits.SUPPORTED_MIME

/** Mimes routed to the motion (looping) path rather than the plain still path. */
private val MOTION_WALLPAPER_MIME = MotionWallpaperLimits.SUPPORTED_MIME

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
    // Show the idle "Options" hint pill over XMB items with a context menu. Default on.
    val contextMenuHintDelaySeconds: Float = 2.5f,
    val touchSensitivity: TouchSensitivity = TouchSensitivity.NORMAL,
    val menuSoundEnabled: Boolean = true,
    // Confirm on a game launches it directly (true) or opens Game Detail first (false).
    val directLaunch: Boolean = false,
    val customWallpaperPath: String? = null,
    // Absolute path of the looping motion file, when one is set (and its poster exists).
    val motionWallpaperPath: String? = null,
    val wallpaperMessage: String? = null,
    val contextMenuHintEnabled: Boolean = true,
    val wallpaperImporting: Boolean = false,
    val wallpaperPreviewVisible: Boolean = false,
) {
    val waveStyleLabel: String get() = WAVE_STYLE_LABELS[waveStyle] ?: waveStyle.name
}

@HiltViewModel
class DisplaySettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _wallpaperMessage  = MutableStateFlow<String?>(null)
    private val _wallpaperImporting = MutableStateFlow(false)
    private val _wallpaperPreviewVisible = MutableStateFlow(false)

    val uiState: StateFlow<DisplaySettingsUiState> = combine(
        context.pfpDataStore.data,
        _wallpaperMessage,
        _wallpaperImporting,
        _wallpaperPreviewVisible,
    ) { prefs, msg, importing, previewVisible ->
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
            contextMenuHintEnabled = prefs[KEY_CONTEXT_MENU_HINT] ?: true,
            contextMenuHintDelaySeconds = (prefs[KEY_CONTEXT_MENU_HINT_DELAY_SECONDS] ?: 2.5f).coerceIn(1f, 5f),
            touchSensitivity     = TouchSensitivity.fromName(prefs[KEY_TOUCH_SENSITIVITY]),
            menuSoundEnabled     = prefs[KEY_MENU_SOUND]      ?: true,
            directLaunch         = prefs[KEY_DIRECT_LAUNCH]   ?: false,
            customWallpaperPath  = prefs[KEY_CUSTOM_WALLPAPER],
            motionWallpaperPath  = prefs[KEY_MOTION_WALLPAPER],
            wallpaperMessage     = msg,
            wallpaperImporting   = importing,
            wallpaperPreviewVisible = previewVisible,
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DisplaySettingsUiState())

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

    fun setShowBootSequence(v: Boolean)      = save { it[KEY_SHOW_BOOT]       = v }
    fun setShowBootOnResume(v: Boolean)      = save { it[KEY_BOOT_ON_RESUME]  = v }
    fun setThermalThrottleAware(v: Boolean)  = save { it[KEY_THERMAL_AWARE]   = v }
    fun setRespectBatterySaver(v: Boolean)   = save { it[KEY_RESPECT_BATTERY] = v }
    fun setMenuSoundEnabled(v: Boolean)      = save { it[KEY_MENU_SOUND]      = v }
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
            // Both keys together — even though a still import never sets the motion key, a
            // previous motion file must go when its poster is replaced.
            save {
                it[KEY_CUSTOM_WALLPAPER] = dest.absolutePath
                it.remove(KEY_MOTION_WALLPAPER)
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
        if (knownSize != null && knownSize > MotionWallpaperLimits.MAX_BYTES) {
            _wallpaperMessage.value = MotionWallpaperLimits.MSG_TOO_LARGE_BYTES
            return
        }

        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                motionDest.outputStream().use { out -> input.copyTo(out) }
            } != null
        }.getOrDefault(false)
        if (!copied) {
            runCatching { motionDest.delete() }
            _wallpaperMessage.value = MotionWallpaperLimits.MSG_UNDECODABLE
            return
        }

        // Probe the copied file (authoritative size even when the provider hid it).
        val probe = probeMotionFile(motionDest, mime)
        val rejection = probe?.let { MotionWallpaperLimits.validate(it) }
        if (probe == null || rejection != null) {
            runCatching { motionDest.delete() }
            _wallpaperMessage.value = rejection ?: MotionWallpaperLimits.MSG_UNDECODABLE
            return
        }

        // Poster extraction at IMPORT time, not render time: the frozen paths become literally
        // the existing still-wallpaper composable (Coil AsyncImage of a JPEG), battery-saver
        // costs what a static wallpaper costs, and a corrupt file degrades to a still instead
        // of a black screen.
        val poster = extractPoster(motionDest, mime)
        if (poster == null) {
            runCatching { motionDest.delete() }
            _wallpaperMessage.value = MotionWallpaperLimits.MSG_UNDECODABLE
            return
        }
        val posterOk = runCatching {
            posterDest.outputStream().use { poster.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            true
        }.getOrDefault(false)
        poster.recycle()
        if (!posterOk) {
            runCatching { motionDest.delete() }
            _wallpaperMessage.value = MotionWallpaperLimits.MSG_UNDECODABLE
            return
        }

        // THE invariant, enforced at the write site: motion is never set without its poster.
        save {
            it[KEY_CUSTOM_WALLPAPER] = posterDest.absolutePath
            it[KEY_MOTION_WALLPAPER] = motionDest.absolutePath
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
    private fun probeMotionFile(file: File, mime: String): MotionWallpaperLimits.Probe? = runCatching {
        if (mime == "image/gif" || mime == "image/webp") {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
            MotionWallpaperLimits.Probe(
                mime = mime,
                width = bounds.outWidth,
                height = bounds.outHeight,
                durationMs = 0L,
                bytes = file.length(),
            )
        } else {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(file.absolutePath)
                MotionWallpaperLimits.Probe(
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
                    maxOf(1, maxOf(info.size.width, info.size.height) / MotionWallpaperLimits.MAX_HEIGHT),
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
            // state the poster invariant exists to prevent.
            save {
                it.remove(KEY_CUSTOM_WALLPAPER)
                it.remove(KEY_MOTION_WALLPAPER)
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
