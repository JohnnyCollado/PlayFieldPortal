package com.playfieldportal.core.ui.gesture

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pins the two things about [dragToScroll] that a reader cannot check by eye: that a null state is
 * inert, and that the drag direction matches `Modifier.verticalScroll`. An inverted header would be
 * worse than the dead one it replaces, and the `reverseDirection` sign that gets it right is not
 * self-evident.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class DragToScrollTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** The scaffold's shape in miniature: a "header" that is a sibling of the scrolling body. */
    private fun setContent(attachState: Boolean) {
        composeRule.setContent {
            val scrollState = rememberScrollState()
            scroll = scrollState
            Column {
                Box(
                    modifier = Modifier
                        .testTag(HEADER)
                        .fillMaxWidth()
                        .height(100.dp)
                        .background(Color.DarkGray)
                        .dragToScroll(if (attachState) scrollState else null),
                )
                Column(
                    modifier = Modifier
                        .testTag(BODY)
                        .fillMaxWidth()
                        .height(200.dp)
                        .verticalScroll(scrollState),
                ) {
                    Spacer(Modifier.height(2000.dp))
                }
            }
        }
    }

    private var scroll: ScrollState? = null

    @Test
    fun `drag up on chrome scrolls the body down, matching verticalScroll`() {
        setContent(attachState = true)

        composeRule.onNodeWithTag(HEADER).performTouchInput { swipeUp() }
        composeRule.waitForIdle()

        assertTrue(
            "Swiping up on the header must move content up (offset grows), like verticalScroll",
            requireNotNull(scroll).value > 0,
        )
    }

    @Test
    fun `drag down on chrome scrolls back toward the top`() {
        setContent(attachState = true)

        composeRule.onNodeWithTag(HEADER).performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        val afterUp = requireNotNull(scroll).value

        composeRule.onNodeWithTag(HEADER).performTouchInput { swipeDown() }
        composeRule.waitForIdle()

        assertTrue("Swiping down must undo the swipe up", requireNotNull(scroll).value < afterUp)
    }

    @Test
    fun `a null state leaves the chrome inert`() {
        setContent(attachState = false)

        composeRule.onNodeWithTag(HEADER).performTouchInput { swipeUp() }
        composeRule.waitForIdle()

        assertEquals(0, requireNotNull(scroll).value)
    }

    @Test
    fun `the body keeps scrolling itself`() {
        setContent(attachState = true)

        composeRule.onNodeWithTag(BODY).performTouchInput {
            down(center)
            moveBy(Offset(0f, -150f))
            up()
        }
        composeRule.waitForIdle()

        assertTrue(requireNotNull(scroll).value > 0)
    }

    private companion object {
        const val HEADER = "chrome-header"
        const val BODY = "scrolling-body"
    }
}
