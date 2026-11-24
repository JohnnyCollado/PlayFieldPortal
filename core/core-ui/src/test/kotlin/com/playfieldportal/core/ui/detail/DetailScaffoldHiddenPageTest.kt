package com.playfieldportal.core.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import com.playfieldportal.core.ui.theme.PFPTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A page covered by a page of its own (Game Detail under Update Metadata) is hidden until the user
 * comes back to it: not drawn, not reachable by touch or accessibility, and its overlay still shown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h360dp")
class DetailScaffoldHiddenPageTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val hidden = mutableStateOf(true)
    private var bodyTaps = 0

    private fun setUp() {
        composeRule.setContent {
            PFPTheme {
                PfpDetailScaffold(
                    pageHidden = hidden.value,
                    header = { Text("Header") },
                    footer = { Text("Footer") },
                    overlay = { Text("Overlay") },
                ) {
                    // Taller than the screen, so a tap in its middle lands on the body.
                    Box(Modifier.fillMaxWidth().height(2000.dp).clickable { bodyTaps++ }) { Text("Body") }
                }
            }
        }
    }

    @Test
    fun `a hidden page shows only its overlay`() {
        setUp()
        composeRule.onNodeWithText("Overlay").assertExists()
        composeRule.onNodeWithText("Header").assertDoesNotExist()
        composeRule.onNodeWithText("Body").assertDoesNotExist()
        composeRule.onNodeWithText("Footer").assertDoesNotExist()
    }

    @Test
    fun `a tap cannot reach a hidden page`() {
        setUp()
        composeRule.onRoot().performTouchInput { click(center) }
        assertEquals(0, bodyTaps)
    }

    @Test
    fun `the page comes back when it is shown again`() {
        setUp()
        hidden.value = false
        composeRule.onNodeWithText("Header").assertExists()
        composeRule.onNodeWithText("Body").assertExists()
        composeRule.onRoot().performTouchInput { click(center) }
        assertEquals(1, bodyTaps)
    }
}
