package com.playfieldportal.core.domain.model.emulatorkb

import com.playfieldportal.core.domain.model.IntentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulatorKbDecoderTest {

    private fun emulator(id: String, extra: String = "") = """
        {"id":"$id","name":"Name $id","packageNames":["com.example.$id"],"platformIds":["psx"],
         "launch":{"intentType":"COMPONENT","activityClass":"com.example.$id.Main",
                   "extras":{"bootPath":"{rom_uri}"},"boolExtras":{"resumeState":false},
                   "flags":["CLEAR_TASK"]}$extra}
    """.trimIndent()

    private fun doc(emulators: String, top: String = "") = """
        {"format":"pfp-emulator-kb","schemaVersion":1,"version":5,"label":"L","minAppVersion":10,
         "emulators":[$emulators],"platforms":[{"id":"psx","romExtensions":["cue","bin"]}]$top}
    """.trimIndent()

    private fun decoded(text: String): EmulatorKbDocument =
        (EmulatorKbDecoder.decode(text) as EmulatorKbDecode.Decoded).document

    private fun rejectedReason(text: String): String =
        (EmulatorKbDecoder.decode(text) as EmulatorKbDecode.Rejected).reason

    @Test
    fun `valid two entry document decodes`() {
        val d = decoded(doc(emulator("alpha") + "," + emulator("beta")))
        assertEquals("pfp-emulator-kb", d.format)
        assertEquals(1, d.schemaVersion)
        assertEquals(5L, d.version)
        assertEquals("L", d.label)
        assertEquals(10, d.minAppVersion)
        assertEquals(2, d.emulators.size)
        val first = (d.emulators[0] as KbItem.Ok).value
        assertEquals("alpha", first.id)
        assertEquals(listOf("com.example.alpha"), first.packageNames)
        assertEquals(IntentType.COMPONENT, first.launch.intentType)
        assertEquals("com.example.alpha.Main", first.launch.activityClass)
        assertEquals(mapOf("bootPath" to "{rom_uri}"), first.launch.extras)
        assertEquals(mapOf("resumeState" to false), first.launch.boolExtras)
        assertEquals(listOf("CLEAR_TASK"), first.launch.flags)
        assertEquals(1, d.platforms.size)
        assertEquals(listOf("cue", "bin"), (d.platforms[0] as KbItem.Ok).value.romExtensions)
    }

    @Test
    fun `unknown top level key rejects the document`() {
        val reason = rejectedReason(doc(emulator("alpha"), ""","surprise":1"""))
        assertTrue(reason, reason.contains("surprise"))
    }

    @Test
    fun `unknown key inside one entry rejects only that entry`() {
        val d = decoded(doc(emulator("alpha", ""","bogus":1""") + "," + emulator("beta")))
        val bad = d.emulators[0] as KbItem.Rejected
        assertEquals(0, bad.index)
        assertEquals("alpha", bad.id)
        assertTrue(bad.reason, bad.reason.contains("bogus"))
        assertTrue(d.emulators[1] is KbItem.Ok)
    }

    @Test
    fun `wrong format is rejected`() {
        val reason = rejectedReason("""{"format":"other","schemaVersion":1}""")
        assertTrue(reason, reason.contains("format"))
    }

    @Test
    fun `non integer schemaVersion is rejected`() {
        rejectedReason("""{"format":"pfp-emulator-kb","schemaVersion":"1"}""")
        rejectedReason("""{"format":"pfp-emulator-kb","schemaVersion":1.5}""")
        rejectedReason("""{"format":"pfp-emulator-kb"}""")
    }

    @Test
    fun `non json input is rejected without throwing`() {
        rejectedReason("not json at all")
        rejectedReason("")
        rejectedReason("[1,2,3]")
        rejectedReason("{\"format\":")
    }

    @Test
    fun `input over the character cap is rejected`() {
        val reason = rejectedReason(" ".repeat(EmulatorKbDecoder.MAX_CHARS + 1))
        assertTrue(reason, reason.contains("large"))
    }

    @Test
    fun `501 emulators are rejected`() {
        val many = (0..EmulatorKbDecoder.MAX_EMULATORS).joinToString(",") { emulator("e$it") }
        val reason = rejectedReason(doc(many))
        assertTrue(reason, reason.contains("emulators"))
    }

    @Test
    fun `500 emulators are accepted`() {
        val many = (1..EmulatorKbDecoder.MAX_EMULATORS).joinToString(",") { emulator("e$it") }
        assertEquals(500, decoded(doc(many)).emulators.size)
    }

    @Test
    fun `65 platforms are rejected`() {
        val platforms = (0..EmulatorKbDecoder.MAX_PLATFORMS).joinToString(",") { """{"id":"p$it"}""" }
        val text = """{"format":"pfp-emulator-kb","schemaVersion":1,"platforms":[$platforms]}"""
        val reason = rejectedReason(text)
        assertTrue(reason, reason.contains("platforms"))
    }

    @Test
    fun `an over long string rejects only its entry`() {
        val d = decoded(doc(emulator("alpha").replace("Name alpha", "x".repeat(1000)) + "," + emulator("beta")))
        assertTrue(d.emulators[0] is KbItem.Rejected)
        assertTrue(d.emulators[1] is KbItem.Ok)
    }

    @Test
    fun `missing optional fields take defaults`() {
        val d = decoded(
            """{"format":"pfp-emulator-kb","schemaVersion":1,
                "emulators":[{"id":"minimal","name":"Min","packageNames":["com.example.min"]}]}""",
        )
        assertEquals(0L, d.version)
        assertEquals("", d.label)
        assertEquals(0, d.minAppVersion)
        assertTrue(d.platforms.isEmpty())
        val e = (d.emulators[0] as KbItem.Ok).value
        assertTrue(e.legacyIds.isEmpty())
        assertTrue(e.platformIds.isEmpty())
        assertTrue(e.signerSha256.isEmpty())
        assertTrue(e.launchByPackage.isEmpty())
        assertEquals(IntentType.ACTION_VIEW, e.launch.intentType)
        assertEquals(null, e.launch.activityClass)
        assertTrue(e.launch.extras.isEmpty())
        assertTrue(e.launch.arrayExtras.isEmpty())
        assertEquals(false, e.launch.attachRomData)
        assertEquals(false, e.launch.useSafUri)
    }

    @Test
    fun `launchByPackage with one override decodes`() {
        val d = decoded(
            doc(
                emulator(
                    "alpha",
                    ""","launchByPackage":{"com.example.alpha":{"intentType":"ACTION_VIEW","mimeType":"application/x-test"}}""",
                ),
            ),
        )
        val e = (d.emulators[0] as KbItem.Ok).value
        val override = e.launchByPackage.getValue("com.example.alpha")
        assertEquals(IntentType.ACTION_VIEW, override.intentType)
        assertEquals("application/x-test", override.mimeType)
    }

    @Test
    fun `unknown key inside an override rejects that entry`() {
        val d = decoded(
            doc(
                emulator("alpha", ""","launchByPackage":{"com.example.alpha":{"intentType":"ACTION_VIEW","nope":1}}""") +
                    "," + emulator("beta"),
            ),
        )
        val bad = d.emulators[0] as KbItem.Rejected
        assertTrue(bad.reason, bad.reason.contains("nope"))
        assertTrue(d.emulators[1] is KbItem.Ok)
    }

    @Test
    fun `a file nested 200000 levels deep is rejected without overflowing the stack`() {
        val depth = 200_000
        val nested = "[".repeat(depth) + "]".repeat(depth)
        val reason = rejectedReason(doc(emulator("alpha", extra = ""","legacyIds":$nested""")))
        assertTrue(reason, reason.contains("nested"))
    }

    @Test
    fun `an entry nested too deeply is rejected alone`() {
        val depth = 50
        val nested = "[".repeat(depth) + "]".repeat(depth)
        val d = decoded(doc(emulator("alpha", extra = ""","legacyIds":$nested""") + "," + emulator("beta")))
        val rejected = d.emulators[0] as KbItem.Rejected
        assertTrue(rejected.reason, rejected.reason.contains("nested"))
        assertTrue(d.emulators[1] is KbItem.Ok)
    }

    @Test
    fun `brackets inside strings do not count as nesting`() {
        val d = decoded(doc(emulator("alpha").replace("Name alpha", "[".repeat(300))))
        assertTrue(d.emulators[0] is KbItem.Ok)
    }

    @Test
    fun `schema version constant is 1`() = assertEquals(1, EmulatorKbDecoder.SCHEMA_VERSION)
}
