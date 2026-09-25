package com.playfieldportal.feature.xmb.ui.detail

import com.playfieldportal.feature.artwork.match.MatchConfidence
import com.playfieldportal.feature.artwork.match.MatchSignal
import com.playfieldportal.feature.artwork.match.ScoredStorefrontCandidate
import com.playfieldportal.feature.artwork.match.Storefront
import com.playfieldportal.feature.artwork.match.StorefrontCandidate
import com.playfieldportal.feature.artwork.match.StorefrontMatchRepository

// ── Storefront picker state (C23 T6, Phases 10 and 18) ───────────────────────
// Screen-shaped, not provider-shaped: every enum and every score has already become a string by
// the time it reaches a Composable, so the panels never import a matcher type to decide wording.

/** One candidate as the picker shows it. Built once, in [storefrontRowOf]. */
data class StorefrontCandidateRow(
    val store: Storefront,
    val storeLabel: String,
    val storeId: String,
    /** `appid 379720` — the store's own word for its id, so the number is never bare. */
    val idLabel: String,
    val title: String,
    /** `2016  ·  id Software  ·  Bethesda Softworks`, or null when the store said none of it. */
    val subtitle: String?,
    val description: String? = null,
    val thumbUrl: String? = null,
    val score: Int,
    /** Chip text for the signals worth showing on the row itself. */
    val strongSignals: List<String>,
    /** The full ledger, including what was NOT compared — see [SignalLine]. */
    val signalLines: List<SignalLine>,
    /** Kept so confirming a choice hands the resolver the candidate it gave us. */
    val candidate: StorefrontCandidate,
    val exactTitle: Boolean,
)

/**
 * The Match Game overlay.
 *
 * One store at a time on purpose: two stores' candidates in one list would invite comparing a
 * Steam appid against a GOG product id, which is exactly the cross-store confusion the identity
 * model exists to prevent. With only Steam built today this is moot, and it stays right when it
 * is not.
 */
data class StorefrontMatchUi(
    val loading: Boolean = true,
    val gameTitle: String = "",
    /** What the 5-rule normalizer actually sent, shown so a bad match has a visible cause. */
    val query: String = "",
    val storeLabel: String? = null,
    val confidence: MatchConfidence? = null,
    val rows: List<StorefrontCandidateRow> = emptyList(),
    /** Stores that could not be reached. Never folded into "no match" — see the panel's wording. */
    val unavailableStores: List<String> = emptyList(),
    val notApplicable: Boolean = false,
    /**
     * Set when every store answered and was already settled — there was nothing to ask about.
     * Shown rather than silently closing: a user who opened this screen is owed an answer.
     */
    val settledLabel: String? = null,
    /** `0..rows.lastIndex` is a candidate; [noMatchIndex] is "No correct match". */
    val focus: Int = 0,
    val moreInfoOpen: Boolean = false,
    /** True once a choice is being written, so a second Select cannot double-write. */
    val confirming: Boolean = false,
) {
    val noMatchIndex: Int get() = rows.size

    val focusedCandidate: StorefrontCandidateRow? get() = rows.getOrNull(focus)

    /** The DOOM case: more than one candidate whose normalized title is identical to the query. */
    val tiedOnExactTitle: Boolean get() = rows.count { it.exactTitle } > 1

    /** Every focus stop, candidates plus the always-present escape hatch. */
    val stopCount: Int get() = rows.size + 1
}

/** What a Rematch row can do. Order is the Left/Right order on a controller. */
enum class RematchAction(val label: String) {
    SEARCH("Search"),
    REPLACE("Replace"),
    REMOVE("Remove"),
}

data class StorefrontRematchRow(
    val store: Storefront,
    val storeLabel: String,
    /** `DOOM (2016)  ·  appid 379720`, or `Not linked`. */
    val detail: String,
    /** The quiet second line: when it was last checked, or why a store cannot be searched. */
    val note: String?,
    val userConfirmed: Boolean,
    /** False for a store with no provider — its actions render dimmed and do nothing. */
    val enabled: Boolean,
    val actions: List<RematchAction>,
    val selectedAction: RematchAction,
)

data class StorefrontRematchUi(
    val loading: Boolean = true,
    val searching: Boolean = false,
    val gameTitle: String = "",
    val rows: List<StorefrontRematchRow> = emptyList(),
    /** `0..rows.lastIndex` is a store; [searchAllIndex] is the Search-every-store button. */
    val focus: Int = 0,
) {
    val searchAllIndex: Int get() = rows.size

    val stopCount: Int get() = rows.size + 1

    val focusedRow: StorefrontRematchRow? get() = rows.getOrNull(focus)
}

// ── Mapping ───────────────────────────────────────────────────────────────────

/**
 * A scored candidate as a row.
 *
 * The signal ledger deliberately includes lines that scored NOTHING. "Release year — not compared,
 * your copy has no year" is the line that explains why an otherwise obvious match is still being
 * questioned; omitting it would make the total look arbitrary.
 */
fun storefrontRowOf(scored: ScoredStorefrontCandidate): StorefrontCandidateRow {
    val candidate = scored.candidate
    val subtitle = listOfNotNull(
        candidate.releaseYear?.toString(),
        candidate.developer?.takeIf { it.isNotBlank() },
        candidate.publisher?.takeIf { it.isNotBlank() && it != candidate.developer },
    ).joinToString("  ·  ").takeIf { it.isNotBlank() }

    val counted = scored.signals.map { signal ->
        SignalLine(
            label = signal.describe(),
            points = if (signal.points >= 0) "+${signal.points}" else "${signal.points}",
            counted = true,
        )
    }
    // The wording is drawn from what the CANDIDATE carries, because that is the only side this
    // mapper can see. Saying "your copy has no year" when the store is the one that stayed silent
    // would point the user at the wrong thing to fix.
    val absent = buildList {
        if (MatchSignal.RELEASE_YEAR !in scored.signals &&
            MatchSignal.RELEASE_YEAR_CONFLICT !in scored.signals
        ) {
            add(absentLine("Release year", candidate.releaseYear != null))
        }
        if (MatchSignal.DEVELOPER !in scored.signals) {
            add(absentLine("Developer", !candidate.developer.isNullOrBlank()))
        }
        if (MatchSignal.PUBLISHER !in scored.signals) {
            add(absentLine("Publisher", !candidate.publisher.isNullOrBlank()))
        }
    }

    return StorefrontCandidateRow(
        store = candidate.store,
        storeLabel = candidate.store.label,
        storeId = candidate.storeId,
        idLabel = candidate.store.idLabel(candidate.storeId),
        title = candidate.title,
        subtitle = subtitle,
        thumbUrl = candidate.thumbUrl,
        score = scored.score,
        // Only the strong ones as chips: a PARTIAL_TITLE chip would dress a weak match up as
        // evidence, and the full ledger is one button away.
        strongSignals = scored.signals.filter { it.strong }.map { it.chipLabel() },
        signalLines = counted + absent,
        candidate = candidate,
        exactTitle = MatchSignal.EXACT_TITLE in scored.signals,
    )
}

/**
 * A signal that scored nothing, worded by which side was silent.
 *
 * [storeHasIt] false means the store said nothing, so there was never anything to compare; true
 * means the store did say something and it did not agree with the local row — which is a weaker
 * statement than a conflict, and is deliberately not scored as one.
 */
private fun absentLine(field: String, storeHasIt: Boolean): SignalLine = SignalLine(
    label = if (storeHasIt) "$field — no match against your copy" else "$field — the store didn't say",
    points = "—",
    counted = false,
)

/** The store's own word for its identifier, so a bare number never appears on screen. */
fun Storefront.idLabel(id: String): String = when (this) {
    Storefront.STEAM -> "appid $id"
    Storefront.GOG -> "product $id"
    Storefront.EPIC -> "catalog $id"
}

private fun MatchSignal.chipLabel(): String = when (this) {
    MatchSignal.AUTHORITATIVE_ID -> "Store id"
    MatchSignal.EXACT_TITLE -> "Exact title"
    MatchSignal.DEVELOPER -> "Developer matches"
    MatchSignal.PUBLISHER -> "Publisher matches"
    MatchSignal.RELEASE_YEAR -> "Year matches"
    MatchSignal.EDITION_STRIPPED_TITLE -> "Same game, other edition"
    MatchSignal.PARTIAL_TITLE -> "Partial title"
    MatchSignal.RELEASE_YEAR_CONFLICT -> "Year disagrees"
}

/** A repository row as a Rematch line. */
fun storefrontRematchRowOf(row: StorefrontMatchRepository.RematchRow): StorefrontRematchRow {
    val identity = row.identity?.record
    val actions = when {
        !row.searchable -> listOf(RematchAction.SEARCH)
        identity == null -> listOf(RematchAction.SEARCH)
        else -> listOf(RematchAction.REPLACE, RematchAction.REMOVE)
    }
    return StorefrontRematchRow(
        store = row.store,
        storeLabel = row.store.label,
        detail = identity?.let { record ->
            listOfNotNull(record.resolvedTitle, row.store.idLabel(record.storeId)).joinToString("  ·  ")
        } ?: "Not linked",
        note = when {
            !row.searchable -> "No provider yet — coming after Steam is proven"
            identity == null -> "Nothing stored for this store"
            identity.userConfirmed -> null
            else -> "Matched automatically"
        },
        userConfirmed = identity?.userConfirmed == true,
        enabled = row.searchable,
        actions = actions,
        selectedAction = actions.first(),
    )
}
