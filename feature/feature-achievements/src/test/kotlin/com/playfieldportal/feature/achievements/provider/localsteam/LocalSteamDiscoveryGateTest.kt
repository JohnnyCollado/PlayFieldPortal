package com.playfieldportal.feature.achievements.provider.localsteam

import android.content.Context
import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.core.data.database.dao.LocalSteamFolderDao
import com.playfieldportal.core.data.database.entity.LocalSteamFolderEntity
import com.playfieldportal.core.data.repository.WindowsLibrarySetup
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The opt-in gate: discovery walks the library only when at least one opt-in is on — Local Steam
 * tracking (detect + sync) or the Goldberg installer (convert on scan). With both off it must
 * short-circuit before any SAF walk, so the whole subsystem (sync, generation, DLL swap) stays off.
 */
class LocalSteamDiscoveryGateTest {

    private val context = mockk<Context>(relaxed = true)
    private val windowsLibrary = mockk<WindowsLibrarySetup>(relaxed = true)
    private val credentials = mockk<AchievementCredentialsProvider>()
    private val registry = mockk<LocalSteamFolderDao>(relaxed = true)
    private val discovery = LocalSteamDiscovery(context, windowsLibrary, credentials, registry)

    @Test
    fun `both opt-ins disabled returns nothing and never walks the library`() = runTest {
        coEvery { credentials.localSteamTrackingEnabled() } returns false
        coEvery { credentials.goldbergInstallerEnabled() } returns false

        assertTrue(discovery.scanAll().isEmpty())
        assertTrue(discovery.scan().isEmpty())
        assertNull(discovery.findByAppId("1173820"))

        coVerify(exactly = 0) { windowsLibrary.windowsFolders() }
    }

    @Test
    fun `a REGISTERED folder still resolves with both opt-ins off`() = runTest {
        // The dead end this exists to prevent: the folder-pick flow is not gated, so a game would
        // link and then every sync failed with "no emu game folder for appid ..." — no coins, no
        // explanation — purely because a global toggle the user had never seen was off.
        //
        // Pointing PFP at a folder IS that game's consent. Resolving it searches nothing and writes
        // nothing, so the gate does not apply to it; it still applies to the library-wide scan above,
        // and the marker and kit writes keep their own opt-ins.
        coEvery { credentials.localSteamTrackingEnabled() } returns false
        coEvery { credentials.goldbergInstallerEnabled() } returns false
        coEvery { registry.getByAppId("1173820") } returns LocalSteamFolderEntity(
            appId = "1173820",
            folderName = "FINAL FANTASY VI",
            treeUri = "content://com.android.externalstorage.documents/tree/primary%3AGames",
            folderDocId = "primary:Games/FINAL FANTASY VI",
            settingsParentDocId = "primary:Games/FINAL FANTASY VI",
            appIdSource = "MARKER",
            lastSeenAt = 1L,
        )

        discovery.findByAppId("1173820")

        // The registry is consulted even with both opt-ins off, and the library is still never walked.
        coVerify { registry.getByAppId("1173820") }
        coVerify(exactly = 0) { windowsLibrary.windowsFolders() }
    }

    @Test
    fun `installer alone opens discovery even when tracking is off`() = runTest {
        coEvery { credentials.localSteamTrackingEnabled() } returns false
        coEvery { credentials.goldbergInstallerEnabled() } returns true

        // No SAF fixtures here — the relaxed windowsLibrary yields no folders, so the scan returns
        // empty — but the gate must still let it reach the library walk rather than short-circuit.
        discovery.scanAll()

        coVerify(atLeast = 1) { windowsLibrary.windowsFolders() }
    }
}
