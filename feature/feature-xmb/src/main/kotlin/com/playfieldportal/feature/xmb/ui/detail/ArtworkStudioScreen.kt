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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed as lazyItemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.theme.LocalPFPColors
import com.playfieldportal.core.ui.theme.menuCursorEdge

/**
 * Fullscreen Artwork Studio — controller-first artwork browser/editor for one game.
 * Layout follows the approved mock: destination tabs (LB/RB) → current-artwork panel +
 * available-artwork grid, source row (L2/R2 — here Left/Right in the SOURCES zone),
 * A = candidate preview → Apply, B = back, X = SGDB NSFW filter toggle.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArtworkStudioScreen(
    gameId: Long,
    onClose: () -> Unit,
    pendingGamepadAction: GamepadAction? = null,
    onGamepadActionConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: ArtworkStudioViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val pfpColors = LocalPFPColors.current
    val accent = menuCursorEdge()

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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to pfpColors.backgroundTop.copy(alpha = 0.97f),
                    1f to pfpColors.backgroundBottom,
                )
            ),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 14.dp)) {

            // ── Header ────────────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "‹ Back",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                    modifier = Modifier.clickable(onClick = viewModel::close).padding(6.dp),
                )
                Spacer(Modifier.width(14.dp))
                Text("ARTWORK STUDIO", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(
                    state.game?.displayTitle ?: "",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(320.dp),
                )
            }
            Spacer(Modifier.height(10.dp))

            // ── Destination tabs (LB/RB) — scrollable, selected tab kept in view ──
            val tabListState = rememberLazyListState()
            LaunchedEffect(state.tabIndex) { tabListState.animateScrollToItem(state.tabIndex) }
            LazyRow(
                state = tabListState,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                lazyItemsIndexed(STUDIO_TABS) { index, tab ->
                    val selected = state.tabIndex == index
                    val focusedZone = state.zone == StudioZone.TABS && selected
                    Text(
                        tab.label,
                        color = if (selected) Color.White else Color.White.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) accent.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.06f))
                            .border(
                                1.dp,
                                if (focusedZone) accent else Color.Transparent,
                                RoundedCornerShape(8.dp),
                            )
                            .clickable { viewModel.selectTab(index) }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
            Text(
                STUDIO_TABS[state.tabIndex].contract,
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )

            Row(Modifier.weight(1f)) {

                // ── Current artwork panel ─────────────────────────────────────
                Column(Modifier.width(230.dp).fillMaxHeight()) {
                    Text("CURRENT", color = Color.White.copy(alpha = 0.55f), fontSize = 10.sp)
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF10101A))
                            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
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
                    Spacer(Modifier.height(8.dp))
                    if (state.currentUri != null) {
                        Text(
                            "START  ·  ACTIONS",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(accent.copy(alpha = 0.18f))
                                .clickable(onClick = viewModel::openActions)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    state.message?.let {
                        Text(
                            it, color = accent, fontSize = 11.sp,
                            modifier = Modifier.clickable(onClick = viewModel::dismissMessage),
                        )
                    }
                }

                Spacer(Modifier.width(18.dp))

                // ── Available artwork ─────────────────────────────────────────
                Column(Modifier.weight(1f).fillMaxHeight()) {

                    // Source row + NSFW toggle + paging status
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val sources = viewModel.sourcesForTab()
                        sources.forEachIndexed { index, source ->
                            val selected = state.sourceIndex == index
                            val focusedZone = state.zone == StudioZone.SOURCES && selected
                            Text(
                                source.label,
                                color = if (selected) Color.White else Color.White.copy(alpha = 0.5f),
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(if (selected) accent.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.05f))
                                    .border(1.dp, if (focusedZone) accent else Color.Transparent, RoundedCornerShape(7.dp))
                                    .clickable {
                                        viewModel.selectSource(index)
                                        if (source == StudioSource.LOCAL) viewModel.requestLocalPick()
                                    }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                        val sgdbActive = viewModel.sourcesForTab().getOrNull(state.sourceIndex) == StudioSource.STEAMGRIDDB
                        if (sgdbActive) {
                            Text(
                                if (state.includeNsfw) "☑ NSFW" else "☐ NSFW",
                                color = if (state.includeNsfw) Color(0xFFE57373) else Color.White.copy(alpha = 0.5f),
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(7.dp))
                                    .clickable(onClick = viewModel::toggleNsfw)
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (state.totalResults > 0) {
                            val first = state.page * 20 + 1
                            val last = (state.page * 20 + state.results.size)
                            if (state.totalResults > 20) {
                                Text(
                                    "‹ Prev", color = Color.White.copy(alpha = if (state.page > 0) 0.8f else 0.25f),
                                    fontSize = 11.sp,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable(enabled = state.page > 0, onClick = viewModel::previousPage)
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                            Text(
                                "$first–$last of ${state.totalResults}",
                                color = Color.White.copy(alpha = 0.45f), fontSize = 10.sp,
                            )
                            if (state.totalResults > 20) {
                                val more = last < state.totalResults
                                Text(
                                    "Next ›", color = Color.White.copy(alpha = if (more) 0.8f else 0.25f),
                                    fontSize = 11.sp,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable(enabled = more, onClick = viewModel::nextPage)
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    val activeSource = viewModel.sourcesForTab().getOrNull(state.sourceIndex)
                    when {
                        state.resultsLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = accent)
                        }
                        activeSource == StudioSource.LOCAL -> Box(
                            Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White.copy(alpha = 0.05f))
                                .clickable(onClick = viewModel::requestLocalPick),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "Press Confirm to choose a file from this device",
                                color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp,
                            )
                        }
                        state.results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                if (activeSource == StudioSource.SCREENSCRAPER)
                                    "ScreenScraper has nothing of this type for this game"
                                else "No results",
                                color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp,
                            )
                        }
                        else -> {
                            val gridState = rememberLazyGridState()
                            LaunchedEffect(state.gridIndex, state.zone) {
                                if (state.zone == StudioZone.GRID && state.results.isNotEmpty()) {
                                    gridState.animateScrollToItem(state.gridIndex.coerceIn(0, state.results.lastIndex))
                                }
                            }
                            // Touch long-press toggles a tile's live video preview; controller
                            // focus previews automatically (one player at a time, ever).
                            var touchPreviewIndex by remember(state.results) { mutableStateOf(-1) }
                            LazyVerticalGrid(
                                state = gridState,
                                columns = GridCells.Fixed(STUDIO_GRID_COLUMNS),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                itemsIndexed(state.results) { index, art ->
                                    val focused = state.zone == StudioZone.GRID && state.gridIndex == index
                                    val previewing = art.isVideo && (focused || touchPreviewIndex == index)
                                    Column {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(92.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0xFF10101A))
                                                .border(
                                                    if (focused) 2.dp else 1.dp,
                                                    if (focused) accent else Color.White.copy(alpha = 0.1f),
                                                    RoundedCornerShape(8.dp),
                                                )
                                                .combinedClickable(
                                                    onClick = { viewModel.openCandidate(index) },
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
                                        }
                                        art.label?.let {
                                            Text(
                                                it, color = Color.White.copy(alpha = 0.4f), fontSize = 9.sp,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Footer hints
            Text(
                "LB / RB — artwork type   ·   D-Pad — grid   ·   A — preview / apply   ·   START — actions   ·   " +
                    "B — back   ·   X — NSFW   ·   Y — sources   ·   past an edge — next / prev page",
                color = Color.White.copy(alpha = 0.35f), fontSize = 10.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        // ── Candidate preview overlay ─────────────────────────────────────────
        state.candidate?.let { art ->
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.93f))
                    .clickable(onClick = viewModel::dismissCandidate),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (state.manualDownloading) {
                        CircularProgressIndicator(color = accent)
                        Spacer(Modifier.height(10.dp))
                        Text("Downloading manual…", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                    } else if (state.candidateManualPath != null) {
                        StudioPdfPage(
                            path = state.candidateManualPath!!,
                            page = state.manualPage,
                            onPageCount = viewModel::onManualPageCount,
                            modifier = Modifier.fillMaxWidth(0.62f).fillMaxHeight(0.68f),
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "‹ Prev", color = Color.White.copy(alpha = if (state.manualPage > 0) 0.85f else 0.3f),
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(enabled = state.manualPage > 0, onClick = viewModel::manualPreviousPage)
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                            Text(
                                "Page ${state.manualPage + 1} / ${state.manualPageCount.coerceAtLeast(1)}",
                                color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                            val more = state.manualPage < state.manualPageCount - 1
                            Text(
                                "Next ›", color = Color.White.copy(alpha = if (more) 0.85f else 0.3f),
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(enabled = more, onClick = viewModel::manualNextPage)
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
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
                                .clickable(enabled = !state.applying, onClick = viewModel::applyCandidate)
                                .padding(horizontal = 18.dp, vertical = 9.dp),
                        )
                        Text(
                            "Ⓑ  CANCEL",
                            color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                                .clickable(onClick = viewModel::dismissCandidate)
                                .padding(horizontal = 18.dp, vertical = 9.dp),
                        )
                    }
                }
            }
        }

        // ── Actions menu overlay (hold A / ACTIONS) ───────────────────────────
        if (state.actionsOpen && !state.showFileInfo) {
            val actions = state.availableActions
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.82f))
                    .clickable(onClick = viewModel::closeActions),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier
                        .width(320.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF14141F))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                        .padding(vertical = 14.dp),
                ) {
                    Text(
                        "${STUDIO_TABS[state.tabIndex].label}  ·  ACTIONS",
                        color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    actions.forEachIndexed { index, act ->
                        val focused = state.actionsIndex == index
                        val danger = act == StudioAction.CLEAR
                        Text(
                            act.label,
                            color = when {
                                danger  -> Color(0xFFE57373)
                                focused -> Color.White
                                else    -> Color.White.copy(alpha = 0.75f)
                            },
                            fontSize = 13.sp,
                            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (focused) accent.copy(alpha = 0.22f) else Color.Transparent)
                                .clickable { viewModel.runAction(act) }
                                .padding(horizontal = 18.dp, vertical = 11.dp),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "D-Pad — move   ·   A — select   ·   B — close",
                        color = Color.White.copy(alpha = 0.35f), fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                    )
                }
            }
        }

        // ── File information panel ────────────────────────────────────────────
        if (state.showFileInfo) {
            val info = state.info
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f))
                    .clickable(onClick = viewModel::closeActions),
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
                            .clickable(onClick = viewModel::closeActions)
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
                onPan = viewModel::panCrop,
                onZoom = viewModel::zoomCrop,
                onApply = viewModel::applyCrop,
                onCancel = viewModel::cancelCrop,
            )
        }

        if (state.cropPreparing) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = accent)
            }
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
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.96f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("ADJUST CROP / POSITION", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            // Fixed centered frame; the image pans and scales behind it (avatar-crop model).
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
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
                contentAlignment = Alignment.Center,
            ) {
                val image = bmp
                if (image == null) {
                    CircularProgressIndicator(color = accent)
                } else {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
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
                        // Dim everything outside the fixed frame.
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
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    if (applying) "Baking…" else "Ⓐ  APPLY CROP",
                    color = Color(0xFF45C46A), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.08f))
                        .clickable(enabled = !applying, onClick = onApply)
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                )
                Text(
                    "Ⓑ  CANCEL", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.08f))
                        .clickable(onClick = onCancel)
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                )
            }
            Text(
                "drag to move the image   ·   pinch to zoom   ·   D-Pad move   ·   LB / RB zoom",
                color = Color.White.copy(alpha = 0.35f), fontSize = 10.sp,
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
