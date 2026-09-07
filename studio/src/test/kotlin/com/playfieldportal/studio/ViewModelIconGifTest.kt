package com.playfieldportal.studio

import com.playfieldportal.themekit.PfpThemeCodec
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
 * The end-to-end animation contract: an animated GIF imported in the Studio must reach the
 * theme bundle AS A GIF — the handheld classifies `icons/<key>.gif` via the frame probe and
 * animates it with Coil's AnimatedImageDecoder. Regression test for the Studio flattening
 * every GIF to frame 1 as a png ("the animated icons added in the theme studio do not
 * animate").
 */
class ViewModelIconGifTest {

    private suspend fun StudioViewModel.awaitIdle() {
        withTimeout(30_000) {
            delay(50)
            while (state.value.busy) delay(25)
        }
    }

    private fun pngFile(dir: File, name: String): File {
        val img = BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB)
        val f = File(dir, name)
        f.outputStream().use { ImageIO.write(img, "png", it) }
        return f
    }

    @Test
    fun `an animated gif icon keeps its bytes through import, open, and export`() = runBlocking {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        val dir = createTempDirectory("studio-gif-icon").toFile()
        try {
            val gifFile = File(dir, "spin.gif")
            val gifBytes = IconGifTestMedia.animatedGif(frames = 3, delayCs = 10)
            gifFile.writeBytes(gifBytes)

            vm.setIconOverride("catbar_games", gifFile)
            vm.awaitIdle()
            assertNull(vm.state.value.dialog, "an in-cap animated gif must be accepted: ${vm.state.value.dialog}")
            val staged = vm.state.value
            assertTrue(gifBytes.contentEquals(staged.iconOverrides["catbar_games"]), "gif bytes must be preserved, not re-encoded")
            assertEquals("gif", staged.iconExtensions["catbar_games"])

            // Export → reopen → re-export: the gif entry survives both hops.
            val bundleFile = File(dir, "out.pfptheme")
            vm.exportTo(bundleFile) { null }
            vm.awaitIdle()
            val reopened = PfpThemeCodec.read(bundleFile)
            val shipped = reopened?.icons?.get("catbar_games")
            assertNotNull(shipped, "bundle must carry the icon")
            assertEquals("gif", shipped.extension, "the entry must ship as gif — png flattens it to a still on device")
            assertTrue(gifBytes.contentEquals(shipped.bytes), "entry bytes must be the original animation")

            vm.newTheme()
            vm.openFile(bundleFile)
            vm.awaitIdle()
            assertEquals("gif", vm.state.value.iconExtensions["catbar_games"])
            assertTrue(gifBytes.contentEquals(vm.state.value.iconOverrides["catbar_games"]))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a single-frame gif is authored as a png still`() = runBlocking {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        val dir = createTempDirectory("studio-gif-still").toFile()
        try {
            val gifFile = File(dir, "flat.gif")
            gifFile.writeBytes(IconGifTestMedia.singleFrameGif())

            vm.setIconOverride("catbar_games", gifFile)
            vm.awaitIdle()
            val state = vm.state.value
            assertNull(state.dialog)
            assertEquals("png", state.iconExtensions["catbar_games"], "single-frame gif must ship as png — no decoder on device")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a still png import keeps working and never sets motion-like extensions`() = runBlocking {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        val dir = createTempDirectory("studio-icon-still").toFile()
        try {
            vm.setIconOverride("catbar_games", pngFile(dir, "still.png"))
            vm.awaitIdle()
            val state = vm.state.value
            assertNull(state.dialog)
            assertEquals("png", state.iconExtensions["catbar_games"])
            assertTrue(state.iconOverrides["catbar_games"]!!.size > 0)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an over-long gif is rejected with the shared string`() = runBlocking {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        val dir = createTempDirectory("studio-gif-long").toFile()
        try {
            val gifFile = File(dir, "long.gif")
            // 110 frames x 100cs = 11s > the 10s cap, all other caps respected.
            gifFile.writeBytes(IconGifTestMedia.animatedGif(frames = 110, delayCs = 100))
            vm.setIconOverride("catbar_games", gifFile)
            vm.awaitIdle()
            assertEquals(
                com.playfieldportal.themekit.IconGifSupport.MSG_TOO_LONG,
                (vm.state.value.dialog as? StudioDialog.Error)?.message,
            )
            assertNull(vm.state.value.iconOverrides["catbar_games"])
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an oversized gif is rejected with the shared string`() = runBlocking {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        val dir = createTempDirectory("studio-gif-big").toFile()
        try {
            val gifFile = File(dir, "big.gif")
            gifFile.writeBytes(IconGifTestMedia.animatedGif(frames = 2, width = 520, height = 64))
            vm.setIconOverride("catbar_games", gifFile)
            vm.awaitIdle()
            assertEquals(
                com.playfieldportal.themekit.IconGifSupport.MSG_TOO_LARGE_RESOLUTION,
                (vm.state.value.dialog as? StudioDialog.Error)?.message,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `reset-all clears extensions along with bytes`() = runBlocking {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        val dir = createTempDirectory("studio-gif-reset").toFile()
        try {
            vm.update {
                it.copy(
                    iconOverrides = mapOf("catbar_games" to byteArrayOf(1)),
                    iconExtensions = mapOf("catbar_games" to "gif"),
                    iconBitmaps = emptyMap(),
                )
            }
            vm.clearAllIconOverrides()
            assertTrue(vm.state.value.iconExtensions.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }
}
