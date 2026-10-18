package com.playfieldportal.feature.library.scanner

import com.playfieldportal.core.data.platform.PlatformFolderHintResolver
import io.mockk.mockk
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Raw-path disc resolution: a .cue hides its .bin companions and a Dreamcast .gdi hides its track
 * files, so the ROM-root walk (and now the raw Memory Card scan) never makes companions game rows.
 */
class DiscImageResolverTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val resolver = DiscImageResolver(mockk<PlatformFolderHintResolver>(relaxed = true))

    @Test
    fun `cue companion bins are suppressed`() {
        val dir = tmp.newFolder()
        val cue = File(dir, "Final Fantasy VII (Disc 1).cue").apply {
            writeText("FILE \"Final Fantasy VII (Disc 1).bin\" BINARY")
        }
        val bin = File(dir, "Final Fantasy VII (Disc 1).bin").apply { writeText("x") }

        val result = resolver.resolveFiles(listOf(cue, bin))

        assertEquals(setOf(bin.absolutePath), result.suppressedPaths)
        assertEquals(cue.absolutePath, result.resolvedDiscs.single().launchFile.absolutePath)
    }

    @Test
    fun `a bin not referenced by its cue is not suppressed`() {
        val dir = tmp.newFolder()
        val cue = File(dir, "Game.cue").apply { writeText("FILE \"Game (Track 1).bin\" BINARY") }
        val other = File(dir, "Other.bin").apply { writeText("x") }

        val result = resolver.resolveFiles(listOf(cue, other))

        assertTrue(other.absolutePath !in result.suppressedPaths)
    }

    @Test
    fun `gdi track files are suppressed`() {
        val dir = tmp.newFolder()
        val gdi = File(dir, "Crazy Taxi (Disc 1).gdi").apply {
            writeText("1 0 4 2352 \"track01.bin\" 0\n2 45000 150 2352 track02.raw 0")
        }
        val track01 = File(dir, "track01.bin").apply { writeText("x") }
        val track02 = File(dir, "track02.raw").apply { writeText("x") }

        val result = resolver.resolveFiles(listOf(gdi, track01, track02))

        assertEquals(setOf(track01.absolutePath, track02.absolutePath), result.suppressedPaths)
    }

    @Test
    fun `a file not listed by the gdi is not suppressed`() {
        val dir = tmp.newFolder()
        val gdi = File(dir, "Game.gdi").apply { writeText("1 0 4 2352 \"track01.bin\" 0") }
        val other = File(dir, "cover.bin").apply { writeText("x") }

        val result = resolver.resolveFiles(listOf(gdi, other))

        assertTrue(other.absolutePath !in result.suppressedPaths)
    }

    // ── Only a sheet the scan can turn into a row may suppress ─────────────

    @Test
    fun `a cue outside the scannable extensions suppresses nothing`() {
        // A card that cannot scan .cue (PS2 shipped iso,bin,chd until v58, and a trimmed list can
        // look the same): the .cue is never a game row, so the .bin it lists must stay one
        // (issue #22).
        val dir = tmp.newFolder()
        val cue = File(dir, "Dragon Quest VIII.cue").apply {
            writeText("FILE \"Dragon Quest VIII.bin\" BINARY")
        }
        val bin = File(dir, "Dragon Quest VIII.bin").apply { writeText("x") }

        val unscannable = resolver.resolveFiles(listOf(cue, bin), scannableSheets = setOf("iso", "bin", "chd"))
        val scannable = resolver.resolveFiles(listOf(cue, bin), scannableSheets = setOf("cue", "bin"))

        assertTrue(bin.absolutePath !in unscannable.suppressedPaths)
        // The sheet is not a row, so the bin is not a companion either: it resolves as a disc of
        // its own (a bin with no usable sheet is an orphan, exactly like a sheet-less dump).
        assertEquals(bin.absolutePath, unscannable.resolvedDiscs.single().launchFile.absolutePath)
        assertEquals(setOf(bin.absolutePath), scannable.suppressedPaths)
        assertEquals(cue.absolutePath, scannable.resolvedDiscs.single().launchFile.absolutePath)
    }

    @Test
    fun `a gdi outside the scannable extensions suppresses nothing`() {
        val dir = tmp.newFolder()
        val gdi = File(dir, "Crazy Taxi.gdi").apply { writeText("1 0 4 2352 \"track01.bin\" 0") }
        val track = File(dir, "track01.bin").apply { writeText("x") }

        val result = resolver.resolveFiles(listOf(gdi, track), scannableSheets = setOf("bin"))

        assertTrue(result.suppressedPaths.isEmpty())
    }

    @Test
    fun `an unreadable gdi suppresses nothing`() {
        val dir = tmp.newFolder()
        val gdi = File(dir, "Game.gdi").apply { writeText("") }
        val track = File(dir, "track01.bin").apply { writeText("x") }

        val result = resolver.resolveFiles(listOf(gdi, track))

        assertTrue(result.suppressedPaths.isEmpty())
    }
}
