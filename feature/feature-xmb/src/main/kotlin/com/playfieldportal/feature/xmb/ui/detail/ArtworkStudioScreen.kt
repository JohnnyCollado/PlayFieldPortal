package com.playfieldportal.feature.xmb.ui.detail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed as lazyItemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.components.ControllerPrompt
import com.playfieldportal.core.ui.components.ControllerPromptBar
import com.playfieldportal.core.ui.components.ControllerPromptItem
import com.playfieldportal.core.ui.theme.LocalPFPColors
import com.playfieldportal.core.ui.theme.menuCursorEdge

// The gap between grid tiles. Must equal StudioGridCapacity's GAP_DP, or the tiles drawn here stop
// matching the capacity the ViewModel paged for.
private val STUDIO_GRID_GAP = 8.dp

// AD-19: the Current rail widens on a large window. Every other band is fixed in dp (AD-16), so a
// bigger screen spends its extra width on grid columns, not on chrome.
private const val STUDIO_WIDE_WINDOW_DP = 1000

/**
 * Fullscreen Artwork Studio — controller-first artwork browser/editor for one game.
 * Layout follows the approved mock: destination tabs (LB/RB) → current-artwork panel +
 * available-artwork grid, source row (Left/Right in the SOURCES zone),
 * A = candidate preview → Apply, B = back, X = search, Y = per-slot options,
 * START = add the picked tiles on a multi-asset tab (SteamGridDB's mature filter is in the Y menu).
 *
 * (L2/R2 are unbound: no GamepadAction maps to KEYCODE_BUTTON_L2/R2 in GamepadBinding, so the
 * old "L2/R2 switch sources" line here described a binding that never existed.)
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ArtworkStudioScreen(
    gameId: Long,
    onClose: () -> Unit,
    pendingGamepadAction: GamepadAction? = null,
    onGamepadActionConsumed: () -> Unit = {},
    // Touch presentation, resolved by the caller exactly as for Game Detail: tappable controls
    // become pills. The Studio replaces Game Detail while open, so it reports touches itself.
    showTouchControls: Boolean = true,
    onTouchInput: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: ArtworkStudioViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(gameId) { viewModel.load(gameId) }
    LaunchedEffect(state.closed) {
        if (state.closed) {
            onClose()
            viewModel.consumeClosed()   // clear immediately so reopening doesn't self-close
        }
    }
    LaunchedEffect(pendingGamepadAction) {
        if (pendingGamepadAction != null) {
            viewModel.handleGamepadAction(pendingGamepadAction)
            onGamepadActionConsumed()
        }
    }

    // Local file picker — mime set follows the destination kind.
    val localPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.applyLocal(uri)
    }
    LaunchedEffect(state.localPickKind) {
        val kind = state.localPickKind ?: return@LaunchedEffect
        val mimes = when (kind) {
            com.playfieldportal.feature.artwork.store.ArtworkKind.MANUAL -> arrayOf("application/pdf")
            com.playfieldportal.feature.artwork.store.ArtworkKind.VIDEO,
            com.playfieldportal.feature.artwork.store.ArtworkKind.ICON1  -> arrayOf("video/mp4", "video/webm", "video/*")
            else -> arrayOf("image/png", "image/jpeg", "image/webp")
        }
        localPicker.launch(mimes)
        viewModel.consumeLocalPick()
    }

    ArtworkStudioContent(
        state = state,
        actions = viewModel,
        showTouchControls = showTouchControls,
        onTouchInput = onTouchInput,
        modifier = modifier,
    )
}

/**
 * The Studio itself, stateless: [state] in, [actions] out. Split from [ArtworkStudioScreen], which
 * owns the ViewModel, loading, closing and the file picker, so that this can be previewed with
 * sample state (ArtworkStudioPreview.kt).
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun ArtworkStudioContent(
    state: ArtworkStudioUiState,
    actions: ArtworkStudioActions,
    showTouchControls: Boolean,
    onTouchInput: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pfpColors = LocalPFPColors.current
    val accent = menuCursorEdge()

    Box(
        modifier = modifier
            .fillMaxSize()
            // Any touch marks the input source as touch (revealing the pills), without consuming
            // the event, as Game Detail does.
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false); onTouchInput() } }
            .background(
                Brush.verticalGradient(
                    0f to pfpColors.backgroundTop.copy(alpha = 0.97f),
                    1f to pfpColors.backgroundBottom,
                )
            ),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 14.dp)) {

            // ── Header row (36 dp) — back, game title, and the query the providers are asked for ──
            // Studio-local rather than the shared DetailBreadcrumb: this row carries a trailing
            // query field (L.3), and the breadcrumb's "Artwork Studio › category › source" trail
            // is what the approved mock replaces with flat tabs. The back arrow still walks the
            // level ladder exactly like B (grid → sources → categories → close).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().height(36.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { actions.handleGamepadAction(GamepadAction.BACK) },
                ) {
                    Text(
                        "◀",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            state.game?.displayTitle ?: "Artwork Studio",
                            color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 320.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            buildString {
                                append("Artwork Studio")
                                state.game?.platformId?.takeIf { it.isNotBlank() }
                                    ?.let { append(" · ${it.uppercase()}") }
                            },
                            color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp,
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                // ── Query field (Square / tap) — the query the providers are actually asked for ──
                // Editable and non-destructive: it never renames the game, and Reset puts the
                // game's own title back. Submit-only, so no provider is hit per keystroke.
                // L.6: a guaranteed gap. At 833 dp a capped title plus "Artwork Studio · WINDOWS"
                // left the weighted spacer ~2 dp, so the subtitle ran into the field.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .widthIn(max = 300.dp)
                        .height(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.10f))
                        .border(
                            1.dp,
                            if (state.queryIsCustom) accent.copy(alpha = 0.6f) else Color.Transparent,
                            RoundedCornerShape(8.dp),
                        )
                        .clickable(onClick = actions::openSearch)
                        .padding(horizontal = 10.dp),
                ) {
                    // The glyph follows the user's controller, which is why it is drawn through
                    // the same mapping table the input handler reads.
                    ControllerPrompt(
                        action = GamepadAction.CHANGE_SORT,
                        label = "",
                        glyphSize = 13.dp,
                        labelColor = Color.White.copy(alpha = 0.45f),
                    )
                    Text(
                        state.query.ifBlank { "—" },
                        color = if (state.queryIsCustom) accent else Color.White.copy(alpha = 0.92f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).padding(start = 4.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    if (state.queryIsCustom) {
                        Text(
                            "Reset",
                            color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(onClick = actions::resetSearchToTitle)
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                        )
                    } else {
                        Text(
                            "game title",
                            color = Color.White.copy(alpha = 0.45f), fontSize = 9.sp,
                        )
                    }
                }
            }

            // ── Destination tabs (AD-18: flat, one press apart) — the LB/RB glyphs sit at both
            // ends of the scrolling chip row, so a narrower screen keeps the selected chip visible.

            val tabListState = rememberLazyListState()
            LaunchedEffect(state.tabIndex) { tabListState.animateScrollToItem(state.tabIndex) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().height(28.dp),
            ) {
                ControllerPrompt(
                    action = GamepadAction.PREV_CATEGORY,
                    label = "",
                    glyphSize = 14.dp,
                    labelColor = Color.White.copy(alpha = 0.45f),
                    modifier = Modifier.padding(end = 6.dp),
                )
                LazyRow(
                    state = tabListState,
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    lazyItemsIndexed(STUDIO_TABS) { index, tab ->
                        val selected = state.tabIndex == index
                        val focusedZone = state.zone == StudioZone.TABS && selected
                        Box(
                            modifier = Modifier
                                .height(24.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (selected) accent.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.07f))
                                .border(
                                    1.dp,
                                    if (focusedZone) accent else Color.Transparent,
                                    RoundedCornerShape(6.dp),
                                )
                                .clickable { actions.selectTab(index) }
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                tab.label,
                                color = if (selected) Color.White else Color.White.copy(alpha = 0.62f),
                                fontSize = 10.5.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1,
                            )
                        }
                    }
                }
                ControllerPrompt(
                    action = GamepadAction.NEXT_CATEGORY,
                    label = "",
                    glyphSize = 14.dp,
                    labelColor = Color.White.copy(alpha = 0.45f),
                    modifier = Modifier.padding(start = 6.dp),
                )
            }

            // L.4: the tab's contract caption moved into the rail, so the tabs band is one line.
            // The wide-window rail width is read once here rather than re-derived inside the rail.
            val railWidth =
                if (LocalConfiguration.current.screenWidthDp >= STUDIO_WIDE_WINDOW_DP) 200.dp else 150.dp

            Row(Modifier.weight(1f)) {

                // ── Current artwork rail (AD-19: narrow on a handheld, wider on a tablet) ──
                Column(Modifier.width(railWidth).fillMaxHeight()) {
                    // L.6: the thumbnail is the inner column's ONLY weighted child, and the message
                    // sits under that column at the rail's bottom. At 914 × 411 dp a portrait
                    // thumbnail sized from the rail's width (~214 dp) pushed the Options pill off
                    // the bottom. Weighted `fill = false`, it shrinks to the height left and keeps
                    // its aspect, and Options still sits directly under it. A weighted spacer beside
                    // it would split the free height and cap the thumbnail at half.
                    Column(Modifier.weight(1f)) {
                        // The category and its display rule both live here now, so the header and tab
                        // bands stay one line each (AD-16).
                        Text(
                            STUDIO_TABS[state.tabIndex].label,
                            color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        )
                        // L.6: an explicit line height, or the theme's 24 sp bodyLarge spaces a wrapped
                        // caption ("XMB tile (Physical Media mode) · natural aspect") like two paragraphs.
                        Text(
                            STUDIO_TABS[state.tabIndex].contract,
                            color = Color.White.copy(alpha = 0.55f), fontSize = 9.5.sp, lineHeight = 12.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        // The thumbnail is drawn in the ACTIVE TAB's tile shape rather than a fixed
                        // 150 dp box, so the preview is judged the way the grid judges it. (The
                        // mockup's 144×80 is ICON0's own crop target, which is that tab's data, not
                        // its tile shape.) No fillMaxWidth: aspectRatio takes the rail's width when
                        // the height allows it, and narrows rather than stretches when it does not.
                        Box(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .aspectRatio(STUDIO_TABS[state.tabIndex].tileClass.aspect.toFloat())
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF080E1E).copy(alpha = 0.55f))
                                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            val curKindName = STUDIO_TABS[state.tabIndex].kind.name
                            when {
                                state.currentUri != null && curKindName in setOf("MANUAL", "VIDEO", "ICON1") -> Text(
                                    when (curKindName) {
                                        "MANUAL" -> "PDF stored"
                                        "ICON1"  -> "Icon video stored"
                                        else     -> "Video stored"
                                    },
                                    color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp,
                                )
                                // key(previewVersion) forces a fresh AsyncImage after an apply so the
                                // preview reloads even when the portable library reused the same URI.
                                state.currentUri != null -> androidx.compose.runtime.key(state.previewVersion) {
                                    AsyncImage(
                                        model = state.currentUri,
                                        contentDescription = null,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxSize().padding(6.dp),
                                    )
                                }
                                else -> Text("No artwork set", color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        // The Y hint replaces the old "Ⓨ · OPTIONS" pill; in touch mode it is an Options pill.
                        StudioOptionsControl(showTouchControls = showTouchControls, onClick = actions::openActions)
                    }
                    // L.5: paging moved to the page line under the grid.
                    state.message?.let {
                        Text(
                            it, color = accent, fontSize = 11.sp,
                            modifier = Modifier.clickable(onClick = actions::dismissMessage),
                        )
                    }
                }

                Spacer(Modifier.width(18.dp))

                // ── Available artwork ─────────────────────────────────────────
                Column(Modifier.weight(1f).fillMaxHeight()) {

                    // ── Sources row (24 dp) — chips styled like the tabs, plus SteamGridDB's mature badge ──
                    // Scrolls rather than clips if a narrow screen cannot fit every chip; the row's
                    // height never changes, so the grid slot below it stays measured.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        val sources = actions.sourcesForTab()
                        sources.forEachIndexed { index, source ->
                            val selected = state.sourceIndex == index
                            val focusedZone = state.zone == StudioZone.SOURCES && selected
                            // Disabled, not hidden: a keyless provider, or one with nothing for this
                            // category, keeps its place and says why.
                            val badge = actions.sourceBadge(source)
                            val available = badge == null
                            Box(
                                modifier = Modifier
                                    .height(24.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (selected) accent.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.07f))
                                    .border(1.dp, if (focusedZone) accent else Color.Transparent, RoundedCornerShape(6.dp))
                                    .clickable {
                                        actions.selectSource(index)
                                        if (source == StudioSource.LOCAL) actions.requestLocalPick()
                                    }
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    if (badge == null) source.label else "${source.label} · $badge",
                                    color = when {
                                        !available -> Color.White.copy(alpha = 0.28f)
                                        selected   -> Color.White
                                        else       -> Color.White.copy(alpha = 0.62f)
                                    },
                                    fontSize = 10.5.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    maxLines = 1,
                                )
                            }
                        }
                        // The mature filter's state, tappable for touch. A controller sets it from the
                        // Y menu: START adds picks now (task 5.2), so the badge no longer shows a glyph.
                        val sgdbActive = sources.getOrNull(state.sourceIndex) == StudioSource.STEAMGRIDDB
                        if (sgdbActive) {
                            Box(
                                modifier = Modifier
                                    .padding(start = 4.dp)
                                    .height(18.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(Color.Black.copy(alpha = 0.18f))
                                    .clickable(onClick = actions::toggleNsfw)
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    if (state.includeNsfw) "Mature on" else "Mature off",
                                    color = if (state.includeNsfw) Color(0xFFE57373) else Color.White.copy(alpha = 0.6f),
                                    fontSize = 9.sp, lineHeight = 12.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                    }

                    // ── Match line (22 dp, task 2.3) ──────────────────────────
                    // Who the active source thinks this game is. Only shown for a source that HAS
                    // an identity: Local files are the user's own and nothing identifies them.
                    // The band keeps its height even when empty, so switching to Local File does not
                    // grow the grid slot and re-page the results (AD-16, AD-17).
                    Spacer(Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().height(22.dp),
                    ) {
                        if (state.matchProvider != null) {
                            val matched = state.matchTitle
                            if (!state.matchResolving && matched != null) {
                                com.playfieldportal.core.ui.components.PfpCheckMark(Color(0xFF66BB6A), size = 12.dp)
                            } else {
                                Text(
                                    if (state.matchResolving) "◌" else "!",
                                    color = if (state.matchResolving) Color.White.copy(alpha = 0.4f) else Color(0xFFE0A030),
                                    fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                )
                            }
                            Spacer(Modifier.width(7.dp))
                            // L.6: the title and badge share ONE weighted row. With the title weighted
                            // `fill = false` beside a separate weighted spacer, Row split the free width
                            // between the two and a short title left its unused half empty, so CHANGE
                            // MATCH stopped ~115 dp short of the edge ("Tactics Ogre") while a long
                            // title sat flush right.
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f),
                            ) {
                                if (matched != null && !state.matchResolving) {
                                    Text(
                                        "Matched as ",
                                        color = Color.White.copy(alpha = 0.75f), fontSize = 10.5.sp,
                                        maxLines = 1,
                                    )
                                    Text(
                                        matched,
                                        color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                } else {
                                    Text(
                                        when {
                                            state.matchResolving -> "Matching on ${state.matchProvider.label}…"
                                            state.matchFailed    -> "${state.matchProvider.label} didn't answer"
                                            // A dead end is stated plainly rather than left blank — it is
                                            // the exact case Change Match exists to rescue.
                                            else                 -> "No ${state.matchProvider.label} match"
                                        },
                                        color = Color.White.copy(alpha = 0.6f), fontSize = 10.5.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                }
                                if (state.matchIsConfirmed) {
                                    Spacer(Modifier.width(7.dp))
                                    Text(
                                        "Confirmed",
                                        color = Color(0xFF66BB6A), fontSize = 9.sp,
                                        maxLines = 1,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(5.dp))
                                            .background(Color(0xFF66BB6A).copy(alpha = 0.14f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            // Forget Match only means anything once something was confirmed, and
                            // it costs the user nothing: no artwork, no metadata is removed.
                            // L.6: both buttons take the band's full 22 dp and pad only sideways, like
                            // the source chips. Vertical padding left ~14 dp for the text, which the
                            // Thor drew with CHANGE MATCH's lower half cut off.
                            if (state.matchIsConfirmed) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable(onClick = actions::forgetMatch)
                                        .padding(horizontal = 6.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "FORGET",
                                        color = Color.White.copy(alpha = 0.5f), fontSize = 9.5.sp,
                                        maxLines = 1,
                                    )
                                }
                                Spacer(Modifier.width(6.dp))
                            }
                            // Always present, so the line keeps its shape across sources — but a
                            // provider without title search has nothing to pick FROM, so there
                            // the button is inert and says why rather than opening an empty list.
                            val canChange = state.canChangeMatch
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { actions.onChangeMatchPressed() }
                                    .padding(horizontal = 6.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "CHANGE MATCH",
                                    color = if (canChange) Color.White else Color.White.copy(alpha = 0.35f),
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))

                    // ── Grid slot ─────────────────────────────────────────────
                    // Whatever height is left belongs to the grid. Its measured size decides how many
                    // tiles one page holds (AD-17); the ViewModel hears about it only when it changes.
                    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                        val slotWidth = maxWidth
                        val slotHeight = maxHeight
                        LaunchedEffect(slotWidth, slotHeight) {
                            actions.onGridMeasured(slotWidth.value, slotHeight.value)
                        }
                        val columns = state.gridColumns
                        val rows = state.gridRows
                        // The tab's true aspect at the measured width, so art is judged in the shape it is
                        // used at. Capped at an even share of the height: the row clamp can keep one row
                        // taller than a short slot, and the first frame still draws the unmeasured 4 × 5.
                        val tileWidth = (slotWidth - STUDIO_GRID_GAP * (columns - 1)) / columns
                        val tileHeight = maxOf(
                            0.dp,
                            minOf(
                                tileWidth / STUDIO_TABS[state.tabIndex].tileClass.aspect.toFloat(),
                                (slotHeight - STUDIO_GRID_GAP * (rows - 1)) / rows,
                            ),
                        )

                        val activeSource = actions.sourcesForTab().getOrNull(state.sourceIndex)
                        when {
                            // Skeleton tiles, not a bare spinner: the grid keeps its shape while an
                            // uncached page loads, so a source switch never flashes an empty panel.
                            state.resultsLoading -> LazyVerticalGrid(
                                columns = GridCells.Fixed(columns),
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.spacedBy(STUDIO_GRID_GAP),
                                verticalArrangement = Arrangement.spacedBy(STUDIO_GRID_GAP),
                                userScrollEnabled = false,
                            ) {
                                items(state.skeletonCount) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(tileHeight)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color.White.copy(alpha = 0.06f)),
                                    )
                                }
                            }
                            activeSource == StudioSource.LOCAL -> Box(
                                Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.White.copy(alpha = 0.05f))
                                    .clickable(onClick = actions::requestLocalPick),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "Press Confirm to choose a file from this device",
                                    color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp,
                                )
                            }
                            state.results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    when {
                                        activeSource != StudioSource.SCREENSCRAPER -> "No results"
                                        // ScreenScraper art is fetched per game, so with no match there
                                        // was nothing to ask for, which is not the same as having none.
                                        state.matchResolving -> "Looking for this game on ScreenScraper…"
                                        state.matchFailed    -> "ScreenScraper didn't answer. Use Change Match to search again."
                                        state.match == null  -> "No ScreenScraper match for this game. Use Change Match to pick one."
                                        else                 -> "ScreenScraper has nothing of this type for this game"
                                    },
                                    color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp,
                                )
                            }
                            else -> {
                                // Touch long-press toggles a tile's live video preview; controller
                                // focus previews automatically (one player at a time, ever).
                                var touchPreviewIndex by remember(state.results) { mutableStateOf(-1) }
                                // A page is exactly one gridful that fits the slot, so there is nothing to
                                // scroll to: the focused tile is always on screen (AD-5).
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(columns),
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalArrangement = Arrangement.spacedBy(STUDIO_GRID_GAP),
                                    verticalArrangement = Arrangement.spacedBy(STUDIO_GRID_GAP),
                                    userScrollEnabled = false,
                                ) {
                                    itemsIndexed(state.results) { index, art ->
                                        val focused = state.zone == StudioZone.GRID && state.gridIndex == index
                                        val previewing = art.isVideo && (focused || touchPreviewIndex == index)
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(tileHeight)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0xFF10101A))
                                                .border(
                                                    if (focused) 2.dp else 1.dp,
                                                    if (focused) accent else Color.White.copy(alpha = 0.1f),
                                                    RoundedCornerShape(8.dp),
                                                )
                                                .combinedClickable(
                                                    // Routed like A: a multi-asset tab picks the tile,
                                                    // and its preview is in the options menu (task 5.1).
                                                    onClick = {
                                                        if (state.selectsMultiple) actions.toggleSelection(index)
                                                        else actions.openCandidate(index)
                                                    },
                                                    onLongClick = {
                                                        if (art.isVideo) touchPreviewIndex =
                                                            if (touchPreviewIndex == index) -1 else index
                                                    },
                                                ),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            when {
                                                previewing -> StudioVideoTilePreview(
                                                    url = art.url,
                                                    modifier = Modifier.fillMaxSize(),
                                                )
                                                art.isVideo -> Text(
                                                    "▶ VIDEO",
                                                    color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp,
                                                )
                                                STUDIO_TABS[state.tabIndex].kind ==
                                                    com.playfieldportal.feature.artwork.store.ArtworkKind.MANUAL -> Text(
                                                    "PDF",
                                                    color = Color.White.copy(alpha = 0.75f),
                                                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                                )
                                                else -> AsyncImage(
                                                    model = art.thumb ?: art.url,
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize(),
                                                )
                                            }
                                            // Over the art, not under it: a line below each tile would
                                            // push the last row out of the slot. Hidden while a video
                                            // plays so it never covers the preview.
                                            if (!previewing) art.label?.let {
                                                Text(
                                                    it, color = Color.White.copy(alpha = 0.85f), fontSize = 9.sp,
                                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier
                                                        .align(Alignment.BottomStart)
                                                        .fillMaxWidth()
                                                        .background(
                                                            Brush.verticalGradient(
                                                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                                                            ),
                                                        )
                                                        .padding(horizontal = 5.dp, vertical = 3.dp),
                                                )
                                            }
                                            // Drawn inside the tile, so a pick never changes the
                                            // measured slot (L.2). Top corner: the label owns the bottom.
                                            StudioTileBadge(
                                                mark = state.tileMarkOf(art),
                                                accent = accent,
                                                markColor = pfpColors.backgroundBottom,
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .padding(5.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ── Page line (16 dp, 40 dp in touch mode) — see StudioPageLine ──
                    Spacer(Modifier.height(6.dp))
                    StudioPageLine(
                        rangeStart = state.rangeStart,
                        rangeEnd = state.rangeEnd,
                        totalResults = state.totalResults,
                        picks = state.queueSummary,
                        page = state.page,
                        pageCount = state.pageCount,
                        hasPreviousPage = state.hasPreviousPage,
                        hasNextPage = state.hasNextPage,
                        showTouchControls = showTouchControls,
                        onPreviousPage = actions::previousPage,
                        onNextPage = actions::nextPage,
                        onApply = actions::applyChanges,
                        onRetryFailed = actions::retryFailed,
                        onRemoveFailed = actions::removeFailed,
                    )
                }
            }

            // Footer hints — per-zone, and resolved from the live bindings so the
            // glyphs follow the user's controller type and any remapped layout.
            //
            // Search and options are appended from ONE place rather than repeated per zone: they
            // apply at every level, and the three hand-written lists are exactly how the old
            // "NSFW" label for X survived it being rebound to search. Paging is not listed (the
            // page line carries its LB/RB glyphs) and neither is mature (its START badge does).
            ControllerPromptBar(
                items = buildList {
                    when (state.zone) {
                        StudioZone.TABS -> {
                            add(ControllerPromptItem(GamepadAction.SELECT, "sources"))
                            add(ControllerPromptItem(GamepadAction.BACK, "close"))
                        }
                        StudioZone.SOURCES -> {
                            add(ControllerPromptItem(GamepadAction.SELECT, "browse / pick file"))
                            add(ControllerPromptItem(GamepadAction.BACK, "back"))
                        }
                        StudioZone.GRID -> {
                            add(ControllerPromptItem(GamepadAction.SELECT, if (state.selectsMultiple) "check" else "preview / apply"))
                            add(ControllerPromptItem(GamepadAction.BACK, "back"))
                        }
                    }
                    // START applies from any level, so its hint shows whenever this tab has changes waiting.
                    if (state.queueSummary.hasChanges) add(ControllerPromptItem(GamepadAction.HOME, "apply"))
                    add(ControllerPromptItem(GamepadAction.CHANGE_SORT, "search"))
                    add(ControllerPromptItem(GamepadAction.OPEN_CONTEXT_MENU, "options"))
                },
                modifier = Modifier.padding(top = 6.dp),
                labelColor = Color.White.copy(alpha = 0.35f),
                labelStyle = TextStyle(fontSize = 10.sp),
                glyphSize = 14.dp,
                arrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
            )
        }

        // ── Candidate preview overlay ─────────────────────────────────────────
        state.candidate?.let { art ->
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.93f))
                    .clickable(onClick = actions::dismissCandidate),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (state.manualDownloading) {
                        CircularProgressIndicator(color = accent)
                        Spacer(Modifier.height(10.dp))
                        Text("Downloading manual…", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                    } else if (state.candidateManualPath != null) {
                        StudioPdfPage(
                            path = state.candidateManualPath,
                            page = state.manualPage,
                            onPageCount = actions::onManualPageCount,
                            modifier = Modifier.fillMaxWidth(0.62f).fillMaxHeight(0.68f),
                        )
                        Spacer(Modifier.height(8.dp))
                        StudioManualPager(
                            page = state.manualPage,
                            pageCount = state.manualPageCount,
                            showTouchControls = showTouchControls,
                            onPreviousPage = actions::manualPreviousPage,
                            onNextPage = actions::manualNextPage,
                        )
                    } else if (art.isVideo) {
                        Text("Video snap from ${art.provider}", color = Color.White, fontSize = 14.sp)
                    } else {
                        AsyncImage(
                            model = art.url,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth(0.72f).fillMaxHeight(0.72f),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        listOfNotNull(art.provider, art.label).joinToString("  ·  "),
                        color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(
                            if (state.applying) "Applying…" else "Ⓐ  APPLY",
                            color = Color(0xFF45C46A), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                                .clickable(enabled = !state.applying, onClick = actions::applyCandidate)
                                .padding(horizontal = 18.dp, vertical = 9.dp),
                        )
                        Text(
                            "Ⓑ  CANCEL",
                            color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                                .clickable(onClick = actions::dismissCandidate)
                                .padding(horizontal = 18.dp, vertical = 9.dp),
                        )
                    }
                }
            }
        }

        // ── Change Match overlay (task 2.3) ───────────────────────────────────
        // The one place a wrong or absent match stops being a dead end. Backed by each provider's
        // multi-result title search (SteamGridDB, IGDB, TheGamesDB).
        //
        // Controller-first, the WizardTextField way: the query field is a cursor stop, and the
        // keyboard opens only when Select starts editing it. This overlay used to focus the field
        // on open, which raised the IME — and an open IME receives key events before
        // MainActivity.dispatchKeyEvent, so the D-pad, A and B never reached the ViewModel.
        if (state.changeMatchOpen) {
            val matchFocus = remember { FocusRequester() }
            val keyboard = LocalSoftwareKeyboardController.current
            val focusManager = LocalFocusManager.current
            val editing by rememberUpdatedState(state.changeMatchEditing)
            LaunchedEffect(state.changeMatchEditing) {
                if (state.changeMatchEditing) {
                    // Settle a frame around the readOnly→editable flip before showing the keyboard —
                    // the same sequence as WizardTextField / SettingsTextFieldRow.
                    withFrameNanos { }
                    runCatching { matchFocus.requestFocus() }
                    withFrameNanos { }
                    keyboard?.show()
                } else {
                    keyboard?.hide()
                    focusManager.clearFocus()
                }
            }
            // The keyboard dismissed by its own Back key ends editing, so the pad drives the picker
            // again. (If the insets never report it, the next pad press ends editing in the VM.)
            val imeVisible = WindowInsets.isImeVisible
            var imeWasShown by remember { mutableStateOf(false) }
            LaunchedEffect(imeVisible) {
                if (imeVisible) {
                    imeWasShown = true
                } else if (imeWasShown && editing) {
                    imeWasShown = false
                    actions.stopChangeMatchEdit()
                }
            }
            val resultsState = rememberLazyListState()
            LaunchedEffect(state.changeMatchIndex, state.changeMatchResults) {
                if (state.changeMatchIndex >= 0) {
                    runCatching { resultsState.animateScrollToItem(state.changeMatchIndex) }
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xC0000000))
                    .clickable(onClick = actions::cancelChangeMatch),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier
                        // A margin, outside the panel's background, so a short window never has the
                        // panel touching its edges.
                        .padding(vertical = 16.dp)
                        .width(520.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(pfpColors.backgroundBottom)
                        .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(18.dp),
                ) {
                    Text(
                        "Change match on ${state.matchProvider?.label.orEmpty()}",
                        color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Tells the provider which game this is. Your artwork and metadata are left alone.",
                        color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    BasicTextField(
                        value = state.changeMatchDraft,
                        readOnly = !state.changeMatchEditing,
                        onValueChange = actions::onChangeMatchDraftChanged,
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                        cursorBrush = SolidColor(accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = { actions.submitChangeMatch() },
                            onDone = { actions.submitChangeMatch() },
                        ),
                        decorationBox = { inner ->
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (state.changeMatchIndex < 0) accent.copy(alpha = 0.22f)
                                        else Color.White.copy(alpha = 0.08f)
                                    )
                                    .then(
                                        if (state.changeMatchIndex < 0) Modifier.border(1.dp, accent, RoundedCornerShape(8.dp))
                                        else Modifier
                                    )
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            ) {
                                if (state.changeMatchDraft.isEmpty()) Text(
                                    state.game?.displayTitle ?: "Game title",
                                    color = Color.White.copy(alpha = 0.35f), fontSize = 15.sp,
                                )
                                inner()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(matchFocus)
                            // A tap focuses the field; that is touch asking to type, so enter edit mode.
                            .onFocusChanged { if (it.isFocused && !editing) actions.startChangeMatchEdit() },
                    )
                    Spacer(Modifier.height(12.dp))
                    // A cross-platform list is offered, never assumed: another release's artwork may
                    // not be what this game uses, so say where the list came from.
                    if (state.changeMatchAcrossPlatforms && !state.changeMatchLoading) {
                        Text(
                            "Includes other platforms. Check the platform before you pick.",
                            color = Color(0xFFE0A030), fontSize = 11.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    when {
                        state.changeMatchLoading -> Text(
                            if (state.changeMatchSearchingEveryPlatform)
                                "Searching every platform. ScreenScraper can take about 10 seconds…"
                            else "Searching…",
                            color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp,
                        )
                        state.changeMatchError != null -> Text(
                            state.changeMatchError.orEmpty(),
                            color = Color(0xFFE0A030), fontSize = 12.sp,
                        )
                        state.changeMatchResults.isEmpty() -> Text(
                            "No games found. Try a shorter title, or the title without its edition.",
                            color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp,
                        )
                        // Weighted without fill, so the Column measures the fixed rows (Search / Cancel and
                        // the hint below included) first and the list takes only the height left. Unweighted,
                        // a long list on a short window took its full 260 dp and the Column squeezed the
                        // pill buttons measured after it.
                        else -> LazyColumn(
                            Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 260.dp),
                            state = resultsState,
                        ) {
                            lazyItemsIndexed(state.changeMatchResults) { index, candidate ->
                                val focused = index == state.changeMatchIndex
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (focused) accent.copy(alpha = 0.22f) else Color.Transparent)
                                        .clickable { actions.confirmMatch(index) }
                                        .padding(horizontal = 12.dp, vertical = 9.dp),
                                ) {
                                    Text(
                                        candidate.title,
                                        color = Color.White, fontSize = 13.sp,
                                        modifier = Modifier.weight(1f),
                                    )
                                    // Which release this is, and whether it has art of its own: once the list
                                    // spans every platform, a release with none is a dead end to confirm.
                                    listOfNotNull(
                                        candidate.platformName,
                                        candidate.releaseYear?.toString(),
                                        candidate.gameArtCount?.let { if (it == 0) "no media" else "$it media" },
                                    )
                                        .joinToString(" · ")
                                        .takeIf { it.isNotEmpty() }
                                        ?.let {
                                            Text(
                                                it,
                                                color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp,
                                            )
                                        }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Search",
                            color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(accent.copy(alpha = 0.30f))
                                .clickable(onClick = actions::submitChangeMatch)
                                .padding(horizontal = 16.dp, vertical = 7.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Cancel",
                            color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.07f))
                                .clickable(onClick = actions::cancelChangeMatch)
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Up/Down  Move  •  A  Select  •  X  Edit title  •  B  Back",
                        color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp,
                    )
                }
            }
        }

        // ── Search overlay (X / tap) ──────────────────────────────────────────
        if (state.searchOpen) {
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xC0000000))
                    .clickable(onClick = actions::cancelSearch),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier
                        .width(460.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(pfpColors.backgroundBottom)
                        .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(18.dp),
                ) {
                    Text(
                        "Search artwork providers",
                        color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Changes what the providers are asked for. It never renames the game.",
                        color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    BasicTextField(
                        value = state.queryDraft,
                        onValueChange = actions::onQueryDraftChanged,
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                        cursorBrush = SolidColor(accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = { actions.submitSearch() },
                            onDone = { actions.submitSearch() },
                        ),
                        decorationBox = { inner ->
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White.copy(alpha = 0.08f))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            ) {
                                if (state.queryDraft.isEmpty()) Text(
                                    state.game?.displayTitle ?: "Game title",
                                    color = Color.White.copy(alpha = 0.35f), fontSize = 15.sp,
                                )
                                inner()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Search",
                            color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(accent.copy(alpha = 0.30f))
                                .clickable(onClick = actions::submitSearch)
                                .padding(horizontal = 16.dp, vertical = 7.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Use game title",
                            color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.07f))
                                .clickable(onClick = actions::resetSearchToTitle)
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "Cancel",
                            color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = actions::cancelSearch)
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        )
                    }
                }
            }
        }

        // ── Options menu overlay (Y / triangle) — the shared XMB-style context menu ──
        if (state.actionsOpen && !state.showFileInfo) {
            val menuActions = state.availableActions
            com.playfieldportal.core.ui.components.PspContextMenuOverlay(
                title = STUDIO_TABS[state.tabIndex].label,
                rows = menuActions.map {
                    com.playfieldportal.core.ui.components.PspMenuRow(it.label, isDestructive = it == StudioAction.CLEAR)
                },
                selectedIndex = state.actionsIndex,
                onRowActivated = { index -> menuActions.getOrNull(index)?.let(actions::runAction) },
                onDismiss = actions::closeActions,
                // Darker than the XMB default — the grid behind is busy, so let it recede.
                scrim = Color(0xA6000000),
            )
        }

        // ── Apply confirmation (task 5.2): START, the Apply pill or Apply Changes ──
        if (state.applyConfirmOpen) {
            val changes = state.queueSummary
            com.playfieldportal.core.ui.components.PspContextMenuOverlay(
                title = studioApplyTitle(STUDIO_TABS[state.tabIndex].kind, changes.toAdd, changes.toRemove),
                rows = StudioApplyChoice.entries.map {
                    // Apply reads as destructive when it deletes stored artwork.
                    com.playfieldportal.core.ui.components.PspMenuRow(
                        it.label,
                        isDestructive = it == StudioApplyChoice.APPLY && changes.toRemove > 0,
                    )
                },
                selectedIndex = state.applyConfirmIndex,
                onRowActivated = { index -> actions.resolveApplyConfirm(StudioApplyChoice.entries[index]) },
                onDismiss = { actions.resolveApplyConfirm(StudioApplyChoice.CANCEL) },
                scrim = Color(0xA6000000),
            )
        }

        // ── Leave prompt (task 5.2): B from the categories while changes wait to be applied ──
        if (state.leavePromptOpen) {
            val waiting = state.selection.size + state.removals.size
            com.playfieldportal.core.ui.components.PspContextMenuOverlay(
                title = if (waiting == 1) "1 change not applied" else "$waiting changes not applied",
                rows = StudioLeaveChoice.entries.map {
                    com.playfieldportal.core.ui.components.PspMenuRow(it.label, isDestructive = it == StudioLeaveChoice.DISCARD)
                },
                selectedIndex = state.leavePromptIndex,
                onRowActivated = { index -> actions.resolveLeavePrompt(StudioLeaveChoice.entries[index]) },
                onDismiss = { actions.resolveLeavePrompt(StudioLeaveChoice.STAY) },
                scrim = Color(0xA6000000),
            )
        }

        // ── File information panel ────────────────────────────────────────────
        if (state.showFileInfo) {
            val info = state.info
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f))
                    .clickable(onClick = actions::closeActions),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier
                        .width(420.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF14141F))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                        .padding(20.dp),
                ) {
                    Text("FILE INFORMATION", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    if (info == null) {
                        Text(
                            "No stored record for this slot (available once the artwork lives in a linked library).",
                            color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp,
                        )
                    } else {
                        StudioInfoRow("Type", STUDIO_TABS[state.tabIndex].label)
                        StudioInfoRow("Provider", info.provider ?: "—")
                        StudioInfoRow("Source", info.source)
                        StudioInfoRow("Pinned", if (info.userAssigned) "Yes (locked)" else "No")
                        StudioInfoRow("Dimensions", if (info.width != null && info.height != null) "${info.width} × ${info.height}" else "—")
                        StudioInfoRow("Size", formatBytes(info.sizeBytes))
                        StudioInfoRow("Cropped", if (info.cropRect != null) "Yes" else "No")
                        StudioInfoRow("Previous version", if (info.hasPrevious) "Available" else "—")
                        StudioInfoRow("Path", info.relativePath ?: "—")
                        info.originUrl?.let { StudioInfoRow("Origin", it) }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Ⓑ  CLOSE", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                            .clickable(onClick = actions::closeActions)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }

        // ── Crop / position editor ────────────────────────────────────────────
        state.cropEditorPath?.let { path ->
            StudioCropEditor(
                path = path,
                srcW = state.cropSrcW, srcH = state.cropSrcH,
                cropL = state.cropL, cropT = state.cropT, cropR = state.cropR, cropB = state.cropB,
                applying = state.applying,
                onPan = actions::panCrop,
                onZoom = actions::zoomCrop,
                onApply = actions::applyCrop,
                onCancel = actions::cancelCrop,
            )
        }

        if (state.cropPreparing) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = accent)
            }
        }
    }
}

/**
 * A tile's corner badge (tasks 5.1, 5.2): a new pick is an accent check, a stored asset a green check,
 * an unchecked stored asset a red ring with "−", waiting an accent ring, downloading spins and failed a
 * red "!".
 */
@Composable
private fun StudioTileBadge(
    mark: StudioTileMark,
    accent: Color,
    markColor: Color,
    modifier: Modifier = Modifier,
) {
    val size = 18.dp
    val circle = androidx.compose.foundation.shape.CircleShape
    when (mark) {
        StudioTileMark.NONE -> Unit
        StudioTileMark.PICKED -> com.playfieldportal.core.ui.components.PfpCheckBadge(
            fill = accent, markColor = markColor, modifier = modifier, size = size,
        )
        StudioTileMark.TO_REMOVE -> Box(
            modifier
                .size(size)
                .background(Color.Black.copy(alpha = 0.55f), circle)
                .border(2.dp, Color(0xFFE57373), circle),
            contentAlignment = Alignment.Center,
        ) {
            Text("−", color = Color(0xFFE57373), fontSize = 12.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold)
        }
        StudioTileMark.QUEUED -> Box(
            modifier
                .size(size)
                .background(Color.Black.copy(alpha = 0.55f), circle)
                .border(2.dp, accent, circle),
        )
        StudioTileMark.DOWNLOADING -> Box(
            modifier.size(size).background(Color.Black.copy(alpha = 0.55f), circle),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = accent, strokeWidth = 2.dp, modifier = Modifier.size(12.dp))
        }
        StudioTileMark.ADDED -> com.playfieldportal.core.ui.components.PfpCheckBadge(
            fill = Color(0xFF66BB6A),
            markColor = markColor,
            modifier = modifier,
            size = size,
        )
        StudioTileMark.FAILED -> Box(
            modifier.size(size).background(Color(0xFFE57373), circle),
            contentAlignment = Alignment.Center,
        ) {
            Text("!", color = Color.White, fontSize = 11.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StudioInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, modifier = Modifier.width(130.dp))
        Text(value, color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L        -> "—"
    bytes < 1024       -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(java.util.Locale.US, bytes / 1024.0)
    else               -> "%.1f MB".format(java.util.Locale.US, bytes / (1024.0 * 1024))
}

/**
 * Crop/position editor: the untouched original fills the screen, a dimmed mask shows the crop
 * window (aspect-locked per kind by the ViewModel). Controller pans with the D-pad and zooms
 * with LB/RB; touch drags to pan and pinches to zoom.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StudioCropEditor(
    path: String,
    srcW: Int, srcH: Int,
    cropL: Float, cropT: Float, cropR: Float, cropB: Float,
    applying: Boolean,
    onPan: (Float, Float) -> Unit,
    onZoom: (Float) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
) {
    val accent = menuCursorEdge()
    // The image transform behind the fixed frame is fully described by the current crop window;
    // rememberUpdatedState keeps the gesture loop reading the LATEST values mid-drag.
    val geom = androidx.compose.runtime.rememberUpdatedState(
        CropGeom(srcW, srcH, cropL, cropT, cropR, cropB)
    )
    var bmp by remember(path) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(path) {
        bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            decodeDisplayBitmap(path)?.asImageBitmap()
        }
    }
    // Layered, not stacked: the image + dim mask fill the WHOLE screen on the bottom layer
    // (clipped, so no zoom level can paint outside it), and the title/buttons/hints float on a
    // layer above — the zoomed image slides underneath them instead of covering them. Only the
    // crop frame's dimensions are static; everything else moves and scales behind it.
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.96f))) {

        // ── Layer 1: full-screen image + fixed frame + dim mask ────────────────
        val image = bmp
        if (image == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = accent)
            }
        } else {
            androidx.compose.foundation.Canvas(
                Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val g = geom.value
                            val (fw, fh) = frameSizeFor(g, size.width.toFloat(), size.height.toFloat())
                            val imgDispW = fw / (g.cropR - g.cropL).coerceAtLeast(0.0001f)
                            val imgDispH = fh / (g.cropB - g.cropT).coerceAtLeast(0.0001f)
                            // Frame is fixed: dragging the image right shifts the framed region left.
                            if (pan.x != 0f || pan.y != 0f) onPan(-pan.x / imgDispW, -pan.y / imgDispH)
                            if (zoom != 1f) onZoom(zoom)
                        }
                    },
            ) {
                val g = geom.value
                val (fw, fh) = frameSizeFor(g, size.width, size.height)
                val fx = (size.width - fw) / 2f; val fy = (size.height - fh) / 2f
                // Scale the image so the crop window maps exactly onto the fixed frame.
                val imgDispW = fw / (cropR - cropL).coerceAtLeast(0.0001f)
                val imgDispH = fh / (cropB - cropT).coerceAtLeast(0.0001f)
                val imgLeft = fx - cropL * imgDispW
                val imgTop = fy - cropT * imgDispH
                drawImage(
                    image = image,
                    dstOffset = androidx.compose.ui.unit.IntOffset(imgLeft.roundToInt(), imgTop.roundToInt()),
                    dstSize = androidx.compose.ui.unit.IntSize(imgDispW.roundToInt(), imgDispH.roundToInt()),
                )
                // Dim everything outside the fixed frame, edge to edge of the screen.
                val dim = Color.Black.copy(alpha = 0.62f)
                val W = size.width; val H = size.height
                drawRect(dim, size = androidx.compose.ui.geometry.Size(W, fy))
                drawRect(dim, topLeft = androidx.compose.ui.geometry.Offset(0f, fy + fh), size = androidx.compose.ui.geometry.Size(W, H - fy - fh))
                drawRect(dim, topLeft = androidx.compose.ui.geometry.Offset(0f, fy), size = androidx.compose.ui.geometry.Size(fx, fh))
                drawRect(dim, topLeft = androidx.compose.ui.geometry.Offset(fx + fw, fy), size = androidx.compose.ui.geometry.Size(W - fx - fw, fh))
                drawRect(
                    accent,
                    topLeft = androidx.compose.ui.geometry.Offset(fx, fy),
                    size = androidx.compose.ui.geometry.Size(fw, fh),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f),
                )
            }
        }

        // ── Layer 2: title, buttons, hints — always above the image ────────────
        Column(
            Modifier.fillMaxSize().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("ADJUST CROP / POSITION", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    if (applying) "Baking…" else "Ⓐ  APPLY CROP",
                    color = Color(0xFF45C46A), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.55f))
                        .clickable(enabled = !applying, onClick = onApply)
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                )
                Text(
                    "Ⓑ  CANCEL", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.55f))
                        .clickable(onClick = onCancel)
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                )
            }
            Text(
                "drag to move the image   ·   pinch to zoom   ·   D-Pad move   ·   LB / RB zoom",
                color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

// Current crop window + source dimensions — everything the fixed-frame render/gesture needs.
private data class CropGeom(
    val srcW: Int, val srcH: Int,
    val cropL: Float, val cropT: Float, val cropR: Float, val cropB: Float,
)

// The fixed frame's on-screen size: the crop window's aspect, fit to ~82% of the editor area.
private fun frameSizeFor(g: CropGeom, areaW: Float, areaH: Float): Pair<Float, Float> {
    val cw = (g.cropR - g.cropL).coerceAtLeast(0.0001f)
    val ch = (g.cropB - g.cropT).coerceAtLeast(0.0001f)
    val frameAspect = (cw * g.srcW) / (ch * g.srcH)
    val fw = if (frameAspect > areaW / areaH) 0.82f * areaW else 0.82f * areaH * frameAspect
    return fw to (fw / frameAspect)
}

// Decodes [path] downscaled to a display-friendly size (bake still reads the full original).
private fun decodeDisplayBitmap(path: String): android.graphics.Bitmap? {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    val maxDim = 1600
    while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    return android.graphics.BitmapFactory.decodeFile(path, opts)
}

// Small muted looping preview inside a grid tile — plays only while the tile is focused
// (controller) or long-pressed (touch), so at most one decoder ever runs. TextureView, not
// SurfaceView, so it composites inside the Studio like any other tile content.
@Composable
private fun StudioVideoTilePreview(url: String, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var videoSize by remember(url) {
        mutableStateOf<androidx.media3.common.VideoSize?>(null)
    }
    var failed by remember(url) { mutableStateOf(false) }
    var triedLocal by remember(url) { mutableStateOf(false) }
    // Starts as the remote URL. ScreenScraper's mediaJeu.php serves videos with no
    // Content-Length and no range support, so a clip whose moov atom trails the media data
    // can't stream progressively. On the first playback error we download the clip to cache
    // and retry from the local file, which is fully seekable.
    var source by remember(url) { mutableStateOf(url) }

    val player = remember(source) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, true)
                .build()
            volume = 0f
            setMediaItem(androidx.media3.common.MediaItem.fromUri(source))
            repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) { videoSize = size }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (!triedLocal) {
                    triedLocal = true
                    scope.launch {
                        val local = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            downloadTilePreviewVideo(context, url)
                        }
                        if (local != null) source = android.net.Uri.fromFile(local).toString()
                        else failed = true
                    }
                } else {
                    timber.log.Timber.w(error, "Studio tile preview failed after local fallback")
                    failed = true
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    if (failed) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("▶ VIDEO", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
        }
        return
    }
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            android.view.TextureView(ctx).also { view ->
                player.setVideoTextureView(view)
                view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                    studioTileCrop(v as android.view.TextureView, videoSize)
                }
            }
        },
        update = { view -> studioTileCrop(view, videoSize) },
        modifier = modifier,
    )
}

// Downloads a tile-preview clip to cache (keyed by URL) so a non-seekable SS stream can play
// from a local, seekable file. Capped so a full gameplay video can't fill the cache partition.
private fun downloadTilePreviewVideo(context: android.content.Context, url: String): java.io.File? =
    runCatching {
        val name = "studio_vid_" + Integer.toHexString(url.hashCode()) + ".mp4"
        val dest = java.io.File(context.cacheDir, name)
        if (dest.exists() && dest.length() > 0) return dest
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
        }
        conn.inputStream.use { input ->
            dest.outputStream().use { out ->
                val buf = ByteArray(64 * 1024); var total = 0L
                while (true) {
                    val n = input.read(buf); if (n == -1) break
                    total += n
                    if (total > 80L * 1024 * 1024) error("tile preview clip too large")
                    out.write(buf, 0, n)
                }
            }
        }
        dest.takeIf { it.length() > 0 } ?: run { dest.delete(); null }
    }.onFailure { timber.log.Timber.w(it, "Tile preview video download failed") }.getOrNull()

// Center-crop matrix so the (usually 4:3) frame fills the tile.
private fun studioTileCrop(view: android.view.TextureView, size: androidx.media3.common.VideoSize?) {
    val vw = size?.width?.toFloat() ?: return
    val vh = size.height.toFloat()
    if (vw <= 0f || vh <= 0f || view.width == 0 || view.height == 0) return
    val viewW = view.width.toFloat()
    val viewH = view.height.toFloat()
    val scale = maxOf(viewW / vw, viewH / vh)
    view.setTransform(android.graphics.Matrix().apply {
        setScale((vw * scale) / viewW, (vh * scale) / viewH, viewW / 2f, viewH / 2f)
    })
}

// One rendered PDF page (PdfRenderer, white backing, 2x scale) for the manual candidate
// preview. Reports the page count once so the ViewModel can clamp navigation.
@Composable
private fun StudioPdfPage(
    path: String,
    page: Int,
    onPageCount: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pageBitmap by remember(path, page) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(path, page) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                android.os.ParcelFileDescriptor.open(
                    java.io.File(path), android.os.ParcelFileDescriptor.MODE_READ_ONLY,
                ).use { pfd ->
                    android.graphics.pdf.PdfRenderer(pfd).use { renderer ->
                        onPageCount(renderer.pageCount)
                        val index = page.coerceIn(0, renderer.pageCount - 1)
                        renderer.openPage(index).use { p ->
                            val scale = 2f
                            val bitmap = android.graphics.Bitmap.createBitmap(
                                (p.width * scale).toInt(), (p.height * scale).toInt(),
                                android.graphics.Bitmap.Config.ARGB_8888,
                            )
                            bitmap.eraseColor(android.graphics.Color.WHITE)
                            p.render(
                                bitmap, null,
                                android.graphics.Matrix().apply { setScale(scale, scale) },
                                android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                            )
                            pageBitmap = bitmap
                        }
                    }
                }
            }.onFailure { timber.log.Timber.w(it, "Manual preview render failed") }
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val bmp = pageBitmap
        if (bmp != null) {
            androidx.compose.foundation.Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Manual page",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CircularProgressIndicator(color = Color.White.copy(alpha = 0.5f))
        }
    }
}
