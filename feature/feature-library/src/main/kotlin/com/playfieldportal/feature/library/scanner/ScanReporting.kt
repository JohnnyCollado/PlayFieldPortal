package com.playfieldportal.feature.library.scanner

import com.playfieldportal.core.domain.model.NotificationAction
import com.playfieldportal.core.domain.model.NotificationDetail
import com.playfieldportal.core.domain.model.PfpErrorCode
import com.playfieldportal.core.domain.model.ResultItem
import com.playfieldportal.core.domain.model.ResultOutcome
import com.playfieldportal.core.domain.model.toDetailAction

// How scan outcomes read in the notification panel (the notification details plan §10): one
// Results item per Memory Card, and a Notes sheet with its error code when a single card fails.
// Shared by the XMB and the Library Manager so both say the same thing about the same scan.

/** The registry code for a card that did not scan cleanly, or null when it did. */
fun PlatformScanOutcome.errorCode(): PfpErrorCode? = when (status) {
    ScanStatus.COMPLETED -> null
    ScanStatus.SKIPPED_NO_SOURCE -> PfpErrorCode.SC_1001
    ScanStatus.SKIPPED_BUSY -> PfpErrorCode.SC_1002
    ScanStatus.FAILED -> classifyScanError(errorMessage)
}

/** A failure message read for its cause: lost access and a vanished folder have their own codes. */
fun classifyScanError(message: String?): PfpErrorCode {
    val m = message?.lowercase().orEmpty()
    return when {
        "permission" in m || "denied" in m || "denial" in m || "access" in m -> PfpErrorCode.SC_2001
        "not found" in m || "no longer exists" in m || "does not exist" in m ||
            "unmounted" in m || "missing" in m -> PfpErrorCode.SC_2002
        else -> PfpErrorCode.SC_9001
    }
}

fun PlatformScanOutcome.toResultItem(): ResultItem {
    val open = NotificationAction.OpenMemoryCard(platformId).toDetailAction()
    return when (status) {
        ScanStatus.COMPLETED -> ResultItem(
            primary = displayName,
            outcome = ResultOutcome.DONE,
            badge = if (added > 0) "+$added" else "No new",
            reason = buildString {
                append(if (added == 0) "No new games" else "$added new game(s) added")
                if (markedMissing > 0) append(", $markedMissing marked missing")
                errorMessage?.let { append(" ($it)") }
            },
            action = open,
        )
        ScanStatus.SKIPPED_NO_SOURCE, ScanStatus.SKIPPED_BUSY -> ResultItem(
            primary = displayName,
            outcome = ResultOutcome.SKIPPED,
            reason = errorMessage ?: errorCode()?.why,
            code = errorCode()?.id,
            action = open,
        )
        ScanStatus.FAILED -> ResultItem(
            primary = displayName,
            outcome = ResultOutcome.FAILED,
            reason = errorMessage ?: errorCode()?.why,
            code = errorCode()?.id,
            action = open,
        )
    }
}

/** Scan All and ROM Root: one item per card, failures first. */
fun scanResults(outcomes: List<PlatformScanOutcome>, extra: List<ResultItem> = emptyList()): NotificationDetail.Results =
    NotificationDetail.results(outcomes.map { it.toResultItem() } + extra)

/** A single card that did not scan cleanly, as a note with its code; null when it did. */
fun PlatformScanOutcome.failureNotes(): NotificationDetail? {
    val code = errorCode() ?: return null
    return NotificationDetail.notes(code = code, summary = errorMessage ?: code.title, diagnostic = errorMessage)
}
