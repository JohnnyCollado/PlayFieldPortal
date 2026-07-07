package com.playfieldportal.studio.preview

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.playfieldportal.studio.StudioState
import com.playfieldportal.studio.io.ImageCodecs
import com.playfieldportal.studio.io.PtfConversion
import com.playfieldportal.themekit.PfpThemeBundle
import java.awt.EventQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.EncodedImageFormat

/**
 * Offscreen Compose renders (no window needed): the bundle's embedded `preview.png` — the
 * same PSP idea of seeing a theme before applying it — and the editable icon templates.
 */
@OptIn(ExperimentalComposeUiApi::class)
object PreviewRenderer {

    private const val DESIGN_WIDTH_DP = 960f

    /**
     * Scenes compose/render on the AWT thread even when callers are on IO: an offscreen
     * composition running concurrently with the window's corrupts shared Compose runtime
     * state (observed as NodeChain/SlotTable crashes). Renders are ~100 ms — blocking the
     * event thread that briefly is fine.
     */
    private fun <T> onAwtThread(block: () -> T): T =
        if (EventQueue.isDispatchThread()) block() else runBlocking(Dispatchers.Swing) { block() }

    /** Renders the themed XMB frame for [state] and encodes it as PNG. */
    fun renderPreviewPng(state: StudioState, widthPx: Int = 1280, heightPx: Int = 720): ByteArray = onAwtThread {
        ImageComposeScene(
            width = widthPx,
            height = heightPx,
            density = Density(widthPx / DESIGN_WIDTH_DP),
        ).use { scene ->
            // The embedded preview is always the Home frame, whatever the editor is showing.
            val model = state.copy(previewMode = com.playfieldportal.studio.PreviewMode.HOME).toPreviewModel()
            scene.setContent { XmbFrame(model) }
            scene.render(nanoTime = 0L).encodeToData(EncodedImageFormat.PNG)!!.bytes
        }
    }

    /** Preview for a just-converted bundle (batch mode) without a full editor state. */
    fun renderPreviewPng(bundle: PfpThemeBundle): ByteArray {
        val state = StudioState(
            name = bundle.manifest.name,
            accentArgb = PtfConversion.parseHexRgb(bundle.manifest.accentColor)
                ?: PtfConversion.DEFAULT_ACCENT,
            wallpaperPng = bundle.wallpaper,
            wallpaperBitmap = bundle.wallpaper?.let(ImageCodecs::toImageBitmap),
            waveStyle = bundle.manifest.waveStyle,
            layout = bundle.manifest.layout
                ?.let(com.playfieldportal.themekit.XmbLayoutSpecCodec::sanitize)
                ?: com.playfieldportal.themekit.XmbLayoutSpec.DEFAULT,
        )
        return renderPreviewPng(state)
    }

    /** The built-in glyph for [key] as a white silhouette PNG — the editable template. */
    fun rasterizeDefaultIcon(key: String, sizePx: Int): ByteArray = onAwtThread {
        ImageComposeScene(width = sizePx, height = sizePx).use { scene ->
            scene.setContent {
                Image(
                    painter = StudioIconSet.defaultPainter(key),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(Color.White, BlendMode.SrcIn),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            scene.render(nanoTime = 0L).encodeToData(EncodedImageFormat.PNG)!!.bytes
        }
    }
}
