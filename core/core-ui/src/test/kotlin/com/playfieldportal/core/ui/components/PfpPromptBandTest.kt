package com.playfieldportal.core.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.NoteFact
import com.playfieldportal.core.domain.model.NoteSection
import com.playfieldportal.core.domain.model.NotificationDetail
import com.playfieldportal.core.domain.model.ResultItem
import com.playfieldportal.core.domain.model.ResultOutcome
import com.playfieldportal.core.ui.theme.PFPTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The prompt look: a full-screen tint and a band between two hairlines, with no card — drawn by
 * one host for every kind of spec, with no call site changed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w833dp-h468dp")
class PfpPromptBandTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var confirms = 0
    private var cancels = 0
    private var intercept: ((GamepadAction) -> Boolean)? = null

    private fun show(spec: PfpModalSpec, showHints: Boolean = true) {
        composeRule.setContent {
            PFPTheme {
                val host = rememberPfpModalHost(spec, showHints)
                intercept = host.intercept
                host.Content()
            }
        }
        composeRule.waitForIdle()
    }

    private fun confirmSpec(destructive: Boolean = false) = PfpModalSpec.Confirm(
        key = "confirm",
        title = "Delete Collection",
        message = "\"Survival Horror\" will be deleted.",
        confirmLabel = "Delete",
        destructive = destructive,
        onConfirm = { confirms++ },
        onCancel = { cancels++ },
    )

    // ── The band ────────────────────────────────────────────────────────────────

    @Test
    fun `the band is 75 percent of the screen width, centred, between two hairlines`() {
        show(confirmSpec())

        val screen = composeRule.onNodeWithTag(PfpModalTags.SCRIM).fetchSemanticsNode().boundsInRoot
        val band = composeRule.onNodeWithTag(PfpModalTags.CARD).fetchSemanticsNode().boundsInRoot
        assertEquals(screen.width * 0.75f, band.width, 1.5f)
        assertEquals(screen.center.x, band.center.x, 1.5f)

        val top = composeRule.onNodeWithTag(PfpModalTags.LINE_TOP).fetchSemanticsNode().boundsInRoot
        val bottom = composeRule.onNodeWithTag(PfpModalTags.LINE_BOTTOM).fetchSemanticsNode().boundsInRoot
        val oneDp = with(composeRule.density) { 1.dp.toPx() }
        assertEquals(oneDp, top.height, 0.5f)
        assertEquals(oneDp, bottom.height, 0.5f)
        assertEquals(band.width, top.width, 1.5f)
        assertEquals(band.width, bottom.width, 1.5f)
        assertEquals(band.top, top.top, 1.5f)
        assertEquals(band.bottom, bottom.bottom, 1.5f)
    }

    @Test
    fun `there is no card - the band is not the old 440dp box`() {
        show(confirmSpec())

        val band = composeRule.onNodeWithTag(PfpModalTags.CARD).fetchSemanticsNode().boundsInRoot
        val oldCard = with(composeRule.density) { 440.dp.toPx() }
        assertTrue("band ${band.width} must be wider than the old card $oldCard", band.width > oldCard + 20f)
    }

    @Test
    fun `the message and buttons sit between the two lines`() {
        show(confirmSpec())

        val top = composeRule.onNodeWithTag(PfpModalTags.LINE_TOP).fetchSemanticsNode().boundsInRoot
        val bottom = composeRule.onNodeWithTag(PfpModalTags.LINE_BOTTOM).fetchSemanticsNode().boundsInRoot
        val title = composeRule.onNodeWithText("Delete Collection").fetchSemanticsNode().boundsInRoot
        val confirm = composeRule.onNodeWithTag(PfpModalTags.CONFIRM).fetchSemanticsNode().boundsInRoot
        assertTrue(title.top >= top.bottom)
        assertTrue(confirm.bottom <= bottom.top)
    }

    @Test
    fun `a long message scrolls inside the band and keeps the buttons on screen`() {
        show(
            PfpModalSpec.Notice(
                key = "long",
                title = "Long",
                message = (1..200).joinToString("\n") { "Line $it of a very long message" },
                onDismiss = { cancels++ },
            ),
        )

        val screen = composeRule.onNodeWithTag(PfpModalTags.SCRIM).fetchSemanticsNode().boundsInRoot
        val bottom = composeRule.onNodeWithTag(PfpModalTags.LINE_BOTTOM).fetchSemanticsNode().boundsInRoot
        val button = composeRule.onNodeWithTag(PfpModalTags.CONFIRM).fetchSemanticsNode().boundsInRoot
        assertTrue(bottom.bottom <= screen.bottom)
        assertTrue(button.bottom <= bottom.top)
    }

    // ── Hints ───────────────────────────────────────────────────────────────────

    @Test
    fun `the hint row is at the bottom of the screen in controller mode`() {
        show(confirmSpec(), showHints = true)

        val screen = composeRule.onNodeWithTag(PfpModalTags.SCRIM).fetchSemanticsNode().boundsInRoot
        val hints = composeRule.onNodeWithTag(PfpModalTags.HINTS).fetchSemanticsNode().boundsInRoot
        val bottom = composeRule.onNodeWithTag(PfpModalTags.LINE_BOTTOM).fetchSemanticsNode().boundsInRoot
        assertTrue("hints sit below the band", hints.top >= bottom.bottom)
        assertTrue("hints sit in the bottom quarter", hints.top > screen.top + screen.height * 0.75f)
    }

    @Test
    fun `touch mode drops the hint row and keeps the buttons tappable`() {
        show(confirmSpec(), showHints = false)

        composeRule.onNodeWithTag(PfpModalTags.HINTS).assertDoesNotExist()
        composeRule.onNodeWithTag(PfpModalTags.CONFIRM).assertExists()
        composeRule.onNodeWithTag(PfpModalTags.CANCEL).assertExists()
    }

    // ── Safe default ────────────────────────────────────────────────────────────

    @Test
    fun `a destructive confirm opens on the safe choice, so a first press cancels`() {
        show(confirmSpec(destructive = true))

        assertTrue(intercept!!(GamepadAction.SELECT))
        composeRule.waitForIdle()

        assertEquals(0, confirms)
        assertEquals(1, cancels)
    }

    @Test
    fun `a plain confirm opens on confirm`() {
        show(confirmSpec(destructive = false))

        intercept!!(GamepadAction.SELECT)

        assertEquals(1, confirms)
        assertEquals(0, cancels)
    }

    // ── Every kind still renders ────────────────────────────────────────────────

    @Test
    fun `confirm renders its content`() {
        show(confirmSpec())
        composeRule.onNodeWithText("Delete Collection").assertExists()
        composeRule.onNodeWithTag(PfpModalTags.CONFIRM).assertExists()
        composeRule.onNodeWithTag(PfpModalTags.CANCEL).assertExists()
    }

    @Test
    fun `notice renders its content`() {
        show(PfpModalSpec.Notice("n", "Heads up", "Something happened.", onDismiss = {}))
        composeRule.onNodeWithText("Heads up").assertExists()
        composeRule.onNodeWithText("Something happened.").assertExists()
        composeRule.onNodeWithTag(PfpModalTags.CONFIRM).assertExists()
    }

    @Test
    fun `choice renders its options`() {
        show(
            PfpModalSpec.Choice(
                key = "c",
                title = "Pick one",
                message = "Which?",
                options = listOf(PfpChoiceOption("Alpha"), PfpChoiceOption("Beta", "detail")),
                confirmLabel = "Use",
                onConfirm = {},
                onCancel = {},
            ),
        )
        composeRule.onNodeWithText("Pick one").assertExists()
        composeRule.onNodeWithText("Alpha").assertExists()
        composeRule.onNodeWithText("Beta").assertExists()
        composeRule.onNodeWithTag(PfpModalTags.CONFIRM).assertExists()
    }

    @Test
    fun `text entry renders its field`() {
        show(PfpModalSpec.TextEntry(key = "t", title = "Rename", initial = "Hello", onConfirm = {}, onCancel = {}))
        composeRule.onNodeWithText("Rename").assertExists()
        composeRule.onNodeWithTag(PfpModalTags.FIELD).assertExists()
        composeRule.onNodeWithTag(PfpModalTags.CONFIRM).assertExists()
    }

    @Test
    fun `notes renders its content`() {
        show(
            PfpModalSpec.Notes(
                key = "notes",
                title = "Scan finished",
                meta = null,
                detail = NotificationDetail.Notes(
                    summary = "Everything went fine.",
                    sections = listOf(NoteSection("Next", "Nothing to do.")),
                    facts = listOf(NoteFact("Emulator", "DuckStation")),
                ),
                accent = Color.White,
                icon = null,
                actionLabel = null,
                onAction = {},
                onCopy = {},
                onClose = {},
            ),
        )
        composeRule.onNodeWithText("Scan finished").assertExists()
        composeRule.onNodeWithText("Everything went fine.").assertExists()
    }

    @Test
    fun `results renders its content`() {
        show(
            PfpModalSpec.Results(
                key = "results",
                title = "Scan results",
                meta = null,
                detail = NotificationDetail.Results(
                    items = listOf(ResultItem("Parasite Eve II", ResultOutcome.DONE)),
                ),
                accent = Color.White,
                icon = null,
                actionLabel = null,
                onAction = {},
                itemActionLabel = { null },
                onItemAction = {},
                onCopy = {},
                onClose = {},
            ),
        )
        composeRule.onNodeWithText("Scan results").assertExists()
        // The item is named twice by design: once in the list and once in the detail pane beside it.
        composeRule.onAllNodesWithText("Parasite Eve II", substring = true)[0].assertExists()
    }

    @Test
    fun `notes sits in the same band between two hairlines, tagged as before`() {
        show(notesSpec())
        assertInBand(PfpDetailSheetTags.NOTES, "Scan finished")
    }

    @Test
    fun `results sits in the same band between two hairlines, tagged as before`() {
        show(resultsSpec())
        assertInBand(PfpDetailSheetTags.RESULTS, "Scan results")
    }

    private fun notesSpec() = PfpModalSpec.Notes(
        key = "notes",
        title = "Scan finished",
        meta = null,
        detail = NotificationDetail.Notes(summary = "Everything went fine.", sections = emptyList(), facts = emptyList()),
        accent = Color.White,
        icon = null,
        actionLabel = null,
        onAction = {},
        onCopy = {},
        onClose = {},
    )

    private fun resultsSpec() = PfpModalSpec.Results(
        key = "results",
        title = "Scan results",
        meta = null,
        detail = NotificationDetail.Results(items = listOf(ResultItem("Parasite Eve II", ResultOutcome.DONE))),
        accent = Color.White,
        icon = null,
        actionLabel = null,
        onAction = {},
        itemActionLabel = { null },
        onItemAction = {},
        onCopy = {},
        onClose = {},
    )

    private fun assertInBand(tag: String, title: String) {
        val screen = composeRule.onNodeWithTag(PfpModalTags.SCRIM).fetchSemanticsNode().boundsInRoot
        val band = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val top = composeRule.onNodeWithTag(PfpModalTags.LINE_TOP).fetchSemanticsNode().boundsInRoot
        val bottom = composeRule.onNodeWithTag(PfpModalTags.LINE_BOTTOM).fetchSemanticsNode().boundsInRoot
        val text = composeRule.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
        assertEquals(screen.width * 0.75f, band.width, 1.5f)
        assertEquals(band.top, top.top, 1.5f)
        assertEquals(band.bottom, bottom.bottom, 1.5f)
        assertTrue(text.top >= top.bottom)
        assertTrue(text.bottom <= bottom.top)
        composeRule.onNodeWithTag(PfpModalTags.HINTS).assertExists()
    }

    // (The silver a prompt lays over the screen is checked by PromptVeilTest.)
}
