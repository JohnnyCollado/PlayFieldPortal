package com.playfieldportal.core.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The two shared modals as a user meets them: what each shows, which taps confirm and which
 * cancel, and that the text modal cannot be confirmed with a name the host would reject.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w833dp-h468dp")
class PfpModalsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var confirms = 0
    private var cancels = 0

    // ── Confirm modal ───────────────────────────────────────────────────────────

    private fun setConfirm(focus: PfpModalFocus = PfpModalFocus.CONFIRM, destructive: Boolean = false) {
        composeRule.setContent {
            PfpConfirmModal(
                title = "Delete Collection",
                message = "\"Survival Horror\" will be deleted.",
                confirmLabel = "Delete",
                focus = focus,
                destructive = destructive,
                onConfirm = { confirms++ },
                onCancel = { cancels++ },
            )
        }
    }

    @Test
    fun `the confirm modal shows its title, message and both actions`() {
        setConfirm()

        composeRule.onNodeWithText("Delete Collection").assertExists()
        composeRule.onNodeWithText("\"Survival Horror\" will be deleted.").assertExists()
        composeRule.onNodeWithTag(PfpModalTags.CONFIRM).assertTextEquals("Delete")
        composeRule.onNodeWithTag(PfpModalTags.CANCEL).assertTextEquals("Cancel")
    }

    @Test
    fun `tapping confirm confirms and tapping cancel cancels`() {
        setConfirm()

        composeRule.onNodeWithTag(PfpModalTags.CONFIRM).performClick()
        assertEquals(1 to 0, confirms to cancels)

        composeRule.onNodeWithTag(PfpModalTags.CANCEL).performClick()
        assertEquals(1 to 1, confirms to cancels)
    }

    @Test
    fun `tapping outside the card cancels and tapping the card does not`() {
        setConfirm()

        composeRule.onNodeWithTag(PfpModalTags.CARD).performClick()
        assertEquals(0 to 0, confirms to cancels)

        composeRule.onNodeWithTag(PfpModalTags.SCRIM).performTouchInput { click(Offset(4f, 4f)) }
        assertEquals(0 to 1, confirms to cancels)
    }

    @Test
    fun `the controller hints are shown with the confirm modal`() {
        setConfirm()

        composeRule.onNodeWithTag(PfpModalTags.HINTS).assertExists()
    }

    // ── Notice modal ────────────────────────────────────────────────────────────

    private fun setNotice() {
        composeRule.setContent {
            PfpNoticeModal(
                title = "Can't play video",
                message = "No installed app can open this file.",
                onDismiss = { cancels++ },
            )
        }
    }

    @Test
    fun `the notice modal shows one button and no cancel`() {
        setNotice()

        composeRule.onNodeWithText("Can't play video").assertExists()
        composeRule.onNodeWithText("No installed app can open this file.").assertExists()
        composeRule.onNodeWithTag(PfpModalTags.CONFIRM).assertTextEquals("OK")
        composeRule.onNodeWithTag(PfpModalTags.CANCEL).assertDoesNotExist()
    }

    @Test
    fun `the notice's button and a tap outside the card both dismiss it`() {
        setNotice()

        composeRule.onNodeWithTag(PfpModalTags.CONFIRM).performClick()
        assertEquals(1, cancels)

        composeRule.onNodeWithTag(PfpModalTags.SCRIM).performTouchInput { click(Offset(4f, 4f)) }
        assertEquals(2, cancels)
    }

    // ── Text entry modal ────────────────────────────────────────────────────────

    private var text by mutableStateOf("")
    private var focus by mutableStateOf(PfpModalFocus.FIELD)

    private fun setTextEntry(
        initial: String = "",
        initialFocus: PfpModalFocus = PfpModalFocus.FIELD,
        error: String? = null,
        maxLength: Int? = 40,
    ) {
        text = initial
        focus = initialFocus
        composeRule.setContent {
            PfpTextEntryModal(
                title = "New Collection",
                value = text,
                onValueChange = { text = it },
                focus = focus,
                onFocusChange = { focus = it },
                onConfirm = { confirms++ },
                onCancel = { cancels++ },
                placeholder = "Collection name",
                error = error,
                maxLength = maxLength,
            )
        }
    }

    @Test
    fun `typing reports the new text and updates the counter`() {
        setTextEntry()

        composeRule.onNodeWithTag(PfpModalTags.FIELD).performTextInput("Survival Horror")

        assertEquals("Survival Horror", text)
        composeRule.onNodeWithText("15 / 40").assertExists()
    }

    @Test
    fun `text past the limit is not accepted`() {
        setTextEntry(maxLength = 8)

        composeRule.onNodeWithTag(PfpModalTags.FIELD).performTextInput("Survival Horror")

        assertEquals("Survival", text)
        composeRule.onNodeWithText("8 / 8").assertExists()
    }

    @Test
    fun `no counter is shown without a limit`() {
        setTextEntry(initial = "Survival Horror", maxLength = null)

        composeRule.onNodeWithTag(PfpModalTags.COUNTER).assertDoesNotExist()
    }

    @Test
    fun `an empty name cannot be saved`() {
        setTextEntry()

        composeRule.onNodeWithTag(PfpModalTags.CONFIRM).assertIsNotEnabled()
        composeRule.onNodeWithTag(PfpModalTags.CONFIRM).performClick()
        assertEquals(0, confirms)
    }

    @Test
    fun `a valid name can be saved`() {
        setTextEntry(initial = "Survival Horror")

        composeRule.onNodeWithTag(PfpModalTags.CONFIRM).assertIsEnabled()
        composeRule.onNodeWithTag(PfpModalTags.CONFIRM).performClick()
        assertEquals(1, confirms)
    }

    @Test
    fun `an error is shown under the field and blocks saving`() {
        setTextEntry(
            initial = "Favorites",
            initialFocus = PfpModalFocus.CANCEL,
            error = "A category named \"Favorites\" already exists.",
        )

        composeRule.onNodeWithTag(PfpModalTags.ERROR)
            .assertTextEquals("A category named \"Favorites\" already exists.")
        composeRule.onNodeWithTag(PfpModalTags.CONFIRM).assertIsNotEnabled()
    }

    @Test
    fun `tapping the field moves focus to it`() {
        setTextEntry(initial = "Survival Horror", initialFocus = PfpModalFocus.CANCEL)

        composeRule.onNodeWithTag(PfpModalTags.FIELD).performClick()
        composeRule.waitForIdle()

        assertEquals(PfpModalFocus.FIELD, focus)
    }

    @Test
    fun `the controller hints are hidden while the field has focus and shown on the buttons`() {
        setTextEntry(initial = "Survival Horror", initialFocus = PfpModalFocus.FIELD)
        composeRule.onNodeWithTag(PfpModalTags.HINTS).assertDoesNotExist()

        focus = PfpModalFocus.CANCEL
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(PfpModalTags.HINTS).assertExists()
    }
}
