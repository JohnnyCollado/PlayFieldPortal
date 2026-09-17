package com.playfieldportal.feature.xmb.ui.detail

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
 * What the redesigned page renders for two content shapes the design calls out explicitly: a
 * package-backed entry (no emulator controls anywhere, no emulator information field) and an entry
 * with nothing to preview (no media band, no information band).
 *
 * Rendering the real screen with a state-driving fake is the point: the rules live in the layout, so
 * nothing short of a composition can show that a band is absent rather than empty.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w480dp-h640dp")
class GameDetailScreenContentTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun render(state: GameDetailUiState) {
        val flow = MutableStateFlow(state)
        val viewModel = mockk<GameDetailViewModel>(relaxed = true)
        every { viewModel.uiState } returns flow

        composeRule.setContent {
            PfpScreenPreview {
                GameDetailScreen(
                    gameId = state.game?.id ?: 0L,
                    onBack = {},
                    showTouchControls = false,
                    viewModel = viewModel,
                )
            }
        }
        composeRule.waitForIdle()
    }

    /** Whether the composed page contains [text] anywhere (labels are not unique by design). */
    private fun exists(text: String): Boolean =
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun assertAbsent(text: String, why: String) =
        assertTrue("$why — found \"$text\"", !exists(text))

    private fun assertPresent(text: String, why: String) =
        assertTrue("$why — \"$text\" missing", exists(text))

    @Test
    fun `a package-backed entry never offers emulator controls`() {
        render(
            GameDetailUiState(
                isLoading = false,
                game = Game(
                    id = 7L,
                    title = "Netflix",
                    platformId = "android",
                    // Package-backed: launched through its package, so no emulator applies.
                    romPath = null,
                    packageName = "com.netflix.mediaclient",
                ),
            ),
        )

        // The quick action and the information band's inline field are both gone, not disabled.
        assertAbsent("Emulator", "a package-backed entry must not offer an emulator action")
        assertAbsent("MEDIA PREVIEW", "an entry with no media must not show a media band")
        // Android entries can never have achievements, so the coins strip is absent too.
        assertAbsent("SHIBA COINS", "an Android entry must not show the Shiba Coins strip")
        // The page itself is still a page: Launch and its footer documentation are present.
        assertPresent("Launch", "the primary action must survive an entry with no extra content")
        assertPresent("Options", "the helper footer must document Options on the base page")
        assertPresent("Back", "the helper footer must document Back")
    }

    @Test
    fun `an entry with media and metadata shows every band`() {
        render(
            GameDetailUiState(
                isLoading = false,
                detailMedia = listOf(
                    DetailMedia("/tmp/clip.mp4", isVideo = true),
                    DetailMedia("/tmp/shot.png", isVideo = false),
                ),
                game = Game(
                    id = 1L,
                    title = "Crash Bandicoot",
                    platformId = "psx",
                    romPath = "/roms/psx/crash.bin",
                    releaseYear = 1996,
                    developer = "Naughty Dog",
                    description = "Bandicoot jumps.",
                ),
            ),
        )

        assertPresent("Emulator", "a ROM-backed entry keeps its emulator action")
        assertPresent("MEDIA PREVIEW", "the media band appears once the strip has an asset")
        // The named plate on a playable tile: "this is playable" never rests on the glyph alone.
        assertPresent("VIDEO", "a video tile must be named, not just glyphed")
        assertPresent("SHIBA COINS", "a non-Android entry keeps the coins strip")
        // Field labels are uppercase chrome; the values keep their own casing.
        assertPresent("RELEASED", "the information band shows the values it has")
        assertPresent("1996", "the information band shows the values it has")
        assertPresent("Naughty Dog", "field values keep their own casing")
        assertAbsent("PUBLISHER", "absent values are omitted, never filled with Unknown")
    }

    @Test
    fun `missing content is a dead end that still offers a way back`() {
        render(GameDetailUiState(isLoading = false, game = null))

        composeRule.onNodeWithText("This game is no longer in your library.").assertExists()
        composeRule.onNodeWithText("Press Back to return to the library.").assertExists()
    }
}
