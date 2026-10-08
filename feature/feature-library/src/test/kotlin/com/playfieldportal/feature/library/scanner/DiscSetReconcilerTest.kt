package com.playfieldportal.feature.library.scanner

import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.repository.GameRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reconcile diffs against what is STORED, and the scan that calls it has already changed what
 * is stored: saving a new primary disc clears the primary flag on the rest of its set.
 *
 * The rows the scan read before it started cannot know that. Diffing against them left the old
 * primary looking correct — so it was never rewritten — while the new disc was demoted, and a set
 * with no primary is a game that appears nowhere.
 */
class DiscSetReconcilerTest {

    private val builder = DiscSetBuilder()
    private val gameRepository = mockk<GameRepository>(relaxed = true)
    // The readers are context-backed; here none of them has anything to say, so the disc tags in
    // the file names are the only evidence — and a relaxed mock's invented answers are kept out.
    private val m3uReader = mockk<M3uPlaylistReader> { every { read(any()) } returns null }
    private val regionReader = mockk<DiscRegionReader> { every { read(any()) } returns null }
    private val sheetReader = mockk<DiscSheetReader> { every { read(any()) } returns null }
    private val reconciler = DiscSetReconciler(builder, m3uReader, regionReader, sheetReader, gameRepository)

    private fun disc(number: Int, id: Long = 0) = Game(
        id = id,
        title = "Parasite Eve II (Disc $number)",
        platformId = "psx",
        romPath = "/roms/psx/Parasite Eve II (Disc $number).chd",
    )

    /** One disc as a scan enriches it when it is the only row in its batch: the primary. */
    private fun scannedAlone(number: Int, id: Long = 0): Game =
        builder.assign(listOf(disc(number, id)), m3uReader = { null }).single()

    @Test
    fun `a disc scanned on its own is its set's primary, which is what makes the join dangerous`() {
        val first = scannedAlone(1)
        val second = scannedAlone(2)

        assertTrue(first.isDiscPrimary)
        assertTrue(second.isDiscPrimary)
        assertEquals(first.discSetKey, second.discSetKey)
    }

    @Test
    fun `a disc joining a stored set leaves the set with its primary`() = runTest {
        // Disc 1 was scanned alone last week: stored as the primary.
        val storedBefore = scannedAlone(1, id = 1L)
        // This scan found Disc 2. Saving it — a primary, as far as its own batch knew — cleared
        // the flag on Disc 1. That is the state of the table when the reconcile runs.
        val newDisc = scannedAlone(2)
        coEvery { gameRepository.getByPlatform("psx") } returns listOf(
            storedBefore.copy(isDiscPrimary = false),
            newDisc.copy(id = 2L),
        )

        reconciler.reconcilePlatform("psx", existingRows = listOf(storedBefore), newRows = listOf(newDisc))

        // Disc 1 is put back as the primary…
        coVerify(exactly = 1) {
            gameRepository.upsert(match { it.romPath == storedBefore.romPath && it.isDiscPrimary })
        }
        // …and Disc 2 is demoted, so the set has exactly one.
        coVerify(exactly = 1) {
            gameRepository.upsert(match { it.romPath == newDisc.romPath && !it.isDiscPrimary })
        }
    }

    @Test
    fun `a set the scan did not disturb is left alone`() = runTest {
        val both = builder.assign(listOf(disc(1, id = 1L), disc(2, id = 2L)), m3uReader = { null })
        coEvery { gameRepository.getByPlatform("psx") } returns both

        val corrected = reconciler.reconcilePlatform("psx", existingRows = both, newRows = emptyList())

        assertEquals(0, corrected)
        coVerify(exactly = 0) { gameRepository.upsert(any()) }
    }

    @Test
    fun `when the table cannot be re-read the reconcile still runs on the rows it was given`() = runTest {
        // Non-fatal, like a failed upsert here: the scan itself completed.
        val newDisc = scannedAlone(2)
        coEvery { gameRepository.getByPlatform("psx") } throws IllegalStateException("db closed")

        reconciler.reconcilePlatform("psx", existingRows = listOf(scannedAlone(1, id = 1L)), newRows = listOf(newDisc))

        coVerify(exactly = 1) {
            gameRepository.upsert(match { it.romPath == newDisc.romPath && !it.isDiscPrimary })
        }
        assertFalse(newDisc.discSetKey.isNullOrBlank())
    }

    // ── Stale companion rows ──────────────────────────────────────────────────────────────────
    // A library scanned before companion suppression still holds a row for each .bin a .cue lists.
    // The scan never reports those paths, so left alone the row is flagged missing and shows in
    // Missing under the game's title. It is merged into its sheet instead: the sheet keeps the
    // row's favorite, play time and links, and the companion row goes.

    @Test
    fun `a stored bin companion is merged into the cue that lists it and never rewritten`() = runTest {
        val cuePath = "/roms/psx/Parasite Eve II (Disc 2)/Parasite Eve II (Disc 2).cue"
        val bin = Game(
            id = 7L, title = "Parasite Eve II", platformId = "psx",
            romPath = "/roms/psx/Parasite Eve II (Disc 2)/Parasite Eve II (Disc 2).bin", isFavorite = true,
        )
        // Stored already correct as its set's disc, so the merge is the only change to count.
        val cue = builder.assign(
            listOf(Game(id = 8L, title = "Parasite Eve II", platformId = "psx", romPath = cuePath)),
            m3uReader = { null },
        ).single()
        val reader = mockk<DiscSheetReader> {
            every { read(any()) } answers {
                if (firstArg<Game>().romPath == cuePath) listOf("FILE \"Parasite Eve II (Disc 2).bin\" BINARY") else null
            }
        }
        val reconciler = DiscSetReconciler(builder, m3uReader, regionReader, reader, gameRepository)
        coEvery { gameRepository.getByPlatform("psx") } returns listOf(bin, cue)
        // After the merge the stored cue carries the bin's favorite.
        coEvery { gameRepository.getById(8L) } returns cue.copy(isFavorite = true)

        val corrected = reconciler.reconcilePlatform("psx", existingRows = listOf(bin, cue), newRows = emptyList())

        coVerify(exactly = 1) { gameRepository.mergeInto(survivorId = 8L, loserId = 7L) }
        coVerify(exactly = 0) { gameRepository.upsert(match { it.id == 7L }) }
        // Any rewrite of the cue must carry the merged favorite, never the stale copy.
        coVerify(exactly = 0) { gameRepository.upsert(match { it.id == 8L && !it.isFavorite }) }
        assertEquals(1, corrected)
    }

    @Test
    fun `a sheet-less bin is a game of its own and is never merged`() = runTest {
        val bin = Game(id = 3L, title = "Sonic", platformId = "psx", romPath = "/roms/psx/Sonic.bin")
        coEvery { gameRepository.getByPlatform("psx") } returns listOf(bin)

        reconciler.reconcilePlatform("psx", existingRows = listOf(bin), newRows = emptyList())

        coVerify(exactly = 0) { gameRepository.mergeInto(any(), any()) }
    }
}
