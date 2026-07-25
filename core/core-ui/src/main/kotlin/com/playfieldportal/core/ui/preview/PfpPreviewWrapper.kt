package com.playfieldportal.core.ui.preview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.playfieldportal.core.ui.theme.DefaultPFPColors
import com.playfieldportal.core.ui.theme.PFPColors
import com.playfieldportal.core.ui.theme.PFPTheme

/**
 * Wraps preview content in the standard [PFPTheme] + a [Surface] with the themed background.
 * Sizes to its content — use for previewing a single component (a row, a card, a dialog).
 *
 * For previewing a whole screen, use [PfpScreenPreview]; for previewing a ViewModel-driven screen,
 * see the note below.
 *
 * ## Previewing ViewModel-driven screens
 *
 * A composable that calls `hiltViewModel()` cannot be previewed directly — there is no Hilt graph
 * in the preview/inspection environment, so it throws. The fix is **state hoisting**: split the
 * screen into a thin stateful entry point that owns the ViewModel, and a stateless content
 * composable that takes the `UiState` plus callback lambdas. Preview the stateless one with a
 * hand-built sample state. For example:
 *
 * ```
 * @Composable
 * fun FooScreen(viewModel: FooViewModel = hiltViewModel()) {          // stateful — real app path
 *     val state by viewModel.uiState.collectAsState()
 *     FooContent(state = state, onDoThing = viewModel::doThing)
 * }
 *
 * @Composable
 * private fun FooContent(state: FooUiState, onDoThing: () -> Unit) { … } // stateless — previewable
 *
 * @CombinedPreviews
 * @Composable
 * private fun FooScreenPreview() {
 *     PfpScreenPreview { FooContent(state = FooUiState(/* sample */), onDoThing = {}) }
 * }
 * ```
 *
 * See `PhotoSettingsScreen` for a real, applied example of this split.
 */
@Composable
fun PfpPreview(
    colors: PFPColors = DefaultPFPColors,
    content: @Composable () -> Unit,
) {
    PFPTheme(colors = colors) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            content = content,
        )
    }
}

/**
 * Like [PfpPreview] but fills the whole preview device — use when previewing an entire screen
 * (which lays itself out with `fillMaxSize`), so it renders against a full themed backdrop rather
 * than shrinking to content. Pair with [CombinedPreviews] for device/font-scale coverage.
 */
@Composable
fun PfpScreenPreview(
    colors: PFPColors = DefaultPFPColors,
    content: @Composable () -> Unit,
) {
    PFPTheme(colors = colors) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            content = content,
        )
    }
}
