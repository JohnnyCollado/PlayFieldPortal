package com.playfieldportal.core.data.repository

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.playfieldportal.themekit.PfpThemeManifest
import com.playfieldportal.themekit.ThemeLegibility
import com.playfieldportal.themekit.ThemeParameterFields
import com.playfieldportal.themekit.XmbLayoutSpec
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The theme parameters a manifest carries and the device prefs they become, declared once: apply
 * writes each, reset removes each, Save as Theme reads each back. A parameter added to the table is
 * covered by all three at once — none of them can forget it.
 */
class ThemeParametersTest {

    /** A manifest that sets every parameter to a non-default value. */
    private val full = PfpThemeManifest(
        name = "Full",
        accentColor = "#FF72B1",
        iconColor = "#00FF00",
        textColor = "#112233",
        subTextColor = "#445566",
        waveStyle = PfpThemeManifest.WAVE_STATIC,
        waveStyleV4 = PfpThemeManifest.WAVE_REDUCED_STATIC,
        layout = XmbLayoutSpec(barTopFraction = 0.2f),
        textColorExact = true,
        legibility = ThemeLegibility(text = "plate", icon = "contour_dark", solidUnfocusedIcons = true),
    )

    @Test
    fun `every manifest parameter field the Studio authors is applied here`() {
        assertEquals(ThemeParameterFields.ALL, ThemeParameters.ALL.flatMap { it.fields }.toSet())
    }

    @Test
    fun `apply then reset leaves no theme parameter behind`() {
        val prefs = mutablePreferencesOf()
        ThemeParameters.apply(full, prefs)
        assertEquals(ThemeParameters.keys.toSet(), prefs.asMap().keys, "apply writes every parameter")

        ThemeParameters.reset(prefs)

        assertTrue(prefs.asMap().isEmpty(), "reset removes every parameter apply can write: ${prefs.asMap().keys}")
    }

    @Test
    fun `every parameter round-trips through apply and Save as Theme`() {
        val prefs = mutablePreferencesOf()
        ThemeParameters.apply(full, prefs)

        val saved = ThemeParameters.save(prefs, PfpThemeManifest(name = "Saved", accentColor = ""))

        assertEquals(full.accentColor, saved.accentColor)
        assertEquals(full.iconColor, saved.iconColor)
        assertEquals(full.textColor, saved.textColor)
        assertEquals(full.subTextColor, saved.subTextColor)
        assertEquals(full.waveStyle, saved.waveStyle)
        assertEquals(full.waveStyleV4, saved.waveStyleV4)
        assertEquals(full.layout, saved.layout)
        assertEquals(full.textColorExact, saved.textColorExact)
        assertEquals(full.legibility, saved.legibility)
    }

    @Test
    fun `the wave style maps both ways through one table`() {
        for (exact in listOf(
            PfpThemeManifest.WAVE_ANIMATED,
            PfpThemeManifest.WAVE_REDUCED,
            PfpThemeManifest.WAVE_STATIC,
            PfpThemeManifest.WAVE_REDUCED_STATIC,
        )) {
            val prefs = mutablePreferencesOf()
            ThemeParameters.apply(PfpThemeManifest(name = "W", accentColor = "", waveStyleV4 = exact), prefs)
            val saved = ThemeParameters.save(prefs, PfpThemeManifest(name = "S", accentColor = ""))
            assertEquals(exact, saved.waveStyleV4, "wave $exact")
        }
    }

    @Test
    fun `a theme that says nothing clears its colours but leaves the user's legibility alone`() {
        val prefs = mutablePreferencesOf()
        ThemeParameters.apply(full, prefs)

        ThemeParameters.apply(PfpThemeManifest(name = "Plain", accentColor = ""), prefs)

        assertNull(prefs[ThemePrefKeys.ACCENT_OVERRIDE])
        assertNull(prefs[ThemePrefKeys.TEXT_COLOR], "an auto text colour removes the previous theme's")
        assertNull(prefs[ThemePrefKeys.SUB_TEXT_COLOR])
        assertNull(prefs[ThemePrefKeys.THEME_LAYOUT], "the default geometry is stored as no override")
        assertEquals("PLATE", prefs[ThemePrefKeys.TEXT_LEGIBILITY], "legibility is the user's unless a theme carries it")
        assertEquals(true, prefs[ThemePrefKeys.TEXT_COLOR_EXACT])
    }

    @Test
    fun `Save as Theme of an untouched device writes the stock values`() {
        val saved = ThemeParameters.save(mutablePreferencesOf(), PfpThemeManifest(name = "Stock", accentColor = ""))

        assertEquals("", saved.accentColor)
        assertEquals(PfpThemeManifest.ICON_COLOR_AUTO, saved.iconColor)
        assertEquals(PfpThemeManifest.ICON_COLOR_AUTO, saved.textColor)
        assertNull(saved.subTextColor)
        assertNull(saved.layout)
        assertEquals(PfpThemeManifest.WAVE_ANIMATED, saved.waveStyleV4)
        assertEquals(false, saved.textColorExact)
        assertEquals(ThemeLegibility(text = "auto", icon = "none", solidUnfocusedIcons = false), saved.legibility)
    }
}
