package com.playfieldportal.feature.settings.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.playfieldportal.core.data.repository.ControllerLayoutRepository
import com.playfieldportal.core.domain.model.ControllerLayoutPrefs
import com.playfieldportal.core.ui.theme.PFPTheme
import com.playfieldportal.feature.settings.viewmodel.ControllerSettingsViewModel
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings ▸ Controller's Keyboard group (Virtual Keyboard plan 6.11). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h2000dp")
class ControllerSettingsKeyboardRowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val layoutRepository = mockk<ControllerLayoutRepository>(relaxed = true)

    private fun show() {
        every { layoutRepository.prefs } returns flowOf(ControllerLayoutPrefs())
        val viewModel = ControllerSettingsViewModel(mockk(relaxed = true), layoutRepository)
        composeRule.setContent {
            PFPTheme { ControllerSettingsScreen(onBack = {}, viewModel = viewModel) }
        }
        composeRule.waitForIdle()
    }

    private fun top(text: String): Float =
        composeRule.onNodeWithText(text).performScrollTo().fetchSemanticsNode().boundsInRoot.top

    @Test
    fun `a Keyboard group sits between Navigation and Reset with the approved copy`() {
        show()

        val navigation = top("NAVIGATION")
        val keyboard = top("KEYBOARD")
        val reset = top("RESET")
        assertTrue("Keyboard after Navigation", keyboard > navigation)
        assertTrue("Keyboard before Reset", keyboard < reset)
        composeRule.onNodeWithText("Virtual Keyboard").performScrollTo()
        composeRule.onNodeWithText(
            "Type with the controller on PFP's own keyboard. Off, or when you use touch, " +
                "the system keyboard opens instead",
        ).performScrollTo()
    }

    @Test
    fun `toggling the row turns the setting off`() {
        show()

        composeRule.onNodeWithText("Virtual Keyboard").performScrollTo().performClick()
        composeRule.waitForIdle()

        coVerify { layoutRepository.setVirtualKeyboard(false) }
    }
}
