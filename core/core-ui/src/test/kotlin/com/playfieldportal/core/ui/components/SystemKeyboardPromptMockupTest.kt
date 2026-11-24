package com.playfieldportal.core.ui.components

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.ui.theme.PFPTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * A picture of the shared name prompt at the Odin's size with Android's own keyboard open: the
 * compact strip resting on the keyboard. The keyboard itself is a grey stand-in of the height
 * given, since Robolectric draws no system keyboard. Pictures only.
 *
 *     ./gradlew :core:core-ui:testDebugUnitTest -Pmockups --tests "*SystemKeyboardPromptMockupTest*"
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w832dp-h468dp-land-xhdpi")
class SystemKeyboardPromptMockupTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // About what Gboard takes in landscape on the Odin's 468dp-tall screen (an estimate, not measured).
    @Test fun `name prompt over the Android keyboard`() = shoot("name-prompt-android-keyboard", 210.dp, "Android keyboard (stand-in)")

    // The room PFP's keyboard reserves (VirtualKeyboardBottomReserve, 328dp): the same space above.
    @Test fun `name prompt over PFP's keyboard`() = shoot("name-prompt-pfp-keyboard", 328.dp, "PFP keyboard and its hints (stand-in)")

    private fun shoot(name: String, keyboard: androidx.compose.ui.unit.Dp, label: String) {
        composeRule.setContent {
            PFPTheme {
                CompositionLocalProvider(LocalSystemKeyboardHeightOverride provides keyboard) {
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0B1B3A), Color(0xFF1E4A8C))))) {
                        PfpTextEntryModal(
                            title = "New Custom Memory Card",
                            value = "",
                            onValueChange = {},
                            focus = PfpModalFocus.FIELD,
                            onFocusChange = {},
                            onConfirm = {},
                            onCancel = {},
                            placeholder = "e.g. RPGs, Currently Playing",
                            confirmLabel = "Create",
                        )
                        Box(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(keyboard).background(Color(0xFF2B2B2B)),
                            contentAlignment = Alignment.Center,
                        ) { Text(label, color = Color(0xFF9A9A9A), fontSize = 14.sp) }
                    }
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
