package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import android.content.Intent
import com.playfieldportal.core.data.repository.Xbox360Emulator
import com.playfieldportal.feature.appbar.LauncherShortcutRepository
import com.playfieldportal.feature.launcher.EmulatorProfileRepository
import com.playfieldportal.feature.launcher.InstalledPcLauncher
import com.playfieldportal.feature.launcher.PcLauncherCatalog
import com.playfieldportal.feature.launcher.isRetroArchProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

private const val RETROARCH_PACKAGE = "com.retroarch"

// Vita3K ships under one package name plus a commonly shared variant; either one installed means
// its data-folder (ux0) section is offered. Same set as the built-in knowledge base entry.
private val VITA3K_PACKAGES = listOf("org.vita3k.emulator", "org.vita3k.emulator.ikhoeyZX")

private const val ARMSX3_PACKAGE = "com.armsx3"

/**
 * The device facts Initial Setup reads: which optional apps are installed, whether PFP is the
 * Home app, and installed apps' real Android labels. One seam, so the wizard's view models never
 * query the PackageManager themselves.
 */
class SetupEnvironment @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profiles: EmulatorProfileRepository,
    private val launcherShortcuts: LauncherShortcutRepository,
) {
    fun availability(): SetupAvailability = SetupAvailability(
        retroArch = isInstalled(RETROARCH_PACKAGE),
        vita3K = VITA3K_PACKAGES.any(::isInstalled),
        armsx3 = isInstalled(ARMSX3_PACKAGE),
        x360Mobile = Xbox360Emulator.X360_MOBILE.packages.any(::isInstalled),
        xenDroid = Xbox360Emulator.XENDROID.packages.any(::isInstalled),
        knownEmulator = profiles.getInstalledProfiles().any { !it.isRetroArchProfile() },
        pcLauncher = pcLaunchers().isNotEmpty(),
        alreadyHome = isHomeApp(),
    )

    fun isHomeApp(): Boolean = launcherShortcuts.isDefaultLauncher()

    /** The role request Library Manager's "Play Field Portal as Home" row also launches. */
    fun homeRoleIntent(): Intent = launcherShortcuts.homeRoleRequestIntent()

    /** Every verified PC launcher, by its Android label. */
    fun pcLaunchers(): List<InstalledPcLauncher> =
        PcLauncherCatalog.installedLaunchers(context.packageManager)

    /** The label Android shows for [packageName], or null when it is not installed. */
    fun appLabel(packageName: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()

    private fun isInstalled(packageName: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess
}
