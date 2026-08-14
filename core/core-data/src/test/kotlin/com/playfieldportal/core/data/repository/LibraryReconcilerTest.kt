package com.playfieldportal.core.data.repository

import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.repository.GameRepository
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 7 verification for the missing-ROM write policy (docs/missing-roms-plan.md).
 *
 * Two things are being proven here, and the second matters more than the first:
 *  - the add / remove / re-add matrix reaches the right flag state, and
 *  - an untrustworthy survey NEVER flags anything. That is the "pull the card and nothing
 *    vanishes" guarantee, and it is the case where a regression silently mass-flags a library.
 *
 * Every assertion checks the DAO calls rather than just the returned counts, since the counts
 * could be right while the wrong paths were written.
 */
class LibraryReconcilerTest {

    private lateinit var gameRepository: GameRepository
    private lateinit var reconciler: LibraryReconciler

    private fun game(id: Long, path: String?) =
        Game(id = id, title = "Game $id", platformId = "psx", romPath = path)

    private val crash = game(1L, "/roms/psx/crash.bin")
    private val spyro = game(2L, "/roms/psx/spyro.bin")

    @Before
    fun setUp() {
        gameRepository = mockk(relaxed = true)
        reconciler = LibraryReconciler(gameRepository)
    }

    // ── The add / remove / re-add matrix ────────────────────────────────────

    @Test
    fun `a game whose file is present is marked seen`() = runTest {
        val result = reconciler.reconcile(
            dbGames = listOf(crash),
            present = setOf("/roms/psx/crash.bin"),
            scanErrored = false,
            now = 1_000L,
        )

        assertEquals(1, result.markedSeen)
        assertEquals(0, result.markedMissing)
        assertFalse(result.skipped)
        coVerify(exactly = 1) { gameRepository.markSeen(listOf("/roms/psx/crash.bin"), 1_000L) }
        coVerify(exactly = 0) { gameRepository.markMissing(any()) }
    }

    @Test
    fun `a game whose file is gone is marked missing, not deleted`() = runTest {
        val result = reconciler.reconcile(
            dbGames = listOf(crash, spyro),
            // Only Spyro survived the survey.
            present = setOf("/roms/psx/spyro.bin"),
            scanErrored = false,
        )

        assertEquals(1, result.markedSeen)
        assertEquals(1, result.markedMissing)
        coVerify(exactly = 1) { gameRepository.markSeen(listOf("/roms/psx/spyro.bin"), any()) }
        coVerify(exactly = 1) { gameRepository.markMissing(listOf("/roms/psx/crash.bin")) }
        // The core promise of the whole design: reconciliation never deletes.
        coVerify(exactly = 0) { gameRepository.delete(any()) }
    }

    @Test
    fun `re-adding the file marks it seen again, clearing the missing flag`() = runTest {
        // The row is still there and still flagged from the previous scan.
        val missingCrash = crash.copy(isMissing = true)

        val result = reconciler.reconcile(
            dbGames = listOf(missingCrash),
            present = setOf("/roms/psx/crash.bin"),
            scanErrored = false,
            now = 2_000L,
        )

        assertEquals(1, result.markedSeen)
        assertEquals(0, result.markedMissing)
        // markSeen is what clears is_missing, so this is the reactivation path.
        coVerify(exactly = 1) { gameRepository.markSeen(listOf("/roms/psx/crash.bin"), 2_000L) }
    }

    @Test
    fun `a newly scanned file that is not yet in the library is left alone`() = runTest {
        // Adding new games is the scanner's job; the reconciler only reconciles existing rows.
        val result = reconciler.reconcile(
            dbGames = listOf(crash),
            present = setOf("/roms/psx/crash.bin", "/roms/psx/tekken.bin"),
            scanErrored = false,
        )

        assertEquals(1, result.markedSeen)
        assertEquals(0, result.markedMissing)
        coVerify(exactly = 1) { gameRepository.markSeen(listOf("/roms/psx/crash.bin"), any()) }
    }

    @Test
    fun `games without a rom path are ignored entirely`() = runTest {
        // Package-backed entries (Android apps, PC shortcuts) have no file to survey, so they must
        // never be flagged missing just because a ROM scan didn't see them.
        val androidApp = game(3L, null)

        val result = reconciler.reconcile(
            dbGames = listOf(androidApp),
            present = setOf("/roms/psx/crash.bin"),
            scanErrored = false,
        )

        assertEquals(0, result.markedSeen)
        assertEquals(0, result.markedMissing)
        coVerify(exactly = 0) { gameRepository.markMissing(any()) }
    }

    // ── Pull the card, nothing vanishes ─────────────────────────────────────

    @Test
    fun `a null present-set touches nothing`() = runTest {
        // null means a source could not survey at all — unmounted card, lost permission.
        val result = reconciler.reconcile(
            dbGames = listOf(crash, spyro),
            present = null,
            scanErrored = false,
        )

        assertTrue(result.skipped)
        assertEquals(0, result.markedMissing)
        coVerify(exactly = 0) { gameRepository.markMissing(any()) }
        coVerify(exactly = 0) { gameRepository.markSeen(any(), any()) }
    }

    @Test
    fun `an errored scan touches nothing even with a plausible present-set`() = runTest {
        // The set looks survivable, but the error means it cannot be trusted as complete.
        val result = reconciler.reconcile(
            dbGames = listOf(crash, spyro),
            present = setOf("/roms/psx/crash.bin"),
            scanErrored = true,
        )

        assertTrue(result.skipped)
        coVerify(exactly = 0) { gameRepository.markMissing(any()) }
        coVerify(exactly = 0) { gameRepository.markSeen(any(), any()) }
    }

    @Test
    fun `an empty survey against a non-empty library touches nothing`() = runTest {
        // The half-mounted card case: the folder walk succeeded but saw zero files. Treating that
        // as truth would flag the entire console missing in one pass.
        val result = reconciler.reconcile(
            dbGames = listOf(crash, spyro),
            present = emptySet(),
            scanErrored = false,
        )

        assertTrue(result.skipped)
        assertEquals(0, result.markedMissing)
        coVerify(exactly = 0) { gameRepository.markMissing(any()) }
    }

    @Test
    fun `an empty survey against an empty library is not an error`() = runTest {
        // Nothing in the DB and nothing on disk is a consistent, trustworthy state — the guard
        // above must not swallow this case as if it were a mount failure.
        val result = reconciler.reconcile(
            dbGames = emptyList(),
            present = emptySet(),
            scanErrored = false,
        )

        assertFalse(result.skipped)
        assertEquals(0, result.markedSeen)
        assertEquals(0, result.markedMissing)
    }

    @Test
    fun `a library of only package-backed games survives an empty survey`() = runTest {
        // No rom paths means romPaths is empty, so the half-mounted guard does not trip; the run is
        // trustworthy and simply has nothing to do.
        val result = reconciler.reconcile(
            dbGames = listOf(game(3L, null), game(4L, null)),
            present = emptySet(),
            scanErrored = false,
        )

        assertFalse(result.skipped)
        assertEquals(0, result.markedMissing)
        coVerify(exactly = 0) { gameRepository.markMissing(any()) }
    }

    @Test
    fun `every game going missing at once is applied when the survey is trustworthy`() = runTest {
        // The counterpart to the half-mounted guard: a non-empty survey that genuinely contains
        // none of the library's paths IS trustworthy (the user really did replace the folder), so
        // the flags must be applied rather than skipped.
        val result = reconciler.reconcile(
            dbGames = listOf(crash, spyro),
            present = setOf("/roms/psx/tekken.bin"),
            scanErrored = false,
        )

        assertFalse(result.skipped)
        assertEquals(0, result.markedSeen)
        assertEquals(2, result.markedMissing)
        coVerify(exactly = 1) {
            gameRepository.markMissing(listOf("/roms/psx/crash.bin", "/roms/psx/spyro.bin"))
        }
    }
}
