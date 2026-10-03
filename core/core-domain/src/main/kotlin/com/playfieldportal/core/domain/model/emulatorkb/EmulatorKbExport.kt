package com.playfieldportal.core.domain.model.emulatorkb

import com.playfieldportal.core.domain.model.EmulatorProfile
import com.playfieldportal.core.domain.model.IntentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A selected profile that could not be exported. [reason] reads as a verb phrase after the profile name. */
data class CantShare(val profileId: String, val name: String, val reason: String)

/** The exportable document (validated, version 0) and the selected profiles left out of it. */
class EmulatorKbExportResult(val document: EmulatorKbDocument, val cantShare: List<CantShare>) {
    val exportedCount: Int get() = document.emulators.size
}

/**
 * Turns the user's own emulator profiles into a shareable KB document (AD-12). Only schema fields are
 * carried, and the output goes through [EmulatorKbValidator], so an export always imports and never
 * carries a literal path. Pure Kotlin.
 */
object EmulatorKbExport {
    private const val RETROARCH_SOURCE = "retroarch-core"
    private const val MAX_ID = 48

    // Leaves room for the "custom_" prefix and a "_NN" collision suffix inside the 48-character id limit.
    private const val MAX_SLUG = 37

    private val json = Json { prettyPrint = true }

    /** Persisted profiles the user may share, in the given order. */
    fun candidates(persisted: List<EmulatorProfile>): List<EmulatorProfile> = persisted.filter {
        (it.isCustom || it.userModified) &&
            (it.intentType == IntentType.ACTION_VIEW || it.intentType == IntentType.COMPONENT) &&
            it.coreMap.isEmpty() &&
            it.autoSource != RETROARCH_SOURCE
    }

    fun build(
        selected: List<EmulatorProfile>,
        knownPlatformIds: Set<String>,
        selfPackage: String?,
    ): EmulatorKbExportResult {
        val ids = assignIds(selected)
        val byId = selected.indices.associate { ids[it] to selected[it] }
        val draft = document(selected.indices.map { toEntry(ids[it], selected[it]) })
        val validated = EmulatorKbValidator.validate(draft, knownPlatformIds, selfPackage)

        val cantShare = mutableListOf<CantShare>()
        for (refusal in validated.refusedEmulators) {
            val profile = byId[refusal.id] ?: continue
            cantShare += CantShare(profile.id, profile.name, refusal.reason)
        }
        val kept = validated.emulators.map { it.id }.toSet()
        val refused = validated.refusedEmulators.mapNotNull { it.id }.toSet()
        // The validator omits an entry with no platform this app knows without a refusal; say so here.
        for ((id, profile) in byId) {
            if (id !in kept && id !in refused) {
                cantShare += CantShare(profile.id, profile.name, "has no console this app knows")
            }
        }
        val order = selected.map { it.id }
        return EmulatorKbExportResult(document(validated.emulators), cantShare.sortedBy { order.indexOf(it.profileId) })
    }

    /** The file text for a document from [build]. */
    fun encode(document: EmulatorKbDocument): String {
        val emulators = document.emulators.mapNotNull { (it as? KbItem.Ok)?.value }
        val root = JsonObject(
            mapOf(
                "format" to JsonPrimitive(document.format),
                "schemaVersion" to JsonPrimitive(document.schemaVersion),
                "version" to JsonPrimitive(document.version),
                "emulators" to JsonArray(
                    emulators.map { json.encodeToJsonElement(EmulatorKbEmulator.serializer(), it) },
                ),
                "platforms" to JsonArray(emptyList()),
            ),
        )
        return json.encodeToString(JsonObject.serializer(), root)
    }

    private fun document(emulators: List<EmulatorKbEmulator>) = EmulatorKbDocument(
        format = EmulatorKbDecoder.FORMAT,
        schemaVersion = EmulatorKbDecoder.SCHEMA_VERSION,
        version = 0,
        label = "",
        minAppVersion = 0,
        emulators = emulators.map { KbItem.Ok(it) },
        platforms = emptyList(),
    )

    private fun toEntry(id: String, p: EmulatorProfile) = EmulatorKbEmulator(
        id = id,
        name = p.name.trim(),
        packageNames = listOf(p.packageName),
        platformIds = p.supportedPlatformIds,
        launch = EmulatorKbLaunch(
            intentType = p.intentType,
            activityClass = p.activityClass,
            extras = p.intentExtras,
            boolExtras = p.intentBoolExtras,
            arrayExtras = p.intentArrayExtras,
            action = p.intentAction,
            category = p.intentCategory,
            flags = p.intentFlags,
            attachRomData = p.attachRomData,
            mimeType = p.mimeType,
            useSafUri = p.useSafUri,
        ),
        signerSha256 = p.signerSha256,
    )

    // Knowledge ids are claimed first so a slug never takes one; the rest get `custom_<slug>` (never a built-in
    // id), `_2`, `_3` on collision.
    private fun assignIds(selected: List<EmulatorProfile>): List<String> {
        val ids = arrayOfNulls<String>(selected.size)
        val used = mutableSetOf<String>()
        for (withKnowledgeId in listOf(true, false)) {
            selected.forEachIndexed { i, p ->
                if ((p.knowledgeId != null) == withKnowledgeId) {
                    ids[i] = unique(p.knowledgeId ?: "custom_" + slug(p.name), used).also { used += it }
                }
            }
        }
        return ids.map { it!! }
    }

    private fun unique(base: String, used: Set<String>): String {
        if (base !in used) return base
        var n = 2
        while (true) {
            val suffix = "_$n"
            val candidate = base.take(MAX_ID - suffix.length) + suffix
            if (candidate !in used) return candidate
            n++
        }
    }

    private fun slug(name: String): String {
        val s = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(MAX_SLUG).trim('_')
        return if (s.length >= 2) s else "emulator"
    }
}
