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
 * See docs/plans/multi-disc-games-plan.md step 4.
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

    @Test
    fun `an unreadable gdi suppresses nothing`() {
        val dir = tmp.newFolder()
        val gdi = File(dir, "Game.gdi").apply { writeText("") }
        val track = File(dir, "track01.bin").apply { writeText("x") }

        val result = resolver.resolveFiles(listOf(gdi, track))

        assertTrue(result.suppressedPaths.isEmpty())
    }
}
