package com.playfieldportal.feature.xmb.ui.detail

import android.content.Context
import app.cash.turbine.test
import com.playfieldportal.core.data.database.dao.PlatformDao
import com.playfieldportal.core.data.database.entity.PlatformEntity
import com.playfieldportal.core.data.repository.MemoryCardRepository
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.MemoryCard
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.artwork.api.ArtworkFetchResult
import com.playfieldportal.feature.artwork.api.ArtworkRepository
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.feature.artwork.match.MatchProvider
import com.playfieldportal.feature.artwork.match.MetadataApplyPolicy
import com.playfieldportal.feature.artwork.match.MetadataField
import com.playfieldportal.feature.artwork.match.MetadataPreset
import com.playfieldportal.feature.artwork.match.MetadataPreview
import com.playfieldportal.feature.artwork.store.ArtworkStore
import com.playfieldportal.feature.launcher.EmulatorIntentResolver
import com.playfieldportal.feature.launcher.EmulatorProfileRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
    private lateinit var autoCoreMemory: com.playfieldportal.feature.launcher.AutoCoreMemory
    private lateinit var intentResolver: EmulatorIntentResolver
    private lateinit var artworkRepository: ArtworkRepository
    private lateinit var artworkStore: ArtworkStore
    private lateinit var artworkRecordDao: com.playfieldportal.core.data.database.dao.ArtworkRecordDao
    private lateinit var launchDispatcher: com.playfieldportal.feature.launcher.LaunchDispatcher
    private lateinit var menuSound: com.playfieldportal.core.ui.sound.MenuSoundPlayer
    private lateinit var pcGameExporter: com.playfieldportal.feature.settings.pc.PcGameExporter
    private lateinit var storefrontMatches: com.playfieldportal.feature.artwork.match.StorefrontMatchRepository
    private lateinit var achievementRepository: com.playfieldportal.feature.achievements.AchievementController
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
        autoCoreMemory    = mockk(relaxed = true)
        intentResolver    = mockk(relaxed = true)
        artworkRepository = mockk(relaxed = true)
        artworkStore      = mockk(relaxed = true)
        artworkRecordDao  = mockk(relaxed = true)
        // No portable-library records unless a test adds one (a relaxed mock would invent a record).
        coEvery { artworkRecordDao.get(any(), any()) } returns null
        launchDispatcher  = mockk(relaxed = true)
        menuSound         = mockk(relaxed = true)
        pcGameExporter    = mockk(relaxed = true)
        // Explicit (not relaxed): a sealed-interface return can't be auto-mocked, and the
        // default launch path for these tests is a successful hand-off.
        coEvery { launchDispatcher.launch(any(), any(), any()) } returns
            com.playfieldportal.feature.launcher.LaunchDispatchResult.Accepted

        coEvery { gameRepository.getById(1L) }    returns fakeGame
        coEvery { platformDao.getById("psx") }    returns fakePlatform
        coEvery { memoryCardRepository.getById("psx") } returns null
        every { profileRepository.getInstalledProfiles() }         returns emptyList()
        coEvery { profileRepository.getProfilesForPlatform(any()) }  returns emptyList()

        achievementRepository = mockk(relaxed = true)
        storefrontMatches = mockk(relaxed = true)
        // Explicit, for the same reason launchDispatcher is: Lookup is a sealed interface and a
        // relaxed mock cannot invent one. These tests never open the picker, so the honest default
        // is the branch that says there is nothing to look up.
        coEvery { storefrontMatches.lookup(any(), any(), any(), any()) } returns
            com.playfieldportal.feature.artwork.match.StorefrontMatchRepository.Lookup.NotApplicable

        viewModel = GameDetailViewModel(
            context           = context,
            gameRepository    = gameRepository,
            platformDao       = platformDao,
            memoryCardRepository = memoryCardRepository,
            collectionRepository = mockk(relaxed = true),
            profileRepository = profileRepository,
            autoCoreMemory    = autoCoreMemory,
            intentResolver    = intentResolver,
            artworkRepository = artworkRepository,
            artworkStore      = artworkStore,
            artworkRecordDao  = artworkRecordDao,
            menuSound         = menuSound,
            discordPresence   = mockk(relaxed = true),
            launcherShortcutRepository = mockk(relaxed = true),
            achievementRepository = achievementRepository,
            launchDispatcher  = launchDispatcher,
            pcGameExporter    = pcGameExporter,
            storefrontMatches = storefrontMatches,
        )
    }

    // ── Export Game (C18 task X.7) ────────────────────────────────────────

    private val windowsGame = Game(
        id              = 2L,
        title           = "Portal 2",
        platformId      = "windows",
        packageName     = "banner.hub",
        launchIntentUri = "intent:#Intent;action=banner.hub.LAUNCH_GAME;S.localGameId=local_1f2e;end",
    )

    @Test
    fun `Export Game is offered for a Windows game, and not for a ROM game or an Android game`() = runTest {
        coEvery { gameRepository.getById(2L) } returns windowsGame
        coEvery { gameRepository.getById(3L) } returns
            Game(id = 3L, title = "Alto's Odyssey", platformId = "android", packageName = "com.noodlecake.altosodyssey")
        coEvery { platformDao.getById("windows") } returns null
        coEvery { platformDao.getById("android") } returns null

        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(DetailAction.EXPORT in viewModel.uiState.value.actionsIn(DetailMenu.FILE))

        viewModel.loadGame(3L)
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(DetailAction.EXPORT in viewModel.uiState.value.actionsIn(DetailMenu.FILE))

        viewModel.loadGame(2L)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(DetailAction.EXPORT in viewModel.uiState.value.actionsIn(DetailMenu.FILE))
    }

    @Test
    fun `Export Game exports this game and shows what happened`() = runTest {
        coEvery { gameRepository.getById(2L) } returns windowsGame
        coEvery { platformDao.getById("windows") } returns null
        coEvery { pcGameExporter.exportGame(2L) } returns com.playfieldportal.feature.settings.pc.PcGameExportReport(
            written = 1, skipped = 0, failed = 0, message = "Exported Portal 2 to windows/import as Portal 2.pfpgame.",
        )
        viewModel.loadGame(2L)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.activateAction(DetailAction.EXPORT)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { pcGameExporter.exportGame(2L) }
        assertEquals("Exported Portal 2 to windows/import as Portal 2.pfpgame.", viewModel.uiState.value.actionMessage)
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    // A launch Intent as a mock, not a real android.content.Intent: the ViewModel logs
    // intent.toUri(...) on the success path, and that real Android method throws "not mocked" in a
    // plain JVM unit test. Stubbing toUri lets the launch flow run while keeping reference identity
    // (mockk uses identity equals) so assertEquals on the emitted intent still holds.
    private fun fakeLaunchIntent(): android.content.Intent = mockk(relaxed = true) {
        every { toUri(any()) } returns "intent://fake"
    }

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

    // ── media strip ───────────────────────────────────────────────────────

    @Test
    fun `loadGame shows every stored video and screenshot in the strip`() = runTest {
        coEvery { artworkStore.findAll(1L, com.playfieldportal.feature.artwork.store.ArtworkKind.VIDEO) } returns
            listOf("vid0", "vid1")
        coEvery { artworkStore.findAll(1L, com.playfieldportal.feature.artwork.store.ArtworkKind.SCREENSHOT) } returns
            listOf("shot0", "shot1", "shot2")
        coEvery { artworkStore.find(1L, com.playfieldportal.feature.artwork.store.ArtworkKind.TITLESCREEN, any()) } returns "title"
        coEvery { artworkStore.find(1L, com.playfieldportal.feature.artwork.store.ArtworkKind.ICON1, any()) } returns null

        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(
                listOf("vid0", "vid1", "shot0", "shot1", "shot2", "title"),
                state.detailMedia.map { it.uri },
            )
            assertEquals(listOf(true, true, false, false, false, false), state.detailMedia.map { it.isVideo })
            // The player still opens on the first video, not on a later one.
            assertEquals("vid0", state.videoUri)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `loadGame falls back to the icon snap when the game has no full video`() = runTest {
        coEvery { artworkStore.findAll(1L, com.playfieldportal.feature.artwork.store.ArtworkKind.VIDEO) } returns emptyList()
        coEvery { artworkStore.find(1L, com.playfieldportal.feature.artwork.store.ArtworkKind.ICON1, any()) } returns "snap"
        coEvery { artworkStore.findAll(1L, com.playfieldportal.feature.artwork.store.ArtworkKind.SCREENSHOT) } returns listOf("shot0")
        coEvery { artworkStore.find(1L, com.playfieldportal.feature.artwork.store.ArtworkKind.TITLESCREEN, any()) } returns null

        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(listOf("snap", "shot0"), state.detailMedia.map { it.uri })
            assertEquals(listOf(true, false), state.detailMedia.map { it.isVideo })
            assertEquals("snap", state.videoUri)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── multi-disc picker ────────────────────────────────────────────────

    @Test
    fun `loadGame exposes disc members and selects the primary by default`() = runTest {
        val setKey = "psx\u0001/roms/psx\u0001Final Fantasy VII"
        val primary = fakeGame.copy(
            id = 1L,
            title = "Final Fantasy VII",
            romPath = "/roms/psx/Final Fantasy VII.m3u",
            discSetKey = setKey,
            discNumber = null,
            isDiscPrimary = true,
        )
        val disc2 = fakeGame.copy(
            id = 2L,
            title = "Final Fantasy VII",
            romPath = "/roms/psx/Final Fantasy VII (Disc 2).cue",
            discSetKey = setKey,
            discNumber = 2,
            isDiscPrimary = false,
        )
        coEvery { gameRepository.getById(1L) } returns primary
        coEvery { gameRepository.getDiscSetMembers(setKey) } returns listOf(primary, disc2)

        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(listOf(2L, 1L), state.discMembers.map { it.id })
            assertEquals(1L, state.selectedDiscId)
            assertEquals(1L, state.selectedDisc?.id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `loadGame orders discs numerically while selecting requested disc`() = runTest {
        val setKey = "psx\u0001/roms/psx\u0001Final Fantasy VII"
        val primary = fakeGame.copy(id = 1L, discSetKey = setKey, isDiscPrimary = true, discNumber = 1)
        val disc2 = fakeGame.copy(id = 2L, discSetKey = setKey, discNumber = 2, isDiscPrimary = false)
        coEvery { gameRepository.getById(1L) } returns primary
        val disc10 = fakeGame.copy(id = 10L, discSetKey = setKey, discNumber = 10, isDiscPrimary = false)
        coEvery { gameRepository.getDiscSetMembers(setKey) } returns listOf(disc10, disc2, primary)

        viewModel.loadGame(1L, requestedDiscId = 2L)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(listOf(1L, 2L, 10L), state.discMembers.map { it.id })
            assertEquals(2L, state.selectedDiscId)
            assertEquals(2L, state.selectedDisc?.id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `loadGame with an unknown requested disc falls back to the primary`() = runTest {
        val setKey = "psx\u0001/roms/psx\u0001Final Fantasy VII"
        val primary = fakeGame.copy(id = 1L, discSetKey = setKey, isDiscPrimary = true, discNumber = 1)
        val disc2 = fakeGame.copy(id = 2L, discSetKey = setKey, discNumber = 2, isDiscPrimary = false)
        coEvery { gameRepository.getById(1L) } returns primary
        coEvery { gameRepository.getDiscSetMembers(setKey) } returns listOf(primary, disc2)

        viewModel.loadGame(1L, requestedDiscId = 999L)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(1L, state.selectedDiscId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `loadGame with a requested disc launches that member directly`() = runTest {
        val setKey = "psx\u0001/roms/psx\u0001Final Fantasy VII"
        val primary = fakeGame.copy(id = 1L, discSetKey = setKey, isDiscPrimary = true, discNumber = 1)
        val disc2 = fakeGame.copy(id = 2L, discSetKey = setKey, discNumber = 2, isDiscPrimary = false)
        val fakeProfile = com.playfieldportal.core.domain.model.EmulatorProfile(
            id = "duckstation",
            name = "DuckStation",
            packageName = "com.github.stenzek.duckstation",
            intentType = com.playfieldportal.core.domain.model.IntentType.ACTION_VIEW,
            supportedPlatformIds = listOf("psx"),
        )
        coEvery { gameRepository.getById(1L) } returns primary
        coEvery { gameRepository.getById(2L) } returns disc2
        coEvery { gameRepository.getDiscSetMembers(setKey) } returns listOf(primary, disc2)
        every { profileRepository.getInstalledProfiles() } returns listOf(fakeProfile)
        coEvery { intentResolver.resolve(any(), any()) } returns Result.success(fakeLaunchIntent())

        viewModel.loadGame(1L, requestedDiscId = 2L)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.launch()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { intentResolver.resolve(disc2, match { it.id == "duckstation" }) }
    }

    @Test
    fun `selecting a disc persists the preferred disc`() = runTest {
        val setKey = "psx\u0001/roms/psx\u0001Final Fantasy VII"
        val primary = fakeGame.copy(id = 1L, discSetKey = setKey, discNumber = 1, isDiscPrimary = true)
        val disc2 = fakeGame.copy(id = 2L, discSetKey = setKey, discNumber = 2, isDiscPrimary = false)
        coEvery { gameRepository.getById(1L) } returns primary
        coEvery { gameRepository.getDiscSetMembers(setKey) } returns listOf(primary, disc2)

        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.selectDisc(2L)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { gameRepository.setPreferredDisc(1L, 2L) }
        assertEquals(2L, viewModel.uiState.value.selectedDiscId)
    }

    @Test
    fun `selecting a non-primary disc launches that member`() = runTest {
        val setKey = "psx\u0001/roms/psx\u0001Final Fantasy VII"
        val primary = fakeGame.copy(id = 1L, discSetKey = setKey, isDiscPrimary = true, discNumber = 1)
        val disc2 = fakeGame.copy(id = 2L, discSetKey = setKey, discNumber = 2, isDiscPrimary = false)
        val fakeProfile = com.playfieldportal.core.domain.model.EmulatorProfile(
            id = "duckstation",
            name = "DuckStation",
            packageName = "com.github.stenzek.duckstation",
            intentType = com.playfieldportal.core.domain.model.IntentType.ACTION_VIEW,
            supportedPlatformIds = listOf("psx"),
        )
        coEvery { gameRepository.getById(1L) } returns primary
        coEvery { gameRepository.getById(2L) } returns disc2
        coEvery { gameRepository.getDiscSetMembers(setKey) } returns listOf(primary, disc2)
        every { profileRepository.getInstalledProfiles() } returns listOf(fakeProfile)
        coEvery { intentResolver.resolve(any(), any()) } returns Result.success(fakeLaunchIntent())

        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.selectDisc(2L)
        viewModel.launch()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { intentResolver.resolve(disc2, match { it.id == "duckstation" }) }
    }

    // ── resolved-launch attribution (B4) ─────────────────────────────────

    @Test
    fun `loadGame reports the catalog source when nothing is configured`() = runTest {
        val duckstation = com.playfieldportal.core.domain.model.EmulatorProfile(
            id                   = "duckstation",
            name                 = "DuckStation",
            packageName          = "com.github.stenzek.duckstation",
            intentType           = com.playfieldportal.core.domain.model.IntentType.ACTION_VIEW,
            supportedPlatformIds = listOf("ps1"),
        )
        every { profileRepository.getInstalledProfiles() } returns listOf(duckstation)

        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("duckstation", state.resolvedLaunch?.profile?.id)
        assertEquals(
            com.playfieldportal.feature.launcher.LaunchSource.CATALOG_DEFAULT,
            state.resolvedLaunch?.source,
        )
    }

    @Test
    fun `loadGame attributes a platform default when one is configured`() = runTest {
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
        coEvery { platformDao.getById("psx") } returns
            fakePlatform.copy(preferredEmulatorPackage = "duckstation")
        every { profileRepository.getInstalledProfiles() } returns listOf(retroarch, duckstation)

        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("duckstation", state.resolvedLaunch?.profile?.id)
        assertEquals(
            com.playfieldportal.feature.launcher.LaunchSource.PLATFORM_DEFAULT,
            state.resolvedLaunch?.source,
        )
    }

    @Test
    fun `confirming an emulator pick re-attributes the launch to a per-game override`() = runTest {
        val duckstation = com.playfieldportal.core.domain.model.EmulatorProfile(
            id                   = "duckstation",
            name                 = "DuckStation",
            packageName          = "com.github.stenzek.duckstation",
            intentType           = com.playfieldportal.core.domain.model.IntentType.ACTION_VIEW,
            supportedPlatformIds = listOf("ps1"),
        )
        every { profileRepository.getInstalledProfiles() } returns listOf(duckstation)

        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        // The DB now carries the override the pick just wrote.
        coEvery { gameRepository.getById(1L) } returns fakeGame.copy(emulatorPackage = "duckstation")
        viewModel.confirmEmulatorPick("duckstation")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("duckstation", state.resolvedLaunch?.profile?.id)
        assertEquals(
            com.playfieldportal.feature.launcher.LaunchSource.PER_GAME_OVERRIDE,
            state.resolvedLaunch?.source,
        )
        assertTrue(state.actionMessage!!.contains("Emulator set to DuckStation"))
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
        viewModel.saveNote("Great game!")
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
        viewModel.saveNote("   ")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { gameRepository.updateNote(1L, null) }
    }

    @Test
    fun `cancelNote clears isEditingNote without saving`() = runTest {
        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.startEditNote()
        viewModel.cancelNote()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { gameRepository.updateNote(any(), any()) }

        viewModel.uiState.test {
            assertFalse(awaitItem().isEditingNote)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── title editing ─────────────────────────────────────────────────────

    @Test
    fun `saveTitle writes the typed title as the override and closes the modal`() = runTest {
        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.startEditTitle()
        viewModel.saveTitle("Crash 1")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { gameRepository.updateUserTitleOverride(1L, "Crash 1") }
        assertFalse(viewModel.uiState.value.isEditingTitle)
    }

    @Test
    fun `saveTitle with a blank title clears the override`() = runTest {
        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.startEditTitle()
        viewModel.saveTitle("   ")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { gameRepository.updateUserTitleOverride(1L, null) }
        assertFalse(viewModel.uiState.value.isEditingTitle)
    }

    @Test
    fun `cancelTitleEdit closes the modal without saving`() = runTest {
        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.startEditTitle()
        viewModel.cancelTitleEdit()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { gameRepository.updateUserTitleOverride(any(), any()) }
        assertFalse(viewModel.uiState.value.isEditingTitle)
    }

    @Test
    fun `the shared modals voice save and cancel, so the view model does not play them again`() = runTest {
        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.startEditTitle()
        viewModel.cancelTitleEdit()
        viewModel.startEditTitle()
        viewModel.saveTitle("Crash 1")
        viewModel.startEditNote()
        viewModel.cancelNote()
        viewModel.startEditNote()
        viewModel.saveNote("Great game!")
        testDispatcher.scheduler.advanceUntilIdle()

        verify(exactly = 0) { menuSound.play(com.playfieldportal.core.ui.sound.MenuSound.BACK, any()) }
        verify(exactly = 0) { menuSound.play(com.playfieldportal.core.ui.sound.MenuSound.CONFIRM, any()) }
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
        val fakeIntent = fakeLaunchIntent()
        every { profileRepository.getInstalledProfiles() }        returns listOf(fakeProfile)
        coEvery { profileRepository.getProfilesForPlatform("psx") } returns listOf(fakeProfile)
        coEvery { intentResolver.resolve(any(), any()) }            returns Result.success(fakeIntent)

        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.launch()
        testDispatcher.scheduler.advanceUntilIdle()

        // The resolved intent goes through the shared LaunchDispatcher (B1), which performs
        // startActivity and records the outcome.
        coVerify(exactly = 1) { launchDispatcher.launch(any(), any(), fakeIntent) }
    }

    @Test
    fun `launch resolves to the console's remembered retroarch core`() = runTest {
        // Two RetroArch cores cover the same console; the console remembers gambatte, so the
        // automatic pick must be gambatte even though mgba sorts first in the detected pool.
        val mgba = com.playfieldportal.core.domain.model.EmulatorProfile(
            id = "auto_retroarch_mgba_libretro_android",
            name = "RetroArch · mGBA (GBA)",
            packageName = "com.retroarch",
            intentType = com.playfieldportal.core.domain.model.IntentType.COMPONENT,
            supportedPlatformIds = listOf("gb", "gbc", "gba"),
            autoSource = "retroarch-core",
            coreMap = mapOf("gb" to "/data/data/com.retroarch/cores/mgba_libretro_android.so"),
        )
        val gambatte = com.playfieldportal.core.domain.model.EmulatorProfile(
            id = "auto_retroarch_gambatte_libretro_android",
            name = "RetroArch · Gambatte (GB/GBC)",
            packageName = "com.retroarch",
            intentType = com.playfieldportal.core.domain.model.IntentType.COMPONENT,
            supportedPlatformIds = listOf("gb", "gbc"),
            autoSource = "retroarch-core",
            coreMap = mapOf("gb" to "/data/data/com.retroarch/cores/gambatte_libretro_android.so"),
        )
        val gbGame = fakeGame.copy(platformId = "gb", romPath = "/roms/gb/tetris.gb")
        val fakeIntent = fakeLaunchIntent()
        coEvery { gameRepository.getById(1L) } returns gbGame
        every { profileRepository.getInstalledProfiles() } returns listOf(mgba, gambatte)
        coEvery { autoCoreMemory.rememberedProfileId("gb") } returns gambatte.id
        coEvery { intentResolver.resolve(any(), any()) } returns Result.success(fakeIntent)

        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.launch()
        testDispatcher.scheduler.advanceUntilIdle()

        // The remembered core wins the automatic pick, and the resolver handed that profile's
        // intent to the shared launch funnel.
        coVerify(exactly = 1) {
            launchDispatcher.launch(
                any(),
                match { it.profile.id == gambatte.id },
                fakeIntent,
            )
        }
    }

    // The guard has to hold even when launching would otherwise fully succeed — otherwise the test
    // passes for the wrong reason (no emulator installed) and the real regression slips through.
    @Test
    fun `launch refuses a missing game even when an emulator is available`() = runTest {
        val missingGame = fakeGame.copy(isMissing = true)
        val fakeProfile = com.playfieldportal.core.domain.model.EmulatorProfile(
            id                   = "ppsspp",
            name                 = "PPSSPP",
            packageName          = "org.ppsspp.ppsspp",
            intentType           = com.playfieldportal.core.domain.model.IntentType.ACTION_VIEW,
            supportedPlatformIds = listOf("psx"),
        )
        coEvery { gameRepository.getById(1L) }                    returns missingGame
        every { profileRepository.getInstalledProfiles() }        returns listOf(fakeProfile)
        coEvery { profileRepository.getProfilesForPlatform("psx") } returns listOf(fakeProfile)
        coEvery { intentResolver.resolve(any(), any()) }            returns Result.success(fakeLaunchIntent())

        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.launch()
        testDispatcher.scheduler.advanceUntilIdle()
        // Refused before the launch funnel: the resolver must never be asked for an intent.
        coVerify(exactly = 0) { intentResolver.resolve(any(), any()) }
        coVerify(exactly = 0) { launchDispatcher.launch(any(), any(), any()) }

        viewModel.uiState.test {
            val state = awaitItem()
            assertNotNull(state.launchError)
            assertNull(state.actionMessage)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── launch sound: a game boot is never scored by the menu launch chime ────────────

    // GameBoot off means a silent launch — no animation, no sound. The menu launch chime is the
    // same bundled sfx_launch sample GameBoot's sequence is timed to, so letting it through when
    // the toggle is off made "off" sound exactly like "on". The ViewModel owns the decision for
    // manual Play, so the chime must never fire here — the select sound stands in.
    @Test
    fun `launch never plays the menu launch chime even with GameBoot off`() = runTest {
        val fakeProfile = com.playfieldportal.core.domain.model.EmulatorProfile(
            id                   = "ppsspp",
            name                 = "PPSSPP",
            packageName          = "org.ppsspp.ppsspp",
            intentType           = com.playfieldportal.core.domain.model.IntentType.ACTION_VIEW,
            supportedPlatformIds = listOf("psx"),
        )
        every { profileRepository.getInstalledProfiles() }         returns listOf(fakeProfile)
        coEvery { profileRepository.getProfilesForPlatform("psx") } returns listOf(fakeProfile)
        coEvery { intentResolver.resolve(any(), any()) }            returns Result.success(fakeLaunchIntent())

        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.launch(playSound = true)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { launchDispatcher.launch(any(), any(), any()) }
        verify(exactly = 1) { menuSound.play(com.playfieldportal.core.ui.sound.MenuSound.SELECT, any()) }
    }

    // The auto-fire path hands sound responsibility to the XMB confirm entirely, so Game Detail
    // itself must stay silent on it.
    @Test
    fun `direct-launch auto-fire plays no menu sound`() = runTest {
        val fakeProfile = com.playfieldportal.core.domain.model.EmulatorProfile(
            id                   = "ppsspp",
            name                 = "PPSSPP",
            packageName          = "org.ppsspp.ppsspp",
            intentType           = com.playfieldportal.core.domain.model.IntentType.ACTION_VIEW,
            supportedPlatformIds = listOf("psx"),
        )
        every { profileRepository.getInstalledProfiles() }         returns listOf(fakeProfile)
        coEvery { profileRepository.getProfilesForPlatform("psx") } returns listOf(fakeProfile)
        coEvery { intentResolver.resolve(any(), any()) }            returns Result.success(fakeLaunchIntent())

        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.launch(playSound = false)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { launchDispatcher.launch(any(), any(), any()) }
        verify(exactly = 0) { menuSound.play(any(), any()) }
    }

    @Test
    fun `launch succeeds once a previously missing game is seen again`() = runTest {
        val fakeProfile = com.playfieldportal.core.domain.model.EmulatorProfile(
            id                   = "ppsspp",
            name                 = "PPSSPP",
            packageName          = "org.ppsspp.ppsspp",
            intentType           = com.playfieldportal.core.domain.model.IntentType.ACTION_VIEW,
            supportedPlatformIds = listOf("psx"),
        )
        val fakeIntent = fakeLaunchIntent()
        // isMissing back to false is exactly what markSeen does when the file reappears.
        coEvery { gameRepository.getById(1L) }                    returns fakeGame.copy(isMissing = false)
        every { profileRepository.getInstalledProfiles() }        returns listOf(fakeProfile)
        coEvery { profileRepository.getProfilesForPlatform("psx") } returns listOf(fakeProfile)
        coEvery { intentResolver.resolve(any(), any()) }            returns Result.success(fakeIntent)

        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.launch()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { launchDispatcher.launch(any(), any(), fakeIntent) }
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
        val fakeIntent = fakeLaunchIntent()
        coEvery { gameRepository.getById(1L) } returns overrideGame
        coEvery { platformDao.getById("psx") } returns platformDefault
        every { profileRepository.getInstalledProfiles() } returns listOf(retroarch, duckstation)
        coEvery { profileRepository.getProfilesForPlatform("psx") } returns listOf(retroarch, duckstation)
        coEvery { intentResolver.resolve(any(), any()) } returns Result.success(fakeIntent)

        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.launch()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { intentResolver.resolve(overrideGame, match { it.id == "duckstation" }) }
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
        val fakeIntent = fakeLaunchIntent()
        coEvery { platformDao.getById("psx") } returns platformDefault
        coEvery { memoryCardRepository.getById("psx") } returns memoryCard
        every { profileRepository.getInstalledProfiles() } returns listOf(retroarch, duckstation)
        coEvery { profileRepository.getProfilesForPlatform("psx") } returns listOf(retroarch, duckstation)
        coEvery { intentResolver.resolve(any(), any()) } returns Result.success(fakeIntent)

        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.launch()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { intentResolver.resolve(fakeGame, match { it.id == "duckstation" }) }
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
        val fakeIntent = fakeLaunchIntent()
        coEvery { platformDao.getById("psx") } returns platformDefault
        every { profileRepository.getInstalledProfiles() } returns listOf(retroarch, duckstation)
        coEvery { profileRepository.getProfilesForPlatform("psx") } returns listOf(retroarch, duckstation)
        coEvery { intentResolver.resolve(any(), any()) } returns Result.success(fakeIntent)

        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.launch()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { intentResolver.resolve(fakeGame, match { it.id == "duckstation" }) }
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
        coEvery { profileRepository.getProfilesForPlatform("psx") } returns listOf(fakeProfile)
        coEvery { intentResolver.resolve(any(), any()) }            returns Result.failure(
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
        coEvery { profileRepository.getProfilesForPlatform("psx") } returns listOf(fakeProfile)
        coEvery { intentResolver.resolve(any(), any()) }            returns Result.failure(
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
        coEvery { profileRepository.getProfilesForPlatform("psx") } returns listOf(fakeProfile)
        coEvery { intentResolver.resolve(any(), any()) }            returns Result.failure(
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

    // ── Launch recovery sheet (B1) ───────────────────────────────────────

    @Test
    fun `requestLaunchHelp raises the recovery sheet through the dispatcher`() = runTest {
        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onLaunchFailed("Emulator not found. Is it installed?")

        viewModel.requestLaunchHelp()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) {
            launchDispatcher.requestRecovery(
                match { it.id == 1L },
                any(),
                eq("Emulator not found. Is it installed?"),
            )
        }
    }

    @Test
    fun `requestLaunchHelp is a no-op when there is no launch error`() = runTest {
        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.requestLaunchHelp()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { launchDispatcher.requestRecovery(any(), any(), any()) }
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

    @Test
    fun `fetchArtwork runs the shared single-game refetch and never wipes the library`() = runTest {
        coEvery { artworkRepository.refetchArtworkForGame(1L, any()) } returns
            ArtworkFetchResult(gameId = 1L, title = "Crash Bandicoot", success = true)

        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.fetchArtwork()
        testDispatcher.scheduler.advanceUntilIdle()

        // Scoped eviction lives in refetchArtworkForGame now (ArtworkRepositoryRefetchTest).
        // clearCache() is the library-wide destructive reset; calling it here was the bug that
        // wiped all artwork on refresh.
        coVerify(exactly = 1) { artworkRepository.refetchArtworkForGame(1L, any()) }
        coVerify(exactly = 0) { artworkRepository.clearCache() }

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isFetchingArtwork)
            assertEquals("Artwork updated", state.artworkMessage)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `fetchArtwork re-reads the game so the new art shows`() = runTest {
        val refreshed = fakeGame.copy(artworkUri = "file:///art/crash_box.png")
        coEvery { gameRepository.getById(1L) } returnsMany listOf(fakeGame, refreshed)
        coEvery { artworkRepository.refetchArtworkForGame(1L, any()) } returns
            ArtworkFetchResult(1L, "Crash Bandicoot", success = true)

        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.fetchArtwork()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("file:///art/crash_box.png", viewModel.uiState.value.game?.artworkUri)
    }

    @Test
    fun `a write from elsewhere reaches the open page`() = runTest {
        val row = kotlinx.coroutines.flow.MutableStateFlow<Game?>(fakeGame)
        every { gameRepository.observeById(1L) } returns row

        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        row.value = fakeGame.copy(scrapedTitle = "Crash Bandicoot", artworkUri = "file:///art/crash_box.png")
        testDispatcher.scheduler.advanceUntilIdle()

        val game = viewModel.uiState.value.game
        assertEquals("Crash Bandicoot", game?.displayTitle)
        assertEquals("file:///art/crash_box.png", game?.artworkUri)
        assertEquals("Crash Bandicoot", viewModel.uiState.value.discMembers.single().displayTitle)
    }

    @Test
    fun `the previous game's row never lands on the next game's page`() = runTest {
        val first = kotlinx.coroutines.flow.MutableStateFlow<Game?>(fakeGame)
        every { gameRepository.observeById(1L) } returns first
        every { gameRepository.observeById(2L) } returns kotlinx.coroutines.flow.MutableStateFlow(windowsGame)
        coEvery { gameRepository.getById(2L) } returns windowsGame

        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.loadGame(2L)
        testDispatcher.scheduler.advanceUntilIdle()
        first.value = fakeGame.copy(scrapedTitle = "Crash Bandicoot")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(windowsGame, viewModel.uiState.value.game)
    }

    @Test
    fun `fetchArtwork shows the scraper's message when nothing is found`() = runTest {
        coEvery { artworkRepository.refetchArtworkForGame(1L, any()) } returns
            ArtworkFetchResult(1L, "Crash Bandicoot", success = false, errorMessage = "Not found on any source")

        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.fetchArtwork()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Not found on any source", viewModel.uiState.value.artworkMessage)
    }

    @Test
    fun `fetchArtwork says so when the XMB menu is already fetching this game`() = runTest {
        coEvery { artworkRepository.refetchArtworkForGame(1L, any()) } returns
            ArtworkFetchResult(1L, "Crash Bandicoot", success = false, alreadyRunning = true)

        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.fetchArtwork()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isFetchingArtwork)
        assertEquals("Already fetching artwork for this game", state.artworkMessage)
    }

    @Test
    fun `the Fetch Artwork option runs the fetch`() = runTest {
        coEvery { artworkRepository.refetchArtworkForGame(1L, any()) } returns
            ArtworkFetchResult(1L, "Crash Bandicoot", success = true)

        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.activateAction(DetailAction.FETCH_ARTWORK)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { artworkRepository.refetchArtworkForGame(1L, any()) }
    }

    @Test
    fun `the Fetch Artwork option is labelled for what it does`() {
        assertEquals("Fetch Artwork", DetailAction.FETCH_ARTWORK.label)
        assertEquals("Fetch Artwork", DetailAction.FETCH_ARTWORK.dynamicLabel(refreshing = false))
        assertEquals("Fetching Artwork...", DetailAction.FETCH_ARTWORK.dynamicLabel(refreshing = true))
    }

    @Test
    fun `dismissArtworkMessage clears artworkMessage`() = runTest {
        coEvery { artworkRepository.refetchArtworkForGame(1L, any()) } returns
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

    // ── Metadata presets — Current vs Incoming (C16 task 3.2) ─────────────

    private val metadataCurrent = mapOf<MetadataField, Any?>(
        MetadataField.DESCRIPTION to "A classic platformer.",
        MetadataField.DEVELOPER to null,
    )
    private val ssPreset = MetadataPreset(
        provider = MatchProvider.SCREENSCRAPER,
        description = "Bandicoot jumps.",
        developer = "Naughty Dog",
    )
    private val tgdbPreset = MetadataPreset(provider = MatchProvider.THEGAMESDB, description = "TGDB text")

    private fun openLoadedPreview(
        presets: List<MetadataPreset> = listOf(ssPreset, tgdbPreset),
        effective: Map<MetadataField, Any?> = metadataCurrent,
        overridden: Set<MetadataField> = emptySet(),
    ) {
        coEvery { artworkRepository.fetchMetadataPreview(1L) } returns
            MetadataPreview(metadataCurrent, effective, overridden, presets)
        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        // The overlay is opened from the page's own graph, so the page has to have reported
        // readiness first — the same order the screen runs in.
        viewModel.onPageLaidOut()
        viewModel.activateAction(DetailAction.METADATA)
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `Update Metadata previews Current vs Incoming and writes nothing until applied`() = runTest {
        openLoadedPreview()

        val preview = viewModel.uiState.value.metadataPreview!!
        assertFalse(preview.loading)
        assertEquals(MatchProvider.SCREENSCRAPER, preview.preset?.provider)
        // Non-destructive by default: only the empty Developer would be written.
        assertEquals(MetadataApplyPolicy.FILL_MISSING_ONLY, preview.policy)
        assertEquals(setOf(MetadataField.DEVELOPER), preview.willWrite)
        assertEquals(preview.applyIndex, preview.focus)
        coVerify(exactly = 0) { artworkRepository.applyMetadata(any(), any(), any(), any()) }
    }

    @Test
    fun `Back closes the metadata preview, not the page, and writes nothing`() = runTest {
        openLoadedPreview()

        viewModel.handleGamepadAction(GamepadAction.BACK)

        assertNull(viewModel.uiState.value.metadataPreview)
        assertFalse(viewModel.uiState.value.closed)
        coVerify(exactly = 0) { artworkRepository.applyMetadata(any(), any(), any(), any()) }
    }

    @Test
    fun `a metadata preview closed while loading is not reopened by the late answer`() = runTest {
        coEvery { artworkRepository.fetchMetadataPreview(1L) } returns
            MetadataPreview(metadataCurrent, metadataCurrent, emptySet(), listOf(ssPreset))
        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.openMetadataPreview()
        viewModel.closeMetadataPreview()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.metadataPreview)
    }

    @Test
    fun `no provider metadata still offers the Manual column rather than a dead end`() = runTest {
        openLoadedPreview(presets = emptyList())

        // A game no scraper recognised is exactly the one worth typing by hand, so the overlay
        // opens straight onto the Manual column instead of an apology.
        val preview = viewModel.uiState.value.metadataPreview!!
        assertFalse(preview.loading)
        assertFalse(preview.nothingFound)
        assertTrue(preview.noProviderFound)
        assertTrue(preview.isManual)
        assertFalse(preview.failed)
        // Every field has a row, including the empty ones — the empty one is the point.
        assertEquals(MetadataField.entries.toList(), preview.shownFields)
        assertNull(viewModel.uiState.value.actionMessage)

        // Nothing typed yet, so Apply has nothing to write and never reaches the writer.
        viewModel.handleGamepadAction(GamepadAction.SELECT)
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.metadataPreview)
        assertFalse(viewModel.uiState.value.closed)
        coVerify(exactly = 0) { artworkRepository.applyMetadata(any(), any(), any(), any()) }
    }

    @Test
    fun `a hand-typed field is applied as a MANUAL preset and nothing else is`() = runTest {
        openLoadedPreview(presets = emptyList())
        coEvery { artworkRepository.applyMetadata(any(), any(), any(), any()) } returns
            setOf(MetadataField.DEVELOPER)

        viewModel.startEditMetadataField(MetadataField.DEVELOPER)
        viewModel.onMetadataEditChanged("Naughty Dog")
        viewModel.saveMetadataEdit()

        val typed = viewModel.uiState.value.metadataPreview!!
        assertNull(typed.editingField)
        assertEquals(setOf(MetadataField.DEVELOPER), typed.willWrite)

        viewModel.applyMetadataPreview()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify {
            artworkRepository.applyMetadata(
                1L,
                // Exactly what was typed, and only that: the untouched fields were seeded from the
                // values already shown, so they cannot register as changes.
                match<MetadataPreset> {
                    it.provider == MatchProvider.MANUAL &&
                        it.developer == "Naughty Dog" &&
                        it.description == "A classic platformer."
                },
                any(), any(),
            )
        }
        assertEquals("Updated 1 field set by hand", viewModel.uiState.value.actionMessage)
    }

    @Test
    fun `reverting a field drops the override and reveals the scraped value`() = runTest {
        // Developer is hand-set to something other than what the column holds.
        openLoadedPreview(
            presets = emptyList(),
            effective = metadataCurrent + (MetadataField.DEVELOPER to "My own studio"),
            overridden = setOf(MetadataField.DEVELOPER),
        )
        coEvery { artworkRepository.clearMetadataOverride(1L, MetadataField.DEVELOPER) } returns true

        viewModel.revertMetadataField(MetadataField.DEVELOPER)
        testDispatcher.scheduler.advanceUntilIdle()

        val preview = viewModel.uiState.value.metadataPreview!!
        // metadataCurrent has a null Developer — the scraped value, revealed again.
        assertNull(preview.effective[MetadataField.DEVELOPER])
        assertFalse(MetadataField.DEVELOPER in preview.overridden)
        assertEquals("", preview.manualText[MetadataField.DEVELOPER])
        assertEquals("Reverted Developer to the scraped value", viewModel.uiState.value.actionMessage)
        coVerify { artworkRepository.clearMetadataOverride(1L, MetadataField.DEVELOPER) }
    }

    @Test
    fun `a failed metadata retrieval stays open and is told apart from nothing found`() = runTest {
        coEvery { artworkRepository.fetchMetadataPreview(1L) } throws java.io.IOException("offline")
        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.activateAction(DetailAction.METADATA)
        testDispatcher.scheduler.advanceUntilIdle()

        val preview = viewModel.uiState.value.metadataPreview!!
        assertFalse(preview.loading)
        assertTrue(preview.nothingFound)
        assertTrue(preview.failed)

        viewModel.handleGamepadAction(GamepadAction.BACK)

        assertNull(viewModel.uiState.value.metadataPreview)
        assertFalse(viewModel.uiState.value.closed)
    }

    @Test
    fun `an untouched Manual column closes without a write`() = runTest {
        openLoadedPreview(presets = emptyList())

        viewModel.applyMetadataPreview()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.metadataPreview)
        coVerify(exactly = 0) { artworkRepository.applyMetadata(any(), any(), any(), any()) }
    }

    @Test
    fun `Apply writes under the selected policy and reloads the game`() = runTest {
        openLoadedPreview()
        coEvery { artworkRepository.applyMetadata(any(), any(), any(), any()) } returns
            setOf(MetadataField.DESCRIPTION, MetadataField.DEVELOPER)

        viewModel.handleGamepadAction(GamepadAction.NAVIGATE_LEFT)   // Fill Missing Only → Replace All
        assertEquals(MetadataApplyPolicy.REPLACE_ALL, viewModel.uiState.value.metadataPreview?.policy)
        viewModel.handleGamepadAction(GamepadAction.SELECT)          // focus starts on Apply
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify {
            artworkRepository.applyMetadata(
                1L, ssPreset, MetadataApplyPolicy.REPLACE_ALL,
                setOf(MetadataField.DESCRIPTION, MetadataField.DEVELOPER),
            )
        }
        coVerify(atLeast = 2) { gameRepository.getById(1L) }
        assertNull(viewModel.uiState.value.metadataPreview)
        assertEquals("Updated 2 fields from ScreenScraper", viewModel.uiState.value.actionMessage)
    }

    @Test
    fun `Keep Current closes without calling the writer`() = runTest {
        openLoadedPreview()

        viewModel.selectMetadataPolicy(MetadataApplyPolicy.KEEP_CURRENT)
        viewModel.applyMetadataPreview()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.metadataPreview)
        assertEquals("Kept current metadata", viewModel.uiState.value.actionMessage)
        coVerify(exactly = 0) { artworkRepository.applyMetadata(any(), any(), any(), any()) }
    }

    @Test
    fun `the metadata overlay takes controller input once its rows arrive`() = runTest {
        openLoadedPreview()

        // Retrieval is asynchronous: the overlay opens before it has any row, so the cursor can
        // only be anywhere once the rows have been handed to the engine.
        assertTrue(GameDetailKeys.METADATA_APPLY in viewModel.focusableNodeKeys())
        assertEquals(GameDetailKeys.METADATA_APPLY, viewModel.uiState.value.navFocusKey)

        viewModel.handleGamepadAction(GamepadAction.NAVIGATE_UP)

        assertEquals(
            GameDetailKeys.metadataField(MetadataField.DEVELOPER.name),
            viewModel.uiState.value.navFocusKey,
        )
    }

    @Test
    fun `switching source re-ticks its changes and toggling a row chooses fields`() = runTest {
        openLoadedPreview()

        viewModel.handleGamepadAction(GamepadAction.NEXT_CATEGORY)   // ScreenScraper → TheGamesDB
        var preview = viewModel.uiState.value.metadataPreview!!
        assertEquals(MatchProvider.THEGAMESDB, preview.preset?.provider)
        assertEquals(setOf(MetadataField.DESCRIPTION), preview.chosen)
        assertEquals(preview.applyIndex, preview.focus)

        viewModel.handleGamepadAction(GamepadAction.NAVIGATE_UP)     // the Description row
        viewModel.handleGamepadAction(GamepadAction.SELECT)          // untick it

        preview = viewModel.uiState.value.metadataPreview!!
        assertEquals(MetadataApplyPolicy.CHOOSE_FIELDS, preview.policy)
        assertTrue(preview.chosen.isEmpty())
        assertTrue(preview.willWrite.isEmpty())
    }

    // ── Navigation (unified engine) ───────────────────────────────────────

    /** Load the game and report the page's first laid-out graph, ready for controller input. */
    private fun loadedAndLaidOut() {
        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onPageLaidOut()
    }

    @Test
    fun `input before the game loads is dropped and never replayed`() = runTest {
        // Nothing is loaded yet: the page has no graph, so the engine ignores the press outright.
        viewModel.handleGamepadAction(GamepadAction.NAVIGATE_DOWN)
        assertNull(viewModel.uiState.value.navFocusKey)

        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()

        // The dropped press did not accumulate: the cursor starts on Launch, where the design says
        // it starts, and the next press is the one that moves it.
        assertEquals(GameDetailKeys.LAUNCH, viewModel.uiState.value.navFocusKey)
        viewModel.handleGamepadAction(GamepadAction.NAVIGATE_DOWN)
        assertEquals(GameDetailKeys.FAVORITE, viewModel.uiState.value.navFocusKey)
    }

    @Test
    fun `Options owns every input while open and hands the page its cursor back`() = runTest {
        loadedAndLaidOut()
        viewModel.handleGamepadAction(GamepadAction.NAVIGATE_DOWN)
        assertEquals(GameDetailKeys.FAVORITE, viewModel.uiState.value.navFocusKey)

        viewModel.handleGamepadAction(GamepadAction.OPEN_CONTEXT_MENU)
        assertTrue(viewModel.uiState.value.showOptions)
        assertEquals(
            GameDetailKeys.option(DetailAction.FAVORITE.name),
            viewModel.uiState.value.navFocusKey,
        )

        viewModel.handleGamepadAction(GamepadAction.NAVIGATE_DOWN)
        assertEquals(
            GameDetailKeys.option(DetailAction.COLLECTIONS.name),
            viewModel.uiState.value.navFocusKey,
        )

        // Back closes the overlay before it can close the page.…
        viewModel.handleGamepadAction(GamepadAction.BACK)
        assertFalse(viewModel.uiState.value.showOptions)
        assertFalse(viewModel.uiState.value.closed)
        // …and the page is back on the exact node it was interrupted on.
        assertEquals(GameDetailKeys.FAVORITE, viewModel.uiState.value.navFocusKey)

        // A second Back, with nothing open, leaves Game Detail.
        viewModel.handleGamepadAction(GamepadAction.BACK)
        assertTrue(viewModel.uiState.value.closed)
    }

    /** Opens Options and walks the cursor down to [action] on the top level. */
    private fun openOptionsOn(action: DetailAction) {
        viewModel.handleGamepadAction(GamepadAction.OPEN_CONTEXT_MENU)
        repeat(viewModel.uiState.value.visibleActions.indexOf(action)) {
            viewModel.handleGamepadAction(GamepadAction.NAVIGATE_DOWN)
        }
        assertEquals(GameDetailKeys.option(action.name), viewModel.uiState.value.navFocusKey)
    }

    @Test
    fun `a sub-panel opens on its first row and Back returns to the row that opened it`() = runTest {
        loadedAndLaidOut()
        openOptionsOn(DetailAction.MENU_INFORMATION)

        viewModel.handleGamepadAction(GamepadAction.SELECT)

        // Still the Options overlay, one level down.
        assertTrue(viewModel.uiState.value.showOptions)
        assertEquals(DetailMenu.INFORMATION, viewModel.uiState.value.optionsMenu)
        assertEquals(GameDetailKeys.option(DetailAction.METADATA.name), viewModel.uiState.value.navFocusKey)
        assertEquals(0, viewModel.uiState.value.optionsIndex)

        viewModel.handleGamepadAction(GamepadAction.NAVIGATE_DOWN)
        assertEquals(GameDetailKeys.option(DetailAction.RENAME.name), viewModel.uiState.value.navFocusKey)

        // Back steps out one level, not out of the menu…
        viewModel.handleGamepadAction(GamepadAction.BACK)
        assertTrue(viewModel.uiState.value.showOptions)
        assertEquals(DetailMenu.ROOT, viewModel.uiState.value.optionsMenu)
        assertEquals(
            GameDetailKeys.option(DetailAction.MENU_INFORMATION.name),
            viewModel.uiState.value.navFocusKey,
        )

        // …and a second Back closes it, leaving the page open.
        viewModel.handleGamepadAction(GamepadAction.BACK)
        assertFalse(viewModel.uiState.value.showOptions)
        assertFalse(viewModel.uiState.value.closed)
    }

    @Test
    fun `an action inside a sub-panel runs and closes the whole menu`() = runTest {
        loadedAndLaidOut()
        openOptionsOn(DetailAction.MENU_INFORMATION)
        viewModel.handleGamepadAction(GamepadAction.SELECT)
        viewModel.handleGamepadAction(GamepadAction.NAVIGATE_DOWN)   // Edit Title

        viewModel.handleGamepadAction(GamepadAction.SELECT)

        assertTrue(viewModel.uiState.value.isEditingTitle)
        assertFalse(viewModel.uiState.value.showOptions)
    }

    @Test
    fun `Options always reopens on the top level`() = runTest {
        loadedAndLaidOut()
        openOptionsOn(DetailAction.MENU_FILE)
        viewModel.handleGamepadAction(GamepadAction.SELECT)
        assertEquals(DetailMenu.FILE, viewModel.uiState.value.optionsMenu)

        // A tap outside closes the whole menu from wherever it was.
        viewModel.closeOptions()
        assertFalse(viewModel.uiState.value.showOptions)

        viewModel.handleGamepadAction(GamepadAction.OPEN_CONTEXT_MENU)
        assertEquals(DetailMenu.ROOT, viewModel.uiState.value.optionsMenu)
        assertEquals(GameDetailKeys.option(DetailAction.FAVORITE.name), viewModel.uiState.value.navFocusKey)
    }

    // ── Results that arrive late ──────────────────────────────────────────
    //
    // This ViewModel outlives a page: the same instance shows game after game. Anything that
    // suspends therefore comes back to a page that may have moved on, and must not write what it
    // captured before it left.

    private fun loadSecondGame() {
        coEvery { gameRepository.getById(2L) } returns windowsGame
        coEvery { platformDao.getById("windows") } returns null
        viewModel.loadGame(2L)
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `a fetch that finishes after the page moved to another game leaves that page alone`() = runTest {
        val fetch = kotlinx.coroutines.CompletableDeferred<ArtworkFetchResult>()
        coEvery { artworkRepository.refetchArtworkForGame(1L, any()) } coAnswers { fetch.await() }
        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.fetchArtwork()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isFetchingArtwork)

        loadSecondGame()
        // The other game's fetch is not this page's, and must not lock its own Fetch Artwork.
        assertFalse(viewModel.uiState.value.isFetchingArtwork)

        fetch.complete(ArtworkFetchResult(1L, "Crash Bandicoot", success = true))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2L, viewModel.uiState.value.game?.id)
        assertNull(viewModel.uiState.value.artworkMessage)
    }

    @Test
    fun `a fetch that finishes on its own page still reports and unlocks`() = runTest {
        coEvery { artworkRepository.refetchArtworkForGame(1L, any()) } returns
            ArtworkFetchResult(1L, "Crash Bandicoot", success = true)
        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.fetchArtwork()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isFetchingArtwork)
        assertEquals("Artwork updated", viewModel.uiState.value.artworkMessage)
    }

    @Test
    fun `a favorite that saves slowly does not undo what changed while it was saving`() = runTest {
        val saving = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { gameRepository.setFavorite(1L, true) } coAnswers { saving.await() }
        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.toggleFavorite()
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.saveNote("Hidden gem")
        testDispatcher.scheduler.advanceUntilIdle()
        saving.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()

        val game = viewModel.uiState.value.game!!
        assertTrue(game.isFavorite)
        assertEquals("Hidden gem", game.userNote)
    }

    @Test
    fun `a note that saves after the page moved on is not written onto the other game`() = runTest {
        val saving = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { gameRepository.updateNote(1L, any()) } coAnswers { saving.await() }
        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.saveNote("Hidden gem")
        testDispatcher.scheduler.advanceUntilIdle()
        loadSecondGame()
        saving.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2L, viewModel.uiState.value.game?.id)
        assertEquals("Portal 2", viewModel.uiState.value.game?.title)
        assertNull(viewModel.uiState.value.game?.userNote)
    }

    @Test
    fun `the page shows the game asked for last, however the loads finish`() = runTest {
        val slow = kotlinx.coroutines.CompletableDeferred<Game?>()
        coEvery { gameRepository.getById(1L) } coAnswers { slow.await() }
        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()

        loadSecondGame()
        slow.complete(fakeGame)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2L, viewModel.uiState.value.game?.id)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `coins from a game the page has left never replace the current game's`() = runTest {
        val firstGameCoins = kotlinx.coroutines.flow.MutableSharedFlow<com.playfieldportal.core.domain.achievement.GameCoins?>()
        val secondCoins = mockk<com.playfieldportal.core.domain.achievement.GameCoins>()
        every { achievementRepository.observeGameCoins(1L) } returns firstGameCoins
        every { achievementRepository.observeGameCoins(2L) } returns kotlinx.coroutines.flow.flowOf(secondCoins)
        viewModel.loadGame(1L)
        testDispatcher.scheduler.advanceUntilIdle()
        loadSecondGame()
        assertEquals(secondCoins, viewModel.uiState.value.coins)

        // A sync touches the tables, and the first game's stream — still open — emits again.
        firstGameCoins.emit(mockk())
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(secondCoins, viewModel.uiState.value.coins)
    }

    // ── Store Match row value ─────────────────────────────────────────────

    private fun linkedOn(vararg stores: com.playfieldportal.feature.artwork.match.Storefront) =
        stores.map {
            com.playfieldportal.feature.artwork.match.StorefrontMatchRepository.LinkedIdentity(
                com.playfieldportal.feature.artwork.match.StorefrontIdentityRecord(it, "1"),
                it.label,
            )
        }

    @Test
    fun `opening Options reads which stores a Windows game is matched on`() = runTest {
        coEvery { storefrontMatches.identities(2L) } returns linkedOn(
            com.playfieldportal.feature.artwork.match.Storefront.STEAM,
            com.playfieldportal.feature.artwork.match.Storefront.GOG,
        )
        loadSecondGame()

        viewModel.openOptions()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("Steam", "GOG"), viewModel.uiState.value.storeLinks)
    }

    @Test
    fun `removing a link takes that store off the Store Match row`() = runTest {
        coEvery { storefrontMatches.identities(2L) } returns linkedOn(
            com.playfieldportal.feature.artwork.match.Storefront.STEAM,
            com.playfieldportal.feature.artwork.match.Storefront.GOG,
        )
        coEvery { storefrontMatches.rematchRows(2L) } returns listOf(
            com.playfieldportal.feature.artwork.match.StorefrontMatchRepository.RematchRow(
                store = com.playfieldportal.feature.artwork.match.Storefront.STEAM,
                identity = linkedOn(com.playfieldportal.feature.artwork.match.Storefront.STEAM).single(),
                searchable = true,
            ),
        )
        loadSecondGame()
        viewModel.openOptions()
        viewModel.openStorefrontRematch()
        testDispatcher.scheduler.advanceUntilIdle()

        coEvery { storefrontMatches.identities(2L) } returns
            linkedOn(com.playfieldportal.feature.artwork.match.Storefront.GOG)
        viewModel.onRematchActionTapped(0, RematchAction.REMOVE)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("GOG"), viewModel.uiState.value.storeLinks)
    }

    // ── Store Match search bar ────────────────────────────────────────────

    private fun openStoreMatch() {
        coEvery { storefrontMatches.rematchRows(any()) } returns listOf(
            com.playfieldportal.feature.artwork.match.StorefrontMatchRepository.RematchRow(
                store = com.playfieldportal.feature.artwork.match.Storefront.STEAM,
                identity = null,
                searchable = true,
            ),
        )
        loadedAndLaidOut()
        viewModel.openStorefrontRematch()
        testDispatcher.scheduler.advanceUntilIdle()
    }

    private val storeMatch get() = viewModel.uiState.value.storefrontRematch!!

    @Test
    fun `Store Match opens on the search bar, holding the game's current title`() = runTest {
        openStoreMatch()

        assertTrue(storeMatch.queryFocused)
        assertEquals("Crash Bandicoot", storeMatch.query)
        assertFalse(storeMatch.editingQuery)

        // The store rows are one step below it, and the bar is one step back up.
        viewModel.handleGamepadAction(GamepadAction.NAVIGATE_DOWN)
        assertFalse(storeMatch.queryFocused)
        assertEquals(0, storeMatch.focus)
        viewModel.handleGamepadAction(GamepadAction.NAVIGATE_UP)
        assertTrue(storeMatch.queryFocused)
    }

    @Test
    fun `Select on the search bar starts typing, and Back stops typing without closing the panel`() = runTest {
        openStoreMatch()

        viewModel.handleGamepadAction(GamepadAction.SELECT)
        assertTrue(storeMatch.editingQuery)

        viewModel.handleGamepadAction(GamepadAction.BACK)
        assertFalse(storeMatch.editingQuery)
        assertTrue(storeMatch.queryFocused)

        // Only now does Back close Store Match.
        viewModel.handleGamepadAction(GamepadAction.BACK)
        assertNull(viewModel.uiState.value.storefrontRematch)
    }

    @Test
    fun `searching a typed name asks the stores for that name and opens the picker`() = runTest {
        openStoreMatch()
        viewModel.handleGamepadAction(GamepadAction.SELECT)
        viewModel.onRematchQueryChanged("Crash Bandicoot N. Sane Trilogy")

        viewModel.searchStorefrontsByName()
        testDispatcher.scheduler.advanceUntilIdle()

        // Every store: a typed name is a search of them all, never of one row's store.
        coVerify { storefrontMatches.lookup(1L, true, "Crash Bandicoot N. Sane Trilogy", null) }
        assertEquals("Crash Bandicoot N. Sane Trilogy", viewModel.uiState.value.storefrontMatch?.typedQuery)
        // Typing is over, and the name is still in the bar for when the picker closes.
        assertFalse(storeMatch.editingQuery)
        assertEquals("Crash Bandicoot N. Sane Trilogy", storeMatch.query)
    }

    @Test
    fun `an empty search bar searches nothing`() = runTest {
        openStoreMatch()
        viewModel.onRematchQueryChanged("   ")

        viewModel.searchStorefrontsByName()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.storefrontMatch)
        coVerify(exactly = 0) { storefrontMatches.lookup(any(), any(), any(), any()) }
    }

    // ── More than one store ───────────────────────────────────────────────

    private val steamStore = com.playfieldportal.feature.artwork.match.Storefront.STEAM
    private val gogStore = com.playfieldportal.feature.artwork.match.Storefront.GOG

    private fun pendingOn(
        store: com.playfieldportal.feature.artwork.match.Storefront,
        confidence: com.playfieldportal.feature.artwork.match.MatchConfidence,
        vararg candidates: Pair<String, String>,
    ) = com.playfieldportal.feature.artwork.match.StorefrontMatchRepository.PendingMatch(
        store = store,
        confidence = confidence,
        candidates = candidates.map { (id, title) ->
            com.playfieldportal.feature.artwork.match.ScoredStorefrontCandidate(
                com.playfieldportal.feature.artwork.match.StorefrontCandidate(store, id, title),
                listOf(com.playfieldportal.feature.artwork.match.MatchSignal.EXACT_TITLE),
            )
        },
    )

    /** Opens the picker over a lookup in which Steam and GOG both have something to choose. */
    private fun openPickerOnTwoStores() {
        coEvery { storefrontMatches.lookup(any(), any(), any(), any()) } returns
            com.playfieldportal.feature.artwork.match.StorefrontMatchRepository.Lookup.NeedsChoice(
                query = "doom",
                pending = listOf(
                    pendingOn(
                        steamStore, com.playfieldportal.feature.artwork.match.MatchConfidence.AMBIGUOUS,
                        "379720" to "DOOM", "2280" to "DOOM (1993)",
                    ),
                    pendingOn(
                        gogStore, com.playfieldportal.feature.artwork.match.MatchConfidence.EXACT,
                        "1390579243" to "DOOM (2016)",
                    ),
                ),
            )
        loadedAndLaidOut()
        viewModel.openStorefrontMatch()
        testDispatcher.scheduler.advanceUntilIdle()
    }

    private val picker get() = viewModel.uiState.value.storefrontMatch!!

    @Test
    fun `the picker shows one store at a time, the first one asked first`() = runTest {
        openPickerOnTwoStores()

        assertEquals(listOf("Steam", "GOG"), picker.stores.map { it.label })
        assertEquals("Steam", picker.storeLabel)
        assertEquals(listOf("379720", "2280"), picker.rows.map { it.storeId })
    }

    @Test
    fun `R1 and L1 move between stores and put the cursor on the new store's first row`() = runTest {
        openPickerOnTwoStores()
        viewModel.handleGamepadAction(GamepadAction.NAVIGATE_DOWN)
        assertEquals(1, picker.focus)

        viewModel.handleGamepadAction(GamepadAction.NEXT_CATEGORY)

        assertEquals("GOG", picker.storeLabel)
        assertEquals(listOf("1390579243"), picker.rows.map { it.storeId })
        assertEquals(com.playfieldportal.feature.artwork.match.MatchConfidence.EXACT, picker.confidence)
        assertEquals(0, picker.focus)
        assertEquals(GameDetailKeys.storefrontCandidate(0), viewModel.uiState.value.navFocusKey)

        // The last store is the end of the row, not a wrap back to the first.
        viewModel.handleGamepadAction(GamepadAction.NEXT_CATEGORY)
        assertEquals("GOG", picker.storeLabel)

        viewModel.handleGamepadAction(GamepadAction.PREV_CATEGORY)
        assertEquals("Steam", picker.storeLabel)
        assertEquals(listOf("379720", "2280"), picker.rows.map { it.storeId })
    }

    @Test
    fun `choosing on one store links that store's game and moves on to the store still waiting`() = runTest {
        openPickerOnTwoStores()

        viewModel.handleGamepadAction(GamepadAction.SELECT)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) {
            storefrontMatches.confirm(
                1L,
                match { it.store == steamStore && it.storeId == "379720" },
                com.playfieldportal.feature.artwork.match.MatchConfidence.AMBIGUOUS,
            )
        }
        // GOG still has a question open, so the picker stays and shows it.
        assertEquals(listOf("GOG"), picker.stores.map { it.label })
        assertEquals("GOG", picker.storeLabel)
        assertFalse(picker.confirming)

        viewModel.handleGamepadAction(GamepadAction.SELECT)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) {
            storefrontMatches.confirm(
                1L,
                match { it.store == gogStore && it.storeId == "1390579243" },
                com.playfieldportal.feature.artwork.match.MatchConfidence.EXACT,
            )
        }
        // Nothing left to ask.
        assertNull(viewModel.uiState.value.storefrontMatch)
    }

    @Test
    fun `with one store to choose from the picker is exactly what it was`() = runTest {
        coEvery { storefrontMatches.lookup(any(), any(), any(), any()) } returns
            com.playfieldportal.feature.artwork.match.StorefrontMatchRepository.Lookup.NeedsChoice(
                query = "doom",
                pending = listOf(
                    pendingOn(
                        steamStore, com.playfieldportal.feature.artwork.match.MatchConfidence.AMBIGUOUS,
                        "379720" to "DOOM", "2280" to "DOOM (1993)",
                    ),
                ),
            )
        loadedAndLaidOut()
        viewModel.openStorefrontMatch()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.handleGamepadAction(GamepadAction.NEXT_CATEGORY)
        assertEquals("Steam", picker.storeLabel)
        assertEquals(0, picker.focus)

        viewModel.handleGamepadAction(GamepadAction.SELECT)
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(viewModel.uiState.value.storefrontMatch)
    }

    @Test
    fun `a store that did not answer is named on the picker, never left looking empty`() = runTest {
        coEvery { storefrontMatches.lookup(any(), any(), any(), any()) } returns
            com.playfieldportal.feature.artwork.match.StorefrontMatchRepository.Lookup.NeedsChoice(
                query = "doom",
                pending = listOf(
                    pendingOn(
                        steamStore, com.playfieldportal.feature.artwork.match.MatchConfidence.AMBIGUOUS,
                        "379720" to "DOOM",
                    ),
                ),
                unavailable = listOf(gogStore),
            )
        loadedAndLaidOut()
        viewModel.openStorefrontMatch()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("GOG"), picker.unavailableStores)
        assertEquals("GOG didn't answer, so this list is Steam's alone. Try again later for GOG.", storefrontOtherStoresNote(picker))
    }

    @Test
    fun `a store row's own Search asks that store only`() = runTest {
        openStoreMatch()
        viewModel.handleGamepadAction(GamepadAction.NAVIGATE_DOWN)   // the Steam row

        viewModel.handleGamepadAction(GamepadAction.SELECT)
        testDispatcher.scheduler.advanceUntilIdle()

        // Looking past Steam's link must leave every other store's link alone.
        coVerify { storefrontMatches.lookup(1L, true, null, steamStore) }
    }

    @Test
    fun `Search every store again asks them all`() = runTest {
        openStoreMatch()

        viewModel.searchAllStorefronts()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { storefrontMatches.lookup(1L, true, null, null) }
    }

    @Test
    fun `a page action cannot fire through the Options overlay`() = runTest {
        loadedAndLaidOut()
        // Page cursor parked on Artwork, which would open the Artwork Studio if it ever fired.
        viewModel.handleGamepadAction(GamepadAction.NAVIGATE_DOWN)
        viewModel.handleGamepadAction(GamepadAction.NAVIGATE_RIGHT)
        assertEquals(GameDetailKeys.ARTWORK, viewModel.uiState.value.navFocusKey)

        viewModel.handleGamepadAction(GamepadAction.OPEN_CONTEXT_MENU)
        viewModel.handleGamepadAction(GamepadAction.SELECT)   // the first option: Favorite
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showArtworkStudio)
        assertTrue(viewModel.uiState.value.game?.isFavorite == true)
        coVerify { gameRepository.setFavorite(1L, true) }
    }

    @Test
    fun `a multi-disc set exposes one node per disc and confirming one keeps the cursor there`() = runTest {
        val setKey = "psx\u0001/roms/psx\u0001Final Fantasy VII"
        val primary = fakeGame.copy(id = 1L, discSetKey = setKey, discNumber = 1, isDiscPrimary = true)
        val disc2 = fakeGame.copy(id = 2L, discSetKey = setKey, discNumber = 2, isDiscPrimary = false)
        coEvery { gameRepository.getById(1L) } returns primary
        coEvery { gameRepository.getDiscSetMembers(setKey) } returns listOf(primary, disc2)

        loadedAndLaidOut()
        assertTrue(GameDetailKeys.disc(1L) in viewModel.focusableNodeKeys())
        assertTrue(GameDetailKeys.disc(2L) in viewModel.focusableNodeKeys())
        assertEquals(GameDetailKeys.LAUNCH, viewModel.uiState.value.navFocusKey)

        viewModel.handleGamepadAction(GamepadAction.NAVIGATE_DOWN)   // quick actions
        viewModel.handleGamepadAction(GamepadAction.NAVIGATE_DOWN)   // first disc
        assertEquals(GameDetailKeys.disc(1L), viewModel.uiState.value.navFocusKey)

        viewModel.handleGamepadAction(GamepadAction.SELECT)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1L, viewModel.uiState.value.selectedDiscId)
        // Confirming a disc never throws the cursor somewhere unrelated.
        assertEquals(GameDetailKeys.disc(1L), viewModel.uiState.value.navFocusKey)
    }

    @Test
    fun `a package-backed game exposes neither emulator nor achievement nodes`() = runTest {
        coEvery { gameRepository.getById(3L) } returns
            Game(id = 3L, title = "Alto's Odyssey", platformId = "android", packageName = "com.noodlecake.altosodyssey")
        coEvery { platformDao.getById("android") } returns null

        viewModel.loadGame(3L)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onPageLaidOut()

        val keys = viewModel.focusableNodeKeys()
        assertTrue(GameDetailKeys.LAUNCH in keys)
        assertTrue(GameDetailKeys.OPTIONS_ACTION in keys)
        assertFalse(GameDetailKeys.COINS in keys)
    }

    @Test
    fun `a missing manual leaves no focusable ghost node`() = runTest {
        coEvery { artworkStore.find(1L, com.playfieldportal.feature.artwork.store.ArtworkKind.MANUAL) } returns null

        loadedAndLaidOut()

        assertFalse(GameDetailKeys.MANUAL in viewModel.focusableNodeKeys())
        assertNull(viewModel.uiState.value.navFocusKey?.takeIf { it == GameDetailKeys.MANUAL })

        // Disabled, not explained: tapping it does nothing at all.
        viewModel.onNodeTapped(GameDetailKeys.MANUAL)
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(viewModel.uiState.value.actionMessage)
        assertNull(viewModel.uiState.value.manualViewerUri)
    }

    @Test
    fun `a manual only in the portable library is enabled and opens`() = runTest {
        // Not scraped into the internal store; linked from {platform}/manuals by its artwork record.
        coEvery { artworkStore.find(1L, com.playfieldportal.feature.artwork.store.ArtworkKind.MANUAL) } returns null
        coEvery {
            artworkRecordDao.get(1L, com.playfieldportal.feature.artwork.store.ArtworkKind.MANUAL.name)
        } returns mockk { every { documentUri } returns "content://library/psx/manuals/crash.pdf" }

        loadedAndLaidOut()

        assertTrue(viewModel.uiState.value.hasManual)
        assertTrue(GameDetailKeys.MANUAL in viewModel.focusableNodeKeys())

        viewModel.onNodeTapped(GameDetailKeys.MANUAL)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("content://library/psx/manuals/crash.pdf", viewModel.uiState.value.manualViewerUri)
    }

    @Test
    fun `the Options quick action opens the context menu`() = runTest {
        loadedAndLaidOut()

        viewModel.onNodeTapped(GameDetailKeys.OPTIONS_ACTION)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showOptions)
        assertEquals(
            GameDetailKeys.option(DetailAction.FAVORITE.name),
            viewModel.uiState.value.navFocusKey,
        )
    }

    @Test
    fun `a tap and a Cross press on the same node do the same thing`() = runTest {
        loadedAndLaidOut()

        viewModel.onNodeTapped(GameDetailKeys.COINS)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.openCoins)
        assertEquals(GameDetailKeys.COINS, viewModel.uiState.value.navFocusKey)
        // Touch hides the controller cursor without losing the logical node.
        assertFalse(viewModel.uiState.value.cursorVisible)
    }

    // ── Menu sounds ───────────────────────────────────────────────────────
    //
    // Game Detail used to be almost silent: the ViewModel played one cue, on Play, and nothing
    // else — no tick on the cursor, no cue on confirm, no cue on back. The rules pinned below are
    // the XMB's own, so a user cannot tell from the sound which screen they are on.

    private val select = com.playfieldportal.core.ui.sound.MenuSound.SELECT
    private val scroll = com.playfieldportal.core.ui.sound.MenuSound.SCROLL
    private val back = com.playfieldportal.core.ui.sound.MenuSound.BACK
    private val confirmCue = com.playfieldportal.core.ui.sound.MenuSound.CONFIRM

    @Test
    fun `moving the cursor ticks, and a clamped move is silent`() = runTest {
        loadedAndLaidOut()
        assertEquals(GameDetailKeys.LAUNCH, viewModel.uiState.value.navFocusKey)

        // Launch is the first node, so UP has nowhere to go: the boundary is audible as silence.
        viewModel.handleGamepadAction(GamepadAction.NAVIGATE_UP)
        assertEquals(GameDetailKeys.LAUNCH, viewModel.uiState.value.navFocusKey)
        verify(exactly = 0) { menuSound.play(scroll, any()) }

        viewModel.handleGamepadAction(GamepadAction.NAVIGATE_DOWN)
        assertEquals(GameDetailKeys.FAVORITE, viewModel.uiState.value.navFocusKey)
        verify(exactly = 1) { menuSound.play(scroll, any()) }

        // Stepping along the quick-action row is a move like any other.
        viewModel.handleGamepadAction(GamepadAction.NAVIGATE_RIGHT)
        assertEquals(GameDetailKeys.ARTWORK, viewModel.uiState.value.navFocusKey)
        verify(exactly = 2) { menuSound.play(scroll, any()) }
    }

    @Test
    fun `Options opens with the activation cue and closes with the back cue`() = runTest {
        loadedAndLaidOut()

        viewModel.handleGamepadAction(GamepadAction.OPEN_CONTEXT_MENU)
        assertTrue(viewModel.uiState.value.showOptions)
        // Exactly once: the cue is on openOptions, never also on the press that called it.
        verify(exactly = 1) { menuSound.play(select, any()) }

        viewModel.handleGamepadAction(GamepadAction.BACK)
        assertFalse(viewModel.uiState.value.showOptions)
        verify(exactly = 1) { menuSound.play(back, any()) }
        // Still on the page — Back closed the overlay, not the screen.
        assertFalse(viewModel.uiState.value.closed)
    }

    @Test
    fun `Back on the page plays the back cue once and leaves`() = runTest {
        loadedAndLaidOut()

        viewModel.handleGamepadAction(GamepadAction.BACK)
        assertTrue(viewModel.uiState.value.closed)
        verify(exactly = 1) { menuSound.play(back, any()) }
    }

    @Test
    fun `a tap and a Confirm on the same affordance sound identically`() = runTest {
        loadedAndLaidOut()

        // Touch: straight at the node.
        viewModel.onNodeTapped(GameDetailKeys.FAVORITE)
        testDispatcher.scheduler.advanceUntilIdle()
        verify(exactly = 1) { menuSound.play(select, any()) }

        // Controller: move onto the same node and confirm it. One more cue, the same cue — because
        // both paths end in toggleFavorite, which is where the sound lives.
        viewModel.handleGamepadAction(GamepadAction.SELECT)
        testDispatcher.scheduler.advanceUntilIdle()
        verify(exactly = 2) { menuSound.play(select, any()) }
    }

    @Test
    fun `the removal prompt opens with the activation cue and leaves the rest to the modal`() = runTest {
        loadedAndLaidOut()

        viewModel.requestRemove()
        assertTrue(viewModel.uiState.value.confirmRemove)
        verify(exactly = 1) { menuSound.play(select, any()) }

        // Cancel and Remove are pressed in the shared modal, which voices them itself.
        viewModel.cancelRemove()
        assertFalse(viewModel.uiState.value.confirmRemove)

        viewModel.requestRemove()
        viewModel.confirmRemoveGame()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { gameRepository.delete(1L) }
        assertTrue(viewModel.uiState.value.closed)
        verify(exactly = 0) { menuSound.play(back, any()) }
        verify(exactly = 0) { menuSound.play(confirmCue, any()) }
    }

    @Test
    fun `a Confirm that reaches the page while the removal prompt is up never removes the game`() = runTest {
        loadedAndLaidOut()
        viewModel.requestRemove()

        // The modal opens on Cancel; a press that raced it onto the screen must not skip that.
        viewModel.handleGamepadAction(GamepadAction.SELECT)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { gameRepository.delete(any()) }
        assertTrue(viewModel.uiState.value.confirmRemove)

        viewModel.handleGamepadAction(GamepadAction.BACK)
        assertFalse(viewModel.uiState.value.confirmRemove)
    }

    @Test
    fun `a refused action plays the error cue instead of an activation`() = runTest {
        // Spelled out rather than left to the relaxed mock: this test is about the branch taken
        // when there is NO video, so "no video" has to be a fact and not a default.
        coEvery { artworkStore.findAll(any(), any()) } returns emptyList()
        coEvery { artworkStore.find(any(), any(), any()) } returns null
        loadedAndLaidOut()
        assertNull(viewModel.uiState.value.videoUri)

        viewModel.onVideoClicked()
        testDispatcher.scheduler.advanceUntilIdle()

        verify(exactly = 1) { menuSound.play(com.playfieldportal.core.ui.sound.MenuSound.ERROR, any()) }
        verify(exactly = 0) { menuSound.play(select, any()) }
    }
}
