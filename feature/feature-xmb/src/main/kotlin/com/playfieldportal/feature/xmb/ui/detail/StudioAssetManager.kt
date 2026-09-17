package com.playfieldportal.feature.xmb.ui.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.playfieldportal.core.ui.components.XmbHeaderPill
import com.playfieldportal.feature.artwork.store.StudioArtworkSlot

/**
 * The stored-assets manager (task 5.4): the active multi-asset slot's assets in order, with the
 * first one labelled the primary — what the rail and the Game Detail strip show.
 *
 * Reorder only. Removal stays the grid's checklist, so there is one way to delete an asset rather
 * than two that could disagree.
 *
 * Drawn as a full-screen overlay for the same reason the file-information panel is: the grid's
 * measured slot decides the page size (L.2), so a panel that took layout space would change how
 * many tiles a page holds.
 *
 * **The order lives in the records, not the filenames.** Files keep their ordinal names, because
 * Relink rebuilds position from those names and the Windows PC export claims records back by exact
 * name. A Relink therefore puts the slot back in file order — said plainly in the footer rather
 * than fought.
 */
@Composable
internal fun StudioAssetManagerPanel(
    kindLabel: String,
    assets: List<StudioArtworkSlot>,
    focusedIndex: Int,
    busy: Boolean,
    showTouchControls: Boolean,
    accent: Color,
    onFocus: (Int) -> Unit,
    onMove: (Int) -> Unit,
    onMakePrimary: () -> Unit,
    onClose: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)).clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(460.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF14141F))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                .clickable(enabled = false) {}
                .padding(20.dp),
        ) {
            Text(
                "REORDER " + kindLabel.uppercase(),
                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "The first one is what the game shows.",
                color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp,
            )
            Spacer(Modifier.height(12.dp))

            if (assets.isEmpty()) {
                // Reachable if every record's file was lost between opening the menu and the panel.
                Text(
                    "Nothing stored for this slot.",
                    color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp,
                )
            } else {
                Column(
                    Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    assets.forEachIndexed { index, asset ->
                        StudioAssetRow(
                            position = index,
                            asset = asset,
                            focused = index == focusedIndex,
                            accent = accent,
                            onClick = { onFocus(index) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            if (showTouchControls) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    XmbHeaderPill(label = "Up", onClick = { if (!busy) onMove(-1) }, leadingGlyph = "▲")
                    XmbHeaderPill(label = "Down", onClick = { if (!busy) onMove(1) }, leadingGlyph = "▼")
                    XmbHeaderPill(label = "Make First", onClick = { if (!busy) onMakePrimary() })
                }
            } else {
                Text(
                    "Ⓛ Ⓡ  MOVE      Ⓐ  MAKE FIRST",
                    color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Re-linking a library rebuilds this order from the filenames, which is how artwork " +
                    "reconnects to re-imported games.",
                color = Color.White.copy(alpha = 0.45f), fontSize = 10.sp, lineHeight = 13.sp,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Ⓑ  CLOSE", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .clickable(onClick = onClose)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * One row: its position, a thumbnail, and where it came from. The focused row frames itself with a
 * [BringIntoViewRequester] rather than scroll-offset arithmetic, which is this app's convention for
 * controller focus — it stays exact when a row is taller than its neighbours.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StudioAssetRow(
    position: Int,
    asset: StudioArtworkSlot,
    focused: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(focused) { if (focused) requester.bringIntoView() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.04f))
            .border(
                1.dp,
                if (focused) accent.copy(alpha = 0.7f) else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        Text(
            (position + 1).toString(),
            color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp,
            modifier = Modifier.width(20.dp),
        )
        Box(
            Modifier
                .width(56.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF080E1E)),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = asset.documentUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (position == 0) "Primary" else (asset.provider ?: "Stored artwork"),
                color = if (position == 0) accent else Color.White.copy(alpha = 0.9f),
                fontSize = 12.sp,
                fontWeight = if (position == 0) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    asset.provider.takeIf { position == 0 },
                    formatBytes(asset.sizeBytes).takeIf { asset.sizeBytes > 0 },
                ).joinToString(" · ").ifEmpty { "—" },
                color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
