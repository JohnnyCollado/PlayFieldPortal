package com.playfieldportal.core.ui.keyboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import kotlinx.coroutines.flow.first

/**
 * While [active], text fields inside [content] do not open the system keyboard: their
 * input-session request is held until [active] turns false, then passed on. Focus, the caret and
 * editing stay the field's own, so PFP's virtual keyboard can type into a real field, and handing
 * over to the system keyboard (a tap mid-edit) is just turning [active] off. Inactive, a request
 * passes straight through — today's behaviour.
 *
 * Always installed rather than added on demand: swapping the wrapper in and out would move the
 * field to a new call site and drop its focus.
 *
 * Virtual Keyboard plan T4: whether the caret still draws while the request is held is what the
 * device spike checks before anything is built on this.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SuppressPlatformKeyboard(active: Boolean, content: @Composable () -> Unit) {
    val suppressed by rememberUpdatedState(active)
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            snapshotFlow { suppressed }.first { !it }
            nextHandler.startInputMethod(request)
        },
        content = content,
    )
}
