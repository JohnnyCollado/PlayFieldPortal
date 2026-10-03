package com.playfieldportal.feature.artwork.api

import com.playfieldportal.core.domain.model.NotificationAction
import com.playfieldportal.core.domain.model.PfpErrorCode
import com.playfieldportal.core.domain.model.ResultOutcome
import com.playfieldportal.core.domain.model.toNotificationAction
import org.junit.Assert.assertEquals
import org.junit.Test

/** An artwork or metadata pass as the Results sheet lists it: per game, with the cause's code. */
class ScrapeReportingTest {

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
                GameScrapeOutcome(2, "Gex", success = false, message = "No match found"),
            )
        )
        assertEquals("Gex", results.items.first().primary)
        assertEquals(ResultOutcome.FAILED, results.items.first().outcome)
        assertEquals("AR-3003", results.items.first().code)
        assertEquals(NotificationAction.OpenGame(2), results.items.first().action?.toNotificationAction())
        assertEquals("Updated", results.labels.done)
    }
}
