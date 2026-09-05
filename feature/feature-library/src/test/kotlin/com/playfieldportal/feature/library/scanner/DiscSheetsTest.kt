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

    // ── case-preserving variants ─────────────────────────────────────────────────
    // The lowercase sets above are comparison keys: a sheet's references matched against a
    // directory listing. A caller that must OPEN the referenced file needs the name as written, or
    // it resolves nothing on a case-sensitive volume — the /roms/psx/Parasite Eve II failure.

    @Test
    fun `raw cue references preserve the case written in the sheet`() {
        assertEquals(
            listOf("Parasite Eve II (Disc 2).bin"),
            cueSheetReferencesRaw(listOf("FILE \"Parasite Eve II (Disc 2).bin\" BINARY")),
        )
    }

    @Test
    fun `raw cue references preserve case in the unquoted form too`() {
        assertEquals(listOf("Game.bin"), cueSheetReferencesRaw(listOf("FILE Game.bin BINARY")))
    }

    @Test
    fun `raw cue references keep sheet order`() {
        // DiscRegionReader opens the FIRST data track. Order is contractual here, not incidental —
        // the lowercase overload returns a Set and only preserves order by LinkedHashSet accident.
        val lines = listOf(
            "FILE \"Game (Track 1).bin\" BINARY",
            "  TRACK 01 MODE2/2352",
            "FILE \"Game (Track 2).bin\" BINARY",
            "FILE \"Game (Track 3).bin\" BINARY",
        )

        assertEquals(
            listOf("Game (Track 1).bin", "Game (Track 2).bin", "Game (Track 3).bin"),
            cueSheetReferencesRaw(lines),
        )
    }

    @Test
    fun `raw cue references collapse path components to a basename`() {
        // This stripping is the path-traversal guard on untrusted sheet contents. It must survive
        // dropping the .lowercase() — without it a sheet can name a file outside its own folder.
        assertEquals(
            listOf("track02.bin"),
            cueSheetReferencesRaw(listOf("FILE \"sub/track02.bin\" BINARY")),
        )
        assertEquals(
            listOf("passwd"),
            cueSheetReferencesRaw(listOf("FILE \"../../etc/passwd\" BINARY")),
        )
        // Windows-authored sheets use backslashes.
        assertEquals(
            listOf("evil.bin"),
            cueSheetReferencesRaw(listOf("FILE \"..\\..\\evil.bin\" BINARY")),
        )
    }

    @Test
    fun `the lowercase cue set still agrees with the raw parse`() {
        // cueSheetReferences delegates to the raw parser. The existing consumers' contract —
        // lowercase basenames, deduplicated — must survive that refactor bit-identically.
        val lines = listOf(
            "FILE \"Parasite Eve II (Disc 2).bin\" BINARY",
            "FILE \"PARASITE EVE II (DISC 2).BIN\" BINARY",
        )

        assertEquals(setOf("parasite eve ii (disc 2).bin"), cueSheetReferences(lines))
        assertEquals(2, cueSheetReferencesRaw(lines).size)
    }

    @Test
    fun `raw gdi track names preserve case and sheet order`() {
        val lines = listOf(
            "1 0 4 2352 \"Track01.bin\" 0",
            "2 45000 150 2352 \"Track02.RAW\" 0",
        )

        assertEquals(listOf("Track01.bin", "Track02.RAW"), gdiSheetTrackNamesRaw(lines))
    }

    @Test
    fun `raw gdi track names collapse path components to a basename`() {
        assertEquals(
            listOf("track01.bin"),
            gdiSheetTrackNamesRaw(listOf("1 0 4 2352 \"../track01.bin\" 0")),
        )
    }

    @Test
    fun `the lowercase gdi set still agrees with the raw parse`() {
        val lines = listOf("1 0 4 2352 \"Track01.bin\" 0", "2 45000 150 2352 \"TRACK01.BIN\" 0")

        assertEquals(setOf("track01.bin"), gdiSheetTrackNames(lines))
        assertEquals(2, gdiSheetTrackNamesRaw(lines).size)
    }
}
