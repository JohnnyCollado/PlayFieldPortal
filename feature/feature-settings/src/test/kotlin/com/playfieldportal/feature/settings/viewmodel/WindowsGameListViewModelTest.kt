package com.playfieldportal.feature.settings.viewmodel

import android.net.Uri
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.WindowsGameLabel
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.achievements.provider.localsteam.AppIdSource
import com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamFolderLinker
import com.playfieldportal.feature.achievements.provider.steam.WindowsGameLabeler
import com.playfieldportal.feature.achievements.provider.steam.WindowsGameLabeler.LabelOutcome
import com.playfieldportal.feature.achievements.provider.steam.WindowsSteamGate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WindowsGameListViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val gameRepository = mockk<GameRepository>(relaxed = true)
    private val gate = mockk<WindowsSteamGate>(relaxed = true)
    private val labeler = mockk<WindowsGameLabeler>(relaxed = true)
    private val folderLinker = mockk<LocalSteamFolderLinker>(relaxed = true)
    private val treeUri = mockk<Uri>()

    private var games = listOf<Game>()
    private var folders = mapOf<Long, WindowsGameLabeler.LinkedFolder>()

    private fun game(id: Long, title: String, label: WindowsGameLabel? = null) = Game(
        id = id,
        title = title,
        platformId = "windows",
        romPath = "/windows/$title",
        windowsLabel = label,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { gameRepository.getByPlatform("windows") } answers { games }
        coEvery { gameRepository.getById(any()) } answers { games.firstOrNull { it.id == firstArg<Long>() } }
        coEvery { gate.autoLabel(any()) } returns null
        coEvery { labeler.linkedFolders() } answers { folders }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun createVm() = WindowsGameListViewModel(gameRepository, gate, labeler, folderLinker)

    private fun WindowsGameListViewModel.row(id: Long) = uiState.value.rows.first { it.gameId == id }

    @Test
    fun `rows list every Windows game sorted by title`() = runTest(dispatcher) {
        games = listOf(game(1, "zelda"), game(2, "Alpha"), game(3, "Beta"))
        val vm = createVm()
        advanceUntilIdle()

        assertEquals(listOf("Alpha", "Beta", "zelda"), vm.uiState.value.rows.map { it.title })
    }

    @Test
    fun `an unlabelled game shows the automatic guess`() = runTest(dispatcher) {
        games = listOf(game(1, "Hades"), game(2, "Celeste"))
        coEvery { gate.autoLabel(games[0]) } returns WindowsGameLabel.STEAM
        coEvery { gate.autoLabel(games[1]) } returns WindowsGameLabel.LOCAL
        val vm = createVm()
        advanceUntilIdle()

        assertEquals("Auto: Steam", labelValueText(vm.row(1)))
        assertEquals("Auto: Local", labelValueText(vm.row(2)))
    }

    @Test
    fun `a game PFP cannot guess reads Not set`() = runTest(dispatcher) {
        games = listOf(game(1, "Mystery"))
        val vm = createVm()
        advanceUntilIdle()

        assertEquals("Not set", labelValueText(vm.row(1)))
    }

    @Test
    fun `a labelled game shows its label whatever the guess`() = runTest(dispatcher) {
        games = listOf(game(1, "A", WindowsGameLabel.STEAM), game(2, "B", WindowsGameLabel.NON_STEAM), game(3, "C", WindowsGameLabel.LOCAL))
        coEvery { gate.autoLabel(any()) } returns WindowsGameLabel.LOCAL
        val vm = createVm()
        advanceUntilIdle()

        assertEquals("Steam", labelValueText(vm.row(1)))
        assertEquals("Non-Steam (owned)", labelValueText(vm.row(2)))
        assertEquals("Local", labelValueText(vm.row(3)))
    }

    @Test
    fun `only Local rows offer the folder action`() = runTest(dispatcher) {
        games = listOf(
            game(1, "A", WindowsGameLabel.LOCAL),
            game(2, "B", WindowsGameLabel.STEAM),
            game(3, "C", WindowsGameLabel.NON_STEAM),
            game(4, "D"),
        )
        val vm = createVm()
        advanceUntilIdle()

        assertTrue(showsFolderAction(vm.row(1)))
        assertFalse(showsFolderAction(vm.row(2)))
        assertFalse(showsFolderAction(vm.row(3)))
        assertFalse(showsFolderAction(vm.row(4)))
    }

    @Test
    fun `the picker marks the current choice`() = runTest(dispatcher) {
        games = listOf(game(1, "A", WindowsGameLabel.NON_STEAM), game(2, "B"))
        coEvery { gate.autoLabel(games[1]) } returns WindowsGameLabel.STEAM
        val vm = createVm()
        advanceUntilIdle()

        val labelled = labelPickerItems(vm.row(1))
        assertEquals(listOf("Auto", "Steam", "Non-Steam (owned)", "Local"), labelled.map { it.text })
        assertEquals(listOf(WindowsGameLabel.NON_STEAM), labelled.filter { it.isCurrent }.map { it.label })

        val auto = labelPickerItems(vm.row(2))
        assertEquals("Auto (Steam)", auto.first().text)
        assertEquals(listOf(null), auto.filter { it.isCurrent }.map { it.label })
    }

    @Test
    fun `setting a label goes through the labeler and refreshes the row`() = runTest(dispatcher) {
        games = listOf(game(1, "A"))
        val vm = createVm()
        advanceUntilIdle()
        coEvery { labeler.setLabel(1, WindowsGameLabel.LOCAL) } answers {
            games = listOf(game(1, "A", WindowsGameLabel.LOCAL))
            folders = mapOf(1L to WindowsGameLabeler.LinkedFolder("220", "A", "primary:Games/A"))
            LabelOutcome.FolderLinked("A")
        }

        vm.setLabel(1, WindowsGameLabel.LOCAL)
        advanceUntilIdle()

        coVerify(exactly = 1) { labeler.setLabel(1, WindowsGameLabel.LOCAL) }
        assertEquals(WindowsGameLabel.LOCAL, vm.row(1).label)
        assertTrue(vm.row(1).folderLinked)
        assertEquals("/storage/emulated/0/Games/A", vm.row(1).folderPath)
        assertFalse(vm.row(1).busy)
    }

    @Test
    fun `a linked folder without a registry path falls back to its name or none`() = runTest(dispatcher) {
        games = listOf(game(1, "A", WindowsGameLabel.LOCAL), game(2, "B", WindowsGameLabel.LOCAL), game(3, "C", WindowsGameLabel.LOCAL))
        folders = mapOf(
            1L to WindowsGameLabeler.LinkedFolder("1", "Folder A", null),
            2L to WindowsGameLabeler.LinkedFolder("2", null, null),
        )
        val vm = createVm()
        advanceUntilIdle()

        assertEquals("Folder A", vm.row(1).folderPath)
        assertTrue(vm.row(1).folderLinked)
        assertNull(vm.row(2).folderPath)
        assertTrue(vm.row(2).folderLinked)
        assertFalse(vm.row(3).folderLinked)
    }

    @Test
    fun `a picked folder links through the folder linker and shows its path`() = runTest(dispatcher) {
        games = listOf(game(1, "A", WindowsGameLabel.LOCAL))
        val vm = createVm()
        advanceUntilIdle()
        coEvery { folderLinker.link(1, treeUri) } answers {
            folders = mapOf(1L to WindowsGameLabeler.LinkedFolder("220", "A", "1A2B-3C4D:Games/A"))
            LocalSteamFolderLinker.LinkOutcome.Linked("220", "A")
        }

        vm.beginFolderPick(1)
        assertTrue(vm.uiState.value.requestFolderPick)
        vm.onFolderPickLaunched()
        assertFalse(vm.uiState.value.requestFolderPick)
        vm.onFolderPicked(treeUri)
        advanceUntilIdle()

        coVerify(exactly = 1) { folderLinker.link(1, treeUri) }
        assertEquals("/storage/1A2B-3C4D/Games/A", vm.row(1).folderPath)
        assertNull(vm.uiState.value.notice)
        assertNull(vm.uiState.value.kitChoice)
    }

    @Test
    fun `a folder with no achievement list offers link only, and install only when enabled`() = runTest(dispatcher) {
        games = listOf(game(1, "A", WindowsGameLabel.LOCAL))
        val vm = createVm()
        advanceUntilIdle()
        coEvery { folderLinker.link(1, treeUri) } returns
            LocalSteamFolderLinker.LinkOutcome.NeedsKit("220", "A", installerEnabled = false, source = AppIdSource.MARKER)

        vm.beginFolderPick(1)
        vm.onFolderPicked(treeUri)
        advanceUntilIdle()

        val choice = vm.uiState.value.kitChoice
        assertNotNull(choice)
        assertEquals(1L, choice!!.gameId)
        assertEquals("220", choice.appId)
        assertEquals("A", choice.folderName)
        assertEquals(AppIdSource.MARKER, choice.source)
        assertEquals(treeUri, choice.treeUri)
        assertFalse(choice.installerEnabled)

        coEvery { folderLinker.linkWithoutKit(1, treeUri, "220", AppIdSource.MARKER) } answers {
            folders = mapOf(1L to WindowsGameLabeler.LinkedFolder("220", "A", "primary:A"))
            LocalSteamFolderLinker.LinkOutcome.Linked("220", "A")
        }
        vm.linkOnly()
        advanceUntilIdle()

        coVerify(exactly = 1) { folderLinker.linkWithoutKit(1, treeUri, "220", AppIdSource.MARKER) }
        assertNull(vm.uiState.value.kitChoice)
        assertTrue(vm.row(1).folderLinked)

        coEvery { folderLinker.link(1, treeUri) } returns
            LocalSteamFolderLinker.LinkOutcome.NeedsKit("220", "A", installerEnabled = true, source = AppIdSource.MARKER)
        coEvery { folderLinker.installKitAndLink(1, treeUri, "220", AppIdSource.MARKER) } returns
            LocalSteamFolderLinker.LinkOutcome.Failed("Could not write the achievement kit: nope")
        vm.beginFolderPick(1)
        vm.onFolderPicked(treeUri)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.kitChoice!!.installerEnabled)

        vm.installKitAndLink()
        advanceUntilIdle()

        coVerify(exactly = 1) { folderLinker.installKitAndLink(1, treeUri, "220", AppIdSource.MARKER) }
        assertNull(vm.uiState.value.kitChoice)
        assertEquals("Could not write the achievement kit: nope", vm.uiState.value.notice)
    }

    @Test
    fun `a dismissed kit choice links nothing`() = runTest(dispatcher) {
        games = listOf(game(1, "A", WindowsGameLabel.LOCAL))
        val vm = createVm()
        advanceUntilIdle()
        coEvery { folderLinker.link(1, treeUri) } returns
            LocalSteamFolderLinker.LinkOutcome.NeedsKit("220", "A", installerEnabled = true, source = AppIdSource.MARKER)
        vm.beginFolderPick(1)
        vm.onFolderPicked(treeUri)
        advanceUntilIdle()

        vm.dismissKitChoice()

        assertNull(vm.uiState.value.kitChoice)
        coVerify(exactly = 0) { folderLinker.linkWithoutKit(any(), any(), any(), any()) }
        coVerify(exactly = 0) { folderLinker.installKitAndLink(any(), any(), any(), any()) }
    }

    @Test
    fun `an ambiguous folder points to Shiba Coins and writes nothing`() = runTest(dispatcher) {
        games = listOf(game(1, "A", WindowsGameLabel.LOCAL))
        val vm = createVm()
        advanceUntilIdle()
        coEvery { folderLinker.link(1, treeUri) } returns
            LocalSteamFolderLinker.LinkOutcome.NeedsConfirmation(mockk(), "A", "A")

        vm.beginFolderPick(1)
        vm.onFolderPicked(treeUri)
        advanceUntilIdle()

        val notice = vm.uiState.value.notice
        assertNotNull(notice)
        assertTrue(notice!!.contains("Shiba Coins"))
        assertNull(vm.uiState.value.kitChoice)
        assertFalse(vm.row(1).folderLinked)
        coVerify(exactly = 0) { folderLinker.confirmCandidate(any(), any(), any()) }

        vm.dismissNotice()
        assertNull(vm.uiState.value.notice)
    }

    @Test
    fun `an unreadable folder shows the linker's reason`() = runTest(dispatcher) {
        games = listOf(game(1, "A", WindowsGameLabel.LOCAL))
        val vm = createVm()
        advanceUntilIdle()
        coEvery { folderLinker.link(1, treeUri) } returns LocalSteamFolderLinker.LinkOutcome.NoEmuData("No app id here")

        vm.beginFolderPick(1)
        vm.onFolderPicked(treeUri)
        advanceUntilIdle()

        assertEquals("No app id here", vm.uiState.value.notice)

        coEvery { folderLinker.link(1, treeUri) } throws IllegalStateException("boom")
        vm.beginFolderPick(1)
        vm.onFolderPicked(treeUri)
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.notice)
        assertFalse(vm.row(1).busy)
    }

    @Test
    fun `a dismissed picker changes nothing`() = runTest(dispatcher) {
        games = listOf(game(1, "A", WindowsGameLabel.LOCAL))
        val vm = createVm()
        advanceUntilIdle()

        vm.beginFolderPick(1)
        vm.onFolderPickLaunched()
        vm.onFolderPicked(null)
        advanceUntilIdle()

        coVerify(exactly = 0) { folderLinker.link(any(), any()) }
        assertNull(vm.uiState.value.notice)
        assertNull(vm.uiState.value.kitChoice)
        assertFalse(vm.uiState.value.requestFolderPick)
        assertFalse(vm.row(1).busy)
    }
}
