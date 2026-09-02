package com.playfieldportal.launcher

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.playfieldportal.core.data.repository.ControllerLayoutRepository
import com.playfieldportal.core.data.repository.ControllerMappingRepository
import com.playfieldportal.core.domain.model.ControllerLayoutPrefs
import com.playfieldportal.core.domain.model.GamepadMappings
import com.playfieldportal.core.ui.components.ControllerPromptStyle
import com.playfieldportal.core.ui.components.LocalControllerPromptStyle
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.combine

/**
 * Supplies the ambient controller identity for every prompt in the app.
 *
 * Both halves are read here so a prompt is always internally consistent: the
 * family decides which art is drawn, the mappings decide which button. Reading
 * them separately in different screens is how footers drift out of sync with
 * the pad.
 *
 * An entry point rather than a ViewModel — this is process-wide chrome with no
 * state of its own, and threading it through the shell's ViewModel would tie
 * every feature module's footer to feature-xmb.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ControllerPromptsEntryPoint {
    fun controllerLayoutRepository(): ControllerLayoutRepository
    fun controllerMappingRepository(): ControllerMappingRepository
}

@Composable
fun ProvideControllerPrompts(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val entryPoint = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            ControllerPromptsEntryPoint::class.java,
        )
    }
    val styleFlow = remember(entryPoint) {
        combine(
            entryPoint.controllerLayoutRepository().prefs,
            entryPoint.controllerMappingRepository().mappings,
        ) { prefs: ControllerLayoutPrefs, mappings: GamepadMappings ->
            ControllerPromptStyle(family = prefs.displayType, mappings = mappings)
        }
    }
    // The default matches LocalControllerPromptStyle's, so the very first frame
    // draws stock Xbox prompts rather than nothing while DataStore is read.
    val style by styleFlow.collectAsStateWithLifecycle(initialValue = ControllerPromptStyle())

    CompositionLocalProvider(LocalControllerPromptStyle provides style) {
        content()
    }
}
