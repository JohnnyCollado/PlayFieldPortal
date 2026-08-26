package com.playfieldportal.feature.library.scanner

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
 * The shared existing-path seam: the platform's library rows fold into one path set, and a read
 * failure throws instead of silently degrading to an empty set (which could duplicate known games).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExistingRomPathResolverTest {

    private lateinit var gameRepository: GameRepository
    private lateinit var resolver: ExistingRomPathResolver

    @Before
    fun setUp() {
        gameRepository = mockk(relaxed = true)
        resolver = ExistingRomPathResolver(gameRepository)
    }

    @Test
    fun `baseline returns the platform's ROM paths`() = runTest {
        coEvery { gameRepository.getByPlatform("psx") } returns listOf(
            Game(title = "Crash", platformId = "psx", romPath = "/roms/psx/crash.bin", isMissing = true),
            Game(title = "App entry", platformId = "psx", romPath = null),
        )

        val baseline = resolver.baselineFor("psx")

        assertEquals(2, baseline.games.size)
        assertTrue("/roms/psx/crash.bin" in baseline.romPaths)
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

    @Test(expected = CancellationException::class)
    fun `CancellationException is rethrown, not wrapped`() = runTest {
        coEvery { gameRepository.getByPlatform("psx") } throws CancellationException("cancelled")

        resolver.baselineFor("psx")
    }
}
