package com.playfieldportal.studio

import com.playfieldportal.studio.io.VideoCodecs
import com.playfieldportal.themekit.PfpThemeCodec
import com.playfieldportal.themekit.PfpThemeManifest
import com.playfieldportal.themekit.ThemeMotion
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Drives the REAL ViewModel through the motion flow: import video → confirm crop → export →
 * the motion entry round-trips byte-for-byte — and the scratch-file lifecycle leaves nothing
 * behind. Regression tests for the two ways a motion theme can silently lose its video:
 * a state transition that drops the scratch file, and an export that names the entry wrong.
 */
class ViewModelMotionRoundTripTest {

    private suspend fun StudioViewModel.awaitIdle() {
        withTimeout(30_000) {
            delay(50)
            while (state.value.busy) delay(25)
        }
    }

    @Test
    fun `imported video survives export, open, and re-export byte-for-byte`() = runBlocking {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        val dir = createTempDirectory("studio-motion-roundtrip").toFile()
        try {
            val video = File(dir, "clip.mp4")
            MotionTestMedia.writeTestMp4(video)

            // Import: the gate accepts, and frame 1 is staged as the pending wallpaper.
            vm.importVideo(video)
            vm.awaitIdle()
            assertTrue(vm.state.value.pendingWallpaper != null, "video import must stage a poster for the crop dialog")

            // Confirm the crop: BOTH the still and the motion land together.
            vm.confirmWallpaper(WallpaperPreset.ORIGINAL)
            vm.awaitIdle()
            val state = vm.state.value
            assertTrue(state.wallpaperPng != null, "poster must become the still wallpaper")
            assertTrue(state.motionFile?.isFile == true, "motion must be set on confirm")
            assertEquals("clip.mp4", state.motionFileName)

            // Export and inspect the zip through the codec.
            val bundleFile = File(dir, "out.pfptheme")
            vm.exportTo(bundleFile) { null }
            vm.awaitIdle()
            val roundTripped = PfpThemeCodec.read(bundleFile)
            assertTrue(roundTripped != null, "export must be a valid bundle: ${vm.state.value.dialog}")
            val exportedMotion = roundTripped.motion
            assertTrue(exportedMotion != null, "export must carry the motion entry")
            assertEquals("mp4", exportedMotion.extension)
            val exported = File(dir, "motion-check.mp4")
            exportedMotion.copyTo(exported.outputStream())
            assertTrue(
                video.readBytes().contentEquals(exported.readBytes()),
                "exported motion entry must be byte-identical to the source video",
            )

            // Open the bundle back, then re-export: the entry must survive both hops.
            vm.newTheme()
            vm.openFile(bundleFile)
            vm.awaitIdle()
            assertTrue(vm.state.value.motionFile?.isFile == true, "opening a motion theme must restore motion")
            val reExport = File(dir, "re.pfptheme")
            vm.exportTo(reExport) { null }
            vm.awaitIdle()
            val reopened = PfpThemeCodec.read(reExport)
            val reOpenedMotion = reopened?.motion
            assertTrue(reOpenedMotion != null, "re-export must carry the motion entry")
            assertEquals("mp4", reOpenedMotion.extension)
            val again = File(dir, "motion-check-2.mp4")
            reOpenedMotion.copyTo(again.outputStream())
            assertTrue(
                video.readBytes().contentEquals(again.readBytes()),
                "re-export must preserve the entry byte-for-byte",
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a video picked at the wallpaper picker enters the motion flow`() = runBlocking {
        // The launcher's Display settings invite "an image or a short video"; the Studio's
        // wallpaper pick must behave the same. Regression for picking an MP4 at the wallpaper
        // row and dead-ending in "not a readable image".
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        val dir = createTempDirectory("studio-motion-route").toFile()
        try {
            val video = File(dir, "clip.mp4")
            MotionTestMedia.writeTestMp4(video)

            vm.onWallpaperPicked(video)
            vm.awaitIdle()
            assertNotNull(vm.state.value.pendingWallpaper, "video must stage its poster through the crop flow")

            vm.confirmWallpaper(WallpaperPreset.ORIGINAL)
            vm.awaitIdle()
            val state = vm.state.value
            assertNotNull(state.wallpaperPng)
            assertNotNull(state.motionFile, "routed video must land as motion on confirm")
            assertEquals("clip.mp4", state.motionFileName)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a webm picked at the wallpaper picker is rejected by name, not as an unreadable image`() = runBlocking {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        val dir = createTempDirectory("studio-motion-route-reject").toFile()
        try {
            // Real MP4 bytes under a .webm name: exercises the routing and the gate's own
            // rejection, not ImageIO's "not a readable image" dead end.
            val webm = File(dir, "clip.webm")
            MotionTestMedia.writeTestMp4(webm)

            vm.onWallpaperPicked(webm)
            vm.awaitIdle()
            val dialog = vm.state.value.dialog
            assertTrue(
                dialog == StudioDialog.Error(VideoCodecs.MSG_UNSUPPORTED_SOURCE),
                "expected the motion gate's rejection, got: $dialog",
            )
            assertNull(vm.state.value.pendingWallpaper)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a still picked at the wallpaper picker stays on the plain wallpaper flow`() = runBlocking {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        val dir = createTempDirectory("studio-motion-route-still").toFile()
        try {
            val png = File(dir, "wall.png")
            val img = BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB)
            png.outputStream().use { ImageIO.write(img, "png", it) }
            val pngBytes = ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()

            vm.onWallpaperPicked(png)
            vm.awaitIdle()
            assertNotNull(vm.state.value.pendingWallpaper, "still must stage normally")

            vm.confirmWallpaper(WallpaperPreset.ORIGINAL)
            vm.awaitIdle()
            val state = vm.state.value
            assertTrue(pngBytes.contentEquals(state.wallpaperPng), "still must import unchanged")
            assertNull(state.motionFile, "a still must never set motion")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `cancelling the crop dialog leaves neither still nor motion`() = runBlocking {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        val dir = createTempDirectory("studio-motion-cancel").toFile()
        try {
            val video = File(dir, "clip.mp4")
            MotionTestMedia.writeTestMp4(video)

            vm.importVideo(video)
            vm.awaitIdle()
            vm.cancelWallpaperImport()

            val state = vm.state.value
            assertNull(state.pendingWallpaper)
            assertNull(state.motionFile, "cancel must not leave a video with no poster")
            assertNull(state.motionFileName)
            assertNull(state.wallpaperPng)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `new, clear wallpaper, and double import leave no scratch files behind`() = runBlocking {
        val dir = createTempDirectory("studio-motion-lifecycle").toFile()
        try {
            fun studioScratches(): Set<String> =
                File(System.getProperty("java.io.tmpdir"))
                    .listFiles { f -> f.name.startsWith("studio-motion-") }
                    ?.map { it.name }
                    ?.toSet()
                    ?: emptySet()

            val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
            val before = studioScratches()

            val clip1 = File(dir, "one.mp4")
            MotionTestMedia.writeTestMp4(clip1)
            val clip2 = File(dir, "two.mp4")
            MotionTestMedia.writeTestMp4(clip2)

            // Import twice (the second while the first's crop dialog is still open)...
            vm.importVideo(clip1)
            vm.awaitIdle()
            vm.importVideo(clip2)
            vm.awaitIdle()
            vm.confirmWallpaper(WallpaperPreset.ORIGINAL)
            vm.awaitIdle()
            // ...clear motion, clear wallpaper, then New.
            vm.clearMotion()
            vm.awaitIdle()
            vm.clearWallpaper()
            vm.newTheme()

            assertEquals(emptySet(), studioScratches() - before, "scratch videos must not outlive the edit session")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a 40 MB motion entry opens and survives re-export byte-for-byte`() = runBlocking {
        // The size regression this pins: opening used to go through a 64 MB in-memory byte cap
        // while a legitimate motion bundle (60 MB video alone) can exceed that, so the file was
        // rejected as "too large to be a theme bundle". Reading via PfpThemeCodec.read(File)
        // streams instead — proven here at a size no in-memory path would survive gracefully.
        // ofBytes is the sanctioned synthetic-bundle constructor; the real VM never holds bytes.
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        val dir = createTempDirectory("studio-motion-big").toFile()
        try {
            val payload = ByteArray(40 * 1024 * 1024) { (it and 0xFF).toByte() }
            val bundleFile = File(dir, "big.pfptheme")
            bundleFile.outputStream().use { out ->
                PfpThemeCodec.write(
                    com.playfieldportal.themekit.PfpThemeBundle(
                        manifest = PfpThemeManifest(name = "Big Motion", accentColor = "#FF8800"),
                        wallpaper = null,
                        preview = null,
                        motion = ThemeMotion.ofBytes(payload, "mp4"),
                    ),
                    out,
                )
            }

            vm.openFile(bundleFile)
            vm.awaitIdle()
            assertNull(vm.state.value.dialog, "a 40 MB motion bundle must open: ${vm.state.value.dialog}")
            assertTrue(vm.state.value.motionFile?.isFile == true, "motion must be spilled to a scratch file")

            // The poster rule: motion without a still must not re-export. Give it the still,
            // then verify the entry survives the open → export round trip.
            vm.update { it.copy(wallpaperPng = ByteArray(16)) }
            val reExport = File(dir, "big-re.pfptheme")
            vm.exportTo(reExport) { null }
            vm.awaitIdle()
            val motion = PfpThemeCodec.read(reExport)?.motion
            assertEquals("mp4", motion?.extension)
            val out = File(dir, "big-out.mp4")
            motion!!.copyTo(out.outputStream())
            assertTrue(payload.contentEquals(out.readBytes()), "40 MB entry must round-trip unchanged")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `clearing the still wallpaper clears motion with it`() = runBlocking {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        val dir = createTempDirectory("studio-motion-clear").toFile()
        try {
            val video = File(dir, "clip.mp4")
            MotionTestMedia.writeTestMp4(video)

            vm.importVideo(video)
            vm.awaitIdle()
            vm.confirmWallpaper(WallpaperPreset.ORIGINAL)
            vm.awaitIdle()
            assertTrue(vm.state.value.motionFile != null)

            // Motion's poster IS the still — a bundle with motion and no wallpaper is invalid,
            // so clearing the still clears the video.
            vm.clearWallpaper()
            assertNull(vm.state.value.motionFile)
            assertNull(vm.state.value.motionFileName)
            assertNull(vm.state.value.wallpaperPng)
        } finally {
            dir.deleteRecursively()
        }
    }
}
