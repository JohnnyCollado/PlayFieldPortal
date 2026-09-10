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
        coEvery { matchEvidence.candidateByRomHash(any(), any(), any()) } returns null
        coEvery { matchEvidence.candidateByStorefront(any(), any(), any()) } returns null

        coEvery { gameRepository.getById(1L) } returns game
        coEvery { sgdbKeyProvider.getKey() } returns "sgdb-key"
        coEvery { igdbApi.hasCredentials() } returns true
        coEvery { theGamesDb.hasApiKey() } returns true
        coEvery { artworkStore.find(any(), any(), any()) } returns null
        coEvery { ssMediaCatalog.mediasFor(any(), any()) } returns emptyList()
        coEvery { steamGridDb.searchGame(any()) } returns Result.success(listOf(SgdbGame(id = 77L, name = "Crash")))
        coEvery { steamGridDb.getArt(any(), any(), any(), any(), any()) } returns Result.success(emptyList())
        coEvery { theGamesDb.fetchGameInfo(any(), any()) } returns null
        coEvery { igdbApi.fetchGameInfo(any(), any()) } returns null
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = ArtworkStudioViewModel(
        context, gameRepository, artworkStore, routingStore, ssMediaCatalog,
        steamGridDb, sgdbKeyProvider, theGamesDb, igdbApi, videoSnapTranscoder, matchEvidence,
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
