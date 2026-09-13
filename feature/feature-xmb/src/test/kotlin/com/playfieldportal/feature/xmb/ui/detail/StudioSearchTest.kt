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
        val a = StudioRequestKey.of("Final Fantasy X (USA)", StudioSource.IGDB, ArtworkKind.HERO, false)
        val b = StudioRequestKey.of("final   fantasy x", StudioSource.IGDB, ArtworkKind.HERO, false)
        assertEquals(a, b)
    }

    @Test
    fun `source, category and query each make a different request`() {
        val base = StudioRequestKey.of("Halo", StudioSource.IGDB, ArtworkKind.HERO, false)
        assertNotEquals(base, StudioRequestKey.of("Halo", StudioSource.THEGAMESDB, ArtworkKind.HERO, false))
        assertNotEquals(base, StudioRequestKey.of("Halo", StudioSource.IGDB, ArtworkKind.LOGO, false))
        assertNotEquals(base, StudioRequestKey.of("Halo 2", StudioSource.IGDB, ArtworkKind.HERO, false))
    }

    // Task 1.3: mature is a SteamGridDB filter, so it must not touch any other source's key.
    @Test
    fun `mature only participates in SteamGridDB keys`() {
        for (source in StudioSource.entries.filter { it != StudioSource.STEAMGRIDDB }) {
            assertEquals(
                "$source's key must ignore mature",
                StudioRequestKey.of("Halo", source, ArtworkKind.HERO, includeNsfw = false),
                StudioRequestKey.of("Halo", source, ArtworkKind.HERO, includeNsfw = true),
            )
        }
        assertNotEquals(
            StudioRequestKey.of("Halo", StudioSource.STEAMGRIDDB, ArtworkKind.HERO, includeNsfw = false),
            StudioRequestKey.of("Halo", StudioSource.STEAMGRIDDB, ArtworkKind.HERO, includeNsfw = true),
        )
    }

    @Test
    fun `the confirmed match is part of the key, so Phase 2 invalidates the right entries`() {
        assertNotEquals(
            StudioRequestKey.of("Halo", StudioSource.IGDB, ArtworkKind.HERO, false, matchId = null),
            StudioRequestKey.of("Halo", StudioSource.IGDB, ArtworkKind.HERO, false, matchId = "igdb:1234"),
        )
    }

    // ── Cache (task 1.2) ──────────────────────────────────────────────────────

    @Test
    fun `each key keeps its own results`() {
        val cache = StudioResultCache()
        val sgdb = StudioRequestKey.of("Halo", StudioSource.STEAMGRIDDB, ArtworkKind.HERO, false)
        val igdb = StudioRequestKey.of("Halo", StudioSource.IGDB, ArtworkKind.HERO, false)
        cache[sgdb] = listOf(art("a"))
        cache[igdb] = listOf(art("b"), art("c"))

        assertEquals(listOf(art("a")), cache[sgdb])
        assertEquals(2, cache[igdb]?.size)
        assertNull(cache[StudioRequestKey.of("Doom", StudioSource.IGDB, ArtworkKind.HERO, false)])
    }

    @Test
    fun `evicting one source leaves the others alone`() {
        val cache = StudioResultCache()
        val sgdb = StudioRequestKey.of("Halo", StudioSource.STEAMGRIDDB, ArtworkKind.HERO, false)
        val igdb = StudioRequestKey.of("Halo", StudioSource.IGDB, ArtworkKind.HERO, false)
        cache[sgdb] = listOf(art("a"))
        cache[igdb] = listOf(art("b"))

        cache.evictSource(StudioSource.STEAMGRIDDB)

        assertFalse(cache.contains(sgdb))
        assertTrue(cache.contains(igdb))
    }

    @Test
    fun `the cache is bounded and evicts least-recently-used entries`() {
        val cache = StudioResultCache(maxEntries = 2)
        val a = StudioRequestKey.of("A", StudioSource.IGDB, ArtworkKind.HERO, false)
        val b = StudioRequestKey.of("B", StudioSource.IGDB, ArtworkKind.HERO, false)
        val c = StudioRequestKey.of("C", StudioSource.IGDB, ArtworkKind.HERO, false)
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

    private fun art(url: String) = StudioArt(url = url, thumb = null, provider = "test")
}
