package com.playfieldportal.studio

import java.awt.image.BufferedImage
import java.io.File
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The accent follows a new wallpaper only while it is on Auto — a picked accent is the author's and
 * no wallpaper or video touches it. The wallpaper Fit starts from the theme's own wallpaper, else the
 * author's last choice, and only falls back to HD on a fresh install.
 */
class AccentAndFitTest {

    private val dir = createTempDirectory("studio-accent").toFile()
    private val nodes = mutableListOf<Preferences>()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
        nodes.forEach { runCatching { it.removeNode() } }
    }

    private suspend fun StudioViewModel.awaitIdle() {
        withTimeout(30_000) {
            delay(50)
            while (state.value.busy) delay(25)
        }
    }

    private fun solid(argb: Int, w: Int = 960, h: Int = 544): File {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until w) for (y in 0 until h) img.setRGB(x, y, argb)
        return File(dir, "wall-${UUID.randomUUID()}.png").also { javax.imageio.ImageIO.write(img, "png", it) }
    }

    private suspend fun StudioViewModel.setWallpaper(file: File) {
        stageWallpaper(file)
        awaitIdle()
        val pending = state.value.pendingWallpaper!!
        confirmWallpaperCrop(CropFrame.centered(pending.source.width, pending.source.height, WallpaperPreset.PSP))
        awaitIdle()
    }

    // ── Accent ───────────────────────────────────────────────────────────────

    @Test
    fun `on auto, a new wallpaper sets the accent`() = runBlocking {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        assertTrue(vm.state.value.accentAuto, "a new theme starts on Auto")
        val before = vm.state.value.accentArgb
        vm.setWallpaper(solid(0xFFD03030.toInt()))
        assertNotEquals(before, vm.state.value.accentArgb)
    }

    @Test
    fun `a picked accent survives a new wallpaper`() = runBlocking {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        vm.setAccent(0xFF63A2DB.toInt())
        assertFalse(vm.state.value.accentAuto, "picking a colour is Custom")
        vm.setWallpaper(solid(0xFFD03030.toInt()))
        assertEquals(0xFF63A2DB.toInt(), vm.state.value.accentArgb)
    }

    @Test
    fun `switching back to auto derives from the current wallpaper at once`() = runBlocking {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        vm.setWallpaper(solid(0xFFD03030.toInt()))
        val derived = vm.state.value.accentArgb
        vm.setAccent(0xFF63A2DB.toInt())
        vm.setAccentAuto(true)
        vm.awaitIdle()
        assertTrue(vm.state.value.accentAuto)
        assertEquals(derived, vm.state.value.accentArgb)
    }

    @Test
    fun `an opened theme keeps its accent as custom`() = runBlocking {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        vm.setAccent(0xFF63A2DB.toInt())
        val file = File(dir, "t.pfptheme")
        vm.exportTo(file) { null }
        vm.awaitIdle()
        vm.newTheme()
        vm.openFile(file)
        vm.awaitIdle()
        assertFalse(vm.state.value.accentAuto)
        vm.setWallpaper(solid(0xFFD03030.toInt()))
        assertEquals(0xFF63A2DB.toInt(), vm.state.value.accentArgb)
    }

    // ── Fit ──────────────────────────────────────────────────────────────────

    @Test
    fun `fit starts from the wallpaper's own size`() {
        assertEquals(WallpaperPreset.FULL_HD, WallpaperFit.initial(1920 to 1080, remembered = WallpaperPreset.HD))
        assertEquals(WallpaperPreset.PSP, WallpaperFit.initial(480 to 272, remembered = WallpaperPreset.HD))
        assertEquals(WallpaperPreset.ORIGINAL, WallpaperFit.initial(2400 to 1080, remembered = WallpaperPreset.HD))
    }

    @Test
    fun `without a wallpaper the last choice wins, and HD only on a fresh install`() {
        assertEquals(WallpaperPreset.FULL_HD, WallpaperFit.initial(null, remembered = WallpaperPreset.FULL_HD))
        assertEquals(WallpaperPreset.HD, WallpaperFit.initial(null, remembered = null))
    }

    @Test
    fun `the chosen fit is remembered across sessions`() {
        val prefs = Preferences.userRoot().node("pfp-studio-test-${UUID.randomUUID()}").also { nodes += it }
        assertEquals(null, WallpaperFitStore(prefs).remembered)
        WallpaperFitStore(prefs).remember(WallpaperPreset.FULL_HD)
        assertEquals(WallpaperPreset.FULL_HD, WallpaperFitStore(prefs).remembered)
    }
}
