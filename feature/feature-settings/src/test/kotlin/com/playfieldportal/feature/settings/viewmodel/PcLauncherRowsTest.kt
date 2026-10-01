package com.playfieldportal.feature.settings.viewmodel

import com.playfieldportal.feature.launcher.InstalledPcLauncher
import com.playfieldportal.feature.launcher.LauncherChoice
import com.playfieldportal.feature.launcher.UnknownLauncher
import com.playfieldportal.feature.launcher.PcLauncherType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Import PC Games ▸ PC Launchers names each installed launcher by its real Android label. */
class PcLauncherRowsTest {

    @Test fun `installed launchers are named by their Android label`() {
        val rows = pcLauncherRows(
            listOf(
                InstalledPcLauncher("com.xiaoji.egggame", PcLauncherType.GAMEHUB, "GameHub"),
                InstalledPcLauncher("com.winlator.vanilla", PcLauncherType.WINLATOR, "Winlator Ludashi"),
                InstalledPcLauncher("com.winlator.cmod", PcLauncherType.WINLATOR, "Winlator Cmod"),
            ),
        )

        val installed = rows.filter { it.installed }
        assertEquals(listOf("GameHub", "Winlator Ludashi", "Winlator Cmod"), installed.map { it.name })
        assertEquals("com.winlator.vanilla", installed[1].packageName)
    }

    @Test fun `brands with nothing installed still list as not installed under their catalog name`() {
        val rows = pcLauncherRows(
            listOf(InstalledPcLauncher("com.xiaoji.egggame", PcLauncherType.GAMEHUB, "GameHub")),
        )

        val missing = rows.filterNot { it.installed }.map { it.name }
        assertEquals(listOf("BannerHub", "GameHub Lite", "Winlator", "GameNative"), missing)
        assertTrue(rows.none { !it.installed && it.name == "GameHub" })
    }

    @Test fun `ambiguous installs wait in Unknown Windows Emulators, not PC Launchers`() {
        val rows = pcLauncherRows(
            listOf(InstalledPcLauncher("com.ludashi.aibench", PcLauncherType.GAMEHUB, "AI Bench", ambiguous = true)),
        )

        assertTrue(rows.none { it.name == "AI Bench" })
    }

    @Test fun `undecided unknown apps show, Not a launcher ones hide behind Show Hidden Apps`() {
        val undecided = UnknownLauncher("com.example.wine", "Wine", "s1", "not in PFP's list", choice = null)
        val hidden = UnknownLauncher("com.example.box", "Box64", "s2", "not in PFP's list", LauncherChoice.NOT_A_LAUNCHER)
        val chosen = UnknownLauncher("com.example.fork", "Fork", "s3", "not in PFP's list", LauncherChoice.WINLATOR)

        val group = unknownLauncherGroup(listOf(undecided, hidden, chosen))

        assertEquals(listOf("com.example.wine"), group.visible.map { it.packageName })
        assertEquals(listOf("com.example.box"), group.hidden.map { it.packageName })
    }

    @Test fun `an unknown row reads its choice, or Choose when undecided`() {
        assertEquals("Choose…", unknownLauncherRow(UnknownLauncher("p", "P", null, "r", null)).value)
        assertEquals(
            "Not a launcher",
            unknownLauncherRow(UnknownLauncher("p", "P", null, "r", LauncherChoice.NOT_A_LAUNCHER)).value,
        )
        assertEquals("p · r", unknownLauncherRow(UnknownLauncher("p", "P", null, "r", null)).sublabel)
    }

    @Test fun `GameHub-family rows support add by id, Winlator does not`() {
        val rows = pcLauncherRows(
            listOf(
                InstalledPcLauncher("com.xiaoji.egggame", PcLauncherType.GAMEHUB, "GameHub"),
                InstalledPcLauncher("com.winlator.cmod", PcLauncherType.WINLATOR, "Winlator Cmod"),
            ),
        )

        assertTrue(rows.first { it.name == "GameHub" }.canAddById)
        assertFalse(rows.first { it.name == "Winlator Cmod" }.canAddById)
    }
}
