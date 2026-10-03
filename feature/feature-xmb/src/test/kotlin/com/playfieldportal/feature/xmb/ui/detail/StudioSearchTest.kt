package com.playfieldportal.feature.xmb.ui.detail

import com.playfieldportal.feature.artwork.api.SsCachedMedia
import com.playfieldportal.feature.artwork.store.StudioArtworkSlot
import com.playfieldportal.feature.artwork.store.ArtworkKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioSearchTest {

    // ── Normalization (task 1.1) ──────────────────────────────────────────────

    @Test
    fun `whitespace, case and punctuation collapse to one key`() {
        assertEquals("final fantasy vii", StudioQuery.normalize("Final Fantasy VII"))
        assertEquals("final fantasy vii", StudioQuery.normalize("  final   FANTASY  vii  "))
        assertEquals("final fantasy vii", StudioQuery.normalize("Final Fantasy: VII"))
    }

    @Test
    fun `release tags never change which results a title addresses`() {
        assertTrue(StudioQuery.sameQuery("Final Fantasy X (USA)", "final fantasy x"))
        assertTrue(StudioQuery.sameQuery("Crash Bandicoot (USA) (Rev A)", "Crash Bandicoot"))
        assertTrue(StudioQuery.sameQuery("Metal Gear Solid (Disc 1)", "Metal Gear Solid"))
        assertTrue(StudioQuery.sameQuery("Sonic [!]", "Sonic"))
    }

    @Test
    fun `an ampersand distinguishes titles and is kept`() {
        assertEquals("jak & daxter", StudioQuery.normalize("Jak & Daxter"))
        assertFalse(StudioQuery.sameQuery("Jak & Daxter", "Jak Daxter"))
    }

    // A query made only of tags still deserves its own cache entry rather than the empty one.
    @Test
    fun `a query that normalizes away keeps its own identity`() {
        assertEquals("[bios]", StudioQuery.normalize("[BIOS]"))
        assertNotEquals(StudioQuery.normalize("[BIOS]"), StudioQuery.normalize("(USA)"))
    }

    @Test
    fun `normalization is for keying only and never rewrites what the user typed`() {
        // The function is pure and returns a NEW string; nothing here mutates the input.
        val typed = "  Final Fantasy: VII (USA)  "
        StudioQuery.normalize(typed)
        assertEquals("  Final Fantasy: VII (USA)  ", typed)
    }

    // ── Request keys (task 1.2 / 1.3) ─────────────────────────────────────────

    @Test
    fun `two requests for the same thing are the same key`() {
        val a = StudioRequestKey.of("Final Fantasy X (USA)", StudioSource.IGDB, ArtworkKind.HERO, SgdbRequestFilter())
        val b = StudioRequestKey.of("final   fantasy x", StudioSource.IGDB, ArtworkKind.HERO, SgdbRequestFilter())
        assertEquals(a, b)
    }

    @Test
    fun `source, category and query each make a different request`() {
        val base = StudioRequestKey.of("Halo", StudioSource.IGDB, ArtworkKind.HERO, SgdbRequestFilter())
        assertNotEquals(base, StudioRequestKey.of("Halo", StudioSource.THEGAMESDB, ArtworkKind.HERO, SgdbRequestFilter()))
        assertNotEquals(base, StudioRequestKey.of("Halo", StudioSource.IGDB, ArtworkKind.LOGO, SgdbRequestFilter()))
        assertNotEquals(base, StudioRequestKey.of("Halo 2", StudioSource.IGDB, ArtworkKind.HERO, SgdbRequestFilter()))
    }

    // Task 1.3: mature is a SteamGridDB filter, so it must not touch any other source's key.
    @Test
    fun `SteamGridDB request filters only participate in SteamGridDB keys`() {
        for (source in StudioSource.entries.filter { it != StudioSource.STEAMGRIDDB }) {
            assertEquals(
                "$source's key must ignore mature",
                StudioRequestKey.of("Halo", source, ArtworkKind.HERO, sgdb = SgdbRequestFilter(mature = false)),
                StudioRequestKey.of("Halo", source, ArtworkKind.HERO, sgdb = SgdbRequestFilter(mature = true)),
            )
        }
        assertNotEquals(
            StudioRequestKey.of("Halo", StudioSource.STEAMGRIDDB, ArtworkKind.HERO, sgdb = SgdbRequestFilter(mature = false)),
            StudioRequestKey.of("Halo", StudioSource.STEAMGRIDDB, ArtworkKind.HERO, sgdb = SgdbRequestFilter(mature = true)),
        )
    }

    @Test
    fun `the confirmed match is part of the key, so Phase 2 invalidates the right entries`() {
        assertNotEquals(
            StudioRequestKey.of("Halo", StudioSource.IGDB, ArtworkKind.HERO, SgdbRequestFilter(), matchId = null),
            StudioRequestKey.of("Halo", StudioSource.IGDB, ArtworkKind.HERO, SgdbRequestFilter(), matchId = "igdb:1234"),
        )
    }

    // ── Cache (task 1.2) ──────────────────────────────────────────────────────

    @Test
    fun `each key keeps its own results`() {
        val cache = StudioResultCache()
        val sgdb = StudioRequestKey.of("Halo", StudioSource.STEAMGRIDDB, ArtworkKind.HERO, SgdbRequestFilter())
        val igdb = StudioRequestKey.of("Halo", StudioSource.IGDB, ArtworkKind.HERO, SgdbRequestFilter())
        cache[sgdb] = listOf(art("a"))
        cache[igdb] = listOf(art("b"), art("c"))

        assertEquals(listOf(art("a")), cache[sgdb])
        assertEquals(2, cache[igdb]?.size)
        assertNull(cache[StudioRequestKey.of("Doom", StudioSource.IGDB, ArtworkKind.HERO, SgdbRequestFilter())])
    }

    @Test
    fun `evicting one source leaves the others alone`() {
        val cache = StudioResultCache()
        val sgdb = StudioRequestKey.of("Halo", StudioSource.STEAMGRIDDB, ArtworkKind.HERO, SgdbRequestFilter())
        val igdb = StudioRequestKey.of("Halo", StudioSource.IGDB, ArtworkKind.HERO, SgdbRequestFilter())
        cache[sgdb] = listOf(art("a"))
        cache[igdb] = listOf(art("b"))

        cache.evictSource(StudioSource.STEAMGRIDDB)

        assertFalse(cache.contains(sgdb))
        assertTrue(cache.contains(igdb))
    }

    @Test
    fun `the cache is bounded and evicts least-recently-used entries`() {
        val cache = StudioResultCache(maxEntries = 2)
        val a = StudioRequestKey.of("A", StudioSource.IGDB, ArtworkKind.HERO, SgdbRequestFilter())
        val b = StudioRequestKey.of("B", StudioSource.IGDB, ArtworkKind.HERO, SgdbRequestFilter())
        val c = StudioRequestKey.of("C", StudioSource.IGDB, ArtworkKind.HERO, SgdbRequestFilter())
        cache[a] = listOf(art("a"))
        cache[b] = listOf(art("b"))
        cache[a]                       // touch A so B is now the oldest
        cache[c] = listOf(art("c"))

        assertEquals(2, cache.size)
        assertTrue(cache.contains(a))
        assertFalse(cache.contains(b))
        assertTrue(cache.contains(c))
    }

    // ── Paging (task 1.4) ─────────────────────────────────────────────────────

    @Test
    fun `a page is one gridful, and the range reads 1-based`() {
        val all = (1..50).map { art("u$it") }
        val first = StudioPage.of(all, 0, 20)
        assertEquals(20, first.items.size)
        assertEquals(3, first.pageCount)
        assertEquals(1, first.rangeStart)
        assertEquals(20, first.rangeEnd)
        assertFalse(first.hasPrevious)
        assertTrue(first.hasNext)

        val last = StudioPage.of(all, 2, 20)
        assertEquals(10, last.items.size)
        assertEquals(41, last.rangeStart)
        assertEquals(50, last.rangeEnd)
        assertTrue(last.hasPrevious)
        assertFalse(last.hasNext)
    }

    @Test
    fun `an out-of-range page clamps instead of showing nothing`() {
        val all = (1..25).map { art("u$it") }
        assertEquals(1, StudioPage.of(all, 99, 20).pageIndex)
        assertEquals(0, StudioPage.of(all, -5, 20).pageIndex)
    }

    @Test
    fun `an empty result list has no pages and an empty range`() {
        val page = StudioPage.of(emptyList(), 0, 20)
        assertEquals(0, page.pageCount)
        assertEquals(0, page.rangeStart)
        assertEquals(0, page.rangeEnd)
        assertEquals(0, page.totalResults)
        assertFalse(page.hasPrevious)
        assertFalse(page.hasNext)
    }

    @Test
    fun `an exact multiple of the page size does not produce a trailing empty page`() {
        assertEquals(2, StudioPage.of((1..40).map { art("u$it") }, 0, 20).pageCount)
    }

    // ── Asset keys (task 5.1) ─────────────────────────────────────────────────

    @Test
    fun `a ScreenScraper asset is the same asset whatever account fetched its URL`() {
        val anonymous = "https://neoclone.screenscraper.fr/api2/mediaJeu.php" +
            "?devid=pfp&devpassword=x&softname=pfp&ssid=&sspassword=&systemeid=57&jeuid=3&media=ss(wor)"
        val signedIn = "https://neoclone.screenscraper.fr/api2/mediaJeu.php" +
            "?devid=pfp&devpassword=x&softname=pfp&ssid=me&sspassword=secret&systemeid=57&jeuid=3&media=ss%28wor%29"

        assertEquals("3:ss(wor)", ScreenScraperAssetId.of(anonymous))
        assertEquals(ScreenScraperAssetId.of(anonymous), ScreenScraperAssetId.of(signedIn))
    }

    @Test
    fun `a URL without both a game id and a media name has no ScreenScraper asset id`() {
        assertNull(ScreenScraperAssetId.of("https://x/api2/mediaJeu.php?jeuid=3"))
        assertNull(ScreenScraperAssetId.of("https://x/api2/mediaJeu.php?jeuid=&media=ss"))
        assertNull(ScreenScraperAssetId.of("https://cdn.example/box.png"))
        assertNull(ScreenScraperAssetId.of(null))
    }

    @Test
    fun `an asset key prefers the provider's id and falls back to the URL`() {
        val withId = StudioArt(url = "https://sgdb/a.png", thumb = null, provider = "SteamGridDB", providerAssetId = "grids:42")
        val sameAssetElsewhere = withId.copy(url = "https://mirror/a.png")

        assertEquals(
            StudioArtKey.of(ArtworkKind.SCREENSHOT, withId),
            StudioArtKey.of(ArtworkKind.SCREENSHOT, sameAssetElsewhere),
        )
        assertEquals("u1", StudioArtKey.of(ArtworkKind.SCREENSHOT, art("u1")).asset)
    }

    @Test
    fun `one asset offered on two tabs is two keys`() {
        assertNotEquals(StudioArtKey.of(ArtworkKind.ICON, art("u1")), StudioArtKey.of(ArtworkKind.SCREENSHOT, art("u1")))
    }

    // ── ScreenScraper tiles (found on device during task 5.2) ─────────────────
    // Shapes taken from real ss_media_cache rows: the same entry twice, and one file under three regions.

    private fun ssUrl(media: String) = "https://neoclone.screenscraper.fr/api2/mediaJeu.php" +
        "?devid=pfp&devpassword=x&softname=pfp&ssid=&sspassword=&systemeid=57&jeuid=3&media=$media"

    @Test
    fun `a file ScreenScraper lists twice is one tile`() {
        val tiles = screenScraperTiles(
            ArtworkKind.SCREENSHOT,
            listOf("ss"),
            listOf(SsCachedMedia("ss", "wor", ssUrl("ss(wor)")), SsCachedMedia("ss", "wor", ssUrl("ss(wor)"))),
        )

        assertEquals(listOf("ss · WOR"), tiles.map { it.label })
    }

    @Test
    fun `one file listed under several regions is one tile naming them all`() {
        val tiles = screenScraperTiles(
            ArtworkKind.ICON,
            listOf("screenmarquee"),
            listOf("wor", "uk", "us").map { SsCachedMedia("screenmarquee", it, ssUrl("screenmarquee(wor)")) },
        )

        assertEquals(1, tiles.size)
        assertEquals("screenmarquee · WOR/UK/US", tiles.single().label)
        assertEquals("3:screenmarquee(wor)", tiles.single().providerAssetId)
        // The Region filter reads every region the one tile stands for.
        assertEquals(StudioArtFacets(mediaType = "screenmarquee", regions = listOf("wor", "uk", "us")), tiles.single().facets)
    }

    @Test
    fun `different files stay separate tiles, in the tab's type order`() {
        val tiles = screenScraperTiles(
            ArtworkKind.SCREENSHOT,
            listOf("ss", "sstitle"),
            listOf(
                SsCachedMedia("sstitle", "wor", ssUrl("sstitle(wor)")),
                SsCachedMedia("ss", "wor", ssUrl("ss(wor)")),
                SsCachedMedia("ss", "jp", ssUrl("ss(jp)")),
                SsCachedMedia("box-2D", "wor", ssUrl("box-2D(wor)")),
            ),
        )

        assertEquals(listOf("ss · WOR", "ss · JP", "sstitle · WOR"), tiles.map { it.label })
    }

    // ── What a slot already holds (found on device during task 5.2) ───────────

    private fun slot(originUrl: String?, providerAssetId: String? = null) = StudioArtworkSlot(
        sortOrder = 0, documentUri = "content://x", provider = null,
        originUrl = originUrl, providerAssetId = providerAssetId, sizeBytes = 0,
    )

    @Test
    fun `a held asset is recognised by its asset id, or by the URL it was downloaded from`() {
        val library = StudioLibraryAssets.of(
            ArtworkKind.SCREENSHOT,
            listOf(
                slot(originUrl = "https://sgdb/mirror/1.png", providerAssetId = "grids:1"),
                slot(originUrl = "https://cdn.thegamesdb.net/ss/1.jpg"),
            ),
        )
        val sgdb = StudioArt(url = "https://sgdb/1.png", thumb = null, provider = "SteamGridDB", providerAssetId = "grids:1")
        val tgdb = StudioArt(url = "https://cdn.thegamesdb.net/ss/1.jpg", thumb = null, provider = "TheGamesDB")

        assertTrue(library.holds(ArtworkKind.SCREENSHOT, sgdb))
        assertTrue(library.holds(ArtworkKind.SCREENSHOT, tgdb))
        assertFalse(library.holds(ArtworkKind.SCREENSHOT, tgdb.copy(url = "https://cdn.thegamesdb.net/ss/2.jpg")))
        assertFalse("another tab's slot", library.holds(ArtworkKind.VIDEO, tgdb))
        assertEquals(listOf(0), library.sortOrdersHolding(tgdb))
    }

    @Test
    fun `a ScreenScraper file stored with other credentials is still held`() {
        val storedSignedIn = ssUrl("ss(wor)").replace("ssid=&sspassword=", "ssid=me&sspassword=secret")
        val library = StudioLibraryAssets.of(ArtworkKind.SCREENSHOT, listOf(slot(originUrl = storedSignedIn)))
        val tile = screenScraperTiles(
            ArtworkKind.SCREENSHOT, listOf("ss"), listOf(SsCachedMedia("ss", "wor", ssUrl("ss(wor)"))),
        ).single()

        assertTrue(library.holds(ArtworkKind.SCREENSHOT, tile))
    }

    @Test
    fun `a single-art slot holds its one asset the same way (task 5-3)`() {
        // The comparison never looked at how many assets the kind takes; 5.3 relies on that, because
        // a single-art slot's position-0 record is the whole library it compares against.
        val library = StudioLibraryAssets.of(ArtworkKind.BOX_ART, listOf(slot(originUrl = "https://sgdb/1.png")))
        val tile = StudioArt(url = "https://sgdb/1.png", thumb = null, provider = "SteamGridDB")

        assertTrue(library.holds(ArtworkKind.BOX_ART, tile))
        assertFalse(library.holds(ArtworkKind.BOX_ART, tile.copy(url = "https://sgdb/2.png")))
        assertFalse("the box art slot says nothing about the hero slot", library.holds(ArtworkKind.HERO, tile))
    }

    // ── Local File's grid is the slot itself ──────────────────────────────────

    private fun stored(sortOrder: Int, provider: String?) = StudioArtworkSlot(
        sortOrder = sortOrder, documentUri = "content://s$sortOrder", provider = provider,
        originUrl = null, providerAssetId = null, sizeBytes = 1,
    )

    @Test
    fun `local art tiles are the slot's stored files, in position order`() {
        val library = StudioLibraryAssets.of(
            ArtworkKind.SCREENSHOT, listOf(stored(0, "ScreenScraper"), stored(1, "Local file")),
        )

        val tiles = localArtTiles(ArtworkKind.SCREENSHOT, library)

        assertEquals(listOf("content://s0", "content://s1"), tiles.map { it.url })
        assertEquals(listOf("content://s0", "content://s1"), tiles.map { it.thumb })
        assertTrue(tiles.all { it.provider == LOCAL_ART })
        assertEquals("where each file came from", listOf("ScreenScraper", "Local file"), tiles.map { it.label })
    }

    @Test
    fun `local art tiles wait for the library of their own kind`() {
        // A tab switch shows the grid before the new slot is re-read.
        val library = StudioLibraryAssets.of(ArtworkKind.VIDEO, listOf(stored(0, "Local file")))

        assertTrue(localArtTiles(ArtworkKind.SCREENSHOT, library).isEmpty())
    }

    @Test
    fun `a local art tile is held by its file URI, and a provider tile never is`() {
        val library = StudioLibraryAssets.of(ArtworkKind.SCREENSHOT, listOf(stored(0, "Local file"), stored(1, "Local file")))
        val tile = localArtTiles(ArtworkKind.SCREENSHOT, library)[1]

        assertTrue(library.holds(ArtworkKind.SCREENSHOT, tile))
        assertEquals(listOf(1), library.sortOrdersHolding(tile))
        assertFalse(
            "a provider asset whose URL happens to equal a stored URI is not that file",
            library.holds(ArtworkKind.SCREENSHOT, StudioArt(url = "content://s1", thumb = null, provider = "IGDB")),
        )
    }

    private fun art(url: String) = StudioArt(url = url, thumb = null, provider = "test")

    // ── Steam store media → tiles ─────────────────────────────────────────────

    private val steam = com.playfieldportal.feature.artwork.api.SteamStoreMedia(
        appId = "620",
        libraryCapsule = "capsule600x900",
        libraryHero = "hero",
        logo = "logo",
        header = "header",
        mainCapsule = "capsule616",
        pageBackground = "pagebg",
        screenshots = listOf(
            com.playfieldportal.feature.artwork.api.SteamScreenshot("ss1", "ss1-thumb"),
            com.playfieldportal.feature.artwork.api.SteamScreenshot("ss2", "ss2-thumb"),
        ),
        trailers = listOf(
            com.playfieldportal.feature.artwork.api.SteamTrailer(
                id = 7, name = "Launch", poster = "poster", shortUrl = "micro.mp4", fullUrl = "full.m3u8",
            ),
            com.playfieldportal.feature.artwork.api.SteamTrailer(
                id = 8, name = "Teaser", poster = null, shortUrl = null, fullUrl = "teaser.m3u8",
            ),
        ),
    )

    @Test
    fun `each Steam tab gets the asset Steam made for that shape`() {
        assertEquals(listOf("capsule600x900"), steamTiles(ArtworkKind.BOX_ART, steam).map { it.url })
        assertEquals(listOf("hero"), steamTiles(ArtworkKind.HERO, steam).map { it.url })
        assertEquals(listOf("logo"), steamTiles(ArtworkKind.LOGO, steam).map { it.url })
        // ICON0 is cropped from the landscape art.
        assertEquals(listOf("header", "capsule616", "hero"), steamTiles(ArtworkKind.ICON, steam).map { it.url })
        assertEquals(listOf("hero", "pagebg", "ss1", "ss2"), steamTiles(ArtworkKind.BACKGROUND, steam).map { it.url })
    }

    @Test
    fun `the screenshot tab leads with Steam's screenshots, thumbnails included`() {
        val tiles = steamTiles(ArtworkKind.SCREENSHOT, steam)

        assertEquals(listOf("ss1", "ss2"), tiles.take(2).map { it.url })
        assertEquals("ss1-thumb", tiles.first().thumb)
        // The other art follows, for the crop editor to shape, like every show-all tab.
        assertTrue(tiles.drop(2).map { it.url }.containsAll(listOf("capsule600x900", "hero", "header")))
    }

    @Test
    fun `both video tabs list each full trailer, then its short cut, with the poster as thumbnail`() {
        for (kind in listOf(ArtworkKind.VIDEO, ArtworkKind.ICON1)) {
            val tiles = steamTiles(kind, steam)
            assertEquals(listOf("full.m3u8", "micro.mp4", "teaser.m3u8"), tiles.map { it.url })
            assertEquals(listOf("Launch", "Launch · short", "Teaser"), tiles.map { it.label })
            assertTrue(tiles.all { it.isVideo })
            assertEquals("poster", tiles.first().thumb)
            // The full stream and its short cut are different files.
            assertEquals(listOf("620:trailer:7", "620:trailer:7:short", "620:trailer:8"), tiles.map { it.providerAssetId })
        }
    }

    @Test
    fun `Steam has no manual, and missing assets are simply absent`() {
        assertTrue(steamTiles(ArtworkKind.MANUAL, steam).isEmpty())
        val bare = steam.copy(libraryCapsule = null, libraryHero = null, screenshots = emptyList(), trailers = emptyList())
        assertTrue(steamTiles(ArtworkKind.BOX_ART, bare).isEmpty())
        assertTrue(steamTiles(ArtworkKind.VIDEO, bare).isEmpty())
    }

    @Test
    fun `every Steam tile is tagged Steam with an id stable across tabs`() {
        val onIcon = steamTiles(ArtworkKind.ICON, steam).first { it.url == "hero" }
        val onHero = steamTiles(ArtworkKind.HERO, steam).single()

        assertEquals("Steam", onHero.provider)
        assertEquals("620:library_hero", onHero.providerAssetId)
        assertEquals(onHero.providerAssetId, onIcon.providerAssetId)
    }
}
