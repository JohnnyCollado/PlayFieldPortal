package com.playfieldportal.core.ui.icons

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A locked Shiba coin is drawn greyscale. Custom coin art (a user pick or a theme's) goes through
 * [CustomIconSurface], so the surface must honour that filter too, or a locked coin with themed
 * art reads as earned.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CustomIconSurfaceFilterTest {

    @get:Rule
    val compose = createComposeRule()

    private val greyscale = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

    private fun solidRed(): ImageBitmap = ImageBitmap(16, 16).also { bitmap ->
        Canvas(bitmap).drawRect(Rect(0f, 0f, 16f, 16f), Paint().apply { color = Color.Red })
    }

    private fun centrePixel(filter: ColorFilter?): Color {
        val icon = CustomIcon.Still(solidRed())
        compose.setContent {
            CustomIconSurface(
                icon = icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp).testTag("icon"),
                colorFilter = filter,
            )
        }
        val pixels = compose.onNodeWithTag("icon").captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 2]
    }

    @Test
    fun `custom art keeps its colour without a filter`() {
        val c = centrePixel(null)
        assertTrue("expected red, got $c", c.red > 0.9f && c.green < 0.1f && c.blue < 0.1f)
    }

    @Test
    fun `a greyscale filter drains custom art of colour`() {
        val c = centrePixel(greyscale)
        assertEquals("red == green for grey, got $c", c.red, c.green, 0.02f)
        assertEquals("green == blue for grey, got $c", c.green, c.blue, 0.02f)
    }
}
