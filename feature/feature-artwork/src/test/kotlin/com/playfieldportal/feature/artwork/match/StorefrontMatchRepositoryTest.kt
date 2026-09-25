package com.playfieldportal.feature.artwork.match

import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.database.entity.GameEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam the picker and the Rematch screen talk to (C23 T6, Phases 10 and 18).
 *
 * The property under test throughout: LOOKING changes nothing. Every path through [lookup] must
 * resolve with `allowAutoLink = false`, so opening a screen can never be what links a game.
 */
class StorefrontMatchRepositoryTest {

    private val gameDao = mockk<GameDao>(relaxed = true)
    private val resolver = mockk<StorefrontMetadataResolver>(relaxed = true)
    private val repo = StorefrontMatchRepository(gameDao, resolver)

    private fun game(platformId: String = "windows") = GameEntity(
        id = 1L, title = "DOOM", platformId = platformId, romPath = null,
        packageName = null, emulatorPackage = null, artworkUri = null, heroUri = null, logoUri = null,
        description = null, developer = null, publisher = null, releaseYear = null,
        genre = null, steamGridDbId = null, scrapedTitle = null,
    )

    private fun candidate(id: String, title: String) =
        StorefrontCandidate(Storefront.STEAM, id, title)

    private fun scored(id: String, title: String, vararg signals: MatchSignal) =
        ScoredStorefrontCandidate(candidate(id, title), signals.toList())

    private fun resolution(vararg entries: Pair<Storefront, StorefrontMetadataResolver.Resolution>) =
        StorefrontMetadataResolver.GameResolution(1L, entries.toMap())

    // -- lookup ----------------------------------------------------------------

    @Test
    fun `an ambiguous store becomes a choice, ranked as the scorer ranked it`() = runTest {
        coEvery { gameDao.getById(1L) } returns game()
        coEvery { resolver.resolve(any(), any(), any()) } returns resolution(
            Storefront.STEAM to StorefrontMetadataResolver.Resolution.NeedsConfirmation(
                StorefrontMatchResult(
                    store = Storefront.STEAM,
                    confidence = MatchConfidence.AMBIGUOUS,
                    best = scored("379720", "DOOM", MatchSignal.EXACT_TITLE, MatchSignal.PUBLISHER),
                    alternatives = listOf(scored("2280", "DOOM", MatchSignal.EXACT_TITLE)),
                )
            )
        )

        val lookup = repo.lookup(1L) as StorefrontMatchRepository.Lookup.NeedsChoice

        assertEquals(1, lookup.pending.size)
        assertEquals(
            listOf("379720", "2280"),
            lookup.pending.single().candidates.map { it.candidate.storeId },
        )
        assertEquals("doom", lookup.query)
    }

    @Test
    fun `looking never links`() = runTest {
        coEvery { gameDao.getById(1L) } returns game()
        coEvery { resolver.resolve(any(), any(), any()) } returns resolution()

        repo.lookup(1L)

        // The whole point of the seam. A screen the user opened must SHOW before it writes.
        coVerify { resolver.resolve(any(), allowAutoLink = false, ignoreStoredIdentity = false) }
    }

    @Test
    fun `rematch is the only thing that looks past a stored id`() = runTest {
        coEvery { gameDao.getById(1L) } returns game()
        coEvery { resolver.resolve(any(), any(), any()) } returns resolution()

        repo.lookup(1L, ignoreStoredIdentity = true)

        coVerify { resolver.resolve(any(), allowAutoLink = false, ignoreStoredIdentity = true) }
    }

    @Test
    fun `a game already linked is Settled, not a question`() = runTest {
        coEvery { gameDao.getById(1L) } returns game()
        coEvery { resolver.resolve(any(), any(), any()) } returns resolution(
            Storefront.STEAM to StorefrontMetadataResolver.Resolution.Linked(
                identity = StorefrontIdentityRecord(Storefront.STEAM, "379720", resolvedTitle = "DOOM"),
                preset = MetadataPreset(provider = MatchProvider.STEAM, title = "DOOM"),
                newlyLinked = false,
            )
        )

        val lookup = repo.lookup(1L) as StorefrontMatchRepository.Lookup.Settled

        assertEquals("379720", lookup.identities.single().record.storeId)
        assertEquals("Steam", lookup.identities.single().storeLabel)
    }

    @Test
    fun `an unreachable store is Unavailable and never NoMatch`() = runTest {
        coEvery { gameDao.getById(1L) } returns game()
        coEvery { resolver.resolve(any(), any(), any()) } returns resolution(
            Storefront.STEAM to StorefrontMetadataResolver.Resolution.Unavailable(
                Storefront.STEAM,
                StorefrontOutcome.Failure(StorefrontFailure.RATE_LIMITED),
            )
        )

        // The distinction the user acts on: wait and retry, versus match by hand.
        val lookup = repo.lookup(1L)

        assertEquals(
            StorefrontMatchRepository.Lookup.Unavailable(listOf(Storefront.STEAM)),
            lookup,
        )
    }

    @Test
    fun `a genuine miss is NoMatch`() = runTest {
        coEvery { gameDao.getById(1L) } returns game()
        coEvery { resolver.resolve(any(), any(), any()) } returns resolution(
            Storefront.STEAM to StorefrontMetadataResolver.Resolution.NoMatch
        )

        assertEquals(StorefrontMatchRepository.Lookup.NoMatch, repo.lookup(1L))
    }

    @Test
    fun `a console ROM is never asked about`() = runTest {
        coEvery { gameDao.getById(1L) } returns game(platformId = "psx")

        assertEquals(StorefrontMatchRepository.Lookup.NotApplicable, repo.lookup(1L))
        coVerify(exactly = 0) { resolver.resolve(any(), any(), any()) }
    }

    @Test
    fun `a row that cannot be read is Unknown, not a miss`() = runTest {
        coEvery { gameDao.getById(1L) } returns null

        assertEquals(StorefrontMatchRepository.Lookup.Unknown, repo.lookup(1L))
    }

    @Test
    fun `a resolver that throws does not take the screen down with it`() = runTest {
        coEvery { gameDao.getById(1L) } returns game()
        coEvery { resolver.resolve(any(), any(), any()) } throws IllegalStateException("boom")

        assertEquals(StorefrontMatchRepository.Lookup.Unknown, repo.lookup(1L))
    }

    // -- Rematch rows ----------------------------------------------------------

    @Test
    fun `every store gets a row, linked or not, searchable or not`() = runTest {
        coEvery { resolver.linkedIdentities(1L) } returns listOf(
            StorefrontIdentityRecord(Storefront.STEAM, "379720", userConfirmed = true, resolvedTitle = "DOOM")
        )
        every { resolver.availableStores() } returns listOf(Storefront.STEAM)

        val rows = repo.rematchRows(1L)

        // All three, because "not built yet" and "nothing to know" are different answers.
        assertEquals(Storefront.entries, rows.map { it.store })
        val steam = rows.single { it.store == Storefront.STEAM }
        assertEquals("379720", steam.identity?.record?.storeId)
        assertTrue(steam.searchable)
        val gog = rows.single { it.store == Storefront.GOG }
        assertNull(gog.identity)
        assertFalse(gog.searchable)
    }

    // -- Writes ----------------------------------------------------------------

    @Test
    fun `confirming stores the candidate the user actually picked`() = runTest {
        val picked = candidate("2280", "DOOM")

        repo.confirm(1L, picked, MatchConfidence.AMBIGUOUS)

        coVerify { resolver.confirm(1L, picked, MatchConfidence.AMBIGUOUS) }
    }

    @Test
    fun `unlinking one store does exactly one thing`() = runTest {
        repo.unlink(1L, Storefront.STEAM)

        coVerify(exactly = 1) { resolver.unlink(1L, Storefront.STEAM) }
        // No metadata write, no other store touched — the promise the screen makes out loud.
        coVerify(exactly = 0) { resolver.confirm(any(), any(), any()) }
    }
}
