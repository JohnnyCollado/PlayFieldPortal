package com.playfieldportal.core.ui.keyboard

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.ui.components.LocalSystemKeyboardHeightOverride
import com.playfieldportal.core.ui.components.PfpModalFocus
import com.playfieldportal.core.ui.components.PfpTextEntryModal
import com.playfieldportal.core.ui.theme.PFPTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * PFP's keyboard sized to the system keyboard's height, Gboard style (hints on top where Gboard has
 * its toolbar, four rows, digits on ?123), on several screens, with the name prompt above it. The
 * system keyboard is taken at its default share of the screen, 50.6% (Gboard on the Odin, 237dp of
 * 468); an orange line marks where its top would be. Pictures only.
 *
 *     ./gradlew :core:core-ui:testDebugUnitTest -Pmockups --tests "*KeyboardHeightMatchMockupTest*"
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyboardHeightMatchMockupTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Config(sdk = [34], qualifiers = "w832dp-h468dp-land-xhdpi")
    @Test fun `odin 832x468`() = shoot("kb-match-odin-832x468")

    @Config(sdk = [34], qualifiers = "w915dp-h412dp-land-xhdpi")
    @Test fun `phone 915x412`() = shoot("kb-match-phone-915x412")

    @Config(sdk = [34], qualifiers = "w640dp-h480dp-land-xhdpi")
    @Test fun `four by three 640x480`() = shoot("kb-match-4x3-640x480")

    @Config(sdk = [34], qualifiers = "w640dp-h360dp-land-xhdpi")
    @Test fun `small 640x360`() = shoot("kb-match-small-640x360")

    @Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
    @Test fun `tablet 1280x800`() = shoot("kb-match-tablet-1280x800")

    private fun shoot(name: String) {
        composeRule.setContent {
            PFPTheme {
                BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0B1B3A), Color(0xFF1E4A8C))))) {
                    val android = maxHeight * SystemKeyboardShare.LANDSCAPE_DEFAULT
                    val size = bottomKeyboardSize(maxHeight, SystemKeyboardShare.LANDSCAPE_DEFAULT)
                    val key = size.keyHeight
                    val block = size.reserve
                    CompositionLocalProvider(LocalSystemKeyboardHeightOverride provides block) {
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
                    }
                    // The same arrangement VirtualKeyboardOverlay draws at the bottom.
                    Column(
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        VirtualKeyboardHintBar(Modifier.height(36.dp))
                        VirtualKeyboardPanel(VirtualKeyboardReducer.open(""), keyHeight = key)
                    }
                    // Where the system keyboard's top would be.
                    Box(Modifier.fillMaxWidth().height(2.dp).offset(y = maxHeight - android).background(Color(0xFFFF9F1C)))
                    Text(
                        "${maxWidth.value.toInt()}×${maxHeight.value.toInt()}dp · keys ${"%.0f".format(key.value)}dp · " +
                            "PFP ${"%.0f".format(block.value)}dp vs Android ≈${"%.0f".format(android.value)}dp",
                        color = Color(0xFFFF9F1C), fontSize = 12.sp, modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(600)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val view = composeRule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val dir = File(System.getProperty("user.dir"), "build/test-screenshots/mockups").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
