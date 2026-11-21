package com.playfieldportal.feature.artwork.api

import com.playfieldportal.core.domain.model.NotificationAction
import com.playfieldportal.core.domain.model.PfpErrorCode
import com.playfieldportal.core.domain.model.ResultOutcome
import com.playfieldportal.core.domain.model.toNotificationAction
import com.playfieldportal.core.domain.text.UiText
import com.playfieldportal.feature.artwork.R
import org.junit.Assert.assertEquals
import org.junit.Test

/** An artwork or metadata pass as the Results sheet lists it: per game, with the cause's code. */
class ScrapeReportingTest {

    // No Context in a plain JUnit test: a text "resolves" to its own description.
    private val resolve: (UiText) -> String = { it.toString() }

    @Test
    fun `our own not-found texts and raw words both map to their codes`() {
        assertEquals(PfpErrorCode.AR_3003, classifyScrapeFailureText(UiText.res(R.string.art_error_not_found_any_source)))
        assertEquals(PfpErrorCode.AR_3003, classifyScrapeFailureText(UiText.res(R.string.art_error_game_not_found)))
        assertEquals(PfpErrorCode.AR_3002, classifyScrapeFailureText(UiText.raw("HTTP 429 rate limited")))
        assertEquals(PfpErrorCode.AR_9001, classifyScrapeFailureText(null))
    }

    @Test
    fun `failure messages map to their codes`() {
        assertEquals(PfpErrorCode.AR_3003, classifyScrapeFailure("No match found for Gex"))
        assertEquals(PfpErrorCode.AR_3002, classifyScrapeFailure("HTTP 429 rate limited"))
        assertEquals(PfpErrorCode.AR_3001, classifyScrapeFailure("Unable to resolve host api.thegamesdb.net"))
        assertEquals(PfpErrorCode.AR_9001, classifyScrapeFailure("weird"))
        assertEquals(PfpErrorCode.AR_9001, classifyScrapeFailure(null))
    }

    @Test
    fun `games become items that open the game, failures first and successes Updated`() {
        val results = scrapeResults(
            listOf(
                GameScrapeOutcome(1, "Spyro", success = true),
                GameScrapeOutcome(2, "Gex", success = false, message = UiText.raw("No match found")),
            ),
            resolve = resolve,
        )
        assertEquals("Gex", results.items.first().primary)
        assertEquals(ResultOutcome.FAILED, results.items.first().outcome)
        assertEquals("AR-3003", results.items.first().code)
        assertEquals(NotificationAction.OpenGame(2), results.items.first().action?.toNotificationAction())
        assertEquals(resolve(UiText.res(R.string.art_results_label_updated)), results.labels.done)
    }
}
