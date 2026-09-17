package com.playfieldportal.core.ui.detail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.ScrollState
import com.playfieldportal.core.ui.components.ControllerHintBar
import com.playfieldportal.core.ui.components.ControllerPromptItem
import com.playfieldportal.core.ui.theme.LocalPFPColors
import com.playfieldportal.core.ui.theme.solveScrimColor

// ── Console-style detail page: background, scaffold, header, footer ───────────
//
// The one page frame every full-page library entry (games, apps, videos) shares, in the approved
// PS3-era information-page direction: a slim darkened-accent breadcrumb band, a scrolling body
// centered at a readable maximum width, and a permanent controller-helper footer pinned in its own
// layout row.
//
// Everything here is accent-derived from the active PFP/XMB theme — no baked-in blue and no fixed
// color asset — so the page follows whichever color scheme (and monthly "Original" hue) is active.
//
// Why this lives in core-ui rather than a feature module: two feature modules render full-page
// entry details (feature-xmb's Game Detail and App Detail), features must not depend on each
// other, and a second copy of these surfaces would drift. Same reasoning as ControllerHintBar.

/** Neutral row surface over the accent background: dark enough for 4.5:1 white body text. */
internal val DetailTextPrimary = Color(0xFFF3F5F9)
internal val DetailTextMuted = Color(0xB8E8EDF6)
internal val DetailRowFill = Color(0x66101219)
internal val DetailRowEdge = Color(0x33FFFFFF)
internal val DetailDivider = Color(0x1FFFFFFF)

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

internal val DetailTextShadow = Shadow(
    color = Color.Black.copy(alpha = 0.72f),
    offset = Offset(0f, 2f),
    blurRadius = 4f,
)

/**
 * The page surface colors: the active theme's own background anchors, undarkened, so the page shows
 * the color scheme the user chose (`backgroundTop`/`backgroundBottom` are re-derived from the wave
 * color for every scheme, including the monthly "Original" palette).
 *
 * Same translucent treatment the pre-redesign page and the Music browser use, so the XMB wave stays
 * visible underneath and the page reads as part of the launcher rather than a separate app.
 *
 * Each anchor goes through [solveScrimColor] — the same legibility pass the Settings scaffold runs on
 * these anchors — so a bright scheme is darkened only as far as white text needs to clear 4.5:1 at
 * that alpha, keeping the hue the user picked. Dark schemes come back unchanged.
 */
@Composable
internal fun detailSurfaceTop(): Color = readableAnchor(LocalPFPColors.current.backgroundTop, 0.72f)

@Composable
internal fun detailSurfaceBottom(): Color = readableAnchor(LocalPFPColors.current.backgroundBottom, 0.90f)

/** The breadcrumb band: the theme's top anchor, near-opaque so it reads as a fixed band. */
@Composable
internal fun detailHeaderSurface(): Color = readableAnchor(LocalPFPColors.current.backgroundTop, 0.94f)

/** The helper footer band: the theme's bottom anchor, so it continues the page gradient. */
@Composable
internal fun detailFooterSurface(): Color = readableAnchor(LocalPFPColors.current.backgroundBottom, 0.96f)

/** Solved once per theme anchor, not per recomposition: each solve bisects 12 times. */
@Composable
private fun readableAnchor(anchor: Color, alpha: Float): Color =
    remember(anchor, alpha) { solveScrimColor(anchor, alpha = alpha).copy(alpha = alpha) }

/**
 * The whole-page backdrop: a mostly uniform accent-derived surface with an extremely subtle vertical
 * tonal gradient for text separation. Never a bright decorative gradient, never a texture.
 *
 * [content] is a [BoxScope], so callers can stack modals/overlays on top of the page.
 */
@Composable
fun PfpDetailBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.background(
            Brush.verticalGradient(0f to detailSurfaceTop(), 1f to detailSurfaceBottom()),
        ),
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
    header: @Composable () -> Unit = {},
    footer: @Composable () -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    PfpDetailBackground(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            header()
            // Hard viewport edge: nothing in the body may paint into the header or footer rows.
            Box(modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = contentMaxWidth)
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(start = horizontalPadding, end = horizontalPadding, bottom = 22.dp),
                    content = content,
                )
            }
            footer()
        }
        overlay()
    }
}

/**
 * The breadcrumb band: `◀ LIBRARY / PLAYSTATION / Game Title`.
 *
 * Deliberately non-focusable for the controller — Back is a button, not a page node — but the whole
 * left group is one touch target that returns off the page, and it is the only header chrome, so
 * touch users always have a way back even when the controller cursor is hidden.
 *
 * [crumbs] renders in order with the trailing segment emphasized; only the leading segments are
 * uppercased, because the last one is a user-visible title and should keep its own casing.
 */
@Composable
fun PfpDetailBreadcrumb(
    crumbs: List<String>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val header = crumbs.lastOrNull().orEmpty()
    val leading = crumbs.dropLast(1).filter { it.isNotBlank() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(detailHeaderSurface())
            .padding(start = DetailContentPadding, end = DetailContentPadding, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onBack,
            ),
        ) {
            // 48dp touch target (Android's minimum) around a 16sp glyph, so the arrow stays easy to
            // hit without a visible chip.
            Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Text(text = "◀", color = DetailTextMuted, fontSize = 16.sp)
            }
            Spacer(Modifier.width(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f, fill = false)) {
                leading.forEach { crumb ->
                    Text(
                        text = crumb.uppercase(),
                        color = DetailTextMuted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(shadow = DetailTextShadow),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(text = "/", color = DetailTextMuted.copy(alpha = 0.55f), fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                }
                // The title takes the remaining width and ellipsizes, so a long title can never
                // overlap the trailing slot.
                Text(
                    text = header,
                    color = DetailTextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(shadow = DetailTextShadow),
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}

/**
 * The permanent controller-helper footer: a real layout row below the scrolling body, never an
 * overlay.
 *
 * Geometry is reserved whether or not the prompts are showing or resolving, so fading the hints
 * (existing PFP behaviour: hints fade while the last input was touch) never moves content.
 */
@Composable
fun PfpDetailHelperFooter(
    items: List<ControllerPromptItem>,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
) {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(200),
        label = "pfpDetailHelperFooter",
    )
    // A near-opaque band: with a transparent row, body content scrolled to the viewport edge read as
    // sliding underneath the floating hint pill.
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
        color = DetailTextMuted.copy(alpha = 0.75f),
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

/** Arrangement for the footer's own prompt rows (helpers keep call sites terse). */
internal val DetailHelperArrangement: Arrangement.Horizontal = Arrangement.spacedBy(16.dp)
