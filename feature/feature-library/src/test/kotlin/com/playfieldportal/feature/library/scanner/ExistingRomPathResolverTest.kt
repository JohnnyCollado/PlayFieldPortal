package com.playfieldportal.feature.library.scanner

import com.playfieldportal.core.data.database.dao.ScanTombstoneDao
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.repository.GameRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The shared existing-path seam: DB rows + tombstones fold into one set, and a read failure throws
 * instead of silently degrading to an empty set (which could re-add tombstoned/known games).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExistingRomPathResolverTest {

    private lateinit var gameRepository: GameRepository
    private lateinit var tombstoneDao: ScanTombstoneDao
    private lateinit var resolver: ExistingRomPathResolver

    @Before
    fun setUp() {
        gameRepository = mockk(relaxed = true)
        tombstoneDao = mockk(relaxed = true)
        resolver = ExistingRomPathResolver(gameRepository, tombstoneDao)
    }

    @Test
    fun `baseline unions game paths and tombstone paths`() = runTest {
        coEvery { gameRepository.getByPlatform("psx") } returns listOf(
            Game(title = "Crash", platformId = "psx", romPath = "/roms/psx/crash.bin", isMissing = true),
            Game(title = "App entry", platformId = "psx", romPath = null),
        )
        coEvery { tombstoneDao.getPathsForPlatform("psx") } returns listOf("/roms/psx/removed.bin")

        val baseline = resolver.baselineFor("psx")

        assertEquals(2, baseline.games.size)
        assertTrue("/roms/psx/crash.bin" in baseline.romPaths)
        assertTrue("/roms/psx/removed.bin" in baseline.romPaths)
    }

    @Test
    fun `a DB read failure throws`() = runTest {
        coEvery { gameRepository.getByPlatform("psx") } throws RuntimeException("db closed")

        try {
            resolver.baselineFor("psx")
            fail("expected an exception")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("db closed") == true)
        }
    }

    @Test
    fun `a tombstone read failure throws`() = runTest {
        coEvery { gameRepository.getByPlatform("psx") } returns emptyList()
        coEvery { tombstoneDao.getPathsForPlatform("psx") } throws RuntimeException("db closed")

        try {
            resolver.baselineFor("psx")
            fail("expected an exception")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("db closed") == true)
        }
    }

    @Test(expected = CancellationException::class)
    fun `CancellationException is rethrown, not wrapped`() = runTest {
        coEvery { gameRepository.getByPlatform("psx") } throws CancellationException("cancelled")

        resolver.baselineFor("psx")
    }
}
