package com.playfieldportal.feature.artwork.api

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenScraperApiTest {

    private val api = ScreenScraperApi(httpClient = mockk(relaxed = true), keyProvider = mockk(relaxed = true))

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
