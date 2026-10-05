package com.playfieldportal.studio

import com.playfieldportal.themekit.PfpThemeBundle
import com.playfieldportal.themekit.PfpThemeCodec
import com.playfieldportal.themekit.PfpThemeManifest
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The Studio's Lock Screen slot (format v5): pick an image, reuse the wallpaper (a video's poster
 * is its wallpaper), or clear — and whatever is set travels in the export as `lockscreen.png`.
 */
class LockScreenStudioTest {

    private suspend fun StudioViewModel.awaitIdle() {
        withTimeout(30_000) {
            delay(50)
            while (state.value.busy) delay(25)
        }
    }

    private fun pngFile(dir: File, name: String, w: Int = 40, h: Int = 24): File {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until w) for (y in 0 until h) img.setRGB(x, y, 0xFF336699.toInt())
        return File(dir, name).also { f -> f.outputStream().use { ImageIO.write(img, "png", it) } }
    }

    private suspend fun export(vm: StudioViewModel, dir: File): PfpThemeBundle {
        val out = File(dir, "out.pfptheme")
        vm.exportTo(out) { null }
        vm.awaitIdle()
        return assertNotNull(PfpThemeCodec.read(out))
    }

    @Test
    fun `an opened theme's lock screen image survives export`() = runBlocking {
        val dir = createTempDirectory("studio-lock").toFile()
        try {
            val lock = pngFile(dir, "lock.png").readBytes()
            val src = File(dir, "in.pfptheme").apply {
                writeBytes(
                    PfpThemeCodec.write(
                        PfpThemeBundle(PfpThemeManifest(name = "L", accentColor = "#112233"), null, null, lockScreen = lock),
                    ),
                )
            }
            val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
            vm.openFile(src)
            vm.awaitIdle()

            assertContentEquals(lock, vm.state.value.lockScreenPng)
            assertContentEquals(lock, export(vm, dir).lockScreen)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a picked image becomes the lock screen, and clearing removes it`() = runBlocking {
        val dir = createTempDirectory("studio-lock-pick").toFile()
        try {
            val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
            vm.setLockScreenImage(pngFile(dir, "pick.png"))
            vm.awaitIdle()
            assertNotNull(vm.state.value.lockScreenPng)
            assertNotNull(vm.state.value.lockScreenBitmap)
            assertNotNull(export(vm, dir).lockScreen)

            vm.clearLockScreen()
            assertNull(vm.state.value.lockScreenPng)
            assertNull(export(vm, dir).lockScreen)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `use wallpaper copies the theme's still - for a video theme that is its poster`() = runBlocking {
        val dir = createTempDirectory("studio-lock-wall").toFile()
        try {
            val wallpaper = pngFile(dir, "wall.png").readBytes()
            val src = File(dir, "wall.pfptheme").apply {
                writeBytes(PfpThemeCodec.write(PfpThemeBundle(PfpThemeManifest(name = "W", accentColor = "#112233"), wallpaper, null)))
            }
            val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
            vm.openFile(src)
            vm.awaitIdle()
            vm.useWallpaperForLockScreen()

            assertContentEquals(wallpaper, vm.state.value.lockScreenPng)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the lock screen image counts in the file budget`() {
        val state = StudioState(lockScreenPng = ByteArray(5_000))
        assertEquals(5_000L, ExportCheck.of(state).bytesByKind[BudgetKind.LOCK_SCREEN])
    }
}
