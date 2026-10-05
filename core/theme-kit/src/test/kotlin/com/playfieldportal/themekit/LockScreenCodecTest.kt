package com.playfieldportal.themekit

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Schema v5 (additive): a theme may carry a still image for the device's lock screen as
 * `lockscreen.png`. Older readers keep it as an unknown entry and ignore it.
 */
class LockScreenCodecTest {

    private val manifest = PfpThemeManifest(name = "Midgar", accentColor = "#3AC4A0")
    private val lock = ByteArray(300) { (it * 7).toByte() }

    private fun names(bytes: ByteArray): List<String> = buildList {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) add(zip.nextEntry?.name ?: break)
        }
    }

    @Test
    fun `the format is version 5`() {
        assertEquals(5, PfpThemeManifest.SCHEMA_VERSION)
    }

    @Test
    fun `a lock screen image round-trips as lockscreen png`() {
        val bundle = PfpThemeBundle(manifest = manifest, wallpaper = ByteArray(10), preview = null, lockScreen = lock)

        val written = PfpThemeCodec.write(bundle)

        assertTrue("lockscreen.png" in names(written), names(written).toString())
        val read = assertNotNull(PfpThemeCodec.read(written))
        assertContentEquals(lock, read.lockScreen)
        assertEquals(bundle, read)
    }

    @Test
    fun `a theme without one writes no entry and reads null`() {
        val written = PfpThemeCodec.write(PfpThemeBundle(manifest = manifest, wallpaper = null, preview = null))
        assertFalse("lockscreen.png" in names(written))
        assertNull(assertNotNull(PfpThemeCodec.read(written)).lockScreen)
    }

    @Test
    fun `the lock screen image is part of equality`() {
        val a = PfpThemeBundle(manifest = manifest, wallpaper = null, preview = null, lockScreen = lock)
        val b = a.copy(lockScreen = lock.copyOf().also { it[0] = 99 })
        assertFalse(a == b)
        assertEquals(a, a.copy(lockScreen = lock.copyOf()))
        assertEquals(a.hashCode(), a.copy(lockScreen = lock.copyOf()).hashCode())
    }

    @Test
    fun `the upgrade report lists it as kept`() {
        val bundle = PfpThemeBundle(manifest = manifest, wallpaper = null, preview = null, lockScreen = lock)
        val report = ThemeUpgrade.report(bundle, ReadDiagnostics())
        assertTrue("Lock screen image" in report.kept, report.kept.toString())
    }

    @Test
    fun `contents says whether a theme carries one, without inflating anything`() {
        val with = File.createTempFile("lock", ".pfptheme").apply {
            deleteOnExit()
            writeBytes(PfpThemeCodec.write(PfpThemeBundle(manifest = manifest, wallpaper = null, preview = null, lockScreen = lock)))
        }
        val without = File.createTempFile("nolock", ".pfptheme").apply {
            deleteOnExit()
            writeBytes(PfpThemeCodec.write(PfpThemeBundle(manifest = manifest, wallpaper = null, preview = null)))
        }
        assertTrue(assertNotNull(PfpThemeCodec.contents(with)).hasLockScreen)
        assertFalse(assertNotNull(PfpThemeCodec.contents(without)).hasLockScreen)
    }
}
