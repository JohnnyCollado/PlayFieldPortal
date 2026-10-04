package com.playfieldportal.studio.preview

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.playfieldportal.studio.StudioState
import com.playfieldportal.studio.TextColorChoice
import java.awt.EventQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Game Picker ("Add Games") is one of the screens the preview can open, and it draws. */
@OptIn(ExperimentalComposeUiApi::class)
class GamePickerPreviewTest {

    private fun render(state: StudioState): ByteArray {
        var png = ByteArray(0)
        EventQueue.invokeAndWait {
            ImageComposeScene(width = 832, height = 468, density = Density(1f)).use { scene ->
                scene.setContent { XmbFrame(state.toPreviewModel(), screen = PreviewScreen.GAME_PICKER) }
                png = scene.render().encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes
            }
        }
        return png
    }

    @Test
    fun `the open menu lists the game picker after the app picker`() {
        val entries = PreviewScreen.entries
        assertEquals("Game Picker", PreviewScreen.GAME_PICKER.label)
        assertEquals(entries.indexOf(PreviewScreen.APP_PICKER) + 1, entries.indexOf(PreviewScreen.GAME_PICKER))
    }

    @Test
    fun `the game picker renders and follows the theme's text colour`() {
        val plain = render(StudioState())
        assertTrue(plain.isNotEmpty())
        val orange = render(StudioState(textColor = TextColorChoice.Custom(0xFFFF8800.toInt())))
        assertFalse(plain.contentEquals(orange), "unselected labels repaint in the main text colour")
    }
}
