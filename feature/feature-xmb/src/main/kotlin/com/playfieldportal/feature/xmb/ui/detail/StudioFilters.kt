package com.playfieldportal.feature.xmb.ui.detail

import com.playfieldportal.core.domain.model.GameRegion
import com.playfieldportal.feature.artwork.api.SgdbArtType
import com.playfieldportal.feature.artwork.store.ArtworkKind

/**
 * The Artwork Studio's per-source filters: pure, so what each filter keeps is testable without a
 * ViewModel.
 *
 * Two kinds of filter, told apart by where they run:
 *  - **Asked of SteamGridDB** ([SgdbRequestFilter]): Mature, Humor, Epilepsy Warning and Animation.
 *    Its image objects do not reliably say which of these they are, so only the server can filter
 *    them, and they are part of the request key.
 *  - **Applied to the list we already have**: SteamGridDB's Style and Dimensions (every image names
 *    its style and size), and ScreenScraper's Region and Media. The cached list stays whole, so
 *    changing one of these never refetches, and the page line can say how many it hid.
 *
 * TheGamesDB, IGDB, Steam and Local File have nothing worth filtering (user decision, 2026-09-29).
 */

/** Which of SteamGridDB's static and animated images to ask for. [param] is its `types` value. */
enum class SgdbAnimation(val label: String, val param: List<String>) {
    STATIC("Static", listOf("static")),
    ANIMATED("Animated", listOf("animated")),
    ALL("All", listOf("static", "animated")),
}

/** What SteamGridDB is asked to leave out. Part of its request key; other sources never carry one. */
data class SgdbRequestFilter(
    val mature: Boolean = false,
    val humor: Boolean = false,
    val epilepsy: Boolean = false,
    val animation: SgdbAnimation = SgdbAnimation.STATIC,
)

/** What a tile is, as far as the filters care. Null fields are simply not filtered on. */
data class StudioArtFacets(
    val sgdbType: SgdbArtType? = null,
    val style: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** ScreenScraper's media type (`box-2D`, `mixrbv2`, …). */
    val mediaType: String? = null,
    /** Every ScreenScraper region the file was listed under, lower case. */
    val regions: List<String> = emptyList(),
) {
    val dimensions: String? get() = if (width != null && height != null) "${width}x$height" else null
}

/**
 * The filters in force. [sgdbStyles] is remembered between opens, per art type (it is written to
 * `SgdbStylePreferences`); everything else lasts one open. Mature is not here: it is
 * `ArtworkStudioUiState.includeNsfw`, which already carries over between opens.
 *
 * An empty style set and a missing dimension mean "all".
 */
data class StudioFilters(
    val sgdbStyles: Map<SgdbArtType, Set<String>> = emptyMap(),
    val sgdbDimensions: Map<SgdbArtType, String> = emptyMap(),
    val sgdbAnimation: SgdbAnimation = SgdbAnimation.STATIC,
    val sgdbHumor: Boolean = false,
    val sgdbEpilepsy: Boolean = false,
    /** ScreenScraper region code, or null for every region. */
    val ssRegion: String? = null,
    /** One ScreenScraper media type per tab, for the tabs that list several. */
    val ssMedia: Map<ArtworkKind, String> = emptyMap(),
) {
    fun sgdbRequest(includeNsfw: Boolean) = SgdbRequestFilter(
        mature = includeNsfw, humor = sgdbHumor, epilepsy = sgdbEpilepsy, animation = sgdbAnimation,
    )

    /** The tiles of [all] these filters keep, in order. */
    fun visible(source: StudioSource, kind: ArtworkKind, all: List<StudioArt>): List<StudioArt> = when (source) {
        StudioSource.STEAMGRIDDB -> {
            val type = sgdbFilterType(kind)
            val styles = type?.let { sgdbStyles[it] }.orEmpty()
            val size = type?.let { sgdbDimensions[it] }
            if (styles.isEmpty() && size == null) all
            else all.filter { art ->
                val facets = art.facets
                (styles.isEmpty() || facets?.style in styles) && (size == null || facets?.dimensions == size)
            }
        }
        StudioSource.SCREENSCRAPER -> {
            val region = ssRegion
            val media = ssMedia[kind]
            if (region == null && media == null) all
            else all.filter { art ->
                val facets = art.facets
                val regions = facets?.regions.orEmpty()
                // Region-less media (most screenshots) and world releases belong to every region.
                val regionOk = region == null || regions.isEmpty() || region in regions || WORLD in regions
                regionOk && (media == null || facets?.mediaType == media)
            }
        }
        else -> all
    }

    /** Whether [source] is filtered narrower than it is by default — the ● on its chip. */
    fun isActive(source: StudioSource, kind: ArtworkKind, includeNsfw: Boolean): Boolean = when (source) {
        StudioSource.STEAMGRIDDB -> {
            val type = sgdbFilterType(kind)
            includeNsfw || sgdbHumor || sgdbEpilepsy || sgdbAnimation != SgdbAnimation.STATIC ||
                (type != null && (sgdbStyles[type].orEmpty().isNotEmpty() || sgdbDimensions[type] != null))
        }
        StudioSource.SCREENSCRAPER -> ssRegion != null || ssMedia[kind] != null
        else -> false
    }

    /** Clear Filters on [source]. SteamGridDB's includes every remembered style. Mature is the caller's. */
    fun cleared(source: StudioSource): StudioFilters = when (source) {
        StudioSource.STEAMGRIDDB -> StudioFilters(ssRegion = ssRegion, ssMedia = ssMedia)
        StudioSource.SCREENSCRAPER -> copy(ssRegion = null, ssMedia = emptyMap())
        else -> this
    }

    companion object {
        const val WORLD = "wor"

        // The regions a player looks for first, ahead of whatever else the results carry.
        private val KNOWN_REGIONS = listOf("us", "eu", "jp", WORLD)

        /** ScreenScraper's region for a disc's TV format, or null when the region is unknown. */
        fun ssRegionFor(region: GameRegion?): String? = when (region) {
            GameRegion.NTSC_U -> "us"
            GameRegion.PAL    -> "eu"
            GameRegion.NTSC_J -> "jp"
            null              -> null
        }

        /**
         * The filters an open starts with: the game's own region when its disc says so (otherwise
         * every region, and the player chooses), and the styles remembered for each art type.
         */
        fun forOpen(region: GameRegion?, rememberedStyles: Map<SgdbArtType, Set<String>>) =
            StudioFilters(sgdbStyles = rememberedStyles, ssRegion = ssRegionFor(region))

        /** The Region list: the game's region, then the common ones, then anything else in [all]. */
        fun regionChoices(all: List<StudioArt>, default: String?): List<String> {
            val present = all.flatMap { it.facets?.regions.orEmpty() }.toSet()
            val known = KNOWN_REGIONS.filter { it in present }
            val rest = (present - KNOWN_REGIONS.toSet()).sorted()
            return (listOfNotNull(default) + known + rest).distinct()
        }
    }
}

/** The one SteamGridDB art type a tab browses, or null for a show-all tab (never narrowed by style). */
internal fun sgdbFilterType(kind: ArtworkKind): SgdbArtType? = when (kind) {
    ArtworkKind.ICON, ArtworkKind.BOX_ART    -> SgdbArtType.GRID
    ArtworkKind.HERO, ArtworkKind.BACKGROUND -> SgdbArtType.HERO
    ArtworkKind.LOGO                         -> SgdbArtType.LOGO
    else                                     -> null
}

/** SteamGridDB's styles per art type, as its API names them. */
internal val SGDB_STYLES: Map<SgdbArtType, List<String>> = mapOf(
    SgdbArtType.GRID to listOf("alternate", "blurred", "white_logo", "material", "no_logo"),
    SgdbArtType.HERO to listOf("alternate", "blurred", "material"),
    SgdbArtType.LOGO to listOf("official", "white", "black", "custom"),
    SgdbArtType.ICON to listOf("official", "custom"),
)

/** SteamGridDB's standard sizes per art type. Logos come in any size, so they have no list. */
internal val SGDB_DIMENSIONS: Map<SgdbArtType, List<String>> = mapOf(
    SgdbArtType.GRID to listOf("600x900", "342x482", "660x930", "460x215", "920x430", "512x512", "1024x1024"),
    SgdbArtType.HERO to listOf("1920x620", "3840x1240", "1600x650"),
)

/** "white_logo" → "White Logo". */
internal fun sgdbStyleLabel(style: String): String =
    style.split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }

/** "920x430" → "920×430". */
internal fun sgdbDimensionLabel(size: String): String = size.replace('x', '×')

// ── Options menu rows ─────────────────────────────────────────────────────────

/** A filter list opened from its root row. [multiSelect] lists stay open while toggling. */
enum class StudioFilterGroup(val title: String, val multiSelect: Boolean = false) {
    STYLE("Style", multiSelect = true),
    DIMENSIONS("Dimensions"),
    ANIMATION("Animation"),
    REGION("Region"),
    MEDIA("Media"),
}

/** What a filter row does. */
sealed interface StudioFilterOption {
    data class Open(val group: StudioFilterGroup) : StudioFilterOption
    data class Style(val style: String) : StudioFilterOption
    data class Dimension(val size: String?) : StudioFilterOption
    data class Animation(val animation: SgdbAnimation) : StudioFilterOption
    data class Region(val code: String?) : StudioFilterOption
    data class Media(val type: String?) : StudioFilterOption
    data object Mature : StudioFilterOption
    data object Humor : StudioFilterOption
    data object Epilepsy : StudioFilterOption
    data object Clear : StudioFilterOption
}

/** One filter row: its label, the setting shown at the right, and whether it is the active choice. */
data class StudioFilterRow(
    val label: String,
    val option: StudioFilterOption,
    val value: String? = null,
    val checked: Boolean = false,
)

private fun onOff(on: Boolean) = if (on) "On" else "Off"

private fun regionLabel(code: String?) = code?.uppercase() ?: "All"

/**
 * The filter rows of the Options menu for [state], Tracker style: the root names each list with its
 * current setting and toggles in place; a list checks its active choice. Empty for a source that has
 * no filters. [ssTypes] is the active tab's ScreenScraper media types, [regions] the Region list.
 */
fun studioFilterRows(
    state: ArtworkStudioUiState,
    group: StudioFilterGroup?,
    ssTypes: List<String>,
    regions: List<String>,
): List<StudioFilterRow> {
    val kind = STUDIO_TABS.getOrNull(state.tabIndex)?.kind ?: return emptyList()
    val source = StudioSource.entries.getOrNull(state.sourceIndex)
    val f = state.filters
    val type = sgdbFilterType(kind)
    val defaultRegion = StudioFilters.ssRegionFor(state.game?.region)
    return when (group) {
        null -> when (source) {
            StudioSource.STEAMGRIDDB -> buildList {
                val styles = type?.let { SGDB_STYLES[it] }.orEmpty()
                if (styles.isNotEmpty()) {
                    val chosen = f.sgdbStyles[type].orEmpty().count { it in styles }
                    add(StudioFilterRow("Style", StudioFilterOption.Open(StudioFilterGroup.STYLE),
                        if (chosen == 0) "All" else "$chosen of ${styles.size}"))
                }
                if (type?.let { SGDB_DIMENSIONS[it] } != null) {
                    add(StudioFilterRow("Dimensions", StudioFilterOption.Open(StudioFilterGroup.DIMENSIONS),
                        f.sgdbDimensions[type]?.let(::sgdbDimensionLabel) ?: "All"))
                }
                add(StudioFilterRow("Animation", StudioFilterOption.Open(StudioFilterGroup.ANIMATION), f.sgdbAnimation.label))
                add(StudioFilterRow("Mature", StudioFilterOption.Mature, onOff(state.includeNsfw)))
                add(StudioFilterRow("Humor", StudioFilterOption.Humor, onOff(f.sgdbHumor)))
                add(StudioFilterRow("Epilepsy Warning", StudioFilterOption.Epilepsy, onOff(f.sgdbEpilepsy)))
                add(StudioFilterRow("Clear Filters", StudioFilterOption.Clear))
            }
            StudioSource.SCREENSCRAPER -> buildList {
                val region = f.ssRegion
                val regionValue = if (region != null && region == defaultRegion) "${regionLabel(region)} · from disc" else regionLabel(region)
                add(StudioFilterRow("Region", StudioFilterOption.Open(StudioFilterGroup.REGION), regionValue))
                if (ssTypes.size > 1) {
                    add(StudioFilterRow("Media", StudioFilterOption.Open(StudioFilterGroup.MEDIA), f.ssMedia[kind] ?: "All"))
                }
                add(StudioFilterRow("Clear Filters", StudioFilterOption.Clear))
            }
            else -> emptyList()
        }
        StudioFilterGroup.STYLE -> type?.let { SGDB_STYLES[it] }.orEmpty().map { style ->
            StudioFilterRow(sgdbStyleLabel(style), StudioFilterOption.Style(style), checked = style in f.sgdbStyles[type].orEmpty())
        }
        StudioFilterGroup.DIMENSIONS -> listOf<String?>(null).plus(type?.let { SGDB_DIMENSIONS[it] }.orEmpty()).map { size ->
            StudioFilterRow(size?.let(::sgdbDimensionLabel) ?: "All", StudioFilterOption.Dimension(size), checked = size == f.sgdbDimensions[type])
        }
        StudioFilterGroup.ANIMATION -> SgdbAnimation.entries.map {
            StudioFilterRow(it.label, StudioFilterOption.Animation(it), checked = it == f.sgdbAnimation)
        }
        StudioFilterGroup.REGION -> (listOf<String?>(null) + regions).map { code ->
            val label = if (code != null && code == defaultRegion) "${regionLabel(code)} · from disc" else regionLabel(code)
            StudioFilterRow(label, StudioFilterOption.Region(code), checked = code == f.ssRegion)
        }
        StudioFilterGroup.MEDIA -> (listOf<String?>(null) + ssTypes).map { media ->
            StudioFilterRow(media ?: "All", StudioFilterOption.Media(media), checked = media == f.ssMedia[kind])
        }
    }
}
