package com.playfieldportal.feature.artwork.match

import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.database.entity.GameEntity
import com.playfieldportal.core.domain.model.MetadataOverrideKeys
import com.playfieldportal.core.domain.model.MetadataOverrides
import com.playfieldportal.feature.artwork.MetadataRepository
import com.playfieldportal.feature.artwork.api.ArtworkRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C23 T2 — the MANUAL preset and where its write lands.
 *
 * Kept apart from [MetadataApplyTest] on purpose: that suite pins the provider write path and must
 * keep passing untouched, because a branch that made it need editing would be a branch in the
 * wrong place.
 */
class MetadataOverrideApplyTest {

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

    private fun storedGame(
        description: String? = "Scraped blurb",
        developer: String? = "Scraped Studio",
        overrides: String? = null,
        titleOverride: String? = null,
    ) = GameEntity(
        id = 1L, title = "crash", platformId = "ps1", romPath = null,
        packageName = null, emulatorPackage = null, artworkUri = null, heroUri = null, logoUri = null,
        description = description, developer = developer, publisher = null, releaseYear = 1996,
        genre = null, steamGridDbId = null, scrapedTitle = "Crash Bandicoot",
        userMetadataOverrides = overrides, userTitleOverride = titleOverride,
    )

    /** The JSON last written to `user_metadata_overrides`; null once nothing is overridden. */
    private var writtenOverrides: String? = null

    private fun given(game: GameEntity) {
        coEvery { gameDao.getById(1L) } returns game
        // Recorded through an answer, not a capturing slot: mockk's capture() is declared for
        // non-nullable types only, and this column is nullable on purpose.
        coEvery { gameDao.updateUserMetadataOverrides(1L, any()) } answers {
            writtenOverrides = arg<String?>(1)
        }
    }

    private fun storedOverrides() = MetadataOverrides.parse(writtenOverrides)

    private val manual = MetadataPreset(
        provider = MatchProvider.MANUAL,
        developer = "My own studio",
        genre = "Platform",
    )

    @Test
    fun `a MANUAL preset writes only the override map, never a metadata column`() = runTest {
        given(storedGame())
        val written = repo.applyMetadata(1L, manual, MetadataApplyPolicy.REPLACE_ALL)

        assertEquals(setOf(MetadataField.DEVELOPER, MetadataField.GENRE), written)
        coVerify { gameDao.getById(1L) }
        coVerify { gameDao.updateUserMetadataOverrides(1L, any()) }
        // Neither metadata write is reached — that is what lets the next Re-scrape All keep
        // refreshing the columns without ever reaching the screen.
        confirmVerified(gameDao)

        val stored = storedOverrides()
        assertEquals("My own studio", stored.string(MetadataOverrideKeys.DEVELOPER))
        assertEquals("Platform", stored.string(MetadataOverrideKeys.GENRE))
    }

    @Test
    fun `applying one field keeps the overrides already set on the others`() = runTest {
        given(storedGame(overrides = "{\"PUBLISHER\":\"My own publisher\"}"))
        repo.applyMetadata(
            1L,
            MetadataPreset(provider = MatchProvider.MANUAL, developer = "My own studio"),
            MetadataApplyPolicy.REPLACE_ALL,
        )

        coVerify { gameDao.updateUserMetadataOverrides(1L, any()) }
        val stored = storedOverrides()
        // Merge, not replace: editing one field must not silently revert the eight set earlier.
        assertEquals("My own publisher", stored.string(MetadataOverrideKeys.PUBLISHER))
        assertEquals("My own studio", stored.string(MetadataOverrideKeys.DEVELOPER))
    }

    @Test
    fun `a MANUAL title goes to its own column, not into the map`() = runTest {
        given(storedGame())
        val written = repo.applyMetadata(
            1L,
            MetadataPreset(provider = MatchProvider.MANUAL, title = "Crash 1", developer = "My own studio"),
            MetadataApplyPolicy.REPLACE_ALL,
        )

        assertEquals(setOf(MetadataField.TITLE, MetadataField.DEVELOPER), written)
        // The achievement joins read user_title_override in SQL; a title inside a JSON blob would
        // be invisible to them.
        coVerify { gameDao.updateUserTitleOverride(1L, "Crash 1") }
        coVerify { gameDao.updateUserMetadataOverrides(1L, any()) }
        assertFalse(writtenOverrides!!.contains("TITLE"))
    }

    // ── A provider title over a hand-set one (C23 T2 follow-up) ───────────────
    //
    // The user's typed title shadows every column, so writing scraped_title under it changed
    // nothing on screen — the bug. An applied TITLE now clears the override as well, which is
    // why the ViewModel confirms before sending one.

    @Test
    fun `an applied provider title clears the hand-set title override`() = runTest {
        given(storedGame(titleOverride = "Crash 1"))

        val written = repo.applyMetadata(
            1L,
            MetadataPreset(provider = MatchProvider.SCREENSCRAPER, title = "Crash Bandicoot"),
            MetadataApplyPolicy.REPLACE_ALL,
        )

        assertEquals(setOf(MetadataField.TITLE), written)
        // Cleared, not overwritten: the scraped layer underneath becomes visible, which is the
        // same end state as reverting the field by hand.
        coVerify { gameDao.updateUserTitleOverride(1L, null) }
    }

    @Test
    fun `a provider title equal to the scraped one still replaces the typed one`() = runTest {
        // Stored scraped_title is "Crash Bandicoot" and so is the provider's — but the user sees
        // their own "Crash 1", so the row is compared against THAT and still offers the change.
        given(storedGame(titleOverride = "Crash 1"))

        val written = repo.applyMetadata(
            1L,
            MetadataPreset(provider = MatchProvider.SCREENSCRAPER, title = "Crash Bandicoot"),
            MetadataApplyPolicy.CHOOSE_FIELDS,
            chosen = setOf(MetadataField.TITLE),
        )

        assertEquals(setOf(MetadataField.TITLE), written)
        coVerify { gameDao.updateUserTitleOverride(1L, null) }
    }

    @Test
    fun `a provider apply that writes no title leaves the override alone`() = runTest {
        given(storedGame(titleOverride = "Crash 1"))

        repo.applyMetadata(
            1L,
            MetadataPreset(provider = MatchProvider.SCREENSCRAPER, developer = "Naughty Dog"),
            MetadataApplyPolicy.REPLACE_ALL,
        )

        coVerify(exactly = 0) { gameDao.updateUserTitleOverride(any(), any()) }
    }

    @Test
    fun `Fill Missing Only never clears a title the user set by hand`() = runTest {
        // A hand-set title is exactly what "missing" is not, whatever the columns underneath hold.
        given(storedGame(titleOverride = "Crash 1"))

        val written = repo.applyMetadata(
            1L,
            MetadataPreset(provider = MatchProvider.SCREENSCRAPER, title = "Crash Bandicoot"),
            MetadataApplyPolicy.FILL_MISSING_ONLY,
        )

        assertFalse(MetadataField.TITLE in written)
        coVerify(exactly = 0) { gameDao.updateUserTitleOverride(any(), any()) }
    }

    @Test
    fun `Fill Missing Only fills only what has neither an override nor a stored value`() = runTest {
        // Developer is hand-set; description is stored; genre is empty both ways.
        given(storedGame(overrides = "{\"DEVELOPER\":\"My own studio\"}"))
        val written = repo.applyMetadata(
            1L,
            MetadataPreset(
                provider = MatchProvider.MANUAL,
                description = "Typed blurb",
                developer = "Something else",
                genre = "Platform",
            ),
            MetadataApplyPolicy.FILL_MISSING_ONLY,
        )

        assertEquals(setOf(MetadataField.GENRE), written)
        coVerify { gameDao.updateUserMetadataOverrides(1L, any()) }
        val stored = storedOverrides()
        assertEquals("Platform", stored.string(MetadataOverrideKeys.GENRE))
        assertEquals("My own studio", stored.string(MetadataOverrideKeys.DEVELOPER))
        assertNull(stored.string(MetadataOverrideKeys.DESCRIPTION))
    }

    @Test
    fun `an override equal to what is already shown is not a change`() = runTest {
        given(storedGame(overrides = "{\"DEVELOPER\":\"My own studio\"}"))

        val written = repo.applyMetadata(
            1L,
            MetadataPreset(provider = MatchProvider.MANUAL, developer = "My own studio"),
            MetadataApplyPolicy.REPLACE_ALL,
        )

        // A manual preset is measured against the EFFECTIVE values — what the user sees — so
        // re-typing the value already displayed writes nothing at all.
        assertTrue(written.isEmpty())
        coVerify { gameDao.getById(1L) }
        confirmVerified(gameDao)
    }

    // ── Revert ──────────────────────────────────────────────────────────────

    @Test
    fun `reverting removes one key and leaves the rest`() = runTest {
        given(storedGame(overrides = "{\"DEVELOPER\":\"My own studio\",\"GENRE\":\"Platform\"}"))
        assertTrue(repo.clearMetadataOverride(1L, MetadataField.DEVELOPER))

        coVerify { gameDao.updateUserMetadataOverrides(1L, any()) }
        val stored = storedOverrides()
        assertFalse(stored.isOverridden(MetadataOverrideKeys.DEVELOPER))
        assertEquals("Platform", stored.string(MetadataOverrideKeys.GENRE))
    }

    @Test
    fun `reverting the last override nulls the column`() = runTest {
        given(storedGame(overrides = "{\"DEVELOPER\":\"My own studio\"}"))

        assertTrue(repo.clearMetadataOverride(1L, MetadataField.DEVELOPER))

        coVerify { gameDao.updateUserMetadataOverrides(1L, null) }
    }

    @Test
    fun `reverting the title clears its own column`() = runTest {
        given(storedGame(titleOverride = "Crash 1"))

        assertTrue(repo.clearMetadataOverride(1L, MetadataField.TITLE))

        coVerify { gameDao.updateUserTitleOverride(1L, null) }
        coVerify(exactly = 0) { gameDao.updateUserMetadataOverrides(any(), any()) }
    }

    @Test
    fun `reverting a field that was never overridden writes nothing`() = runTest {
        given(storedGame())

        assertFalse(repo.clearMetadataOverride(1L, MetadataField.DEVELOPER))
        assertFalse(repo.clearMetadataOverride(1L, MetadataField.TITLE))

        coVerify(exactly = 2) { gameDao.getById(1L) }
        confirmVerified(gameDao)
    }

    // ── The key contract core-domain restates ───────────────────────────────

    @Test
    fun `every MetadataField name is a known override key`() {
        // MetadataOverrideKeys lives in core-domain, which cannot see this enum. This is the test
        // that fails if a field is ever added or renamed here without following it there.
        assertEquals(
            MetadataField.entries.mapTo(mutableSetOf()) { it.name },
            MetadataOverrideKeys.ALL,
        )
    }

    @Test
    fun `effectiveOf layers overrides over the stored columns and currentOf does not`() {
        val game = storedGame(
            overrides = "{\"DEVELOPER\":\"My own studio\",\"RELEASE_YEAR\":2001}",
            titleOverride = "Crash 1",
        )

        val effective = MetadataApply.effectiveOf(game)
        assertEquals("My own studio", effective[MetadataField.DEVELOPER])
        assertEquals(2001, effective[MetadataField.RELEASE_YEAR])
        assertEquals("Crash 1", effective[MetadataField.TITLE])
        assertEquals("Scraped blurb", effective[MetadataField.DESCRIPTION])

        // A provider can only reach the columns, so it is still compared against them alone.
        val current = MetadataApply.currentOf(game)
        assertEquals("Scraped Studio", current[MetadataField.DEVELOPER])
        assertEquals(1996, current[MetadataField.RELEASE_YEAR])
        assertEquals("Crash Bandicoot", current[MetadataField.TITLE])

        assertEquals(
            setOf(MetadataField.DEVELOPER, MetadataField.RELEASE_YEAR, MetadataField.TITLE),
            MetadataApply.overriddenFieldsOf(game),
        )
    }
}
