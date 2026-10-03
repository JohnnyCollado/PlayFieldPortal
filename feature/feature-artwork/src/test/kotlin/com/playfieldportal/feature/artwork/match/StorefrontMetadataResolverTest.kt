package com.playfieldportal.feature.artwork.match

import com.playfieldportal.core.data.database.dao.GameStorefrontIdentityDao
import com.playfieldportal.core.data.database.entity.GameEntity
import com.playfieldportal.core.data.database.entity.GameStorefrontIdentityEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * The resolver's order of operations (C23 T6, Phase 17).
 *
 * The property every case here defends is the plan's Core Rule: a title is how an identity is
 * DISCOVERED, never the relationship itself. A game that already has a stored id must not produce
 * a single search request, ever.
 */
class StorefrontMetadataResolverTest {

    // -- Test doubles -----------------------------------------------------------

    /** An in-memory stand-in for the identity table, keyed the way the real one is. */
    private class FakeIdentityDao : GameStorefrontIdentityDao {
        val rows = mutableMapOf<Pair<Long, String>, GameStorefrontIdentityEntity>()

        override suspend fun forGame(gameId: Long) = rows.values.filter { it.gameId == gameId }
        override suspend fun get(gameId: Long, store: String) = rows[gameId to store]
        override suspend fun byStoreId(store: String, storeId: String) =
            rows.values.filter { it.store == store && it.storeId == storeId }
        override suspend fun upsert(identity: GameStorefrontIdentityEntity) {
            rows[identity.gameId to identity.store] = identity
        }
        override suspend fun markVerified(gameId: Long, store: String, at: Long) {
            rows[gameId to store]?.let { rows[gameId to store] = it.copy(lastVerifiedAt = at) }
        }
        override suspend fun delete(gameId: Long, store: String) { rows.remove(gameId to store) }
        override suspend fun deleteForGame(gameId: Long) { rows.keys.removeAll { it.first == gameId } }
        override suspend fun count() = rows.size
    }

    /** A provider that answers from a script and counts what it was asked. */
    private class FakeProvider(
        override val store: Storefront = Storefront.STEAM,
        val available: Boolean = true,
        val searchResult: (List<String>) -> StorefrontOutcome<List<StorefrontCandidate>> =
            { StorefrontOutcome.NoMatch },
        val metadata: (String) -> StorefrontOutcome<MetadataPreset> = { StorefrontOutcome.NoMatch },
        override val trustsCapturedId: Boolean = true,
    ) : StorefrontMetadataProvider {
        var searches = 0
        var metadataCalls = mutableListOf<String>()

        override suspend fun isAvailable() = available

        override suspend fun search(titles: List<String>): StorefrontOutcome<List<StorefrontCandidate>> {
            searches++
            return searchResult(titles)
        }

        override suspend fun getMetadata(storeId: String): StorefrontOutcome<MetadataPreset> {
            metadataCalls += storeId
            return metadata(storeId)
        }

        override suspend fun validateIdentity(storeId: String) = StorefrontOutcome.Ok(true)
    }

    private fun resolver(dao: GameStorefrontIdentityDao, vararg providers: StorefrontMetadataProvider) =
        StorefrontMetadataResolver(
            providers = StorefrontProviders(providers.toList()),
            identities = dao,
            now = StorefrontMetadataResolver.TimeSource { FIXED_NOW },
        )

    private fun game(
        title: String = "Portal 2",
        storefront: String? = null,
        storefrontGameId: String? = null,
        developer: String? = null,
        releaseYear: Int? = null,
    ) = GameEntity(
        id = 1L, title = title, platformId = "windows", romPath = null,
        packageName = null, emulatorPackage = null, artworkUri = null, heroUri = null, logoUri = null,
        description = null, developer = developer, publisher = null, releaseYear = releaseYear,
        genre = null, steamGridDbId = null, scrapedTitle = null,
        storefront = storefront, storefrontGameId = storefrontGameId,
    )

    private fun preset(title: String, developer: String? = null, year: Int? = null) =
        MetadataPreset(provider = MatchProvider.STEAM, title = title, developer = developer, releaseYear = year)

    private fun candidate(id: String, title: String) = StorefrontCandidate(Storefront.STEAM, id, title)

    // -- 1. A stored identity short-circuits everything --------------------------

    @Test
    fun `a stored identity is used by id and never searched by title`() = runTest {
        val dao = FakeIdentityDao()
        dao.upsert(
            GameStorefrontIdentityEntity(
                gameId = 1L, store = "STEAM", storeId = "620",
                confidence = MatchConfidence.EXACT.name, linkedAt = 0L,
            )
        )
        val provider = FakeProvider(metadata = { StorefrontOutcome.Ok(preset("Portal 2")) })

        val resolution = resolver(dao, provider).resolve(game())

        assertEquals(0, provider.searches)
        assertEquals(listOf("620"), provider.metadataCalls)
        val linked = resolution.byStore[Storefront.STEAM] as StorefrontMetadataResolver.Resolution.Linked
        assertEquals(false, linked.newlyLinked)
    }

    @Test
    fun `a stored identity is re-verified rather than re-discovered`() = runTest {
        val dao = FakeIdentityDao()
        dao.upsert(
            GameStorefrontIdentityEntity(
                gameId = 1L, store = "STEAM", storeId = "620",
                confidence = MatchConfidence.EXACT.name, linkedAt = 0L, lastVerifiedAt = null,
            )
        )
        val provider = FakeProvider(metadata = { StorefrontOutcome.Ok(preset("Portal 2")) })

        resolver(dao, provider).resolve(game())

        assertEquals(FIXED_NOW, dao.rows.values.single().lastVerifiedAt)
    }

    @Test
    fun `a delisted id the user chose themselves is reported, not silently replaced`() = runTest {
        val dao = FakeIdentityDao()
        dao.upsert(
            GameStorefrontIdentityEntity(
                gameId = 1L, store = "STEAM", storeId = "620",
                confidence = MatchConfidence.EXACT.name, userConfirmed = true, linkedAt = 0L,
            )
        )
        val provider = FakeProvider(metadata = { StorefrontOutcome.NoMatch })

        val resolution = resolver(dao, provider).resolve(game())

        assertEquals(0, provider.searches)
        assertEquals(
            StorefrontMetadataResolver.Resolution.NoMatch,
            resolution.byStore[Storefront.STEAM],
        )
    }

    @Test
    fun `an outage never costs a game its stored identity`() = runTest {
        val dao = FakeIdentityDao()
        dao.upsert(
            GameStorefrontIdentityEntity(
                gameId = 1L, store = "STEAM", storeId = "620",
                confidence = MatchConfidence.EXACT.name, linkedAt = 0L,
            )
        )
        val provider = FakeProvider(
            metadata = { StorefrontOutcome.Failure(StorefrontFailure.NETWORK_ERROR) },
        )

        val resolution = resolver(dao, provider).resolve(game())

        assertTrue(resolution.byStore[Storefront.STEAM] is StorefrontMetadataResolver.Resolution.Unavailable)
        // Still linked. Unlinking on a timeout would empty a library over a flaky connection.
        assertEquals("620", dao.rows.values.single().storeId)
        assertEquals(0, provider.searches)
    }

    // -- 2. The import-captured pair ---------------------------------------------

    @Test
    fun `an import-captured appid is used directly and stored, with no title search`() = runTest {
        val dao = FakeIdentityDao()
        val provider = FakeProvider(metadata = { StorefrontOutcome.Ok(preset("Portal 2")) })

        val resolution = resolver(dao, provider)
            .resolve(game(storefront = "STEAM", storefrontGameId = "620"))

        assertEquals(0, provider.searches)
        val linked = resolution.byStore[Storefront.STEAM] as StorefrontMetadataResolver.Resolution.Linked
        assertTrue(linked.newlyLinked)
        assertEquals("620", dao.rows.values.single().storeId)
    }

    @Test
    fun `a GOG product id is never handed to the Steam provider`() = runTest {
        val dao = FakeIdentityDao()
        val provider = FakeProvider(
            searchResult = { StorefrontOutcome.NoMatch },
            metadata = { StorefrontOutcome.Ok(preset("Wrong Game")) },
        )

        resolver(dao, provider).resolve(game(storefront = "GOG", storefrontGameId = "1207658930"))

        // The captured pair belongs to another store, so it is not an authoritative id HERE. The
        // resolver falls through to discovery instead of looking a GOG id up on Steam.
        assertTrue(provider.metadataCalls.isEmpty())
        assertEquals(1, provider.searches)
    }

    // -- 3. Discovery ------------------------------------------------------------

    @Test
    fun `an unambiguous exact title is linked automatically`() = runTest {
        val dao = FakeIdentityDao()
        val provider = FakeProvider(
            searchResult = { StorefrontOutcome.Ok(listOf(candidate("620", "Portal 2"))) },
            metadata = { StorefrontOutcome.Ok(preset("Portal 2")) },
        )

        val resolution = resolver(dao, provider).resolve(game())

        val linked = resolution.byStore[Storefront.STEAM] as StorefrontMetadataResolver.Resolution.Linked
        assertEquals("620", linked.identity.storeId)
        assertEquals(MatchConfidence.EXACT.name, dao.rows.values.single().confidence)
    }

    @Test
    fun `an ambiguous field asks the user and stores nothing`() = runTest {
        val dao = FakeIdentityDao()
        val provider = FakeProvider(
            searchResult = {
                StorefrontOutcome.Ok(listOf(candidate("379720", "DOOM"), candidate("2280", "DOOM")))
            },
            metadata = { StorefrontOutcome.Ok(preset("DOOM")) },
        )

        val resolution = resolver(dao, provider).resolve(game(title = "DOOM"))

        assertTrue(
            resolution.byStore[Storefront.STEAM] is StorefrontMetadataResolver.Resolution.NeedsConfirmation
        )
        assertTrue(resolution.needsConfirmation)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `near misses reach a person who opened the picker, at LOW confidence`() = runTest {
        // The Bravely Default case: Steam has BRAVELY DEFAULT II, which is a DIFFERENT game, so it
        // scores a partial title and nothing else - below the floor PFP would ever link on. Telling
        // the user Steam has nothing, while Steam plainly lists it, is the wrong kind of caution.
        val dao = FakeIdentityDao()
        val provider = FakeProvider(
            searchResult = { StorefrontOutcome.Ok(listOf(candidate("1358800", "BRAVELY DEFAULT II"))) },
            metadata = { StorefrontOutcome.Ok(preset("BRAVELY DEFAULT II")) },
        )

        val resolution = resolver(dao, provider)
            .resolve(game(title = "Bravely Default"), allowAutoLink = false)

        val needs = resolution.byStore[Storefront.STEAM]
            as StorefrontMetadataResolver.Resolution.NeedsConfirmation
        assertEquals(MatchConfidence.LOW, needs.result.confidence)
        assertEquals("1358800", needs.result.best?.candidate?.storeId)
        // Still nothing written: LOW never auto-links, and this run could not have anyway.
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `an automatic pass treats the same near miss as no match`() = runTest {
        // The other half of the rule: a bulk run must not queue a confirmation for every game a
        // store merely has a near-namesake for.
        val dao = FakeIdentityDao()
        val provider = FakeProvider(
            searchResult = { StorefrontOutcome.Ok(listOf(candidate("1358800", "BRAVELY DEFAULT II"))) },
            metadata = { StorefrontOutcome.Ok(preset("BRAVELY DEFAULT II")) },
        )

        val resolution = resolver(dao, provider).resolve(game(title = "Bravely Default"))

        assertEquals(
            StorefrontMetadataResolver.Resolution.NoMatch,
            resolution.byStore[Storefront.STEAM],
        )
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `a result with nothing in common is not dressed up as a near miss`() = runTest {
        val dao = FakeIdentityDao()
        val provider = FakeProvider(
            searchResult = { StorefrontOutcome.Ok(listOf(candidate("42", "Farming Simulator 22"))) },
        )

        val resolution = resolver(dao, provider)
            .resolve(game(title = "Bravely Default"), allowAutoLink = false)

        // Zero signals is a search-engine artefact, not a candidate worth a user's attention.
        assertEquals(
            StorefrontMetadataResolver.Resolution.NoMatch,
            resolution.byStore[Storefront.STEAM],
        )
    }

    @Test
    fun `a preview run links nothing even when it is certain`() = runTest {
        val dao = FakeIdentityDao()
        val provider = FakeProvider(
            searchResult = { StorefrontOutcome.Ok(listOf(candidate("620", "Portal 2"))) },
            metadata = { StorefrontOutcome.Ok(preset("Portal 2")) },
        )

        resolver(dao, provider).resolve(game(), allowAutoLink = false)

        assertTrue(dao.rows.isEmpty())
    }

    // -- More than one store -----------------------------------------------------

    @Test
    fun `a resolve scoped to one store never asks another`() = runTest {
        // Local Steam only wants Steam's answer, and a Store Match row only its own store's: a
        // second provider asked anyway is a request spent on an answer nobody reads.
        val steam = FakeProvider(
            store = Storefront.STEAM,
            searchResult = { StorefrontOutcome.Ok(listOf(candidate("620", "Portal 2"))) },
            metadata = { StorefrontOutcome.Ok(preset("Portal 2")) },
        )
        val gog = FakeProvider(
            store = Storefront.GOG,
            searchResult = { StorefrontOutcome.Ok(listOf(StorefrontCandidate(Storefront.GOG, "1", "Portal 2"))) },
            metadata = { StorefrontOutcome.Ok(preset("Portal 2")) },
        )

        val resolution = resolver(FakeIdentityDao(), steam, gog)
            .resolve(game(), allowAutoLink = false, stores = setOf(Storefront.STEAM))

        assertEquals(setOf(Storefront.STEAM), resolution.byStore.keys)
        assertEquals(0, gog.searches)
        assertTrue(gog.metadataCalls.isEmpty())
    }

    @Test
    fun `an unscoped resolve asks every provider, in the order they are listed`() = runTest {
        val steam = FakeProvider(store = Storefront.STEAM)
        val gog = FakeProvider(store = Storefront.GOG)

        val resolution = resolver(FakeIdentityDao(), steam, gog).resolve(game(), allowAutoLink = false)

        assertEquals(listOf(Storefront.STEAM, Storefront.GOG), resolution.byStore.keys.toList())
        assertEquals(1, steam.searches)
        assertEquals(1, gog.searches)
    }

    @Test
    fun `a store that does not trust the imported id finds the game by title instead`() = runTest {
        // The number a launcher export carries for a GOG game is not known to be the gog.com
        // product id. Fetching by it — and, on an automatic pass, storing it as EXACT — would be a
        // confident link to whatever product happens to own that number.
        val dao = FakeIdentityDao()
        val gog = FakeProvider(
            store = Storefront.GOG,
            trustsCapturedId = false,
            searchResult = { StorefrontOutcome.Ok(listOf(StorefrontCandidate(Storefront.GOG, "1207658924", "Portal 2"))) },
            metadata = { StorefrontOutcome.Ok(preset("Portal 2")) },
        )

        val resolution = resolver(dao, gog).resolve(game(storefront = "GOG", storefrontGameId = "42"))

        assertEquals(1, gog.searches)
        assertFalse("42" in gog.metadataCalls)
        val linked = resolution.byStore[Storefront.GOG] as StorefrontMetadataResolver.Resolution.Linked
        assertEquals("1207658924", linked.identity.storeId)
        assertEquals("1207658924", dao.rows.values.single().storeId)
    }

    // -- A name the user typed ---------------------------------------------------

    @Test
    fun `a typed name is what gets searched and scored, not the game's title`() = runTest {
        val asked = mutableListOf<String>()
        val provider = FakeProvider(
            searchResult = { titles ->
                asked += titles
                StorefrontOutcome.Ok(listOf(candidate("1358800", "Bravely Default")))
            },
            metadata = { StorefrontOutcome.Ok(preset("Bravely Default")) },
        )

        val resolution = resolver(FakeIdentityDao(), provider).resolve(
            game(title = "Brave Default FF (repack)"),
            allowAutoLink = false,
            titleOverride = "Bravely Default",
        )

        assertTrue(asked.any { it.contains("bravely default", ignoreCase = true) })
        assertTrue(asked.none { it.contains("brave default", ignoreCase = true) })
        // Scored against what was typed: the store's title is exactly that, whatever the row says.
        val linked = resolution.byStore[Storefront.STEAM] as StorefrontMetadataResolver.Resolution.Linked
        assertEquals(listOf(MatchSignal.EXACT_TITLE), linked.match?.best?.signals)
    }

    @Test
    fun `a typed name looks past every id PFP already holds`() = runTest {
        // Typing a name is the user saying the link is wrong or missing, so neither the stored
        // row nor the import-captured pair may answer in its place.
        val dao = FakeIdentityDao()
        dao.upsert(
            GameStorefrontIdentityEntity(
                gameId = 1L, store = "STEAM", storeId = "620",
                confidence = MatchConfidence.EXACT.name, linkedAt = 0L,
            )
        )
        val provider = FakeProvider(
            searchResult = { StorefrontOutcome.Ok(listOf(candidate("400", "Portal"))) },
            metadata = { StorefrontOutcome.Ok(preset("Portal")) },
        )

        resolver(dao, provider).resolve(
            game(storefront = "STEAM", storefrontGameId = "620"),
            allowAutoLink = false,
            titleOverride = "Portal",
        )

        assertEquals(1, provider.searches)
        // Still only a look: the stored link survives until the user picks a replacement.
        assertEquals("620", dao.rows.values.single().storeId)
    }

    @Test
    fun `a match found by title carries the field it was chosen from`() = runTest {
        // A preview that links nothing still has to be able to SHOW what it found, or the screen
        // can only say "matched" about a game it did not match.
        val provider = FakeProvider(
            searchResult = { StorefrontOutcome.Ok(listOf(candidate("620", "Portal 2"))) },
            metadata = { StorefrontOutcome.Ok(preset("Portal 2")) },
        )

        val resolution = resolver(FakeIdentityDao(), provider).resolve(game(), allowAutoLink = false)

        val linked = resolution.byStore[Storefront.STEAM] as StorefrontMetadataResolver.Resolution.Linked
        assertEquals(MatchConfidence.EXACT, linked.match?.confidence)
        assertEquals("620", linked.match?.best?.candidate?.storeId)
        assertEquals(listOf(MatchSignal.EXACT_TITLE), linked.match?.best?.signals)
    }

    @Test
    fun `a search failure is recorded as unavailable and never as no match`() = runTest {
        val dao = FakeIdentityDao()
        val provider = FakeProvider(
            searchResult = { StorefrontOutcome.Failure(StorefrontFailure.RATE_LIMITED) },
        )

        val resolution = resolver(dao, provider).resolve(game())

        assertEquals(listOf(Storefront.STEAM), resolution.unavailableStores)
        assertTrue(resolution.presets.isEmpty())
    }

    @Test
    fun `one provider throwing does not take the others down with it`() = runTest {
        val dao = FakeIdentityDao()
        val exploding = object : StorefrontMetadataProvider {
            override val store = Storefront.GOG
            override suspend fun search(titles: List<String>): StorefrontOutcome<List<StorefrontCandidate>> =
                throw IllegalStateException("endpoint moved")
            override suspend fun getMetadata(storeId: String) = StorefrontOutcome.NoMatch
            override suspend fun validateIdentity(storeId: String) = StorefrontOutcome.Ok(false)
        }
        val steam = FakeProvider(
            searchResult = { StorefrontOutcome.Ok(listOf(candidate("620", "Portal 2"))) },
            metadata = { StorefrontOutcome.Ok(preset("Portal 2")) },
        )

        val resolution = resolver(dao, exploding, steam).resolve(game())

        assertTrue(resolution.byStore[Storefront.GOG] is StorefrontMetadataResolver.Resolution.Unavailable)
        assertTrue(resolution.byStore[Storefront.STEAM] is StorefrontMetadataResolver.Resolution.Linked)
    }

    @Test
    fun `cancellation inside a provider propagates instead of becoming a provider error`() = runTest {
        val dao = FakeIdentityDao()
        val stopped = object : StorefrontMetadataProvider {
            override val store = Storefront.STEAM
            override suspend fun search(titles: List<String>): StorefrontOutcome<List<StorefrontCandidate>> =
                throw CancellationException("sync stopped")
            override suspend fun getMetadata(storeId: String) = StorefrontOutcome.NoMatch
            override suspend fun validateIdentity(storeId: String) = StorefrontOutcome.Ok(false)
        }

        assertFailsWith<CancellationException> { resolver(dao, stopped).resolve(game()) }
    }

    @Test
    fun `an unavailable provider is skipped entirely`() = runTest {
        val dao = FakeIdentityDao()
        val provider = FakeProvider(available = false)

        val resolution = resolver(dao, provider).resolve(game())

        assertNull(resolution.byStore[Storefront.STEAM])
        assertEquals(0, provider.searches)
    }

    // -- Confirming and unlinking -------------------------------------------------

    @Test
    fun `a user-confirmed match is stored as theirs`() = runTest {
        val dao = FakeIdentityDao()
        val resolver = resolver(dao, FakeProvider())

        resolver.confirm(1L, candidate("2280", "DOOM"), MatchConfidence.AMBIGUOUS)

        val row = dao.rows.values.single()
        assertTrue(row.userConfirmed)
        assertEquals("2280", row.storeId)
        assertEquals("DOOM", row.resolvedTitle)
    }

    @Test
    fun `unlinking one store leaves the others alone`() = runTest {
        val dao = FakeIdentityDao()
        dao.upsert(GameStorefrontIdentityEntity(1L, "STEAM", "620", confidence = "EXACT", linkedAt = 0L))
        dao.upsert(GameStorefrontIdentityEntity(1L, "GOG", "123", confidence = "EXACT", linkedAt = 0L))
        val resolver = resolver(dao, FakeProvider())

        resolver.unlink(1L, Storefront.STEAM)

        assertEquals(listOf("GOG"), dao.rows.values.map { it.store })
    }

    private companion object {
        const val FIXED_NOW = 1_700_000_000_000L
    }
}
