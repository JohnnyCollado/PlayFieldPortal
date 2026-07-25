package com.playfieldportal.feature.launcher

import com.playfieldportal.core.domain.model.EmulatorProfile

/**
 * Which emulator a console defaults to when the user hasn't chosen one.
 *
 * Purpose-built standalone emulators are preferred over RetroArch cores: they generally need less
 * setup (no core download, fewer BIOS/asset prerequisites) and are the better out-of-the-box
 * experience for a freshly detected console. RetroArch cores remain fully selectable — they just
 * don't win the automatic pick when a standalone is installed for the same platform.
 */

/** True for RetroArch itself and for the per-core profiles generated from an install. */
fun EmulatorProfile.isRetroArchProfile(): Boolean =
    autoSource == "retroarch-core" || packageName.startsWith("com.retroarch")

/**
 * Orders profiles by how suitable they are as an automatic default: standalones first, RetroArch
 * cores after. Stable — profiles within a tier keep their existing relative order, so detection
 * order (catalog order) still decides between two standalones.
 */
fun List<EmulatorProfile>.byLaunchPreference(): List<EmulatorProfile> =
    sortedBy { if (it.isRetroArchProfile()) 1 else 0 }
