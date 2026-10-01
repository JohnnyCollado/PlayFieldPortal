package com.playfieldportal.feature.artwork.api

import com.playfieldportal.core.domain.model.NotificationAction
import com.playfieldportal.core.domain.model.NotificationDetail
import com.playfieldportal.core.domain.model.PfpErrorCode
import com.playfieldportal.core.domain.model.ResultItem
import com.playfieldportal.core.domain.model.ResultOutcome
import com.playfieldportal.core.domain.model.ResultsLabels
import com.playfieldportal.core.domain.model.toDetailAction

// How an artwork or metadata pass reads in the notification panel (the notification details plan
// §10): one Results item per game, "Updated" or "Failed" with the reason and its code.

/** A provider's or exception's words read for their cause. */
fun classifyScrapeFailure(message: String?): PfpErrorCode {
    val m = message?.lowercase().orEmpty()
    return when {
        "no match" in m || "not found" in m || "no result" in m || "no artwork" in m -> PfpErrorCode.AR_3003
        "429" in m || "rate" in m || "limit" in m || "unauthor" in m || "401" in m || "403" in m ||
            "api key" in m || "forbidden" in m -> PfpErrorCode.AR_3002
        "unable to resolve host" in m || "timeout" in m || "timed out" in m || "network" in m ||
            "offline" in m || "connect" in m -> PfpErrorCode.AR_3001
        "space" in m || "enospc" in m || "write" in m -> PfpErrorCode.AR_2001
        else -> PfpErrorCode.AR_9001
    }
}

fun GameScrapeOutcome.toResultItem(): ResultItem {
    val open = NotificationAction.OpenGame(gameId).toDetailAction()
    if (success) return ResultItem(primary = title, outcome = ResultOutcome.DONE, action = open)
    val code = classifyScrapeFailure(message)
    return ResultItem(
        primary = title,
        outcome = ResultOutcome.FAILED,
        reason = message?.takeIf { it.isNotBlank() } ?: code.why,
        code = code.id,
        action = open,
    )
}

/** One item per game, failures first, successes labelled "Updated". */
fun scrapeResults(outcomes: List<GameScrapeOutcome>): NotificationDetail.Results =
    NotificationDetail.results(outcomes.map { it.toResultItem() }, labels = ResultsLabels(done = "Updated"))
