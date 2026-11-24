package com.playfieldportal.feature.library.scanner

import com.playfieldportal.core.domain.model.NotificationAction
import com.playfieldportal.core.domain.model.NotificationDetail
import com.playfieldportal.core.domain.model.PfpErrorCode
import com.playfieldportal.core.domain.model.ResultItem
import com.playfieldportal.core.domain.model.ResultOutcome
import com.playfieldportal.core.domain.model.toDetailAction
import com.playfieldportal.core.domain.text.UiText
import com.playfieldportal.feature.library.R

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
fun classifyScanError(message: UiText?): PfpErrorCode = when (message) {
    // Our own messages are told apart by which text they are; only a raw exception message is read.
    is UiText.Res -> when (message.id) {
        R.string.scan_error_permission_lost -> PfpErrorCode.SC_2001
        R.string.scan_error_rom_dir_not_found -> PfpErrorCode.SC_2002
        else -> PfpErrorCode.SC_9001
    }
    is UiText.Raw -> classifyRawScanError(message.value)
    else -> PfpErrorCode.SC_9001
}

private fun classifyRawScanError(message: String): PfpErrorCode {
    val m = message.lowercase()
    return when {
        "permission" in m || "denied" in m || "denial" in m || "access" in m -> PfpErrorCode.SC_2001
        "not found" in m || "no longer exists" in m || "does not exist" in m ||
            "unmounted" in m || "missing" in m -> PfpErrorCode.SC_2002
        else -> PfpErrorCode.SC_9001
    }
}

/**
 * [resolve] turns the card's text into the user's language; it is a parameter so this stays free of
 * a Context (callers pass `{ it.resolve(context) }`).
 */
fun PlatformScanOutcome.toResultItem(resolve: (UiText) -> String): ResultItem {
    val open = NotificationAction.OpenMemoryCard(platformId).toDetailAction()
    return when (status) {
        ScanStatus.COMPLETED -> ResultItem(
            primary = displayName,
            outcome = ResultOutcome.DONE,
            badge = if (added > 0) "+$added" else resolve(UiText.res(R.string.scan_badge_no_new)),
            reason = resolve(completedReason()),
            action = open,
        )
        ScanStatus.SKIPPED_NO_SOURCE, ScanStatus.SKIPPED_BUSY -> ResultItem(
            primary = displayName,
            outcome = ResultOutcome.SKIPPED,
            reason = errorMessage?.let(resolve) ?: errorCode()?.why,
            code = errorCode()?.id,
            action = open,
        )
        ScanStatus.FAILED -> ResultItem(
            primary = displayName,
            outcome = ResultOutcome.FAILED,
            reason = errorMessage?.let(resolve) ?: errorCode()?.why,
            code = errorCode()?.id,
            action = open,
        )
    }
}

/** Scan All and ROM Root: one item per card, failures first. */
fun scanResults(
    outcomes: List<PlatformScanOutcome>,
    extra: List<ResultItem> = emptyList(),
    resolve: (UiText) -> String,
): NotificationDetail.Results =
    NotificationDetail.results(outcomes.map { it.toResultItem(resolve) } + extra)

/** A single card that did not scan cleanly, as a note with its code; null when it did. */
fun PlatformScanOutcome.failureNotes(resolve: (UiText) -> String): NotificationDetail? {
    val code = errorCode() ?: return null
    val message = errorMessage?.let(resolve)
    return NotificationDetail.notes(code = code, summary = message ?: code.title, diagnostic = message)
}

private fun PlatformScanOutcome.completedReason(): UiText {
    val summary = when {
        added == 0 && markedMissing == 0 -> UiText.res(R.string.scan_reason_no_new)
        added == 0 -> UiText.res(R.string.scan_reason_no_new_marked_missing, markedMissing)
        markedMissing == 0 -> UiText.plural(R.plurals.scan_reason_added, added)
        else -> UiText.plural(R.plurals.scan_reason_added_marked_missing, added, added, markedMissing)
    }
    return errorMessage?.let { UiText.res(R.string.scan_with_error_note, summary, it) } ?: summary
}
