package com.playfieldportal.studio.preview

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.playfieldportal.studio.StudioState
import com.playfieldportal.studio.TextColorChoice
import java.awt.EventQueue
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * The options panel (the launcher's PspContextMenu) repaints its title and rows with the Main text
 * colour and its values, submenu arrows and group headers with the Sub colour — so the preview's
 * open panel must change with both.
 */
@OptIn(ExperimentalComposeUiApi::class)
class OptionsPanelTextColorTest {

    private val optionsOpen = PreviewNav.reduce(PreviewNavState.HOME, PreviewNavAction.OpenOptions)

    private fun render(state: StudioState): ByteArray {
        var png = ByteArray(0)
        EventQueue.invokeAndWait {
            ImageComposeScene(width = 832, height = 468, density = Density(1f)).use { scene ->
                scene.setContent { XmbFrame(state.toPreviewModel(), nav = optionsOpen) }
                png = scene.render().encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes
            }
        }
        return png
    }

    @Test
    fun `the open options panel follows the main and sub text colours`() {
        assertNotNull(optionsOpen.flyout, "Options opens the panel on the home frame")
        val plain = render(StudioState())
        val main = render(StudioState(textColor = TextColorChoice.Custom(0xFFFF8800.toInt())))
        val sub = render(StudioState(subTextColor = TextColorChoice.Custom(0xFF00AA88.toInt())))
        assertFalse(plain.contentEquals(main), "title and rows repaint in the main colour")
        assertFalse(plain.contentEquals(sub), "values and headers repaint in the sub colour")
    }
}
