package com.playfieldportal.core.data.repository

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Any linked emulator data folder counts as a connected achievement source — the Shiba Coins hub
 * swaps its "Connect accounts" prompt for the player card as soon as one is set.
 */
class LocalAchievementFoldersTest {

    private fun folders(
        vita: String? = null,
        ps3: String? = null,
        x360Mobile: String? = null,
        xenDroid: String? = null,
    ) = LocalAchievementFolders(
        mockk<Vita3KLibrary> { every { ux0TreeUriFlow } returns flowOf(vita) },
        mockk<Ps3DataLibrary> { every { dataTreeUriFlow } returns flowOf(ps3) },
        mockk<Xbox360DataLibrary> {
            every { treeUriFlow(Xbox360Emulator.X360_MOBILE) } returns flowOf(x360Mobile)
            every { treeUriFlow(Xbox360Emulator.XENDROID) } returns flowOf(xenDroid)
        },
    )

    @Test
    fun `no folders linked is not connected`() = runTest {
        assertEquals(false, folders().anyLinked.first())
    }

    @Test
    fun `each emulator's folder alone counts`() = runTest {
        assertEquals(true, folders(vita = "content://v").anyLinked.first())
        assertEquals(true, folders(ps3 = "content://p").anyLinked.first())
        assertEquals(true, folders(x360Mobile = "content://m").anyLinked.first())
        assertEquals(true, folders(xenDroid = "content://x").anyLinked.first())
    }

    @Test
    fun `a blank stored value is not a folder`() = runTest {
        assertEquals(false, folders(vita = "", ps3 = " ").anyLinked.first())
    }
}
