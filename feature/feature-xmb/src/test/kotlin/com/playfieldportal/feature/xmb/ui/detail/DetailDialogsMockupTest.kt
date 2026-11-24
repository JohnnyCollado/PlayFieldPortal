package com.playfieldportal.feature.xmb.ui.detail

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.playfieldportal.core.ui.components.PfpConfirmModal
import com.playfieldportal.core.ui.components.PfpModalFocus
import com.playfieldportal.core.ui.preview.PfpScreenPreview
import com.playfieldportal.feature.artwork.match.MatchProvider
import com.playfieldportal.feature.artwork.match.MetadataField
import com.playfieldportal.feature.artwork.match.MetadataPreset
import com.playfieldportal.feature.xmb.ui.collection.CollectionPickerOption
import com.playfieldportal.feature.xmb.ui.collection.CollectionPickerPanel
import com.playfieldportal.feature.xmb.ui.collection.CollectionPickerUi
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Pictures for the game-detail dialog sweep, drawn by the real composables: each old dark-box
 * dialog beside the shared style it would move to. Pictures only; nothing is asserted.
 *
 *     ./gradlew :feature:feature-xmb:testDebugUnitTest -Pmockups --tests "*DetailDialogsMockupTest*"
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "w640dp-h360dp-land-480dpi")
class DetailDialogsMockupTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val cards = listOf(
        CollectionPickerOption(1, "Favourites", checked = true),
        CollectionPickerOption(2, "Couch co-op", checked = false),
        CollectionPickerOption(3, "Finish this year", checked = true),
    )

    @Test fun `add to card, shared Options panel`() = shoot("dialog-add-to-card-new") {
        CollectionPickerPanel(CollectionPickerUi(visible = true, options = cards, selectedIndex = 1), onRowClick = {}, onDismiss = {})
    }

    private val metadata = MetadataPreviewUi(
        focus = 0,
        loading = false,
        editable = true,
        current = mapOf(MetadataField.TITLE to "Crash Bandicoot", MetadataField.DEVELOPER to "Naughty Dog"),
        effective = mapOf(MetadataField.TITLE to "Crash Bandicoot", MetadataField.DEVELOPER to "Naughty Dog"),
        presets = listOf(
            MetadataPreset(
                provider = MatchProvider.SCREENSCRAPER,
                title = "Crash Bandicoot",
                developer = "Naughty Dog",
                publisher = "Sony Computer Entertainment",
                releaseYear = 1996,
                genre = "Platform",
            ),
        ),
    )

    @Test fun `update metadata, its own page`() = shoot("dialog-metadata-page") {
        MetadataUpdatePage(
            ui = metadata,
            gameTitle = "Crash Bandicoot",
            focusFill = com.playfieldportal.core.ui.theme.menuCursorFill(),
            focusEdge = com.playfieldportal.core.ui.theme.menuCursorEdge(),
            onSelectPolicy = {}, onCycleSource = {}, onToggleField = {}, onEditField = {},
            onRevertField = {}, onApply = {}, onClose = {},
        )
    }

    /** The frame the bigger panels would move into: the one the shared confirm prompts already use. */
    @Test fun `the shared prompt frame`() = shoot("dialog-prompt-frame-reference") {
        PfpConfirmModal(
            title = "Remove Game",
            message = "Crash Bandicoot will be removed from your library. The file on the card is not touched.",
            confirmLabel = "Remove",
            focus = PfpModalFocus.CANCEL,
            onConfirm = {},
            onCancel = {},
            destructive = true,
        )
    }

    private fun shoot(name: String, dialog: @Composable () -> Unit) {
        composeRule.setContent {
            PfpScreenPreview {
                // A stand-in for the detail page behind the dialog: the storefront's deep-to-mid blue.
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0B1B3A), Color(0xFF1E4A8C))))) {
                    dialog()
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(600)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val view = composeRule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val dir = File(System.getProperty("user.dir"), "build/test-screenshots/mockups").apply { mkdirs() }
        val file = File(dir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SCREENSHOT saved: ${file.absolutePath}")
    }
}
