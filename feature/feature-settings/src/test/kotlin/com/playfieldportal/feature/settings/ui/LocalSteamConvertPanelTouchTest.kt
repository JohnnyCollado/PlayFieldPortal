package com.playfieldportal.feature.settings.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.playfieldportal.core.ui.achievement.LocalSteamConvertPanel
import com.playfieldportal.core.ui.achievement.LocalSteamConvertRow
import com.playfieldportal.core.ui.theme.PFPTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The convert picker's touch branch, as the Library Manager now drives it: every action the
 * controller hint line names must be reachable by tap.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w800dp-h480dp")
class LocalSteamConvertPanelTouchTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var installs = 0
    private var skips = 0
    private var allNone = 0
    private var cancels = 0

    @Test
    fun `touch panel exposes Install, All, Skip and Cancel by tap`() {
        composeRule.setContent {
            PFPTheme {
                LocalSteamConvertPanel(
                    rows = listOf(LocalSteamConvertRow("Game", "12 coins", selected = false)),
                    focus = 0,
                    loading = false,
                    canConfirm = true,
                    focusFill = Color.DarkGray,
                    focusEdge = Color.White,
                    showTouchControls = true,
                    onRowClick = {},
                    onSelectAllNone = { allNone++ },
                    onConfirm = { installs++ },
                    onSkip = { skips++ },
                    onCancel = { cancels++ },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Install (0)").performClick()
        composeRule.onNodeWithText("All").performClick()
        composeRule.onNodeWithText("Skip & Sync").performClick()
        composeRule.onNodeWithText("Cancel").performClick()

        assertEquals(1, installs)
        assertEquals(1, allNone)
        assertEquals(1, skips)
        assertEquals(1, cancels)
    }
}
