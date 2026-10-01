package com.playfieldportal.core.ui.components

import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.NotificationDetail
import com.playfieldportal.core.domain.model.NoteFact
import com.playfieldportal.core.domain.model.NoteSection
import com.playfieldportal.core.domain.model.ResultItem
import com.playfieldportal.core.domain.model.ResultOutcome
import com.playfieldportal.core.domain.model.ResultsLabels
import com.playfieldportal.core.ui.sound.MenuSound
import com.playfieldportal.core.ui.sound.MenuSoundSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the Notes and Results sheets move. The rules that matter: the sheet opens where the trouble
 * is (Failed first), L1/R1 never land on an empty tab, ✕ falls back from the item to the row to
 * nothing, and ○ always goes back to the panel.
 */
class PfpDetailSheetNavTest {

    private val sounds = mutableListOf<MenuSound>()
    private val sink = MenuSoundSink { sounds += it }

    private fun results(vararg outcomes: ResultOutcome) = NotificationDetail.Results(
        items = outcomes.mapIndexed { i, o -> ResultItem("item $i", o) },
    )

    // ── Filters ───────────────────────────────────────────────────────────────

    @Test
    fun `the sheet opens on Failed when anything failed and on All otherwise`() {
        assertEquals(ResultFilter.FAILED, PfpDetailSheetNav.initialFilter(results(ResultOutcome.DONE, ResultOutcome.FAILED)))
        assertEquals(ResultFilter.ALL, PfpDetailSheetNav.initialFilter(results(ResultOutcome.DONE, ResultOutcome.SKIPPED)))
    }

    @Test
    fun `only buckets with items are offered, after All`() {
        assertEquals(
            listOf(ResultFilter.ALL, ResultFilter.FAILED, ResultFilter.DONE),
            PfpDetailSheetNav.filters(results(ResultOutcome.FAILED, ResultOutcome.DONE)),
        )
    }

    @Test
    fun `L1 and R1 cycle the offered filters and wrap`() {
        val r = results(ResultOutcome.FAILED, ResultOutcome.DONE)
        assertEquals(ResultFilter.FAILED, PfpDetailSheetNav.cycleFilter(ResultFilter.ALL, r, +1))
        assertEquals(ResultFilter.DONE, PfpDetailSheetNav.cycleFilter(ResultFilter.FAILED, r, +1))
        assertEquals(ResultFilter.ALL, PfpDetailSheetNav.cycleFilter(ResultFilter.DONE, r, +1))
        assertEquals(ResultFilter.DONE, PfpDetailSheetNav.cycleFilter(ResultFilter.ALL, r, -1))
    }

    @Test
    fun `a filter shows only its bucket and All shows everything`() {
        val r = results(ResultOutcome.FAILED, ResultOutcome.DONE, ResultOutcome.FAILED)
        assertEquals(3, PfpDetailSheetNav.visible(r, ResultFilter.ALL).size)
        assertEquals(2, PfpDetailSheetNav.visible(r, ResultFilter.FAILED).size)
        assertEquals(0, PfpDetailSheetNav.visible(r, ResultFilter.SKIPPED).size)
    }

    // ── Results input ─────────────────────────────────────────────────────────

    private data class Calls(
        var cursor: Int? = null,
        var filter: ResultFilter? = null,
        var item: ResultItem? = null,
        var fallback: Int = 0,
        var copied: Int = 0,
        var closed: Int = 0,
    )

    private fun pressResults(
        action: GamepadAction,
        r: NotificationDetail.Results,
        filter: ResultFilter = ResultFilter.ALL,
        cursor: Int = 0,
        hasFallback: Boolean = false,
    ): Calls {
        val calls = Calls()
        PfpDetailSheetNav.handleResults(
            action = action, detail = r, filter = filter, cursor = cursor,
            hasFallbackAction = hasFallback, sounds = sink,
            onCursorChange = { calls.cursor = it },
            onFilterChange = { calls.filter = it },
            onItemAction = { calls.item = it },
            onFallbackAction = { calls.fallback++ },
            onCopy = { calls.copied++ },
            onClose = { calls.closed++ },
        )
        return calls
    }

    @Test
    fun `up and down move the list cursor and clamp silently at the ends`() {
        val r = results(ResultOutcome.FAILED, ResultOutcome.FAILED)
        assertEquals(1, pressResults(GamepadAction.NAVIGATE_DOWN, r).cursor)
        sounds.clear()
        assertNull(pressResults(GamepadAction.NAVIGATE_DOWN, r, cursor = 1).cursor)
        assertTrue(sounds.isEmpty())
    }

    @Test
    fun `changing the filter resets the cursor`() {
        val calls = pressResults(GamepadAction.NEXT_CATEGORY, results(ResultOutcome.FAILED, ResultOutcome.DONE), cursor = 1)
        assertEquals(ResultFilter.FAILED, calls.filter)
        assertEquals(0, calls.cursor)
    }

    @Test
    fun `confirm runs the focused item's action, then the row's, then nothing`() {
        val withItemAction = NotificationDetail.Results(
            items = listOf(ResultItem("PSP", ResultOutcome.FAILED,
                action = com.playfieldportal.core.domain.model.DetailAction("open_memory_card", "psp"))),
        )
        assertEquals("PSP", pressResults(GamepadAction.SELECT, withItemAction, hasFallback = true).item?.primary)

        val plain = results(ResultOutcome.FAILED)
        assertEquals(1, pressResults(GamepadAction.SELECT, plain, hasFallback = true).fallback)

        val none = pressResults(GamepadAction.SELECT, plain, hasFallback = false)
        assertNull(none.item)
        assertEquals(0, none.fallback)
    }

    @Test
    fun `triangle copies and back closes`() {
        val r = results(ResultOutcome.DONE)
        assertEquals(1, pressResults(GamepadAction.OPEN_CONTEXT_MENU, r).copied)
        assertEquals(1, pressResults(GamepadAction.BACK, r).closed)
    }

    // ── Notes input ───────────────────────────────────────────────────────────

    @Test
    fun `notes act, copy, open the diagnostic, scroll and close`() {
        var acted = 0; var copied = 0; var toggled = 0; var closed = 0; var scrolled = 0
        fun press(action: GamepadAction, hasAction: Boolean = true) = PfpDetailSheetNav.handleNotes(
            action = action, hasAction = hasAction, hasDiagnostic = true, sounds = sink,
            onScroll = { scrolled += it }, onAction = { acted++ }, onCopy = { copied++ },
            onToggleDiagnostic = { toggled++ }, onClose = { closed++ },
        )
        press(GamepadAction.SELECT)
        press(GamepadAction.SELECT, hasAction = false)
        press(GamepadAction.OPEN_CONTEXT_MENU)
        press(GamepadAction.NAVIGATE_RIGHT)
        press(GamepadAction.NAVIGATE_DOWN)
        press(GamepadAction.NAVIGATE_UP)
        press(GamepadAction.BACK)

        assertEquals(1, acted)
        assertEquals(1, copied)
        assertEquals(1, toggled)
        assertEquals(0, scrolled)
        assertEquals(1, closed)
    }

    // ── Copy text ─────────────────────────────────────────────────────────────

    @Test
    fun `copied notes carry the title, code, sections, facts and diagnostic`() {
        val text = DetailSheetText.notes(
            "Couldn't launch Ape Escape",
            NotificationDetail.Notes(
                summary = "It never came to the front.",
                sections = listOf(NoteSection("Why", "It closed.")),
                facts = listOf(NoteFact("Emulator", "DuckStation")),
                code = "LN-4003",
                diagnostic = "game=42",
            ),
        )
        listOf("Couldn't launch Ape Escape", "LN-4003", "It never came to the front.", "Why", "It closed.",
            "Emulator: DuckStation", "game=42").forEach { assertTrue("missing $it", text.contains(it)) }
    }

    @Test
    fun `a copied list has one line per item with its outcome label`() {
        val text = DetailSheetText.results(
            "Artwork fetch finished",
            listOf(
                ResultItem("Spyro", ResultOutcome.DONE),
                ResultItem("Gex", ResultOutcome.FAILED, code = "AR-3003", reason = "No match"),
            ),
            ResultsLabels(done = "Updated"),
        )
        val lines = text.lines()
        assertEquals("Artwork fetch finished", lines.first())
        assertTrue(lines.contains("Updated · Spyro"))
        assertTrue(lines.contains("Failed · Gex · AR-3003 · No match"))
        assertFalse(text.contains("null"))
    }
}
