package com.playfieldportal.core.domain.playlist

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

enum class PlaylistKind { MUSIC, VIDEO }

/**
 * A library item reduced to what matching needs. [relativePath] is the folder under the scanned
 * root (no root name), null or blank for a file in the root itself.
 */
data class PlaylistCandidate(
    val id: String,
    val relativePath: String?,
    val displayName: String,
    val title: String? = null,
    val artist: String? = null,
    val durationMs: Long? = null,
)

enum class PlaylistSkipReason {
    /** A stream or other non-file address. `content://` entries also land here for now. */
    WEB_LINK,

    /** The file belongs to the other kind, or is itself a playlist. */
    WRONG_TYPE,

    /** An earlier entry already resolved to the same item. */
    DUPLICATE,
}

sealed interface PlaylistEntryOutcome {
    val entry: PlaylistEntry

    data class Matched(override val entry: PlaylistEntry, val id: String) : PlaylistEntryOutcome

    data class Skipped(override val entry: PlaylistEntry, val reason: PlaylistSkipReason) : PlaylistEntryOutcome

    /** [ambiguous] is true when more than one item fit and nothing broke the tie. */
    data class NotFound(override val entry: PlaylistEntry, val ambiguous: Boolean) : PlaylistEntryOutcome
}

/** One outcome per entry in file order, and the matched ids in that order without repeats. */
data class PlaylistMatchReport(
    val outcomes: List<PlaylistEntryOutcome>,
    val matchedIds: List<String>,
)

/**
 * Maps playlist entries onto library items: skip rules, then path suffix, then unique file name,
 * then (music only) artist + title. A wrong match is worse than a listed miss, so a step that finds
 * several equally good items matches nothing and the entry is reported as ambiguous. Pure Kotlin.
 */
object PlaylistMatcher {
    private const val DURATION_WINDOW_MS = 2_000L
    private val nestedPlaylistExtensions = setOf("m3u", "m3u8", "pls", "xspf")
    private val whitespace = Regex("\\s+")

    /** [ownExtensions] / [otherExtensions] are lowercase, without the dot. */
    fun match(
        kind: PlaylistKind,
        entries: List<PlaylistEntry>,
        candidates: List<PlaylistCandidate>,
        ownExtensions: Set<String>,
        otherExtensions: Set<String>,
    ): PlaylistMatchReport {
        val keyed = candidates.map { Keyed(it, keySegments(it)) }
        val byName = keyed.groupBy { it.fileName }
        val byTags = if (kind == PlaylistKind.MUSIC) {
            keyed.filter { !it.candidate.artist.isNullOrBlank() && !it.candidate.title.isNullOrBlank() }
                .groupBy { tagKey(it.candidate.artist!!, it.candidate.title!!) }
        } else {
            emptyMap()
        }

        val outcomes = ArrayList<PlaylistEntryOutcome>(entries.size)
        val matchedIds = LinkedHashSet<String>()
        for (entry in entries) {
            val outcome = outcomeFor(entry, kind, byName, byTags, ownExtensions, otherExtensions)
            if (outcome is PlaylistEntryOutcome.Matched && !matchedIds.add(outcome.id)) {
                outcomes += PlaylistEntryOutcome.Skipped(entry, PlaylistSkipReason.DUPLICATE)
            } else {
                outcomes += outcome
            }
        }
        return PlaylistMatchReport(outcomes, matchedIds.toList())
    }

    private class Keyed(val candidate: PlaylistCandidate, val segments: List<String>) {
        val fileName: String get() = segments.last()

        /** Step 1 needs a folder to compare, so a root-level file has no path key. */
        val hasPathKey: Boolean get() = segments.size >= 2
    }

    private fun outcomeFor(
        entry: PlaylistEntry,
        kind: PlaylistKind,
        byName: Map<String, List<Keyed>>,
        byTags: Map<String, List<Keyed>>,
        ownExtensions: Set<String>,
        otherExtensions: Set<String>,
    ): PlaylistEntryOutcome {
        val location = PlaylistLocationNormalizer.normalize(entry.location)
        val variants = when (location) {
            PlaylistLocation.Empty -> return PlaylistEntryOutcome.NotFound(entry, ambiguous = false)
            PlaylistLocation.WebLink -> return PlaylistEntryOutcome.Skipped(entry, PlaylistSkipReason.WEB_LINK)
            is PlaylistLocation.Path -> listOfNotNull(location.segments, location.decodedSegments)
                .filter { it.isNotEmpty() }
        }

        val extension = variants.firstOrNull()?.last()?.substringAfterLast('.', "").orEmpty()
        val wrongType = extension in nestedPlaylistExtensions ||
            (extension in otherExtensions && extension !in ownExtensions)
        if (wrongType) return PlaylistEntryOutcome.Skipped(entry, PlaylistSkipReason.WRONG_TYPE)

        var ambiguous = false

        // Step 1: the longest shared run of trailing segments, at least two.
        val pathHits = HashMap<Keyed, Int>()
        for (variant in variants) {
            for (candidate in byName[variant.last()].orEmpty()) {
                if (!candidate.hasPathKey) continue
                val shared = sharedSuffix(variant, candidate.segments)
                if (shared >= 2 && shared == minOf(variant.size, candidate.segments.size)) {
                    pathHits.merge(candidate, shared, ::maxOf)
                }
            }
        }
        if (pathHits.isNotEmpty()) {
            val best = pathHits.values.max()
            val top = pathHits.filterValues { it == best }.keys
            if (top.size == 1) return PlaylistEntryOutcome.Matched(entry, top.single().candidate.id)
            ambiguous = true
        }

        // Step 2: exactly one item with this file name.
        val named = variants.flatMap { byName[it.last()].orEmpty() }.distinct()
        if (named.size == 1) return PlaylistEntryOutcome.Matched(entry, named.single().candidate.id)
        if (named.size > 1) ambiguous = true

        // Step 3: artist + title tags, music only.
        val artist = entry.artist
        val title = entry.title
        if (kind == PlaylistKind.MUSIC && !artist.isNullOrBlank() && !title.isNullOrBlank()) {
            val tagged = byTags[tagKey(artist, title)].orEmpty()
            if (tagged.size == 1) return PlaylistEntryOutcome.Matched(entry, tagged.single().candidate.id)
            if (tagged.size > 1) {
                val wanted = entry.durationMs
                val close = if (wanted == null) {
                    emptyList()
                } else {
                    tagged.filter { t -> t.candidate.durationMs?.let { abs(it - wanted) <= DURATION_WINDOW_MS } == true }
                }
                if (close.size == 1) return PlaylistEntryOutcome.Matched(entry, close.single().candidate.id)
                ambiguous = true
            }
        }

        return PlaylistEntryOutcome.NotFound(entry, ambiguous)
    }

    private fun sharedSuffix(a: List<String>, b: List<String>): Int {
        var n = 0
        while (n < a.size && n < b.size && a[a.size - 1 - n] == b[b.size - 1 - n]) n++
        return n
    }

    private fun keySegments(candidate: PlaylistCandidate): List<String> {
        val folders = candidate.relativePath.orEmpty().replace('\\', '/').split('/').filter { it.isNotBlank() }
        return (folders + candidate.displayName).map(::fold)
    }

    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT)

    private fun tagKey(artist: String, title: String): String =
        fold(artist.trim().replace(whitespace, " ")) + "\u0000" + fold(title.trim().replace(whitespace, " "))
}
