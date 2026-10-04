package com.playfieldportal.feature.achievements.provider.x360

import com.playfieldportal.core.domain.model.Game
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * How an Xbox 360 library game finds its title ID: from its own file first (deterministic, works
 * before the game was ever played), then — for an image that can't be read — by matching its name
 * against the titles the emulators' profiles have played.
 */
class X360TitleMatcherTest {

    private val reader = mockk<X360GameTitleIdReader>()
    private val discovery = mockk<X360AchievementDiscovery>()
    private val matcher = X360TitleMatcher(reader, discovery)

    private val game = Game(id = 4, title = "Dead or Alive 4 (USA)", platformId = "x360", romPath = "/roms/x360/doa4.iso")

    @Test
    fun `the title id read from the game file wins without touching the profiles`() = runTest {
        coEvery { reader.titleIdFor(game) } returns "544307D1"

        assertEquals(X360TitleMatcher.Result.Matched("544307D1"), matcher.match(game))
        coVerify(exactly = 0) { discovery.playedTitles() }
    }

    @Test
    fun `an unreadable file falls back to a played title with the same name`() = runTest {
        coEvery { reader.titleIdFor(game) } returns null
        coEvery { discovery.playedTitles() } returns listOf(
            X360AchievementDiscovery.PlayedTitle("41560855", "Call of Duty Black Ops"),
            X360AchievementDiscovery.PlayedTitle("544307D1", "DEAD OR ALIVE 4"),
        )

        assertEquals(X360TitleMatcher.Result.Matched("544307D1"), matcher.match(game))
    }

    @Test
    fun `a name match must be exact after normalizing - no guessing`() = runTest {
        coEvery { reader.titleIdFor(game) } returns null
        coEvery { discovery.playedTitles() } returns listOf(
            X360AchievementDiscovery.PlayedTitle("544307D2", "DEAD OR ALIVE 4 Ultimate"),
        )

        val result = assertIs<X360TitleMatcher.Result.Unmatched>(matcher.match(game))
        assertTrue("name" in result.reason, result.reason)
    }

    @Test
    fun `no title id and no profile data says to set a data folder`() = runTest {
        coEvery { reader.titleIdFor(game) } returns null
        coEvery { discovery.playedTitles() } returns emptyList()

        val result = assertIs<X360TitleMatcher.Result.Unmatched>(matcher.match(game))
        assertTrue("Data Folder" in result.reason, result.reason)
    }
}
