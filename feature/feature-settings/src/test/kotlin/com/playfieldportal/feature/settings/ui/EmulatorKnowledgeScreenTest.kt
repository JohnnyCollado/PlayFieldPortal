package com.playfieldportal.feature.settings.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.playfieldportal.core.data.kb.PlatformGain
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.IntentType
import com.playfieldportal.core.domain.model.EmulatorProfile
import com.playfieldportal.core.domain.model.emulatorkb.CantShare
import com.playfieldportal.core.domain.model.emulatorkb.EffectiveKb
import com.playfieldportal.core.domain.model.emulatorkb.EffectiveKbEmulator
import com.playfieldportal.core.domain.model.emulatorkb.EffectiveKbPlatform
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbEmulator
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbImportPlan
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbLaunch
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbPlatform
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbValidation
import com.playfieldportal.core.domain.model.emulatorkb.KbItem
import com.playfieldportal.core.domain.model.emulatorkb.KbSource
import com.playfieldportal.core.domain.model.emulatorkb.SignerState
import com.playfieldportal.core.ui.components.PfpModalTags
import com.playfieldportal.core.ui.theme.PFPTheme
import com.playfieldportal.feature.launcher.kb.KbUserFile
import com.playfieldportal.feature.settings.viewmodel.EmulatorKnowledgeUiState
import com.playfieldportal.feature.settings.viewmodel.KbExportNotice
import com.playfieldportal.feature.settings.viewmodel.KbExportPicker
import com.playfieldportal.feature.settings.viewmodel.KbImportReview
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings > Emulators > Emulator knowledge, driven by controller actions through the real
 * scaffold: every row is reachable, a user file's Remove is one RIGHT away and asks first, and
 * Reset to built-in only raises the view model's confirm.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w833dp-h1200dp")
class EmulatorKnowledgeScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val pendingAction = mutableStateOf<GamepadAction?>(null)
    private val actions = Channel<GamepadAction>(Channel.UNLIMITED)
    private var consumedPlain = false

    private val removed = mutableListOf<String>()
    private var checks = 0
    private var resetRequests = 0
    private var resetConfirms = 0
    private var backs = 0
    private val toggled = mutableListOf<String>()
    private var importConfirms = 0
    private var importCancels = 0
    private var exportOpens = 0
    private val exportToggled = mutableListOf<String>()
    private var exportAll = 0
    private var exportConfirms = 0
    private var exportCancels = 0
    private var exportNoticeDismissed = 0

    private val file = KbUserFile(id = "f1", displayName = "my-emulators.json", unreadable = false, emulatorCount = 3)

    private fun showScreen(state: EmulatorKnowledgeUiState) {
        composeRule.setContent {
            PFPTheme {
                LaunchedEffect(Unit) { for (a in actions) pendingAction.value = a }
                CompositionLocalProvider(
                    LocalSettingsPendingAction provides pendingAction.value,
                    LocalSettingsActionConsumed provides { consumedPlain = true },
                ) {
                    EmulatorKnowledgeContent(
                        state = state,
                        onBack = { backs++ },
                        onCheckNow = { checks++ },
                        onSetAutoUpdate = {},
                        onRemoveFile = { removed += it },
                        onRequestReset = { resetRequests++ },
                        onConfirmReset = { resetConfirms++ },
                        onDismissReset = {},
                        onToggleReviewItem = { toggled += it },
                        onConfirmImport = { importConfirms++ },
                        onCancelImport = { importCancels++ },
                        onExport = { exportOpens++ },
                        onToggleExportItem = { exportToggled += it },
                        onToggleExportAll = { exportAll++ },
                        onConfirmExport = { exportConfirms++ },
                        onCancelExport = { exportCancels++ },
                        onDismissExportNotice = { exportNoticeDismissed++ },
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun press(action: GamepadAction) {
        actions.trySend(action)
        composeRule.waitUntil(10_000) { consumedPlain }
        consumedPlain = false
        pendingAction.value = null
        composeRule.waitForIdle()
    }

    private fun assertFocusedRow(text: String) {
        composeRule.onNode(isFocused()).assert(hasText(text) or hasAnyDescendant(hasText(text)))
    }

    @Test
    fun `every row is reachable with the D-pad in the mockup order`() {
        showScreen(EmulatorKnowledgeUiState(statusText = "Official v2026.10.02 · verified", userFiles = listOf(file)))
        // The status card is read-only, so the cursor opens on the first actionable row.
        assertFocusedRow("Check for updates")
        listOf(
            "Automatic updates",
            "Import a knowledge file",
            "my-emulators.json",
            "Export your files",
            "Reset to built-in",
        ).forEach { row ->
            press(GamepadAction.NAVIGATE_DOWN)
            assertFocusedRow(row)
        }
    }

    @Test
    fun `check for updates runs the check`() {
        showScreen(EmulatorKnowledgeUiState())
        assertFocusedRow("Check for updates")
        press(GamepadAction.SELECT)
        assertEquals(1, checks)
    }

    @Test
    fun `a user file shows its count, badge and removes only after the confirm`() {
        showScreen(EmulatorKnowledgeUiState(userFiles = listOf(file)))
        composeRule.onNodeWithText("3 emulators · not signed").assertIsDisplayed()
        composeRule.onNodeWithText("Custom").assertIsDisplayed()

        repeat(3) { press(GamepadAction.NAVIGATE_DOWN) }
        assertFocusedRow("my-emulators.json")
        press(GamepadAction.NAVIGATE_RIGHT)
        press(GamepadAction.SELECT)
        composeRule.onNodeWithTag(PfpModalTags.CARD).assertIsDisplayed()
        assertEquals(emptyList<String>(), removed)

        // The destructive confirm opens on Cancel; RIGHT then SELECT confirms.
        press(GamepadAction.NAVIGATE_RIGHT)
        press(GamepadAction.SELECT)
        assertEquals(listOf("f1"), removed)
    }

    @Test
    fun `cancelling the remove confirm removes nothing`() {
        showScreen(EmulatorKnowledgeUiState(userFiles = listOf(file)))
        repeat(3) { press(GamepadAction.NAVIGATE_DOWN) }
        press(GamepadAction.NAVIGATE_RIGHT)
        press(GamepadAction.SELECT)
        press(GamepadAction.SELECT)
        assertEquals(emptyList<String>(), removed)
        composeRule.onNodeWithTag(PfpModalTags.CARD).assertDoesNotExist()
    }

    @Test
    fun `an unreadable file is flagged`() {
        showScreen(EmulatorKnowledgeUiState(userFiles = listOf(file.copy(unreadable = true))))
        composeRule.onNodeWithText("Unreadable").assertIsDisplayed()
    }

    @Test
    fun `reset to built-in only requests the confirm`() {
        showScreen(EmulatorKnowledgeUiState())
        repeat(4) { press(GamepadAction.NAVIGATE_DOWN) }
        assertFocusedRow("Reset to built-in")
        press(GamepadAction.SELECT)
        assertEquals(1, resetRequests)
        assertEquals(0, resetConfirms)
    }

    @Test
    fun `the reset confirm is shown from the view model state and confirms through it`() {
        showScreen(EmulatorKnowledgeUiState(confirmResetVisible = true))
        composeRule.onNodeWithTag(PfpModalTags.CARD).assertIsDisplayed()
        press(GamepadAction.NAVIGATE_RIGHT)
        press(GamepadAction.SELECT)
        assertEquals(1, resetConfirms)
    }

    @Test
    fun `error text, check result and gained file types are shown`() {
        showScreen(
            EmulatorKnowledgeUiState(
                errorText = "Could not reach the update server",
                checkResult = "Already up to date",
                gainedFileTypes = listOf(PlatformGain("psx", listOf("chd"))),
            ),
        )
        composeRule.onNodeWithText("Could not reach the update server").assertIsDisplayed()
        composeRule.onNodeWithText("Already up to date").assertIsDisplayed()
        composeRule.onNodeWithText("rescan", substring = true, ignoreCase = true).assertIsDisplayed()
    }

    @Test
    fun `without a pinned key the update rows say so and cannot run`() {
        showScreen(EmulatorKnowledgeUiState(updatesConfigured = false))
        composeRule.onAllNodesWithText("Not available in this build").assertCountEquals(2)
        press(GamepadAction.SELECT)
        assertEquals(0, checks)
    }

    @Test
    fun `gained file types are named by console`() {
        showScreen(EmulatorKnowledgeUiState(gainedFileTypes = listOf(PlatformGain("psp", listOf("chd")))))
        composeRule.onNodeWithText("PlayStation Portable: chd", substring = true).assertIsDisplayed()
    }

    @Test
    fun `back leaves the screen`() {
        showScreen(EmulatorKnowledgeUiState())
        press(GamepadAction.BACK)
        assertEquals(1, backs)
    }

    // ── Import review (6.3) ───────────────────────────────────────────────────

    private fun emulator(id: String, name: String, vararg platforms: String) = EmulatorKbEmulator(
        id = id,
        name = name,
        packageNames = listOf("org.example.$id"),
        platformIds = platforms.toList(),
    )

    /** One of each kind: New "PPSSPP", Change "Eden" (6 differing fields, official, user edited), a Switch update, a Blocked entry. */
    private fun reviewState(selectNothing: Boolean = false): EmulatorKnowledgeUiState {
        val current = emulator("eden", "Eden", "switch")
        val edited = current.copy(
            name = "Eden Custom",
            packageNames = listOf("org.example.eden", "org.example.eden2"),
            platformIds = listOf("switch", "psp"),
            launch = EmulatorKbLaunch(intentType = IntentType.COMPONENT, activityClass = ".Boot", action = "x.RUN"),
        )
        val effective = EffectiveKb(
            emulators = listOf(EffectiveKbEmulator(current, KbSource.Official, null)),
            platforms = listOf(EffectiveKbPlatform(EmulatorKbPlatform("switch", listOf("nsp")), KbSource.BuiltIn, null)),
            officialApplied = true,
        )
        val validated = EmulatorKbValidation(
            emulators = listOf(emulator("ppsspp", "PPSSPP", "psp"), edited),
            platforms = listOf(EmulatorKbPlatform("switch", listOf("nsp", "xci", "nsz"))),
            refusedEmulators = listOf(KbItem.Rejected(2, "evil", "Custom commands are never allowed")),
            refusedPlatforms = emptyList(),
        )
        var plan = EmulatorKbImportPlan.build(effective, validated, setOf("eden")) { _, _ -> SignerState.NotInstalled }
        if (selectNothing) plan = plan.toggle("emulator:ppsspp")
        return EmulatorKnowledgeUiState(review = KbImportReview("shared.json", plan))
    }

    @Test
    fun `the review shows the mockup sections, badges, console names and the full diff`() {
        showScreen(reviewState())
        listOf(
            "Review shared.json",
            "Not signed · from a file on this device",
            "Import only files you trust",
            "New emulators",
            "Add PPSSPP",
            "org.example.ppsspp · PlayStation Portable · Opens the game file",
            "Already on this device",
            "Override Eden Custom",
            "Overrides official",
            "Your edit",
            "- Name: Eden",
            "+ Name: Eden Custom",
            "Consoles",
            "Update Nintendo Switch",
            "+ extensions: xci, nsz",
            "Blocked",
            "evil",
            "Custom commands are never allowed",
            "Import 1 emulator",
        ).forEach { composeRule.onNodeWithText(it, substring = true, ignoreCase = true).assertExists() }
        composeRule.onNodeWithText("+ Action: x.RUN", substring = true).assertExists()
        composeRule.onNodeWithText("more", substring = true).assertDoesNotExist()
        // The status and update rows belong to the main screen, not the review.
        composeRule.onNodeWithText("Check for updates").assertDoesNotExist()
    }

    @Test
    fun `confirm toggles the focused review row and moves between rows with the D-pad`() {
        showScreen(reviewState())
        assertFocusedRow("Add PPSSPP")
        press(GamepadAction.SELECT)
        press(GamepadAction.NAVIGATE_DOWN)
        assertFocusedRow("Override Eden Custom")
        press(GamepadAction.SELECT)
        press(GamepadAction.NAVIGATE_DOWN)
        assertFocusedRow("Update Nintendo Switch")
        press(GamepadAction.SELECT)
        assertEquals(listOf("emulator:ppsspp", "emulator:eden", "platform:switch"), toggled)
    }

    @Test
    fun `a blocked row has no choice`() {
        showScreen(reviewState())
        repeat(3) { press(GamepadAction.NAVIGATE_DOWN) }
        assertFocusedRow("evil")
        press(GamepadAction.SELECT)
        assertEquals(emptyList<String>(), toggled)
    }

    @Test
    fun `back cancels the review and does not leave the screen`() {
        showScreen(reviewState())
        press(GamepadAction.BACK)
        assertEquals(1, importCancels)
        assertEquals(0, backs)
    }

    @Test
    fun `the confirm button imports and the cancel button cancels`() {
        showScreen(reviewState())
        repeat(4) { press(GamepadAction.NAVIGATE_DOWN) }
        assertFocusedRow("Cancel")
        press(GamepadAction.SELECT)
        assertEquals(1, importCancels)
        press(GamepadAction.NAVIGATE_DOWN)
        assertFocusedRow("Import 1 emulator")
        press(GamepadAction.SELECT)
        assertEquals(1, importConfirms)
    }

    @Test
    fun `the confirm button does nothing while nothing is ticked`() {
        showScreen(reviewState(selectNothing = true))
        repeat(5) { press(GamepadAction.NAVIGATE_DOWN) }
        assertFocusedRow("Import 0 emulators")
        press(GamepadAction.SELECT)
        assertEquals(0, importConfirms)
    }

    // ── Export picker (7.2) ───────────────────────────────────────────────────

    private fun exportState(selected: Set<String>? = null): EmulatorKnowledgeUiState {
        val custom = EmulatorProfile(
            id = "mine", name = "My Emu", packageName = "org.example.mine", intentType = IntentType.COMPONENT,
            supportedPlatformIds = listOf("psp"), isCustom = true,
        )
        val edited = EmulatorProfile(
            id = "eden", name = "Eden", packageName = "org.example.eden", intentType = IntentType.COMPONENT,
            supportedPlatformIds = listOf("switch"), userModified = true,
        )
        val entries = listOf(custom, edited)
        return EmulatorKnowledgeUiState(
            export = KbExportPicker(
                entries = entries,
                cantShare = listOf(CantShare("bad", "Bad Emu", "has a fixed file path")),
                selectedIds = selected ?: entries.map { it.id }.toSet(),
            ),
        )
    }

    @Test
    fun `the export picker shows the mockup sections, all checked by default`() {
        showScreen(exportState())
        listOf(
            "Export your emulators",
            "Saved as one .json you can share",
            "Select all",
            "2 of 2 selected",
            "Custom emulators",
            "My Emu",
            "org.example.mine · PlayStation Portable",
            "Edited official emulators",
            "Eden",
            "Your changes only · Nintendo Switch",
            "Only launch settings are saved. No game list, ROM paths or account details.",
            "Can't be shared",
            "Bad Emu",
            "has a fixed file path",
            "Export 2 emulators",
        ).forEach { composeRule.onNodeWithText(it, substring = true, ignoreCase = true).assertExists() }
        composeRule.onNodeWithText("Check for updates").assertDoesNotExist()
    }

    @Test
    fun `confirm toggles the focused export rows and select all`() {
        showScreen(exportState())
        assertFocusedRow("Select all")
        press(GamepadAction.SELECT)
        press(GamepadAction.NAVIGATE_DOWN)
        assertFocusedRow("My Emu")
        press(GamepadAction.SELECT)
        press(GamepadAction.NAVIGATE_DOWN)
        assertFocusedRow("Eden")
        press(GamepadAction.SELECT)
        assertEquals(1, exportAll)
        assertEquals(listOf("mine", "eden"), exportToggled)
    }

    @Test
    fun `a can't-share row has no choice`() {
        showScreen(exportState())
        repeat(4) { press(GamepadAction.NAVIGATE_DOWN) }
        assertFocusedRow("Bad Emu")
        press(GamepadAction.SELECT)
        assertEquals(emptyList<String>(), exportToggled)
    }

    @Test
    fun `back cancels the export picker and does not leave the screen`() {
        showScreen(exportState())
        press(GamepadAction.BACK)
        assertEquals(1, exportCancels)
        assertEquals(0, backs)
    }

    @Test
    fun `the export button exports and the cancel button cancels`() {
        showScreen(exportState())
        repeat(5) { press(GamepadAction.NAVIGATE_DOWN) }
        assertFocusedRow("Cancel")
        press(GamepadAction.SELECT)
        assertEquals(1, exportCancels)
        press(GamepadAction.NAVIGATE_DOWN)
        assertFocusedRow("Export 2 emulators")
        press(GamepadAction.SELECT)
        assertEquals(1, exportConfirms)
    }

    @Test
    fun `the export button does nothing while nothing is ticked`() {
        showScreen(exportState(selected = emptySet()))
        composeRule.onNodeWithText("0 of 2 selected").assertExists()
        repeat(6) { press(GamepadAction.NAVIGATE_DOWN) }
        assertFocusedRow("Export 0 emulators")
        press(GamepadAction.SELECT)
        assertEquals(0, exportConfirms)
    }

    @Test
    fun `the export row opens the picker`() {
        showScreen(EmulatorKnowledgeUiState())
        repeat(3) { press(GamepadAction.NAVIGATE_DOWN) }
        assertFocusedRow("Export your files")
        press(GamepadAction.SELECT)
        assertEquals(1, exportOpens)
    }

    @Test
    fun `an export notice is shown and dismissed`() {
        showScreen(EmulatorKnowledgeUiState(exportNotice = KbExportNotice("Exported", "Exported 2 emulators")))
        composeRule.onNodeWithTag(PfpModalTags.CARD).assertIsDisplayed()
        composeRule.onNodeWithText("Exported 2 emulators").assertIsDisplayed()
        press(GamepadAction.SELECT)
        assertEquals(1, exportNoticeDismissed)
    }
}
