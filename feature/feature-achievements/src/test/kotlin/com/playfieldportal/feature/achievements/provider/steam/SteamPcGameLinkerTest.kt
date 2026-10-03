package com.playfieldportal.feature.achievements.provider.steam

import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.feature.achievements.AchievementController
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * A GameNative import carries a Steam appid, and used to be linked to Steam on the strength of it
 * alone — including games the user runs from a local copy and does not own, whose Steam link then
 * never synced. The import now links Steam only for an owned game.
 */
class SteamPcGameLinkerTest {

    private val achievements = mockk<AchievementController>(relaxed = true)
    private val gate = mockk<WindowsSteamGate>()
    private val linker = SteamPcGameLinker(achievements, gate)

    @Test
    fun `an owned game links Steam`() = runTest {
        coEvery { gate.verdict("220") } returns WindowsSteamGate.Verdict.OWNED

        linker.linkSteam(1L, "220")

        coVerify { achievements.linkManually(1L, AchievementProvider.STEAM, "220") }
    }

    @Test
    fun `a game not in the Steam library is not linked`() = runTest {
        coEvery { gate.verdict("1984270") } returns WindowsSteamGate.Verdict.LOCAL

        linker.linkSteam(1L, "1984270")

        coVerify(exactly = 0) { achievements.linkManually(any(), any(), any()) }
    }

    @Test
    fun `a game whose ownership is unknown is not linked`() = runTest {
        coEvery { gate.verdict("220") } returns WindowsSteamGate.Verdict.UNKNOWN

        linker.linkSteam(1L, "220")

        coVerify(exactly = 0) { achievements.linkManually(any(), any(), any()) }
    }
}
