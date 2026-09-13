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
import com.playfieldportal.feature.artwork.api.SgdbArtType
import com.playfieldportal.feature.artwork.api.SsMediaCatalog
import com.playfieldportal.feature.artwork.api.SteamGridDbApi
import com.playfieldportal.feature.artwork.store.ArtworkKind
import com.playfieldportal.feature.artwork.store.ArtworkStore
import com.playfieldportal.feature.artwork.store.RoutingArtworkStore
import com.playfieldportal.feature.artwork.video.VideoSnapTranscoder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
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
    private lateinit var matchEvidence: com.playfieldportal.feature.artwork.match.ProviderMatchEvidence

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
        matchEvidence = mockk(relaxed = true)
        coEvery { matchEvidence.searchByTitle(any(), any(), any()) } returns emptyList()
        coEvery { matchEvidence.searchScreenScraperOnAnyPlatform(any(), any()) } returns emptyList()
        coEvery { matchEvidence.candidateByRomHash(any(), any(), any()) } returns null
        coEvery { matchEvidence.candidateByStorefront(any(), any(), any()) } returns null

        coEvery { gameRepository.getById(1L) } returns game
        coEvery { sgdbKeyProvider.getKey() } returns "sgdb-key"
        coEvery { igdbApi.hasCredentials() } returns true
        coEvery { theGamesDb.hasApiKey() } returns true
        coEvery { artworkStore.find(any(), any(), any()) } returns null
        coEvery { ssMediaCatalog.mediasFor(any(), any()) } returns emptyList()
        // SteamGridDB's autocomplete, as the Studio asks it (task M.2): one hit, not an exact title
        // match for the game, so it browses as the first hit without ever being the match.
        coEvery {
            matchEvidence.searchByTitle(com.playfieldportal.feature.artwork.match.MatchProvider.STEAMGRIDDB, any(), any())
        } returns listOf(sgdbCandidate("77", "Crash"))
        coEvery { steamGridDb.getArt(any(), any(), any(), any(), any()) } returns Result.success(emptyList())
        coEvery { theGamesDb.fetchGameInfo(any(), any()) } returns null
        coEvery { igdbApi.fetchGameInfo(any(), any()) } returns null
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = ArtworkStudioViewModel(
        context, gameRepository, artworkStore, routingStore, ssMediaCatalog,
        steamGridDb, sgdbKeyProvider, theGamesDb, igdbApi, videoSnapTranscoder, matchEvidence,
        // Nothing kept between opens: these tests count what each open asks.
        com.playfieldportal.feature.artwork.match.TitleSearchStore.None,
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
            assertEquals(StudioGridCapacity.UNMEASURED.pageSize, vm.uiState.value.skeletonCount)

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

    // START applies changes since task 5.2; mature moved to the options menu (covered with the queue tests).
    // With nothing changed, START must change nothing and ask no provider again.
    @Test
    fun `START with nothing picked changes nothing`() = runTest(testDispatcher) {
        val vm = loadedOn(StudioSource.STEAMGRIDDB)

        vm.handleGamepadAction(GamepadAction.HOME)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.includeNsfw)
        assertTrue(vm.uiState.value.queue.isEmpty())
        coVerify(exactly = 1) { steamGridDb.getArt(any(), any(), any(), any(), any()) }
    }

    // ── Paging (task 1.4) ─────────────────────────────────────────────────────

    @Test
    fun `paging walks one gridful at a time and reports the range`() = runTest(testDispatcher) {
        val pageSize = StudioGridCapacity.UNMEASURED.pageSize
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

    // ── Selection (task 5.1) ──────────────────────────────────────────────────

    /** SteamGridDB on SCREENSHOT with the grid focused: [perType] results for each of its four art types. */
    private suspend fun kotlinx.coroutines.test.TestScope.screenshotGridOnSgdb(perType: Int): ArtworkStudioViewModel {
        coEvery { steamGridDb.getArt(any(), any(), any(), any(), any()) } answers {
            val type = secondArg<SgdbArtType>()
            Result.success((1..perType).map { SgdbArtItem(id = it.toLong(), url = "${type.endpoint}$it") })
        }
        val vm = loadedOn(StudioSource.STEAMGRIDDB)
        vm.selectTab(STUDIO_TABS.indexOfFirst { it.kind == ArtworkKind.SCREENSHOT })
        advanceUntilIdle()
        vm.selectSource(vm.sourcesForTab().indexOf(StudioSource.STEAMGRIDDB))
        advanceUntilIdle()
        vm.handleGamepadAction(GamepadAction.SELECT)   // into the grid
        return vm
    }

    @Test
    fun `A on a screenshot tile picks it, and the pick survives paging away and back`() = runTest(testDispatcher) {
        val vm = screenshotGridOnSgdb(perType = 6)   // 24 results: two 4 × 5 pages
        vm.handleGamepadAction(GamepadAction.SELECT)

        val picked = vm.uiState.value.results[0]
        assertEquals("grids:1", picked.providerAssetId)
        assertEquals(null, vm.uiState.value.candidate)
        assertTrue(vm.uiState.value.isSelected(picked))

        vm.nextPage()
        assertFalse(vm.uiState.value.isSelected(vm.uiState.value.results[0]))
        vm.previousPage()
        assertTrue(vm.uiState.value.isSelected(vm.uiState.value.results[0]))
        assertEquals(1, vm.uiState.value.selectedOnTab)

        vm.handleGamepadAction(GamepadAction.SELECT)
        assertTrue(vm.uiState.value.selection.isEmpty())
    }

    @Test
    fun `a SteamGridDB grid and hero with the same id are two picks`() = runTest(testDispatcher) {
        val vm = screenshotGridOnSgdb(perType = 2)   // grids1, grids2, heroes1, heroes2, …
        vm.toggleSelection(0)
        vm.toggleSelection(2)

        assertEquals(listOf("grids1", "heroes1"), vm.uiState.value.selection.values.map { it.url })
    }

    @Test
    fun `picks survive a source switch and are counted only on their own tab`() = runTest(testDispatcher) {
        val vm = screenshotGridOnSgdb(perType = 2)
        vm.toggleSelection(0)
        val picked = vm.uiState.value.results[0]

        vm.selectSource(vm.sourcesForTab().indexOf(StudioSource.IGDB))
        advanceUntilIdle()
        vm.selectSource(vm.sourcesForTab().indexOf(StudioSource.STEAMGRIDDB))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isSelected(picked))

        vm.selectTab(STUDIO_TABS.indexOfFirst { it.kind == ArtworkKind.VIDEO })
        advanceUntilIdle()
        assertEquals(0, vm.uiState.value.selectedOnTab)
        assertEquals(1, vm.uiState.value.selection.size)
    }

    @Test
    fun `A on a single-art tab still previews the tile and picks nothing`() = runTest(testDispatcher) {
        coEvery { steamGridDb.getArt(any(), any(), any(), any(), any()) } returns
            Result.success(listOf(SgdbArtItem(id = 1L, url = "art1")))
        val vm = loadedOn(StudioSource.STEAMGRIDDB)   // ICON0
        vm.handleGamepadAction(GamepadAction.SELECT)   // into the grid
        vm.handleGamepadAction(GamepadAction.SELECT)

        assertEquals("art1", vm.uiState.value.candidate?.url)
        vm.toggleSelection(0)
        assertTrue(vm.uiState.value.selection.isEmpty())
    }

    @Test
    fun `Preview in the Triangle menu opens the focused screenshot`() = runTest(testDispatcher) {
        val vm = screenshotGridOnSgdb(perType = 2)
        vm.handleGamepadAction(GamepadAction.NAVIGATE_RIGHT)
        vm.handleGamepadAction(GamepadAction.OPEN_CONTEXT_MENU)
        advanceUntilIdle()

        assertEquals(StudioAction.PREVIEW, vm.uiState.value.availableActions.first())
        vm.handleGamepadAction(GamepadAction.SELECT)

        assertEquals("grids2", vm.uiState.value.candidate?.url)
        assertFalse(vm.uiState.value.actionsOpen)
        assertTrue(vm.uiState.value.selection.isEmpty())
    }

    @Test
    fun `every open starts with no picks`() = runTest(testDispatcher) {
        val vm = screenshotGridOnSgdb(perType = 2)
        vm.toggleSelection(0)

        vm.load(1L)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.selection.isEmpty())
    }

    // ── Download queue (task 5.2) ─────────────────────────────────────────────

    @Test
    fun `START asks, then adds the tab's picks in pick order, one download at a time`() = runTest(testDispatcher) {
        val first = CompletableDeferred<String?>()
        coEvery { routingStore.studioAppendFromUrl(any(), any(), "grids2", any(), any()) } coAnswers { first.await() }
        coEvery { routingStore.studioAppendFromUrl(any(), any(), "heroes1", any(), any()) } returns "content://heroes1"
        val vm = screenshotGridOnSgdb(perType = 2)   // grids1, grids2, heroes1, heroes2, …
        vm.toggleSelection(1)
        vm.toggleSelection(2)

        vm.handleGamepadAction(GamepadAction.HOME)
        assertTrue(vm.uiState.value.applyConfirmOpen)
        assertTrue(vm.uiState.value.queue.isEmpty())

        vm.handleGamepadAction(GamepadAction.SELECT)   // Apply is the first row
        advanceUntilIdle()

        assertFalse(vm.uiState.value.applyConfirmOpen)
        assertTrue(vm.uiState.value.selection.isEmpty())
        assertEquals(
            listOf(StudioQueueState.DOWNLOADING, StudioQueueState.QUEUED),
            vm.uiState.value.queue.map { it.state },
        )
        coVerify(exactly = 0) { routingStore.studioAppendFromUrl(any(), any(), "heroes1", any(), any()) }

        first.complete("content://grids2")
        advanceUntilIdle()

        assertEquals(
            listOf(StudioQueueState.ADDED, StudioQueueState.ADDED),
            vm.uiState.value.queue.map { it.state },
        )
        coVerify { routingStore.studioAppendFromUrl(1L, ArtworkKind.SCREENSHOT, "grids2", any(), "grids:2") }
        coVerify { routingStore.studioAppendFromUrl(1L, ArtworkKind.SCREENSHOT, "heroes1", any(), "heroes:1") }
    }

    @Test
    fun `a failed download is kept while the rest are added, then retried alone or removed`() = runTest(testDispatcher) {
        coEvery { routingStore.studioAppendFromUrl(any(), any(), "grids1", any(), any()) } throws IllegalStateException("boom")
        coEvery { routingStore.studioAppendFromUrl(any(), any(), "grids2", any(), any()) } returns "content://grids2"
        val vm = screenshotGridOnSgdb(perType = 2)
        vm.toggleSelection(0)
        vm.toggleSelection(1)

        vm.applyChanges()
        vm.resolveApplyConfirm(StudioApplyChoice.APPLY)
        advanceUntilIdle()

        assertEquals(StudioQueueSummary(added = 1, failed = 1, total = 2), vm.uiState.value.queueSummary)
        assertTrue(StudioAction.RETRY_FAILED in vm.uiState.value.availableActions)

        vm.retryFailed()
        advanceUntilIdle()
        coVerify(exactly = 2) { routingStore.studioAppendFromUrl(any(), any(), "grids1", any(), any()) }
        coVerify(exactly = 1) { routingStore.studioAppendFromUrl(any(), any(), "grids2", any(), any()) }

        vm.removeFailed()
        assertEquals(StudioQueueSummary(added = 1, total = 1), vm.uiState.value.queueSummary)
    }

    @Test
    fun `an added tile unchecks to be removed, and checks again to keep it`() = runTest(testDispatcher) {
        coEvery { routingStore.studioAppendFromUrl(any(), any(), any(), any(), any()) } returns "content://added"
        val vm = screenshotGridOnSgdb(perType = 1)
        vm.toggleSelection(0)
        vm.applyChanges()
        vm.resolveApplyConfirm(StudioApplyChoice.APPLY)
        advanceUntilIdle()
        val tile = vm.uiState.value.results[0]
        assertEquals(StudioTileMark.ADDED, vm.uiState.value.tileMarkOf(tile))

        vm.toggleSelection(0)
        assertEquals(StudioTileMark.TO_REMOVE, vm.uiState.value.tileMarkOf(tile))
        assertTrue(vm.uiState.value.selection.isEmpty())
        assertEquals(StudioQueueSummary(toRemove = 1, added = 1, total = 1), vm.uiState.value.queueSummary)

        vm.toggleSelection(0)
        assertEquals(StudioTileMark.ADDED, vm.uiState.value.tileMarkOf(tile))
        assertTrue(vm.uiState.value.removals.isEmpty())
    }

    @Test
    fun `stored assets start checked, and Apply removes the unchecked ones before adding new picks`() = runTest(testDispatcher) {
        val slots = listOf(
            com.playfieldportal.feature.artwork.store.StudioArtworkSlot(
                sortOrder = 0, documentUri = "content://s0", provider = "SteamGridDB",
                originUrl = "grids1", providerAssetId = "grids:1", sizeBytes = 1,
            ),
            com.playfieldportal.feature.artwork.store.StudioArtworkSlot(
                sortOrder = 1, documentUri = "content://s1", provider = "SteamGridDB",
                originUrl = "grids2", providerAssetId = "grids:2", sizeBytes = 1,
            ),
        )
        // Both files open: what the Studio marks and what a removal resolves positions from agree.
        coEvery { routingStore.studioAssetsOnDisk(1L, ArtworkKind.SCREENSHOT) } returns slots
        coEvery { routingStore.studioAssets(1L, ArtworkKind.SCREENSHOT) } returns slots
        coEvery { routingStore.deleteAssetAt(any(), any(), any()) } returns true
        val vm = screenshotGridOnSgdb(perType = 2)   // grids1, grids2, heroes1, heroes2, …
        val results = vm.uiState.value.results
        assertEquals(StudioTileMark.ADDED, vm.uiState.value.tileMarkOf(results[0]))
        assertEquals(StudioTileMark.NONE, vm.uiState.value.tileMarkOf(results[2]))

        vm.toggleSelection(0)
        vm.toggleSelection(1)
        vm.toggleSelection(2)
        assertEquals(StudioQueueSummary(toAdd = 1, toRemove = 2), vm.uiState.value.queueSummary)

        vm.handleGamepadAction(GamepadAction.HOME)
        vm.handleGamepadAction(GamepadAction.SELECT)   // Apply
        advanceUntilIdle()

        // Highest position first: deleting 0 first would move 1 down to 0.
        io.mockk.coVerifyOrder {
            routingStore.deleteAssetAt(1L, ArtworkKind.SCREENSHOT, 1)
            routingStore.deleteAssetAt(1L, ArtworkKind.SCREENSHOT, 0)
            routingStore.studioAppendFromUrl(1L, ArtworkKind.SCREENSHOT, "heroes1", any(), "heroes:1")
        }
        assertTrue(vm.uiState.value.removals.isEmpty())
        assertTrue(vm.uiState.value.selection.isEmpty())
    }

    // Found on device: a record whose file was lost read as checked while the gallery showed nothing.
    @Test
    fun `a stored record whose file is gone is not held, so its asset can be picked again`() = runTest(testDispatcher) {
        val lost = com.playfieldportal.feature.artwork.store.StudioArtworkSlot(
            sortOrder = 0, documentUri = "content://gone", provider = "SteamGridDB",
            originUrl = "grids1", providerAssetId = "grids:1", sizeBytes = 1,
        )
        coEvery { routingStore.studioAssets(1L, ArtworkKind.SCREENSHOT) } returns listOf(lost)
        coEvery { routingStore.studioAssetsOnDisk(1L, ArtworkKind.SCREENSHOT) } returns emptyList()
        val vm = screenshotGridOnSgdb(perType = 1)
        val tile = vm.uiState.value.results[0]

        assertEquals(StudioTileMark.NONE, vm.uiState.value.tileMarkOf(tile))
        vm.toggleSelection(0)
        assertEquals(StudioTileMark.PICKED, vm.uiState.value.tileMarkOf(tile))
    }

    @Test
    fun `Cancel or B in the apply confirmation keeps the changes and applies nothing`() = runTest(testDispatcher) {
        val vm = screenshotGridOnSgdb(perType = 2)
        vm.toggleSelection(0)

        vm.handleGamepadAction(GamepadAction.HOME)
        vm.handleGamepadAction(GamepadAction.HOME)   // a second START does not confirm
        assertTrue(vm.uiState.value.applyConfirmOpen)
        vm.handleGamepadAction(GamepadAction.BACK)
        assertFalse(vm.uiState.value.applyConfirmOpen)

        vm.runAction(StudioAction.APPLY_CHANGES)
        assertTrue(vm.uiState.value.applyConfirmOpen)
        vm.resolveApplyConfirm(StudioApplyChoice.CANCEL)
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.selection.size)
        assertTrue(vm.uiState.value.queue.isEmpty())
        coVerify(exactly = 0) { routingStore.studioAppendFromUrl(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { routingStore.deleteAssetAt(any(), any(), any()) }
    }

    @Test
    fun `the apply confirmation names what it adds and removes`() {
        assertEquals("Add 1 screenshot?", studioApplyTitle(ArtworkKind.SCREENSHOT, toAdd = 1, toRemove = 0))
        assertEquals("Remove 2 videos?", studioApplyTitle(ArtworkKind.VIDEO, toAdd = 0, toRemove = 2))
        assertEquals("Add 3 screenshots and remove 1?", studioApplyTitle(ArtworkKind.SCREENSHOT, toAdd = 3, toRemove = 1))
    }

    @Test
    fun `START no longer toggles the mature filter, and the menu still does`() = runTest(testDispatcher) {
        val vm = screenshotGridOnSgdb(perType = 1)

        vm.handleGamepadAction(GamepadAction.HOME)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.includeNsfw)

        vm.runAction(StudioAction.TOGGLE_MATURE)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.includeNsfw)
    }

    @Test
    fun `B from the categories with picks asks first, and Stay or Discard do what they say`() = runTest(testDispatcher) {
        val vm = screenshotGridOnSgdb(perType = 2)
        vm.toggleSelection(0)
        repeat(3) { vm.handleGamepadAction(GamepadAction.BACK) }   // grid → sources → categories → prompt

        assertTrue(vm.uiState.value.leavePromptOpen)
        assertFalse(vm.uiState.value.closed)

        vm.handleGamepadAction(GamepadAction.BACK)   // B in the prompt is Stay
        assertFalse(vm.uiState.value.leavePromptOpen)
        assertEquals(1, vm.uiState.value.selection.size)

        vm.handleGamepadAction(GamepadAction.BACK)
        vm.resolveLeavePrompt(StudioLeaveChoice.DISCARD)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.closed)
        assertTrue(vm.uiState.value.selection.isEmpty())
        coVerify(exactly = 0) { routingStore.studioAppendFromUrl(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `Apply and Close queues the picks and closes`() = runTest(testDispatcher) {
        val vm = screenshotGridOnSgdb(perType = 2)
        vm.toggleSelection(0)
        repeat(3) { vm.handleGamepadAction(GamepadAction.BACK) }

        vm.handleGamepadAction(GamepadAction.SELECT)   // Apply and Close is the first row
        advanceUntilIdle()

        assertTrue(vm.uiState.value.closed)
        coVerify(exactly = 1) { routingStore.studioAppendFromUrl(1L, ArtworkKind.SCREENSHOT, "grids1", any(), "grids:1") }
    }

    // ── Measured grid capacity (task L.1) ─────────────────────────────────────

    @Test
    fun `a capacity change keeps the focused result focused, on the page that now holds it`() =
        runTest(testDispatcher) {
            coEvery { steamGridDb.getArt(any(), any(), any(), any(), any()) } returns
                Result.success((1..30).map { SgdbArtItem(id = it.toLong(), url = "art$it") })
            val vm = loadedOn(StudioSource.STEAMGRIDDB)
            vm.handleGamepadAction(GamepadAction.SELECT)   // into the grid

            // 4 × 5 page 2 holds results 21–30; three steps right focuses art24 (absolute index 23).
            vm.nextPage()
            repeat(3) { vm.handleGamepadAction(GamepadAction.NAVIGATE_RIGHT) }
            assertEquals("art24", vm.uiState.value.let { it.results[it.gridIndex].url })

            vm.onGridMeasured(635f, 259f)   // Thor, landscape: 5 × 3 = 15 per page

            val state = vm.uiState.value
            assertEquals(5, state.gridColumns)
            assertEquals(3, state.gridRows)
            assertEquals(1, state.page)
            assertEquals(16, state.rangeStart)
            assertEquals(8, state.gridIndex)
            assertEquals("art24", state.results[state.gridIndex].url)
            assertEquals(StudioZone.GRID, state.zone)
        }

    @Test
    fun `D-pad up and down move by the measured column count`() = runTest(testDispatcher) {
        coEvery { steamGridDb.getArt(any(), any(), any(), any(), any()) } returns
            Result.success((1..30).map { SgdbArtItem(id = it.toLong(), url = "art$it") })
        val vm = loadedOn(StudioSource.STEAMGRIDDB)
        vm.onGridMeasured(635f, 259f)
        vm.handleGamepadAction(GamepadAction.SELECT)

        vm.handleGamepadAction(GamepadAction.NAVIGATE_DOWN)
        assertEquals(5, vm.uiState.value.gridIndex)
        vm.handleGamepadAction(GamepadAction.NAVIGATE_DOWN)
        assertEquals(10, vm.uiState.value.gridIndex)
        vm.handleGamepadAction(GamepadAction.NAVIGATE_DOWN)
        assertEquals("clamped at the page's last row", 10, vm.uiState.value.gridIndex)

        vm.handleGamepadAction(GamepadAction.NAVIGATE_UP)
        assertEquals(5, vm.uiState.value.gridIndex)
    }

    @Test
    fun `a tab change recomputes capacity from the last measured size`() = runTest(testDispatcher) {
        val vm = loadedOn(StudioSource.STEAMGRIDDB)
        vm.onGridMeasured(635f, 259f)

        vm.selectTab(STUDIO_TABS.indexOfFirst { it.kind == ArtworkKind.BOX_ART })
        advanceUntilIdle()

        assertEquals(7, vm.uiState.value.gridColumns)
        assertEquals(2, vm.uiState.value.gridRows)
    }

    @Test
    fun `skeletons fill the measured page`() = runTest(testDispatcher) {
        val slow = CompletableDeferred<List<SgdbArtItem>>()
        coEvery { theGamesDb.fetchGameInfo(any(), any()) } returns tgdb("tgdb-hero")
        coEvery { steamGridDb.getArt(any(), any(), any(), any(), any()) } coAnswers {
            Result.success(slow.await())
        }
        val vm = loadedOn(StudioSource.THEGAMESDB)
        vm.onGridMeasured(635f, 259f)

        vm.selectSource(vm.sourcesForTab().indexOf(StudioSource.STEAMGRIDDB))
        advanceUntilIdle()

        assertEquals(15, vm.uiState.value.skeletonCount)
        slow.complete(emptyList())
        advanceUntilIdle()
    }

    // ── Game match (task 2.3) ─────────────────────────────────────────────────

    @Test
    fun `a saved provider id is the match, with no lookup at all`() = runTest(testDispatcher) {
        coEvery { gameRepository.getById(1L) } returns game.copy(steamGridDbId = 77L)

        val vm = loadedOn(StudioSource.STEAMGRIDDB)

        val state = vm.uiState.value
        assertEquals(com.playfieldportal.feature.artwork.match.MatchProvider.STEAMGRIDDB, state.matchProvider)
        assertEquals(
            com.playfieldportal.feature.artwork.match.MatchTier.SAVED_PROVIDER_ID,
            state.match?.tier,
        )
        assertEquals("77", state.match?.candidate?.providerGameId)
        // SteamGridDB never searched. (Opening lands on ScreenScraper first, which has no saved id
        // here and now title-searches — so the check is scoped to the provider that had one.)
        coVerify(exactly = 0) {
            matchEvidence.searchByTitle(com.playfieldportal.feature.artwork.match.MatchProvider.STEAMGRIDDB, any(), any())
        }
    }

    @Test
    fun `an unmatched game says so instead of showing a stale title`() = runTest(testDispatcher) {
        val vm = loadedOn(StudioSource.STEAMGRIDDB)

        // No saved id, no crc, no storefront, and the title search returns nothing.
        assertEquals(null, vm.uiState.value.match)
        assertFalse(vm.uiState.value.matchResolving)
        assertFalse(vm.uiState.value.matchFailed)
    }

    @Test
    fun `a match search that fails says the provider didn't answer, not that there is no match`() = runTest(testDispatcher) {
        coEvery {
            matchEvidence.searchByTitle(com.playfieldportal.feature.artwork.match.MatchProvider.SCREENSCRAPER, any(), any())
        } throws com.playfieldportal.feature.artwork.api.SsSearchFailedException("No answer from ScreenScraper")

        val vm = loadedOn(StudioSource.SCREENSCRAPER)

        assertEquals(null, vm.uiState.value.match)
        assertTrue(vm.uiState.value.matchFailed)
        assertFalse(vm.uiState.value.matchResolving)
    }

    @Test
    fun `a provider that answers after a failed one clears the failure`() = runTest(testDispatcher) {
        coEvery {
            matchEvidence.searchByTitle(com.playfieldportal.feature.artwork.match.MatchProvider.SCREENSCRAPER, any(), any())
        } throws com.playfieldportal.feature.artwork.api.SsSearchFailedException("No answer from ScreenScraper")
        val vm = loadedOn(StudioSource.SCREENSCRAPER)
        assertTrue(vm.uiState.value.matchFailed)

        // SteamGridDB answers, with nothing: that is a plain "no match".
        vm.selectSource(vm.sourcesForTab().indexOf(StudioSource.STEAMGRIDDB))
        advanceUntilIdle()

        assertFalse(vm.uiState.value.matchFailed)
    }

    @Test
    fun `Change Match is offered only where there is something to pick from`() = runTest(testDispatcher) {
        // Every provider now has a multi-result title search, ScreenScraper's jeuRecherche included.
        assertTrue(loadedOn(StudioSource.STEAMGRIDDB).uiState.value.canChangeMatch)
        assertTrue(loadedOn(StudioSource.IGDB).uiState.value.canChangeMatch)
        assertTrue(loadedOn(StudioSource.THEGAMESDB).uiState.value.canChangeMatch)
        assertTrue(loadedOn(StudioSource.SCREENSCRAPER).uiState.value.canChangeMatch)
    }

    @Test
    fun `pressing Change Match on ScreenScraper opens the picker`() = runTest(testDispatcher) {
        val vm = loadedOn(StudioSource.SCREENSCRAPER)

        vm.onChangeMatchPressed()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.changeMatchOpen)
        coVerify {
            matchEvidence.searchByTitle(com.playfieldportal.feature.artwork.match.MatchProvider.SCREENSCRAPER, any(), any())
        }
    }

    /** Without a key TheGamesDB is disabled and skipped — never hidden, and never asked. */
    @Test
    fun `a keyless TheGamesDB stays listed but disabled, is refused, and is skipped by cycling`() =
        runTest(testDispatcher) {
            coEvery { theGamesDb.hasApiKey() } returns false

            val vm = viewModel()
            vm.load(1L)
            advanceUntilIdle()

            val sources = vm.sourcesForTab()
            val tgdb = sources.indexOf(StudioSource.THEGAMESDB)
            assertTrue("still listed", tgdb >= 0)
            assertTrue(StudioSource.THEGAMESDB in vm.uiState.value.unavailableSources)

            // Picking it directly explains instead of switching.
            vm.selectSource(tgdb)
            advanceUntilIdle()
            assertTrue(sources[vm.uiState.value.sourceIndex] != StudioSource.THEGAMESDB)
            assertTrue(vm.uiState.value.message?.contains("TheGamesDB") == true)

            // Cycling from the source before it steps straight over it.
            vm.selectSource(tgdb - 1)
            advanceUntilIdle()
            vm.cycleSource(+1)
            advanceUntilIdle()
            assertEquals(sources[tgdb + 1], sources[vm.uiState.value.sourceIndex])

            coVerify(exactly = 0) { theGamesDb.fetchGameInfo(any(), any()) }
        }

    /** The reported bug: a key entered in Settings never took effect for a game already opened. */
    @Test
    fun `a key added after the Studio was opened is picked up on the next open`() = runTest(testDispatcher) {
        coEvery { theGamesDb.hasApiKey() } returns false
        val vm = viewModel()
        vm.load(1L)
        advanceUntilIdle()
        assertTrue(StudioSource.THEGAMESDB in vm.uiState.value.unavailableSources)

        // The user adds the key in Settings, then reopens the Studio for the same game.
        coEvery { theGamesDb.hasApiKey() } returns true
        vm.load(1L)
        advanceUntilIdle()

        assertFalse(StudioSource.THEGAMESDB in vm.uiState.value.unavailableSources)
        vm.selectSource(vm.sourcesForTab().indexOf(StudioSource.THEGAMESDB))
        advanceUntilIdle()
        assertEquals(StudioSource.THEGAMESDB, vm.sourcesForTab()[vm.uiState.value.sourceIndex])
    }

    /** The reported case: IGDB had the game, and the Studio said "No IGDB match". */
    @Test
    fun `a unique exact IGDB title matches and the grid browses that game by id`() = runTest(testDispatcher) {
        coEvery {
            matchEvidence.searchByTitle(com.playfieldportal.feature.artwork.match.MatchProvider.IGDB, any(), any())
        } returns listOf(
            com.playfieldportal.feature.artwork.match.GameCandidate(
                provider = com.playfieldportal.feature.artwork.match.MatchProvider.IGDB,
                providerGameId = "1234",
                title = "Cr4sh Bandicoot",
            ),
        )
        coEvery { igdbApi.fetchGameInfoById(1234L) } returns igdb("igdb-by-id")

        val vm = loadedOn(StudioSource.IGDB)

        assertEquals("IGDB:1234", vm.uiState.value.match?.matchKey)
        assertEquals(listOf("igdb-by-id"), vm.uiState.value.results.map { it.url })
    }

    @Test
    fun `a unique exact TheGamesDB title matches and the grid browses that game by id`() = runTest(testDispatcher) {
        coEvery {
            matchEvidence.searchByTitle(com.playfieldportal.feature.artwork.match.MatchProvider.THEGAMESDB, any(), any())
        } returns listOf(
            com.playfieldportal.feature.artwork.match.GameCandidate(
                provider = com.playfieldportal.feature.artwork.match.MatchProvider.THEGAMESDB,
                providerGameId = "55",
                title = "Cr4sh Bandicoot",
            ),
        )
        coEvery { theGamesDb.fetchGameInfoById(55L) } returns tgdb("tgdb-by-id")

        val vm = loadedOn(StudioSource.THEGAMESDB)

        assertEquals("THEGAMESDB:55", vm.uiState.value.match?.matchKey)
        assertEquals(listOf("tgdb-by-id"), vm.uiState.value.results.map { it.url })
        // The match is scoped to the game's own platform, not searched across every system.
        coVerify { matchEvidence.searchByTitle(com.playfieldportal.feature.artwork.match.MatchProvider.THEGAMESDB, any(), "psx") }
    }

    /**
     * The reported case: the ScreenScraper grid identified the game by its ROM and showed its art,
     * while the match row — resolved against the game as first loaded — said "No ScreenScraper match".
     */
    @Test
    fun `a ScreenScraper browse that identifies the game brings the match row along`() = runTest(testDispatcher) {
        var catalogSavedIdentity = false
        coEvery { ssMediaCatalog.mediasFor(1L, any()) } coAnswers {
            catalogSavedIdentity = true
            listOf(com.playfieldportal.feature.artwork.api.SsCachedMedia(type = "box-2D", region = "us", url = "ss-box", format = "png"))
        }
        coEvery { gameRepository.getById(1L) } answers {
            if (catalogSavedIdentity) game.copy(ssId = 777L, romCrc32 = "ABCD1234") else game
        }

        val vm = viewModel()
        vm.load(1L)   // ICON0's first source is ScreenScraper
        advanceUntilIdle()

        assertEquals("SCREENSCRAPER:777", vm.uiState.value.match?.matchKey)
        assertEquals(listOf("ss-box"), vm.uiState.value.results.map { it.url })
    }

    /** A Windows install has no ROM: a title match must drive the grid without being saved as identity. */
    @Test
    fun `a ScreenScraper title match browses that game's media without saving it`() = runTest(testDispatcher) {
        coEvery {
            matchEvidence.searchByTitle(com.playfieldportal.feature.artwork.match.MatchProvider.SCREENSCRAPER, any(), any())
        } returns listOf(
            com.playfieldportal.feature.artwork.match.GameCandidate(
                provider = com.playfieldportal.feature.artwork.match.MatchProvider.SCREENSCRAPER,
                providerGameId = "555",
                title = "Cr4sh Bandicoot",
            ),
        )

        val vm = loadedOn(StudioSource.SCREENSCRAPER)

        assertEquals("SCREENSCRAPER:555", vm.uiState.value.match?.matchKey)
        coVerify { ssMediaCatalog.mediasFor(1L, 555L) }
        coVerify(exactly = 0) { gameRepository.updateProviderMatch(any(), any(), any()) }
    }

    /**
     * The reported case: a Windows install of Tactics Ogre. ScreenScraper's Windows-scoped search found
     * nothing, so the row said no match and Change Match said "No games found" whatever was typed.
     */
    @Test
    fun `an empty ScreenScraper platform search widens Change Match to every platform`() = runTest(testDispatcher) {
        coEvery { matchEvidence.searchScreenScraperOnAnyPlatform(any(), any()) } returns listOf(
            com.playfieldportal.feature.artwork.match.GameCandidate(
                provider = com.playfieldportal.feature.artwork.match.MatchProvider.SCREENSCRAPER,
                providerGameId = "1001",
                title = "Tactics Ogre: Reborn",
                platformName = "Switch",
            ),
        )
        val vm = loadedOn(StudioSource.SCREENSCRAPER)

        // The matcher itself never looks beyond the game's platform.
        assertEquals(null, vm.uiState.value.match)
        coVerify(exactly = 0) { matchEvidence.searchScreenScraperOnAnyPlatform(any(), any()) }

        vm.openChangeMatch()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(listOf("Switch"), state.changeMatchResults.map { it.platformName })
        assertTrue(state.changeMatchAcrossPlatforms)
        assertEquals(0, state.changeMatchIndex)
    }

    @Test
    fun `a ScreenScraper platform hit keeps Change Match on the game's own platform`() = runTest(testDispatcher) {
        coEvery {
            matchEvidence.searchByTitle(com.playfieldportal.feature.artwork.match.MatchProvider.SCREENSCRAPER, any(), any())
        } returns listOf(
            com.playfieldportal.feature.artwork.match.GameCandidate(
                provider = com.playfieldportal.feature.artwork.match.MatchProvider.SCREENSCRAPER,
                providerGameId = "555",
                title = "Crash Bandicoot",
            ),
        )
        val vm = loadedOn(StudioSource.SCREENSCRAPER)

        vm.openChangeMatch()
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.changeMatchResults.size)
        assertFalse(vm.uiState.value.changeMatchAcrossPlatforms)
        coVerify(exactly = 0) { matchEvidence.searchScreenScraperOnAnyPlatform(any(), any()) }
    }

    @Test
    fun `other providers never widen Change Match beyond the platform`() = runTest(testDispatcher) {
        val vm = loadedOn(StudioSource.IGDB)

        vm.openChangeMatch()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.changeMatchAcrossPlatforms)
        coVerify(exactly = 0) { matchEvidence.searchScreenScraperOnAnyPlatform(any(), any()) }
    }

    /** The device log showed ten identical ScreenScraper searches in four minutes. */
    @Test
    fun `re-resolving a match reuses the title search, and so does the picker`() = runTest(testDispatcher) {
        val vm = loadedOn(StudioSource.IGDB)
        val sources = vm.sourcesForTab()

        vm.selectSource(sources.indexOf(StudioSource.THEGAMESDB))
        advanceUntilIdle()
        vm.selectSource(sources.indexOf(StudioSource.IGDB))
        advanceUntilIdle()
        vm.openChangeMatch()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            matchEvidence.searchByTitle(com.playfieldportal.feature.artwork.match.MatchProvider.IGDB, any(), any())
        }
    }

    // ── One owner job for resolve, then browse (task M.1) ─────────────────────

    @Test
    fun `switching tabs keeps the match and never browses without it`() = runTest(testDispatcher) {
        coEvery {
            matchEvidence.searchByTitle(com.playfieldportal.feature.artwork.match.MatchProvider.THEGAMESDB, any(), any())
        } returns listOf(
            com.playfieldportal.feature.artwork.match.GameCandidate(
                provider = com.playfieldportal.feature.artwork.match.MatchProvider.THEGAMESDB,
                providerGameId = "55",
                title = "Cr4sh Bandicoot",
            ),
        )
        coEvery { theGamesDb.fetchGameInfoById(55L) } returns tgdb("tgdb-by-id")
        val vm = loadedOn(StudioSource.THEGAMESDB)

        // A tab change lands on the first source, so each tab is walked back to TheGamesDB.
        listOf(ArtworkKind.BOX_ART, ArtworkKind.LOGO).forEach { kind ->
            vm.selectTab(STUDIO_TABS.indexOfFirst { it.kind == kind })
            advanceUntilIdle()
            vm.selectSource(vm.sourcesForTab().indexOf(StudioSource.THEGAMESDB))
            advanceUntilIdle()
            assertEquals("THEGAMESDB:55", vm.uiState.value.match?.matchKey)
        }

        coVerify(exactly = 0) { theGamesDb.fetchGameInfo(any(), any()) }
        coVerify(exactly = 3) { theGamesDb.fetchGameInfoById(55L) }
        coVerify(exactly = 1) {
            matchEvidence.searchByTitle(com.playfieldportal.feature.artwork.match.MatchProvider.THEGAMESDB, any(), any())
        }
    }

    @Test
    fun `the grid waits on skeletons, not an empty result, while its match resolves`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        coEvery {
            matchEvidence.searchByTitle(com.playfieldportal.feature.artwork.match.MatchProvider.SCREENSCRAPER, any(), any())
        } coAnswers {
            gate.await()
            emptyList()
        }

        val vm = viewModel()
        vm.load(1L)   // ICON0's first source is ScreenScraper
        advanceUntilIdle()

        assertTrue(vm.uiState.value.matchResolving)
        assertTrue(vm.uiState.value.resultsLoading)
        // Only the ROM identity lookup that runs ahead of resolution (task M.3b); no browse yet.
        coVerify(exactly = 1) { ssMediaCatalog.mediasFor(1L, null) }
        coVerify(exactly = 0) { ssMediaCatalog.mediasFor(any(), isNull(inverse = true)) }

        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.matchResolving)
        assertFalse(vm.uiState.value.resultsLoading)
    }

    @Test
    fun `leaving a source cancels its running resolution`() = runTest(testDispatcher) {
        var cancelled = false
        coEvery {
            matchEvidence.searchByTitle(com.playfieldportal.feature.artwork.match.MatchProvider.SCREENSCRAPER, any(), any())
        } coAnswers {
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        val vm = viewModel()
        vm.load(1L)
        advanceUntilIdle()

        vm.selectSource(vm.sourcesForTab().indexOf(StudioSource.STEAMGRIDDB))
        advanceUntilIdle()

        assertTrue(cancelled)
        assertEquals(com.playfieldportal.feature.artwork.match.MatchProvider.STEAMGRIDDB, vm.uiState.value.matchProvider)
        assertFalse(vm.uiState.value.matchResolving)
    }

    @Test
    fun `returning to a source reuses its match without asking again`() = runTest(testDispatcher) {
        coEvery {
            matchEvidence.searchByTitle(com.playfieldportal.feature.artwork.match.MatchProvider.SCREENSCRAPER, any(), any())
        } returns listOf(ssCandidate("555", "Cr4sh Bandicoot"))
        val vm = viewModel()
        vm.load(1L)
        advanceUntilIdle()
        val sources = vm.sourcesForTab()
        vm.selectSource(sources.indexOf(StudioSource.STEAMGRIDDB))
        advanceUntilIdle()

        vm.selectSource(sources.indexOf(StudioSource.SCREENSCRAPER))

        // Known at once: no "Matching…" and no "No ScreenScraper match" flash while it re-resolves.
        assertFalse(vm.uiState.value.matchResolving)
        assertEquals("SCREENSCRAPER:555", vm.uiState.value.match?.matchKey)
        advanceUntilIdle()
        coVerify(exactly = 1) {
            matchEvidence.searchByTitle(com.playfieldportal.feature.artwork.match.MatchProvider.SCREENSCRAPER, any(), any())
        }
    }

    @Test
    fun `a failed resolution is not remembered, so the next visit asks again`() = runTest(testDispatcher) {
        var calls = 0
        coEvery {
            matchEvidence.searchByTitle(com.playfieldportal.feature.artwork.match.MatchProvider.SCREENSCRAPER, any(), any())
        } coAnswers {
            if (++calls == 1) throw com.playfieldportal.feature.artwork.api.SsSearchFailedException("No answer")
            listOf(ssCandidate("555", "Cr4sh Bandicoot"))
        }
        val vm = viewModel()
        vm.load(1L)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.matchFailed)

        val sources = vm.sourcesForTab()
        vm.selectSource(sources.indexOf(StudioSource.STEAMGRIDDB))
        advanceUntilIdle()
        vm.selectSource(sources.indexOf(StudioSource.SCREENSCRAPER))
        advanceUntilIdle()

        assertEquals(2, calls)
        assertFalse(vm.uiState.value.matchFailed)
        assertEquals("SCREENSCRAPER:555", vm.uiState.value.match?.matchKey)
    }

    @Test
    fun `a new query resolves again`() = runTest(testDispatcher) {
        val vm = loadedOn(StudioSource.IGDB)

        vm.openSearch()
        vm.onQueryDraftChanged("Crash Bandicoot")
        vm.submitSearch()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            matchEvidence.searchByTitle(com.playfieldportal.feature.artwork.match.MatchProvider.IGDB, "Crash Bandicoot", any())
        }
    }

    @Test
    fun `reopening the Studio resolves afresh`() = runTest(testDispatcher) {
        val vm = loadedOn(StudioSource.IGDB)
        val sources = vm.sourcesForTab()

        vm.load(1L)
        advanceUntilIdle()
        vm.selectSource(sources.indexOf(StudioSource.THEGAMESDB))
        advanceUntilIdle()
        vm.selectSource(sources.indexOf(StudioSource.IGDB))
        advanceUntilIdle()

        coVerify(exactly = 2) {
            matchEvidence.searchByTitle(com.playfieldportal.feature.artwork.match.MatchProvider.IGDB, any(), any())
        }
    }

    // ── One ScreenScraper identity lookup, not two (task M.3b) ────────────────

    @Test
    fun `a ROM game's ScreenScraper identity comes from one lookup`() = runTest(testDispatcher) {
        var catalogSavedIdentity = false
        coEvery { gameRepository.getById(1L) } answers {
            if (catalogSavedIdentity) game.copy(ssId = 555L, romCrc32 = "ABCD1234") else game.copy(romCrc32 = "ABCD1234")
        }
        coEvery { ssMediaCatalog.mediasFor(1L, null) } coAnswers {
            catalogSavedIdentity = true
            listOf(com.playfieldportal.feature.artwork.api.SsCachedMedia(type = "mixrbv2", region = "us", url = "ss-icon", format = "png"))
        }

        val vm = viewModel()
        vm.load(1L)   // ICON0's first source is ScreenScraper
        advanceUntilIdle()

        assertEquals("SCREENSCRAPER:555", vm.uiState.value.match?.matchKey)
        assertEquals(com.playfieldportal.feature.artwork.match.MatchTier.SAVED_PROVIDER_ID, vm.uiState.value.match?.tier)
        assertEquals(listOf("ss-icon"), vm.uiState.value.results.map { it.url })
        // One jeuInfos in all: no checksum lookup, no title search, and the browse reused the answer.
        coVerify(exactly = 0) { matchEvidence.candidateByRomHash(any(), any(), any()) }
        coVerify(exactly = 0) {
            matchEvidence.searchByTitle(com.playfieldportal.feature.artwork.match.MatchProvider.SCREENSCRAPER, any(), any())
        }
        coVerify(exactly = 1) { ssMediaCatalog.mediasFor(any(), any()) }
    }

    @Test
    fun `when the catalog finds nothing, the title search runs and the checksum is not asked again`() =
        runTest(testDispatcher) {
            coEvery { gameRepository.getById(1L) } returns game.copy(romCrc32 = "ABCD1234")
            coEvery { ssMediaCatalog.mediasFor(1L, null) } returns null

            val vm = viewModel()
            vm.load(1L)
            advanceUntilIdle()

            assertEquals(null, vm.uiState.value.match)
            coVerify(exactly = 0) { matchEvidence.candidateByRomHash(any(), any(), any()) }
            coVerify(exactly = 1) {
                matchEvidence.searchByTitle(com.playfieldportal.feature.artwork.match.MatchProvider.SCREENSCRAPER, any(), any())
            }
            // The browse did not repeat the lookup that just missed.
            coVerify(exactly = 1) { ssMediaCatalog.mediasFor(any(), any()) }
        }

    // ── Background resolution on open (task M.6) ──────────────────────────────

    private fun igdbExact(id: String) = listOf(
        com.playfieldportal.feature.artwork.match.GameCandidate(
            provider = com.playfieldportal.feature.artwork.match.MatchProvider.IGDB,
            providerGameId = id,
            title = "Cr4sh Bandicoot",
        ),
    )

    @Test
    fun `opening the Studio resolves SteamGridDB and IGDB in the background, never TheGamesDB`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            vm.load(1L)   // lands on ScreenScraper
            advanceUntilIdle()

            coVerify(exactly = 1) {
                matchEvidence.searchByTitle(com.playfieldportal.feature.artwork.match.MatchProvider.STEAMGRIDDB, any(), any())
            }
            coVerify(exactly = 1) {
                matchEvidence.searchByTitle(com.playfieldportal.feature.artwork.match.MatchProvider.IGDB, any(), any())
            }
            coVerify(exactly = 0) {
                matchEvidence.searchByTitle(com.playfieldportal.feature.artwork.match.MatchProvider.THEGAMESDB, any(), any())
            }
            // ScreenScraper only by its own visit, and nothing browsed for the background providers.
            coVerify(exactly = 1) {
                matchEvidence.searchByTitle(com.playfieldportal.feature.artwork.match.MatchProvider.SCREENSCRAPER, any(), any())
            }
            coVerify(exactly = 0) { steamGridDb.getArt(any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { igdbApi.fetchGameInfoById(any()) }
        }

    @Test
    fun `switching to a source resolved in the background asks nothing more`() = runTest(testDispatcher) {
        coEvery {
            matchEvidence.searchByTitle(com.playfieldportal.feature.artwork.match.MatchProvider.IGDB, any(), any())
        } returns igdbExact("1234")
        val vm = viewModel()
        vm.load(1L)
        advanceUntilIdle()

        vm.selectSource(vm.sourcesForTab().indexOf(StudioSource.IGDB))

        // Known before the dispatcher runs: no "Matching…" at all.
        assertFalse(vm.uiState.value.matchResolving)
        assertEquals("IGDB:1234", vm.uiState.value.match?.matchKey)
        advanceUntilIdle()
        coVerify(exactly = 1) {
            matchEvidence.searchByTitle(com.playfieldportal.feature.artwork.match.MatchProvider.IGDB, any(), any())
        }
    }

    @Test
    fun `a visit mid-flight joins the background resolution instead of asking again`() = runTest(testDispatcher) {
        coEvery { gameRepository.getById(1L) } returns game.copy(storefront = "STEAM", storefrontGameId = "620")
        val gate = CompletableDeferred<Unit>()
        coEvery {
            matchEvidence.candidateByStorefront(com.playfieldportal.feature.artwork.match.MatchProvider.STEAMGRIDDB, "STEAM", "620")
        } coAnswers {
            gate.await()
            sgdbCandidate("9620", "Portal 2")
        }
        val vm = viewModel()
        vm.load(1L)
        advanceUntilIdle()

        vm.selectSource(vm.sourcesForTab().indexOf(StudioSource.STEAMGRIDDB))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.matchResolving)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("STEAMGRIDDB:9620", vm.uiState.value.match?.matchKey)
        assertFalse(vm.uiState.value.matchResolving)
        // The storefront lookup is not a title search, so nothing else would have shared it.
        coVerify(exactly = 1) {
            matchEvidence.candidateByStorefront(com.playfieldportal.feature.artwork.match.MatchProvider.STEAMGRIDDB, any(), any())
        }
    }

    @Test
    fun `a background resolution that fails does not stop the other`() = runTest(testDispatcher) {
        coEvery {
            matchEvidence.searchByTitle(com.playfieldportal.feature.artwork.match.MatchProvider.STEAMGRIDDB, any(), any())
        } throws IllegalStateException("SteamGridDB is down")
        coEvery {
            matchEvidence.searchByTitle(com.playfieldportal.feature.artwork.match.MatchProvider.IGDB, any(), any())
        } returns igdbExact("1234")
        val vm = viewModel()
        vm.load(1L)
        advanceUntilIdle()
        val sources = vm.sourcesForTab()

        vm.selectSource(sources.indexOf(StudioSource.IGDB))
        assertEquals("IGDB:1234", vm.uiState.value.match?.matchKey)
        advanceUntilIdle()

        // The failure was not remembered: visiting SteamGridDB asks again, and says it didn't answer.
        vm.selectSource(sources.indexOf(StudioSource.STEAMGRIDDB))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.matchFailed)
        // At least the background's search and the visit's own. (The browse's first-hit search asks
        // once more, since a failure is never remembered anywhere.)
        coVerify(atLeast = 2) {
            matchEvidence.searchByTitle(com.playfieldportal.feature.artwork.match.MatchProvider.STEAMGRIDDB, any(), any())
        }
    }

    @Test
    fun `closing the Studio cancels background resolutions`() = runTest(testDispatcher) {
        var cancelled = 0
        coEvery {
            matchEvidence.searchByTitle(com.playfieldportal.feature.artwork.match.MatchProvider.IGDB, any(), any())
        } coAnswers {
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                cancelled++
                throw e
            }
        }
        val vm = viewModel()
        vm.load(1L)
        advanceUntilIdle()

        vm.close()
        advanceUntilIdle()

        assertEquals(1, cancelled)
    }

    // ── SteamGridDB browses by the match (task M.2) ───────────────────────────

    private fun sgdbCandidate(id: String, title: String) =
        com.playfieldportal.feature.artwork.match.GameCandidate(
            provider = com.playfieldportal.feature.artwork.match.MatchProvider.STEAMGRIDDB,
            providerGameId = id,
            title = title,
        )

    @Test
    fun `SteamGridDB searches once however many tabs are browsed`() = runTest(testDispatcher) {
        val vm = loadedOn(StudioSource.STEAMGRIDDB)

        listOf(ArtworkKind.BOX_ART, ArtworkKind.HERO).forEach { kind ->
            vm.selectTab(STUDIO_TABS.indexOfFirst { it.kind == kind })
            advanceUntilIdle()
            vm.selectSource(vm.sourcesForTab().indexOf(StudioSource.STEAMGRIDDB))
            advanceUntilIdle()
        }

        coVerify(exactly = 0) { steamGridDb.searchGame(any()) }
        coVerify(exactly = 1) {
            matchEvidence.searchByTitle(com.playfieldportal.feature.artwork.match.MatchProvider.STEAMGRIDDB, any(), any())
        }
        // ICON0 and BOX ART browse grids, HERO browses heroes: all three from the one hit.
        coVerify(exactly = 2) { steamGridDb.getArt(77L, SgdbArtType.GRID, any(), any(), any()) }
        coVerify(exactly = 1) { steamGridDb.getArt(77L, SgdbArtType.HERO, any(), any(), any()) }
    }

    @Test
    fun `an ambiguous SteamGridDB title still browses its first hit`() = runTest(testDispatcher) {
        // Two exact titles: the matcher calls that a miss, but the grid still has something to show.
        coEvery {
            matchEvidence.searchByTitle(com.playfieldportal.feature.artwork.match.MatchProvider.STEAMGRIDDB, any(), any())
        } returns listOf(sgdbCandidate("501", "Cr4sh Bandicoot"), sgdbCandidate("502", "Cr4sh Bandicoot"))

        val vm = loadedOn(StudioSource.STEAMGRIDDB)

        assertEquals(null, vm.uiState.value.match)
        coVerify { steamGridDb.getArt(501L, any(), any(), any(), any()) }
        coVerify(exactly = 0) { steamGridDb.getArt(502L, any(), any(), any(), any()) }
    }

    @Test
    fun `a typed query browses its own hit, not the saved id`() = runTest(testDispatcher) {
        coEvery { gameRepository.getById(1L) } returns game.copy(steamGridDbId = 77L)
        coEvery {
            matchEvidence.searchByTitle(com.playfieldportal.feature.artwork.match.MatchProvider.STEAMGRIDDB, "Spyro", any())
        } returns listOf(sgdbCandidate("88", "Spyro the Dragon"))
        val vm = loadedOn(StudioSource.STEAMGRIDDB)
        coVerify { steamGridDb.getArt(77L, any(), any(), any(), any()) }

        vm.openSearch()
        vm.onQueryDraftChanged("Spyro")
        vm.submitSearch()
        advanceUntilIdle()

        coVerify { steamGridDb.getArt(88L, any(), any(), any(), any()) }
        coVerify(exactly = 0) { steamGridDb.searchGame(any()) }
    }

    private fun ssCandidate(id: String, title: String, platformName: String? = null) =
        com.playfieldportal.feature.artwork.match.GameCandidate(
            provider = com.playfieldportal.feature.artwork.match.MatchProvider.SCREENSCRAPER,
            providerGameId = id,
            title = title,
            platformName = platformName,
        )

    /** A Windows platform search has found nothing for any title, so the picker skips it. */
    @Test
    fun `a Windows game's Change Match asks every platform once, with no Windows-only search first`() =
        runTest(testDispatcher) {
            coEvery { gameRepository.getById(1L) } returns game.copy(platformId = "windows")
            every { matchEvidence.searchesEveryPlatformFirst(any(), "windows") } returns true
            coEvery { matchEvidence.searchScreenScraperOnAnyPlatform(any(), "windows") } returns
                listOf(ssCandidate("478505", "Tactics Ogre: Reborn", "Playstation 5"))
            val vm = loadedOn(StudioSource.SCREENSCRAPER)

            vm.openChangeMatch()
            advanceUntilIdle()

            // The matcher's own Windows search is the only one; the picker added none.
            coVerify(exactly = 1) {
                matchEvidence.searchByTitle(com.playfieldportal.feature.artwork.match.MatchProvider.SCREENSCRAPER, any(), "windows")
            }
            coVerify(exactly = 1) { matchEvidence.searchScreenScraperOnAnyPlatform(any(), "windows") }
            assertEquals(1, vm.uiState.value.changeMatchResults.size)
            assertTrue(vm.uiState.value.changeMatchAcrossPlatforms)
        }

    @Test
    fun `searching the same title again in the picker is answered from memory`() = runTest(testDispatcher) {
        coEvery { matchEvidence.searchScreenScraperOnAnyPlatform(any(), any()) } returns
            listOf(ssCandidate("425726", "Tactics Ogre - Reborn", "Switch"))
        val vm = loadedOn(StudioSource.SCREENSCRAPER)

        vm.openChangeMatch()
        advanceUntilIdle()
        vm.submitChangeMatch()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            matchEvidence.searchByTitle(com.playfieldportal.feature.artwork.match.MatchProvider.SCREENSCRAPER, any(), any())
        }
        coVerify(exactly = 1) { matchEvidence.searchScreenScraperOnAnyPlatform(any(), any()) }
        assertEquals(1, vm.uiState.value.changeMatchResults.size)
    }

    /** On device a timeout read "No games found", which was not true. */
    @Test
    fun `a failed picker search says so, and Search asks again`() = runTest(testDispatcher) {
        var calls = 0
        coEvery { matchEvidence.searchScreenScraperOnAnyPlatform(any(), any()) } coAnswers {
            if (++calls == 1) throw com.playfieldportal.feature.artwork.api.SsSearchFailedException("No answer from ScreenScraper")
            listOf(ssCandidate("425726", "Tactics Ogre - Reborn", "Switch"))
        }
        val vm = loadedOn(StudioSource.SCREENSCRAPER)

        vm.openChangeMatch()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.changeMatchError != null)
        assertTrue(vm.uiState.value.changeMatchResults.isEmpty())

        vm.submitChangeMatch()
        advanceUntilIdle()

        assertEquals(null, vm.uiState.value.changeMatchError)
        assertEquals(1, vm.uiState.value.changeMatchResults.size)
        assertEquals(2, calls)
    }

    /** On device a typed search waited behind two older ones for ScreenScraper's single slot. */
    @Test
    fun `a new picker search cancels the one it replaces`() = runTest(testDispatcher) {
        var firstCancelled = false
        coEvery { matchEvidence.searchScreenScraperOnAnyPlatform("cr4sh bandicoot (u) [!]", any()) } coAnswers {
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                firstCancelled = true
                throw e
            }
        }
        coEvery { matchEvidence.searchScreenScraperOnAnyPlatform("Crash", any()) } returns
            listOf(ssCandidate("1", "Crash Bandicoot", "Playstation"))
        val vm = loadedOn(StudioSource.SCREENSCRAPER)

        vm.openChangeMatch()
        advanceUntilIdle()
        vm.onChangeMatchDraftChanged("Crash")
        vm.submitChangeMatch()
        advanceUntilIdle()

        assertTrue(firstCancelled)
        assertEquals(listOf("Crash Bandicoot"), vm.uiState.value.changeMatchResults.map { it.title })
    }

    @Test
    fun `closing the picker cancels its search`() = runTest(testDispatcher) {
        var cancelled = false
        coEvery { matchEvidence.searchScreenScraperOnAnyPlatform(any(), any()) } coAnswers {
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        val vm = loadedOn(StudioSource.SCREENSCRAPER)
        vm.openChangeMatch()
        advanceUntilIdle()

        vm.cancelChangeMatch()
        advanceUntilIdle()

        assertTrue(cancelled)
        assertFalse(vm.uiState.value.changeMatchLoading)
    }

    /**
     * A browse cancelled by a source switch used to be cached as an empty page: the provider call
     * swallowed the CancellationException and returned null, loadResults stored that under the
     * request's key, and coming back showed "No results" without ever asking again.
     */
    @Test
    fun `a browse cancelled by a source switch is never cached as No results`() = runTest(testDispatcher) {
        coEvery { theGamesDb.fetchGameInfo(any(), any()) } returns tgdb("tgdb-hero")
        val never = CompletableDeferred<Unit>()
        var igdbCalls = 0
        coEvery { igdbApi.fetchGameInfo(any(), any()) } coAnswers {
            igdbCalls++
            if (igdbCalls == 1) {
                // Exactly what the old IgdbApi did: cancellation caught, "nothing found" returned.
                runCatching { never.await() }
                null
            } else {
                igdb("igdb-hero")
            }
        }

        val vm = loadedOn(StudioSource.THEGAMESDB)
        val sources = vm.sourcesForTab()
        vm.selectSource(sources.indexOf(StudioSource.IGDB))
        advanceUntilIdle()
        assertTrue("IGDB should still be in flight", vm.uiState.value.resultsLoading)

        vm.selectSource(sources.indexOf(StudioSource.THEGAMESDB))
        advanceUntilIdle()
        vm.selectSource(sources.indexOf(StudioSource.IGDB))
        advanceUntilIdle()

        assertEquals(listOf("igdb-hero"), vm.uiState.value.results.map { it.url })
        assertEquals(2, igdbCalls)
    }

    @Test
    fun `confirming a match persists exactly one provider id and repoints the browse`() = runTest(testDispatcher) {
        coEvery { matchEvidence.searchByTitle(any(), any(), any()) } returns listOf(
            com.playfieldportal.feature.artwork.match.GameCandidate(
                provider = com.playfieldportal.feature.artwork.match.MatchProvider.STEAMGRIDDB,
                providerGameId = "9001",
                title = "Crash Bandicoot",
            ),
        )
        val vm = loadedOn(StudioSource.STEAMGRIDDB)

        vm.openChangeMatch()
        advanceUntilIdle()
        vm.confirmMatch(0)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("Crash Bandicoot", state.matchTitle)
        assertTrue(state.matchIsConfirmed)
        assertFalse(state.changeMatchOpen)
        // One provider column, named explicitly — never a blanket write over all four.
        coVerify { gameRepository.updateProviderMatch(1L, "STEAMGRIDDB", 9001L) }
        // And the grid now asks SteamGridDB about THAT game, not the one its own search picked.
        coVerify { steamGridDb.getArt(9001L, any(), any(), any(), any()) }
    }

    @Test
    fun `forgetting a match clears the id and deletes nothing`() = runTest(testDispatcher) {
        coEvery { matchEvidence.searchByTitle(any(), any(), any()) } returns listOf(
            com.playfieldportal.feature.artwork.match.GameCandidate(
                provider = com.playfieldportal.feature.artwork.match.MatchProvider.STEAMGRIDDB,
                providerGameId = "9001",
                title = "Crash Bandicoot",
            ),
        )
        val vm = loadedOn(StudioSource.STEAMGRIDDB)
        vm.openChangeMatch()
        advanceUntilIdle()
        vm.confirmMatch(0)
        advanceUntilIdle()

        vm.forgetMatch()
        advanceUntilIdle()

        coVerify { gameRepository.updateProviderMatch(1L, "STEAMGRIDDB", null) }
        assertFalse(vm.uiState.value.matchIsConfirmed)
        // Forgetting who a game is must never cost the user an asset.
        coVerify(exactly = 0) { artworkStore.deleteAll() }
    }

    @Test
    fun `the match is part of the request key, so switching match refetches`() = runTest(testDispatcher) {
        coEvery { matchEvidence.searchByTitle(any(), any(), any()) } returns listOf(
            com.playfieldportal.feature.artwork.match.GameCandidate(
                provider = com.playfieldportal.feature.artwork.match.MatchProvider.STEAMGRIDDB,
                providerGameId = "9001",
                title = "Crash Bandicoot",
            ),
        )
        val vm = loadedOn(StudioSource.STEAMGRIDDB)
        val before = vm.uiState.value.match?.matchKey

        vm.openChangeMatch()
        advanceUntilIdle()
        vm.confirmMatch(0)
        advanceUntilIdle()

        assertEquals("STEAMGRIDDB:9001", vm.uiState.value.match?.matchKey)
        assertTrue(before != vm.uiState.value.match?.matchKey)
    }

    /** Opening the picker used to drop the in-flight resolution without clearing its flag. */
    @Test
    fun `opening Change Match while a match resolves never leaves the row on Matching`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        coEvery {
            matchEvidence.searchByTitle(com.playfieldportal.feature.artwork.match.MatchProvider.IGDB, any(), any())
        } coAnswers {
            if (++calls == 1) gate.await()
            emptyList()
        }
        val vm = loadedOn(StudioSource.IGDB)
        assertTrue("the first resolution is still out", vm.uiState.value.matchResolving)

        vm.openChangeMatch()
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.matchResolving)
    }

    @Test
    fun `confirming a match mid-resolution clears the spinner and keeps the user's choice`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        coEvery {
            matchEvidence.searchByTitle(com.playfieldportal.feature.artwork.match.MatchProvider.STEAMGRIDDB, any(), any())
        } coAnswers {
            if (++calls == 1) {
                gate.await()
                emptyList()
            } else {
                twoCandidates()
            }
        }
        val vm = loadedOn(StudioSource.STEAMGRIDDB)
        // The seeded search would share the resolution's own request, still out, so pick from
        // another title's search instead.
        vm.openChangeMatch()
        vm.onChangeMatchDraftChanged("Crash")
        vm.submitChangeMatch()
        advanceUntilIdle()

        vm.confirmMatch(0)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.matchResolving)

        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue("the stale resolution must not replace the choice", vm.uiState.value.matchIsConfirmed)
        assertEquals("STEAMGRIDDB:9001", vm.uiState.value.match?.matchKey)
    }

    // ── Change Match with a controller ────────────────────────────────────────

    private fun twoCandidates() = listOf(
        com.playfieldportal.feature.artwork.match.GameCandidate(
            provider = com.playfieldportal.feature.artwork.match.MatchProvider.STEAMGRIDDB,
            providerGameId = "9001",
            title = "Crash Bandicoot",
        ),
        com.playfieldportal.feature.artwork.match.GameCandidate(
            provider = com.playfieldportal.feature.artwork.match.MatchProvider.STEAMGRIDDB,
            providerGameId = "9002",
            title = "Crash Bandicoot 2",
        ),
    )

    private suspend fun kotlinx.coroutines.test.TestScope.changeMatchOpenWithResults(): ArtworkStudioViewModel {
        coEvery { matchEvidence.searchByTitle(any(), any(), any()) } returns twoCandidates()
        val vm = loadedOn(StudioSource.STEAMGRIDDB)
        vm.openChangeMatch()
        advanceUntilIdle()
        return vm
    }

    /** The reported bug: the picker could not be driven by the pad at all. */
    @Test
    fun `the Change Match picker can be walked and confirmed with the controller alone`() = runTest(testDispatcher) {
        val vm = changeMatchOpenWithResults()

        // Opens on the first candidate, with the keyboard closed.
        assertEquals(0, vm.uiState.value.changeMatchIndex)
        assertFalse(vm.uiState.value.changeMatchEditing)

        vm.handleGamepadAction(GamepadAction.NAVIGATE_DOWN)
        assertEquals(1, vm.uiState.value.changeMatchIndex)
        vm.handleGamepadAction(GamepadAction.NAVIGATE_DOWN)
        assertEquals("the cursor clamps at the last candidate", 1, vm.uiState.value.changeMatchIndex)

        vm.handleGamepadAction(GamepadAction.SELECT)
        advanceUntilIdle()

        coVerify { gameRepository.updateProviderMatch(1L, "STEAMGRIDDB", 9002L) }
        assertFalse(vm.uiState.value.changeMatchOpen)
    }

    @Test
    fun `UP from the first candidate reaches the title field, where A edits instead of confirming`() =
        runTest(testDispatcher) {
            val vm = changeMatchOpenWithResults()

            vm.handleGamepadAction(GamepadAction.NAVIGATE_UP)
            assertEquals(-1, vm.uiState.value.changeMatchIndex)
            vm.handleGamepadAction(GamepadAction.NAVIGATE_UP)
            assertEquals("the field is the top stop", -1, vm.uiState.value.changeMatchIndex)

            vm.handleGamepadAction(GamepadAction.SELECT)

            assertTrue(vm.uiState.value.changeMatchEditing)
            assertTrue(vm.uiState.value.changeMatchOpen)
            coVerify(exactly = 0) { gameRepository.updateProviderMatch(any(), any(), any()) }
        }

    @Test
    fun `Square edits the title from anywhere in the picker`() = runTest(testDispatcher) {
        val vm = changeMatchOpenWithResults()

        vm.handleGamepadAction(GamepadAction.CHANGE_SORT)

        assertTrue(vm.uiState.value.changeMatchEditing)
        assertEquals(-1, vm.uiState.value.changeMatchIndex)
    }

    @Test
    fun `Back leaves editing first, then closes the picker without writing`() = runTest(testDispatcher) {
        val vm = changeMatchOpenWithResults()
        vm.handleGamepadAction(GamepadAction.CHANGE_SORT)
        assertTrue(vm.uiState.value.changeMatchEditing)

        vm.handleGamepadAction(GamepadAction.BACK)
        assertFalse(vm.uiState.value.changeMatchEditing)
        assertTrue("the first Back only leaves the field", vm.uiState.value.changeMatchOpen)

        vm.handleGamepadAction(GamepadAction.BACK)
        assertFalse(vm.uiState.value.changeMatchOpen)
        coVerify(exactly = 0) { gameRepository.updateProviderMatch(any(), any(), any()) }
    }

    @Test
    fun `submitting a typed title ends editing and lands on the first candidate`() = runTest(testDispatcher) {
        val vm = changeMatchOpenWithResults()
        vm.handleGamepadAction(GamepadAction.CHANGE_SORT)

        vm.onChangeMatchDraftChanged("Crash")
        vm.submitChangeMatch()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.changeMatchEditing)
        assertEquals(0, vm.uiState.value.changeMatchIndex)
    }

    // ── Reaching Change Match / Forget Match with a controller (task 2.4) ─────
    //
    // Every test here drives the ViewModel ONLY through handleGamepadAction. The picker tests above
    // open it by calling openChangeMatch() directly, which is how the row's buttons shipped
    // touch-only without a single test failing.

    @Test
    fun `an unmatched game with no artwork reaches Change Match from Triangle`() = runTest(testDispatcher) {
        val vm = loadedOn(StudioSource.IGDB)
        assertEquals(null, vm.uiState.value.matchTitle)
        assertEquals(null, vm.uiState.value.currentUri)

        vm.handleGamepadAction(GamepadAction.OPEN_CONTEXT_MENU)
        advanceUntilIdle()

        assertTrue("Triangle must open even with no artwork", vm.uiState.value.actionsOpen)
        pickFromMenu(vm, StudioAction.CHANGE_MATCH)

        assertTrue(vm.uiState.value.changeMatchOpen)
        assertFalse("the picker replaces the menu", vm.uiState.value.actionsOpen)
    }

    @Test
    fun `Forget Match is offered only once a match is confirmed, and clears it`() = runTest(testDispatcher) {
        coEvery { matchEvidence.searchByTitle(any(), any(), any()) } returns twoCandidates()
        val vm = loadedOn(StudioSource.STEAMGRIDDB)

        vm.handleGamepadAction(GamepadAction.OPEN_CONTEXT_MENU)
        advanceUntilIdle()
        assertFalse(StudioAction.FORGET_MATCH in vm.uiState.value.availableActions)

        // Confirm the top candidate: Change Match from the menu, then A on the first result.
        pickFromMenu(vm, StudioAction.CHANGE_MATCH)
        vm.handleGamepadAction(GamepadAction.SELECT)
        advanceUntilIdle()
        coVerify { gameRepository.updateProviderMatch(1L, "STEAMGRIDDB", 9001L) }

        vm.handleGamepadAction(GamepadAction.OPEN_CONTEXT_MENU)
        advanceUntilIdle()
        pickFromMenu(vm, StudioAction.FORGET_MATCH)

        coVerify { gameRepository.updateProviderMatch(1L, "STEAMGRIDDB", null) }
        assertFalse(vm.uiState.value.matchIsConfirmed)
        assertFalse(vm.uiState.value.actionsOpen)
    }

    @Test
    fun `on SteamGridDB the menu lists the mature filter, then Change Match`() = runTest(testDispatcher) {
        val vm = loadedOn(StudioSource.STEAMGRIDDB)

        vm.handleGamepadAction(GamepadAction.OPEN_CONTEXT_MENU)
        advanceUntilIdle()

        val actions = vm.uiState.value.availableActions
        assertTrue(StudioAction.TOGGLE_MATURE in actions)
        assertEquals(actions.indexOf(StudioAction.TOGGLE_MATURE) + 1, actions.indexOf(StudioAction.CHANGE_MATCH))
    }

    @Test
    fun `a source with no match provider and no artwork still opens no menu`() = runTest(testDispatcher) {
        val vm = loadedOn(StudioSource.LOCAL)
        assertEquals(null, vm.uiState.value.matchProvider)

        vm.handleGamepadAction(GamepadAction.OPEN_CONTEXT_MENU)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.actionsOpen)
    }

    // ── Every source on every tab ─────────────────────────────────────────────

    @Test
    fun `every tab lists every source, with the image providers disabled on ICON1, Manual and Video`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            vm.load(1L)
            advanceUntilIdle()
            val imageProviders = listOf(StudioSource.STEAMGRIDDB, StudioSource.THEGAMESDB, StudioSource.IGDB)
            val noImageTabs = setOf(ArtworkKind.ICON1, ArtworkKind.MANUAL, ArtworkKind.VIDEO)

            STUDIO_TABS.forEachIndexed { index, tab ->
                vm.selectTab(index)
                advanceUntilIdle()
                assertEquals(StudioSource.entries, vm.sourcesForTab())
                imageProviders.forEach { source ->
                    assertEquals("$source on ${tab.label}", tab.kind !in noImageTabs, vm.isSourceAvailable(source))
                }
                assertTrue(vm.isSourceAvailable(StudioSource.SCREENSCRAPER))
                assertTrue(vm.isSourceAvailable(StudioSource.LOCAL))
            }
        }

    @Test
    fun `an image provider on the Video tab says why, is skipped, and is never asked`() = runTest(testDispatcher) {
        val vm = loadedOnTab(ArtworkKind.VIDEO)
        assertEquals("n/a", vm.sourceBadge(StudioSource.STEAMGRIDDB))

        vm.selectSource(vm.sourcesForTab().indexOf(StudioSource.STEAMGRIDDB))
        advanceUntilIdle()
        assertEquals("SteamGridDB has no VIDEO artwork", vm.uiState.value.message)
        assertEquals(StudioSource.SCREENSCRAPER, vm.sourcesForTab()[vm.uiState.value.sourceIndex])

        // From ScreenScraper, cycling right steps over all three image providers to Local.
        vm.cycleSource(+1)
        advanceUntilIdle()
        assertEquals(StudioSource.LOCAL, vm.sourcesForTab()[vm.uiState.value.sourceIndex])
        coVerify(exactly = 0) { steamGridDb.getArt(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `on the 3D Box tab SteamGridDB offers every art type it has`() = runTest(testDispatcher) {
        val vm = loadedOnTab(ArtworkKind.BOX_3D)

        vm.selectSource(vm.sourcesForTab().indexOf(StudioSource.STEAMGRIDDB))
        advanceUntilIdle()

        SgdbArtType.entries.forEach { type ->
            coVerify { steamGridDb.getArt(77L, type, any(), any(), any()) }
        }
    }

    @Test
    fun `on the Screenshot tab TheGamesDB offers its box art, fanart and logo`() = runTest(testDispatcher) {
        coEvery { theGamesDb.fetchGameInfo(any(), any()) } returns TgdbGameInfo(
            tgdbId = 1L, title = "Crash", description = null, releaseYear = null,
            artworkUrl = "tgdb-box", heroUrl = "tgdb-fanart", logoUrl = "tgdb-logo",
        )
        val vm = loadedOnTab(ArtworkKind.SCREENSHOT)

        vm.selectSource(vm.sourcesForTab().indexOf(StudioSource.THEGAMESDB))
        advanceUntilIdle()

        assertEquals(listOf("tgdb-box", "tgdb-fanart", "tgdb-logo"), vm.uiState.value.results.map { it.url })
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun kotlinx.coroutines.test.TestScope.loadedOnTab(kind: ArtworkKind): ArtworkStudioViewModel {
        val vm = viewModel()
        vm.load(1L)
        advanceUntilIdle()
        vm.selectTab(STUDIO_TABS.indexOfFirst { it.kind == kind })
        advanceUntilIdle()
        return vm
    }

    /** With the actions menu open, walks the cursor to [action] with the D-pad and presses A. */
    private fun kotlinx.coroutines.test.TestScope.pickFromMenu(vm: ArtworkStudioViewModel, action: StudioAction) {
        val index = vm.uiState.value.availableActions.indexOf(action)
        check(index >= 0) { "$action is not in the menu: ${vm.uiState.value.availableActions}" }
        repeat(index - vm.uiState.value.actionsIndex) { vm.handleGamepadAction(GamepadAction.NAVIGATE_DOWN) }
        vm.handleGamepadAction(GamepadAction.SELECT)
        advanceUntilIdle()
    }

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
