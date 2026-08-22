package com.playfieldportal.feature.library.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared sheet parsers: .cue FILE references and Dreamcast .gdi track names, normalised to
 * lowercase basenames so the raw-path resolver and the SAF suppressor match companions the same
 * way. See docs/plans/multi-disc-games-plan.md step 4.
 */
class DiscSheetsTest {

    @Test
    fun `cue sheet quoted FILE references parse`() {
        val lines = listOf(
            "FILE \"Final Fantasy VII (Disc 1).bin\" BINARY",
            "  TRACK 01 MODE2/2352",
            "    INDEX 01 00:00:00",
            "FILE \"Final Fantasy VII (Disc 1) (Track 2).bin\" BINARY",
        )

        assertEquals(
            setOf("final fantasy vii (disc 1).bin", "final fantasy vii (disc 1) (track 2).bin"),
            cueSheetReferences(lines),
        )
    }

    @Test
    fun `cue sheet unquoted FILE references parse`() {
        assertEquals(setOf("game.bin"), cueSheetReferences(listOf("FILE game.bin BINARY")))
    }

    @Test
    fun `cue sheet FILE references with a subdirectory path collapse to basenames`() {
        assertEquals(
            setOf("track02.bin"),
            cueSheetReferences(listOf("FILE \"sub/track02.bin\" BINARY")),
        )
    }

    @Test
    fun `cue sheet lines that are not FILE entries are ignored`() {
        assertTrue(cueSheetReferences(listOf("REM COMMENT", "  TRACK 01 AUDIO", "INDEX 01 00:00:00")).isEmpty())
    }

    @Test
    fun `gdi sheet quoted track names parse`() {
        val lines = listOf(
            "1 0 4 2352 \"track01.bin\" 0",
            "2 45000 150 2352 \"track02.raw\" 0",
        )

        assertEquals(setOf("track01.bin", "track02.raw"), gdiSheetTrackNames(lines))
    }

    @Test
    fun `gdi sheet unquoted track names parse`() {
        assertEquals(setOf("track01.bin"), gdiSheetTrackNames(listOf("1 0 4 2352 track01.bin 0")))
    }

    @Test
    fun `gdi sheet quoted track names containing spaces parse as one field`() {
        assertEquals(
            setOf("track one.bin"),
            gdiSheetTrackNames(listOf("1 0 4 2352 \"track one.bin\" 0")),
        )
    }

    @Test
    fun `gdi lines with too few fields are ignored`() {
        assertTrue(gdiSheetTrackNames(listOf("1 0 4 2352")).isEmpty())
        assertTrue(gdiSheetTrackNames(listOf("", "   ")).isEmpty())
    }
}
