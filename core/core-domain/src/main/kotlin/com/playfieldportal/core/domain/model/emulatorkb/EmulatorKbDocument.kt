package com.playfieldportal.core.domain.model.emulatorkb

import com.playfieldportal.core.domain.model.IntentType
import kotlinx.serialization.Serializable

/**
 * A decoded emulator knowledge-base file. Structure only: nothing here has passed the semantic rules
 * (those are [EmulatorKbValidator]'s). Each emulator and platform item stands alone so one bad entry
 * does not sink the document.
 */
class EmulatorKbDocument(
    val format: String,
    val schemaVersion: Int,
    /** Publisher's monotonically increasing version. User files and exports use 0. */
    val version: Long,
    val label: String,
    val minAppVersion: Int,
    val emulators: List<KbItem<EmulatorKbEmulator>>,
    val platforms: List<KbItem<EmulatorKbPlatform>>,
)

/** One item of a document: decoded, or refused with a reason ([index] is its position in the array). */
sealed interface KbItem<out T> {
    data class Ok<out T>(val value: T) : KbItem<T>

    data class Rejected(val index: Int, val id: String?, val reason: String) : KbItem<Nothing>
}

/** One emulator. Field names for [launch] mirror `EmulatorProfile`. */
@Serializable
data class EmulatorKbEmulator(
    val id: String,
    val name: String,
    // Tried in order; the first installed one wins.
    val packageNames: List<String>,
    val legacyIds: List<String> = emptyList(),
    val platformIds: List<String> = emptyList(),
    val launch: EmulatorKbLaunch = EmulatorKbLaunch(),
    // A complete launch object that replaces [launch] for one of this entry's own packages (AD-15).
    val launchByPackage: Map<String, EmulatorKbLaunch> = emptyMap(),
    val signerSha256: List<String> = emptyList(),
)

@Serializable
data class EmulatorKbLaunch(
    val intentType: IntentType = IntentType.ACTION_VIEW,
    val activityClass: String? = null,
    val extras: Map<String, String> = emptyMap(),
    val boolExtras: Map<String, Boolean> = emptyMap(),
    val arrayExtras: Map<String, List<String>> = emptyMap(),
    val action: String? = null,
    val category: String? = null,
    val flags: List<String> = emptyList(),
    val attachRomData: Boolean = false,
    val mimeType: String? = null,
    val useSafUri: Boolean = false,
)

/** Extensions a document adds to an existing platform. */
@Serializable
data class EmulatorKbPlatform(
    val id: String,
    val romExtensions: List<String> = emptyList(),
)
