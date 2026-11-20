package com.playfieldportal.feature.launcher

import com.playfieldportal.core.domain.model.EmulatorProfile

/**
 * The launch values RetroArch's native activity needs besides `ROM` and `LIBRETRO`.
 *
 * RetroArch's own launcher always sends them (MainMenuActivity.startRetroActivity). Up to 1.22.2
 * the native side has no fallback: without `CONFIGFILE` it runs on built-in defaults and cannot
 * save, and without `DATADIR` it never sets up its autoconfig, save or remap folders — so a game
 * started from PFP ignored the user's settings and hotkeys (GitHub issue 19). Later builds derive
 * the same values themselves; a value sent here still wins there, so these must stay identical to
 * what the launcher sends.
 *
 * Added at launch time rather than stored on the profile, so existing and hand-made RetroArch
 * profiles are covered without a re-detect. A value the profile sets itself takes precedence.
 */
internal object RetroArchLaunchExtras {

    /** What PFP can read about the installed RetroArch build. */
    data class Install(
        val packageName: String,
        val dataDir: String,
        val apkPath: String?,
        val targetSdk: Int,
        val storageRoot: String,
        val defaultIme: String?,
    )

    /** Only the native activity reads these; RetroArch's Java menu works them out on its own. */
    fun appliesTo(profile: EmulatorProfile): Boolean =
        profile.packageName in RetroArchCoreScanner.RETROARCH_PACKAGES &&
            profile.activityClass == EmulatorDetector.RETROARCH_ACTIVITY_CLASS

    /** RetroArch's global config file: `<external files dir>/retroarch.cfg`. */
    fun configPath(packageName: String, storageRoot: String): String =
        "${externalFilesDir(packageName, storageRoot)}/retroarch.cfg"

    fun build(install: Install): Map<String, String> {
        val external = externalFilesDir(install.packageName, install.storageRoot)
        return buildMap {
            put("CONFIGFILE", configPath(install.packageName, install.storageRoot))
            put("DATADIR", install.dataDir)
            // Play Store builds are confined to their own folder; direct builds use shared storage.
            // This decides where saves and states live, so it must match what RetroArch's own
            // launcher picks or a game would have one save from PFP and another from RetroArch.
            put("SDCARD", if (install.targetSdk >= SCOPED_STORAGE_TARGET_SDK) external else install.storageRoot)
            put("EXTERNAL", external)
            install.apkPath?.takeIf { it.isNotBlank() }?.let { put("APK", it) }
            install.defaultIme?.takeIf { it.isNotBlank() }?.let { put("IME", it) }
        }
    }

    private fun externalFilesDir(packageName: String, storageRoot: String): String =
        "$storageRoot/Android/data/$packageName/files"

    // The package name cannot tell the builds apart (direct and Play Store "Plus" are both
    // com.retroarch.aarch64). Direct builds target SDK 28 and Play Store builds 35; 30 is where
    // Android enforces scoped storage, the reason the two builds differ.
    private const val SCOPED_STORAGE_TARGET_SDK = 30
}
