package com.playfieldportal.feature.artwork.match

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileTitleSearchStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var clock = 1_000L

    private fun store(maxEntries: Int = 500) =
        FileTitleSearchStore(folder.root.resolve("match-searches"), now = { clock }, maxEntries = maxEntries)

    private val switch = GameCandidate(
        provider = MatchProvider.SCREENSCRAPER,
        providerGameId = "425726",
        title = "Tactics Ogre - Reborn",
        platformName = "Switch",
        releaseYear = 2022,
        thumbUrl = null,
        gameArtCount = 37,
    )

    @Test
    fun `a kept search reads back whole, from a new store over the same folder`() = runTest {
        store().write(MatchProvider.SCREENSCRAPER, "tactics ogre", "every-platform:windows", StoredTitleSearch(listOf(switch), 5_000L))

        val read = store().read(MatchProvider.SCREENSCRAPER, "tactics ogre", "every-platform:windows")

        assertEquals(StoredTitleSearch(listOf(switch), 5_000L), read)
    }

    @Test
    fun `an empty answer round-trips as empty, not as missing`() = runTest {
        store().write(MatchProvider.SCREENSCRAPER, "tactics ogre", "windows", StoredTitleSearch(emptyList(), 5_000L))

        assertEquals(emptyList<GameCandidate>(), store().read(MatchProvider.SCREENSCRAPER, "tactics ogre", "windows")?.candidates)
    }

    @Test
    fun `provider, query and scope each address their own entry`() = runTest {
        val store = store()
        store.write(MatchProvider.SCREENSCRAPER, "tactics ogre", "psp", StoredTitleSearch(listOf(switch), 5_000L))

        assertNull(store.read(MatchProvider.IGDB, "tactics ogre", "psp"))
        assertNull(store.read(MatchProvider.SCREENSCRAPER, "tactics ogre reborn", "psp"))
        assertNull(store.read(MatchProvider.SCREENSCRAPER, "tactics ogre", "every-platform:psp"))
    }

    @Test
    fun `an expired entry reads as missing and is deleted`() = runTest {
        val store = store()
        store.write(MatchProvider.SCREENSCRAPER, "tactics ogre", "psp", StoredTitleSearch(listOf(switch), 5_000L))

        clock = 5_000L

        assertNull(store.read(MatchProvider.SCREENSCRAPER, "tactics ogre", "psp"))
        assertTrue(folder.root.resolve("match-searches").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `an unreadable file reads as empty and is deleted`() = runTest {
        val store = store()
        store.write(MatchProvider.SCREENSCRAPER, "tactics ogre", "psp", StoredTitleSearch(listOf(switch), 5_000L))
        val file = folder.root.resolve("match-searches").listFiles()!!.single()
        file.writeText("{\"provider\":\"SCREENSCRA")

        assertNull(store.read(MatchProvider.SCREENSCRAPER, "tactics ogre", "psp"))
        assertTrue(!file.exists())
    }

    @Test
    fun `a missing folder reads as empty`() = runTest {
        assertNull(store().read(MatchProvider.SCREENSCRAPER, "tactics ogre", "psp"))
    }

    @Test
    fun `only the newest entries are kept`() = runTest {
        val store = store(maxEntries = 2)
        val dir = folder.root.resolve("match-searches")
        // File times are set by hand, whole seconds in the past, so the order does not rest on how
        // finely the file system records them.
        // A search's candidates are always the searched provider's, so these are kept under ScreenScraper.
        store.write(MatchProvider.SCREENSCRAPER, "a", "psp", StoredTitleSearch(listOf(switch), 5_000L))
        dir.listFiles()!!.single().setLastModified(10_000L)
        store.write(MatchProvider.SCREENSCRAPER, "b", "psp", StoredTitleSearch(listOf(switch), 5_000L))
        dir.listFiles()!!.single { it.lastModified() != 10_000L }.setLastModified(20_000L)

        store.write(MatchProvider.SCREENSCRAPER, "c", "psp", StoredTitleSearch(listOf(switch), 5_000L))

        assertEquals(2, dir.listFiles()!!.size)
        assertNull(store.read(MatchProvider.SCREENSCRAPER, "a", "psp"))
        assertEquals(listOf(switch), store.read(MatchProvider.SCREENSCRAPER, "c", "psp")?.candidates)
    }
}
