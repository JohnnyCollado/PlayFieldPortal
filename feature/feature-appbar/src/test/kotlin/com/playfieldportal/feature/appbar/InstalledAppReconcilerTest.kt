package com.playfieldportal.feature.appbar

import com.playfieldportal.core.data.repository.MemoryCardRepository
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.repository.GameRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The uninstalled-app write policy, mirroring LibraryReconcilerTest for the ROM side.
 *
 * Three things are proven, in increasing order of how much a regression would cost:
 *  - the install / uninstall / reinstall matrix reaches the right flag state,
 *  - the steady state writes NOTHING, which is what makes this safe to run on every catalog
 *    change rather than only at startup, and
 *  - an empty survey never flags anything. A PackageManager query that comes back short must not
 *    be able to wipe the Android Memory Card.
 *
 * Assertions check the repository calls, not just the returned counts: the counts can be right
 * while the wrong packages were written.
 */
class InstalledAppReconcilerTest {

    private lateinit var gameRepository: GameRepository
    private lateinit var memoryCardRepository: MemoryCardRepository
    private lateinit var reconciler: InstalledAppReconciler

    private fun appRow(id: Long, pkg: String, missing: Boolean = false) = Game(
        id = id,
        title = "App $id",
        platformId = "android",
        packageName = pkg,
        isMissing = missing,
    )

    private suspend fun rows(vararg games: Game) {
        coEvery { gameRepository.getByPlatform("android") } returns games.toList()
    }

    @Before
    fun setUp() {
        gameRepository = mockk(relaxed = true)
        memoryCardRepository = mockk(relaxed = true)
        reconciler = InstalledAppReconciler(
            appCategoryRepository = mockk(relaxed = true),
            gameRepository = gameRepository,
            memoryCardRepository = memoryCardRepository,
            scope = mockk(relaxed = true),
        )
    }

    @Test
    fun `an uninstalled app's row is flagged missing, not deleted`() = runTest {
        rows(appRow(1L, "com.a"), appRow(2L, "com.gone"))

        val result = reconciler.reconcile(setOf("com.a"))

        assertEquals(1, result.markedMissing)
        coVerify(exactly = 1) { gameRepository.markAppsMissing("android", listOf("com.gone")) }
        coVerify(exactly = 0) { gameRepository.delete(any()) }
    }

    @Test
    fun `a reinstalled app's row is unflagged`() = runTest {
        rows(appRow(1L, "com.back", missing = true))

        val result = reconciler.reconcile(setOf("com.back"), now = 1234L)

        assertEquals(1, result.markedSeen)
        coVerify(exactly = 1) { gameRepository.markAppsSeen("android", listOf("com.back"), 1234L) }
    }

    @Test
    fun `the steady state writes nothing`() = runTest {
        rows(appRow(1L, "com.a"), appRow(2L, "com.b"), appRow(3L, "com.gone", missing = true))

        val result = reconciler.reconcile(setOf("com.a", "com.b"))

        assertEquals(0, result.markedSeen)
        assertEquals(0, result.markedMissing)
        assertFalse(result.skipped)
        coVerify(exactly = 0) { gameRepository.markAppsSeen(any(), any(), any()) }
        coVerify(exactly = 0) { gameRepository.markAppsMissing(any(), any()) }
        coVerify(exactly = 0) { memoryCardRepository.recountGames(any()) }
    }

    @Test
    fun `an empty survey against existing rows touches nothing`() = runTest {
        rows(appRow(1L, "com.a"), appRow(2L, "com.b"))

        val result = reconciler.reconcile(emptySet())

        assertTrue(result.skipped)
        coVerify(exactly = 0) { gameRepository.markAppsMissing(any(), any()) }
        coVerify(exactly = 0) { memoryCardRepository.recountGames(any()) }
    }

    @Test
    fun `an empty survey with no rows is not a skip`() = runTest {
        rows()

        assertFalse(reconciler.reconcile(emptySet()).skipped)
    }

    @Test
    fun `harvested launcher shortcuts are left alone`() = runTest {
        // Shares its host's package name but is a separate entry with its own presence, so an
        // uninstalled host must not be inferred from — and reached through — the shortcut row.
        rows(appRow(1L, "com.host").copy(shortcutId = "shortcut-1"))

        val result = reconciler.reconcile(emptySet())

        assertEquals(0, result.markedMissing)
        coVerify(exactly = 0) { gameRepository.markAppsMissing(any(), any()) }
    }

    @Test
    fun `the memory card is recounted only when a flag moved`() = runTest {
        rows(appRow(1L, "com.gone"))

        reconciler.reconcile(setOf("com.other"))

        coVerify(exactly = 1) { memoryCardRepository.recountGames("android") }
    }
}
