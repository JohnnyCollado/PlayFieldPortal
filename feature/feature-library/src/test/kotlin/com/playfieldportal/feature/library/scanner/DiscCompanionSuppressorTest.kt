package com.playfieldportal.feature.library.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SAF-path companion suppressor: a .cue hides the .bin files it lists and a Dreamcast .gdi
 * hides its track files, so Memory Card SAF scans never make companion files game rows.
 * See docs/plans/multi-disc-games-plan.md step 4.
 */
class DiscCompanionSuppressorTest {

    private val suppressor = DiscCompanionSuppressor()

    private fun file(path: String) =
        ScannedDiscFile(rawPath = path, name = path.substringAfterLast('/').substringAfterLast('\\'))

    // A fake reader that serves a fixed sheet per file name.
    private fun reader(sheets: Map<String, List<String>>): DiscCompanionSuppressor.SheetReader =
        DiscCompanionSuppressor.SheetReader { f -> sheets[f.name] }

    @Test
    fun `cue referenced bins are suppressed and the cue is not`() {
        val files = listOf(
            file("/roms/psx/Final Fantasy VII (Disc 1).cue"),
            file("/roms/psx/Final Fantasy VII (Disc 1).bin"),
            file("/roms/psx/Final Fantasy VII (Disc 2).cue"),
            file("/roms/psx/Final Fantasy VII (Disc 2).bin"),
        )
        val sheets = mapOf(
            "Final Fantasy VII (Disc 1).cue" to listOf("FILE \"Final Fantasy VII (Disc 1).bin\" BINARY"),
            "Final Fantasy VII (Disc 2).cue" to listOf("FILE \"Final Fantasy VII (Disc 2).bin\" BINARY"),
        )

        val suppressed = suppressor.suppressedFiles(files, reader(sheets))

        assertEquals(
            setOf(
                "/roms/psx/Final Fantasy VII (Disc 1).bin",
                "/roms/psx/Final Fantasy VII (Disc 2).bin",
            ),
            suppressed,
        )
    }

    @Test
    fun `gdi track files are suppressed`() {
        val files = listOf(
            file("/roms/dreamcast/Crazy Taxi (Disc 1).gdi"),
            file("/roms/dreamcast/track01.bin"),
            file("/roms/dreamcast/track02.raw"),
        )
        val sheets = mapOf(
            "Crazy Taxi (Disc 1).gdi" to listOf(
                "1 0 4 2352 \"track01.bin\" 0",
                "2 45000 150 2352 track02.raw 0",
            ),
        )

        val suppressed = suppressor.suppressedFiles(files, reader(sheets))

        assertEquals(setOf("/roms/dreamcast/track01.bin", "/roms/dreamcast/track02.raw"), suppressed)
    }

    @Test
    fun `matching is case-insensitive`() {
        val files = listOf(
            file("/roms/psx/Game.cue"),
            file("/roms/psx/Game.BIN"),
        )

        val suppressed = suppressor.suppressedFiles(files, reader(mapOf("Game.cue" to listOf("FILE game.bin BINARY"))))

        assertEquals(setOf("/roms/psx/Game.BIN"), suppressed)
    }

    @Test
    fun `sheet entries with path prefixes resolve by basename`() {
        val files = listOf(
            file("/roms/dreamcast/Game.gdi"),
            file("/roms/dreamcast/track01.bin"),
        )

        val suppressed = suppressor.suppressedFiles(
            files,
            reader(mapOf("Game.gdi" to listOf("1 0 4 2352 \"discs/track01.bin\" 0"))),
        )

        assertEquals(setOf("/roms/dreamcast/track01.bin"), suppressed)
    }

    @Test
    fun `an unreadable sheet suppresses nothing`() {
        val files = listOf(
            file("/roms/psx/Game.cue"),
            file("/roms/psx/Game (Track 1).bin"),
        )

        assertTrue(suppressor.suppressedFiles(files) { null }.isEmpty())
    }

    @Test
    fun `a bin not listed in any cue is not suppressed`() {
        val files = listOf(
            file("/roms/psx/Game.cue"),
            file("/roms/psx/Game (Track 1).bin"),
            file("/roms/psx/Orphan.bin"),
        )

        val suppressed = suppressor.suppressedFiles(
            files,
            reader(mapOf("Game.cue" to listOf("FILE \"Game (Track 1).bin\" BINARY"))),
        )

        assertEquals(setOf("/roms/psx/Game (Track 1).bin"), suppressed)
    }

    @Test
    fun `windows-style paths suppress companions`() {
        // Desktop ROM folders use backslash paths — same suppression, other separator.
        val files = listOf(
            file("D:\\Roms\\psx\\Game.cue"),
            file("D:\\Roms\\psx\\Game (Track 1).bin"),
        )

        val suppressed = suppressor.suppressedFiles(
            files,
            reader(mapOf("Game.cue" to listOf("FILE \"Game (Track 1).bin\" BINARY"))),
        )

        assertEquals(setOf("D:\\Roms\\psx\\Game (Track 1).bin"), suppressed)
    }

    @Test
    fun `companions in a different folder are not suppressed`() {
        val files = listOf(
            file("/roms/psx/A/Game.cue"),
            file("/roms/psx/B/Game (Track 1).bin"),
        )

        assertTrue(suppressor.suppressedFiles(files, reader(mapOf("Game.cue" to listOf("FILE \"Game (Track 1).bin\" BINARY")))).isEmpty())
    }
}
