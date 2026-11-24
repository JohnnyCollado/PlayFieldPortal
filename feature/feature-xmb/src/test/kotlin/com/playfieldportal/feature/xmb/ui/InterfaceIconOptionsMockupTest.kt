package com.playfieldportal.feature.xmb.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * MOCKUP, not a feature: candidate base icons for the Settings ▸ Interface section, every one a
 * drawable or Material glyph the app already ships, each with the wrench badge (sysicon_settings) in
 * the approved upper-left spot at the approved size.
 *
 *     ./gradlew :feature:feature-xmb:testDebugUnitTest -Pmockups --tests "*InterfaceIconOptionsMockupTest*"
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "w640dp-h130dp-land-480dpi")
class InterfaceIconOptionsMockupTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `interface icon options`() {
        composeRule.setContent {
            val options: List<Painter> = listOf(
                painterResource(com.playfieldportal.core.ui.R.drawable.sysicon_desktop),
                painterResource(com.playfieldportal.core.ui.R.drawable.catbar_photos),
                painterResource(com.playfieldportal.core.ui.R.drawable.sysicon_windows),
                rememberVectorPainter(Icons.Filled.Palette),
                rememberVectorPainter(Icons.Filled.Tune),
                rememberVectorPainter(Icons.Filled.Wallpaper),
                rememberVectorPainter(Icons.Filled.Dashboard),
                rememberVectorPainter(Icons.Filled.DisplaySettings),
            )
            Row(
                Modifier.fillMaxSize().background(Color(0xFF24457A)).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                options.forEachIndexed { i, painter ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(SIDE)) {
                            Image(
                                painter = painter,
                                contentDescription = null,
                                colorFilter = ColorFilter.tint(Color.White),
                                modifier = Modifier
                                    .fillMaxSize()
                                    // The badge's notch: a round hole cut out of the base icon.
                                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                                    .drawWithContent {
                                        drawContent()
                                        val s = size.width / 256f
                                        drawCircle(Color.Black, radius = 53f * s, center = Offset(45f * s, 45f * s), blendMode = BlendMode.Clear)
                                    },
                            )
                            Image(
                                painter = painterResource(com.playfieldportal.core.ui.R.drawable.sysicon_settings),
                                contentDescription = null,
                                modifier = Modifier.offset(SIDE * (2f / 256f), SIDE * (2f / 256f)).size(SIDE * (86f / 256f)),
                            )
                        }
                        Text("${i + 1}", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val view = composeRule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val dir = File(System.getProperty("user.dir"), "build/test-screenshots/mockups").apply { mkdirs() }
        val file = File(dir, "interface-icon-options.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SCREENSHOT saved: ${file.absolutePath} ${view.width}x${view.height}")
    }

    /**
     * Option 8, chosen 21 Nov 2026: Material's Display Settings glyph, white on black at 256 px, so
     * its brightness can be turned into transparency and the wrench badge added to make the asset.
     */
    @Config(qualifiers = "w256dp-h256dp-mdpi")
    @Test
    fun `display settings glyph for the interface asset`() {
        composeRule.setContent {
            Image(
                painter = rememberVectorPainter(Icons.Filled.DisplaySettings),
                contentDescription = null,
                colorFilter = ColorFilter.tint(Color.White),
                modifier = Modifier.fillMaxSize().background(Color.Black),
            )
        }
        composeRule.waitForIdle()
        val view = composeRule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val dir = File(System.getProperty("user.dir"), "build/test-screenshots/mockups").apply { mkdirs() }
        val file = File(dir, "interface-glyph-256.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SCREENSHOT saved: ${file.absolutePath} ${view.width}x${view.height}")
    }

    private companion object {
        val SIDE = 66.dp
    }
}
