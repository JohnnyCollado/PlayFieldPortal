package com.playfieldportal.core.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import android.os.Build
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.ScrollState
import com.playfieldportal.core.ui.R
import com.playfieldportal.core.ui.components.ControllerHintBar
import com.playfieldportal.core.ui.components.ControllerPromptItem
import com.playfieldportal.core.ui.components.MENU_BACK_KEY
import com.playfieldportal.core.ui.components.MenuGlyphOverride
import com.playfieldportal.core.ui.components.LocalPfpBackdropBlur
import com.playfieldportal.core.ui.components.PfpBackdropBlurState
import com.playfieldportal.core.ui.components.PfpScrollBar
import com.playfieldportal.core.ui.components.pfpBackdropBlur
import com.playfieldportal.core.ui.theme.dimmed

// ── Console-style detail page: background, scaffold, header, footer ───────────
//
// The one page frame every full-page library entry (games, apps, videos) shares, in the approved
// PS3-era information-page direction: a see-through breadcrumb header over a thin divider, a scrolling body
// centered at a readable maximum width, and a permanent controller-helper footer pinned in its own
// layout row.
//
// Everything here is accent-derived from the active PFP/XMB theme — no baked-in blue and no fixed
// color asset — so the page follows whichever color scheme (and monthly "Original" hue) is active.
//
// Why this lives in core-ui rather than a feature module: two feature modules render full-page
// entry details (feature-xmb's Game Detail and App Detail), features must not depend on each
// other, and a second copy of these surfaces would drift. Same reasoning as ControllerHintBar.

// Page tones, all read from the theme's detail palette (see DetailPalette.kt: the App Drawer's colors
// with the mockup's layering).
internal val DetailTextPrimary: Color @Composable @ReadOnlyComposable get() = detailPalette().textPrimary
internal val DetailTextMuted: Color @Composable @ReadOnlyComposable get() = detailPalette().textMuted
internal val DetailIconPrimary: Color @Composable @ReadOnlyComposable get() = detailPalette().iconPrimary
internal val DetailIconMuted: Color @Composable @ReadOnlyComposable get() = detailPalette().iconMuted
internal val DetailRowFill: Color @Composable @ReadOnlyComposable get() = detailPalette().rowFill
internal val DetailRowEdge: Color @Composable @ReadOnlyComposable get() = detailPalette().rowEdge
internal val DetailDivider: Color @Composable @ReadOnlyComposable get() = detailPalette().divider
internal val DetailFocusEdge: Color @Composable @ReadOnlyComposable get() = detailPalette().focus

/** The launch action keeps its restrained green fill and dark label. */
val DetailLaunchFill = Color(0xFF45C46A)
val DetailLaunchText = Color(0xFF06210D)

/** Readable maximum width for the page body, plus its side margins. */
val DetailContentMaxWidth: Dp = 920.dp
val DetailContentPadding: Dp = 28.dp

/**
 * The height the helper footer always reserves. Fixed on purpose: the footer's prompts change with
 * context (and fade out entirely for touch input), and neither may move the body's geometry.
 */
val DetailFooterHeight: Dp = 58.dp

internal val DetailTextShadow = com.playfieldportal.core.ui.theme.ThemeTokens.DetailTextShadow

/**
 * The page surface colors: the App Drawer's translucent theme gradient, with see-through header and
 * footer bands, so the XMB wave reads through the page exactly as it does through the drawer.
 */
@Composable
@ReadOnlyComposable
internal fun detailSurfaceTop(): Color = detailPalette().pageTop

@Composable
@ReadOnlyComposable
internal fun detailSurfaceBottom(): Color = detailPalette().pageBottom

@Composable
@ReadOnlyComposable
internal fun detailHeaderSurface(): Color = detailPalette().header

@Composable
@ReadOnlyComposable
internal fun detailFooterSurface(): Color = detailPalette().footer

/**
 * The whole-page backdrop: the App Drawer's translucent theme gradient (deep top easing into a
 * midtone), so the XMB wave reads through it exactly as it does through the drawer. Never a texture.
 *
 * [content] is a [BoxScope], so callers can stack modals/overlays on top of the page.
 */
@Composable
fun PfpDetailBackground(
    modifier: Modifier = Modifier,
    // False draws no surface of its own: the page is hidden under another page, whose surface is the
    // one that shows (two stacked would read darker than every other page).
    drawSurface: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = if (drawSurface) {
            modifier.background(Brush.verticalGradient(0f to detailSurfaceTop(), 1f to detailSurfaceBottom()))
        } else {
            modifier
        },
        content = content,
    )
}

/**
 * The detail page frame: pinned breadcrumb header, scrolling body, pinned helper footer.
 *
 * The body scrolls under [scrollState] the caller owns — controller focus and touch must share one
 * scroll owner, so the screen that drives focus-driven scrolling passes its state in here.
 *
 * [overlay] is the top layer of the page's own stack: blocking overlays (context menus, pickers,
 * viewers) belong there rather than inside the scrolling body, so they cover the whole screen and
 * cannot be scrolled away.
 */
@Composable
fun PfpDetailScaffold(
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    contentMaxWidth: Dp = DetailContentMaxWidth,
    horizontalPadding: Dp = DetailContentPadding,
    showScrollBar: Boolean = true,
    // Covered by a page of its own in [overlay] (Game Detail under Update Metadata): the page is kept
    // laid out — its scroll, cursor and geometry are exactly as left — but not drawn, not touchable
    // and not read out, until the user comes back to it.
    pageHidden: Boolean = false,
    header: @Composable () -> Unit = {},
    footer: @Composable () -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    // The page keeps its own blur state: its prompts and panels sit in [overlay], above the layer
    // that gets blurred, and must not blur the enclosing host (the shell) they are drawn inside.
    ProvidePfpBackdropBlur { blur ->
    // The page is see-through: while it blurs, what shows through it blurs too (see LocalPfpBackdropBehind).
    com.playfieldportal.core.ui.components.RaiseWhile(
        com.playfieldportal.core.ui.components.LocalPfpBackdropBehind.current,
        active = Build.VERSION.SDK_INT >= 31 && blur.active,
    )
    PfpDetailBackground(modifier = modifier.fillMaxSize(), drawSurface = !pageHidden) {
        Column(modifier = Modifier.fillMaxSize().pfpBackdropBlurLayer(blur).hiddenPage(pageHidden)) {
            header()
            // Hard viewport edge: nothing in the body may paint into the header or footer rows.
            BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
                // The body's real viewport, so the page can size its top band to fit above the
                // footer (see detailHeroHeightFor).
                CompositionLocalProvider(LocalDetailViewportHeight provides maxHeight) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .widthIn(max = contentMaxWidth)
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                            .padding(start = horizontalPadding, end = horizontalPadding, bottom = 22.dp),
                        content = content,
                    )
                    // Slim scroll bar on the page's end edge, over the body's gutter; hidden when the
                    // page fits. Display-only for a controller (never focusable).
                    if (showScrollBar) {
                        PfpScrollBar(scrollState, Modifier.align(Alignment.CenterEnd))
                    }
                }
            }
            footer()
        }
        overlay()
    }
    }
}

/** See PfpDetailScaffold's pageHidden: invisible, every touch stopped before it reaches the page, no semantics. */
private fun Modifier.hiddenPage(hidden: Boolean): Modifier =
    if (!hidden) {
        this
    } else {
        alpha(0f)
            .clearAndSetSemantics {}
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
    }

// ── Backdrop blur: one layer for the content, a sibling above it for prompts and panels ────────
//
// A prompt or panel cannot blur what is behind it; the host that owns the content does, by putting
// [pfpBackdropBlurLayer] on the layer holding the screen's own content and drawing the prompt in a
// sibling layer above. [ProvidePfpBackdropBlur] gives that host its own state, so a screen nested in
// another host (Settings or a detail page inside the shell) blurs its own content, not the shell's.

/** Set on the layer that carries the backdrop blur: true while it is actually blurred. Tests read it. */
val PfpBackdropBlurAppliedKey = SemanticsPropertyKey<Boolean>("PfpBackdropBlurApplied")
var SemanticsPropertyReceiver.pfpBackdropBlurApplied by PfpBackdropBlurAppliedKey

/** [pfpBackdropBlur] for a content layer, marking it for tests. Never put it on a layer that holds a prompt. */
@Composable
fun Modifier.pfpBackdropBlurLayer(state: PfpBackdropBlurState?): Modifier {
    val applied = state != null && Build.VERSION.SDK_INT >= 31 && state.active
    return this.pfpBackdropBlur(state).semantics { pfpBackdropBlurApplied = applied }
}

/**
 * Provides a fresh [PfpBackdropBlurState] to [content] (and to [LocalPfpBackdropBlur] below it).
 * Its switch follows [enabled], or the enclosing host's state when null (so the shell's Performance /
 * Thermal decision reaches nested screens), or is on when there is no enclosing host.
 */
@Composable
fun ProvidePfpBackdropBlur(
    enabled: Boolean? = null,
    content: @Composable (PfpBackdropBlurState) -> Unit,
) {
    val outer = LocalPfpBackdropBlur.current
    val allowed = enabled ?: outer?.enabled ?: true
    val state = remember { PfpBackdropBlurState(allowed) }
    SideEffect { state.enabled = allowed }
    CompositionLocalProvider(LocalPfpBackdropBlur provides state) { content(state) }
}

/** The height of the scrolling body between the header and the footer (unbounded outside a scaffold). */
val LocalDetailViewportHeight = staticCompositionLocalOf { Dp.Infinity }

/**
 * The breadcrumb header: `◀` + title over a small subtitle (e.g. `Nintendo DS` / `ROM`), the same
 * shape as the App Drawer's header, over a thin divider like the drawer's.
 *
 * Deliberately non-focusable for the controller — Back is a button, not a page node — but the arrow
 * and the title stack are one touch target that returns off the page, and it is the only header
 * chrome, so touch users always have a way back even when the controller cursor is hidden.
 */
@Composable
fun PfpDetailBreadcrumb(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth().background(detailHeaderSurface())) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = DetailContentPadding, end = DetailContentPadding, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Deliberately unweighted: a Row measures unweighted children first, in order, so the
            // breadcrumb claims its full width before [trailing] and is never cut short by it. (It
            // used to share a weight with the spacer below, which handed it only half the leftover
            // space.) Past the screen width it still ellipsizes against the Row's own bound.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onBack,
                    ),
            ) {
                // 48dp touch target (Android's minimum) around a 16sp glyph, so the arrow stays easy to
                // hit without a visible chip.
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (!MenuGlyphOverride(MENU_BACK_KEY, 18.dp, stringResource(R.string.core_back))) {
                        Text(text = "◀", color = DetailTextMuted, fontSize = 16.sp)
                    }
                }
                Spacer(Modifier.width(4.dp))
                Column {
                    Text(
                        text = title,
                        color = DetailTextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subtitle,
                        color = DetailTextMuted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(16.dp))
                Spacer(Modifier.weight(1f))
                // Gets whatever the breadcrumb leaves; clipped rather than pushing into it.
                Box(modifier = Modifier.clipToBounds(), contentAlignment = Alignment.CenterEnd) { trailing() }
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(DetailDivider))
    }
}

/**
 * The permanent controller-helper footer: a real layout row below the scrolling body, never an
 * overlay.
 *
 * Geometry is reserved whether or not the prompts are showing or resolving, so fading the hints
 * (existing PFP behaviour: hints fade while the last input was touch) never moves content.
 */
/**
 * Whether the detail screens' helper footer has earned its idle fade-in: true once the user has
 * idled there with a controller, false again on the next press. Provided by the shell from the same
 * clock and setting as the crossbar pill and the Settings footer. Defaults to true so a detail
 * screen hosted without the shell (a preview, a test) still shows its prompts.
 */
val LocalDetailHintVisible = androidx.compose.runtime.compositionLocalOf { true }

@Composable
fun PfpDetailHelperFooter(
    items: List<ControllerPromptItem>,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
) {
    // The XMB pill's timing: fades in, and is gone the instant it stops applying. PFP's keyboard,
    // when open over the page, brings its own prompts in this footer's place.
    val alpha = com.playfieldportal.core.ui.components.idleHintAlpha(
        visible && LocalDetailHintVisible.current &&
            !com.playfieldportal.core.ui.keyboard.isVirtualKeyboardOverlayOpen(),
    )
    // See-through like the App Drawer's footer. Content cannot slide underneath it: the body above
    // clips to its own viewport, so the footer row only ever shows the page background.
    Column(modifier = modifier.fillMaxWidth().height(DetailFooterHeight).background(detailFooterSurface())) {
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(DetailDivider))
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            ControllerHintBar(
                items = items,
                background = Color.Black.copy(alpha = 0.70f),
                modifier = Modifier.alpha(alpha),
            )
        }
    }
}

/** A small all-caps label that names a band of the page (e.g. `MEDIA PREVIEW`). */
@Composable
fun PfpDetailSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        color = DetailTextMuted.dimmed(0.75f),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = modifier,
    )
}

/**
 * The focus ring every controller-focusable detail surface shares: a thin bright edge plus a lift in
 * fill, applied inside the node's own bounds so focus never changes layout.
 *
 * Deliberately not a scale animation: a scaled focus target moves its neighbours, which is exactly
 * the layout shift the approved design forbids.
 */
@Composable
internal fun Modifier.detailFocusRing(
    focused: Boolean,
    edge: Color,
    fill: Color,
    shape: RoundedCornerShape,
    strong: Boolean = false,
): Modifier = this
    .background(if (focused) fill else Color.Transparent, shape)
    .border(
        width = if (focused) (if (strong) 2.dp else 1.5.dp) else 1.dp,
        color = if (focused) edge else DetailRowEdge,
        shape = shape,
    )
