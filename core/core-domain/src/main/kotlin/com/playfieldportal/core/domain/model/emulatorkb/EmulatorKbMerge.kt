package com.playfieldportal.core.domain.model.emulatorkb

/** Which layer an effective item came from (AD-5). */
sealed interface KbSource {
    data object BuiltIn : KbSource

    data object Official : KbSource

    /** [fileId] is the imported file's id in the user layer index. */
    data class User(val fileId: String) : KbSource
}

/** One validated layer. [version] is the publisher's version; user layers use 0. */
class EmulatorKbLayer(
    val emulators: List<EmulatorKbEmulator>,
    val platforms: List<EmulatorKbPlatform>,
    val version: Long,
)

/** A user layer, identified by its file. */
class UserKbLayer(val fileId: String, val layer: EmulatorKbLayer)

/** An effective emulator, with the layer that supplied it and the source it displaced, if any. */
data class EffectiveKbEmulator(
    val emulator: EmulatorKbEmulator,
    val source: KbSource,
    val replaced: KbSource?,
)

data class EffectiveKbPlatform(
    val platform: EmulatorKbPlatform,
    val source: KbSource,
    val replaced: KbSource?,
)

data class EffectiveKb(
    val emulators: List<EffectiveKbEmulator>,
    val platforms: List<EffectiveKbPlatform>,
    /** False when no official layer was given or it was not newer than the built-in one. */
    val officialApplied: Boolean,
)

/**
 * Computes the effective knowledge base from validated layers (AD-5): built-in < official < user files
 * in import order. Pure Kotlin.
 */
object EmulatorKbMerge {

    fun merge(builtIn: EmulatorKbLayer, official: EmulatorKbLayer?, users: List<UserKbLayer>): EffectiveKb {
        val officialApplied = official != null && official.version > builtIn.version
        var emulators = emptyList<EffectiveKbEmulator>()
        var platforms = emptyList<EffectiveKbPlatform>()

        fun apply(layer: EmulatorKbLayer, source: KbSource) {
            emulators = mergeEmulators(emulators, layer.emulators, source)
            platforms = mergePlatforms(platforms, layer.platforms, source)
        }

        apply(builtIn, KbSource.BuiltIn)
        if (officialApplied) apply(official,KbSource.Official)
        users.forEach { apply(it.layer, KbSource.User(it.fileId)) }
        return EffectiveKb(emulators, platforms, officialApplied)
    }

    private fun mergeEmulators(
        lower: List<EffectiveKbEmulator>,
        incoming: List<EmulatorKbEmulator>,
        source: KbSource,
    ): List<EffectiveKbEmulator> {
        val survivors = lower.toMutableList()
        val added = mutableListOf<EffectiveKbEmulator>()
        // Each incoming entry is checked against the lower layers only, never against its own layer.
        for (entry in incoming) {
            var replaced: KbSource? = null
            val iterator = survivors.listIterator()
            while (iterator.hasNext()) {
                val current = iterator.next()
                val stripped = strip(current.emulator, entry, source)
                if (stripped == null) {
                    if (replaced == null) replaced = current.source
                    iterator.remove()
                } else if (stripped !== current.emulator) {
                    iterator.set(current.copy(emulator = stripped))
                }
            }
            added += EffectiveKbEmulator(entry, source, replaced)
        }
        return survivors + added
    }

    /** [lower] unchanged, trimmed of packages [higher] claims, or null when [higher] replaces it outright. */
    private fun strip(lower: EmulatorKbEmulator, higher: EmulatorKbEmulator, source: KbSource): EmulatorKbEmulator? {
        // legacyIds are a publisher mechanism: a user file may not use them to remove another entry.
        val publisher = source == KbSource.BuiltIn || source == KbSource.Official
        val legacyMatch = publisher && (lower.id in higher.legacyIds || higher.id in lower.legacyIds)
        if (lower.id == higher.id || legacyMatch) return null
        val claimed = higher.packageNames.toSet()
        if (lower.packageNames.none { it in claimed }) return lower
        val remaining = lower.packageNames.filter { it !in claimed }
        if (remaining.isEmpty()) return null
        return lower.copy(
            packageNames = remaining,
            launchByPackage = lower.launchByPackage.filterKeys { it in remaining },
        )
    }

    private fun mergePlatforms(
        lower: List<EffectiveKbPlatform>,
        incoming: List<EmulatorKbPlatform>,
        source: KbSource,
    ): List<EffectiveKbPlatform> {
        val byId = lower.associateBy { it.platform.id }
        val replacedIds = incoming.map { it.id }.toSet()
        val kept = lower.filter { it.platform.id !in replacedIds }
        return kept + incoming.map { EffectiveKbPlatform(it, source, byId[it.id]?.source) }
    }
}
