package com.playfieldportal.core.domain.model.emulatorkb

import com.playfieldportal.core.domain.model.EmulatorProfile
import com.playfieldportal.core.domain.model.IntentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulatorKbExportTest {

    private val known = setOf("psx", "nds")

    private fun profile(
        id: String,
        name: String = id,
        pkg: String = "org.$id.emu",
        custom: Boolean = true,
        modified: Boolean = false,
        type: IntentType = IntentType.ACTION_VIEW,
        extras: Map<String, String> = emptyMap(),
        platforms: List<String> = listOf("psx"),
        coreMap: Map<String, String> = emptyMap(),
        knowledgeId: String? = null,
        notes: String? = null,
    ) = EmulatorProfile(
        id = id,
        name = name,
        packageName = pkg,
        activityClass = if (type == IntentType.COMPONENT) "org.$id.Main" else null,
        intentType = type,
        supportedPlatformIds = platforms,
        intentExtras = extras,
        coreMap = coreMap,
        notes = notes,
        createdAt = 111_222_333L,
        updatedAt = 444_555_666L,
        isCustom = custom,
        userModified = modified,
        isAvailable = false,
        knowledgeId = knowledgeId,
    )

    private fun build(vararg p: EmulatorProfile) = EmulatorKbExport.build(p.toList(), known, "com.playfieldportal.launcher")

    private fun ids(result: EmulatorKbExportResult) =
        result.document.emulators.map { ((it as KbItem.Ok<*>).value as EmulatorKbEmulator).id }

    @Test
    fun `candidates are custom or user-modified action-view and component profiles only`() {
        val custom = profile("mine")
        val edited = profile("edited", custom = false, modified = true)
        val untouched = profile("auto", custom = false)
        val retro = profile("retro", coreMap = mapOf("psx" to "/cores/x.so"))
        val shortcut = profile("short", type = IntentType.SHORTCUT)
        val command = profile("cmd", type = IntentType.CUSTOM_COMMAND)

        val result = EmulatorKbExport.candidates(listOf(custom, untouched, retro, shortcut, command, edited))

        assertEquals(listOf(custom, edited), result)
    }

    @Test
    fun `notes timestamps and availability are absent from the output`() {
        val result = build(profile("mine", notes = "secret note text"))
        val json = EmulatorKbExport.encode(result.document)

        assertFalse(json.contains("secret note text"))
        assertFalse(json.contains("111222333"))
        assertFalse(json.contains("444555666"))
        assertFalse(json.contains("notes"))
        assertFalse(json.contains("isAvailable"))
        assertEquals(0L, result.document.version)
    }

    @Test
    fun `a literal path extra is reported as cant-share and absent from the output`() {
        val bad = profile("bad", extras = mapOf("rom" to "/storage/emulated/0/x"))
        val good = profile("good")

        val result = build(bad, good)

        assertEquals(1, result.cantShare.size)
        assertEquals("bad", result.cantShare.single().profileId)
        assertTrue(result.cantShare.single().reason.isNotBlank())
        assertEquals(listOf("custom_good"), ids(result))
        assertFalse(EmulatorKbExport.encode(result.document).contains("/storage/emulated/0/x"))
    }

    @Test
    fun `ids use the knowledge id, else a slug, with numbered collisions`() {
        val result = build(
            profile("a", name = "My Emu!", pkg = "org.a.one"),
            profile("b", name = "my emu", pkg = "org.b.two"),
            profile("c", name = "Other", pkg = "org.c.three", knowledgeId = "known_id"),
            profile("d", name = "x", pkg = "org.d.four", knowledgeId = "known_id"),
        )
        val ids = ids(result)

        assertEquals(listOf("custom_my_emu", "custom_my_emu_2", "known_id", "known_id_2"), ids)
        assertTrue(result.cantShare.isEmpty())
    }

    @Test
    fun `an entry with no known platform is reported rather than silently dropped`() {
        val result = build(profile("far", platforms = listOf("unknown_console")))

        assertTrue(result.document.emulators.isEmpty())
        assertEquals("far", result.cantShare.single().profileId)
    }

    @Test
    fun `output round-trips through decoder and validator and imports as New`() {
        val result = build(
            profile("plain"),
            profile(
                "comp",
                type = IntentType.COMPONENT,
                extras = mapOf("rom" to "{rom_uri}", "mode" to "fast"),
                platforms = listOf("psx", "nds"),
            ),
            profile("edited", custom = false, modified = true, knowledgeId = "edited_kb"),
        )
        assertTrue(result.cantShare.isEmpty())

        val decoded = EmulatorKbDecoder.decode(EmulatorKbExport.encode(result.document))
        val document = (decoded as EmulatorKbDecode.Decoded).document
        val validated = EmulatorKbValidator.validate(document, known, "com.playfieldportal.launcher")

        assertTrue(validated.refusedEmulators.isEmpty())
        assertEquals(3, validated.emulators.size)

        val fresh = EffectiveKb(emptyList(), emptyList(), officialApplied = false)
        val plan = EmulatorKbImportPlan.build(fresh, validated, emptySet()) { _, _ -> SignerState.NotInstalled }
        assertEquals(3, plan.items.size)
        assertTrue(plan.items.all { it is ImportItem.New })
    }

    @Test
    fun `a custom profile named like a built-in does not take its id`() {
        val result = build(profile("a", name = "Dolphin", pkg = "org.a.one"))

        assertEquals(listOf("custom_dolphin"), ids(result))
    }
}
