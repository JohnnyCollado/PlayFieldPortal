package com.playfieldportal.feature.xmb.viewmodel

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.data.repository.CustomIconStore
import com.playfieldportal.core.data.repository.ThemePrefKeys
import com.playfieldportal.core.data.repository.ThemeTiers
import com.playfieldportal.core.domain.model.UiMediaSlot
import com.playfieldportal.core.domain.model.XmbColorScheme
import com.playfieldportal.core.domain.model.XmbPalette
import com.playfieldportal.core.domain.model.lightBackgroundAnchors
import com.playfieldportal.core.domain.model.resolve
import com.playfieldportal.core.ui.icons.CustomIcon
import com.playfieldportal.core.ui.icons.XmbIcons
import com.playfieldportal.core.ui.media.UiMediaPaths
import com.playfieldportal.core.ui.media.bootDefaultAudioUri
import com.playfieldportal.core.ui.media.resolveBootAudio
import com.playfieldportal.core.ui.theme.DefaultPFPColors
import com.playfieldportal.core.ui.theme.PFPColors
import com.playfieldportal.themekit.XmbLayoutAdjust
import com.playfieldportal.themekit.XmbLayoutAdjustCodec
import com.playfieldportal.themekit.XmbLayoutSpec
import com.playfieldportal.themekit.XmbLayoutSpecCodec
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Everything the XMB draws from the applied theme and the Display settings that shape it. */
data class XmbThemeLook(
    /** The active preset's name; an [accent override][ThemePrefKeys.ACCENT_OVERRIDE] supersedes it. */
    val schemeName: String,
    val colors: PFPColors,
    /** Both icon tiers: the user's picks over the applied theme's. */
    val icons: XmbIcons,
    /** The theme's XMB geometry, with the user's bar position on top. */
    val layoutSpec: XmbLayoutSpec,
    /** Display ▸ Scale & Layout, clamped. */
    val xmbScale: Float,
    /** Per-form-factor Adjust XMB Layout tunings. */
    val layoutAdjustMap: Map<String, XmbLayoutAdjust>,
    /** The boot clip that plays (the user's, else the theme's), or null for the built-in animation. */
    val bootVideoPath: String?,
    /** The boot sound: the bundled chime with the built-in animation, none under a clip. */
    val bootAudioPath: String?,
)

/**
 * The launcher's theme look, as one [Flow]: XMBViewModel collects it and draws. Three inputs,
 * each re-read only when it moves — the colour and geometry prefs, the two icon tiers (decoded
 * only when their own stamp bumps, never for a text-colour edit), and the boot media (on the UI
 * media stamp).
 *
 * The seams are the prefs flow, the tier loader and [UiMediaPaths]: production hands in the
 * DataStore and [ThemeTiers], tests a [kotlinx.coroutines.flow.MutableStateFlow] and fakes.
 */
class ThemeLook internal constructor(
    private val prefs: Flow<Preferences>,
    private val loadIcons: suspend (ThemeTiers.Tier) -> Map<String, CustomIcon>,
    private val uiMedia: UiMediaPaths,
    private val packageName: String,
    private val month: () -> Int,
    /** Where the boot media lookup (file IO) runs; tests hand in their own dispatcher. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        tiers: ThemeTiers,
        uiMedia: UiMediaPaths,
    ) : this(context.pfpDataStore.data, tiers::loadIcons, uiMedia, context.packageName, { LocalDate.now().monthValue })

    /** The colour and geometry prefs the look is computed from. */
    private data class LookPrefs(
        val schemeName: String,
        val accentOverride: Long?,
        val iconColor: Long?,
        val layoutJson: String?,
        val xmbScale: Float?,
        val barTopOverride: Float?,
        val layoutAdjustJson: String?,
        val textColor: Long?,
        val subTextColor: Long?,
    )

    fun observe(): Flow<XmbThemeLook> {
        val look = prefs.map { p ->
            LookPrefs(
                schemeName = p[ThemePrefKeys.COLOR_SCHEME] ?: XmbColorScheme.CLASSIC_BLUE.name,
                accentOverride = p[ThemePrefKeys.ACCENT_OVERRIDE],
                iconColor = p[ThemePrefKeys.ICON_COLOR],
                layoutJson = p[ThemePrefKeys.THEME_LAYOUT],
                xmbScale = p[KEY_XMB_SCALE],
                barTopOverride = p[KEY_BAR_TOP_FRACTION],
                layoutAdjustJson = p[KEY_XMB_LAYOUT_ADJUST],
                textColor = p[ThemePrefKeys.TEXT_COLOR],
                subTextColor = p[ThemePrefKeys.SUB_TEXT_COLOR],
            )
        }.distinctUntilChanged()

        // Each tier reloads on its own stamp alone. A stamp's presence means the tier holds icons;
        // the value only bumps so this reloads. Two tiers by design: user picks survive theme
        // switches, theme icons don't.
        val userIcons = tierIcons(ThemeTiers.Tier.USER) { it[CustomIconStore.KEY_CUSTOM_ICONS_STAMP] }
        val themeIcons = tierIcons(ThemeTiers.Tier.THEME) { it[ThemePrefKeys.THEME_ICONS_STAMP] }

        // Resolved off the composition that draws the first frame: the lookup is file IO. A custom
        // boot video keeps its own track (resolveBootAudio pins that rule).
        val boot = uiMedia.stamp.distinctUntilChanged().map {
            withContext(io) {
                val video = uiMedia.pathFor(UiMediaSlot.BOOT_VIDEO)
                video to resolveBootAudio(customVideoPath = video, defaultUri = bootDefaultAudioUri(packageName))
            }
        }

        return combine(look, userIcons, themeIcons, boot) { p, user, theme, (video, audio) ->
            XmbThemeLook(
                schemeName = p.schemeName,
                colors = colorsOf(p),
                icons = XmbIcons(user = user, theme = theme),
                layoutSpec = layoutOf(p),
                xmbScale = (p.xmbScale ?: 1f).coerceIn(MIN_SCALE, MAX_SCALE),
                layoutAdjustMap = XmbLayoutAdjustCodec.decode(p.layoutAdjustJson),
                bootVideoPath = video,
                bootAudioPath = audio,
            )
        }
    }

    private fun tierIcons(tier: ThemeTiers.Tier, stamp: (Preferences) -> Long?): Flow<Map<String, CustomIcon>> =
        prefs.map(stamp).distinctUntilChanged().map { if (it != null) loadIcons(tier) else emptyMap() }

    private fun colorsOf(p: LookPrefs): PFPColors {
        val base = if (p.accentOverride != null) {
            // One accent drives everything: wave colour + re-derived gradient.
            DefaultPFPColors.withWaveTint(Color(p.accentOverride and 0xFFFFFFFFL))
        } else {
            // ORIGINAL re-resolves to the current month each time the scheme is (re)observed.
            val scheme = runCatching { XmbColorScheme.valueOf(p.schemeName) }.getOrDefault(XmbColorScheme.CLASSIC_BLUE)
            scheme.resolve(month()).toPFPColors()
        }
        // The user's font colour joins beside iconColor and by the same rule: absent = inherit the
        // theme's own value (white on every preset). Secondary keeps the 0.7 alpha relationship
        // toPFPColors establishes, so a picked colour carries its own sublabels.
        val picked = p.textColor?.let { Color(it and 0xFFFFFFFFL) }
        val text = picked ?: base.textPrimary
        return base.copy(
            iconColor = p.iconColor?.let { Color(it and 0xFFFFFFFFL) } ?: Color.White,
            textPrimary = text,
            textSecondary = text.copy(alpha = 0.7f),
            // The same pick, but null when unset: the crossbar, status strip and palette-driven
            // screens repaint only when the user actually chose one.
            textOverride = picked,
            subTextOverride = p.subTextColor?.let { Color(it and 0xFFFFFFFFL) },
        )
    }

    /**
     * Per-theme XMB geometry — lenient + sanitized, so a mangled pref can never wedge the crossbar
     * offscreen. The user's Display ▸ bar-position override wins over the theme's value.
     */
    private fun layoutOf(p: LookPrefs): XmbLayoutSpec {
        val theme = XmbLayoutSpecCodec.decode(p.layoutJson) ?: XmbLayoutSpec.DEFAULT
        return if (p.barTopOverride != null) {
            XmbLayoutSpecCodec.sanitize(theme.copy(barTopFraction = p.barTopOverride))
        } else {
            theme
        }
    }

    companion object {
        // Display ▸ Scale & Layout: whole-UI scale factor and the user's crossbar-position override.
        val KEY_XMB_SCALE = floatPreferencesKey("display_xmb_scale")
        val KEY_BAR_TOP_FRACTION = floatPreferencesKey("display_bar_top_fraction")

        // Per-form-factor "Adjust XMB Layout" tunings (JSON map, one prefs string).
        val KEY_XMB_LAYOUT_ADJUST = stringPreferencesKey("display_xmb_layout_adjust")

        private const val MIN_SCALE = 0.75f
        private const val MAX_SCALE = 1.3f
    }
}

internal fun XmbPalette.toPFPColors() = PFPColors(
    waveColor         = Color(waveColor),
    accentColor       = Color(accentColor),
    textPrimary       = Color(textColor),
    textSecondary     = Color(textColor).copy(alpha = 0.7f),
    backgroundOverlay = Color(0x88000000),
    selectedItem      = Color(accentColor),
    categoryBar       = Color(0x00000000),
    backgroundTop     = Color(backgroundTop),
    backgroundBottom  = Color(backgroundBottom),
)

/**
 * Sets the wave colour AND re-derives the light PSP background gradient from it, so the background
 * always matches whatever hue the wave is.
 */
internal fun PFPColors.withWaveTint(wave: Color): PFPColors {
    val anchors = lightBackgroundAnchors(wave.toArgb().toLong() and 0xFFFFFFFFL)
    return copy(waveColor = wave, backgroundTop = Color(anchors.first), backgroundBottom = Color(anchors.second))
}
