package com.playfieldportal.core.ui.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import coil3.Extras
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.playfieldportal.core.domain.model.ImageMotion
import com.playfieldportal.core.ui.image.rememberArtworkModel

/** The Animated Images setting, provided at the app root. */
val LocalImageMotion = staticCompositionLocalOf { ImageMotion.DEFAULT }

/**
 * Whether the images below are the focused item. Defaults to true: a hero, background, logo or
 * detail screen belongs to the focused game. Lists provide it per row (XMBItemList, the category
 * bar), so under Reduced only the selected row's art plays.
 */
val LocalMotionFocused = compositionLocalOf { true }

/**
 * Focus for custom icons (CustomIconSurface), which is not the same default as artwork's: an icon
 * outside the XMB lists and the icon customizer — a menu, a settings row — is never "the focused
 * item", so under Reduced it holds still, as custom icons always have. Those containers provide it
 * per slot, next to [LocalMotionFocused].
 */
val LocalIconFocused = compositionLocalOf { false }

/**
 * Whether the images below may animate at all: false while PFP is in the background (the app
 * root), and wherever a container already gates animation (battery saver, a blocking overlay).
 * A nested provider ANDs with the value above it; it never re-enables what a parent turned off.
 */
val LocalMotionAllowed = compositionLocalOf { true }

/** The [MotionGate] a request's animated result obeys. Absent = [MotionGate.Shared]. */
val MotionGateKey = Extras.Key<MotionGate?>(default = null)

fun ImageRequest.Builder.motionGate(gate: MotionGate) = apply { extras[MotionGateKey] = gate }

/** A gate for one image, kept in step with the setting, [focused] and [LocalMotionAllowed]. */
@Composable
fun rememberMotionGate(focused: Boolean = LocalMotionFocused.current): MotionGate {
    val gate = remember { MotionGate() }
    val mode = LocalImageMotion.current
    val allowed = LocalMotionAllowed.current
    SideEffect { gate.update(mode, focused, allowed) }
    return gate
}

/**
 * Reports whether this node is inside the window: the draw distance. `boundsInWindow` is clipped
 * by every parent, so a node scrolled or slid out of view has empty bounds.
 */
fun Modifier.motionOnScreen(gate: MotionGate): Modifier = onGloballyPositioned { coordinates ->
    val bounds = coordinates.boundsInWindow()
    gate.onScreen = coordinates.isAttached && bounds.width > 0f && bounds.height > 0f
}

/**
 * `AsyncImage` for artwork: the same drawing, plus the Animated Images rules. An animated file
 * plays only while it is on screen, PFP may animate, and the setting allows it for this image's
 * focus; otherwise it holds its first frame (or the frame it paused on). A still image draws
 * exactly as it always did.
 *
 * A String [model] goes through [rememberArtworkModel], so bytes replaced at the same URI reload.
 */
@Composable
fun ArtworkImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    alignment: Alignment = Alignment.Center,
    alpha: Float = 1f,
    colorFilter: ColorFilter? = null,
    focused: Boolean = LocalMotionFocused.current,
    onError: (() -> Unit)? = null,
) {
    val gate = rememberMotionGate(focused)
    val context = LocalPlatformContext.current
    val base = if (model is String) rememberArtworkModel(model) else model
    val request = remember(base, gate, context) {
        when (base) {
            null -> null
            is ImageRequest -> base.newBuilder().motionGate(gate).build()
            else -> ImageRequest.Builder(context).data(base).motionGate(gate).build()
        }
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier.motionOnScreen(gate),
        alignment = alignment,
        contentScale = contentScale,
        alpha = alpha,
        colorFilter = colorFilter,
        onError = onError?.let { callback -> { _ -> callback() } },
    )
}
