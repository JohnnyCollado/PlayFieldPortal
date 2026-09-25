package com.playfieldportal.feature.artwork.match

import com.playfieldportal.feature.artwork.portable.ArtworkNaming
import java.util.Locale

/**
 * The Title 5-Rule Normalizer (C23 T6, Phases 2-3) — the DISCOVERY mechanism for a Windows game's
 * storefront identity, and nothing more.
 *
 * It exists because a storefront has to be asked for a game by name exactly once: to find out what
 * the game's id is. From then on the id is the relationship and this object is never consulted
 * again ([StorefrontMetadataResolver] enforces that). Nothing here rewrites a game's display title
 * — [NormalizedTitle.displayTitle] is carried through untouched beside every derived form.
 *
 * **What is reused rather than rewritten.** Rules 2 and 3 already exist, frozen, in
 * [ArtworkNaming]: `normalizeForMatch` does NFKC + lowercase + apostrophe unification + whitespace,
 * and `simplifyTitle` adds release-tag removal and punctuation unification. [TitleCanon] then adds
 * the article and roman-numeral rules. This object adds only what those do not have — trademark
 * stripping (rule 1), edition handling (rule 4) and launcher/executable noise (rule 5) — and
 * composes the rest. No normalization rule is duplicated here.
 *
 * **Why trademark stripping has to happen first.** NFKC does not delete a trademark sign; it
 * DECOMPOSES it, to the letters `tm`. Handing `FINAL FANTASY VII(tm) REMAKE` straight to
 * `normalizeForMatch` yields `final fantasy viitm remake`, which matches nothing on any store.
 * Rule 1 therefore runs before anything in [ArtworkNaming] sees the string.
 *
 * **Why two derived strings and not one.** A search term and a comparison key want opposite
 * things. Steam's `storesearch` does better with `sid meier's civilization vi` than with
 * `sid meier s civilization vi`, so [NormalizedTitle.searchTitle] keeps apostrophes and colons —
 * rule 3's own warning against destroying meaningful title information. Deciding whether two
 * titles are the SAME title wants the opposite, so [NormalizedTitle.comparisonKey] runs the full
 * frozen chain. One string could not be both without being worse at both.
 *
 * **What is deliberately NOT collapsed.** A parenthesised year is a disambiguator, not a release
 * tag: `Resident Evil 4` and `Resident Evil 4 (2005)` are two different products, and
 * `simplifyTitle` erases the only thing that says so. The year is lifted out into
 * [NormalizedTitle.yearHint] BEFORE the tag groups are dropped, so the two titles still differ
 * after normalization and the scorer can tell a 2005 candidate from a 2023 one. Sequel numerals
 * (`DOOM` vs `DOOM II`), remakes and rebirths are simply never touched by any rule here.
 */
object StorefrontTitleNormalizer {

    /**
     * [displayTitle] as the storefront resolver sees it. Every field is derived; the display title
     * is never replaced.
     */
    data class NormalizedTitle(
        /** Exactly what the library shows. Carried so a picker can label a row honestly. */
        val displayTitle: String,
        /** Rules 1, 2 and 5: what a storefront is actually asked for. */
        val searchTitle: String,
        /** Rule 4: [searchTitle] with a trailing edition descriptor removed, or null if none was. */
        val editionlessTitle: String?,
        /** The edition descriptor rule 4 removed, lowercased — a scoring signal, not a title. */
        val edition: String?,
        /** A parenthesised release year, kept rather than dropped. See the class KDoc. */
        val yearHint: Int?,
        /** The strict equality key: [searchTitle] through `simplifyTitle` and [TitleCanon]. */
        val comparisonKey: String,
        /** The same key over [editionlessTitle], or null when rule 4 removed nothing. */
        val editionlessComparisonKey: String?,
    ) {
        /**
         * What to send to a provider, strongest first, deduplicated and never blank (Phase 3).
         *
         * The edition-stripped form is second and never first: it is a broader net, and a hit on
         * it alone is explicitly not enough to establish identity (Phase 4).
         */
        val searchCandidates: List<String>
            get() = listOfNotNull(searchTitle, editionlessTitle)
                .filter { it.isNotBlank() }
                .distinct()
    }

    /** The five rules, applied in order. Blank in, blank out. */
    fun normalize(displayTitle: String): NormalizedTitle {
        val raw = displayTitle.trim()
        if (raw.isEmpty()) {
            return NormalizedTitle(displayTitle, "", null, null, null, "", null)
        }

        val yearHint = YEAR_TAG.find(raw)?.groupValues?.get(1)?.toIntOrNull()

        // Rule 1 — symbols. Before ArtworkNaming, because NFKC turns the trademark sign into "tm".
        val symbolFree = raw.replace(TRADEMARKS, "")

        // Rules 2 and 3 (the safe half) — NFKC, lowercase, apostrophes, whitespace, release tags.
        // simplifyTitle is NOT used here: it also flattens ':' and the apostrophe, which a store
        // search reads better with. The full form is reached below, for the comparison key only.
        val tagFree = ArtworkNaming.normalizeForMatch(symbolFree)
            .replace(TAG_GROUPS, " ")
            .replace(WHITESPACE, " ")
            .trim()

        // Rule 5 — launcher / executable / installer noise.
        val searchTitle = stripNoise(tagFree)

        // Rule 4 — edition descriptors, as an ALTERNATE form. The original survives untouched.
        val edition = EDITIONS.firstOrNull { searchTitle.endsWith(" $it") }
        val editionlessTitle = edition
            // The separator the edition hung off goes with it: `The Witcher 3: Wild Hunt -
            // Complete Edition` must not leave a trailing dash in what a store is asked for.
            ?.let { searchTitle.removeSuffix(" $it").trim().trim(*TRAILING_SEPARATORS) }
            ?.takeIf { it.isNotBlank() && it != searchTitle }

        return NormalizedTitle(
            displayTitle = displayTitle,
            searchTitle = searchTitle,
            editionlessTitle = editionlessTitle,
            edition = edition,
            yearHint = yearHint,
            comparisonKey = keyOf(searchTitle),
            editionlessComparisonKey = editionlessTitle?.let { keyOf(it) },
        )
    }

    /**
     * The strict equality key for an arbitrary title — what a provider's answer is measured with,
     * so both sides of a comparison go through the identical chain.
     */
    fun keyOf(title: String): String =
        TitleCanon.of(ArtworkNaming.simplifyTitle(title.replace(TRADEMARKS, "")))

    /**
     * Rule 5. Drops a trailing executable extension, then removes known noise TOKENS — never a
     * word merely because it looks technical, and never the last token standing: `Launcher` on its
     * own is a title PFP has no better guess for, and emptying it would search for nothing.
     */
    private fun stripNoise(title: String): String {
        val withoutExtension = EXECUTABLE_EXTENSION.replace(title, "")
        val tokens = withoutExtension.split(WHITESPACE).filter { it.isNotBlank() }
        val kept = tokens.filterNot { it in NOISE_TOKENS }
        return (if (kept.isEmpty()) tokens else kept).joinToString(" ").trim()
    }

    // -- Rule tables -----------------------------------------------------------

    /** Rule 1. Trademark/copyright marks carry no title information on any store. */
    private val TRADEMARKS = Regex("[™®©℗℠]")

    private val WHITESPACE = Regex("""\s+""")

    /** Release/dump tag groups, the same shape `ArtworkNaming` recognises. */
    private val TAG_GROUPS = Regex("""\(([^)]*)\)|\[([^\]]*)]""")

    /** A parenthesised or bracketed four-digit year — lifted out before the tags are dropped. */
    private val YEAR_TAG = Regex("""[(\[]\s*((?:19|20)\d{2})\s*[)\]]""")

    /** Punctuation left dangling once a trailing edition phrase is removed. */
    private val TRAILING_SEPARATORS = charArrayOf(' ', '-', ':', '_', ',', '–', '—')

    private val EXECUTABLE_EXTENSION =
        Regex("""\.(exe|lnk|url|desktop|bat|cmd)$""", RegexOption.IGNORE_CASE)

    /**
     * Rule 5's controlled token list — architecture and packaging words an executable name, a
     * shortcut or a folder path leaks into a title. Deliberately short.
     *
     * `win` is absent on purpose (a title may legitimately contain it), and so is every word that
     * names a PRODUCT rather than a build: `remake`, `remastered`, `rebirth`, `redux` and
     * `director's cut` are different games from their originals and must never be filed away.
     */
    private val NOISE_TOKENS = setOf(
        "launcher", "win32", "win64", "x64", "x86", "x86_64", "shipping",
        "installer", "setup", "steamapps", "binaries", "retail",
    )

    /**
     * Rule 4's edition descriptors, longest first so `game of the year edition` is matched before
     * the shorter `game of the year` could swallow half of it.
     *
     * Only packaging: a Deluxe Edition is the same GAME as the standard one, bundled differently.
     * Anything that names a different product stays out, for the reason given at [NOISE_TOKENS].
     * Matched as a whole trailing phrase, never anywhere in the middle — `Gold` in `Pokemon Gold`
     * is the title.
     */
    private val EDITIONS: List<String> = listOf(
        "game of the year edition",
        "digital deluxe edition",
        "collector's edition",
        "collectors edition",
        "anniversary edition",
        "definitive edition",
        "legendary edition",
        "enhanced edition",
        "complete edition",
        "ultimate edition",
        "standard edition",
        "deluxe edition",
        "digital deluxe",
        "royal edition",
        "gold edition",
        "game of the year",
        "goty edition",
        "goty",
    ).map { it.lowercase(Locale.ROOT) }
        .sortedByDescending { it.length }
}
