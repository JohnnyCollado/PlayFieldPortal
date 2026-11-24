package com.playfieldportal.studio

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.playfieldportal.studio.ui.StudioApp
import com.playfieldportal.studio.ui.StudioSection
import java.awt.Frame
import java.io.File
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat

/**
 * Pictures of the whole Studio window, to look at: the real [StudioApp] drawn off-screen at
 * 1920 x 1080 under the display scales a 1080p monitor is commonly set to (100%, 125%, 150%), so a
 * squeezed button or a wrapped label shows before anyone opens the app.
 * Saved to studio/build/test-screenshots/.
 *
 *     ./gradlew :studio:test -Pmockups --tests "*StudioShellRenderTest*"
 */
class StudioShellRenderTest {

    @Test
    fun `studio window at 1080p under common display scales`() {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Unconfined))
        val frame = Frame()
        val dir = File("build/test-screenshots").apply { mkdirs() }
        try {
            for (scale in listOf(1.0f, 1.25f, 1.5f)) {
                for (section in StudioSection.entries) {
                    ImageComposeScene(width = 1920, height = 1080, density = Density(scale)).use { scene ->
                        scene.setContent { StudioApp(vm, frame, initialSection = section) }
                        scene.render(0)
                        val image = scene.render(16_000_000)
                        val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("could not encode")
                        File(dir, "studio-${(scale * 100).toInt()}-${section.id}.png").writeBytes(data.bytes)
                    }
                }
            }
        } finally {
            frame.dispose()
        }
        // The same window in German at 150%: longer words are where a squeezed button shows first.
        com.playfieldportal.studio.i18n.StudioI18n.useForNow("de")
        val german = Frame()
        try {
            for (section in StudioSection.entries) {
                ImageComposeScene(width = 1920, height = 1080, density = Density(1.5f)).use { scene ->
                    scene.setContent { StudioApp(vm, german, initialSection = section) }
                    scene.render(0)
                    val image = scene.render(16_000_000)
                    val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("could not encode")
                    File(dir, "studio-de-150-${section.id}.png").writeBytes(data.bytes)
                }
            }
        } finally {
            german.dispose()
            com.playfieldportal.studio.i18n.StudioI18n.useForNow("en")
        }
        println("SCREENSHOT saved in ${dir.absolutePath}")
    }
}
