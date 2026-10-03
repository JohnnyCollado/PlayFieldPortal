package com.playfieldportal.studio

import com.playfieldportal.themekit.PfpThemeBundle
import com.playfieldportal.themekit.PfpThemeCodec
import com.playfieldportal.themekit.PfpThemeManifest
import com.playfieldportal.themekit.ThemeFixtures
import com.playfieldportal.themekit.ThemeLegibility
import com.playfieldportal.themekit.ThemeMotion
import com.playfieldportal.themekit.MotionCrop
import com.playfieldportal.themekit.WaveStyles
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Open a file in the real ViewModel, export it, read both back: nothing the format carries may
 * be lost on the way (bug c: sysicons dropped by hydrate; bug d: `created` overwritten), and
 * everything this build has no typed field for must ride along (extras, passthrough, media).
 */
class ViewModelLosslessRoundTripTest {

    private suspend fun StudioViewModel.awaitIdle() {
        withTimeout(30_000) {
            delay(50)
            while (state.value.busy) delay(25)
        }
    }

    private fun bytesOf(copy: (java.io.OutputStream) -> Long): ByteArray =
        ByteArrayOutputStream().also { copy(it) }.toByteArray()

    private fun pngFile(dir: File, name: String): File {
        val img = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until 32) for (y in 0 until 32) img.setRGB(x, y, 0xFF3366AA.toInt())
        return File(dir, name).also { f -> f.outputStream().use { ImageIO.write(img, "png", it) } }
    }

    /** Fields the export legitimately rewrites; wave is compared by resolved value instead. */
    private fun PfpThemeManifest.normalized() =
        copy(updated = null, schemaVersion = 0, waveStyle = "", waveStyleV4 = null)

    private fun assertSameBundle(label: String, a: PfpThemeBundle, b: PfpThemeBundle) {
        assertEquals(a.manifest.normalized(), b.manifest.normalized(), "$label: manifest")
        assertEquals(WaveStyles.resolveExact(a.manifest), WaveStyles.resolveExact(b.manifest), "$label: wave")
        assertEquals(a.manifestExtras, b.manifestExtras, "$label: manifest extras")
        assertContentEquals(a.wallpaper, b.wallpaper, "$label: wallpaper")
        assertContentEquals(a.preview, b.preview, "$label: preview")
        // Icons for parts themes no longer customize (the status strip, menus...) are left out on open.
        assertEquals(a.icons.filterKeys(EditableSlots::isEditable), b.icons, "$label: icons")
        assertEquals(a.sysicons, b.sysicons, "$label: sysicons")
        assertEquals(a.motion, b.motion, "$label: motion")
        a.motion?.let { assertContentEquals(bytesOf(it::copyTo), bytesOf(b.motion!!::copyTo), "$label: motion bytes") }
        assertEquals(a.media, b.media, "$label: media keys")
        for ((key, m) in a.media) {
            assertContentEquals(bytesOf(m::copyTo), bytesOf(b.media.getValue(key)::copyTo), "$label: media $key")
        }
        assertEquals(a.passthrough.map { it.name }, b.passthrough.map { it.name }, "$label: passthrough names")
        for ((i, p) in a.passthrough.withIndex()) {
            assertContentEquals(bytesOf(p::copyTo), bytesOf(b.passthrough[i]::copyTo), "$label: passthrough ${p.name}")
        }
    }

    /** Opens [original], exports, and returns (read of original, read of export, the VM). */
    private suspend fun roundTrip(
        dir: File,
        label: String,
        original: ByteArray,
        deleteSourceBeforeExport: Boolean = false,
    ): Triple<PfpThemeBundle, PfpThemeBundle, StudioViewModel> {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        val src = File(dir, "$label-in.pfptheme").also { it.writeBytes(original) }
        val before = assertNotNull(PfpThemeCodec.read(original), "$label: fixture must read") // in-memory: outlives the file
        vm.openFile(src)
        vm.awaitIdle()
        assertNull(vm.state.value.dialog, "$label: open must not raise a dialog")
        if (deleteSourceBeforeExport) assertTrue(src.delete())
        val out = File(dir, "$label-out.pfptheme")
        vm.exportTo(out) { null }
        vm.awaitIdle()
        assertNull(vm.state.value.dialog, "$label: export must not raise a dialog: ${vm.state.value.dialog}")
        val after = assertNotNull(PfpThemeCodec.read(out), "$label: export must read")
        return Triple(before, after, vm)
    }

    @Test
    fun `every golden fixture opens and re-exports losslessly`() = runBlocking {
        val dir = createTempDirectory("studio-lossless").toFile()
        try {
            val fixtures = mapOf(
                "v1" to ThemeFixtures.v1(),
                "v2" to ThemeFixtures.v2(),
                "v3" to ThemeFixtures.v3(),
                "v4" to ThemeFixtures.v4(),
                "future" to ThemeFixtures.future(),
            )
            for ((label, bytes) in fixtures) {
                val (before, after, _) = roundTrip(dir, label, bytes)
                assertSameBundle(label, before, after)
                assertEquals(PfpThemeManifest.SCHEMA_VERSION, after.manifest.schemaVersion, "$label: written as v4")
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `bug c - a v3 theme keeps its console icons through open and export`() = runBlocking {
        val dir = createTempDirectory("studio-lossless-c").toFile()
        try {
            val (before, after, vm) = roundTrip(dir, "v3", ThemeFixtures.v3())
            assertEquals(setOf("psx"), before.sysicons.keys)
            assertEquals(setOf("psx"), after.sysicons.keys, "sysicons/psx must survive")
            assertContentEquals(ThemeFixtures.SYSICON_PNG, after.sysicons.getValue("psx").bytes)
            assertEquals(setOf("sysicon_psx"), vm.state.value.sysiconOverrides.keys)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `bug d - created is preserved and updated is stamped`() = runBlocking {
        val dir = createTempDirectory("studio-lossless-d").toFile()
        try {
            val (_, after, _) = roundTrip(dir, "v3", ThemeFixtures.v3())
            assertEquals("2026-08-01", after.manifest.created, "created must never be overwritten")
            assertEquals(LocalDate.now().toString(), after.manifest.updated)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a brand new theme gets created and updated from today`() {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        val m = vm.buildManifest(StudioState(name = "Fresh"), today = LocalDate.of(2026, 10, 2))
        assertEquals("2026-10-02", m.created)
        assertEquals("2026-10-02", m.updated)
        assertEquals(PfpThemeManifest.SCHEMA_VERSION, m.schemaVersion)
    }

    @Test
    fun `a future file keeps extras and passthrough even after the source is deleted`() = runBlocking {
        val dir = createTempDirectory("studio-lossless-future").toFile()
        try {
            val (before, after, _) = roundTrip(dir, "future", ThemeFixtures.future(), deleteSourceBeforeExport = true)
            assertTrue(before.manifestExtras.keys.containsAll(setOf("someFutureField", "anotherFutureKey")))
            assertEquals(before.manifestExtras, after.manifestExtras)
            assertEquals(
                setOf("extras/thing.bin", "readme.txt", "icons/item_from_the_future.png", "sysicons/future_console.png"),
                after.passthrough.map { it.name }.toSet(),
            )
            assertSameBundle("future", before, after)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `v4 media survives from a scratch copy after the source is deleted`() = runBlocking {
        val dir = createTempDirectory("studio-lossless-media").toFile()
        try {
            val (_, after, vm) = roundTrip(dir, "v4", ThemeFixtures.v4(), deleteSourceBeforeExport = true)
            assertEquals(setOf("sound_scroll", "ambience_audio", "boot_video"), vm.state.value.mediaFiles.keys)
            assertContentEquals(ThemeFixtures.BOOT_MP4, bytesOf(after.media.getValue("boot_video")::copyTo))
            assertContentEquals(ThemeFixtures.AMBIENCE_MP3, bytesOf(after.media.getValue("ambience_audio")::copyTo))
            assertContentEquals(ThemeFixtures.SOUND_OGG, bytesOf(after.media.getValue("sound_scroll")::copyTo))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `opening a v4 file fills every v4 state field`() = runBlocking {
        val dir = createTempDirectory("studio-lossless-state").toFile()
        try {
            val (_, _, vm) = roundTrip(dir, "v4", ThemeFixtures.v4())
            val s = vm.state.value
            assertEquals("Jane", s.author)
            assertEquals("Neon over rain", s.description)
            assertEquals("2026-07-06", s.created)
            assertEquals(PfpThemeManifest.WAVE_REDUCED_STATIC, s.waveStyle)
            assertEquals(false, s.textColorExact)
            assertEquals(ThemeLegibility("auto", "contour_auto", false), s.legibility)
            assertEquals(MotionCrop(0.1f, 0.0f, 0.8f, 1.0f), s.motionCrop)
            assertEquals(4, s.schemaVersion)
            assertNotNull(s.upgradeReport)
            assertNotNull(s.previewPng, "preview frame kept")
            Unit
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a v3 file reports its old schema version and an upgrade report`() = runBlocking {
        val dir = createTempDirectory("studio-lossless-v3state").toFile()
        try {
            val (_, _, vm) = roundTrip(dir, "v3", ThemeFixtures.v3())
            assertEquals(3, vm.state.value.schemaVersion)
            val report = assertNotNull(vm.state.value.upgradeReport)
            assertTrue(report.added.any { it.startsWith("Format version 4") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `setIconOverride routes a console key to sysicons and exports it`() = runBlocking {
        val dir = createTempDirectory("studio-lossless-console").toFile()
        try {
            val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
            vm.setIconOverride("sysicon_cps1", pngFile(dir, "console.png"))
            vm.awaitIdle()
            vm.setIconOverride("catbar_games", pngFile(dir, "cat.png"))
            vm.awaitIdle()
            val s = vm.state.value
            assertEquals(setOf("sysicon_cps1"), s.sysiconOverrides.keys)
            assertEquals(setOf("catbar_games"), s.iconOverrides.keys)
            assertEquals(setOf("sysicon_cps1"), s.sysiconBitmaps.keys)

            val out = File(dir, "out.pfptheme")
            vm.exportTo(out) { null }
            vm.awaitIdle()
            val read = assertNotNull(PfpThemeCodec.read(out))
            assertEquals(setOf("cps1"), read.sysicons.keys)
            assertEquals(setOf("catbar_games"), read.icons.keys)

            vm.clearIconOverride("sysicon_cps1")
            assertTrue(vm.state.value.sysiconOverrides.isEmpty())
            assertTrue(vm.state.value.sysiconBitmaps.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `new theme and re-open delete media and passthrough scratch files`() = runBlocking {
        val dir = createTempDirectory("studio-lossless-scratch").toFile()
        try {
            val src = File(dir, "in.pfptheme").also { it.writeBytes(ThemeFixtures.future()) }
            val v4 = File(dir, "v4.pfptheme").also { it.writeBytes(ThemeFixtures.v4()) }
            val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))

            vm.openFile(src)
            vm.awaitIdle()
            val passthrough = vm.state.value.passthroughFiles.values.toList()
            assertTrue(passthrough.isNotEmpty() && passthrough.all { it.isFile })

            vm.openFile(v4)
            vm.awaitIdle()
            assertTrue(passthrough.none { it.exists() }, "re-open must delete the outgoing passthrough scratch")
            val media = vm.state.value.mediaFiles.values.toList()
            assertTrue(media.isNotEmpty() && media.all { it.isFile })

            vm.newTheme()
            assertFalse(media.any { it.exists() }, "New must delete media scratch")
            assertTrue(vm.state.value.mediaFiles.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `wave style exports legacy fallback beside the exact value`() {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        val m = vm.buildManifest(StudioState(waveStyle = PfpThemeManifest.WAVE_REDUCED_STATIC))
        assertEquals(PfpThemeManifest.WAVE_STATIC, m.waveStyle)
        assertEquals(PfpThemeManifest.WAVE_REDUCED_STATIC, m.waveStyleV4)
    }

    @Test
    fun `motion in a v4 file survives by scratch copy after the source is deleted`() = runBlocking {
        // The v4 fixture's motion is opaque bytes; the VM must carry it by scratch file all the same.
        val dir = createTempDirectory("studio-lossless-motion").toFile()
        try {
            val (_, after, vm) = roundTrip(dir, "v4", ThemeFixtures.v4(), deleteSourceBeforeExport = true)
            val motion: ThemeMotion = assertNotNull(after.motion)
            assertContentEquals(ThemeFixtures.MOTION_MP4, bytesOf(motion::copyTo))
            assertNotNull(vm.state.value.motionFile)
            Unit
        } finally {
            dir.deleteRecursively()
        }
    }
}
