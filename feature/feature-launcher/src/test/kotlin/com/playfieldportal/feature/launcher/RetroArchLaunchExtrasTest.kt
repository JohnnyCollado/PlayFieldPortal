package com.playfieldportal.feature.launcher

import com.playfieldportal.core.domain.model.EmulatorProfile
import com.playfieldportal.core.domain.model.IntentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The values RetroArch's own launcher hands its native activity (MainMenuActivity.startRetroActivity,
 * RetroArch v1.22.2). Without CONFIGFILE and DATADIR, RetroArch up to 1.22.2 starts on built-in
 * defaults and cannot save settings — GitHub issue 19.
 */
class RetroArchLaunchExtrasTest {

    private val pkg = "com.retroarch.aarch64"
    private val external = "/storage/emulated/0/Android/data/$pkg/files"

    private fun install(targetSdk: Int, ime: String? = "com.example.ime/.Service") =
        RetroArchLaunchExtras.Install(
            packageName = pkg,
            dataDir = "/data/user/0/$pkg",
            apkPath = "/data/app/$pkg-1/base.apk",
            targetSdk = targetSdk,
            storageRoot = "/storage/emulated/0",
            defaultIme = ime,
        )

    @Test
    fun `direct install gets the config file, data folder and shared storage root`() {
        val extras = RetroArchLaunchExtras.build(install(targetSdk = 28))

        assertEquals("$external/retroarch.cfg", extras["CONFIGFILE"])
        assertEquals("/data/user/0/$pkg", extras["DATADIR"])
        assertEquals("/data/app/$pkg-1/base.apk", extras["APK"])
        assertEquals("/storage/emulated/0", extras["SDCARD"])
        assertEquals(external, extras["EXTERNAL"])
        assertEquals("com.example.ime/.Service", extras["IME"])
    }

    @Test
    fun `play store build is confined to its own external files folder`() {
        // Same package name as the direct build (playStorePlus is also .aarch64); only the target
        // SDK tells them apart. Play Store builds target 35, direct builds 28.
        val extras = RetroArchLaunchExtras.build(install(targetSdk = 35))

        assertEquals(external, extras["SDCARD"])
        assertEquals(external, extras["EXTERNAL"])
        assertEquals("$external/retroarch.cfg", extras["CONFIGFILE"])
    }

    @Test
    fun `unknown values are left out, never sent blank`() {
        val extras = RetroArchLaunchExtras.build(
            install(targetSdk = 28, ime = null).copy(apkPath = null),
        )

        assertFalse("IME" in extras)
        assertFalse("APK" in extras)
        assertTrue(extras.values.none { it.isBlank() })
    }

    @Test
    fun `only a RetroArch component profile qualifies`() {
        fun profile(packageName: String, activity: String?) = EmulatorProfile(
            id = "p", name = "p", packageName = packageName, activityClass = activity,
            intentType = IntentType.COMPONENT, supportedPlatformIds = listOf("gba"),
        )

        assertTrue(RetroArchLaunchExtras.appliesTo(profile(pkg, EmulatorDetector.RETROARCH_ACTIVITY_CLASS)))
        assertTrue(RetroArchLaunchExtras.appliesTo(profile("com.retroarch", EmulatorDetector.RETROARCH_ACTIVITY_CLASS)))
        assertFalse(
            RetroArchLaunchExtras.appliesTo(
                profile("com.github.stenzek.duckstation", "com.github.stenzek.duckstation.EmulationActivity"),
            ),
        )
        // RetroArch's Java menu activity derives these itself; only the native activity needs them.
        assertFalse(RetroArchLaunchExtras.appliesTo(profile(pkg, "com.retroarch.browser.mainmenu.MainMenuActivity")))
    }
}
