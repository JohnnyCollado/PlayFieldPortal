package com.playfieldportal.themekit

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Renaming a saved theme rewrites only the manifest's name. A theme made by a newer app carries
 * fields and files this one doesn't know; a rename must not be the thing that strips them.
 */
class RewriteNameTest {

    private fun rename(bytes: ByteArray, name: String): Pair<Boolean, ByteArray> {
        val out = ByteArrayOutputStream()
        val ok = PfpThemeCodec.rewriteName(ByteArrayInputStream(bytes), out, name)
        return ok to out.toByteArray()
    }

    private fun entries(zip: ByteArray): Map<String, ByteArray> {
        val map = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                map[e.name] = z.readBytes()
            }
        }
        return map
    }

    @Test
    fun `a rename keeps unknown manifest keys, the schema version, and every other entry`() {
        val original = ThemeFixtures.future()
        val (ok, renamed) = rename(original, "Renamed Theme")
        assertTrue(ok)

        val before = entries(original)
        val after = entries(renamed)
        assertEquals(before.keys, after.keys, "no entry is added or dropped")
        for (name in before.keys - "manifest.json") {
            assertTrue(before.getValue(name).contentEquals(after.getValue(name)), "$name is byte-identical")
        }

        val oldManifest = Json.parseToJsonElement(before.getValue("manifest.json").decodeToString()).jsonObject
        val newManifest = Json.parseToJsonElement(after.getValue("manifest.json").decodeToString()).jsonObject
        assertEquals("Renamed Theme", newManifest.getValue("name").jsonPrimitive.content)
        assertEquals(oldManifest - "name", newManifest - "name", "every other key survives unchanged")
    }

    @Test
    fun `a renamed theme still reads, with its passthrough intact`() {
        val (_, renamed) = rename(ThemeFixtures.future(), "Renamed Theme")
        val bundle = assertNotNull(PfpThemeCodec.read(renamed))
        assertEquals("Renamed Theme", bundle.manifest.name)
        assertTrue(bundle.manifestExtras.isNotEmpty(), "unknown keys still surface as extras")
    }

    @Test
    fun `a file without a readable manifest is refused`() {
        val notATheme = ThemeFixtures.zip("readme.txt" to "hello".toByteArray())
        val (ok, _) = rename(notATheme, "X")
        assertFalse(ok)
    }
}
