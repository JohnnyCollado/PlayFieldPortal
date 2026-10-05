package com.playfieldportal.themekit

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** TS-03: unknown manifest keys and unknown zip entries survive read -> write, safely. */
class PassthroughTest {

    private val fx = ThemeFixtures

    private fun entryBytes(entry: PassthroughEntry): ByteArray =
        ByteArrayOutputStream().also { entry.copyTo(it) }.toByteArray()

    private fun zipNames(bytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) { names += e.name; e = z.nextEntry }
        }
        return names
    }

    private fun zipEntry(bytes: ByteArray, name: String): ByteArray? {
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (e.name == name) return z.readBytes()
                e = z.nextEntry
            }
        }
        return null
    }

    private fun manifestJson(bytes: ByteArray): JsonObject =
        Json.parseToJsonElement(zipEntry(bytes, "manifest.json")!!.decodeToString()).jsonObject

    private fun bundleWithPassthrough(vararg names: String): PfpThemeBundle =
        PfpThemeBundle(
            manifest = PfpThemeManifest(name = "P", accentColor = "#000000"),
            wallpaper = null,
            preview = null,
            passthrough = names.map { PassthroughEntry.ofBytes(it, fx.FUTURE_BLOB) },
        )

    @Test
    fun `future fixture exposes unknown manifest keys and unknown entries`() {
        val bundle = assertNotNull(PfpThemeCodec.read(fx.future()))

        assertEquals(
            setOf("someFutureField", "anotherFutureKey"),
            bundle.manifestExtras.keys,
        )
        assertEquals(JsonPrimitive("kept"), bundle.manifestExtras["anotherFutureKey"])
        assertEquals(
            setOf(
                "icons/item_from_the_future.png",
                "sysicons/future_console.png",
                "extras/thing.bin",
                "readme.txt",
            ),
            bundle.passthrough.map { it.name }.toSet(),
        )
        assertTrue(bundle.unrecoverableEntries.isEmpty())
    }

    @Test
    fun `future fixture round-trips manifest extras and every unknown entry`() {
        val first = assertNotNull(PfpThemeCodec.read(fx.future()))
        val written = PfpThemeCodec.write(first)
        val second = assertNotNull(PfpThemeCodec.read(written))

        assertEquals(first.manifestExtras, second.manifestExtras)
        assertEquals(first.passthrough.map { it.name }.toSet(), second.passthrough.map { it.name }.toSet())
        for (entry in second.passthrough) {
            assertContentEquals(fx.FUTURE_BLOB.takeIf { entry.name != "icons/item_from_the_future.png" && entry.name != "sysicons/future_console.png" }
                ?: if (entry.name.startsWith("icons/")) fx.ICON_PNG else fx.SYSICON_PNG, entryBytes(entry), entry.name)
        }
        // Write stamps the current schema version; nothing else differs.
        assertEquals(first.copy(manifest = first.manifest.copy(schemaVersion = PfpThemeManifest.SCHEMA_VERSION)), second)
    }

    @Test
    fun `typed manifest fields win over an extras collision on write`() {
        val bundle = PfpThemeBundle(
            manifest = PfpThemeManifest(name = "Typed", accentColor = "#123456"),
            wallpaper = null,
            preview = null,
            manifestExtras = JsonObject(mapOf("name" to JsonPrimitive("Impostor"), "extra" to JsonPrimitive(1))),
        )
        val manifest = manifestJson(PfpThemeCodec.write(bundle))

        assertEquals(JsonPrimitive("Typed"), manifest["name"])
        assertEquals(JsonPrimitive(1), manifest["extra"])
    }

    @Test
    fun `known keys never leak into manifestExtras`() {
        val bundle = assertNotNull(PfpThemeCodec.read(fx.v4()))
        assertTrue(bundle.manifestExtras.isEmpty(), "extras were ${bundle.manifestExtras.keys}")
    }

    @Test
    fun `passthrough is stream-backed and reads nothing until copied`() {
        val bytes = fx.future()
        var opens = 0
        val source = { opens++; ByteArrayInputStream(bytes) }

        val bundle = assertNotNull(
            PfpThemeCodec.read(ByteArrayInputStream(bytes), null) { name ->
                PfpThemeCodec.passthroughFrom(source, name)
            },
        )
        assertEquals(0, opens, "reading the bundle must not reopen the source")
        assertEquals(4, bundle.passthrough.size)

        val entry = bundle.passthrough.first { it.name == "extras/thing.bin" }
        assertContentEquals(fx.FUTURE_BLOB, entryBytes(entry))
        assertEquals(1, opens)
    }

    @Test
    fun `plain stream read still reports names but holds no passthrough bytes`() {
        val bundle = assertNotNull(PfpThemeCodec.read(ByteArrayInputStream(fx.future())))
        assertTrue(bundle.passthrough.isEmpty())
        assertTrue(bundle.manifestExtras.isNotEmpty())
    }

    @Test
    fun `hostile and malformed names are dropped on read and listed`() {
        val hostile = listOf(
            "../x.png",
            "/abs.png",
            "a\\b.png",
            "a/b/c.png",
            "extras/" + "a".repeat(100) + ".bin",
            "Upper.png",
            "icons/../evil.png",
            "noext",
        )
        val bytes = fx.zip(
            "manifest.json" to """{"manifest":"pfptheme","name":"H","accentColor":"#FFFFFF"}""".toByteArray(),
            *hostile.map { it to fx.FUTURE_BLOB }.toTypedArray(),
            "extras/ok.bin" to fx.FUTURE_BLOB,
        )
        val bundle = assertNotNull(PfpThemeCodec.read(bytes))

        assertEquals(listOf("extras/ok.bin"), bundle.passthrough.map { it.name })
        assertEquals(hostile.toSet(), bundle.unrecoverableEntries.toSet())
    }

    @Test
    fun `hostile passthrough names never reach the written zip`() {
        val bundle = bundleWithPassthrough(
            "../x.png", "/abs.png", "a\\b.png", "a/b/c.png", "x/" + "a".repeat(100) + ".bin", "extras/ok.bin",
        )
        val names = zipNames(PfpThemeCodec.write(bundle))

        assertEquals(listOf("manifest.json", "extras/ok.bin"), names)
    }

    @Test
    fun `a passthrough name colliding with a registered entry is dropped`() {
        val bundle = bundleWithPassthrough(
            "manifest.json", "wallpaper.png", "preview.png", "lockscreen.png", "motion.mp4",
            "icons/catbar_games.png", "sysicons/psx.png", "extras/ok.bin",
        )
        val written = PfpThemeCodec.write(bundle)

        assertEquals(listOf("manifest.json", "extras/ok.bin"), zipNames(written))
        assertContentEquals(fx.FUTURE_BLOB, zipEntry(written, "extras/ok.bin"))
        // And manifest.json is the real manifest, not the passthrough blob.
        assertEquals(JsonPrimitive("pfptheme"), manifestJson(written)["manifest"])
    }

    @Test
    fun `duplicate passthrough names are written once`() {
        val written = PfpThemeCodec.write(bundleWithPassthrough("extras/ok.bin", "extras/ok.bin"))
        assertEquals(listOf("manifest.json", "extras/ok.bin"), zipNames(written))
    }

    @Test
    fun `write still drops unregistered icon keys held in icons`() {
        val bundle = PfpThemeBundle(
            manifest = PfpThemeManifest(name = "P", accentColor = "#000000"),
            wallpaper = null,
            preview = null,
            icons = mapOf("not_a_slot" to ThemeImage(fx.ICON_PNG, "png")),
        )
        assertFalse(zipNames(PfpThemeCodec.write(bundle)).any { it.contains("not_a_slot") })
    }

    @Test
    fun `equality covers extras and passthrough names`() {
        val a = bundleWithPassthrough("extras/a.bin")
        assertEquals(a, bundleWithPassthrough("extras/a.bin"))
        assertFalse(a == bundleWithPassthrough("extras/b.bin"))
        assertFalse(a == a.copy(manifestExtras = JsonObject(mapOf("k" to JsonPrimitive(1)))))
    }
}
