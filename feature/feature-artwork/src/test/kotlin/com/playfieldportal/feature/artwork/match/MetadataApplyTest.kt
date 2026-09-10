package com.playfieldportal.feature.artwork.match

import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.database.entity.GameEntity
import com.playfieldportal.feature.artwork.MetadataCandidates
import com.playfieldportal.feature.artwork.MetadataRepository
import com.playfieldportal.feature.artwork.TgdbGameInfo
import com.playfieldportal.feature.artwork.api.ArtworkRepository
import com.playfieldportal.feature.artwork.api.IgdbGameInfo
import com.playfieldportal.feature.artwork.api.ScrapeOptions
import com.playfieldportal.feature.artwork.api.SsGameInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C16 task 3.2 — Current-vs-Incoming metadata and the four apply policies.
 *
 * The policy function is pure, so most cases need no mocks. The last group pins the one writer,
 * [ArtworkRepository.applyMetadata]: Fill Missing Only must go through the reversed-COALESCE
 * `updateMetadataIfMissing`, and Keep Current must not touch the table at all.
 */
class MetadataApplyTest {

    // ── Presets from provider candidates ────────────────────────────────────────

    private fun ss(
        title: String? = "Chrono Trigger",
        description: String? = "Time travel RPG",
        developer: String? = "Square",
    ) = SsGameInfo(
        ssId = 42L, title = title, description = description, developer = developer,
        publisher = "Square", releaseYear = 1995, genre = "RPG", players = "1",
        ageRating = "ESRB E", franchise = "Chrono", communityRating = 0.9f, releaseDate = "1995-03-11",
        artworkUrl = null, boxArtUrl = null, box3dUrl = null, physicalMediaUrl = null,
        screenshotUrl = null, heroUrl = null, logoUrl = null, manualUrl = null,
        videoUrl = null, videoRawUrl = null,
    )

    private fun candidates(
        ssInfo: SsGameInfo? = null,
        tgdbInfo: TgdbGameInfo? = null,
        igdbInfo: IgdbGameInfo? = null,
    ) = MetadataCandidates(
        gameEntity = null, bestTitle = "Chrono Trigger", ssInfo = ssInfo, romIdentity = null,
        usedSsCache = false, cachedSsId = null, tgdbInfo = tgdbInfo, igdbInfo = igdbInfo,
        sgdbGameId = null, sgdbGridUrl = null, sgdbHeroUrl = null, sgdbLogoUrl = null,
    )

    private val tgdb = TgdbGameInfo(
        tgdbId = 7L, title = "Chrono Trigger", description = "TGDB blurb", releaseYear = 1995,
        artworkUrl = null, heroUrl = null, logoUrl = null,
    )

    @Test
    fun `ScreenScraper and TheGamesDB become presets, artwork-only answers never do`() {
        val presets = MetadataApply.presetsFrom(
            candidates(
                ssInfo = ss(),
                tgdbInfo = tgdb,
                igdbInfo = IgdbGameInfo(artworkUrl = "https://igdb/cover.jpg", heroUrl = null, logoUrl = null),
            )
        )

        assertEquals(listOf(MatchProvider.SCREENSCRAPER, MatchProvider.THEGAMESDB), presets.map { it.provider })
        assertEquals("Square", presets[0].developer)
        assertEquals("TGDB blurb", presets[1].description)
    }

    @Test
    fun `a URL-only ScreenScraper answer offers no preset`() {
        val cacheShaped = ss(title = null, description = null, developer = null).copy(
            publisher = null, releaseYear = null, genre = null, ageRating = null,
            franchise = null, communityRating = null, releaseDate = null,
        )

        assertTrue(MetadataApply.presetsFrom(candidates(ssInfo = cacheShaped)).isEmpty())
    }

    // ── The four policies ───────────────────────────────────────────────────────

    private val current: Map<MetadataField, Any?> = mapOf(
        MetadataField.TITLE to "Chrono Trigger",
        MetadataField.DESCRIPTION to "My own notes",
        MetadataField.DEVELOPER to null,
        MetadataField.RELEASE_YEAR to 1995,
    )

    private val incoming = MetadataPreset(
        provider = MatchProvider.SCREENSCRAPER,
        title = "Chrono Trigger",            // same as current — never a change
        description = "Time travel RPG",     // differs from a populated value
        developer = "Square",                // fills an empty value
        publisher = "   ",                   // blank — never offered, never written
        releaseYear = 1995,
    )

    @Test
    fun `rows list only what the provider supplied, and mark what differs`() {
        val rows = MetadataApply.rows(current, incoming)

        assertEquals(
            listOf(MetadataField.TITLE, MetadataField.DESCRIPTION, MetadataField.DEVELOPER, MetadataField.RELEASE_YEAR),
            rows.map { it.field },
        )
        assertEquals(listOf(false, true, true, false), rows.map { it.differs })
    }

    @Test
    fun `Replace All writes every differing value and never a blank`() {
        val plan = MetadataApply.plan(current, incoming, MetadataApplyPolicy.REPLACE_ALL, chosen = emptySet())

        assertEquals(
            mapOf(MetadataField.DESCRIPTION to "Time travel RPG", MetadataField.DEVELOPER to "Square"),
            plan,
        )
    }

    @Test
    fun `Fill Missing Only never overwrites a populated value`() {
        val plan = MetadataApply.plan(current, incoming, MetadataApplyPolicy.FILL_MISSING_ONLY, chosen = emptySet())

        assertEquals(mapOf(MetadataField.DEVELOPER to "Square"), plan)
    }

    @Test
    fun `Choose Fields writes only ticked fields that actually change`() {
        val plan = MetadataApply.plan(
            current, incoming, MetadataApplyPolicy.CHOOSE_FIELDS,
            chosen = setOf(MetadataField.DESCRIPTION, MetadataField.TITLE, MetadataField.PUBLISHER),
        )

        assertEquals(mapOf(MetadataField.DESCRIPTION to "Time travel RPG"), plan)
    }

    @Test
    fun `Keep Current writes nothing`() {
        val plan = MetadataApply.plan(current, incoming, MetadataApplyPolicy.KEEP_CURRENT, chosen = MetadataField.entries.toSet())

        assertTrue(plan.isEmpty())
    }

    // ── The writer ──────────────────────────────────────────────────────────────

    private val gameDao = mockk<GameDao>(relaxed = true)
    private val metadataRepository = mockk<MetadataRepository>(relaxed = true)

    private val repo = ArtworkRepository(
        imageCache = mockk(relaxed = true),
        gameDao = gameDao,
        metadataRepository = metadataRepository,
        scrapePreferences = mockk(relaxed = true),
        artworkStore = mockk(relaxed = true),
        internalStore = mockk(relaxed = true),
        ssMediaCacheDao = mockk(relaxed = true),
    )

    private fun givenStoredGame(description: String? = "My own notes", developer: String? = null) {
        coEvery { gameDao.getById(1L) } returns GameEntity(
            id = 1L, title = "chrono_trigger", platformId = "snes", romPath = null,
            packageName = null, emulatorPackage = null, artworkUri = null, heroUri = null, logoUri = null,
            description = description, developer = developer, publisher = null, releaseYear = 1995,
            genre = null, steamGridDbId = null, scrapedTitle = "Chrono Trigger",
        )
    }

    @Test
    fun `the preview asks for text only and bypasses the URL-only ScreenScraper cache`() = runTest {
        givenStoredGame()
        coEvery { metadataRepository.fetchCandidates(any(), any(), any(), any(), any(), any()) } returns
            candidates(tgdbInfo = tgdb)

        val preview = repo.fetchMetadataPreview(1L)

        assertEquals("My own notes", preview?.current?.get(MetadataField.DESCRIPTION))
        assertEquals(listOf(MatchProvider.THEGAMESDB), preview?.presets?.map { it.provider })
        coVerify {
            metadataRepository.fetchCandidates(
                1L, "chrono_trigger", "snes", null,
                ScrapeOptions(metadataOnly = true, bypassSsCache = true), null,
            )
        }
    }

    @Test
    fun `Fill Missing Only goes through the reversed-COALESCE write`() = runTest {
        givenStoredGame()

        val written = repo.applyMetadata(1L, incoming, MetadataApplyPolicy.FILL_MISSING_ONLY)

        assertEquals(setOf(MetadataField.DEVELOPER), written)
        coVerify { gameDao.getById(1L) }
        coVerify {
            gameDao.updateMetadataIfMissing(
                id = 1L, description = null, developer = "Square", publisher = null,
                releaseYear = null, genre = null, scrapedTitle = null,
                ageRating = null, franchise = null, communityRating = null, releaseDate = null,
            )
        }
        confirmVerified(gameDao)
    }

    @Test
    fun `Replace All overwrites through the COALESCE write, touching no artwork column`() = runTest {
        givenStoredGame()

        val written = repo.applyMetadata(1L, incoming, MetadataApplyPolicy.REPLACE_ALL)

        assertEquals(setOf(MetadataField.DESCRIPTION, MetadataField.DEVELOPER), written)
        coVerify { gameDao.getById(1L) }
        coVerify {
            gameDao.updateMetadata(
                id = 1L, description = "Time travel RPG", developer = "Square", publisher = null,
                releaseYear = null, genre = null, artworkUri = null, heroUri = null, logoUri = null,
                iconUri = null, boxArtUri = null, physicalMediaUri = null, box3dUri = null,
                scrapedTitle = null, players = null, ageRating = null, franchise = null,
                communityRating = null, releaseDate = null, ssId = null, tgdbId = null, igdbId = null,
                steamGridDbId = null, romCrc32 = null,
            )
        }
        confirmVerified(gameDao)
    }

    @Test
    fun `Keep Current and an empty plan never touch the games table`() = runTest {
        givenStoredGame(description = "Time travel RPG", developer = "Square")

        assertTrue(repo.applyMetadata(1L, incoming, MetadataApplyPolicy.KEEP_CURRENT).isEmpty())
        assertTrue(repo.applyMetadata(1L, incoming, MetadataApplyPolicy.REPLACE_ALL).isEmpty())

        coVerify(exactly = 2) { gameDao.getById(1L) }
        confirmVerified(gameDao)
    }
}
