package com.playfieldportal.feature.xmb.ui.detail

import android.content.Context
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.artwork.TgdbGameInfo
import com.playfieldportal.feature.artwork.TheGamesDbApi
import com.playfieldportal.feature.artwork.api.IgdbApi
import com.playfieldportal.feature.artwork.api.IgdbGameInfo
import com.playfieldportal.feature.artwork.api.SgdbApiKeyProvider
import com.playfieldportal.feature.artwork.api.SgdbArtItem
import com.playfieldportal.feature.artwork.api.SgdbGame
import com.playfieldportal.feature.artwork.api.SsMediaCatalog
import com.playfieldportal.feature.artwork.api.SteamGridDbApi
import com.playfieldportal.feature.artwork.store.ArtworkStore
import com.playfieldportal.feature.artwork.store.RoutingArtworkStore
import com.playfieldportal.feature.artwork.video.VideoSnapTranscoder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * C16 Phase 1 — the Artwork Studio's search and browse behaviour.
 *
 * The screen had zero coverage before this, and the bug it is famous for ("artwork vanishes when
 * you switch source") is a concurrency bug, so these tests are mostly about WHICH response is
 * allowed to reach the grid.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArtworkStudioViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var context: Context
    private lateinit var gameRepository: GameRepository
    private lateinit var artworkStore: ArtworkStore
    private lateinit var routingStore: RoutingArtworkStore
    private lateinit var ssMediaCatalog: SsMediaCatalog
    private lateinit var steamGridDb: SteamGridDbApi
    private lateinit var sgdbKeyProvider: SgdbApiKeyProvider
    private lateinit var theGamesDb: TheGamesDbApi
    private lateinit var igdbApi: IgdbApi
    private lateinit var videoSnapTranscoder: VideoSnapTranscoder

    private val game = Game(
        id = 1L,
        // Deliberately a bad filename-derived title: the reason an editable query exists.
        title = "cr4sh bandicoot (u) [!]",
        platformId = "psx",
        romPath = "/roms/psx/cr4sh bandicoot (u) [!].bin",
        steamGridDbId = null,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = mockk(relaxed = true)
        gameRepository = mockk(relaxed = true)
        artworkStore = mockk(relaxed = true)
        routingStore = mockk(relaxed = true)
        ssMediaCatalog = mockk(relaxed = true)
        steamGridDb = mockk(relaxed = true)
        sgdbKeyProvider = mockk(relaxed = true)
        theGamesDb = mockk(relaxed = true)
        igdbApi = mockk(relaxed = true)
        videoSnapTranscoder = mockk(relaxed = true)

        coEvery { gameRepository.getById(1L) } returns game
        coEvery { sgdbKeyProvider.getKey() } returns "sgdb-key"
        coEvery { igdbApi.hasCredentials() } returns true
        coEvery { artworkStore.find(any(), any(), any()) } returns null
        coEvery { ssMediaCatalog.mediasFor(any()) } returns emptyList()
        coEvery { steamGridDb.searchGame(any()) } returns Result.success(listOf(SgdbGame(id = 77L, name = "Crash")))
        coEvery { steamGridDb.getArt(any(), any(), any(), any(), any()) } returns Result.success(emptyList())
        coEvery { theGamesDb.fetchGameInfo(any(), any()) } returns null
        coEvery { igdbApi.fetchGameInfo(any(), any()) } returns null
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = ArtworkStudioViewModel(
        context, gameRepository, artworkStore, routingStore, ssMediaCatalog,
        steamGridDb, sgdbKeyProvider, theGamesDb, igdbApi, videoSnapTranscoder,
    )

    // ── The query is state, seeded from the title (task 1.1) ──────────────────

    @Test
    fun `the query starts as the game's title and is not marked custom`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.load(1L)
        advanceUntilIdle()

        assertEquals("cr4sh bandicoot (u) [!]", vm.uiState.value.query)
        assertFalse(vm.uiState.value.queryIsCustom)
    }

    @Test
    fun `typing does not browse — only submitting does`() = runTest(testDispatcher) {
        val vm = loadedOn(StudioSource.IGDB)

        vm.openSearch()
        vm.onQueryDraftChanged("Cra")
        vm.onQueryDraftChanged("Crash Ban")
        vm.onQueryDraftChanged("Crash Bandicoot")
        advanceUntilIdle()

        // Still only the initial browse from load().
        coVerify(exactly = 1) { igdbApi.fetchGameInfo(any(), any()) }
        assertTrue(vm.uiState.value.searchOpen)

        vm.submitSearch()
        advanceUntilIdle()

        coVerify(exactly = 1) { igdbApi.fetchGameInfo("psx", "Crash Bandicoot") }
        assertFalse(vm.uiState.value.searchOpen)
        assertEquals("Crash Bandicoot", vm.uiState.value.query)
        assertTrue(vm.uiState.value.queryIsCustom)
    }

    @Test
    fun `searching never renames the game`() = runTest(testDispatcher) {
        val vm = loadedOn(StudioSource.IGDB)

        vm.openSearch()
        vm.onQueryDraftChanged("Crash Bandicoot")
        vm.submitSearch()
        advanceUntilIdle()

        coVerify(exactly = 0) { gameRepository.upsert(any()) }
        coVerify(exactly = 0) { gameRepository.updateScrapedTitle(any(), any()) }
        assertEquals("cr4sh bandicoot (u) [!]", vm.uiState.value.game?.displayTitle)
    }

    @Test
    fun `submitting an equivalent query does not refetch`() = runTest(testDispatcher) {
        val vm = loadedOn(StudioSource.IGDB)

        vm.openSearch()
        // Same title, differently spaced and tagged — the same request.
        vm.onQueryDraftChanged("  cr4sh   bandicoot (u) [!]  ")
        vm.submitSearch()
        advanceUntilIdle()

        coVerify(exactly = 1) { igdbApi.fetchGameInfo(any(), any()) }
    }

    @Test
    fun `a blank query falls back to the game's title rather than searching for nothing`() =
        runTest(testDispatcher) {
            val vm = loadedOn(StudioSource.IGDB)

            vm.openSearch()
            vm.onQueryDraftChanged("   ")
            vm.submitSearch()
            advanceUntilIdle()

            assertEquals("cr4sh bandicoot (u) [!]", vm.uiState.value.query)
            assertFalse(vm.uiState.value.queryIsCustom)
        }

    @Test
    fun `reset puts the game's own title back and browses for it`() = runTest(testDispatcher) {
        val vm = loadedOn(StudioSource.IGDB)

        vm.openSearch()
        vm.onQueryDraftChanged("Crash Bandicoot")
        vm.submitSearch()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.queryIsCustom)

        vm.resetSearchToTitle()
        advanceUntilIdle()

        assertEquals("cr4sh bandicoot (u) [!]", vm.uiState.value.query)
        assertFalse(vm.uiState.value.queryIsCustom)
        coVerify(exactly = 0) { gameRepository.upsert(any()) }
    }

    // ── Race safety (task 1.2) ────────────────────────────────────────────────

    /**
     * The reported bug, reproduced: switch source while the first source is still loading. The
     * slow provider must not be able to paint its results over the fast one that replaced it.
     */
    @Test
    fun `a slow source that finishes late never repaints the source that replaced it`() =
        runTest(testDispatcher) {
            val slow = CompletableDeferred<List<SgdbArtItem>>()
            coEvery { steamGridDb.getArt(any(), any(), any(), any(), any()) } coAnswers {
                Result.success(slow.await())
            }
            coEvery { theGamesDb.fetchGameInfo(any(), any()) } returns tgdb("tgdb-hero")

            val vm = viewModel()
            vm.load(1L)
            advanceUntilIdle()

            val sources = vm.sourcesForTab()
            vm.selectSource(sources.indexOf(StudioSource.STEAMGRIDDB))
            advanceUntilIdle()
            assertTrue("SGDB should still be in flight", vm.uiState.value.resultsLoading)

            // The user gives up on SteamGridDB and switches.
            vm.selectSource(sources.indexOf(StudioSource.THEGAMESDB))
            advanceUntilIdle()
            val afterSwitch = vm.uiState.value.results
            assertEquals(listOf("tgdb-hero"), afterSwitch.map { it.url })

            // SteamGridDB finally answers.
            slow.complete(listOf(SgdbArtItem(id = 9L, url = "sgdb-late")))
            advanceUntilIdle()

            assertEquals(
                "a superseded response reached the grid",
                afterSwitch.map { it.url },
                vm.uiState.value.results.map { it.url },
            )
        }

    @Test
    fun `switching source never leaves the previous provider's tiles on screen`() =
        runTest(testDispatcher) {
            val slow = CompletableDeferred<List<SgdbArtItem>>()
            coEvery { theGamesDb.fetchGameInfo(any(), any()) } returns tgdb("tgdb-hero")
            coEvery { steamGridDb.getArt(any(), any(), any(), any(), any()) } coAnswers {
                Result.success(slow.await())
            }

            val vm = viewModel()
            vm.load(1L)
            advanceUntilIdle()

            val sources = vm.sourcesForTab()
            vm.selectSource(sources.indexOf(StudioSource.THEGAMESDB))
            advanceUntilIdle()
            assertEquals(1, vm.uiState.value.results.size)

            vm.selectSource(sources.indexOf(StudioSource.STEAMGRIDDB))
            advanceUntilIdle()

            // Skeletons, not TheGamesDB's art.
            assertTrue(vm.uiState.value.resultsLoading)
            assertTrue(vm.uiState.value.results.isEmpty())
            assertEquals(STUDIO_GRID_COLUMNS * STUDIO_GRID_ROWS, vm.uiState.value.skeletonCount)

            slow.complete(emptyList())
            advanceUntilIdle()
        }

    @Test
    fun `returning to a source already browsed renders from cache without refetching`() =
        runTest(testDispatcher) {
            coEvery { theGamesDb.fetchGameInfo(any(), any()) } returns tgdb("tgdb-hero")
            coEvery { igdbApi.fetchGameInfo(any(), any()) } returns igdb("igdb-hero")

            val vm = loadedOn(StudioSource.THEGAMESDB)
            val sources = vm.sourcesForTab()

            vm.selectSource(sources.indexOf(StudioSource.IGDB))
            advanceUntilIdle()
            vm.selectSource(sources.indexOf(StudioSource.THEGAMESDB))
            advanceUntilIdle()

            assertEquals(listOf("tgdb-hero"), vm.uiState.value.results.map { it.url })
            // Instant: a cache hit never shows a loading state...
            assertFalse(vm.uiState.value.resultsLoading)
            // ...and never hits the provider a second time.
            coVerify(exactly = 1) { theGamesDb.fetchGameInfo(any(), any()) }
        }

    // ── Mature is SteamGridDB's alone (task 1.3) ──────────────────────────────

    @Test
    fun `toggling mature refetches SteamGridDB and leaves other sources' caches intact`() =
        runTest(testDispatcher) {
            coEvery { theGamesDb.fetchGameInfo(any(), any()) } returns tgdb("tgdb-hero")

            val vm = loadedOn(StudioSource.THEGAMESDB)
            val sources = vm.sourcesForTab()
            vm.selectSource(sources.indexOf(StudioSource.STEAMGRIDDB))
            advanceUntilIdle()

            vm.toggleNsfw()
            advanceUntilIdle()

            assertTrue(vm.uiState.value.includeNsfw)
            coVerify(exactly = 2) { steamGridDb.getArt(any(), any(), any(), any(), any()) }

            // TheGamesDB's page is still cached: going back does not re-hit it.
            vm.selectSource(sources.indexOf(StudioSource.THEGAMESDB))
            advanceUntilIdle()
            assertEquals(listOf("tgdb-hero"), vm.uiState.value.results.map { it.url })
            coVerify(exactly = 1) { theGamesDb.fetchGameInfo(any(), any()) }
        }

    @Test
    fun `Square opens search instead of firing a browse filter`() = runTest(testDispatcher) {
        val vm = loadedOn(StudioSource.IGDB)

        vm.handleGamepadAction(GamepadAction.CHANGE_SORT)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.searchOpen)
        assertFalse("Square must no longer toggle mature", vm.uiState.value.includeNsfw)
    }

    @Test
    fun `START toggles mature while SteamGridDB is the active source`() = runTest(testDispatcher) {
        val vm = loadedOn(StudioSource.STEAMGRIDDB)

        vm.handleGamepadAction(GamepadAction.HOME)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.includeNsfw)
        coVerify(exactly = 2) { steamGridDb.getArt(any(), any(), any(), any(), any()) }

        vm.handleGamepadAction(GamepadAction.HOME)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.includeNsfw)
    }

    // Mature is a SteamGridDB filter and nothing else's. Flipping it elsewhere would change
    // hidden state that nothing on screen reflects and no provider would act on.
    @Test
    fun `START does nothing on a source that has no mature filter`() = runTest(testDispatcher) {
        val vm = loadedOn(StudioSource.IGDB)

        vm.handleGamepadAction(GamepadAction.HOME)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.includeNsfw)
        coVerify(exactly = 1) { igdbApi.fetchGameInfo(any(), any()) }
    }

    // ── Paging (task 1.4) ─────────────────────────────────────────────────────

    @Test
    fun `paging walks one gridful at a time and reports the range`() = runTest(testDispatcher) {
        val pageSize = STUDIO_GRID_COLUMNS * STUDIO_GRID_ROWS
        coEvery { steamGridDb.getArt(any(), any(), any(), any(), any()) } returns
            Result.success((1..(pageSize + 5)).map { SgdbArtItem(id = it.toLong(), url = "art$it") })

        val vm = loadedOn(StudioSource.STEAMGRIDDB)

        assertEquals(pageSize, vm.uiState.value.results.size)
        assertEquals(1, vm.uiState.value.rangeStart)
        assertEquals(pageSize, vm.uiState.value.rangeEnd)
        assertEquals(2, vm.uiState.value.pageCount)

        vm.nextPage()
        advanceUntilIdle()
        assertEquals(5, vm.uiState.value.results.size)
        assertEquals(pageSize + 1, vm.uiState.value.rangeStart)
        assertEquals(pageSize + 5, vm.uiState.value.rangeEnd)
        assertEquals(0, vm.uiState.value.gridIndex)

        // Past the end is a no-op, not an empty grid.
        vm.nextPage()
        advanceUntilIdle()
        assertEquals(5, vm.uiState.value.results.size)

        vm.previousPage()
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.rangeStart)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun kotlinx.coroutines.test.TestScope.loadedOn(
        source: StudioSource,
    ): ArtworkStudioViewModel {
        val vm = viewModel()
        vm.load(1L)
        advanceUntilIdle()
        val index = vm.sourcesForTab().indexOf(source)
        check(index >= 0) { "$source is not available for ${STUDIO_TABS[0].label}" }
        vm.selectSource(index)
        advanceUntilIdle()
        return vm
    }

    private fun tgdb(heroUrl: String) = TgdbGameInfo(
        tgdbId = 1L, title = "Crash", description = null, releaseYear = null,
        artworkUrl = null, heroUrl = heroUrl, logoUrl = null,
    )

    private fun igdb(heroUrl: String) = IgdbGameInfo(artworkUrl = null, heroUrl = heroUrl, logoUrl = null)
}
