package com.playfieldportal.feature.xmb.ui.detail

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.playfieldportal.core.domain.achievement.ShibaTier
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * A picture of the four Shiba coins as the app draws them (the real [ShibaCoinIcon]) at the sizes
 * they appear at on the Odin: 20, 32, 62 and 96 dp. To look at, since the coins are pixel art shown
 * at sizes that are not whole multiples of their 64 px.
 *
 *     ./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*ShibaCoinSizesRenderTest*"
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "w520dp-h280dp-land-480dpi")
class ShibaCoinSizesRenderTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `coins at the sizes the app shows them`() {
        composeRule.setContent {
            Column(
                Modifier.fillMaxSize().background(Color(0xFF12284A)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (size in listOf(20, 32, 62, 96)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        for (tier in listOf(ShibaTier.BRONZE, ShibaTier.SILVER, ShibaTier.GOLD, ShibaTier.PLATINUM)) {
                            ShibaCoinIcon(tier, Modifier.size(size.dp))
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val view = composeRule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val dir = File(System.getProperty("user.dir"), "build/test-screenshots/mockups").apply { mkdirs() }
        val file = File(dir, "shiba-coins-in-app.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SCREENSHOT saved: ${file.absolutePath} ${view.width}x${view.height}")
    }
}
