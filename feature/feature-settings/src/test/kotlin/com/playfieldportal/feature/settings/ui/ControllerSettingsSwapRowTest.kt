package com.playfieldportal.feature.settings.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.playfieldportal.core.data.repository.ControllerLayoutRepository
import com.playfieldportal.core.domain.model.ConfirmBackLayout
import com.playfieldportal.core.domain.model.ControllerLayoutPrefs
import com.playfieldportal.core.domain.model.XYLayout
import com.playfieldportal.core.ui.theme.PFPTheme
import com.playfieldportal.feature.settings.viewmodel.ControllerSettingsViewModel
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings ▸ Controller: the two swaps are plainly named switch rows, not On/Off value rows. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h2000dp")
class ControllerSettingsSwapRowTest {

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

    @Test
    fun `the rows are named for what they do and A B jargon is gone`() {
        show()

        composeRule.onNodeWithText("Swap confirm and back").performScrollTo()
        composeRule.onNodeWithText("Swap options and sort").performScrollTo()
        assertEquals(0, composeRule.onAllNodesWithText("A / B swap").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("X / Y swap").fetchSemanticsNodes().size)
    }

    @Test
    fun `switching the confirm and back row stores the reversed layout`() {
        show()

        composeRule.onNodeWithText("Swap confirm and back").performScrollTo().performClick()
        composeRule.waitForIdle()

        coVerify { layoutRepository.setConfirmBackLayout(ConfirmBackLayout.REVERSED) }
    }

    @Test
    fun `switching the options and sort row stores the swapped layout`() {
        show()

        composeRule.onNodeWithText("Swap options and sort").performScrollTo().performClick()
        composeRule.waitForIdle()

        coVerify { layoutRepository.setXYLayout(XYLayout.SWAPPED) }
    }
}
