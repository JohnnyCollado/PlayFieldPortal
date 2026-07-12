package com.playfieldportal.feature.xmb.ui.detail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    LaunchedEffect(state.closed) { if (state.closed) onClose() }
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
            com.playfieldportal.feature.artwork.store.ArtworkKind.VIDEO  -> arrayOf("video/mp4", "video/webm", "video/*")
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

            // ── Destination tabs (LB/RB) ──────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                STUDIO_TABS.forEachIndexed { index, tab ->
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
                        when {
                            state.currentUri != null && STUDIO_TABS[state.tabIndex].kind.name in
                                setOf("MANUAL", "VIDEO") -> Text(
                                if (STUDIO_TABS[state.tabIndex].kind == com.playfieldportal.feature.artwork.store.ArtworkKind.MANUAL)
                                    "PDF stored" else "Video stored",
                                color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp,
                            )
                            state.currentUri != null -> AsyncImage(
                                model = state.currentUri,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize().padding(6.dp),
                            )
                            else -> Text("No artwork set", color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Clear ${STUDIO_TABS[state.tabIndex].label}",
                        color = Color(0xFFE57373),
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = viewModel::clearCurrent)
                            .padding(6.dp),
                    )
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
                            Text(
                                "$first–$last of ${state.totalResults}",
                                color = Color.White.copy(alpha = 0.45f), fontSize = 10.sp,
                            )
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
                                    "Nothing cached for this type — scrape this game once with ScreenScraper"
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
                            LazyVerticalGrid(
                                state = gridState,
                                columns = GridCells.Fixed(4),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                itemsIndexed(state.results) { index, art ->
                                    val focused = state.zone == StudioZone.GRID && state.gridIndex == index
                                    val isCurrent = false   // provenance match lands with pass 2
                                    Column {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(92.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0xFF10101A))
                                                .border(
                                                    if (focused) 2.dp else 1.dp,
                                                    when {
                                                        focused -> accent
                                                        isCurrent -> accent.copy(alpha = 0.5f)
                                                        else -> Color.White.copy(alpha = 0.1f)
                                                    },
                                                    RoundedCornerShape(8.dp),
                                                )
                                                .clickable { viewModel.openCandidate(index) },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            if (art.isVideo) {
                                                Text("▶ VIDEO", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
                                            } else {
                                                AsyncImage(
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
                "LB/RB type · ◄► browse · Ⓐ preview/apply · Ⓑ back · ▢ NSFW filter · edges page",
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
                    if (art.isVideo) {
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
    }
}
