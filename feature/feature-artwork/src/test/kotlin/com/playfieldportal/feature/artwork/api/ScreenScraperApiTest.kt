package com.playfieldportal.feature.artwork.api

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenScraperApiTest {

    private val api = ScreenScraperApi(httpClient = mockk(relaxed = true), credentials = mockk(relaxed = true))

    // ScreenScraper serves these as HTTP 200 with a plain-text body — classification is what
    // keeps a batch run from hammering the API after a quota/credential failure.
    @Test
    fun `plain-text error bodies classify to typed reasons`() {
        assertEquals(SsFailureReason.API_CLOSED,
            api.failureForTextBody("API closed for non-registered members"))
        assertEquals(SsFailureReason.DAILY_QUOTA_EXCEEDED,
            api.failureForTextBody("Votre quota de scrape est atteint"))
        assertEquals(SsFailureReason.BAD_DEV_CREDENTIALS,
            api.failureForTextBody("Erreur de login : Verifiez vos identifiants developpeur !"))
        assertEquals(SsFailureReason.PARSE_ERROR,
            api.failureForTextBody("<html>some cdn error page</html>"))
    }

    @Test
    fun `name search keeps ranked hits and drops the empty padding entries`() {
        val body = """
            {"response":{"jeux":[
              {"id":"3506","noms":[{"region":"jp","text":"Final Fantasy VI Advance JP"},{"region":"us","text":"Final Fantasy VI Advance"}],
               "dates":[{"region":"us","text":"2007-02-05"}]},
              {"id":"421","noms":[{"region":"wor","text":"Final Fantasy VI"}]},
              {}
            ]}}
        """.trimIndent()

        val hits = api.parseSearch(body)

        assertEquals(
            listOf(
                SsSearchHit(ssId = 3506L, title = "Final Fantasy VI Advance", releaseYear = 2007),
                SsSearchHit(ssId = 421L, title = "Final Fantasy VI", releaseYear = null),
            ),
            hits,
        )
    }

    @Test
    fun `a plain-text search error is no hits, not a crash`() {
        assertEquals(emptyList<SsSearchHit>(), api.parseSearch("API closed for non-registered members"))
    }

    /** The HTTP 400 on a Windows game: jeuInfos was sent a bare systemeid with nothing to match. */
    @Test
    fun `a lookup needs a game id or a ROM checksum or file name`() {
        val nothing = com.playfieldportal.feature.artwork.rom.RomIdentity(crc32 = null, sizeBytes = null, fileName = null)
        val sizeOnly = com.playfieldportal.feature.artwork.rom.RomIdentity(crc32 = null, sizeBytes = 4096L, fileName = null)

        assertEquals(false, ScreenScraperApi.canLookUp(rom = null, ssGameId = null))
        assertEquals(false, ScreenScraperApi.canLookUp(rom = nothing, ssGameId = null))
        assertEquals(false, ScreenScraperApi.canLookUp(rom = sizeOnly, ssGameId = null))
        assertEquals(true, ScreenScraperApi.canLookUp(rom = null, ssGameId = 42L))
        assertEquals(true, ScreenScraperApi.canLookUp(rom = nothing.copy(crc32 = "ABCD1234"), ssGameId = null))
        assertEquals(true, ScreenScraperApi.canLookUp(rom = nothing.copy(fileName = "game.nds"), ssGameId = null))
    }

    @Test
    fun `batch stopper flags cover quota and credential failures only`() {
        fun resultWith(reason: SsFailureReason) = SsLookupResult(
            info = null,
            diagnostics = SsLookupDiagnostics(
                fileName = "x.gba", platformId = "gba", systemId = 12,
                userCredentialsPresent = false, sentCrc = false, failureReason = reason,
            ),
        )
        assertEquals(true,  resultWith(SsFailureReason.DAILY_QUOTA_EXCEEDED).isBatchStopper)
        assertEquals(true,  resultWith(SsFailureReason.BAD_DEV_CREDENTIALS).isBatchStopper)
        assertEquals(true,  resultWith(SsFailureReason.API_CLOSED).isBatchStopper)
        assertEquals(false, resultWith(SsFailureReason.NO_MATCH).isBatchStopper)
        assertEquals(false, resultWith(SsFailureReason.RATE_LIMITED).isBatchStopper)
        assertEquals(true,  resultWith(SsFailureReason.TOO_MANY_UNRECOGNIZED).stopsUnhashedLookups)
        assertEquals(false, resultWith(SsFailureReason.TOO_MANY_UNRECOGNIZED).isBatchStopper)
    }
}
