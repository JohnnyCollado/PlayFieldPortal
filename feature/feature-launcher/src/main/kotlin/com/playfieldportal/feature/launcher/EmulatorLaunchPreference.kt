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

/**
 * Orders a console's launch pool so its remembered RetroArch core stays the automatic pick.
 *
 * [rememberedProfileId] is the profile id [AutoCoreMemory] last recorded for the console. When
 * that profile is present in the pool (installed, available, and mapped for the platform — the
 * pool is already filtered for all three by its callers) it moves to the front of the RetroArch
 * tier, so a core can never overtake a console's core just because detection order changed when
 * another core was installed or removed. Standalones stay preferred ahead of it, and every other
 * profile keeps its relative order.
 *
 * A remembered profile that is missing from the pool is ignored and the pool is returned
 * unchanged — the core is genuinely gone, so the fallback picks normally and the record is
 * refreshed on the next successful launch.
 */
fun List<EmulatorProfile>.stabilizeCore(rememberedProfileId: String?): List<EmulatorProfile> {
    val remembered = rememberedProfileId?.let { id -> firstOrNull { it.id == id } } ?: return this
    if (!remembered.isRetroArchProfile()) return this
    return buildList {
        addAll(this@stabilizeCore.filter { !it.isRetroArchProfile() })
        add(remembered)
        addAll(this@stabilizeCore.filter { it.isRetroArchProfile() && it.id != remembered.id })
    }
}
