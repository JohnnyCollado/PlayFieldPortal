package com.playfieldportal.feature.settings.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.components.PfpConfirmModal
import com.playfieldportal.core.ui.components.PfpModalFocus
import com.playfieldportal.core.ui.components.PfpModalNav
import com.playfieldportal.core.ui.components.PfpModalTags
import com.playfieldportal.core.ui.components.PfpTextEntryModal
import com.playfieldportal.core.ui.theme.PFPTheme
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A shared modal over a settings screen, driven the way the Collections and Categories screens
 * drive it: the scaffold's interceptor hands every press to [PfpModalNav.handle]. What matters is
 * the hand-off of focus — the modal's field must keep the keyboard while presses arrive, nothing
 * behind the modal may react, and the cursor must be back on its row when the modal closes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w833dp-h468dp")
class SettingsScaffoldModalTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val pendingAction = mutableStateOf<GamepadAction?>(null)
    // Same pump as SettingsScaffoldNavigationTest: state written inside the composition is the
    // only kind the test recomposer observes reliably.
    private val actions = Channel<GamepadAction>(Channel.UNLIMITED)
    private var consumedPlain = false

    private val textModalOpen = mutableStateOf(false)
    private val confirmModalOpen = mutableStateOf(false)
    private val modalFocus = mutableStateOf(PfpModalFocus.FIELD)
    private val text = mutableStateOf("Horror")
    private var deleteRowSelects = 0
    private var confirms = 0

    private fun showScreen() {
        composeRule.setContent {
            PFPTheme {
                LaunchedEffect(Unit) {
                    for (action in actions) pendingAction.value = action
                }
                CompositionLocalProvider(
                    LocalSettingsPendingAction provides pendingAction.value,
                    LocalSettingsActionConsumed provides { consumedPlain = true },
                ) {
                    val modalOpen = textModalOpen.value || confirmModalOpen.value
                    SettingsScaffold(
                        title = "Settings",
                        subtitle = "Test screen",
                        onBack = {},
                        modalOpen = modalOpen,
                        onInterceptAction = { action ->
                            if (!modalOpen) return@SettingsScaffold false
                            PfpModalNav.handle(
                                action = action,
                                focus = modalFocus.value,
                                hasField = textModalOpen.value,
                                confirmEnabled = true,
                                sounds = { },
                                onFocusChange = { modalFocus.value = it },
                                onConfirm = { confirms++; closeModals() },
                                onCancel = { closeModals() },
                            )
                            true
                        },
                    ) {
                        SettingsRow(label = "First", onClick = {})
                        SettingsRow(
                            label = "Rename",
                            onClick = {
                                modalFocus.value = PfpModalFocus.FIELD
                                textModalOpen.value = true
                            },
                        )
                        SettingsRow(
                            label = "Delete",
                            onClick = {
                                deleteRowSelects++
                                modalFocus.value = PfpModalNav.initialConfirmFocus(destructive = true)
                                confirmModalOpen.value = true
                            },
                        )
                    }
                    if (textModalOpen.value) {
                        PfpTextEntryModal(
                            title = "Rename Collection",
                            value = text.value,
                            onValueChange = { text.value = it },
                            focus = modalFocus.value,
                            onFocusChange = { modalFocus.value = it },
                            onConfirm = { confirms++; closeModals() },
                            onCancel = { closeModals() },
                        )
                    }
                    if (confirmModalOpen.value) {
                        PfpConfirmModal(
                            title = "Delete Collection",
                            message = "\"Horror\" will be deleted.",
                            confirmLabel = "Delete",
                            focus = modalFocus.value,
                            destructive = true,
                            onConfirm = { confirms++; closeModals() },
                            onCancel = { closeModals() },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun closeModals() {
        textModalOpen.value = false
        confirmModalOpen.value = false
    }

    private fun press(action: GamepadAction) {
        actions.trySend(action)
        composeRule.waitUntil(10_000) { consumedPlain }
        consumedPlain = false
        pendingAction.value = null
        composeRule.waitForIdle()
    }

    private fun assertFocusedRow(text: String) {
        composeRule.onNode(isFocused())
            .assert(hasText(text) or hasAnyDescendant(hasText(text)))
    }

    @Test
    fun `the text modal's field keeps focus while presses arrive`() {
        showScreen()
        press(GamepadAction.NAVIGATE_DOWN)
        assertFocusedRow("Rename")

        press(GamepadAction.SELECT)
        composeRule.onNodeWithTag(PfpModalTags.FIELD).assertIsFocused()

        // Left in the field belongs to the text cursor; the press must not pull focus to a row.
        press(GamepadAction.NAVIGATE_LEFT)
        composeRule.onNodeWithTag(PfpModalTags.FIELD).assertIsFocused()
        assertEquals(PfpModalFocus.FIELD, modalFocus.value)
    }

    @Test
    fun `closing the text modal puts the cursor back on its row`() {
        showScreen()
        press(GamepadAction.NAVIGATE_DOWN)
        press(GamepadAction.SELECT)
        composeRule.onNodeWithTag(PfpModalTags.FIELD).assertIsFocused()

        press(GamepadAction.BACK)

        composeRule.onNodeWithTag(PfpModalTags.CARD).assertDoesNotExist()
        assertFocusedRow("Rename")
    }

    @Test
    fun `nothing behind a confirm modal reacts, and it opens on cancel`() {
        showScreen()
        press(GamepadAction.NAVIGATE_DOWN)
        press(GamepadAction.NAVIGATE_DOWN)
        assertFocusedRow("Delete")
        press(GamepadAction.SELECT)
        assertEquals(1, deleteRowSelects)
        assertEquals(PfpModalFocus.CANCEL, modalFocus.value)

        // Up would move the cursor to "Rename" if the scaffold still saw the press.
        press(GamepadAction.NAVIGATE_UP)
        // Select lands on Cancel, not on the "Delete" row underneath and not on Confirm.
        press(GamepadAction.SELECT)

        assertEquals(1, deleteRowSelects)
        assertEquals(0, confirms)
        composeRule.onNodeWithTag(PfpModalTags.CARD).assertDoesNotExist()
        assertFocusedRow("Delete")
    }

    @Test
    fun `right then select confirms`() {
        showScreen()
        press(GamepadAction.NAVIGATE_DOWN)
        press(GamepadAction.NAVIGATE_DOWN)
        press(GamepadAction.SELECT)

        press(GamepadAction.NAVIGATE_RIGHT)
        press(GamepadAction.SELECT)

        assertEquals(1, confirms)
        assertEquals(1, deleteRowSelects)
    }
}
