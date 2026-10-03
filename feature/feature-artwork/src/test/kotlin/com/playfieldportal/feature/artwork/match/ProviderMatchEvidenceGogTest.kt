package com.playfieldportal.feature.artwork.match

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GOG in the provider tables the matcher reads, and as a title-search evidence source. */
class ProviderMatchEvidenceGogTest {

    private val gog = mockk<GogMetadataProvider>()
    private val evidence = ProviderMatchEvidence(
        steamGridDb = mockk(relaxed = true),
        screenScraper = mockk(relaxed = true),
        igdbApi = mockk(relaxed = true),
        theGamesDb = mockk(relaxed = true),
        steam = mockk(relaxed = true),
        gog = gog,
    )

    @Test
    fun `GOG is a metadata source found by title, and by nothing stored on the game row`() {
        val capability = ProviderCapabilities[MatchProvider.GOG]

        assertEquals("GOG", MatchProvider.GOG.label)
        assertTrue(capability.supportsTitleSearch)
        assertTrue(capability.suppliesMetadata)
        // No id column on `games`, no ROM, and the import-captured pair is not trusted as a GOG
        // product id — so the matcher must never try to address GOG by any of them.
        assertFalse(capability.addressableBySavedId)
        assertFalse(capability.addressableByRomHash)
        assertFalse(capability.addressableByStorefrontId)
        // Text only: the Artwork Studio has no GOG source.
        assertFalse(capability.suppliesArtwork)
    }

    @Test
    fun `a GOG title search goes through the provider, normalized the way the resolver would send it`() = runTest {
        val expected = StorefrontTitleNormalizer.normalize("DOOM (2016)").searchCandidates
        coEvery { gog.search(expected) } returns StorefrontOutcome.Ok(
            listOf(
                StorefrontCandidate(
                    store = Storefront.GOG, storeId = "1390579243", title = "DOOM (2016)",
                    releaseYear = 2016, thumbUrl = "https://images.gog-statics.com/doom.png",
                ),
            )
        )

        val candidates = evidence.searchByTitle(MatchProvider.GOG, "DOOM (2016)", "windows")

        assertEquals(
            listOf(
                GameCandidate(
                    provider = MatchProvider.GOG,
                    providerGameId = "1390579243",
                    title = "DOOM (2016)",
                    releaseYear = 2016,
                    thumbUrl = "https://images.gog-statics.com/doom.png",
                ),
            ),
            candidates,
        )
        coVerify(exactly = 1) { gog.search(expected) }
    }

    @Test
    fun `a GOG search that fails or finds nothing is an empty list here`() = runTest {
        coEvery { gog.search(any()) } returns StorefrontOutcome.Failure(StorefrontFailure.NETWORK_ERROR)
        assertEquals(emptyList<GameCandidate>(), evidence.searchByTitle(MatchProvider.GOG, "Quake", "windows"))

        coEvery { gog.search(any()) } returns StorefrontOutcome.NoMatch
        assertEquals(emptyList<GameCandidate>(), evidence.searchByTitle(MatchProvider.GOG, "Quake", "windows"))
    }

    @Test
    fun `GOG answers neither a ROM checksum nor a storefront pair`() = runTest {
        assertNull(evidence.candidateByRomHash(MatchProvider.GOG, "deadbeef", "windows"))
        assertNull(evidence.candidateByStorefront(MatchProvider.GOG, "GOG", "1390579243"))
    }
}
