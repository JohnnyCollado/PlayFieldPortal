package com.playfieldportal.feature.xmb.ui.app

import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.repository.GameRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * App Detail shows the app it was opened for, however the loads finish.
 *
 * Survey L13 (docs/audits/codebase-survey-2026-10-22.md): the activity-scoped view model's load had
 * no guard, so opening App A, backing out and opening App B could show A — and edits went to A.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppDetailLoadTest {

    private val dispatcher = StandardTestDispatcher()
    private val games = mockk<GameRepository>(relaxed = true)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun app(id: Long) = Game(id = id, title = "App $id", platformId = "app_shortcut", romPath = null)

    private fun viewModel() = AppDetailViewModel(
        games, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
    )

    @Test
    fun `a slower load for the app just closed never replaces the one opened after it`() = runTest(dispatcher) {
        val slowA = CompletableDeferred<Game?>()
        coEvery { games.getById(1L) } coAnswers { slowA.await() }
        coEvery { games.getById(2L) } returns app(2L)
        val vm = viewModel()

        vm.prepareForOpen(); vm.loadApp(1L)
        dispatcher.scheduler.advanceUntilIdle()
        vm.prepareForOpen(); vm.loadApp(2L)
        dispatcher.scheduler.advanceUntilIdle()
        slowA.complete(app(1L))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2L, vm.uiState.value.game?.id)
    }
}
