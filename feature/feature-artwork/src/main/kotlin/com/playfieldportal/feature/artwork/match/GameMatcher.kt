package com.playfieldportal.feature.artwork.match

import com.playfieldportal.core.domain.model.Game
import java.util.Locale

/**
 * The comparison form of a game title.
 *
 * Used for CACHE KEYING and MATCHING only — it never touches what the user typed and the game is
 * never renamed by searching. "Final Fantasy VII", "  final   fantasy vii  " and
 * "Final Fantasy VII (USA)" are one identity here and three strings everywhere else.
 *
 * The Artwork Studio's `StudioQuery.normalize` delegates to this, so the query that addresses a
 * result cache and the title that resolves a match can never drift apart.
 */
object TitleKey {

    private val WHITESPACE = Regex("\\s+")

    /** Bracketed release tags — "(USA)", "[!]", "(Disc 1)", "(Rev A)" — as ROM filenames carry them. */
    private val TAGS = Regex("[\\(\\[][^\\)\\]]*[\\)\\]]")

    /** Punctuation that never distinguishes two titles. Digits and letters are kept as-is. */
    private val NOISE = Regex("[\\p{Punct}&&[^&]]")

    /**
     * The key form of [raw]. A title that normalizes to nothing (only tags or punctuation) keeps
     * its trimmed raw form, so a deliberate "[BIOS]" still has an identity of its own rather than
     * collapsing into the empty one.
     */
    fun of(raw: String): String {
        val trimmed = raw.trim()
        val stripped = trimmed
            .replace(TAGS, " ")
            .replace(NOISE, " ")
            .replace(WHITESPACE, " ")
            .trim()
            .lowercase(Locale.US)
        return stripped.ifBlank { trimmed.lowercase(Locale.US) }
    }

    /** True when [a] and [b] name the same game as far as matching is concerned. */
    fun same(a: String, b: String): Boolean = of(a) == of(b)
}

/**
 * The provider lookups the matcher is allowed to perform.
 *
 * Kept as an interface so [GameMatcher] stays a pure function of its evidence: Tiers 1-3 are
 * decided by ordering and by the capability table, and every network call lives behind an
 * implementation of this. Nothing here persists anything — retrieval and application are separate
 * operations (AD-7).
 */
interface MatchEvidenceSource {

    /** The provider's game for a ROM checksum, or null. Only called when the provider accepts one. */
    suspend fun candidateByRomHash(
        provider: MatchProvider,
        crc32: String,
        platformId: String,
    ): GameCandidate?

    /**
     * The provider's game for a storefront PAIR, or null. The pair is passed whole because an app
     * id is unique only within its own store.
     */
    suspend fun candidateByStorefront(
        provider: MatchProvider,
        storefront: String,
        storefrontGameId: String,
    ): GameCandidate?

    /**
     * Every game the provider returns for [query], scoped to [platformId] where the provider can.
     * Only called for providers whose capability says they return more than one (SteamGridDB today).
     */
    suspend fun searchByTitle(
        provider: MatchProvider,
        query: String,
        platformId: String,
    ): List<GameCandidate>

    /**
     * A display title for an already-saved provider id, when the provider can give one cheaply.
     * Null is normal and simply means the match row falls back to the game's own title.
     */
    suspend fun titleForSavedId(provider: MatchProvider, providerGameId: String): String? = null
}

/**
 * C16 task 2.2 — resolves "which game is this, according to one provider" at Tiers 1-3.
 *
 * The order is the whole design: the strongest evidence wins and stops the search, so a game with
 * a saved id never spends a network call on a title lookup, and an ambiguous title is a miss
 * rather than a guess. Tiers 4-6 (ranked suggestions) are deferred because only one provider can
 * return more than one game (AD-4); they arrive as extra branches below [tierThree], not a rewrite.
 */
class GameMatcher(private val evidence: MatchEvidenceSource) {

    /**
     * The best match for [game] on [provider], or null when nothing at Tiers 1-3 stands up.
     *
     * [query] lets the Studio resolve against the user's edited search text instead of the game
     * row; it defaults to the game's own display title.
     */
    suspend fun resolve(
        game: Game,
        provider: MatchProvider,
        query: String? = null,
    ): GameMatch? {
        val capability = ProviderCapabilities[provider]

        // ── Tier 1: an id already saved on this game, for THIS provider ────
        if (capability.addressableBySavedId) {
            savedIdFor(game, provider)?.let { id ->
                return GameMatch(
                    candidate = GameCandidate(
                        provider = provider,
                        providerGameId = id,
                        title = evidence.titleForSavedId(provider, id) ?: game.displayTitle,
                    ),
                    tier = MatchTier.SAVED_PROVIDER_ID,
                )
            }
        }

        // ── Tier 2: a content identifier ───────────────────────────────────
        val crc = game.romCrc32?.trim()?.takeIf { it.isNotEmpty() }
        if (capability.addressableByRomHash && crc != null) {
            evidence.candidateByRomHash(provider, crc, game.platformId)?.let {
                return GameMatch(it.copy(provider = provider), MatchTier.CONTENT_ID)
            }
        }

        val store = game.storefront?.trim()?.takeIf { it.isNotEmpty() }
        val storeId = game.storefrontGameId?.trim()?.takeIf { it.isNotEmpty() }
        if (capability.addressableByStorefrontId && store != null && storeId != null) {
            evidence.candidateByStorefront(provider, store, storeId)?.let {
                return GameMatch(it.copy(provider = provider), MatchTier.CONTENT_ID)
            }
        }

        // ── Tier 3: exactly one exact normalized-title hit ─────────────────
        return tierThree(game, provider, capability, query)
    }

    private suspend fun tierThree(
        game: Game,
        provider: MatchProvider,
        capability: ProviderCapability,
        query: String?,
    ): GameMatch? {
        // A provider that returns a single game has no uniqueness to establish — its one answer is
        // not evidence that no other game shares the title.
        if (!capability.supportsTitleSearch) return null

        val raw = query?.takeIf { it.isNotBlank() } ?: game.displayTitle
        val wanted = TitleKey.of(raw)
        if (wanted.isBlank()) return null

        val exact = evidence.searchByTitle(provider, raw.trim(), game.platformId)
            .filter { TitleKey.of(it.title) == wanted }

        // Ambiguity is a miss: without a ranked picker, picking one of several would be a guess
        // the user cannot see or correct.
        val only = exact.singleOrNull() ?: return null
        return GameMatch(only.copy(provider = provider), MatchTier.EXACT_TITLE)
    }

    /**
     * The id this game already carries for [provider], as a string. Never crossed between
     * providers — a `tgdb_id` is meaningless to IGDB, and reading one as the other is exactly the
     * bug this indirection exists to prevent.
     */
    private fun savedIdFor(game: Game, provider: MatchProvider): String? = when (provider) {
        MatchProvider.SCREENSCRAPER -> game.ssId
        MatchProvider.THEGAMESDB -> game.tgdbId
        MatchProvider.IGDB -> game.igdbId
        MatchProvider.STEAMGRIDDB -> game.steamGridDbId
    }?.takeIf { it > 0 }?.toString()
}
