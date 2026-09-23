package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.BackgroundTaskInfo
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.NotificationAction
import com.playfieldportal.core.domain.model.TaskKind
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.core.ui.notification.BackgroundTaskCenter
import com.playfieldportal.feature.artwork.api.ArtworkFetchResult
import com.playfieldportal.feature.artwork.api.ArtworkRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * Fetch Artwork from the XMB game menu. Progress goes on a RUNNING task row; the outcome goes to
 * the notification tray only, and tapping it opens the game.
 */
class GameArtworkFetchRunnerTest {

    private val artworkRepository = mockk<ArtworkRepository>()
    private val gameRepository = mockk<GameRepository>()
    private val running = MutableStateFlow<List<BackgroundTaskInfo>>(emptyList())
    private val tasks = mockk<BackgroundTaskCenter>(relaxed = true) {
        every { this@mockk.running } returns this@GameArtworkFetchRunnerTest.running
    }

    private val runner = GameArtworkFetchRunner(artworkRepository, gameRepository, tasks)

    private val game = Game(
        id = 1L, title = "crash", platformId = "psx", romPath = "/roms/psx/crash.bin",
        packageName = null, emulatorPackage = null, artworkUri = null, heroUri = null, logoUri = null,
        description = null, developer = null, publisher = null, releaseYear = null, genre = null,
        steamGridDbId = null, totalPlayTimeMillis = 0L, lastPlayedAt = null, userNote = null,
        userTitleOverride = "Crash Bandicoot",
    )

    private val taskId = "fetch_artwork_1"

    init {
        coEvery { gameRepository.getById(1L) } returns game
    }

    @Test
    fun `a successful fetch runs as an artwork task and settles in the tray`() = runTest {
        coEvery { artworkRepository.refetchArtworkForGame(1L, any()) } returns
            ArtworkFetchResult(1L, "crash", success = true)

        val changed = runner.run(1L)

        assertTrue(changed)
        verify {
            tasks.start(match<BackgroundTaskInfo> {
                it.id == taskId && it.kind == TaskKind.ARTWORK && it.label == "Fetching artwork: Crash Bandicoot"
            })
        }
        verify { tasks.complete(taskId, "Artwork updated", NotificationAction.OpenGame(1L)) }
    }

    @Test
    fun `each asset being fetched shows on the running row`() = runTest {
        coEvery { artworkRepository.refetchArtworkForGame(1L, any()) } answers {
            secondArg<((String, String) -> Unit)?>()?.invoke("TheGamesDB", "Box Art")
            ArtworkFetchResult(1L, "crash", success = true)
        }

        runner.run(1L)

        // No counts for a single game: total 0 keeps the bar indeterminate.
        verify { tasks.progress(taskId, 0, 0, "TheGamesDB · Box Art") }
    }

    @Test
    fun `a fetch that finds nothing fails in the tray with the scraper's message`() = runTest {
        coEvery { artworkRepository.refetchArtworkForGame(1L, any()) } returns
            ArtworkFetchResult(1L, "crash", success = false, errorMessage = "Not found on any source")

        val changed = runner.run(1L)

        assertFalse(changed)
        verify { tasks.fail(taskId, "Not found on any source", NotificationAction.OpenGame(1L)) }
    }

    @Test
    fun `a fetch that throws fails in the tray`() = runTest {
        coEvery { artworkRepository.refetchArtworkForGame(1L, any()) } throws IllegalStateException("boom")

        val changed = runner.run(1L)

        assertFalse(changed)
        verify { tasks.fail(taskId, "Artwork fetch failed", NotificationAction.OpenGame(1L)) }
    }

    @Test
    fun `cancellation is rethrown, not reported`() = runTest {
        coEvery { artworkRepository.refetchArtworkForGame(1L, any()) } throws CancellationException("left")

        assertFailsWith<CancellationException> { runner.run(1L) }

        verify(exactly = 0) { tasks.fail(any(), any(), any()) }
        verify(exactly = 0) { tasks.complete(any(), any(), any()) }
    }

    @Test
    fun `a second menu fetch for the same game while its row is running does nothing`() = runTest {
        running.value = listOf(BackgroundTaskInfo(id = taskId, label = "Fetching artwork: Crash Bandicoot"))

        val changed = runner.run(1L)

        assertFalse(changed)
        coVerify(exactly = 0) { artworkRepository.refetchArtworkForGame(any(), any()) }
        verify(exactly = 0) { tasks.start(any<BackgroundTaskInfo>()) }
    }

    @Test
    fun `a fetch already running from Game Detail drops the row without a tray entry`() = runTest {
        coEvery { artworkRepository.refetchArtworkForGame(1L, any()) } returns
            ArtworkFetchResult(1L, "crash", success = false, alreadyRunning = true)

        val changed = runner.run(1L)

        assertFalse(changed)
        verify { tasks.cancel(taskId) }
        verify(exactly = 0) { tasks.fail(any(), any(), any()) }
        verify(exactly = 0) { tasks.complete(any(), any(), any()) }
    }

    @Test
    fun `an unknown game does nothing`() = runTest {
        coEvery { gameRepository.getById(9L) } returns null

        assertFalse(runner.run(9L))
        verify(exactly = 0) { tasks.start(any<BackgroundTaskInfo>()) }
    }
}
