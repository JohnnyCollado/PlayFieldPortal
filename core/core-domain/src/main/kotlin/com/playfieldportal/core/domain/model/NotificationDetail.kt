package com.playfieldportal.core.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a notification has to say beyond its one-line title, stored in [PfpNotification.payload].
 *
 * The depth of a row follows from it: no detail is a Simple row (Confirm acts), [Notes] opens the
 * Notes sheet and [Results] opens the Results sheet. See the notification details plan §4.
 */
@Serializable
sealed interface NotificationDetail {

    /** Type 1: an explanation. [code] is a [PfpErrorCode] id; its help text fills missing sections. */
    @Serializable
    @SerialName("notes")
    data class Notes(
        /** "What happened". */
        val summary: String? = null,
        val sections: List<NoteSection> = emptyList(),
        /** The key/value box, e.g. Emulator: DuckStation. */
        val facts: List<NoteFact> = emptyList(),
        val code: String? = null,
        /** Collapsed monospace block, copied by Copy Details. Dropped first when a payload is too big. */
        val diagnostic: String? = null,
    ) : NotificationDetail

    /** Type 2: every item the work touched. Build with [NotificationDetail.results] to get the order and cap. */
    @Serializable
    @SerialName("results")
    data class Results(
        val summary: String? = null,
        val items: List<ResultItem>,
        val labels: ResultsLabels = ResultsLabels(),
        /** Items cut to fit the caps; the sheet shows a "+N more" row. */
        val truncated: Int = 0,
    ) : NotificationDetail {
        fun count(outcome: ResultOutcome): Int = items.count { it.outcome == outcome }
    }

    companion object {
        const val MAX_ITEMS = 500

        /** Failures first, then skips, then successes (input order kept within each), capped at [MAX_ITEMS]. */
        fun results(
            items: List<ResultItem>,
            summary: String? = null,
            labels: ResultsLabels = ResultsLabels(),
        ): Results {
            val ordered = items.sortedBy { it.outcome.ordinal }
            return Results(
                summary = summary,
                items = ordered.take(MAX_ITEMS),
                labels = labels,
                truncated = (ordered.size - MAX_ITEMS).coerceAtLeast(0),
            )
        }

        /** A note for an error code, taking its "Why" and "What you can do" from the registry. */
        fun notes(
            code: PfpErrorCode,
            summary: String? = null,
            facts: List<NoteFact> = emptyList(),
            diagnostic: String? = null,
        ): Notes = Notes(
            summary = summary ?: code.title,
            sections = listOfNotNull(
                code.why?.let { NoteSection("Why", it) },
                NoteSection("What you can do", code.whatYouCanDo),
            ),
            facts = facts,
            code = code.id,
            diagnostic = diagnostic,
        )
    }
}

@Serializable
data class NoteSection(val heading: String, val body: String)

@Serializable
data class NoteFact(val label: String, val value: String)

/** Declared in display order: the sheet and [NotificationDetail.results] sort by ordinal. */
@Serializable
enum class ResultOutcome { FAILED, SKIPPED, DONE }

/** What each bucket is called for this kind of work — "Added" for a scan, "Updated" for artwork. */
@Serializable
data class ResultsLabels(
    val done: String = "Added",
    val failed: String = "Failed",
    val skipped: String = "Skipped",
) {
    fun of(outcome: ResultOutcome): String = when (outcome) {
        ResultOutcome.FAILED -> failed
        ResultOutcome.SKIPPED -> skipped
        ResultOutcome.DONE -> done
    }
}

@Serializable
data class ResultItem(
    val primary: String,
    val outcome: ResultOutcome,
    /** Right-hand text in the list ("+31"); null shows the outcome's label. */
    val badge: String? = null,
    val reason: String? = null,
    val code: String? = null,
    val path: String? = null,
    val action: DetailAction? = null,
)

/** A [NotificationAction] in a JSON-safe shape, sharing its typeKey/arg encoding. */
@Serializable
data class DetailAction(val typeKey: String, val arg: String? = null)

fun NotificationAction.toDetailAction(): DetailAction = DetailAction(typeKey, arg)

fun DetailAction.toNotificationAction(): NotificationAction = NotificationAction.decode(typeKey, arg)

/** What Confirm on a row does: act (SIMPLE) or open the matching sheet. */
enum class NotificationDepth { SIMPLE, NOTES, RESULTS }

/**
 * Reads and writes [PfpNotification.payload]. Decoding never throws — rows written before payloads
 * existed, or by a newer build, read as "no detail" — and encoding keeps every payload under
 * [MAX_PAYLOAD_BYTES] so a full history stays small.
 */
object NotificationDetailCodec {

    const val MAX_PAYLOAD_BYTES = 64 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "t"
        encodeDefaults = false
    }

    fun decode(payload: String?): NotificationDetail? {
        if (payload.isNullOrBlank()) return null
        return runCatching { json.decodeFromString(NotificationDetail.serializer(), payload) }.getOrNull()
    }

    fun encode(detail: NotificationDetail): String {
        var current = detail
        var encoded = json.encodeToString(NotificationDetail.serializer(), current)
        if (encoded.fits()) return encoded

        if (current is NotificationDetail.Notes && current.diagnostic != null) {
            current = current.copy(diagnostic = null)
            encoded = json.encodeToString(NotificationDetail.serializer(), current)
            if (encoded.fits()) return encoded
        }

        if (current is NotificationDetail.Results) {
            var results: NotificationDetail.Results = current
            while (!encoded.fits() && results.items.isNotEmpty()) {
                val keep = results.items.size / 2
                results = results.copy(
                    items = results.items.take(keep),
                    truncated = results.truncated + (results.items.size - keep),
                )
                encoded = json.encodeToString(NotificationDetail.serializer(), results)
            }
            return encoded
        }

        // A note whose prose alone is over the cap: keep the summary and code, which is what matters.
        val notes = current as NotificationDetail.Notes
        return json.encodeToString(
            NotificationDetail.serializer(),
            NotificationDetail.Notes(summary = notes.summary?.take(4_000), code = notes.code),
        )
    }

    private fun String.fits() = toByteArray().size <= MAX_PAYLOAD_BYTES
}
