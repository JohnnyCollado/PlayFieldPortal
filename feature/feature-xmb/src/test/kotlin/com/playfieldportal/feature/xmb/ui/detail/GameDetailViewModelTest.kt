package com.playfieldportal.feature.xmb.ui.detail

import android.content.Context
import app.cash.turbine.test
import com.playfieldportal.core.data.database.dao.PlatformDao
import com.playfieldportal.core.data.database.entity.PlatformEntity
import com.playfieldportal.core.data.repository.MemoryCardRepository
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.MemoryCard
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.artwork.ScreenScraperApi
import com.playfieldportal.feature.artwork.TheGamesDbApi
import com.playfieldportal.feature.artwork.api.ArtworkFetchResult
import com.playfieldportal.feature.artwork.api.ArtworkRepository
import com.playfieldportal.feature.artwork.api.IgdbApi
import com.playfieldportal.feature.artwork.api.SteamGridDbApi
import com.playfieldportal.feature.artwork.card.CardArtworkProcessor
import com.playfieldportal.feature.launcher.EmulatorIntentResolver
import com.playfieldportal.feature.launcher.EmulatorProfileRepository
import io.mockk.any
import io.mockk.anyOrNull
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.eq
import io.mockk.every
import io.mockk.match
import io.mockk.mockk
import io.mockk.returnsMany
import io.mockk.verify
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
    private lateinit var memoryCardRepository: MemoryCardRepository
    private lateinit var profileRepository: EmulatorProfileRepository
    private lateinit var intentResolver: EmulatorIntentResolver
    private lateinit var artworkRepository: ArtworkRepository
    private lateinit var steamGridDb: SteamGridDbApi
    private lateinit var cardProcessor: CardArtworkProcessor
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
        memoryCardRepository = mockk(relaxed = true)
        profileRepository = mockk(relaxed = true)
        intentResolver    = mockk(relaxed = true)
        artworkRepository = mockk(relaxed = true)
        steamGridDb       = mockk(relaxed = true)
        cardProcessor     = mockk(relaxed = true)

        coEvery { gameRepository.getById(1L) }    returns fakeGame
        coEvery { platformDao.getById("psx") }    returns fakePlatform
        coEvery { memoryCardRepository.getById("psx") } returns null
        every { profileRepository.getInstalledProfiles() }         returns emptyList()
        every { profileRepository.getProfilesForPlatform(any()) }  returns emptyList()

        viewModel = GameDetailViewModel(
            context           = context,
            gameRepository    = gameRepository,
            platformDao       = platformDao,
            memoryCardRepository = memoryCardRepository,
            profileRepository = profileRepository,
            intentResolver    = intentResolver,
            artworkRepository = artworkRepository,
            steamGridDb       = steamGridDb,
            cardProcessor     = cardProcessor,
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

    @Test
    fun `launch emits intent when profile and resolver both succeed`() = runTest {
        val fakeProfile = com.playfieldportal.core.domain.model.EmulatorProfile(
            id                   = "ppsspp",
            name                 = "PPSSPP",
            packageName          = "org.ppsspp.ppsspp",
            intentType           = com.playfieldportal.core.domain.model.IntentType.ACTION_VIEW,
            supportedPlatformIds = listOf("psx"),
        )
        val fakeIntent = android.content.Intent(android.content.Intent.ACTION_VIEW)
        every { profileRepository.getInstalledProfiles() }        returns listOf(fakeProfile)
        every { profileRepository.getProfilesForPlatform("psx") } returns listOf(fakeProfile)
        every { intentResolver.resolve(any(), any()) }            returns Result.success(fakeIntent)

        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.launchEffect.test {
            viewModel.launch()
            testDispatcher.scheduler.advanceUntilIdle()
            val emitted = awaitItem()
            assertEquals(fakeIntent, emitted)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `launch uses per-game emulator override before platform default`() = runTest {
        val overrideGame = fakeGame.copy(emulatorPackage = "duckstation")
        val platformDefault = fakePlatform.copy(preferredEmulatorPackage = "retroarch_aarch64")
        val duckstation = com.playfieldportal.core.domain.model.EmulatorProfile(
            id                   = "duckstation",
            name                 = "DuckStation",
            packageName          = "com.github.stenzek.duckstation",
            intentType           = com.playfieldportal.core.domain.model.IntentType.ACTION_VIEW,
            supportedPlatformIds = listOf("ps1"),
        )
        val retroarch = com.playfieldportal.core.domain.model.EmulatorProfile(
            id                   = "retroarch_aarch64",
            name                 = "RetroArch (64-bit)",
            packageName          = "com.retroarch.aarch64",
            intentType           = com.playfieldportal.core.domain.model.IntentType.COMPONENT,
            supportedPlatformIds = listOf("psx"),
        )
        val fakeIntent = android.content.Intent(android.content.Intent.ACTION_VIEW)
        coEvery { gameRepository.getById(1L) } returns overrideGame
        coEvery { platformDao.getById("psx") } returns platformDefault
        every { profileRepository.getInstalledProfiles() } returns listOf(retroarch, duckstation)
        every { profileRepository.getProfilesForPlatform("psx") } returns listOf(retroarch, duckstation)
        every { intentResolver.resolve(any(), any()) } returns Result.success(fakeIntent)

        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.launch()
        testDispatcher.scheduler.advanceUntilIdle()

        verify { intentResolver.resolve(overrideGame, match { it.id == "duckstation" }) }
    }

    @Test
    fun `launch uses memory card emulator when game has no emulator override`() = runTest {
        val platformDefault = fakePlatform.copy(preferredEmulatorPackage = "retroarch_aarch64")
        val memoryCard = MemoryCard(
            platformId = "psx",
            displayName = "PlayStation Memory Card",
            emulatorId = "duckstation",
        )
        val duckstation = com.playfieldportal.core.domain.model.EmulatorProfile(
            id                   = "duckstation",
            name                 = "DuckStation",
            packageName          = "com.github.stenzek.duckstation",
            intentType           = com.playfieldportal.core.domain.model.IntentType.ACTION_VIEW,
            supportedPlatformIds = listOf("ps1"),
        )
        val retroarch = com.playfieldportal.core.domain.model.EmulatorProfile(
            id                   = "retroarch_aarch64",
            name                 = "RetroArch (64-bit)",
            packageName          = "com.retroarch.aarch64",
            intentType           = com.playfieldportal.core.domain.model.IntentType.COMPONENT,
            supportedPlatformIds = listOf("psx"),
        )
        val fakeIntent = android.content.Intent(android.content.Intent.ACTION_VIEW)
        coEvery { platformDao.getById("psx") } returns platformDefault
        coEvery { memoryCardRepository.getById("psx") } returns memoryCard
        every { profileRepository.getInstalledProfiles() } returns listOf(retroarch, duckstation)
        every { profileRepository.getProfilesForPlatform("psx") } returns listOf(retroarch, duckstation)
        every { intentResolver.resolve(any(), any()) } returns Result.success(fakeIntent)

        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.launch()
        testDispatcher.scheduler.advanceUntilIdle()

        verify { intentResolver.resolve(fakeGame, match { it.id == "duckstation" }) }
    }

    @Test
    fun `launch uses platform default when game has no emulator override`() = runTest {
        val platformDefault = fakePlatform.copy(preferredEmulatorPackage = "duckstation")
        val duckstation = com.playfieldportal.core.domain.model.EmulatorProfile(
            id                   = "duckstation",
            name                 = "DuckStation",
            packageName          = "com.github.stenzek.duckstation",
            intentType           = com.playfieldportal.core.domain.model.IntentType.ACTION_VIEW,
            supportedPlatformIds = listOf("ps1"),
        )
        val retroarch = com.playfieldportal.core.domain.model.EmulatorProfile(
            id                   = "retroarch_aarch64",
            name                 = "RetroArch (64-bit)",
            packageName          = "com.retroarch.aarch64",
            intentType           = com.playfieldportal.core.domain.model.IntentType.COMPONENT,
            supportedPlatformIds = listOf("psx"),
        )
        val fakeIntent = android.content.Intent(android.content.Intent.ACTION_VIEW)
        coEvery { platformDao.getById("psx") } returns platformDefault
        every { profileRepository.getInstalledProfiles() } returns listOf(retroarch, duckstation)
        every { profileRepository.getProfilesForPlatform("psx") } returns listOf(retroarch, duckstation)
        every { intentResolver.resolve(any(), any()) } returns Result.success(fakeIntent)

        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.launch()
        testDispatcher.scheduler.advanceUntilIdle()

        verify { intentResolver.resolve(fakeGame, match { it.id == "duckstation" }) }
    }

    @Test
    fun `launch sets launchError when resolver returns failure for missing ROM`() = runTest {
        val fakeProfile = com.playfieldportal.core.domain.model.EmulatorProfile(
            id                   = "ppsspp",
            name                 = "PPSSPP",
            packageName          = "org.ppsspp.ppsspp",
            intentType           = com.playfieldportal.core.domain.model.IntentType.ACTION_VIEW,
            supportedPlatformIds = listOf("psx"),
        )
        every { profileRepository.getInstalledProfiles() }        returns listOf(fakeProfile)
        every { profileRepository.getProfilesForPlatform("psx") } returns listOf(fakeProfile)
        every { intentResolver.resolve(any(), any()) }            returns Result.failure(
            IllegalStateException("ROM file not found: /roms/psx/crash.bin")
        )

        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.launch()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertNotNull(state.launchError)
            assertTrue(state.launchError!!.contains("ROM file not found"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `launch sets launchError when resolver returns failure for uninstalled emulator`() = runTest {
        val fakeProfile = com.playfieldportal.core.domain.model.EmulatorProfile(
            id                   = "retroarch_aarch64",
            name                 = "RetroArch (64-bit)",
            packageName          = "com.retroarch.aarch64",
            intentType           = com.playfieldportal.core.domain.model.IntentType.COMPONENT,
            activityClass        = "com.retroarch.browser.retroactivity.RetroActivityFuture",
            supportedPlatformIds = listOf("psx"),
        )
        every { profileRepository.getInstalledProfiles() }        returns listOf(fakeProfile)
        every { profileRepository.getProfilesForPlatform("psx") } returns listOf(fakeProfile)
        every { intentResolver.resolve(any(), any()) }            returns Result.failure(
            IllegalStateException("Emulator not installed: RetroArch (64-bit) (com.retroarch.aarch64)")
        )

        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.launch()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertNotNull(state.launchError)
            assertTrue(state.launchError!!.contains("Emulator not installed"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `launch sets launchError when resolver returns failure for missing RetroArch core`() = runTest {
        val fakeProfile = com.playfieldportal.core.domain.model.EmulatorProfile(
            id                   = "retroarch_aarch64",
            name                 = "RetroArch (64-bit)",
            packageName          = "com.retroarch.aarch64",
            intentType           = com.playfieldportal.core.domain.model.IntentType.COMPONENT,
            activityClass        = "com.retroarch.browser.retroactivity.RetroActivityFuture",
            supportedPlatformIds = listOf("psx"),
            coreMap              = mapOf("psx" to "/data/data/com.retroarch.aarch64/cores/pcsx_rearmed.so"),
        )
        every { profileRepository.getInstalledProfiles() }        returns listOf(fakeProfile)
        every { profileRepository.getProfilesForPlatform("psx") } returns listOf(fakeProfile)
        every { intentResolver.resolve(any(), any()) }            returns Result.failure(
            IllegalStateException("RetroArch core not found: /data/data/com.retroarch.aarch64/cores/pcsx_rearmed.so")
        )

        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.launch()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertNotNull(state.launchError)
            assertTrue(state.launchError!!.contains("RetroArch core not found"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onLaunchFailed sets launchError in state`() = runTest {
        viewModel.onLaunchFailed("Emulator not found. Is it installed?")

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("Emulator not found. Is it installed?", state.launchError)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `dismissLaunchError clears launchError`() = runTest {
        viewModel.onLaunchFailed("some error")
        viewModel.dismissLaunchError()

        viewModel.uiState.test {
            assertNull(awaitItem().launchError)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── artwork ───────────────────────────────────────────────────────────

    @Test
    fun `prepareForOpen clears stale closed state before reopening same game`() = runTest {
        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.handleGamepadAction(com.playfieldportal.core.domain.model.GamepadAction.BACK)
        assertTrue(viewModel.uiState.value.closed)

        viewModel.prepareForOpen()
        assertFalse(viewModel.uiState.value.closed)

        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.closed)
            assertEquals("Crash Bandicoot", state.game?.title)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── clearArtwork ──────────────────────────────────────────────────────

    @Test
    fun `clearArtwork BACKGROUND only clears artworkUri`() = runTest {
        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.clearArtwork(ArtworkType.BACKGROUND)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { gameRepository.updateBoxArt(1L, null) }
        coVerify(exactly = 0) { gameRepository.updateIconArt(any(), anyOrNull()) }
        coVerify(exactly = 0) { gameRepository.updateHeroArt(any(), anyOrNull()) }
    }

    @Test
    fun `clearArtwork HERO only clears heroUri`() = runTest {
        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.clearArtwork(ArtworkType.HERO)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { gameRepository.updateHeroArt(1L, null) }
        coVerify(exactly = 0) { gameRepository.updateIconArt(any(), anyOrNull()) }
        coVerify(exactly = 0) { gameRepository.updateBoxArt(any(), anyOrNull()) }
    }

    @Test
    fun `clearArtwork ICON only clears iconUri`() = runTest {
        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.clearArtwork(ArtworkType.ICON)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { gameRepository.updateIconArt(1L, null) }
        coVerify(exactly = 0) { gameRepository.updateHeroArt(any(), anyOrNull()) }
        coVerify(exactly = 0) { gameRepository.updateBoxArt(any(), anyOrNull()) }
    }

    // ── pickSgdbArtwork ───────────────────────────────────────────────────

    @Test
    fun `pickSgdbArtwork BACKGROUND failure keeps existing artwork and shows error`() = runTest {
        coEvery { cardProcessor.downloadRaw(any(), any(), any(), any()) } returns null

        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.pickSgdbArtwork("https://cdn.steamgriddb.com/art.png", ArtworkType.BACKGROUND)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { gameRepository.updateBoxArt(any(), any()) }
        coVerify(exactly = 0) { gameRepository.updateIconArt(any(), any()) }

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.artworkIsProcessing)
            assertNotNull(state.artworkMessage)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pickSgdbArtwork HERO only updates heroUri not iconUri`() = runTest {
        coEvery { cardProcessor.downloadRaw(any(), any(), any(), any()) } returns null

        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.pickSgdbArtwork("https://cdn.steamgriddb.com/hero.jpg", ArtworkType.HERO)
        testDispatcher.scheduler.advanceUntilIdle()

        // downloadRaw returned null so repo should not be called — just verify no icon update
        coVerify(exactly = 0) { gameRepository.updateIconArt(any(), any()) }
    }

    @Test
    fun `pickSgdbArtwork succeeds on second import for same URL`() = runTest {
        // Regression: second SGDB import of the same URL must not fail. Previously, recycling
        // a Coil memory-cached bitmap caused the second downloadRaw call to return null.
        val firstPath  = "/data/artwork/1/icon_111.jpg"
        val secondPath = "/data/artwork/1/icon_222.jpg"
        coEvery {
            cardProcessor.downloadRaw(any(), eq("https://cdn.sgdb.com/icon.jpg"), any(), any())
        } returnsMany listOf(firstPath, secondPath)

        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.pickSgdbArtwork("https://cdn.sgdb.com/icon.jpg", ArtworkType.ICON)
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { gameRepository.updateIconArt(1L, firstPath) }

        viewModel.pickSgdbArtwork("https://cdn.sgdb.com/icon.jpg", ArtworkType.ICON)
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { gameRepository.updateIconArt(1L, secondPath) }

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.artworkIsProcessing)
            assertNull(state.artworkSgdbError)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `fetchArtwork clears artwork cache after scrape completes`() = runTest {
        coEvery { artworkRepository.fetchArtworkForGame(any(), any()) } returns
            ArtworkFetchResult(gameId = 1L, title = "Crash Bandicoot", success = true)

        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.fetchArtwork()
        testDispatcher.scheduler.advanceUntilIdle()

        // The scraper overwrites the fixed-path card file; Coil must re-read from disk.
        coVerify { artworkRepository.clearCache() }

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
