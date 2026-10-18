package com.playfieldportal.feature.library.scanner

import com.playfieldportal.core.data.platform.PlatformFolderHintResolver
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Companion suppression is a promise about a REPLACEMENT row, not about a file: a .cue hides the
 * .bin it lists because the .cue becomes the game. When the card's own extension list cannot scan
 * .cue (PS2 shipped `iso,bin,chd` until v58, and any trimmed list can look the same), there is
 * nothing to replace it with — the .bin must stay a game, or the whole disc vanishes from the
 * library (issue #22).
 */
class CardDiscCompanionScanTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val arcadeRomsets = mockk<ArcadeRomsetCatalog> {
        every { decide(any(), any(), any()) } returns ArcadeRomsetCatalog.Decision.UseDefault
    }

    private val scanner = RomScanner(
        context = mockk(relaxed = true),
        platformExtensionMap = mockk(relaxed = true),
        discImageResolver = DiscImageResolver(PlatformFolderHintResolver()),
        folderHintResolver = PlatformFolderHintResolver(),
        arcadeRomsets = arcadeRomsets,
        discSetBuilder = DiscSetBuilder(),
        discCompanionSuppressor = DiscCompanionSuppressor(),
        m3uPlaylistReader = mockk(relaxed = true) { every { read(any()) } returns null },
        discRegionReader = mockk(relaxed = true) { every { read(any()) } returns null },
    )

    /** The seeder's PS2 card: a .cue is NOT one of its scannable extensions. */
    private val ps2Extensions = listOf("iso", "bin", "chd")

    /** The seeder's PSX card: the sheet itself is scannable, so it is the game. */
    private val psxExtensions = listOf("cue", "bin", "iso", "pbp", "chd")

    private suspend fun scan(
        root: File,
        extensions: List<String>,
        platformId: String,
    ): ScanResult.Complete =
        scanner.scanDirectory(root.absolutePath, extensions, platformId, true, emptySet())
            .toList()
            .filterIsInstance<ScanResult.Complete>()
            .single()

    /** A disc dumped as a sheet plus its data track — the shape both tests below use. */
    private fun sheetAndTrack(root: File, stem: String): Pair<File, File> {
        val cue = File(root, "$stem.cue").apply { writeText("FILE \"$stem.bin\" BINARY") }
        val bin = File(root, "$stem.bin").apply { writeText("x") }
        return cue to bin
    }

    @Test
    fun `a bin stays a game when the card cannot scan its cue`() = runTest {
        val root = temp.newFolder("ps2")
        val (_, bin) = sheetAndTrack(root, "Dragon Quest VIII")

        val complete = scan(root, ps2Extensions, "ps2")

        assertEquals(1, complete.newGames.size)
        assertEquals("Dragon Quest VIII", complete.newGames.single().title)
        assertEquals(bin.absolutePath, complete.newGames.single().romPath)
        assertEquals("ps2", complete.newGames.single().platformId)
        // Present, so a row the same scan already holds is never marked missing for it.
        assertTrue(bin.absolutePath in complete.presentRomPaths.orEmpty())
    }

    @Test
    fun `a bin in a subfolder stays a game when the card cannot scan its cue`() = runTest {
        val root = temp.newFolder("ps2")
        val sub = File(root, "RPGs").apply { mkdirs() }
        val (_, bin) = sheetAndTrack(sub, "Dragon Quest VIII")

        val complete = scan(root, ps2Extensions, "ps2")

        assertEquals(listOf(bin.absolutePath), complete.newGames.map { it.romPath })
    }

    @Test
    fun `where the card scans the sheet, the sheet is the game and the bin is not`() = runTest {
        val root = temp.newFolder("psx")
        val (cue, bin) = sheetAndTrack(root, "Final Fantasy VII (Disc 1)")

        val complete = scan(root, psxExtensions, "psx")

        assertEquals(listOf(cue.absolutePath), complete.newGames.map { it.romPath })
        assertFalse(bin.absolutePath in complete.newGames.map { it.romPath })
    }

    @Test
    fun `a ps2 card on the v58 default scans the sheet, so the disc is one row`() = runTest {
        // The migration gives PS2 the same shape as every other CD platform: cue+bin is one disc,
        // launched from the sheet, instead of a .bin row per track.
        val root = temp.newFolder("ps2")
        val (cue, _) = sheetAndTrack(root, "Dragon Quest VIII")
        val secondTrack = File(root, "Dragon Quest VIII (Track 2).bin").apply { writeText("y") }
        File(root, "Dragon Quest VIII.cue").appendText("\nFILE \"Dragon Quest VIII (Track 2).bin\" BINARY")

        val complete = scan(root, listOf("iso", "bin", "chd", "cue"), "ps2")

        assertEquals(listOf(cue.absolutePath), complete.newGames.map { it.romPath })
        assertFalse(secondTrack.absolutePath in complete.newGames.map { it.romPath })
    }

    @Test
    fun `a lone bin in a card folder is a game`() = runTest {
        val root = temp.newFolder("ps2")
        val bin = File(root, "Shadow of the Colossus.bin").apply { writeText("x") }

        val complete = scan(root, ps2Extensions, "ps2")

        assertEquals(listOf(bin.absolutePath), complete.newGames.map { it.romPath })
    }
}
