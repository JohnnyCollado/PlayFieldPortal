package com.playfieldportal.core.domain.model.emulatorkb

/** Whether an entry's pinned signer matches the build installed on this device (AD-10). */
enum class SignerState { NotInstalled, Matches, Mismatch }

/** One changed field of an entry, as display text. */
data class FieldChange(val field: String, val before: String, val after: String)

/** One row of the import review (AD-11). */
sealed interface ImportItem {
    /** A row the user can tick. [key] is what [EmulatorKbImportPlan.toggle] takes. */
    sealed interface Selectable : ImportItem {
        val key: String
        val selected: Boolean
    }

    /** An entry nothing in the effective KB matches. "Add?", checked by default. */
    data class New(val entry: EmulatorKbEmulator, override val selected: Boolean = true) : Selectable {
        override val key get() = emulatorKey(entry.id)
    }

    /**
     * An entry that matches [current]. "Override?", unchecked by default.
     * [overridesOfficial]: [current] comes from the built-in or official layer.
     * [userEdited]: the user has edited the profile for [current], and those edits still win.
     */
    data class Change(
        val entry: EmulatorKbEmulator,
        val current: EmulatorKbEmulator,
        val diff: List<FieldChange>,
        val overridesOfficial: Boolean,
        val userEdited: Boolean,
        override val selected: Boolean = false,
    ) : Selectable {
        override val key get() = emulatorKey(entry.id)
    }

    /** An entry identical to the effective one. Shown, not selectable. */
    data class Unchanged(val entry: EmulatorKbEmulator) : ImportItem

    /** An entry or platform item that cannot be imported, with no choice. */
    data class Blocked(val id: String?, val name: String?, val reason: String) : ImportItem

    /** Extensions an existing platform would gain. "Update?", unchecked by default. */
    data class PlatformUpdate(
        val id: String,
        val addedExtensions: List<String>,
        val platform: EmulatorKbPlatform,
        override val selected: Boolean = false,
    ) : Selectable {
        override val key get() = platformKey(id)
    }

    companion object {
        fun emulatorKey(id: String) = "emulator:$id"

        fun platformKey(id: String) = "platform:$id"
    }
}

/**
 * The per-entry review of a user file (AD-11): what each entry would do, what the user ticked, and the
 * document holding only the ticked entries. Immutable; [toggle] returns a new plan. Pure Kotlin.
 */
class EmulatorKbImportPlan private constructor(val items: List<ImportItem>) {

    /** Ticked emulators. Platform updates are not counted ("Import N emulators"). */
    val selectedCount: Int
        get() = items.count { (it is ImportItem.New && it.selected) || (it is ImportItem.Change && it.selected) }

    /** Ticked platform (console) updates; with [selectedCount] they decide whether anything can be imported. */
    val selectedPlatformCount: Int
        get() = items.count { it is ImportItem.PlatformUpdate && it.selected }

    /** Flips the row with [key]. An unknown key, or a row with no choice, changes nothing. */
    fun toggle(key: String): EmulatorKbImportPlan = EmulatorKbImportPlan(
        items.map { item ->
            when {
                item is ImportItem.New && item.key == key -> item.copy(selected = !item.selected)
                item is ImportItem.Change && item.key == key -> item.copy(selected = !item.selected)
                item is ImportItem.PlatformUpdate && item.key == key -> item.copy(selected = !item.selected)
                else -> item
            }
        },
    )

    /** A version 0 document holding only the ticked emulators and platform updates, to store as a user file. */
    fun selectedDocument(): EmulatorKbDocument = EmulatorKbDocument(
        format = EmulatorKbDecoder.FORMAT,
        schemaVersion = EmulatorKbDecoder.SCHEMA_VERSION,
        version = 0,
        label = "",
        minAppVersion = 0,
        emulators = items.mapNotNull {
            when {
                it is ImportItem.New && it.selected -> KbItem.Ok(it.entry)
                it is ImportItem.Change && it.selected -> KbItem.Ok(it.entry)
                else -> null
            }
        },
        platforms = items.mapNotNull { if (it is ImportItem.PlatformUpdate && it.selected) KbItem.Ok(it.platform) else null },
    )

    companion object {
        /**
         * [userEditedIds] are the knowledge ids that have a user-modified profile. [signerProbe] answers
         * for one installed-or-not package and the entry's pinned digests; it is the only device access.
         */
        fun build(
            effective: EffectiveKb,
            validated: EmulatorKbValidation,
            userEditedIds: Set<String>,
            signerProbe: (pkg: String, pins: List<String>) -> SignerState,
        ): EmulatorKbImportPlan {
            val items = mutableListOf<ImportItem>()
            validated.refusedEmulators.mapTo(items) { ImportItem.Blocked(it.id, null, it.reason) }
            validated.refusedPlatforms.mapTo(items) { ImportItem.Blocked(it.id, null, it.reason) }

            for (entry in validated.emulators) {
                val mismatched = if (entry.signerSha256.isEmpty()) null else entry.packageNames.firstOrNull {
                    signerProbe(it, entry.signerSha256) == SignerState.Mismatch
                }
                if (mismatched != null) {
                    items += ImportItem.Blocked(entry.id, entry.name, "Signer differs from the installed $mismatched")
                    continue
                }
                val match = match(effective.emulators, entry)
                items += when {
                    match == null -> ImportItem.New(entry)
                    match.emulator == entry -> ImportItem.Unchanged(entry)
                    else -> ImportItem.Change(
                        entry = entry,
                        current = match.emulator,
                        diff = diff(match.emulator, entry),
                        overridesOfficial = match.source == KbSource.BuiltIn || match.source == KbSource.Official,
                        userEdited = match.emulator.id in userEditedIds,
                    )
                }
            }

            val platformsById = effective.platforms.associateBy { it.platform.id }
            for (platform in validated.platforms) {
                val known = platformsById[platform.id]?.platform?.romExtensions.orEmpty().toSet()
                val added = platform.romExtensions.filter { it !in known }
                if (added.isNotEmpty()) items += ImportItem.PlatformUpdate(platform.id, added, platform)
            }
            return EmulatorKbImportPlan(items)
        }

        /** By id, then a shared package. Never by legacy id: the merge ignores user legacyIds, so the review must too. */
        private fun match(effective: List<EffectiveKbEmulator>, entry: EmulatorKbEmulator): EffectiveKbEmulator? =
            effective.firstOrNull { it.emulator.id == entry.id }
                ?: effective.firstOrNull { current -> current.emulator.packageNames.any { it in entry.packageNames } }

        private fun diff(a: EmulatorKbEmulator, b: EmulatorKbEmulator): List<FieldChange> {
            val changes = mutableListOf<FieldChange>()
            fun <T> field(name: String, before: T, after: T, show: (T) -> String = { it?.toString() ?: NONE }) {
                if (before != after) changes += FieldChange(name, show(before), show(after))
            }
            fun list(l: List<String>) = l.joinToString(", ").ifEmpty { NONE }
            fun <V> map(m: Map<String, V>) = m.entries.sortedBy { it.key }.joinToString(", ") { "${it.key}=${it.value}" }.ifEmpty { NONE }

            field("Id", a.id, b.id)
            field("Name", a.name, b.name)
            field("Legacy ids", a.legacyIds, b.legacyIds, ::list)
            field("Packages", a.packageNames, b.packageNames, ::list)
            field("Platforms", a.platformIds, b.platformIds, ::list)
            field("Intent type", a.launch.intentType, b.launch.intentType) { it.name }
            field("Activity", a.launch.activityClass, b.launch.activityClass)
            field("Action", a.launch.action, b.launch.action)
            field("Category", a.launch.category, b.launch.category)
            field("Extras", a.launch.extras, b.launch.extras, ::map)
            field("Boolean extras", a.launch.boolExtras, b.launch.boolExtras, ::map)
            field("Array extras", a.launch.arrayExtras, b.launch.arrayExtras) { m -> map(m.mapValues { list(it.value) }) }
            field("Flags", a.launch.flags, b.launch.flags, ::list)
            field("MIME type", a.launch.mimeType, b.launch.mimeType)
            field("URI mode", a.launch.useSafUri, b.launch.useSafUri) { if (it) "SAF URI" else "File path" }
            field("Attach ROM data", a.launch.attachRomData, b.launch.attachRomData)
            field("Per-package launch", a.launchByPackage, b.launchByPackage) { m ->
                m.entries.sortedBy { it.key }.joinToString("; ") { (pkg, l) ->
                    "$pkg: ${launchText(l, ::list, ::map)}"
                }.ifEmpty { NONE }
            }
            field("Signers", a.signerSha256, b.signerSha256, ::list)
            return changes
        }

        // Every launch field, so a per-package override hides nothing from the review.
        private fun launchText(
            l: EmulatorKbLaunch,
            list: (List<String>) -> String,
            map: (Map<String, Any>) -> String,
        ): String = listOf(
            "type ${l.intentType.name}",
            "activity ${l.activityClass ?: NONE}",
            "action ${l.action ?: NONE}",
            "category ${l.category ?: NONE}",
            "extras ${map(l.extras)}",
            "boolean extras ${map(l.boolExtras)}",
            "array extras ${map(l.arrayExtras.mapValues { list(it.value) })}",
            "flags ${list(l.flags)}",
            "MIME ${l.mimeType ?: NONE}",
            if (l.useSafUri) "SAF URI" else "file path",
            "attach ROM data ${l.attachRomData}",
        ).joinToString(", ")

        private const val NONE = "none"
    }
}
