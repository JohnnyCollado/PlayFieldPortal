package com.playfieldportal.studio.preview

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import kotlin.math.min
import com.playfieldportal.core.ui.theme.ThemeTokens

/*
 * A faithful, interactive frame of the launcher's XMB, replicated from the launcher sources so
 * "what you author is what the phone renders". Every constant here mirrors a named source:
 *   background/wave/bloom — feature-xmb XmbBackground.kt
 *   crossbar geometry     — feature-xmb XMBShell.kt + XMBCategoryBar.kt (PreviewGeometry)
 *   item rows + drill     — feature-xmb XMBItemList.kt
 *   sizes/fractions       — theme-kit XmbLayoutSpec (the theme's own, never re-typed)
 * The frame is laid out in the launcher's 832x468 dp base; the canvas fit-scales it.
 */

// Design box the frame is authored at (the launcher's Thor baseline).
private val DESIGN_WIDTH = PreviewGeometry.BASE_WIDTH.dp
private val DESIGN_HEIGHT = PreviewGeometry.BASE_HEIGHT.dp

private val CategorySlotWidth = PreviewGeometry.CATEGORY_SLOT.dp
private val CatBarHeight = PreviewGeometry.CAT_BAR_HEIGHT.dp
private val RowHeight = PreviewGeometry.ROW_HEIGHT.dp

// XMBCategoryBar.kt / XMBItemList.kt text colours.
private val LabelInactive = ThemeTokens.XmbInactiveLabel
private val SecondaryText = ThemeTokens.XmbSecondaryLabel
private val SelectedLabelShadow = Shadow(color = Color(0x73001627), offset = Offset.Zero, blurRadius = 12f)
private val SubtitleShadow = ThemeTokens.TextShadow

// XMBItemList: the tap target is shorter than the row; a drilled card column is icon-only.
private val TapTargetHeight = 72.dp
private val SiblingColumnWidth = (PreviewGeometry.DRILL_CHILD_COLUMN_LEFT - 10f).dp

// XmbBackground.kt (the wave maths itself lives in WaveMotion)
private val WallpaperScrim = ThemeTokens.WallpaperScrim

private val FocusRing = Color(0xFFE6E6EA)

/**
 * The interactive preview: [XmbFrame] fit-scaled into the space it gets, with keyboard navigation
 * (arrows, Enter, Esc / Backspace, Tab / Y options, X Games filter) once engaged. The first click
 * only engages it (takes the keyboard) and never reaches a row; after that clicks, right-click
 * (options), the mouse wheel (rows; Shift or sideways for categories) and click-and-drag swipes
 * work like touch on the device ([PreviewPointer]). Cancel, Esc at the root, or a click elsewhere
 * in the Studio releases it. [nav] is hoisted so the icon picker can follow it. While [adjustOverlay] is open the keys
 * drive it instead (arrows move, Q / E scale, R reset, S sliders, Enter save, Esc cancel).
 *
 * [live] makes the frame move like the device (animated wave, focused GIF icons, motion wallpaper,
 * a playing boot sequence); null renders it still. While a boot sequence plays, any key ends it.
 */
@Composable
fun XmbPreviewCanvas(
    model: XmbPreviewModel,
    nav: PreviewNavState,
    onNav: (PreviewNavAction) -> Unit,
    modifier: Modifier = Modifier,
    adjustOverlay: AdjustOverlayState? = null,
    onAdjust: (AdjustAction) -> Unit = {},
    live: PreviewLiveSpec? = null,
    onBootFinished: () -> Unit = {},
    screen: PreviewScreen = PreviewScreen.XMB,
    onCloseScreen: () -> Unit = {},
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val pointer = remember { PreviewPointer() }
    // Mirrors pointer.engaged for composition (the Cancel button and the ring).
    var engaged by remember { mutableStateOf(false) }
    val currentOnNav by rememberUpdatedState(onNav)
    val release = {
        pointer.release()
        engaged = false
        focusManager.clearFocus()
    }
    // The overlay takes the keyboard the moment it opens, and the search field hands it back on close.
    LaunchedEffect(adjustOverlay != null) { if (adjustOverlay != null) runCatching { focusRequester.requestFocus() } }
    var wasSearching by remember { mutableStateOf(false) }
    LaunchedEffect(nav.search != null) {
        if (wasSearching && nav.search == null) runCatching { focusRequester.requestFocus() }
        wasSearching = nav.search != null
    }
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val scale = min(maxWidth / DESIGN_WIDTH, maxHeight / DESIGN_HEIGHT)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(DESIGN_WIDTH * scale, DESIGN_HEIGHT * scale)
                .pointerInput(scale) {
                    // Initial pass, so the preview sees every event before the rows do: the engaging
                    // press, wheel steps and swipes are consumed here and never reach a row.
                    val stepPx = TapTargetHeight.toPx() * scale
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull() ?: continue
                            when (event.type) {
                                PointerEventType.Press -> {
                                    pointer.dragStart()
                                    val passes = pointer.press()
                                    engaged = true
                                    focusRequester.requestFocus()
                                    if (!passes) {
                                        event.changes.forEach { it.consume() }
                                    } else if (event.buttons.isSecondaryPressed) {
                                        currentOnNav(PreviewNavAction.OpenOptions)
                                    }
                                }
                                PointerEventType.Move -> if (event.buttons.isPrimaryPressed) {
                                    val moved = change.position - change.previousPosition
                                    pointer.drag(moved.x, moved.y, stepPx).forEach(currentOnNav)
                                    // A swipe is not a click: the row under it must not open on release.
                                    if (pointer.dragged) event.changes.forEach { it.consume() }
                                }
                                PointerEventType.Release -> if (pointer.dragged) event.changes.forEach { it.consume() }
                                PointerEventType.Scroll -> {
                                    val delta = change.scrollDelta
                                    pointer.wheel(delta.x, delta.y, event.keyboardModifiers.isShiftPressed).forEach(currentOnNav)
                                    if (pointer.engaged) event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    }
                }
                .focusRequester(focusRequester)
                .onFocusChanged {
                    focused = it.hasFocus
                    // Focus went elsewhere in the Studio: the next click on the preview engages it again.
                    if (!it.hasFocus && pointer.engaged) {
                        pointer.release()
                        engaged = false
                    }
                }
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isMetaPressed || event.isAltPressed) {
                        return@onKeyEvent false
                    }
                    if (live?.boot?.isPlaying == true) {
                        onBootFinished()
                        return@onKeyEvent true
                    }
                    // An opened screen is a still frame: Esc / Backspace close it, nothing else navigates.
                    if (screen != PreviewScreen.XMB) {
                        if (event.key != Key.Escape && event.key != Key.Backspace) return@onKeyEvent false
                        onCloseScreen()
                        return@onKeyEvent true
                    }
                    if (adjustOverlay != null) {
                        val adjust = AdjustOverlay.actionFor(event.key) ?: return@onKeyEvent false
                        onAdjust(adjust)
                        return@onKeyEvent true
                    }
                    val action = PreviewNav.actionFor(event.key) ?: return@onKeyEvent false
                    // The search field owns typing; only its Enter / Esc reach the reducer from here.
                    if (nav.search != null && action != PreviewNavAction.Enter && action != PreviewNavAction.Back) {
                        return@onKeyEvent false
                    }
                    if (action == PreviewNavAction.Back && !PreviewNav.canGoBack(nav)) {
                        // Esc with nowhere to go back to hands the keyboard back to the Studio.
                        if (event.key == Key.Escape) release()
                    } else {
                        onNav(action)
                    }
                    true
                }
                .focusable()
                .drawWithContent {
                    drawContent()
                    if (focused && engaged) drawRect(FocusRing, style = Stroke(width = 3.dp.toPx()))
                },
        ) {
            Box(
                Modifier
                    .requiredSize(DESIGN_WIDTH, DESIGN_HEIGHT)
                    .graphicsLayer(scaleX = scale, scaleY = scale)
                    .clipToBounds(),
            ) {
                XmbFrame(model, nav, onNav, adjustOverlay, onAdjust, live, onBootFinished, screen)
            }
            if (engaged) {
                // Studio chrome over the frame, unscaled: hands the keyboard and mouse back.
                ReleaseButton(onClick = release, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))
            }
        }
    }
}

/**
 * The full frame at design size — also rendered offscreen for the bundle's preview.png, where the
 * default [nav] is the static Home frame (animation state starts at its target, so nothing moves).
 */
@Composable
fun XmbFrame(
    model: XmbPreviewModel,
    nav: PreviewNavState = PreviewNavState.HOME,
    onNav: (PreviewNavAction) -> Unit = {},
    adjustOverlay: AdjustOverlayState? = null,
    onAdjust: (AdjustAction) -> Unit = {},
    live: PreviewLiveSpec? = null,
    onBootFinished: () -> Unit = {},
    screen: PreviewScreen = PreviewScreen.XMB,
) {
    val runtime = rememberPreviewLive(live, model.waveStyle, hasWallpaper = model.wallpaper != null)
    CompositionLocalProvider(LocalPreviewLive provides runtime) {
        if (screen == PreviewScreen.XMB) {
            XmbFrameBody(model, nav, onNav, adjustOverlay, onAdjust, runtime, onBootFinished)
        } else {
            Box(Modifier.fillMaxSize()) {
                ScreenPreview(screen, model)
                runtime?.spec?.boot?.takeIf { it.isPlaying }?.let { BootOverlay(it, model, onBootFinished) }
            }
        }
    }
}

/**
 * The launcher's XMB background as the theme sets it: the wallpaper (plus its motion loop while live)
 * under the legibility scrim, or the wave. Screens drawn over the XMB start from this.
 */
@Composable
fun XmbBackdrop(model: XmbPreviewModel) {
    val live = LocalPreviewLive.current
    if (model.wallpaper != null) {
        // WallpaperBackground: image fills, plus the legibility scrim. No wave. The poster is
        // always underneath; the motion loop draws over it while it plays.
        Image(
            bitmap = model.wallpaper,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        live?.spec?.motionFile?.let { MotionWallpaperLayer(live, it) }
        Box(Modifier.fillMaxSize().background(WallpaperScrim))
    } else {
        // Not live (preview.png): the static pose, whatever the style.
        val params = live?.wave ?: WaveMotion.paramsFor(model.waveStyle)
        WaveBackground(model, params) { WaveMotion.timeSeconds(params, live?.elapsedMs?.longValue) }
    }
}

@Composable
private fun XmbFrameBody(
    model: XmbPreviewModel,
    nav: PreviewNavState,
    onNav: (PreviewNavAction) -> Unit,
    adjustOverlay: AdjustOverlayState?,
    onAdjust: (AdjustAction) -> Unit,
    live: PreviewLive?,
    onBootFinished: () -> Unit,
) {
    // While a boot sequence covers the whole frame, the frame underneath is not drawn at all; the
    // flag is read in the draw phase, so its change only redraws and never recomposes.
    val bootCover = rememberBootCover()
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().drawWithContent { if (!bootCover.coversFrame) drawContent() }) {
            XmbBackdrop(model)
            LayoutScaled(model) {
                XmbCross(model, nav, onNav)
                StatusStrip(model, PreviewNav.statusLabel(nav))
                if (nav.flyout == null && nav.search == null) IdleHintPill(nav)
                nav.search?.let {
                    GameSearchBox(
                        text = nav.filter.term,
                        onTextChange = { onNav(PreviewNavAction.SetSearch(it)) },
                        onConfirm = { onNav(PreviewNavAction.Enter) },
                        onCancel = { onNav(PreviewNavAction.Back) },
                    )
                }
            }
            NavMessage(nav)
            // Menus open in place over the frame, instantly, like the launcher's PspContextMenuOverlay.
            FlyoutPanel(model, nav, onNav)
            if (adjustOverlay != null) AdjustOverlayPanel(adjustOverlay, onAdjust)
            if (live?.motionFellBack == true) {
                PlaysOnDeviceBadge(
                    "Preview shows the poster \u2014 plays on the device",
                    Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 12.dp),
                )
            }
        }
        live?.spec?.boot?.takeIf { it.isPlaying }?.let { BootOverlay(it, model, onBootFinished, cover = bootCover) }
    }
}

/**
 * The launcher multiplies its density by the layout-adjust scale, so the cross lays out in a
 * (832 / scale) x (468 / scale) dp box that is then magnified back over the frame. Same here:
 * lay out in that box, scale it from the top-left corner. The wallpaper stays outside.
 */
@Composable
private fun LayoutScaled(model: XmbPreviewModel, content: @Composable BoxScope.() -> Unit) {
    val adjust = model.layoutAdjust
    val (w, h) = PreviewGeometry.layoutSize(adjust)
    Box(
        Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(Constraints.fixed(w.dp.roundToPx(), h.dp.roundToPx()))
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
            }
            .graphicsLayer(scaleX = adjust.scale, scaleY = adjust.scale, transformOrigin = TransformOrigin(0f, 0f)),
        content = content,
    )
}

/** XmbBackground: the diagonal gradient, two folds, and the off-centre bloom. [time] is read while drawing. */
@Composable
internal fun WaveBackground(model: XmbPreviewModel, params: WaveParams, time: () -> Float) {
    // The launcher's exact gradient call — including its default (diagonal) direction.
    val gradient = Brush.linearGradient(
        colorStops = arrayOf(
            0.00f to model.backgroundTop,
            0.30f to model.backgroundTop,
            0.70f to lerp(model.backgroundTop, model.backgroundBottom, 0.5f),
            1.00f to model.backgroundBottom,
        ),
    )
    Box(Modifier.fillMaxSize().background(gradient)) {
        // Its own layer: a wave tick re-records this canvas alone, not the whole frame above it.
        Canvas(Modifier.fillMaxSize().graphicsLayer()) {
            val t = time()
            for (fold in WaveMotion.FOLDS) drawFold(fold, params, t)
            // Soft off-centre light bloom.
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.10f), Color.Transparent),
                    center = center.copy(x = size.width * 0.48f, y = size.height * 0.30f),
                    radius = size.minDimension * 0.62f,
                ),
            )
        }
    }
}

// Port of XmbBackground.drawFold; the crest maths is WaveMotion's.
private fun DrawScope.drawFold(fold: WaveMotion.Fold, params: WaveParams, t: Float) {
    val w = size.width
    val h = size.height
    val n = 48
    val crestPath = Path()
    val fillPath = Path()
    fillPath.moveTo(0f, h)
    for (i in 0..n) {
        val xx = i / n.toFloat()
        val y = WaveMotion.crestY01(fold, params, xx, t) * h
        val x = xx * w
        if (i == 0) crestPath.moveTo(x, y) else crestPath.lineTo(x, y)
        fillPath.lineTo(x, y)
    }
    fillPath.lineTo(w, h)
    fillPath.close()
    val edge = WaveMotion.edgeAlpha(fold, params)
    drawPath(fillPath, color = Color.White.copy(alpha = WaveMotion.sheetAlpha(fold, params)))
    drawPath(crestPath, color = Color.White.copy(alpha = edge * 0.5f), style = Stroke(width = h * 0.022f))
    drawPath(crestPath, color = Color.White.copy(alpha = edge), style = Stroke(width = h * 0.006f))
}

/**
 * The XMB cross (XMBShell): category bar drawn on top of the item column. Position is instant;
 * the bar's selection slide, category icon size/alpha and row scale/alpha are springs; the item
 * list swaps through the launcher's AnimatedContent. Drilled in, the bar truncates, the cross
 * pins to the left margin, and a parent card column plus the children replace the item list.
 */
@Composable
private fun XmbCross(model: XmbPreviewModel, nav: PreviewNavState, onNav: (PreviewNavAction) -> Unit) {
    val spec = model.layout
    val adjust = model.layoutAdjust
    val (layoutW, layoutH) = PreviewGeometry.layoutSize(adjust)
    val drilled = nav.isDrilled
    val barTop = PreviewGeometry.barTop(spec, adjust, layoutH).dp
    val anchorTop = PreviewGeometry.anchorTop(spec, adjust, layoutH).dp
    val startPad = PreviewGeometry.startPad(spec, drilled, adjust, layoutW).dp
    val view = PreviewNav.view(nav)

    Box(Modifier.fillMaxSize().padding(top = spec.contentTopPaddingDp.dp)) {
        if (drilled) {
            val siblings = view.siblings.orEmpty()
            Box(Modifier.align(Alignment.TopStart).fillMaxSize().padding(start = startPad, end = 24.dp)) {
                // LEFT: the parent list, icon-only, with the accent ◀ trailing the drilled-into card.
                ItemList(
                    model, siblings, selectedIndex = view.siblingIndex,
                    barTop = barTop, anchorTop = anchorTop, showLabels = false, cursorOnSelected = true,
                    onClick = { i -> if (i == view.siblingIndex) onNav(PreviewNavAction.ClickSibling) },
                    modifier = Modifier.fillMaxHeight().width(SiblingColumnWidth),
                )
                // RIGHT: the children, one continuous column seated on the same anchor line.
                ChildColumn(
                    model, view.rows, selectedIndex = view.selected, anchorTop = anchorTop,
                    onClick = { onNav(PreviewNavAction.ClickRow(it)) },
                    modifier = Modifier.fillMaxSize().padding(start = PreviewGeometry.DRILL_CHILD_COLUMN_LEFT.dp),
                )
            }
        } else {
            AnimatedContent(
                targetState = nav.category,
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 8 })
                        .togetherWith(fadeOut(tween(160)) + slideOutVertically(tween(180)) { -it / 10 })
                        .using(SizeTransform(clip = false))
                },
                label = "previewCategoryItems",
                modifier = Modifier.align(Alignment.TopStart).fillMaxSize().padding(start = startPad, end = 24.dp),
            ) { category ->
                // During a switch both lists compose; only the settled category carries the cursor.
                val settled = category == nav.category
                ItemList(
                    model,
                    if (settled) view.rows else SampleContent.rootRows(category),
                    selectedIndex = if (settled) view.selected else -1,
                    barTop = barTop, anchorTop = anchorTop, showLabels = true, cursorOnSelected = false,
                    onClick = { onNav(PreviewNavAction.ClickRow(it)) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        CategoryBar(model, nav, onNav, layoutW, Modifier.align(Alignment.TopStart).offset(y = barTop))
    }
}

@Composable
private fun CategoryBar(
    model: XmbPreviewModel,
    nav: PreviewNavState,
    onNav: (PreviewNavAction) -> Unit,
    layoutWidth: Float,
    modifier: Modifier,
) {
    val spec = model.layout
    val drilled = nav.isDrilled
    val visible = PreviewGeometry.visibleCategoryCount(nav.category, SampleContent.categories.size, drilled)
    // The anchor snaps with the drill; only the selection slide glides (XMBCategoryBar).
    val anchor = PreviewGeometry.barLeft(spec, selected = 0, drilled, model.layoutAdjust, layoutWidth).dp
    val slide by animateDpAsState(
        targetValue = PreviewGeometry.barSlide(nav.category.coerceIn(0, visible - 1)).dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "previewCategorySlide",
    )
    Box(modifier.fillMaxWidth().height(CatBarHeight).clipToBounds()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            // Measured unbounded: the filmstrip is wider than the frame and the Box clips it.
            modifier = Modifier.offset(x = anchor + slide).wrapContentWidth(align = Alignment.Start, unbounded = true),
        ) {
            SampleContent.categories.take(visible).forEachIndexed { index, category ->
                CategoryCell(
                    model, category, selected = index == nav.category,
                    onClick = { onNav(PreviewNavAction.ClickCategory(index)) },
                )
            }
        }
    }
}

@Composable
private fun CategoryCell(
    model: XmbPreviewModel,
    category: SampleContent.Category,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val spec = model.layout
    val iconSize by animateDpAsState(
        targetValue = (if (selected) spec.categoryIconSelectedDp else spec.categoryIconDp).dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "previewCategoryIconSize",
    )
    val iconAlpha by animateFloatAsState(
        targetValue = model.legibility.categoryIconAlpha(selected),
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "previewCategoryAlpha",
    )
    // Only the active category shows its label; it keeps its slot so icons never shift.
    val labelAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "previewCategoryLabelAlpha",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(CategorySlotWidth)
            .height(CatBarHeight)
            .focusProperties { canFocus = false }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(top = 4.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(82.dp).alpha(iconAlpha)) {
            SlotIcon(model, category.slotKey, Modifier.size(iconSize), focused = selected)
        }
        LegibleLabel(
            text = category.label,
            protection = model.legibility.text,
            // XMBCategoryBar: themedText(if (isSelected) SelectedIcon else LabelInactive).
            color = model.textOr(if (selected) Color.White else LabelInactive),
            fontSize = if (selected) 15.sp else 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            shadow = if (selected) SelectedLabelShadow else null,
            textAlign = TextAlign.Center,
            fillWidth = true,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).alpha(labelAlpha),
        )
    }
}

/**
 * XMBItemList: the selected row seats directly under the bar with the rows after it below, and
 * only the bottom half of the previous row shows above the bar. Rows are placed, not scrolled, so
 * position is instant — only each row's scale and alpha animate.
 */
@Composable
private fun ItemList(
    model: XmbPreviewModel,
    rows: List<SampleContent.Row>,
    selectedIndex: Int,
    barTop: Dp,
    anchorTop: Dp,
    showLabels: Boolean,
    cursorOnSelected: Boolean,
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth().fillMaxHeight().clipToBounds()) {
        val rowsBelow = ((maxHeight.value - anchorTop.value) / RowHeight.value).toInt().coerceAtLeast(1)
        val sel = selectedIndex.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
        if (rows.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().offset(y = anchorTop)) {
                for (i in sel until minOf(rows.size, sel + rowsBelow)) {
                    ItemRow(
                        model, rows[i], selected = i == selectedIndex, showLabel = showLabels,
                        trailingCursor = cursorOnSelected && i == selectedIndex,
                        onClick = { onClick(i) },
                        modifier = Modifier.fillMaxWidth().height(RowHeight),
                    )
                }
            }
        }
        if (selectedIndex in 1..rows.lastIndex) {
            // A half-row window seated above the bar; the full row inside is bottom-aligned so its
            // top half clips off and it reads as coming in from behind the crossbar.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(RowHeight / 2)
                    .offset(y = barTop - RowHeight * model.layout.previousItemRiseRows)
                    .clipToBounds(),
                contentAlignment = Alignment.BottomStart,
            ) {
                ItemRow(
                    model, rows[selectedIndex - 1], selected = false, showLabel = showLabels, trailingCursor = false,
                    onClick = { onClick(selectedIndex - 1) },
                    modifier = Modifier.fillMaxWidth().requiredHeight(RowHeight),
                )
            }
        }
    }
}

/** XmbGameColumn: every child in one continuous column, the selected one on the anchor line. */
@Composable
private fun ChildColumn(
    model: XmbPreviewModel,
    rows: List<SampleContent.Row>,
    selectedIndex: Int,
    anchorTop: Dp,
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        if (rows.isEmpty()) return@BoxWithConstraints
        val sel = selectedIndex.coerceIn(0, rows.lastIndex)
        val above = (anchorTop.value / RowHeight.value).toInt() + 2
        val below = ((maxHeight.value - anchorTop.value) / RowHeight.value).toInt() + 2
        for (i in (sel - above).coerceAtLeast(0)..(sel + below).coerceAtMost(rows.lastIndex)) {
            ItemRow(
                model, rows[i], selected = i == selectedIndex, showLabel = true, trailingCursor = false,
                onClick = { onClick(i) },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .height(RowHeight)
                    .offset(y = anchorTop + RowHeight * (i - sel)),
            )
        }
    }
}

/** XmbVerticalListRow: spring scale 1.06/0.9 about the leading-icon centre, alpha .68 when unselected. */
@Composable
private fun ItemRow(
    model: XmbPreviewModel,
    row: SampleContent.Row,
    selected: Boolean,
    showLabel: Boolean,
    trailingCursor: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spec = model.layout
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.06f else 0.9f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "previewRowScale",
    )
    val rowAlpha by animateFloatAsState(
        targetValue = when {
            // "Solid Unfocused Icons" skips the unfocused dim; selection still reads by scale + label.
            selected || model.legibility.solidUnfocusedIcons -> 1f
            row.leading == SampleContent.Leading.EMPTY -> 0.5f
            else -> 0.68f
        },
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "previewRowAlpha",
    )
    val iconCenterPx = with(LocalDensity.current) { PreviewGeometry.leadingIconCenter(spec).dp.toPx() }
    var rowWidthPx by remember { mutableFloatStateOf(0f) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .onSizeChanged { rowWidthPx = it.width.toFloat() }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                // Pivot on the leading-icon centre so icons stay on the caticon line while scaling.
                if (rowWidthPx > 0f) transformOrigin = TransformOrigin((iconCenterPx / rowWidthPx).coerceIn(0f, 1f), 0.5f)
            }
            .alpha(rowAlpha),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f, fill = false)
                .height(TapTargetHeight)
                .focusProperties { canFocus = false }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
                .padding(horizontal = 18.dp),
        ) {
            LeadingIcon(model, row, selected)
            // A game's text (the UMD slot's too) shows on the selected card only (no logo art in the sample).
            val gameRow = row.isGame || row.leading == SampleContent.Leading.UMD
            if (showLabel && (!gameRow || selected)) {
                Column(Modifier.weight(1f, fill = false).padding(start = spec.itemTextStartGapDp.dp)) {
                    LegibleLabel(
                        text = row.title,
                        protection = model.legibility.text,
                        // XMBItemList: themedText(if (isSelected) PrimaryText else InactiveText).
                        color = model.textOr(if (selected) Color.White else LabelInactive),
                        fontSize = if (selected) spec.itemTextSelectedSp.sp else spec.itemTextSp.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        shadow = if (selected) SelectedLabelShadow else null,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (row.subtitle != null) {
                        LegibleLabel(
                            text = row.subtitle,
                            protection = model.legibility.text,
                            // XMBItemList: themedSubText(SecondaryText).
                            color = model.subTextOr(SecondaryText),
                            fontSize = if (selected) 12.sp else 11.sp,
                            shadow = SubtitleShadow,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                    }
                }
            }
            if (trailingCursor) {
                Text(
                    text = "◀",
                    color = model.drillCursor,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

/** XMBItemList.XmbItemLeadingIcon, for the leading kinds the sample uses. */
@Composable
private fun LeadingIcon(model: XmbPreviewModel, row: SampleContent.Row, selected: Boolean) {
    val spec = model.layout
    when (row.leading) {
        SampleContent.Leading.EMPTY -> Spacer(Modifier.width(12.dp))
        SampleContent.Leading.GAME -> {
            GameLetterTile(row.title, Color(row.accentArgb ?: GameTileFallbackAccent), Modifier.size(GameTileWidth, GameTileHeight))
            Spacer(Modifier.width(GameTileTextGap))
        }
        // A file with no thumbnail: its glyph framed in a 60x40 tile (a photo or video frame grab on device).
        SampleContent.Leading.THUMB -> Box(Modifier.width(spec.itemIconSlotDp.dp), contentAlignment = Alignment.Center) {
            FramedGlyph(model, checkNotNull(row.slotKey), Modifier.size(width = 60.dp, height = 40.dp), glyph = 28.dp, selected)
        }
        // A track with no cover art: its note framed in a 56 dp square.
        SampleContent.Leading.COVER -> Box(Modifier.width(spec.itemIconSlotDp.dp), contentAlignment = Alignment.Center) {
            FramedGlyph(model, checkNotNull(row.slotKey), Modifier.size(56.dp), glyph = 32.dp, selected)
        }
        // The PSP's UMD, whatever the game's platform: the item_umd slot — the bundled silhouette in
        // the icon colour, or the theme's art as authored. (On device a focused, read slot turns into
        // the game's ICON0; the preview keeps the UMD.)
        SampleContent.Leading.UMD -> {
            Box(Modifier.width(spec.itemIconSlotDp.dp), contentAlignment = Alignment.Center) {
                SlotIcon(model, "item_umd", Modifier.size(spec.itemIconDp.dp), focused = selected)
            }
        }
        // The Shiba Coins player card: "Lv N" in a ring, both in the icon colour.
        SampleContent.Leading.LEVEL -> Box(Modifier.width(spec.itemIconSlotDp.dp), contentAlignment = Alignment.Center) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size((spec.itemIconDp * 0.8f).dp)
                    .clip(CircleShape)
                    .border(2.dp, model.iconTint, CircleShape),
            ) {
                Text(row.badge.orEmpty(), color = model.iconTint, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
        SampleContent.Leading.APP -> Box(Modifier.width(spec.itemIconSlotDp.dp), contentAlignment = Alignment.Center) {
            AppIconStandIn(row.title)
        }
        SampleContent.Leading.SLOT -> {
            val key = checkNotNull(row.slotKey) { "a SLOT row needs a slot key" }
            // Raster art (memory cards, console art) fills the spec's icon size; vector glyphs have their own.
            val raster = key in StudioIconSet.RESOURCE_SLOTS || key.startsWith("sysicon_")
            val size = if (raster) spec.itemIconDp else StudioIconSet.glyphSizeDp(key, spec.itemIconDp)
            Box(Modifier.width(spec.itemIconSlotDp.dp), contentAlignment = Alignment.Center) {
                SlotIcon(model, key, Modifier.size(size.dp), focused = selected)
            }
        }
    }
}

// GameIconView.PspIcon0Icon: the ICON0 tile a game with no artwork shows (the default Custom Icon display).
private val GameTileWidth = 126.dp
private val GameTileHeight = 70.dp
private val GameTileTextGap = 16.dp
private val GameTileShape = RoundedCornerShape(4.dp)
private val GameTileBacking = Color(0xFF0A0A0F)
private val GameTileBorder = Color(0x55FFFFFF)
private const val GameTileFallbackAccent = 0xFF4A9EFF

// XmbItemLeadingIcon's fallback frame for files and tracks without art.
private val GlyphFrame = Color(0xFF1B1B27)

@Composable
private fun FramedGlyph(model: XmbPreviewModel, key: String, modifier: Modifier, glyph: Dp, selected: Boolean) {
    Box(contentAlignment = Alignment.Center, modifier = modifier.clip(RoundedCornerShape(6.dp)).background(GlyphFrame)) {
        SlotIcon(model, key, Modifier.size(glyph), focused = selected)
    }
}

@Composable
internal fun GameLetterTile(title: String, accent: Color, modifier: Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(GameTileShape)
            .background(GameTileBacking)
            .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.6f), GameTileBacking)))
            .border(1.dp, GameTileBorder, GameTileShape),
    ) {
        Text(
            text = (title.firstOrNull()?.uppercaseChar() ?: '?').toString(),
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        // The top gloss strip.
        Box(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .fillMaxHeight(0.3f)
                .background(Brush.verticalGradient(listOf(Color(0x18FFFFFF), Color.Transparent))),
        )
    }
}

/**
 * An installed app's row shows the app's own launcher icon (AppListIcon, 48 dp), which no theme
 * changes; the preview cannot load real app icons, so it draws a neutral round badge in its place.
 */
@Composable
private fun AppIconStandIn(title: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(48.dp).clip(CircleShape).background(Color(0xFFE9ECF2)),
    ) {
        Text(
            text = (title.firstOrNull()?.uppercaseChar() ?: '?').toString(),
            color = Color(0xFF3B4A66),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** What Enter said on a leaf: the preview opens nothing, so it says where the real thing happens. */
@Composable
private fun BoxScope.NavMessage(nav: PreviewNavState) {
    val message = nav.message ?: return
    Text(
        text = message,
        color = Color.White,
        fontSize = 13.sp,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 14.dp)
            .background(Color(0x99000000), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/**
 * The status strip's clock (epoch ms). The real time by default; renders that are compared pixel for
 * pixel across compositions pin it, or a minute ticking over between them changes the time text.
 */
internal val LocalPreviewClock = androidx.compose.runtime.staticCompositionLocalOf<() -> Long> { { System.currentTimeMillis() } }

// XmbStatusStrip.kt: palette, metrics and the sample device state the strip reads.
private val StripPrimary = Color(0xFFEEEEEE)
private val StripMuted = Color(0xAAEEEEEE)
private val StripSep = Color(0x55FFFFFF)
private val MeterInactive = Color(0x40EEEEEE)

/**
 * XmbStatusStrip's icon tints: the strip's greys, repainted by the theme's Main text colour at their
 * own weight (themedText), so a muted icon stays muted and an unlit meter bar stays faint. The Sub
 * colour never reaches them, and a theme's own status art still draws as authored.
 */
internal object StatusStripTints {
    /** The built-in status glyphs (bell, controller, Bluetooth, battery): muted. */
    fun icon(model: XmbPreviewModel): Color = model.textOr(StripMuted)

    /** The date: the Main colour at full weight, matching the time, once set; else the strip's muted grey. */
    fun date(model: XmbPreviewModel): Color = model.textOr(StripMuted, 1f)

    /** A Wi-Fi / signal meter segment, lit or not. */
    fun meter(model: XmbPreviewModel, lit: Boolean): Color = model.textOr(if (lit) StripPrimary else MeterInactive)
}
private val StripHeight = 28.dp
private val StripSidePadding = 20.dp
private val StripFontSize = 12.sp
private const val SampleBatteryPercent = 84

/**
 * XmbPspStatusStrip as a controller user sees it: date | time [| sort label] on the left; bell,
 * controller, Bluetooth, Wi-Fi, battery and percentage on the right. The sample device has a
 * controller connected, Bluetooth on, full Wi-Fi, no cellular, and is unplugged. Status icons draw
 * in the strip's muted white (not the theme icon colour); a theme override draws as authored.
 */
@Composable
private fun BoxScope.StatusStrip(model: XmbPreviewModel, sortLabel: String?) {
    // Read in composition (a CompositionLocal), formatted once per composition below.
    val clock = LocalPreviewClock.current
    val (date, time) = remember(clock) {
        val now = java.util.Date(clock())
        java.text.SimpleDateFormat("MM/dd/yyyy").format(now) to java.text.SimpleDateFormat("h:mm a").format(now)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .height(StripHeight)
            .padding(horizontal = StripSidePadding),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // XmbStatusStrip: the date, time and sort label are all main text (the date at its muted weight).
            Text(date, color = StatusStripTints.date(model), fontSize = StripFontSize, fontWeight = FontWeight.Normal)
            StripSeparator()
            Text(time, color = model.textOr(StripPrimary), fontSize = StripFontSize, fontWeight = FontWeight.Medium)
            if (sortLabel != null) {
                StripSeparator()
                Text(sortLabel, color = model.textOr(StripPrimary), fontSize = StripFontSize, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            StatusGlyph(model, "status_notifications", Modifier.size(13.dp))
            StatusGlyph(model, "status_controller", Modifier.size(15.dp))
            StatusGlyph(model, "status_bluetooth", Modifier.size(width = 9.dp, height = 13.dp))
            val wifi = model.iconOverrides["status_wifi"]
            if (wifi != null) {
                Image(bitmap = wifi, contentDescription = null, modifier = Modifier.size(width = 16.dp, height = 13.dp))
            } else {
                WifiMeter(model, level = 4, Modifier.size(width = 16.dp, height = 13.dp))
            }
            StatusGlyph(model, batterySlotKey(SampleBatteryPercent), Modifier.size(width = 24.dp, height = 11.dp))
            Text("$SampleBatteryPercent%", color = model.textOr(StripPrimary), fontSize = StripFontSize, fontWeight = FontWeight.Medium)
        }
    }
}

/** XmbStatusIcons.batterySlotKey while unplugged: the fill tier by level. */
private fun batterySlotKey(level: Int): String = when {
    level >= 76 -> "status_battery_full"
    level >= 51 -> "status_battery_high"
    level >= 26 -> "status_battery_medium"
    else -> "status_battery_low"
}

@Composable
private fun StripSeparator() {
    Box(Modifier.width(1.dp).height(10.dp).background(StripSep))
}

/** XmbStatusStrip.StatusIcon: the built-in glyph in the strip's muted tint (Main colour once set), or the theme's art as authored. */
@Composable
private fun StatusGlyph(model: XmbPreviewModel, key: String, modifier: Modifier) {
    val override = model.iconOverrides[key]
    if (override != null) {
        Image(bitmap = override, contentDescription = null, modifier = modifier)
    } else {
        Image(
            painter = StudioIconSet.defaultPainter(key),
            contentDescription = null,
            colorFilter = ColorFilter.tint(StatusStripTints.icon(model)),
            modifier = modifier,
        )
    }
}

/** XmbStatusStrip.WifiMeter: a base dot and three arcs, lit up to [level] (0..4). */
@Composable
private fun WifiMeter(model: XmbPreviewModel, level: Int, modifier: Modifier) {
    val lit = StatusStripTints.meter(model, lit = true)
    val unlit = StatusStripTints.meter(model, lit = false)
    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height * 0.92f
        val maxR = size.height * 0.9f
        val stroke = size.height * 0.11f
        fun color(threshold: Int) = if (level >= threshold) lit else unlit
        drawCircle(color = color(1), radius = stroke * 1.1f, center = Offset(cx, cy))
        for (i in 1..3) {
            val r = maxR * i / 3f
            drawArc(
                color = color(i + 1),
                startAngle = 225f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(cx - r, cy - r),
                size = Size(r * 2, r * 2),
                style = Stroke(width = stroke),
            )
        }
    }
}

// ContextMenuHint / core-ui ControllerHintBar(compact): the idle hint pill, with the Xbox glyphs for
// the default bindings (CHANGE_SORT = X, OPEN_CONTEXT_MENU = Y, HOME = Menu).
private val HintTextShadow = ThemeTokens.TextShadow

/**
 * The idle hint pill in the bottom-right corner, as it stands once a controller user has been idle:
 * Sort (or Filter) only where X does something, Options only where the row has a menu, and
 * Notifications always. Nothing shows when neither of the first two applies.
 */
@Composable
private fun BoxScope.IdleHintPill(nav: PreviewNavState) {
    val hint = PreviewNav.idleHint(nav) ?: return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(bottom = 24.dp, end = 20.dp)
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        hint.sortLabel?.let { HintPrompt("xmb/ctl_xb_face_west.png", it) }
        if (hint.options) HintPrompt("xmb/ctl_xb_face_north.png", "Options")
        HintPrompt("xmb/ctl_xb_start.png", "Notifications")
    }
}

@Composable
private fun HintPrompt(glyph: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Image(StudioIconSet.chromePainter(glyph), contentDescription = null, modifier = Modifier.size(15.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            style = TextStyle(shadow = HintTextShadow),
        )
    }
}

/**
 * One draw path for every slot: a custom icon renders as-authored (untinted, like PSP theme
 * icons); the built-in glyph follows the unified icon color via SrcIn — the PortalIcon rule. With an
 * icon-legibility style set, a matte copy sits behind the glyph (IconMatteSurface), one draw node.
 * A GIF override animates only while [focused] in a live preview; otherwise it is its frame 1.
 */
/**
 * A themeable slot exactly as the launcher draws it (override as authored, else the built-in glyph in
 * the icon colour, with the theme's icon matte). For the opened screens to share.
 */
@Composable
fun PreviewSlotIcon(model: XmbPreviewModel, key: String, modifier: Modifier, focused: Boolean = false) =
    SlotIcon(model, key, modifier, focused)

@Composable
private fun SlotIcon(model: XmbPreviewModel, key: String, modifier: Modifier, focused: Boolean = false) {
    val override = model.iconOverrides[key]
    val live = LocalPreviewLive.current
    val animation = rememberGifAnimation(if (GifFrames.animates(key, focused, live?.spec)) live?.spec?.iconGifs?.get(key) else null)
    val painter: Painter = when {
        animation != null && live != null -> remember(animation, live.elapsedMs) { GifPainter(animation, live.elapsedMs) }
        override != null -> remember(override) { BitmapPainter(override) }
        else -> StudioIconSet.defaultPainter(key)
    }
    val glyphTint = if (override != null || StudioIconSet.isFullColour(key)) null else model.iconTint
    val matte = model.legibility.matteColor(glyphTint ?: Color.White)
    if (matte == null) {
        Image(
            painter = painter,
            contentDescription = null,
            colorFilter = glyphTint?.let { ColorFilter.tint(it, BlendMode.SrcIn) },
            modifier = modifier,
        )
        return
    }
    val offsets = model.legibility.matteOffsets()
    val radiusPx = with(LocalDensity.current) { model.legibility.matteRadiusDp.dp.toPx() }
    val matteFilter = ColorFilter.tint(matte, BlendMode.SrcIn)
    val glyphFilter = glyphTint?.let { ColorFilter.tint(it, BlendMode.SrcIn) }
    Box(
        modifier.drawWithCache {
            // Fit exactly the way ContentScale would, so every matte copy sits on the glyph's own contour.
            val intrinsic = painter.intrinsicSize
            val dst = if (intrinsic.isSpecified) {
                val factor = ContentScale.Fit.computeScaleFactor(intrinsic, size)
                Size(intrinsic.width * factor.scaleX, intrinsic.height * factor.scaleY)
            } else {
                size
            }
            val origin = Offset((size.width - dst.width) / 2f, (size.height - dst.height) / 2f)
            onDrawBehind {
                for (o in offsets) {
                    translate(origin.x + o.x * radiusPx, origin.y + o.y * radiusPx) {
                        with(painter) { draw(dst, colorFilter = matteFilter) }
                    }
                }
                translate(origin.x, origin.y) { with(painter) { draw(dst, colorFilter = glyphFilter) } }
            }
        },
    )
}

private val PlatePadX = 6.dp
private val PlatePadY = 1.dp
private val PlateRadius = 6.dp

/**
 * A label under the theme's text-legibility style: a drop shadow (the launcher's default and AUTO
 * floor), none, a stroked outline copy behind the fill, or a text-shaped plate drawn behind it with
 * no extra layout. [shadow] is the label's own shadow; styles other than SHADOW drop it.
 */
@Composable
private fun LegibleLabel(
    text: String,
    protection: LabelProtection,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    shadow: Shadow? = null,
    textAlign: TextAlign? = null,
    fillWidth: Boolean = false,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val fit = if (fillWidth) Modifier.fillMaxWidth() else Modifier
    val style = TextStyle(shadow = if (protection.hasShadow) shadow else null)
    when (protection) {
        LabelProtection.OUTLINE -> {
            val strokePx = with(LocalDensity.current) { 3.dp.toPx() }
            Box(modifier) {
                Text(
                    text = text, fontSize = fontSize, fontWeight = fontWeight, textAlign = textAlign,
                    maxLines = 1, overflow = overflow, modifier = fit,
                    style = TextStyle(
                        color = Color.Black.copy(alpha = 0.85f),
                        drawStyle = Stroke(width = strokePx, join = StrokeJoin.Round),
                    ),
                )
                Text(
                    text = text, color = color, fontSize = fontSize, fontWeight = fontWeight, textAlign = textAlign,
                    maxLines = 1, overflow = overflow, modifier = fit,
                )
            }
        }
        LabelProtection.PLATE -> {
            var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
            val padX = with(LocalDensity.current) { PlatePadX.toPx() }
            val padY = with(LocalDensity.current) { PlatePadY.toPx() }
            val radius = with(LocalDensity.current) { PlateRadius.toPx() }
            Text(
                text = text, color = color, fontSize = fontSize, fontWeight = fontWeight, textAlign = textAlign,
                maxLines = 1, overflow = overflow, style = style,
                onTextLayout = { layout = it },
                modifier = modifier.then(fit).drawBehind {
                    val result = layout ?: return@drawBehind
                    for (line in 0 until result.lineCount) {
                        val left = result.getLineLeft(line) - padX
                        val top = result.getLineTop(line) - padY
                        drawRoundRect(
                            color = Color.Black.copy(alpha = PreviewLegibility.PLATE_ALPHA),
                            topLeft = Offset(left, top),
                            size = Size(result.getLineRight(line) + padX - left, result.getLineBottom(line) + padY - top),
                            cornerRadius = CornerRadius(radius),
                        )
                    }
                },
            )
        }
        else -> Text(
            text = text, color = color, fontSize = fontSize, fontWeight = fontWeight, textAlign = textAlign,
            maxLines = 1, overflow = overflow, style = style, modifier = modifier.then(fit),
        )
    }
}

/**
 * Releases the engaged preview: the keyboard and mouse go back to the Studio, and the next click
 * on the preview engages it again. Never takes focus itself, so pressing it cannot strand the keys.
 */
@Composable
private fun ReleaseButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xE0202024))
            .border(1.dp, FocusRing.copy(alpha = 0.6f), RoundedCornerShape(50))
            .focusProperties { canFocus = false }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text("Cancel", color = Color.White, fontSize = 12.sp)
        Text("  Esc", color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
    }
}
