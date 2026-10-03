package com.playfieldportal.studio

import com.playfieldportal.studio.io.ImageCodecs
import com.playfieldportal.themekit.MotionCrop
import java.awt.image.BufferedImage
import java.io.File
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

class CropFrameTest {

    private val eps = 1e-3f

    private fun CropFrame.assertInvariants(label: String) {
        val ratio = ratio
        assertTrue(kotlin.math.abs(w / h - ratio) < eps * ratio, "$label: ratio drifted ($w x $h vs $ratio)")
        assertTrue(x >= -eps && y >= -eps, "$label: origin out of source ($x,$y)")
        assertTrue(x + w <= sourceW + eps && y + h <= sourceH + eps, "$label: rect leaves source")
        assertTrue(w >= minW - eps && w <= maxW + eps, "$label: size out of bounds ($w not in $minW..$maxW)")
    }

    private val fits = WallpaperPreset.entries

    @Test
    fun `centered starts at the largest frame of each fit`() {
        val psp = CropFrame.centered(1000, 1000, WallpaperPreset.PSP)
        assertEquals(1000f, psp.w, eps)
        assertEquals(1000f * 272 / 480, psp.h, eps)
        assertEquals(0f, psp.x, eps)
        assertEquals((1000f - psp.h) / 2, psp.y, eps)
        val tall = CropFrame.centered(500, 2000, WallpaperPreset.HD)
        assertEquals(500f, tall.w, eps)
        val original = CropFrame.centered(640, 480, WallpaperPreset.ORIGINAL)
        assertEquals(640f, original.w, eps)
        assertEquals(480f, original.h, eps)
        fits.forEach { CropFrame.centered(1234, 777, it).assertInvariants(it.name) }
    }

    @Test
    fun `ratio holds under every operation`() {
        for (fit in fits) for ((sw, sh) in listOf(1920 to 1080, 800 to 1200, 4000 to 900, 300 to 300)) {
            var f = CropFrame.centered(sw, sh, fit)
            f = f.zoomed(2.5f).also { it.assertInvariants("$fit zoom") }
            f = f.moved(37f, -91f).also { it.assertInvariants("$fit move") }
            f = f.moved(99999f, 99999f).also { it.assertInvariants("$fit move far") }
            for (corner in CropFrame.Corner.entries) {
                f = f.resizedFromCorner(corner, sw / 3f, sh / 3f).also { it.assertInvariants("$fit $corner") }
                f = f.resizedFromCorner(corner, -500f, 99999f).also { it.assertInvariants("$fit $corner wild") }
            }
            f = f.zoomed(0.01f).also { it.assertInvariants("$fit zoom out") }
            f = f.zoomed(1000f).also { it.assertInvariants("$fit zoom in") }
            f = f.centeredInSource().also { it.assertInvariants("$fit center") }
            f = f.reset().also { it.assertInvariants("$fit reset") }
        }
    }

    @Test
    fun `move clamps to the source edges and keeps size`() {
        val f = CropFrame.centered(1000, 1000, WallpaperPreset.HD).zoomed(2f)
        val moved = f.moved(-99999f, -99999f)
        assertEquals(0f, moved.x, eps)
        assertEquals(0f, moved.y, eps)
        assertEquals(f.w, moved.w, eps)
        val far = f.moved(99999f, 99999f)
        assertEquals(1000f, far.x + far.w, eps)
        assertEquals(1000f, far.y + far.h, eps)
    }

    @Test
    fun `zoom keeps the anchor fixed and clamps to the size bounds`() {
        val f = CropFrame.centered(1000, 1000, WallpaperPreset.HD).zoomed(2f)
        val ax = f.x + f.w * 0.25f
        val ay = f.y + f.h * 0.25f
        val z = f.zoomed(1.5f, ax, ay)
        assertEquals(f.w / 1.5f, z.w, eps)
        assertEquals((ax - z.x) / z.w, 0.25f, eps)
        assertEquals((ay - z.y) / z.h, 0.25f, eps)
        assertEquals(f.maxW, f.zoomed(0.0001f).w, eps)
        assertEquals(f.minW, f.zoomed(10000f).w, eps)
        // Non-finite / non-positive factors are ignored.
        assertEquals(f, f.zoomed(Float.NaN))
        assertEquals(f, f.zoomed(0f))
    }

    @Test
    fun `corner resize pins the opposite corner`() {
        val base = CropFrame.centered(2000, 2000, WallpaperPreset.HD).zoomed(2f).moved(-99999f, -99999f)
        // Frame sits at the top-left; drag its bottom-right corner outward.
        val grown = base.resizedFromCorner(CropFrame.Corner.BOTTOM_RIGHT, base.x + base.w + 200f, base.y + base.h + 50f)
        assertEquals(base.x, grown.x, eps)
        assertEquals(base.y, grown.y, eps)
        assertTrue(grown.w > base.w)
        // Drag the top-left corner: bottom-right stays put.
        val shrunk = grown.resizedFromCorner(CropFrame.Corner.TOP_LEFT, grown.x + 100f, grown.y + 100f)
        assertEquals(grown.x + grown.w, shrunk.x + shrunk.w, eps)
        assertEquals(grown.y + grown.h, shrunk.y + shrunk.h, eps)
        assertTrue(shrunk.w < grown.w)
    }

    @Test
    fun `reset returns to the centered maximum`() {
        val f = CropFrame.centered(1600, 900, WallpaperPreset.PSP).zoomed(3f).moved(40f, 10f)
        assertEquals(CropFrame.centered(1600, 900, WallpaperPreset.PSP), f.reset())
    }

    @Test
    fun `changing fit re-locks the ratio and keeps the center`() {
        val f = CropFrame.centered(2000, 1000, WallpaperPreset.HD).zoomed(2f).moved(100f, 0f)
        val g = f.withFit(WallpaperPreset.PSP)
        g.assertInvariants("withFit")
        assertEquals(WallpaperPreset.PSP, g.fit)
        assertEquals(f.x + f.w / 2, g.x + g.w / 2, 1f)
    }

    @Test
    fun `normalized rect and pixel rect round-trip`() {
        for (fit in fits) {
            val f = CropFrame.centered(1920, 1080, fit).zoomed(1.7f).moved(120f, 33f)
            val crop = f.toMotionCrop()
            assertTrue(crop.x in 0f..1f && crop.y in 0f..1f && crop.x + crop.w <= 1f + eps && crop.y + crop.h <= 1f + eps)
            val back = CropFrame.fromMotionCrop(1920, 1080, fit, crop)
            assertEquals(f.x, back.x, 0.05f)
            assertEquals(f.y, back.y, 0.05f)
            assertEquals(f.w, back.w, 0.05f)
            val px = f.pixelRect()
            assertEquals(f.x, px.x.toFloat(), 0.5f + eps)
            assertEquals(f.w, px.width.toFloat(), 1f + eps)
            assertTrue(px.x >= 0 && px.y >= 0 && px.x + px.width <= 1920 && px.y + px.height <= 1080)
        }
    }

    @Test
    fun `fromMotionCrop repairs a rect that does not match the fit ratio`() {
        val f = CropFrame.fromMotionCrop(1000, 1000, WallpaperPreset.HD, MotionCrop(0.9f, 0.9f, 0.5f, 0.5f))
        f.assertInvariants("repaired")
    }

    @Test
    fun `output size is the fit size, or the cropped pixels for Original`() {
        assertEquals(480 to 272, CropFrame.centered(1000, 1000, WallpaperPreset.PSP).outputSize())
        assertEquals(1280 to 720, CropFrame.centered(1000, 1000, WallpaperPreset.HD).outputSize())
        assertEquals(1920 to 1080, CropFrame.centered(1000, 1000, WallpaperPreset.FULL_HD).outputSize())
        assertEquals(640 to 480, CropFrame.centered(640, 480, WallpaperPreset.ORIGINAL).outputSize())
    }

    private fun gradient(w: Int, h: Int) = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).also {
        for (px in 0 until w) for (py in 0 until h) {
            // Red encodes x, green encodes y, so a bake's origin can be read back from its corner.
            it.setRGB(px, py, (0xFF shl 24) or ((px * 255 / (w - 1)) shl 16) or ((py * 255 / (h - 1)) shl 8))
        }
    }

    @Test
    fun `bake outputs the fit size for every fit`() {
        val src = gradient(1000, 700)
        for (fit in fits) {
            val f = CropFrame.centered(1000, 700, fit)
            val baked = ImageCodecs.bakeCrop(src, f)
            val (w, h) = f.outputSize()
            assertEquals(w, baked.width, "$fit width")
            assertEquals(h, baked.height, "$fit height")
        }
        val original = ImageCodecs.bakeCrop(src, CropFrame.centered(1000, 700, WallpaperPreset.ORIGINAL))
        assertEquals(1000 to 700, original.width to original.height)
    }

    @Test
    fun `bake takes exactly the framed region`() {
        val src = gradient(1000, 1000)
        val f = CropFrame.centered(1000, 1000, WallpaperPreset.HD).zoomed(4f).moved(-99999f, -99999f)
        val baked = ImageCodecs.bakeCrop(src, f)
        val topLeft = baked.getRGB(0, 0)
        assertTrue(((topLeft shr 16) and 0xFF) < 3, "top-left red should be ~0 (source x=0)")
        assertTrue(((topLeft shr 8) and 0xFF) < 3, "top-left green should be ~0 (source y=0)")
        // The right edge of a 4x zoom spans only a quarter of the source's red range.
        val topRight = baked.getRGB(baked.width - 1, 0)
        assertTrue(((topRight shr 16) and 0xFF) in 55..75, "red at right edge was ${(topRight shr 16) and 0xFF}")
    }

    // ── ViewModel ────────────────────────────────────────────────────────────

    private suspend fun StudioViewModel.awaitIdle() {
        withTimeout(30_000) {
            delay(50)
            while (state.value.busy) delay(25)
        }
    }

    private fun writePng(dir: File, name: String, image: BufferedImage): File =
        File(dir, name).also { javax.imageio.ImageIO.write(image, "png", it) }

    @Test
    fun `confirming a crop bakes the framed region and records no motionCrop without a video`() = runBlocking {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        val dir = createTempDirectory("studio-crop").toFile()
        try {
            vm.stageWallpaper(writePng(dir, "wall.png", gradient(1000, 1000)))
            vm.awaitIdle()
            val frame = CropFrame.centered(1000, 1000, WallpaperPreset.PSP).zoomed(2f).moved(-99999f, -99999f)
            vm.confirmWallpaperCrop(frame)
            vm.awaitIdle()
            val baked = ImageCodecs.decodeImage(assertNotNull(vm.state.value.wallpaperPng))
            assertNotNull(baked)
            assertEquals(480 to 272, baked.width to baked.height)
            assertNull(vm.state.value.motionCrop)
            assertNull(vm.state.value.pendingWallpaper)
            assertTrue(vm.canUndo.value, "the crop confirmation is one undoable edit")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `video crop writes motionCrop describing the same region as the poster`() = runBlocking {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        val dir = createTempDirectory("studio-crop-video").toFile()
        try {
            val video = File(dir, "clip.mp4")
            MotionTestMedia.writeTestMp4(video)
            vm.importVideo(video)
            vm.awaitIdle()
            val pending = assertNotNull(vm.state.value.pendingWallpaper)
            val frame = CropFrame.centered(pending.source.width, pending.source.height, WallpaperPreset.HD)
                .zoomed(1.5f).moved(5f, 3f)
            vm.confirmWallpaperCrop(frame)
            vm.awaitIdle()
            val state = vm.state.value
            assertEquals(frame.toMotionCrop(), state.motionCrop)
            assertEquals(frame.toMotionCrop(), vm.buildManifest(state).motionCrop)
            assertTrue(state.motionFile?.isFile == true)
            // Undo takes the crop back together with the poster and the video.
            vm.undo()
            assertNull(vm.state.value.motionCrop)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `setMotionCrop is an undoable edit and a mismatched frame is rejected`() = runBlocking {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        val crop = MotionCrop(0.1f, 0.2f, 0.5f, 0.5f)
        vm.setMotionCrop(crop)
        assertEquals(crop, vm.state.value.motionCrop)
        vm.undo()
        assertNull(vm.state.value.motionCrop)

        val dir = createTempDirectory("studio-crop-mismatch").toFile()
        try {
            vm.stageWallpaper(writePng(dir, "wall.png", gradient(400, 300)))
            vm.awaitIdle()
            vm.confirmWallpaperCrop(CropFrame.centered(10, 10, WallpaperPreset.HD))
            vm.awaitIdle()
            assertTrue(vm.state.value.dialog is StudioDialog.Error)
            assertNull(vm.state.value.wallpaperPng)
        } finally {
            dir.deleteRecursively()
        }
    }
}
