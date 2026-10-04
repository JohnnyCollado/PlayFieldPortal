package com.playfieldportal.core.data.repository

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.playfieldportal.themekit.PfpThemeManifest
import com.playfieldportal.themekit.ThemeLegibility
import com.playfieldportal.themekit.WaveStyles
import com.playfieldportal.themekit.XmbLayoutSpec
import com.playfieldportal.themekit.XmbLayoutSpecCodec

/**
 * One theme parameter: the manifest [fields] it reads and the device pref it becomes. [fromManifest]
 * says what applying a theme does to the pref; [intoManifest] writes the pref's current value back
 * into a manifest being saved (Save as Theme).
 */
internal class ThemeParameter<T : Any>(
    val key: Preferences.Key<T>,
    /** The manifest JSON fields this parameter carries (ThemeParameterFields names). */
    val fields: Set<String>,
    private val fromManifest: (PfpThemeManifest) -> Write<T>,
    private val intoManifest: (PfpThemeManifest, T?) -> PfpThemeManifest,
) {
    /** What applying a theme does to one pref. */
    sealed interface Write<out T> {
        data class Set<T>(val value: T) : Write<T>

        /** The theme says "none" (or "auto"): the previous theme's value must not linger. */
        data object Remove : Write<Nothing>

        /** The theme says nothing: the value is the user's, and stays. */
        data object Keep : Write<Nothing>
    }

    fun apply(manifest: PfpThemeManifest, prefs: MutablePreferences) {
        when (val write = fromManifest(manifest)) {
            is Write.Set -> prefs[key] = write.value
            Write.Remove -> prefs.remove(key)
            Write.Keep -> Unit
        }
    }

    fun save(prefs: Preferences, manifest: PfpThemeManifest): PfpThemeManifest = intoManifest(manifest, prefs[key])
}

/**
 * The theme parameters, declared once. [PfpThemeStore.applyDetailed] writes each, its reset removes
 * each, and Save as Theme reads each back — so a parameter added here is applied, reset and exported
 * at once, and none of the three can forget it. Wallpaper, motion, icons and media are not prefs
 * alone (they carry files) and stay bespoke steps around this table.
 *
 * Two contracts live in the [ThemeParameter.Write]s: a colour or geometry the theme leaves on
 * "auto" REMOVES the pref (a theme that says nothing about text must not leave the last theme's
 * colour behind), while legibility and the exact-colour flag are Display settings the user owns —
 * a theme writes them only when it carries them.
 */
internal object ThemeParameters {

    private fun hexArgb(hex: String?): Long? {
        val digits = hex?.removePrefix("#") ?: return null
        if (digits.length != 6) return null
        return digits.toLongOrNull(16)?.let { 0xFF000000L or it }
    }

    private fun hexOf(argb: Long): String = "#%06X".format(argb and 0xFFFFFF)

    private fun <T : Any> setOrRemove(value: T?): ThemeParameter.Write<T> =
        if (value != null) ThemeParameter.Write.Set(value) else ThemeParameter.Write.Remove

    private fun <T : Any> setOrKeep(value: T?): ThemeParameter.Write<T> =
        if (value != null) ThemeParameter.Write.Set(value) else ThemeParameter.Write.Keep

    /** Manifest wave value ↔ the device's WAVE_STYLE pref, both ways from one table. */
    private val WAVE_PREFS = mapOf(
        PfpThemeManifest.WAVE_ANIMATED to "ANIMATED",
        PfpThemeManifest.WAVE_REDUCED to "REDUCED",
        PfpThemeManifest.WAVE_STATIC to "STATIC",
        PfpThemeManifest.WAVE_REDUCED_STATIC to "REDUCED_STATIC",
    )

    // TextLegibilityStyle.DEFAULT / IconLegibilityStyle.DEFAULT names (core-domain is not a dependency here).
    private const val DEFAULT_TEXT_LEGIBILITY = "AUTO"
    private const val DEFAULT_ICON_LEGIBILITY = "NONE"

    private fun PfpThemeManifest.withLegibility(edit: (ThemeLegibility) -> ThemeLegibility) =
        copy(legibility = edit(legibility ?: ThemeLegibility()))

    val ALL: List<ThemeParameter<*>> = listOf(
        ThemeParameter(
            ThemePrefKeys.ACCENT_OVERRIDE,
            fields = setOf("accentColor"),
            fromManifest = { setOrRemove(hexArgb(it.accentColor)) },
            intoManifest = { m, v -> m.copy(accentColor = v?.let(::hexOf) ?: "") },
        ),
        ThemeParameter(
            ThemePrefKeys.ICON_COLOR,
            fields = setOf("iconColor"),
            fromManifest = { setOrRemove(hexArgb(it.iconColor.takeIf { c -> c != PfpThemeManifest.ICON_COLOR_AUTO })) },
            intoManifest = { m, v -> m.copy(iconColor = v?.let(::hexOf) ?: PfpThemeManifest.ICON_COLOR_AUTO) },
        ),
        ThemeParameter(
            ThemePrefKeys.TEXT_COLOR,
            fields = setOf("textColor"),
            fromManifest = { setOrRemove(hexArgb(it.textColor.takeIf { c -> c != PfpThemeManifest.ICON_COLOR_AUTO })) },
            intoManifest = { m, v -> m.copy(textColor = v?.let(::hexOf) ?: PfpThemeManifest.ICON_COLOR_AUTO) },
        ),
        // Absent removes the pref, so sub text falls back to the main colour rather than keeping
        // the previous theme's.
        ThemeParameter(
            ThemePrefKeys.SUB_TEXT_COLOR,
            fields = setOf("subTextColor"),
            fromManifest = { setOrRemove(hexArgb(it.subTextColor)) },
            intoManifest = { m, v -> m.copy(subTextColor = v?.let(::hexOf)) },
        ),
        // resolveExact honours waveStyleV4 first, so reduced+static is reachable; unknown values
        // fail safe to the animated wave rather than persisting an invalid enum.
        ThemeParameter(
            ThemePrefKeys.WAVE_STYLE,
            fields = setOf("waveStyle", "waveStyleV4"),
            fromManifest = { ThemeParameter.Write.Set(WAVE_PREFS.getValue(WaveStyles.resolveExact(it))) },
            intoManifest = { m, v ->
                val exact = WAVE_PREFS.entries.firstOrNull { it.value == v }?.key ?: PfpThemeManifest.WAVE_ANIMATED
                val (legacy, v4) = WaveStyles.encode(exact)
                m.copy(waveStyle = legacy, waveStyleV4 = v4)
            },
        ),
        // Per-theme XMB geometry, sanitized here AND on read so a hostile manifest can never wedge
        // the crossbar offscreen. The default geometry is stored as no override.
        ThemeParameter(
            ThemePrefKeys.THEME_LAYOUT,
            fields = setOf("layout"),
            fromManifest = { m ->
                setOrRemove(
                    m.layout?.let(XmbLayoutSpecCodec::sanitize)
                        ?.takeUnless { it == XmbLayoutSpec.DEFAULT }
                        ?.let(XmbLayoutSpecCodec::encode),
                )
            },
            intoManifest = { m, v ->
                m.copy(
                    layout = v?.let(XmbLayoutSpecCodec::decode)
                        ?.let(XmbLayoutSpecCodec::sanitize)
                        ?.takeUnless { it == XmbLayoutSpec.DEFAULT },
                )
            },
        ),
        // The codec already sanitized unknown enum strings to null, so null means "absent".
        ThemeParameter(
            ThemePrefKeys.TEXT_LEGIBILITY,
            fields = setOf("legibility"),
            fromManifest = { setOrKeep(it.legibility?.text?.uppercase()) },
            intoManifest = { m, v -> m.withLegibility { it.copy(text = (v ?: DEFAULT_TEXT_LEGIBILITY).lowercase()) } },
        ),
        ThemeParameter(
            ThemePrefKeys.ICON_LEGIBILITY,
            fields = setOf("legibility"),
            fromManifest = { setOrKeep(it.legibility?.icon?.uppercase()) },
            intoManifest = { m, v -> m.withLegibility { it.copy(icon = (v ?: DEFAULT_ICON_LEGIBILITY).lowercase()) } },
        ),
        ThemeParameter(
            ThemePrefKeys.SOLID_UNFOCUSED_ICONS,
            fields = setOf("legibility"),
            fromManifest = { setOrKeep(it.legibility?.solidUnfocusedIcons) },
            intoManifest = { m, v -> m.withLegibility { it.copy(solidUnfocusedIcons = v ?: false) } },
        ),
        ThemeParameter(
            ThemePrefKeys.TEXT_COLOR_EXACT,
            fields = setOf("textColorExact"),
            fromManifest = { setOrKeep(it.textColorExact) },
            intoManifest = { m, v -> m.copy(textColorExact = v ?: false) },
        ),
    )

    /** Every pref a theme parameter can write. */
    val keys: List<Preferences.Key<*>> = ALL.map { it.key }

    /** Writes [manifest]'s parameters into [prefs]. */
    fun apply(manifest: PfpThemeManifest, prefs: MutablePreferences) = ALL.forEach { it.apply(manifest, prefs) }

    /** Removes every theme parameter, back to the stock look. */
    fun reset(prefs: MutablePreferences) = keys.forEach { prefs.remove(it) }

    /** [into] with every parameter read back from [prefs]. */
    fun save(prefs: Preferences, into: PfpThemeManifest): PfpThemeManifest =
        ALL.fold(into) { manifest, parameter -> parameter.save(prefs, manifest) }
}
