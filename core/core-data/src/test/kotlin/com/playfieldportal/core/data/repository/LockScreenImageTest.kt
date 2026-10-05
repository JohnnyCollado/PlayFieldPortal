package com.playfieldportal.core.data.repository

import android.content.Context
import android.graphics.Bitmap
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.datastore.pfpDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The device lock screen image: cropped to the screen, handed to the system as the LOCK wallpaper
 * only, remembered (with who set it) so Settings can show it, a theme reset can undo only what a
 * theme did, and backups can carry it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LockScreenImageTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Records what would reach WallpaperManager instead of calling it. */
    private class FakeSystem(var accept: Boolean = true) : LockScreenImage.System {
        val set = mutableListOf<Bitmap>()
        var cleared = 0
        override val screenWidth = 1920
        override val screenHeight = 1080
        override fun setLock(bitmap: Bitmap): Boolean = accept.also { if (it) set += bitmap }
        override fun clearLock(): Boolean { cleared++; return true }
    }

    private val system = FakeSystem()
    private val lock = LockScreenImage(context, system)

    @Before
    fun clean() {
        runBlocking { context.pfpDataStore.edit { it.clear() } }
        File(context.filesDir, LockScreenImage.DIR).deleteRecursively()
    }

    private fun png(w: Int, h: Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        return java.io.ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Test
    fun `an image is cropped to the screen and set on the lock screen only`() = runTest {
        val result = lock.set(png(2000, 2000), LockScreenImage.Source.USER)

        assertIs<LockScreenImage.Result.Set>(result)
        val sent = system.set.single()
        assertEquals(16f / 9f, sent.width.toFloat() / sent.height, 0.02f)
        val state = lock.state.first()
        assertEquals(LockScreenImage.Source.USER, state.source)
        val path = assertNotNull(state.path)
        assertTrue(File(path).isFile && File(path).parentFile?.name == LockScreenImage.DIR, path)
    }

    @Test
    fun `a refused system call changes nothing`() = runTest {
        system.accept = false

        assertIs<LockScreenImage.Result.Failed>(lock.set(png(800, 600), LockScreenImage.Source.USER))
        assertNull(lock.state.first().path)
    }

    @Test
    fun `unreadable bytes are refused before the system is touched`() = runTest {
        assertIs<LockScreenImage.Result.Failed>(lock.set("not an image".encodeToByteArray(), LockScreenImage.Source.USER))
        assertTrue(system.set.isEmpty())
    }

    @Test
    fun `setting a new image replaces the old file`() = runTest {
        lock.set(png(800, 600), LockScreenImage.Source.USER)
        val first = lock.state.first().path!!
        lock.set(png(600, 800), LockScreenImage.Source.THEME)

        assertFalse(File(first).exists())
        assertEquals(LockScreenImage.Source.THEME, lock.state.first().source)
        assertEquals(1, File(context.filesDir, LockScreenImage.DIR).listFiles()!!.size)
    }

    @Test
    fun `reset clears the system lock screen and forgets the image`() = runTest {
        lock.set(png(800, 600), LockScreenImage.Source.USER)

        lock.clear()

        assertEquals(1, system.cleared)
        assertNull(lock.state.first().path)
        assertTrue(File(context.filesDir, LockScreenImage.DIR).listFiles().isNullOrEmpty())
    }

    @Test
    fun `a theme reset undoes only a lock screen a theme set`() = runTest {
        lock.set(png(800, 600), LockScreenImage.Source.USER)
        lock.clearIfFromTheme()
        assertEquals(0, system.cleared)
        assertNotNull(lock.state.first().path)

        lock.set(png(800, 600), LockScreenImage.Source.THEME)
        lock.clearIfFromTheme()
        assertEquals(1, system.cleared)
        assertNull(lock.state.first().path)
    }

    @Test
    fun `center crop keeps the middle at the target shape`() {
        assertEquals(LockScreenImage.Crop(0, 0, 1920, 1080), LockScreenImage.centerCrop(1920, 1080, 1920, 1080))
        // Square source on a 16:9 screen: full width, the middle band of rows.
        assertEquals(LockScreenImage.Crop(0, 219, 1000, 562), LockScreenImage.centerCrop(1000, 1000, 1920, 1080))
        // Wide source on a portrait screen: full height, the middle columns.
        assertEquals(LockScreenImage.Crop(737, 0, 506, 900), LockScreenImage.centerCrop(1980, 900, 1080, 1920))
        // No screen size known: the whole image.
        assertEquals(LockScreenImage.Crop(0, 0, 640, 480), LockScreenImage.centerCrop(640, 480, 0, 0))
    }
}
