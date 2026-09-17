package com.playfieldportal.feature.xmb.ui.detail

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.ui.preview.PfpScreenPreview
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Focus-driven scrolling on a real composition.
 *
 * This guards a crash, not just a behaviour: the page's scroll effect used to branch on
 * `ScrollState.animateScrollTo`, whose Unit result gets materialized as `checkcast kotlin.Unit` in a
 * branch — while the call itself is compiled as a discarded Float-returning `animateScrollBy`. Every
 * time that scroll suspended, the resumed Float hit the cast and opening the page died with
 * `ClassCastException: Float cannot be cast to kotlin.Unit`. Rendering the page is therefore the
 * regression test.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w480dp-h640dp")
class GameDetailScrollTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val game = Game(
        id = 1L,
        title = "Crash Bandicoot",
        platformId = "psx",
        romPath = "/roms/psx/crash.bin",
        releaseYear = 1996,
        developer = "Naughty Dog",
        description = "Bandicoot jumps.",
    )

    private fun state(focus: String?) = GameDetailUiState(
        isLoading = false,
        game = game,
        navFocusKey = focus,
        cursorVisible = true,
        detailMedia = listOf(
            DetailMedia("/tmp/clip.mp4", isVideo = true),
            DetailMedia("/tmp/shot.png", isVideo = false),
        ),
    )

    private fun render(flow: MutableStateFlow<GameDetailUiState>) {
        val viewModel = mockk<GameDetailViewModel>(relaxed = true)
        every { viewModel.uiState } returns flow
        composeRule.setContent {
            PfpScreenPreview {
                GameDetailScreen(
                    gameId = 1L,
                    onBack = {},
                    showTouchControls = false,
                    viewModel = viewModel,
                )
            }
        }
        composeRule.waitForIdle()
    }

    /** Root-space Y of a top-band row, used as the page's scroll instrument. */
    private fun topBandTop(): Float =
        composeRule.onNodeWithText("Artwork").fetchSemanticsNode().boundsInRoot.top

    @Test
    fun `opening the page with Launch focused renders the hero band`() {
        render(MutableStateFlow(state(GameDetailKeys.LAUNCH)))

        composeRule.onNodeWithText("Artwork").assertIsDisplayed()
        composeRule.onNodeWithText("Options").assertIsDisplayed()
    }

    @Test
    fun `focus returning to the top band brings the page back to the hero`() {
        val flow = MutableStateFlow(state(GameDetailKeys.LAUNCH))
        render(flow)
        val atTop = topBandTop()

        // Moving down to a media tile scrolls the page; the top band must leave the viewport.
        composeRule.runOnIdle { flow.value = state(GameDetailKeys.media(mediaStableId(flow.value.detailMedia[0]))) }
        composeRule.waitForIdle()
        val scrolledDown = topBandTop()
        assertTrue(
            "focusing a media tile must scroll the page (top band moved from $atTop to $scrolledDown)",
            scrolledDown < atTop,
        )

        // Coming back must return the page to the hero, not park it just below it.
        composeRule.runOnIdle { flow.value = state(GameDetailKeys.LAUNCH) }
        composeRule.waitForIdle()
        assertTrue(
            "returning to the top band must restore the page top (expected ~$atTop, was ${topBandTop()})",
            kotlin.math.abs(topBandTop() - atTop) < 1f,
        )
    }
}
