package com.playfieldportal.feature.launcher

import android.content.ComponentName
import android.content.Intent
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Winlator-family `.desktop` exports: every fork drops them in the same shared folder, so the file
 * itself must say which app made it, and each fork has its own way in.
 */
@RunWith(RobolectricTestRunner::class)
class WinlatorDesktopTest {

    private val installed = listOf("com.winlator.cmod", "com.winlator.banner")

    // Trimmed from the Bannerlator export on the Odin3 (2026-10-11).
    private val bannerlatorExport = """
        [Desktop Entry]
        Name=BootstrapPackagedGame
        Exec=wine E:\Games\Lollipop Chainsaw RePOP\lollipop\Binaries\Win64\lollipop-Win64-Shipping.exe

        [Extra Data]
        steamAppId=2658450
        customCoverArtPath=/data/user/0/com.winlator.banner/files/imagefs/home/xuser-2/app_data/cover_arts/LOLLIPOP CHAINSAW RePOP.png
        container_id:2
    """.trimIndent()

    @Test
    fun `a Bannerlator export names Bannerlator as its owner`() {
        assertEquals("com.winlator.banner", WinlatorDesktop.ownerPackage(bannerlatorExport, installed))
    }

    @Test
    fun `the legacy data-data path form names its owner too`() {
        val text = "Icon=/data/data/com.winlator.cmod/files/icons/game.png"
        assertEquals("com.winlator.cmod", WinlatorDesktop.ownerPackage(text, installed))
    }

    @Test
    fun `a file naming no installed launcher has no owner`() {
        assertNull(WinlatorDesktop.ownerPackage("Exec=wine game.exe", installed))
        assertNull(WinlatorDesktop.ownerPackage(bannerlatorExport, listOf("com.winlator.cmod")))
    }

    @Test
    fun `a star-lineage fork opens the file in its game screen`() {
        val intent = WinlatorDesktop.launchIntent(
            packageName = "com.winlator.banner", shortcutPath = "/storage/emulated/0/Winlator/Shortcuts/Game.desktop",
            hasStarDisplay = true, packageLaunch = null,
        )!!
        assertEquals(ComponentName("com.winlator.banner", WinlatorDesktop.STAR_DISPLAY_ACTIVITY), intent.component)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("/storage/emulated/0/Winlator/Shortcuts/Game.desktop", intent.getStringExtra("shortcut_path"))
    }

    @Test
    fun `other forks take the file through their main screen`() {
        val main = Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.winlator.cmod", "com.winlator.MainActivity"))
        val intent = WinlatorDesktop.launchIntent(
            packageName = "com.winlator.cmod", shortcutPath = "/x/Game.desktop",
            hasStarDisplay = false, packageLaunch = main,
        )!!
        assertEquals(ComponentName("com.winlator.cmod", "com.winlator.MainActivity"), intent.component)
        assertEquals("/x/Game.desktop", intent.getStringExtra("shortcut_path"))
    }

    @Test
    fun `a fork with no way in launches nothing`() {
        assertNull(WinlatorDesktop.launchIntent("com.winlator", "/x/Game.desktop", hasStarDisplay = false, packageLaunch = null))
    }

    @Test
    fun `the shortcut path is read back from a stored intent`() {
        val stored = WinlatorDesktop.launchIntent(
            "com.winlator.banner", "/storage/emulated/0/Winlator/Shortcuts/Game.desktop",
            hasStarDisplay = true, packageLaunch = null,
        )!!.toUri(Intent.URI_INTENT_SCHEME)
        assertEquals("/storage/emulated/0/Winlator/Shortcuts/Game.desktop", WinlatorDesktop.shortcutPathOf(stored))
        assertNull(WinlatorDesktop.shortcutPathOf("intent:#Intent;action=banner.hub.LAUNCH_GAME;S.localGameId=local_1;end"))
        assertNull(WinlatorDesktop.shortcutPathOf(null))
    }

    @Test
    fun `only a shared-storage file can be opened by another fork`() {
        assertEquals(true, WinlatorDesktop.isShared("/storage/emulated/0/Winlator/Shortcuts/Game.desktop"))
        assertEquals(false, WinlatorDesktop.isShared("/data/user/0/com.winlator.banner/files/imagefs/Game.desktop"))
    }

    // Survey L12 (docs/audits/codebase-survey-2026-10-22.md): an app's own external folder
    // (Android/data, Android/obb) is private to that app on Android 11+, so another Winlator fork
    // cannot read a shortcut stored there and must not be offered for it.
    @Test
    fun `an app-specific external folder is not shared storage`() {
        assertEquals(false, WinlatorDesktop.isShared("/storage/emulated/0/Android/data/com.winlator.banner/files/Game.desktop"))
        assertEquals(false, WinlatorDesktop.isShared("/storage/1A2B-3C4D/Android/obb/com.winlator/Game.desktop"))
        assertEquals(false, WinlatorDesktop.isShared("/storage/emulated/0/android/DATA/com.winlator/Game.desktop"))
        assertEquals(true, WinlatorDesktop.isShared("/storage/emulated/0/Games/Android data notes/Game.desktop"))
    }

    @Test
    fun `the export's Steam appid is read from its extra data`() {
        assertEquals("2658450", WinlatorDesktop.steamAppId(bannerlatorExport))
    }

    @Test
    fun `a missing or implausible Steam appid reads as none`() {
        assertNull(WinlatorDesktop.steamAppId("[Extra Data]\nstoreSource=custom"))
        assertNull(WinlatorDesktop.steamAppId("steamAppId="))
        assertNull(WinlatorDesktop.steamAppId("steamAppId=0"))
        assertNull(WinlatorDesktop.steamAppId("steamAppId=12ab"))
        assertNull(WinlatorDesktop.steamAppId("steamAppId=1234567890123"))
    }
}
