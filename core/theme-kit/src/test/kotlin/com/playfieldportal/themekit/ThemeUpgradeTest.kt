package com.playfieldportal.themekit

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** TS-04: read diagnostics, the upgrade report, and upgrade-to-v4. */
class ThemeUpgradeTest {

    private val fx = ThemeFixtures
    private val today = "2026-10-02"

    private val fixtures = mapOf(
        "v1" to fx.v1(),
        "v2" to fx.v2(),
        "v3" to fx.v3(),
        "v4" to fx.v4(),
        "future" to fx.future(),
    )

    private fun detailed(bytes: ByteArray) = assertNotNull(PfpThemeCodec.readDetailed(bytes))

    private fun report(name: String): UpgradeReport {
        val r = detailed(fixtures.getValue(name))
        return ThemeUpgrade.report(r.bundle, r.diagnostics)
    }

    private fun manifestJson(extra: String = "") =
        """{"manifest":"pfptheme","name":"T","accentColor":"#FFFFFF"$extra}""".toByteArray()

    private fun List<String>.anyContains(s: String) = any { s in it }

    // -- read() delegation ---------------------------------------------------------------

    @Test
    fun `read and readDetailed agree on the bundle for every fixture`() {
        for ((name, bytes) in fixtures) {
            val plain = assertNotNull(PfpThemeCodec.read(bytes), name)
            assertEquals(plain, detailed(bytes).bundle, name)
        }
    }

    @Test
    fun `readDetailed returns null exactly where read does`() {
        assertNull(PfpThemeCodec.readDetailed("definitely not a zip".toByteArray()))
        assertNull(PfpThemeCodec.readDetailed(fx.zip("wallpaper.png" to fx.WALLPAPER)))
    }

    @Test
    fun `a clean bundle produces empty diagnostics`() {
        for (name in listOf("v1", "v2", "v3", "v4")) {
            val d = detailed(fixtures.getValue(name)).diagnostics
            assertTrue(d.dropped.isEmpty() && d.repaired.isEmpty() && d.undecodableFields.isEmpty(), name)
        }
    }

    // -- diagnostics ---------------------------------------------------------------------

    @Test
    fun `an over-cap icon is dropped with the over cap reason`() {
        val big = ByteArray(PfpThemeCodec.MAX_ICON_BYTES + 1024 * 1024) // 9 MB
        val r = detailed(
            fx.zip(
                "manifest.json" to manifestJson(),
                "icons/catbar_games.png" to big,
                "icons/item_add.png" to fx.ICON_PNG,
            ),
        )
        assertEquals(setOf("item_add"), r.bundle.icons.keys)
        assertEquals(listOf(DroppedEntry("icons/catbar_games.png", DropReason.OVER_CAP)), r.diagnostics.dropped)
        val report = ThemeUpgrade.report(r.bundle, r.diagnostics)
        assertTrue(report.cantRecover.anyContains("icons/catbar_games.png"), report.cantRecover.toString())
    }

    @Test
    fun `hostile names and missing extensions are dropped with their reasons`() {
        val r = detailed(
            fx.zip(
                "manifest.json" to manifestJson(),
                "../x.png" to fx.FUTURE_BLOB,
                "noext" to fx.FUTURE_BLOB,
                "extras/ok.bin" to fx.FUTURE_BLOB,
            ),
        )
        assertEquals(
            setOf(
                DroppedEntry("../x.png", DropReason.HOSTILE_NAME),
                DroppedEntry("noext", DropReason.BAD_EXTENSION),
            ),
            r.diagnostics.dropped.toSet(),
        )
        // The long-standing field still lists them.
        assertEquals(setOf("../x.png", "noext"), r.bundle.unrecoverableEntries.toSet())
        val report = ThemeUpgrade.report(r.bundle, r.diagnostics)
        assertTrue(report.cantRecover.anyContains("../x.png"))
        assertTrue(report.cantRecover.anyContains("noext"))
        assertFalse(report.cantRecover.anyContains("extras/ok.bin"))
    }

    @Test
    fun `a repeated passthrough name is reported as a duplicate`() {
        // ZipOutputStream refuses duplicate names, so write a same-length decoy and patch the
        // name bytes (local header and central directory) afterwards.
        val zip = fx.zip(
            "manifest.json" to manifestJson(),
            "extras/ok.bin" to fx.FUTURE_BLOB,
            "extras/ok.bix" to fx.FUTURE_BLOB,
        )
        val decoy = "extras/ok.bix".toByteArray()
        val real = "extras/ok.bin".toByteArray()
        for (i in 0..zip.size - decoy.size) {
            if (decoy.indices.all { zip[i + it] == decoy[it] }) real.copyInto(zip, i)
        }
        val r = detailed(zip)
        assertEquals(listOf(DroppedEntry("extras/ok.bin", DropReason.DUPLICATE)), r.diagnostics.dropped)
        assertEquals(1, r.bundle.passthrough.size)
    }

    @Test
    fun `a field of the wrong type is recovered and reported not fatal`() {
        val r = detailed(
            fx.zip("manifest.json" to manifestJson(""","waveStyle":5,"iconColor":"#112233","textColor":{"a":1}""")),
        )
        assertEquals(setOf("waveStyle", "textColor"), r.diagnostics.undecodableFields.toSet())
        assertEquals("#112233", r.bundle.manifest.iconColor)
        assertEquals(PfpThemeManifest.WAVE_ANIMATED, r.bundle.manifest.waveStyle)
        assertEquals("auto", r.bundle.manifest.textColor)
        val report = ThemeUpgrade.report(r.bundle, r.diagnostics)
        assertTrue(report.cantRecover.anyContains("waveStyle"))
        assertTrue(report.cantRecover.anyContains("textColor"))
    }

    @Test
    fun `an unreadable required field still means not a theme`() {
        assertNull(
            PfpThemeCodec.readDetailed(
                fx.zip("manifest.json" to """{"manifest":"pfptheme","name":"T","accentColor":7}""".toByteArray()),
            ),
        )
    }

    @Test
    fun `sanitizer repairs are surfaced`() {
        val r = detailed(
            fx.zip(
                "manifest.json" to manifestJson(
                    ""","description":"${"d".repeat(600)}","legibility":{"text":"hologram","icon":"contour_auto"},""" +
                        """"motionCrop":{"x":0.9,"y":0.0,"w":0.5,"h":1.0}""",
                ),
            ),
        )
        val repaired = r.diagnostics.repaired
        assertTrue(repaired.anyContains("Description"), repaired.toString())
        assertTrue(repaired.anyContains("hologram"), repaired.toString())
        assertTrue(repaired.anyContains("Motion crop"), repaired.toString())
        assertEquals(3, repaired.size)
    }

    // -- report categories per fixture ---------------------------------------------------

    @Test
    fun `v1 report`() {
        val r = report("v1")
        assertTrue(r.kept.anyContains("Wallpaper"))
        assertTrue(r.kept.anyContains("Preview"))
        assertTrue(r.kept.anyContains("layout"))
        assertTrue(r.added.anyContains("version ${PfpThemeManifest.SCHEMA_VERSION}"))
        assertTrue(r.added.anyContains("wave"))
        assertTrue(r.repaired.isEmpty())
        assertTrue(r.cantRecover.isEmpty())
    }

    @Test
    fun `v2 report counts icons`() {
        val r = report("v2")
        assertTrue(r.kept.anyContains("2 custom icons"), r.kept.toString())
        assertTrue(r.added.anyContains("version ${PfpThemeManifest.SCHEMA_VERSION}"))
    }

    @Test
    fun `v3 report keeps icons console art and motion`() {
        val r = report("v3")
        assertTrue(r.kept.anyContains("2 custom icons"), r.kept.toString())
        assertTrue(r.kept.anyContains("1 console icon"), r.kept.toString())
        assertTrue(r.kept.anyContains("Motion wallpaper (mp4)"), r.kept.toString())
        assertTrue(r.added.anyContains("was 3"))
        assertTrue(r.cantRecover.isEmpty())
    }

    @Test
    fun `report counts physical media art`() {
        val bundle = PfpThemeBundle(
            manifest = PfpThemeManifest(name = "Discs", accentColor = "#000000"),
            wallpaper = null,
            preview = null,
            icons = mediaArt("psp" to ThemeImage(byteArrayOf(1), "png"), "snes" to ThemeImage(byteArrayOf(2), "png")),
        )
        val r = ThemeUpgrade.report(bundle, ReadDiagnostics())
        assertTrue(r.kept.anyContains("2 physical media icons"), r.kept.toString())
    }

    @Test
    fun `v4 report adds only the format version`() {
        // v5 is additive (the lock screen image); a v4 theme has every v4 field already.
        val r = report("v4")
        assertEquals(listOf("Format version ${PfpThemeManifest.SCHEMA_VERSION} (was 4)"), r.added)
        assertTrue(r.repaired.isEmpty(), r.repaired.toString())
        assertTrue(r.cantRecover.isEmpty())
        assertTrue(r.kept.isNotEmpty())
    }

    @Test
    fun `future report keeps unknown data and repairs unknown enums`() {
        val r = report("future")
        assertTrue(r.kept.anyContains("4 unrecognized files"), r.kept.toString())
        assertTrue(r.kept.anyContains("anotherFutureKey"), r.kept.toString())
        assertTrue(r.kept.anyContains("someFutureField"), r.kept.toString())
        assertTrue(r.repaired.anyContains("wobbly"), r.repaired.toString())
        assertTrue(r.repaired.anyContains("hologram"), r.repaired.toString())
        assertTrue(r.cantRecover.isEmpty())
    }

    @Test
    fun `malformed colours are reported as repaired and fixed by upgrade`() {
        val r = detailed(
            fx.zip(
                "manifest.json" to """
                    {"manifest":"pfptheme","name":"T","accentColor":"3A6F","iconColor":"red","textColor":"#GGGGGG"}
                """.trimIndent().toByteArray(),
            ),
        )
        val report = ThemeUpgrade.report(r.bundle, r.diagnostics)
        assertTrue(report.repaired.anyContains("ccent"), report.repaired.toString())
        assertTrue(report.repaired.anyContains("con colo"), report.repaired.toString())
        assertTrue(report.repaired.anyContains("ext colo"), report.repaired.toString())

        val up = ThemeUpgrade.upgrade(r.bundle, today)
        assertTrue(Regex("^#[0-9A-Fa-f]{6}$").matches(up.manifest.accentColor), up.manifest.accentColor)
        assertEquals("auto", up.manifest.iconColor)
        assertEquals("auto", up.manifest.textColor)
        // And the repair is not reported twice.
        assertTrue(ThemeUpgrade.report(up, ReadDiagnostics()).repaired.isEmpty())
    }

    // -- upgrade -------------------------------------------------------------------------

    @Test
    fun `upgrade stamps the current version with legacy and exact wave and dates`() {
        val up = ThemeUpgrade.upgrade(detailed(fx.v1()).bundle, today)
        assertEquals(PfpThemeManifest.SCHEMA_VERSION, up.manifest.schemaVersion)
        assertEquals(PfpThemeManifest.WAVE_REDUCED, up.manifest.waveStyle)
        assertEquals(PfpThemeManifest.WAVE_REDUCED, up.manifest.waveStyleV4)
        assertEquals("2026-07-06", up.manifest.created, "created is preserved")
        assertEquals(today, up.manifest.updated)

        val v4 = ThemeUpgrade.upgrade(detailed(fx.v4()).bundle, today)
        assertEquals(PfpThemeManifest.WAVE_STATIC, v4.manifest.waveStyle)
        assertEquals(PfpThemeManifest.WAVE_REDUCED_STATIC, v4.manifest.waveStyleV4, "exact wave kept")
    }

    @Test
    fun `upgrade backfills created when absent`() {
        val b = detailed(fx.zip("manifest.json" to manifestJson())).bundle
        assertEquals(today, ThemeUpgrade.upgrade(b, today).manifest.created)
    }

    @Test
    fun `upgrade keeps extras and passthrough`() {
        val up = ThemeUpgrade.upgrade(detailed(fx.future()).bundle, today)
        assertEquals(PfpThemeManifest.SCHEMA_VERSION, up.manifest.schemaVersion)
        assertEquals("2031-01-01", up.manifest.created)
        val bytes = PfpThemeCodec.write(up)
        val names = ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            generateSequence { z.nextEntry }.map { it.name }.toList()
        }
        for (n in listOf("extras/thing.bin", "readme.txt", "icons/item_from_the_future.png", "sysicons/future_console.png")) {
            assertTrue(n in names, n)
        }
        val manifest = ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            generateSequence { z.nextEntry }.first { it.name == "manifest.json" }
            Json.parseToJsonElement(z.readBytes().decodeToString()).jsonObject
        }
        assertTrue("someFutureField" in manifest && "anotherFutureKey" in manifest)
        assertEquals(PfpThemeManifest.WAVE_ANIMATED, manifest.getValue("waveStyleV4").toString().trim('"'))
    }

    @Test
    fun `upgrading twice equals upgrading once`() {
        for ((name, bytes) in fixtures) {
            val once = ThemeUpgrade.upgrade(detailed(bytes).bundle, today)
            val twice = ThemeUpgrade.upgrade(once, today)
            assertEquals(once, twice, name)
            // Through a write and a fresh read as well, which is how upgrade-in-place will run.
            val reread = detailed(PfpThemeCodec.write(once)).bundle
            assertEquals(once, ThemeUpgrade.upgrade(reread, today), name)
            // Nothing left to add once upgraded.
            assertTrue(ThemeUpgrade.report(reread, ReadDiagnostics()).added.isEmpty(), name)
        }
    }

    @Test
    fun `an upgraded bundle yields the same v3 subset under the frozen v3 reader`() {
        for ((name, bytes) in fixtures) {
            val before = assertNotNull(V3EraReader.read(bytes), name)
            val up = PfpThemeCodec.write(ThemeUpgrade.upgrade(detailed(bytes).bundle, today))
            val after = assertNotNull(V3EraReader.read(up), "$name upgraded")

            assertEquals(before.manifest.name, after.manifest.name, name)
            assertEquals(before.manifest.accentColor, after.manifest.accentColor, name)
            assertEquals(before.manifest.iconColor, after.manifest.iconColor, name)
            assertEquals(before.manifest.textColor, after.manifest.textColor, name)
            assertEquals(before.manifest.layout, after.manifest.layout, name)
            assertEquals(before.manifest.source, after.manifest.source, name)
            assertEquals(before.manifest.created, after.manifest.created, name)
            if (name != "future") assertEquals(before.manifest.waveStyle, after.manifest.waveStyle, name)
            assertEquals(before.wallpaper?.toList(), after.wallpaper?.toList(), name)
            assertEquals(before.preview?.toList(), after.preview?.toList(), name)
            assertEquals(before.icons, after.icons, name)
            assertEquals(before.sysicons, after.sysicons, name)
            assertEquals(before.motionExtension, after.motionExtension, name)
        }
    }
}
