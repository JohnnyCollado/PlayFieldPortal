package com.playfieldportal.feature.artwork.match

/**
 * C16 Phase 2's identity model — pure data, no I/O, no persistence.
 *
 * Everything here answers "which game is this, according to whom?" and nothing here writes a
 * column. Retrieval and application are separate operations (AD-7): the tiered matcher (2.2) and
 * the metadata preview (3.2) both consume these types, and only an explicit user action turns one
 * into a database write.
 */

/** A provider a game can be identified against. Kept separate from the Studio's source list. */
enum class MatchProvider(val label: String) {
    SCREENSCRAPER("ScreenScraper"),
    THEGAMESDB("TheGamesDB"),
    IGDB("IGDB"),
    STEAMGRIDDB("SteamGridDB"),
}

/**
 * What a provider can actually be addressed by, as the tree stands today.
 *
 * [supportsTitleSearch] is true for every provider today — each has a multi-result title endpoint
 * (ScreenScraper's jeuRecherche included). Saved ids and ROM checksums still resolve first, so a
 * title search only runs when nothing stronger stands up. A provider gains a
 * capability by flipping a flag here once its API grows the endpoint — the matcher reads the
 * table, it never hardcodes a provider name.
 */
data class ProviderCapability(
    val provider: MatchProvider,
    /** Has a persisted per-game id on `games` (`ss_id`, `tgdb_id`, `igdb_id`, `steam_grid_db_id`). */
    val addressableBySavedId: Boolean,
    /** Can be asked for a game by ROM checksum (`games.rom_crc32`). */
    val addressableByRomHash: Boolean,
    /** Can be asked for a game by a storefront pair (`games.storefront` + `storefront_game_id`). */
    val addressableByStorefrontId: Boolean,
    /** Returns MORE THAN ONE game for a title query — what a Change Match picker needs. */
    val supportsTitleSearch: Boolean,
    /** Contributes text metadata (title, description, developer, ...). */
    val suppliesMetadata: Boolean,
    /** Contributes artwork. */
    val suppliesArtwork: Boolean,
)

/** The capability table. Verified against the provider APIs in this module, not assumed. */
object ProviderCapabilities {

    private val table: Map<MatchProvider, ProviderCapability> = listOf(
        // ScreenScraper is addressed by ss_id or ROM checksum first (jeuInfos), and by name through
        // jeuRecherche — up to 30 ranked games — which is what matches a game with no ROM file.
        ProviderCapability(
            provider = MatchProvider.SCREENSCRAPER,
            addressableBySavedId = true,
            addressableByRomHash = true,
            addressableByStorefrontId = false,
            supportsTitleSearch = true,
            suppliesMetadata = true,
            suppliesArtwork = true,
        ),
        // Games/ByGameName returns every hit (TheGamesDbApi.searchGames), platform-filtered when the
        // platform is mapped, so TheGamesDB backs Tier 3 and Change Match.
        ProviderCapability(
            provider = MatchProvider.THEGAMESDB,
            addressableBySavedId = true,
            addressableByRomHash = false,
            addressableByStorefrontId = false,
            supportsTitleSearch = true,
            suppliesMetadata = true,
            suppliesArtwork = true,
        ),
        // IgdbApi.searchGames returns up to ten games per title, so IGDB backs Tier 3 and Change
        // Match. IgdbGameInfo carries cover/hero URLs only — no text fields are requested — so it has
        // no metadata preset to offer (C16 task 3.2).
        ProviderCapability(
            provider = MatchProvider.IGDB,
            addressableBySavedId = true,
            addressableByRomHash = false,
            addressableByStorefrontId = false,
            supportsTitleSearch = true,
            suppliesMetadata = false,
            suppliesArtwork = true,
        ),
        // SteamGridDbApi.searchGame returns a List<SgdbGame>, and getSteamAppId resolves the Steam
        // side of a storefront pair. Artwork only — SGDB supplies no text metadata (Non-Goals).
        ProviderCapability(
            provider = MatchProvider.STEAMGRIDDB,
            addressableBySavedId = true,
            addressableByRomHash = false,
            addressableByStorefrontId = true,
            supportsTitleSearch = true,
            suppliesMetadata = false,
            suppliesArtwork = true,
        ),
    ).associateBy { it.provider }

    operator fun get(provider: MatchProvider): ProviderCapability = table.getValue(provider)

    val all: List<ProviderCapability> get() = MatchProvider.entries.map { table.getValue(it) }

    /** Providers that can back a Change Match picker — all four today. */
    val searchable: List<MatchProvider> get() = all.filter { it.supportsTitleSearch }.map { it.provider }

    /** Providers a metadata preset can be built from. */
    val metadataProviders: List<MatchProvider> get() = all.filter { it.suppliesMetadata }.map { it.provider }
}

/**
 * One game a provider believes this ROM/installation could be. Retrieved, never written.
 *
 * [providerGameId] is a string because providers disagree on the type (SGDB and TGDB use numbers,
 * ScreenScraper a numeric string); it is only ever compared within one provider, never across
 * them — a tgdb_id is meaningless to IGDB.
 */
data class GameCandidate(
    val provider: MatchProvider,
    val providerGameId: String,
    val title: String,
    val platformName: String? = null,
    val releaseYear: Int? = null,
    val thumbUrl: String? = null,
)

/**
 * How much evidence stands behind a match, strongest first.
 *
 * Tiers 4-6 (ranked suggestions, fuzzy title, edition disambiguation) are deliberately absent:
 * they need multi-result search that only SteamGridDB has (AD-4). They join this enum as extra
 * entries below [EXACT_TITLE] when the follow-up plan lands — the matcher branches on the tier it
 * produced, so adding one is additive.
 */
enum class MatchTier {
    /** A provider id already saved on the game — the user or a past scrape already decided. */
    SAVED_PROVIDER_ID,

    /** A content identifier: ROM CRC32, or the storefront + app-id PAIR for a Windows game. */
    CONTENT_ID,

    /** Exactly one exact normalized-title hit on the game's expected platform. */
    EXACT_TITLE,
}

/**
 * The resolved identity for one provider.
 *
 * [userConfirmed] is what the mockup's "Confirmed" chip reads: a match the user chose through
 * Change Match outranks anything the matcher derives, and Forget Match clears it without touching
 * a single local artwork file or metadata column.
 */
data class GameMatch(
    val candidate: GameCandidate,
    val tier: MatchTier,
    val userConfirmed: Boolean = false,
) {
    /**
     * The value that goes into StudioRequestKey.matchId — provider-qualified, so two providers'
     * ids can never be crossed and confirming a match invalidates exactly its own cache entries.
     */
    val matchKey: String get() = "${candidate.provider.name}:${candidate.providerGameId}"
}

/**
 * A provider's proposed metadata for a game, retrieved without writing anything.
 *
 * Every field is nullable and null means "this provider said nothing", which is distinct from
 * "this provider said empty" — a blank incoming value must never clear a populated column
 * (Data/Persistence: Compatibility). `players` is absent on purpose: it stays unsurfaced
 * (Non-Goals).
 */
data class MetadataPreset(
    val provider: MatchProvider,
    val title: String? = null,
    val description: String? = null,
    val developer: String? = null,
    val publisher: String? = null,
    val releaseYear: Int? = null,
    val releaseDate: String? = null,
    val genre: String? = null,
    val ageRating: String? = null,
    val franchise: String? = null,
    val communityRating: Float? = null,
) {
    /** True when the provider returned nothing usable — such a preset is never offered. */
    val isEmpty: Boolean
        get() = listOf(
            title, description, developer, publisher, releaseYear,
            releaseDate, genre, ageRating, franchise, communityRating,
        ).all { it == null || (it is String && it.isBlank()) }
}
