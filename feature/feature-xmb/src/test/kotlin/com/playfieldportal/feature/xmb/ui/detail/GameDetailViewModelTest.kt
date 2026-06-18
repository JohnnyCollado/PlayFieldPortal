package com.playfieldportal.feature.xmb.ui.detail

import android.content.Context
import app.cash.turbine.test
import com.playfieldportal.core.data.database.dao.PlatformDao
import com.playfieldportal.core.data.database.entity.PlatformEntity
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.artwork.api.ArtworkFetchResult
import com.playfieldportal.feature.artwork.api.ArtworkRepository
import com.playfieldportal.feature.launcher.EmulatorIntentResolver
import com.playfieldportal.feature.launcher.EmulatorProfileRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
class GameDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var context: Context
    private lateinit var gameRepository: GameRepository
    private lateinit var platformDao: PlatformDao
    private lateinit var profileRepository: EmulatorProfileRepository
    private lateinit var intentResolver: EmulatorIntentResolver
    private lateinit var artworkRepository: ArtworkRepository
    private lateinit var viewModel: GameDetailViewModel

    private val fakeGame = Game(
        id                  = 1L,
        title               = "Crash Bandicoot",
        platformId          = "psx",
        romPath             = "/roms/psx/crash.bin",
        packageName         = null,
        emulatorPackage     = null,
        artworkUri          = null,
        heroUri             = null,
        logoUri             = null,
        description         = "A classic platformer.",
        developer           = "Naughty Dog",
        publisher           = "Sony",
        releaseYear         = 1996,
        genre               = "Platformer",
        steamGridDbId       = null,
        totalPlayTimeMillis = 7_200_000L,
        lastPlayedAt        = null,
        userNote            = null,
    )

    private val fakePlatform = PlatformEntity(
        id                       = "psx",
        name                     = "PlayStation",
        shortName                = "PS1",
        iconRes                  = null,
        accentColor              = 0xFF0070D1L,
        isPinnedToBar            = false,
        barPosition              = -1,
        preferredEmulatorPackage = null,
        romExtensions            = ".bin,.cue",
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context           = mockk(relaxed = true)
        gameRepository    = mockk(relaxed = true)
        platformDao       = mockk(relaxed = true)
        profileRepository = mockk(relaxed = true)
        intentResolver    = mockk(relaxed = true)
        artworkRepository = mockk(relaxed = true)

        coEvery { gameRepository.getById(1L) }    returns fakeGame
        coEvery { platformDao.getById("psx") }    returns fakePlatform
        every { profileRepository.getInstalledProfiles() }         returns emptyList()
        every { profileRepository.getProfilesForPlatform(any()) }  returns emptyList()

        viewModel = GameDetailViewModel(
            context           = context,
            gameRepository    = gameRepository,
            platformDao       = platformDao,
            profileRepository = profileRepository,
            intentResolver    = intentResolver,
            artworkRepository = artworkRepository,
        )
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    // ── loadGame ──────────────────────────────────────────────────────────

    @Test
    fun `loadGame populates game and platform in state`() = runTest {
        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isLoading)
            assertEquals("Crash Bandicoot", state.game?.title)
            assertEquals("PlayStation",     state.platform?.name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `loadGame sets isLoading false when game not found`() = runTest {
        coEvery { gameRepository.getById(99L) } returns null
        viewModel.loadGame(99L)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isLoading)
            assertNull(state.game)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── toggleFavorite ────────────────────────────────────────────────────

    @Test
    fun `toggleFavorite calls repository and flips isFavorite in state`() = runTest {
        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.toggleFavorite()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { gameRepository.setFavorite(1L, true) }

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state.game?.isFavorite == true)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── note editing ──────────────────────────────────────────────────────

    @Test
    fun `startEditNote sets isEditingNote true`() = runTest {
        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.startEditNote()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiState.test {
            assertTrue(awaitItem().isEditingNote)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `saveNote persists and clears editing state`() = runTest {
        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.startEditNote()
        viewModel.onNoteChanged("Great game!")
        viewModel.saveNote()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { gameRepository.updateNote(1L, "Great game!") }

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isEditingNote)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `saveNote with blank text persists null`() = runTest {
        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.startEditNote()
        viewModel.onNoteChanged("   ")
        viewModel.saveNote()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { gameRepository.updateNote(1L, null) }
    }

    @Test
    fun `cancelNote clears isEditingNote without saving`() = runTest {
        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.startEditNote()
        viewModel.onNoteChanged("unsaved change")
        viewModel.cancelNote()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { gameRepository.updateNote(any(), any()) }

        viewModel.uiState.test {
            assertFalse(awaitItem().isEditingNote)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── launch ────────────────────────────────────────────────────────────

    @Test
    fun `launch sets launchError when no emulator is installed`() = runTest {
        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.launch()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiState.test {
            assertNotNull(awaitItem().launchError)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── artwork ───────────────────────────────────────────────────────────

    @Test
    fun `fetchArtwork sets isFetchingArtwork during fetch then clears it`() = runTest {
        coEvery { artworkRepository.fetchArtworkForGame(any(), any()) } returns
            ArtworkFetchResult(gameId = 1L, title = "Crash Bandicoot", success = true)

        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.fetchArtwork()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isFetchingArtwork)
            assertEquals("Artwork updated", state.artworkMessage)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `dismissArtworkMessage clears artworkMessage`() = runTest {
        coEvery { artworkRepository.fetchArtworkForGame(any(), any()) } returns
            ArtworkFetchResult(1L, "Crash", success = true)

        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.fetchArtwork()
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.dismissArtworkMessage()

        viewModel.uiState.test {
            assertNull(awaitItem().artworkMessage)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
