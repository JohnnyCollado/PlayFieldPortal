package com.playfieldportal.studio

import com.playfieldportal.studio.ui.sections.DeviceHints
import com.playfieldportal.studio.ui.sections.LEGIBILITY_ICON_CHOICES
import com.playfieldportal.studio.ui.sections.LEGIBILITY_TEXT_CHOICES
import com.playfieldportal.studio.ui.sections.exportFormatLine
import com.playfieldportal.studio.ui.sections.originLine
import com.playfieldportal.studio.ui.sections.upgradeSections
import com.playfieldportal.themekit.MANIFEST_DESCRIPTION_MAX
import com.playfieldportal.themekit.PfpThemeSource
import com.playfieldportal.themekit.ThemeLegibility
import com.playfieldportal.themekit.UpgradeReport
import com.playfieldportal.themekit.XmbLayoutAdjust
import java.time.LocalDate
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/** TS-28: VM setters for the v4 fields, the preview-only adjust store, and the pure panel helpers. */
class PanelsATest {

    private val vm = StudioViewModel(CoroutineScope(Dispatchers.Unconfined))
    private val today = LocalDate.of(2026, 10, 2)
    private val nodes = mutableListOf<Preferences>()

    @AfterTest
    fun cleanup() {
        nodes.forEach { runCatching { it.removeNode() } }
    }

    private fun node(): Preferences =
        Preferences.userRoot().node("pfp-studio-test-${UUID.randomUUID()}").also { nodes += it }

    private fun manifest() = vm.buildManifest(today = today)

    // ── Info ────────────────────────────────────────────────────────────────

    @Test
    fun `author and description are undoable and exported`() {
        vm.setAuthor("Johnny")
        vm.setDescription("A calm blue theme")
        assertEquals("Johnny", manifest().author)
        assertEquals("A calm blue theme", manifest().description)
        vm.undo()
        assertNull(vm.state.value.description)
        assertEquals("Johnny", vm.state.value.author)
        vm.undo()
        assertNull(vm.state.value.author)
    }

    @Test
    fun `blank author and description become null and description is capped like the codec`() {
        vm.setAuthor("   ")
        vm.setDescription("")
        assertNull(vm.state.value.author)
        assertNull(vm.state.value.description)
        vm.setDescription("x".repeat(MANIFEST_DESCRIPTION_MAX + 50))
        assertEquals(MANIFEST_DESCRIPTION_MAX, vm.state.value.description?.length)
    }

    @Test
    fun `typing a burst into author is one undo step`() {
        "Joh".indices.forEach { vm.setAuthor("Joh".substring(0, it + 1)) }
        vm.undo()
        assertNull(vm.state.value.author)
    }

    @Test
    fun `origin line names the ptf source or the created date`() {
        val ptf = StudioState(source = PfpThemeSource(PfpThemeSource.TYPE_PTF_IMPORT, "x.ptf", "6.60"))
        assertEquals("Imported from x.ptf — firmware 6.60", originLine(ptf))
        assertEquals("Created 2026-01-05", originLine(StudioState(created = "2026-01-05")))
        assertEquals("New theme — not saved yet", originLine(StudioState()))
    }

    // ── Color ───────────────────────────────────────────────────────────────

    @Test
    fun `use my exact color is undoable, exported, and off means unspecified`() {
        assertNull(manifest().textColorExact)
        vm.setTextColorExact(true)
        assertEquals(true, manifest().textColorExact)
        vm.setTextColorExact(false)
        assertNull(manifest().textColorExact)
        vm.undo()
        assertEquals(true, vm.state.value.textColorExact)
    }

    // ── Legibility ──────────────────────────────────────────────────────────

    @Test
    fun `legibility setters merge into one object, export, and undo`() {
        vm.setTextLegibility("outline")
        vm.setIconLegibility("contour_auto")
        vm.setSolidUnfocusedIcons(true)
        assertEquals(ThemeLegibility("outline", "contour_auto", true), manifest().legibility)
        vm.undo()
        assertEquals(ThemeLegibility("outline", "contour_auto", null), vm.state.value.legibility)
    }

    @Test
    fun `clearing every legibility field returns to not set, unknown values are refused`() {
        vm.setTextLegibility("plate")
        vm.setTextLegibility(null)
        assertNull(vm.state.value.legibility)
        vm.setTextLegibility("glitter")
        vm.setIconLegibility("sparkle")
        assertNull(vm.state.value.legibility)
        vm.setSolidUnfocusedIcons(true)
        vm.setSolidUnfocusedIcons(false)
        assertEquals(ThemeLegibility(solidUnfocusedIcons = false), vm.state.value.legibility)
    }

    @Test
    fun `legibility choices cover every codec value exactly`() {
        assertEquals(ThemeLegibility.TEXT_VALUES, LEGIBILITY_TEXT_CHOICES.map { it.first }.toSet())
        assertEquals(ThemeLegibility.ICON_VALUES, LEGIBILITY_ICON_CHOICES.map { it.first }.toSet())
        assertEquals(5, LEGIBILITY_TEXT_CHOICES.size)
        assertEquals(6, LEGIBILITY_ICON_CHOICES.size)
    }

    // ── Preview-only adjust (never saved) ───────────────────────────────────

    @Test
    fun `adjust values clamp and snap to their steps`() {
        val store = PreviewAdjustStore(node())
        store.setScale(1.013f)
        assertEquals(1.02f, store.adjust.value.scale)
        store.setScale(9f)
        assertEquals(XmbLayoutAdjust.SCALE_MAX, store.adjust.value.scale)
        store.setScale(0f)
        assertEquals(XmbLayoutAdjust.SCALE_MIN, store.adjust.value.scale)
        store.setLeft(-1f)
        assertEquals(XmbLayoutAdjust.LEFT_MIN, store.adjust.value.barLeftFraction)
        store.setLeft(0.1234f)
        assertEquals(0.12f, store.adjust.value.barLeftFraction)
        store.setTop(0.9f)
        assertEquals(XmbLayoutAdjust.TOP_MAX, store.adjust.value.barTopFraction)
        store.setTop(0.2f)
        store.setTop(Float.NaN)
        assertEquals(0.2f, store.adjust.value.barTopFraction)
    }

    @Test
    fun `adjust values are remembered per install and reset restores defaults`() {
        val prefs = node()
        val first = PreviewAdjustStore(prefs)
        assertFalse(first.enabled.value)
        first.setScale(1.5f)
        first.setLeft(-0.1f)
        first.setTop(0.3f)
        first.setEnabled(true)
        val second = PreviewAdjustStore(prefs)
        assertEquals(XmbLayoutAdjust(1.5f, -0.1f, 0.3f), second.adjust.value)
        assertTrue(second.enabled.value)
        second.reset()
        assertEquals(XmbLayoutAdjust.DEFAULT, PreviewAdjustStore(prefs).adjust.value)
    }

    @Test
    fun `biblically accurate fills all three axes from the preset`() {
        val store = PreviewAdjustStore(node())
        store.applyBiblicallyAccurate()
        val thor = com.playfieldportal.themekit.XmbLayoutPreset.computeForWindow(1920f, 1080f, 369f)
        assertTrue(com.playfieldportal.themekit.XmbLayoutPreset.matches(store.adjust.value, thor))
        assertNotEquals(XmbLayoutAdjust.DEFAULT, store.adjust.value)
    }

    @Test
    fun `adjusting the preview never touches the document, the manifest, or undo`() {
        val before = manifest()
        val stateBefore = vm.state.value
        val store = PreviewAdjustStore(node())
        store.setScale(1.7f)
        store.setLeft(0.3f)
        store.setTop(0.4f)
        store.setEnabled(true)
        store.applyBiblicallyAccurate()
        assertEquals(before, manifest())
        assertEquals(stateBefore, vm.state.value)
        assertFalse(vm.canUndo.value)
        assertNull(manifest().layout)
    }

    // ── Export check + hints ────────────────────────────────────────────────

    @Test
    fun `format line says schema N to current`() {
        assertEquals("Format: schema 2 → 4", exportFormatLine(2))
        assertEquals("Format: schema 4 → 4", exportFormatLine(4))
        assertEquals("Format: new theme → 4", exportFormatLine(null))
    }

    @Test
    fun `upgrade sections list only the non-empty groups in order`() {
        val report = UpgradeReport(kept = listOf("a"), added = emptyList(), repaired = listOf("b"), cantRecover = listOf("c", "d"))
        assertEquals(
            listOf("Kept" to listOf("a"), "Repaired" to listOf("b"), "Can't recover" to listOf("c", "d")),
            upgradeSections(report),
        )
        assertTrue(upgradeSections(null).isEmpty())
    }

    @Test
    fun `device hints name the real settings paths`() {
        assertEquals("Settings › Interface › Display › Adjust XMB Layout", DeviceHints.ADJUST_LAYOUT)
        assertEquals("Settings › Interface › Sound", DeviceHints.SOUND)
        assertEquals("Settings › Interface › Display › Show Boot Sequence / GameBoot", DeviceHints.BOOT)
        assertTrue("Battery Saver" in DeviceHints.WAVE_FREEZE && "Thermal Throttle" in DeviceHints.WAVE_FREEZE)
    }
}
