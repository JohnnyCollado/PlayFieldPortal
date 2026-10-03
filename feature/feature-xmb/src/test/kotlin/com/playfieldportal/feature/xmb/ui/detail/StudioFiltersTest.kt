package com.playfieldportal.feature.xmb.ui.detail

import com.playfieldportal.core.domain.model.GameRegion
import com.playfieldportal.feature.artwork.api.SgdbArtType
import com.playfieldportal.feature.artwork.store.ArtworkKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioFiltersTest {

    private fun sgdb(style: String?, w: Int = 600, h: Int = 900, type: SgdbArtType = SgdbArtType.GRID) =
        StudioArt(
            url = "u-$style-$w", thumb = null, provider = "SteamGridDB",
            facets = StudioArtFacets(sgdbType = type, style = style, width = w, height = h),
        )

    private fun ss(type: String, vararg regions: String) =
        StudioArt(
            url = "ss-$type-${regions.joinToString()}", thumb = null, provider = "ScreenScraper",
            facets = StudioArtFacets(mediaType = type, regions = regions.toList()),
        )

    // ── Region default ──────────────────────────────────────────────────────

    @Test
    fun `the disc's region picks ScreenScraper's region, and an unknown one picks none`() {
        assertEquals("us", StudioFilters.ssRegionFor(GameRegion.NTSC_U))
        assertEquals("eu", StudioFilters.ssRegionFor(GameRegion.PAL))
        assertEquals("jp", StudioFilters.ssRegionFor(GameRegion.NTSC_J))
        assertNull(StudioFilters.ssRegionFor(null))
    }

    @Test
    fun `a fresh open starts on the game's region and remembered styles`() {
        val styles = mapOf(SgdbArtType.GRID to setOf("material"))
        val fresh = StudioFilters.forOpen(GameRegion.PAL, styles)
        assertEquals("eu", fresh.ssRegion)
        assertEquals(styles, fresh.sgdbStyles)
        assertEquals(SgdbAnimation.STATIC, fresh.sgdbAnimation)
        assertFalse(fresh.sgdbHumor)
    }

    // ── SteamGridDB ─────────────────────────────────────────────────────────

    @Test
    fun `styles keep only the chosen styles of the tab's art type`() {
        val filters = StudioFilters(sgdbStyles = mapOf(SgdbArtType.GRID to setOf("material", "alternate")))
        val all = listOf(sgdb("material"), sgdb("blurred"), sgdb("alternate"), sgdb(null))
        val shown = filters.visible(StudioSource.STEAMGRIDDB, ArtworkKind.BOX_ART, all)
        assertEquals(listOf("material", "alternate"), shown.map { it.facets?.style })
    }

    @Test
    fun `no styles chosen shows every style`() {
        val all = listOf(sgdb("material"), sgdb("blurred"))
        assertEquals(all, StudioFilters().visible(StudioSource.STEAMGRIDDB, ArtworkKind.BOX_ART, all))
    }

    @Test
    fun `grid styles do not reach the hero tab`() {
        val filters = StudioFilters(sgdbStyles = mapOf(SgdbArtType.GRID to setOf("material")))
        val heroes = listOf(sgdb("blurred", type = SgdbArtType.HERO))
        assertEquals(heroes, filters.visible(StudioSource.STEAMGRIDDB, ArtworkKind.HERO, heroes))
    }

    @Test
    fun `a show-all tab is never narrowed by style or size`() {
        val filters = StudioFilters(
            sgdbStyles = mapOf(SgdbArtType.GRID to setOf("material")),
            sgdbDimensions = mapOf(SgdbArtType.GRID to "460x215"),
        )
        val all = listOf(sgdb("blurred"))
        assertEquals(all, filters.visible(StudioSource.STEAMGRIDDB, ArtworkKind.SCREENSHOT, all))
    }

    @Test
    fun `dimensions keep only that size`() {
        val filters = StudioFilters(sgdbDimensions = mapOf(SgdbArtType.GRID to "920x430"))
        val all = listOf(sgdb("a", 920, 430), sgdb("b", 600, 900))
        assertEquals(listOf(920), filters.visible(StudioSource.STEAMGRIDDB, ArtworkKind.ICON, all).map { it.facets?.width })
    }

    @Test
    fun `SteamGridDB filters leave other sources alone`() {
        val filters = StudioFilters(sgdbStyles = mapOf(SgdbArtType.GRID to setOf("material")))
        val igdb = listOf(StudioArt("x", null, "IGDB"))
        assertEquals(igdb, filters.visible(StudioSource.IGDB, ArtworkKind.BOX_ART, igdb))
    }

    @Test
    fun `the request filter moves only with what SteamGridDB is asked`() {
        val base = StudioFilters()
        assertEquals(base.sgdbRequest(false), base.copy(sgdbStyles = mapOf(SgdbArtType.GRID to setOf("x"))).sgdbRequest(false))
        assertNotEquals(base.sgdbRequest(false), base.sgdbRequest(true))
        assertNotEquals(base.sgdbRequest(false), base.copy(sgdbHumor = true).sgdbRequest(false))
        assertNotEquals(base.sgdbRequest(false), base.copy(sgdbAnimation = SgdbAnimation.ALL).sgdbRequest(false))
    }

    // ── ScreenScraper ───────────────────────────────────────────────────────

    @Test
    fun `a region keeps its own, world and region-less media`() {
        val filters = StudioFilters(ssRegion = "us")
        val all = listOf(ss("box-2D", "us"), ss("box-2D", "jp"), ss("box-2D", "wor"), ss("ss"), ss("box-2D", "eu", "us"))
        val shown = filters.visible(StudioSource.SCREENSCRAPER, ArtworkKind.BOX_ART, all)
        assertEquals(listOf(listOf("us"), listOf("wor"), emptyList(), listOf("eu", "us")), shown.map { it.facets?.regions })
    }

    @Test
    fun `media keeps one type on the tab it was chosen for`() {
        val filters = StudioFilters(ssMedia = mapOf(ArtworkKind.ICON to "mixrbv2"))
        val all = listOf(ss("mixrbv2", "us"), ss("box-2D", "us"))
        assertEquals(listOf("mixrbv2"), filters.visible(StudioSource.SCREENSCRAPER, ArtworkKind.ICON, all).map { it.facets?.mediaType })
        assertEquals(all, filters.visible(StudioSource.SCREENSCRAPER, ArtworkKind.BACKGROUND, all))
    }

    @Test
    fun `region choices list the known regions first, then what the results carry`() {
        val all = listOf(ss("box-2D", "fr"), ss("box-2D", "jp"), ss("box-2D", "wor"), ss("box-2D", "us"))
        assertEquals(listOf("us", "jp", "wor", "fr"), StudioFilters.regionChoices(all, default = null))
        assertEquals(listOf("eu", "us", "jp", "wor", "fr"), StudioFilters.regionChoices(all, default = "eu"))
    }

    // ── Active marker ───────────────────────────────────────────────────────

    @Test
    fun `defaults are not an active filter, anything narrower is`() {
        val clean = StudioFilters()
        assertFalse(clean.isActive(StudioSource.STEAMGRIDDB, ArtworkKind.BOX_ART, includeNsfw = false))
        assertTrue(clean.isActive(StudioSource.STEAMGRIDDB, ArtworkKind.BOX_ART, includeNsfw = true))
        assertTrue(
            clean.copy(sgdbStyles = mapOf(SgdbArtType.GRID to setOf("material")))
                .isActive(StudioSource.STEAMGRIDDB, ArtworkKind.BOX_ART, includeNsfw = false),
        )
        assertFalse(clean.isActive(StudioSource.SCREENSCRAPER, ArtworkKind.BOX_ART, includeNsfw = false))
        assertTrue(clean.copy(ssRegion = "us").isActive(StudioSource.SCREENSCRAPER, ArtworkKind.BOX_ART, includeNsfw = false))
        assertFalse(clean.copy(ssRegion = "us").isActive(StudioSource.IGDB, ArtworkKind.BOX_ART, includeNsfw = false))
    }

    @Test
    fun `clearing a source resets only that source`() {
        val filters = StudioFilters(
            sgdbStyles = mapOf(SgdbArtType.GRID to setOf("material")), sgdbHumor = true, ssRegion = "us",
        )
        val sgdbCleared = filters.cleared(StudioSource.STEAMGRIDDB)
        assertEquals(StudioFilters(ssRegion = "us"), sgdbCleared)
        assertEquals(StudioFilters(sgdbStyles = filters.sgdbStyles, sgdbHumor = true), filters.cleared(StudioSource.SCREENSCRAPER))
    }

    // ── Media names (context-menu plan task 3.9) ────────────────────────────

    @Test
    fun `ScreenScraper media codes read as names, and an unknown code stays as it is`() {
        assertEquals("Box Art 2D", ssMediaLabel("box-2D"))
        assertEquals("Mix v2", ssMediaLabel("mixrbv2"))
        assertEquals("Screenshot", ssMediaLabel("ss"))
        assertEquals("newtype", ssMediaLabel("newtype"))
        // Every code the Studio can browse has a name of its own.
        SS_TYPES_FOR_KIND.values.flatten().distinct().forEach { code ->
            assertNotEquals("$code has no readable name", code, ssMediaLabel(code))
        }
    }

    @Test
    fun `the Media filter shows names in its value and its list`() {
        val types = listOf("mixrbv2", "box-2D")
        val state = ArtworkStudioUiState(
            sourceIndex = StudioSource.entries.indexOf(StudioSource.SCREENSCRAPER),
            tabIndex = STUDIO_TABS.indexOfFirst { it.kind == ArtworkKind.ICON },
            filters = StudioFilters(ssMedia = mapOf(ArtworkKind.ICON to "box-2D")),
        )
        val root = studioFilterRows(state, StudioFilterGroup.FILTERS, types, emptyList())
        assertEquals("Box Art 2D", root.first { it.label == "Media" }.value)
        val list = studioFilterRows(state, StudioFilterGroup.MEDIA, types, emptyList())
        assertEquals(listOf("All", "Mix v2", "Box Art 2D"), list.map { it.label })
        assertEquals(listOf(false, false, true), list.map { it.checked })
    }
}
