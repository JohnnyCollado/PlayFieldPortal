package com.playfieldportal.feature.achievements.provider.localsteam

import android.content.Context
import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.core.data.repository.WindowsLibrarySetup
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The opt-in gate: with Local Steam tracking disabled, discovery must short-circuit before any
 * SAF walk, so the whole subsystem (sync, generation, DLL swap) stays off. Verified without
 * touching SAF because the gate returns ahead of the scan surfaces.
 */
class LocalSteamDiscoveryGateTest {

    private val context = mockk<Context>(relaxed = true)
    private val windowsLibrary = mockk<WindowsLibrarySetup>(relaxed = true)
    private val credentials = mockk<AchievementCredentialsProvider>()
    private val discovery = LocalSteamDiscovery(context, windowsLibrary, credentials)

    @Test
    fun `disabled tracking returns nothing and never walks the library`() = runTest {
        coEvery { credentials.localSteamTrackingEnabled() } returns false

        assertTrue(discovery.scanAll().isEmpty())
        assertTrue(discovery.scan().isEmpty())
        assertNull(discovery.findByAppId("1173820"))

        coVerify(exactly = 0) { windowsLibrary.windowsFolders() }
    }
}
