package com.playfieldportal.feature.xmb.ui.detail

import android.net.Uri
import com.playfieldportal.core.data.database.entity.ProviderGameLinkEntity
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.achievements.AchievementController
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.match.AchievementAutoMatcher
import com.playfieldportal.feature.achievements.provider.localsteam.AppIdSource
import com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamFolderLinker
import com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamGame
import com.playfieldportal.feature.artwork.match.MatchConfidence
import com.playfieldportal.feature.artwork.match.MatchSignal
import com.playfieldportal.feature.artwork.match.ScoredStorefrontCandidate
import com.playfieldportal.feature.artwork.match.Storefront
import com.playfieldportal.feature.artwork.match.StorefrontCandidate
import com.playfieldportal.feature.artwork.match.StorefrontMatchResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The folder-picked Local Steam flow on the game's coins page: pick a folder, work out its app id,
 * and stop for the user at every point where PFP would otherwise guess or write unasked.
 *
 * Answering "No" to the legitimate-copy question used to scan the windows library for a folder that
 * increasingly is not under it. It asks now — and the one thing it must never do is ask twice for a
 * folder it was already shown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ShibaCoinsFolderMatchTest {

    private val gameId = 1L
    private val pickedUri: Uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AGames%2FPortal%202")

    private val link = MutableStateFlow<ProviderGameLinkEntity?>(null)

    private lateinit var achievements: AchievementController
    private lateinit var linker: LocalSteamFolderLinker
    private lateinit var viewModel: ShibaCoinsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        achievements = mockk(relaxed = true) {
            every { observeGameCoins(gameId) } returns MutableStateFlow(null)
            every { observeCoins(gameId) } returns MutableStateFlow(emptyList())
            every { observeLink(gameId) } returns link
            coEvery { syncGameById(gameId) } returns ProviderSyncResult.Success("620", emptyList())
        }
        linker = mockk(relaxed = true) {
            coEvery { registeredFolderFor(gameId) } returns null
        }
        val games = mockk<GameRepository> {
            // A windows game, which is what makes this the Steam/Local Steam branch at all.
            coEvery { getById(gameId) } returns Game(id = gameId, title = "Portal 2", platformId = "windows")
        }
        viewModel = ShibaCoinsViewModel(
            games,
            achievements,
            mockk<AchievementAutoMatcher>(relaxed = true),
            linker,
        )
        viewModel.load(ShibaCoinsTarget.LibraryGame(gameId))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val state get() = viewModel.uiState.value

    private fun folder() = LocalSteamGame(
        folderName = "Portal 2",
        folderDocId = "primary:Games/Portal 2",
        appId = "620",
        achievementsUri = null,
        settingsTreeUri = pickedUri.toString(),
    )

    private fun candidateResult() = StorefrontMatchResult(
        store = Storefront.STEAM,
        confidence = MatchConfidence.AMBIGUOUS,
        best = scored("620", "Portal 2"),
        alternatives = listOf(scored("400", "Portal")),
    )

    private fun scored(storeId: String, title: String) = ScoredStorefrontCandidate(
        candidate = StorefrontCandidate(store = Storefront.STEAM, storeId = storeId, title = title),
        signals = listOf(MatchSignal.EXACT_TITLE),
    )

    // ── Entering the flow ──────────────────────────────────────────────────────

    @Test
    fun `answering No asks for the game folder instead of scanning the library`() {
        viewModel.chooseAutoMatch(legit = false)

        assertEquals(AutoMatchStep.PICK_FOLDER, state.autoMatchStep)
        assertTrue(state.requestFolderPick, "the screen has to be told to launch the picker")
    }

    @Test
    fun `the registry pre-check links straight through and never asks for a folder`() {
        coEvery { linker.registeredFolderFor(gameId) } returns folder()
        coEvery { linker.linkRegistered(gameId, folder()) } returns
            LocalSteamFolderLinker.LinkOutcome.Linked("620", "Portal 2")

        viewModel.chooseAutoMatch(legit = false)

        assertNull(state.autoMatchStep)
        assertFalse(state.requestFolderPick, "a batch-matched game must not be asked again")
        coVerify { achievements.syncGameById(gameId) }
        // Not through link(): a batch-registered folder was reached through a grant on its PARENT,
        // so re-deriving the folder from that tree would anchor the wrong directory.
        coVerify(exactly = 0) { linker.link(any(), any()) }
    }

    @Test
    fun `the pick request is cleared once the screen has launched the picker`() {
        viewModel.chooseAutoMatch(legit = false)
        viewModel.onFolderPickLaunched()

        assertFalse(state.requestFolderPick)
        assertEquals(AutoMatchStep.PICK_FOLDER, state.autoMatchStep, "still waiting on the result")
    }

    @Test
    fun `dismissing the picker returns to the copy question rather than a dead end`() {
        viewModel.chooseAutoMatch(legit = false)
        viewModel.onFolderPicked(null)

        assertEquals(AutoMatchStep.CONFIRM_COPY, state.autoMatchStep)
    }

    // ── Outcomes ───────────────────────────────────────────────────────────────

    @Test
    fun `a ready folder links, syncs and closes the flow`() {
        coEvery { linker.link(gameId, pickedUri) } returns
            LocalSteamFolderLinker.LinkOutcome.Linked("620", "Portal 2")

        viewModel.onFolderPicked(pickedUri)

        assertNull(state.autoMatchStep)
        assertNull(state.storefrontMatch)
        assertTrue(state.message!!.contains("620"))
        coVerify { achievements.syncGameById(gameId) }
    }

    @Test
    fun `a kit-missing folder opens the kit prompt with the installer state it was given`() {
        coEvery { linker.link(gameId, pickedUri) } returns
            LocalSteamFolderLinker.LinkOutcome.NeedsKit("620", "Portal 2", installerEnabled = false, source = AppIdSource.TITLE_MATCH)

        viewModel.onFolderPicked(pickedUri)

        assertEquals(AutoMatchStep.CONFIRM_KIT, state.autoMatchStep)
        val prompt = state.kitPrompt!!
        assertEquals("620", prompt.appId)
        assertEquals(false, prompt.installerEnabled)
        // With the installer off the cursor starts on Link Only, so Confirm never lands on a button
        // whose only job would be to explain why it cannot run.
        assertEquals(false, prompt.installSelected)
    }

    @Test
    fun `Install and Link on the kit prompt writes the kit through the linker`() {
        coEvery { linker.link(gameId, pickedUri) } returns
            LocalSteamFolderLinker.LinkOutcome.NeedsKit("620", "Portal 2", installerEnabled = true, source = AppIdSource.MARKER)
        coEvery { linker.installKitAndLink(gameId, pickedUri, "620", AppIdSource.MARKER) } returns
            LocalSteamFolderLinker.LinkOutcome.Linked("620", "Portal 2")

        viewModel.onFolderPicked(pickedUri)
        viewModel.handleGamepadAction(GamepadAction.SELECT)

        coVerify { linker.installKitAndLink(gameId, pickedUri, "620", AppIdSource.MARKER) }
        assertNull(state.autoMatchStep)
    }

    @Test
    fun `Link Only tracks the game and writes nothing more into the folder`() {
        coEvery { linker.link(gameId, pickedUri) } returns
            LocalSteamFolderLinker.LinkOutcome.NeedsKit("620", "Portal 2", installerEnabled = true, source = AppIdSource.TITLE_MATCH)
        coEvery { linker.linkWithoutKit(gameId, pickedUri, "620", AppIdSource.TITLE_MATCH) } returns
            LocalSteamFolderLinker.LinkOutcome.Linked("620", "Portal 2")

        viewModel.onFolderPicked(pickedUri)
        // Left/Right moves the cursor onto Link Only, Confirm takes it.
        viewModel.handleGamepadAction(GamepadAction.NAVIGATE_RIGHT)
        viewModel.handleGamepadAction(GamepadAction.SELECT)

        coVerify { linker.linkWithoutKit(gameId, pickedUri, "620", AppIdSource.TITLE_MATCH) }
        coVerify(exactly = 0) { linker.installKitAndLink(any(), any(), any(), any()) }
    }

    @Test
    fun `the kit prompt cursor does not move onto Install while the installer is off`() {
        coEvery { linker.link(gameId, pickedUri) } returns
            LocalSteamFolderLinker.LinkOutcome.NeedsKit("620", "Portal 2", installerEnabled = false, source = AppIdSource.MARKER)

        viewModel.onFolderPicked(pickedUri)
        viewModel.handleGamepadAction(GamepadAction.NAVIGATE_LEFT)

        assertEquals(false, state.kitPrompt!!.installSelected)
    }

    @Test
    fun `a pick that resolves nothing is terminal and names the actual cause`() {
        coEvery { linker.link(gameId, pickedUri) } returns
            LocalSteamFolderLinker.LinkOutcome.NoEmuData("No Steam library file in that folder.")

        viewModel.onFolderPicked(pickedUri)

        assertEquals(AutoMatchStep.NO_EMU_DATA, state.autoMatchStep)
        assertEquals("No Steam library file in that folder.", state.noEmuDataReason)
        coVerify(exactly = 0) { achievements.syncGameById(any()) }
    }

    @Test
    fun `a lost grant mid-flow is reported and offers another pick`() {
        coEvery { linker.link(gameId, pickedUri) } returns
            LocalSteamFolderLinker.LinkOutcome.Failed("That folder could not be read back.")

        viewModel.onFolderPicked(pickedUri)
        assertEquals(AutoMatchStep.NO_EMU_DATA, state.autoMatchStep)

        // "Try Another Folder" is the same entry point as the first attempt.
        viewModel.startAutoMatch()
        assertEquals(AutoMatchStep.CONFIRM_COPY, state.autoMatchStep)
    }

    // ── The ambiguous-match picker ─────────────────────────────────────────────

    @Test
    fun `an ambiguous title opens the shared picker showing the query PFP searched`() {
        coEvery { linker.link(gameId, pickedUri) } returns
            LocalSteamFolderLinker.LinkOutcome.NeedsConfirmation(candidateResult(), "portal 2", "Portal 2")

        viewModel.onFolderPicked(pickedUri)

        assertEquals(AutoMatchStep.IDENTIFY, state.autoMatchStep)
        val ui = state.storefrontMatch!!
        assertEquals("portal 2", ui.query)
        assertEquals(listOf("620", "400"), ui.rows.map { it.storeId })
        assertEquals("Steam", ui.storeLabel)
    }

    @Test
    fun `choosing a candidate writes that id back and links from it`() {
        coEvery { linker.link(gameId, pickedUri) } returns
            LocalSteamFolderLinker.LinkOutcome.NeedsConfirmation(candidateResult(), "portal 2", "Portal 2")
        coEvery { linker.confirmCandidate(gameId, pickedUri, "400") } returns
            LocalSteamFolderLinker.LinkOutcome.Linked("400", "Portal 2")

        viewModel.onFolderPicked(pickedUri)
        viewModel.handleGamepadAction(GamepadAction.NAVIGATE_DOWN)
        viewModel.handleGamepadAction(GamepadAction.SELECT)

        coVerify { linker.confirmCandidate(gameId, pickedUri, "400") }
        assertNull(state.autoMatchStep)
    }

    @Test
    fun `No correct match writes nothing and says where to set the id instead`() {
        coEvery { linker.link(gameId, pickedUri) } returns
            LocalSteamFolderLinker.LinkOutcome.NeedsConfirmation(candidateResult(), "portal 2", "Portal 2")

        viewModel.onFolderPicked(pickedUri)
        // Past both candidates is the always-present escape hatch.
        viewModel.handleGamepadAction(GamepadAction.NAVIGATE_DOWN)
        viewModel.handleGamepadAction(GamepadAction.NAVIGATE_DOWN)
        viewModel.handleGamepadAction(GamepadAction.SELECT)

        assertEquals(AutoMatchStep.NO_EMU_DATA, state.autoMatchStep)
        assertTrue(state.noEmuDataReason!!.contains("Nothing was written"))
        coVerify(exactly = 0) { linker.confirmCandidate(any(), any(), any()) }
    }

    @Test
    fun `the picker's focus clamps at the escape hatch and never runs off the end`() {
        coEvery { linker.link(gameId, pickedUri) } returns
            LocalSteamFolderLinker.LinkOutcome.NeedsConfirmation(candidateResult(), "portal 2", "Portal 2")

        viewModel.onFolderPicked(pickedUri)
        repeat(6) { viewModel.handleGamepadAction(GamepadAction.NAVIGATE_UP) }
        assertEquals(0, state.storefrontMatch!!.focus)
    }

    // ── Cancelling ─────────────────────────────────────────────────────────────

    @Test
    fun `Back clears the whole flow from every step`() {
        for (outcome in listOf(
            LocalSteamFolderLinker.LinkOutcome.NeedsConfirmation(candidateResult(), "portal 2", "Portal 2"),
            LocalSteamFolderLinker.LinkOutcome.NeedsKit("620", "Portal 2", true, AppIdSource.MARKER),
            LocalSteamFolderLinker.LinkOutcome.NoEmuData("nope"),
        )) {
            coEvery { linker.link(gameId, pickedUri) } returns outcome
            viewModel.onFolderPicked(pickedUri)
            viewModel.handleGamepadAction(GamepadAction.BACK)

            assertNull(state.autoMatchStep, "step survived Back from $outcome")
            assertNull(state.storefrontMatch)
            assertNull(state.kitPrompt)
            assertNull(state.pickedFolderUri)
        }
    }

    @Test
    fun `reopening the page starts the flow fresh rather than resuming a half-finished pick`() {
        coEvery { linker.link(gameId, pickedUri) } returns
            LocalSteamFolderLinker.LinkOutcome.NeedsKit("620", "Portal 2", true, AppIdSource.MARKER)
        viewModel.onFolderPicked(pickedUri)
        assertEquals(AutoMatchStep.CONFIRM_KIT, state.autoMatchStep)

        viewModel.load(ShibaCoinsTarget.LibraryGame(gameId))

        assertNull(state.autoMatchStep)
        assertNull(state.kitPrompt)
        assertNull(state.pickedFolderUri)
    }
}
