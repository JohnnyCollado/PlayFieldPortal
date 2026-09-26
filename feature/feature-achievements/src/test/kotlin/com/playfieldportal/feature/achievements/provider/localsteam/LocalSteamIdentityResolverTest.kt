package com.playfieldportal.feature.achievements.provider.localsteam

import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.core.data.database.dao.GameStorefrontIdentityDao
import com.playfieldportal.core.data.database.entity.GameEntity
import com.playfieldportal.core.data.database.entity.GameStorefrontIdentityEntity
import com.playfieldportal.feature.artwork.match.MatchConfidence
import com.playfieldportal.feature.artwork.match.MatchProvider
import com.playfieldportal.feature.artwork.match.MatchSignal
import com.playfieldportal.feature.artwork.match.MetadataPreset
import com.playfieldportal.feature.artwork.match.ScoredStorefrontCandidate
import com.playfieldportal.feature.artwork.match.Storefront
import com.playfieldportal.feature.artwork.match.StorefrontCandidate
import com.playfieldportal.feature.artwork.match.StorefrontFailure
import com.playfieldportal.feature.artwork.match.StorefrontIdentityRecord
import com.playfieldportal.feature.artwork.match.StorefrontMatchResult
import com.playfieldportal.feature.artwork.match.StorefrontMetadataResolver
import com.playfieldportal.feature.artwork.match.StorefrontOutcome
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The identification ladder: marker, then stored identity, then the 5-rule title matcher — and the
 * marker write-back that makes a folder identify itself from then on.
 *
 * The rules being pinned here are the ones a user's game folder depends on: an existing marker is
 * never questioned and never overwritten, an ambiguous title never writes anything, and a store that
 * could not be reached is never reported as a game that does not exist.
 */
class LocalSteamIdentityResolverTest {

    private val storefront = mockk<StorefrontMetadataResolver>()
    private val identities = mockk<GameStorefrontIdentityDao>(relaxed = true)
    private val writer = mockk<LocalSteamSchemaWriter>(relaxed = true)
    private val credentials = mockk<AchievementCredentialsProvider>()
    private val resolver = LocalSteamIdentityResolver(storefront, identities, writer, credentials)

    private val game = GameEntity(
        id = 7L, title = "Portal 2", platformId = "windows", romPath = null,
        packageName = null, emulatorPackage = null, artworkUri = null, heroUri = null,
        logoUri = null, description = null, developer = null, publisher = null,
        releaseYear = null, genre = null, steamGridDbId = null,
    )

    private fun anchor(markerAppId: String? = null) = FolderAnchor(
        treeUri = "content://tree/Portal 2",
        folderName = "Portal 2",
        folderDocId = "Portal 2",
        settingsDirDocId = markerAppId?.let { "Portal 2/steam_settings" },
        settingsParentDocId = "Portal 2",
        markerAppId = markerAppId,
    )

    private fun trackingOn() {
        coEvery { credentials.localSteamTrackingEnabled() } returns true
    }

    private fun candidate(storeId: String, title: String) = ScoredStorefrontCandidate(
        candidate = StorefrontCandidate(store = Storefront.STEAM, storeId = storeId, title = title),
        signals = listOf(MatchSignal.EXACT_TITLE),
    )

    @Test
    fun `a marker short-circuits the whole ladder — the store is never even asked`() = runTest {
        val outcome = resolver.identify(anchor(markerAppId = "620"), game)

        assertEquals(LocalSteamIdentityResolver.Outcome.Resolved("620", AppIdSource.MARKER, written = false), outcome)
        // The point of the short-circuit: no request, no search, and nothing written.
        coVerify(exactly = 0) { storefront.resolve(any(), any(), any()) }
        coVerify(exactly = 0) { writer.writeAppIdMarker(any(), any(), any()) }
        coVerify(exactly = 0) { identities.get(any(), any()) }
    }

    @Test
    fun `a stored storefront identity is used without asking the store, and written back`() = runTest {
        trackingOn()
        coEvery { identities.get(7L, "STEAM") } returns storedIdentity("620")
        coEvery { writer.writeAppIdMarker(any(), any(), "620") } returns
            LocalSteamSchemaWriter.MarkerWrite.Written("Portal 2/steam_settings")

        val outcome = resolver.identify(anchor(), game)

        assertEquals(
            LocalSteamIdentityResolver.Outcome.Resolved("620", AppIdSource.STORED_IDENTITY, written = true),
            outcome,
        )
        // Offline-safe: a confirmed id needs no verification request to be usable.
        coVerify(exactly = 0) { storefront.resolve(any(), any(), any()) }
        coVerify { writer.writeAppIdMarker("content://tree/Portal 2", "Portal 2", "620") }
    }

    @Test
    fun `an EXACT title match writes the marker and records it as a title match`() = runTest {
        trackingOn()
        coEvery { identities.get(any(), any()) } returns null
        coEvery { storefront.resolve(game, false, false) } returns linkedResolution("620", MatchConfidence.EXACT)
        coEvery { writer.writeAppIdMarker(any(), any(), "620") } returns
            LocalSteamSchemaWriter.MarkerWrite.Written("Portal 2/steam_settings")

        val outcome = resolver.identify(anchor(), game)

        assertEquals(
            LocalSteamIdentityResolver.Outcome.Resolved("620", AppIdSource.TITLE_MATCH, written = true),
            outcome,
        )
    }

    @Test
    fun `an ambiguous title asks the user and writes absolutely nothing`() = runTest {
        trackingOn()
        coEvery { identities.get(any(), any()) } returns null
        val result = StorefrontMatchResult(
            store = Storefront.STEAM,
            confidence = MatchConfidence.AMBIGUOUS,
            best = candidate("620", "Portal 2"),
            alternatives = listOf(candidate("400", "Portal")),
        )
        coEvery { storefront.resolve(game, false, false) } returns
            StorefrontMetadataResolver.GameResolution(
                7L,
                mapOf(Storefront.STEAM to StorefrontMetadataResolver.Resolution.NeedsConfirmation(result)),
            )

        val outcome = resolver.identify(anchor(), game)

        assertTrue(outcome is LocalSteamIdentityResolver.Outcome.NeedsConfirmation)
        assertEquals(result, outcome.result)
        coVerify(exactly = 0) { writer.writeAppIdMarker(any(), any(), any()) }
    }

    @Test
    fun `an unreachable store is Unavailable, never NoMatch`() = runTest {
        trackingOn()
        coEvery { identities.get(any(), any()) } returns null
        coEvery { storefront.resolve(game, false, false) } returns
            StorefrontMetadataResolver.GameResolution(
                7L,
                mapOf(
                    Storefront.STEAM to StorefrontMetadataResolver.Resolution.Unavailable(
                        Storefront.STEAM,
                        StorefrontOutcome.Failure(StorefrontFailure.NETWORK_ERROR),
                    ),
                ),
            )

        val outcome = resolver.identify(anchor(), game)

        // The distinction the whole provider layer exists to keep: "could not ask" is not "no game".
        assertTrue(outcome is LocalSteamIdentityResolver.Outcome.Unavailable)
        coVerify(exactly = 0) { writer.writeAppIdMarker(any(), any(), any()) }
    }

    @Test
    fun `a store that answered with nothing is NoMatch, and still writes nothing`() = runTest {
        trackingOn()
        coEvery { identities.get(any(), any()) } returns null
        coEvery { storefront.resolve(game, false, false) } returns
            StorefrontMetadataResolver.GameResolution(
                7L,
                mapOf(Storefront.STEAM to StorefrontMetadataResolver.Resolution.NoMatch),
            )

        assertEquals(LocalSteamIdentityResolver.Outcome.NoMatch, resolver.identify(anchor(), game))
        coVerify(exactly = 0) { writer.writeAppIdMarker(any(), any(), any()) }
    }

    @Test
    fun `an id is still usable when tracking is off — it is simply not written down`() = runTest {
        coEvery { credentials.localSteamTrackingEnabled() } returns false
        coEvery { identities.get(7L, "STEAM") } returns storedIdentity("620")

        val outcome = resolver.identify(anchor(), game)

        assertEquals(
            LocalSteamIdentityResolver.Outcome.Resolved("620", AppIdSource.STORED_IDENTITY, written = false),
            outcome,
        )
        coVerify(exactly = 0) { writer.writeAppIdMarker(any(), any(), any()) }
    }

    @Test
    fun `an existing marker file is reported as already present and never rewritten`() = runTest {
        trackingOn()
        coEvery { writer.writeAppIdMarker(any(), any(), "620") } returns
            LocalSteamSchemaWriter.MarkerWrite.AlreadyPresent("Portal 2/steam_settings")

        val outcome = resolver.confirm(anchor(), "620")

        assertEquals(
            LocalSteamIdentityResolver.Outcome.Resolved("620", AppIdSource.TITLE_MATCH, written = false),
            outcome,
        )
    }

    @Test
    fun `a folder PFP cannot write into reports WriteFailed rather than pretending to link`() = runTest {
        trackingOn()
        coEvery { writer.writeAppIdMarker(any(), any(), "620") } returns
            LocalSteamSchemaWriter.MarkerWrite.Failed

        assertEquals(
            LocalSteamIdentityResolver.Outcome.WriteFailed("620"),
            resolver.confirm(anchor(), "620"),
        )
    }

    @Test
    fun `a folder with no library game resolves against its own folder name`() = runTest {
        trackingOn()
        coEvery { identities.get(any(), any()) } returns null
        coEvery { storefront.resolve(any(), false, false) } answers {
            // The synthetic row carries the folder name as its title, and nothing else PFP invented.
            val subject = firstArg<GameEntity>()
            assertEquals("Portal 2", subject.title)
            assertEquals(0L, subject.id)
            linkedResolution("620", MatchConfidence.HIGH)
        }
        coEvery { writer.writeAppIdMarker(any(), any(), "620") } returns
            LocalSteamSchemaWriter.MarkerWrite.Written("Portal 2/steam_settings")

        val outcome = resolver.identify(anchor(), game = null)

        assertEquals(
            LocalSteamIdentityResolver.Outcome.Resolved("620", AppIdSource.TITLE_MATCH, written = true),
            outcome,
        )
    }

    private fun storedIdentity(storeId: String) = GameStorefrontIdentityEntity(
        gameId = 7L,
        store = "STEAM",
        storeId = storeId,
        confidence = "EXACT",
        userConfirmed = true,
        linkedAt = 1_700_000_000_000L,
    )

    private fun linkedResolution(storeId: String, confidence: MatchConfidence) =
        StorefrontMetadataResolver.GameResolution(
            7L,
            mapOf(
                Storefront.STEAM to StorefrontMetadataResolver.Resolution.Linked(
                    identity = StorefrontIdentityRecord(
                        store = Storefront.STEAM,
                        storeId = storeId,
                        confidence = confidence,
                    ),
                    preset = MetadataPreset(MatchProvider.STEAM, title = "Portal 2"),
                    newlyLinked = true,
                ),
            ),
        )
}
