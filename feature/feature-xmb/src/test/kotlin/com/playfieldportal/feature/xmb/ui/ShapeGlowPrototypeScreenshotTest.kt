package com.playfieldportal.feature.xmb.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.playfieldportal.core.ui.components.focusGlow
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * MOCKUP, not a feature: draws a glow that follows an icon's own shape, with the Android graphics
 * calls the app would use, and saves the result as a PNG to look at. Nothing here ships.
 *
 * The glow is the icon's transparency, blurred ([Bitmap.extractAlpha] with a [BlurMaskFilter], both
 * available on every Android version), tinted and drawn under the icon. The picture is the owner's
 * device screenshot twice: as it is on the left, with the glow on the right, at the icon's measured
 * place (216 px at 110, 73 in this crop).
 *
 *     ./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*ShapeGlowPrototypeScreenshotTest*"
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "w414dp-h140dp-land-480dpi")
class ShapeGlowPrototypeScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `glow follows the icon shape`() {
        val base = File(System.getProperty("user.dir"), "../../docs/mockups/glow-base-crop.png")
        assumeTrue("needs the device screenshot crop", base.exists())
        val background = BitmapFactory.decodeFile(base.path)
        val icon = BitmapFactory.decodeResource(
            composeRule.activity.resources,
            com.playfieldportal.core.ui.R.drawable.catbar_games,
            BitmapFactory.Options().apply { inScaled = false },
        )

        composeRule.setContent {
            Canvas(Modifier.fillMaxSize()) {
                drawIntoCanvas { c ->
                    val canvas = c.nativeCanvas
                    canvas.drawBitmap(background, 0f, 0f, null)
                    canvas.drawBitmap(background, PANEL_W, 0f, null)
                    val scale = ICON_PX / icon.width
                    // Wide and soft, then tight and bright: together they read as light off the edge.
                    for ((radius, alpha, passes) in listOf(Triple(22f, 215, 1), Triple(6f, 150, 1))) {
                        val offset = IntArray(2)
                        val halo = icon.extractAlpha(
                            Paint().apply { maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL) },
                            offset,
                        )
                        val left = PANEL_W + ICON_X + offset[0] * scale
                        val top = ICON_Y + offset[1] * scale
                        val dst = RectF(left, top, left + halo.width * scale, top + halo.height * scale)
                        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                            colorFilter = PorterDuffColorFilter(GLOW_COLOR, PorterDuff.Mode.SRC_IN)
                            this.alpha = alpha
                        }
                        repeat(passes) { canvas.drawBitmap(halo, null, dst, paint) }
                    }
                    val x = PANEL_W + ICON_X
                    canvas.drawBitmap(icon, null, RectF(x, ICON_Y, x + ICON_PX, ICON_Y + ICON_PX), Paint(Paint.FILTER_BITMAP_FLAG))
                }
            }
        }
        composeRule.waitForIdle()

        val view = composeRule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val dir = File(System.getProperty("user.dir"), "build/test-screenshots/mockups").apply { mkdirs() }
        val file = File(dir, "shape-glow.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SCREENSHOT saved: ${file.absolutePath} ${view.width}x${view.height}")
    }

    /**
     * The same glow on every kind of icon the XMB shows: the eight category icons at their focused
     * size, then row icons (console art, the wrench, a memory card, the UMD) and a game tile. Drawn
     * once on a plain XMB blue and once over a busy wallpaper, the hardest case.
     */
    @Config(qualifiers = "w640dp-h360dp-land-480dpi")
    @Test
    fun `glow on every xmb icon kind`() {
        val base = File(System.getProperty("user.dir"), "../../docs/mockups/glow-wallpaper-crop.png")
        val tileFile = File(System.getProperty("user.dir"), "../../docs/mockups/glow-tile-sample.png")
        assumeTrue("needs the device screenshot crops", base.exists() && tileFile.exists())
        val wallpaper = BitmapFactory.decodeFile(base.path)
        val res = composeRule.activity.resources
        val raw = BitmapFactory.Options().apply { inScaled = false }
        fun drawable(id: Int) = BitmapFactory.decodeResource(res, id, raw)
        fun asset(name: String) = composeRule.activity.assets.open("systems/physical-media/$name.png").use(BitmapFactory::decodeStream)

        val categories = listOf(
            com.playfieldportal.core.ui.R.drawable.catbar_settings, com.playfieldportal.core.ui.R.drawable.catbar_photos, com.playfieldportal.core.ui.R.drawable.catbar_music, com.playfieldportal.core.ui.R.drawable.catbar_video,
            com.playfieldportal.core.ui.R.drawable.catbar_games, com.playfieldportal.core.ui.R.drawable.catbar_network, com.playfieldportal.core.ui.R.drawable.catbar_achievements, com.playfieldportal.core.ui.R.drawable.catbar_appstore,
        ).map(::drawable)
        val rowIcons = listOf(
            drawable(com.playfieldportal.core.ui.R.drawable.sysicon_psp), drawable(com.playfieldportal.core.ui.R.drawable.sysicon_gba), drawable(com.playfieldportal.core.ui.R.drawable.sysicon_favorites),
            drawable(com.playfieldportal.core.ui.R.drawable.sysicon_settings), asset("_default"), asset("psp"),
        )
        val tile = BitmapFactory.decodeFile(tileFile.path)

        composeRule.setContent {
            Canvas(Modifier.fillMaxSize()) {
                drawIntoCanvas { c ->
                    val canvas = c.nativeCanvas
                    val w = size.width
                    val half = size.height / 2f
                    canvas.drawColor(0xFF24457A.toInt())
                    canvas.drawBitmap(wallpaper, null, RectF(0f, half, w, size.height), Paint(Paint.FILTER_BITMAP_FLAG))
                    for (top in listOf(0f, half)) {
                        categories.forEachIndexed { i, icon ->
                            glowIcon(canvas, icon, RectF(48f + i * 232f, top + 30f, 48f + i * 232f + 216f, top + 246f))
                        }
                        rowIcons.forEachIndexed { i, icon ->
                            glowIcon(canvas, icon, RectF(60f + i * 232f, top + 300f, 60f + i * 232f + 186f, top + 486f))
                        }
                        glowIcon(canvas, tile, RectF(1480f, top + 288f, 1480f + 378f, top + 288f + 210f))
                    }
                }
            }
        }
        composeRule.waitForIdle()
        save("shape-glow-all-icons")
    }

    /**
     * The shipped modifier, not the prototype drawing above: the real category glyph with
     * `Modifier.focusGlow`, steady, on XMB blue. Proves the app's own code draws the glow.
     */
    @Config(qualifiers = "w200dp-h120dp-land-480dpi")
    @Test
    fun `the app modifier draws the glow`() {
        composeRule.setContent {
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxSize().then(
                    Modifier.drawBehind { drawRect(Color(0xFF24457A)) },
                ),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                androidx.compose.foundation.layout.Row(
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(40.dp),
                ) {
                    for (key in listOf("games", "settings", "network")) {
                        com.playfieldportal.core.ui.icons.CategoryIconGlyph(
                            iconKey = key,
                            modifier = Modifier.size(72.dp).focusGlow(active = true, key = key),
                        )
                    }
                }
            }
        }
        // Let the picture of each icon be taken (the last retake is at 1.2 s) and the glow be built.
        repeat(8) {
            // A device draws every frame; here a frame is only drawn when asked for.
            composeRule.activity.window.decorView.let { v ->
                v.draw(android.graphics.Canvas(Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)))
            }
            composeRule.mainClock.advanceTimeBy(300)
            Thread.sleep(60)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        composeRule.waitForIdle()
        save("focus-glow-app-modifier")
    }

    /** [icon] drawn into [dst] with the shape glow under it; radii are for a 216 px icon and scale with it. */
    private fun glowIcon(canvas: android.graphics.Canvas, icon: Bitmap, dst: RectF) {
        val scale = dst.width() / icon.width
        for ((screenRadius, alpha, passes) in listOf(Triple(18.6f, 215, 1), Triple(5f, 150, 1))) {
            val offset = IntArray(2)
            // The blur is applied to the source image, so the radius is taken back to source pixels.
            val halo = icon.extractAlpha(
                Paint().apply { maskFilter = BlurMaskFilter(screenRadius / scale, BlurMaskFilter.Blur.NORMAL) },
                offset,
            )
            val left = dst.left + offset[0] * scale
            val top = dst.top + offset[1] * scale
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = PorterDuffColorFilter(GLOW_COLOR, PorterDuff.Mode.SRC_IN)
                this.alpha = alpha
            }
            repeat(passes) {
                canvas.drawBitmap(halo, null, RectF(left, top, left + halo.width * scale, top + halo.height * scale), paint)
            }
        }
        canvas.drawBitmap(icon, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
    }

    private fun save(name: String) {
        val view = composeRule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val dir = File(System.getProperty("user.dir"), "build/test-screenshots/mockups").apply { mkdirs() }
        val file = File(dir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SCREENSHOT saved: ${file.absolutePath} ${view.width}x${view.height}")
    }

    private companion object {
        const val PANEL_W = 620f
        const val ICON_X = 110f
        const val ICON_Y = 73f
        const val ICON_PX = 216f
        const val GLOW_COLOR = 0xFFDDEEFF.toInt()
    }
}
