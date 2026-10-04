package com.playfieldportal.studio.preview.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.studio.preview.XmbBackdrop
import com.playfieldportal.studio.preview.XmbPreviewModel
import com.playfieldportal.core.ui.theme.StorefrontColors
import com.playfieldportal.core.ui.theme.unselectedLabel

// ── App Picker (feature-xmb ui/apppicker/AppPickerScreen.kt) ──────────────────────────────────
//
// The installed-app picker as "Add Apps" on the Network category opens it
// (XMBViewModel ADD_APPS_ITEM_ID → openAppPicker(CategoryShortcuts, "Add Apps")): every installed
// app sorted by label, the category's current apps (Chrome, Firefox — SampleContent's Network rows)
// pre-checked, cursor on the first tile, search closed. Static frame — the focus / check cross-fades
// are drawn at their targets.
//
// Theme inputs: like the App Drawer, only StorefrontColors (deriveStorefrontColors — waveColor and
// backgroundTop/Bottom) over the XMB background. XMBShell hides the XMB foreground under the picker
// but does NOT freeze the wave (the picker is not in waveCovered), so the backdrop is XmbBackdrop.
// The Main / Sub text colours come through the palette, as in the drawer; iconColor, legibility and
// icon overrides are not read.

// XMBViewModel PICKER_GRID_COLUMNS — one denser than the drawer's 6.
private const val PickerColumns = 7

// The category's existing members, pre-checked (openAppPicker: selected = membership).
private val PickerSelected = setOf("Chrome", "Firefox")

// AppPickerFooter (not confirming): D-pad Navigate (fixed DPAD_ALL), then the Xbox glyphs for the
// default bindings — A SELECT, X CHANGE_SORT, Menu (START) HOME, B BACK.
private val PickerPrompts = listOf(
    "xmb/ctl_xb_dpad_all.png" to "Navigate",
    "xmb/ctl_xb_face_south.png" to "Toggle",
    "xmb/ctl_xb_face_west.png" to "Search",
    "xmb/ctl_xb_start.png" to "Apply",
    "xmb/ctl_xb_face_east.png" to "Cancel",
)

// AppPickerFooter labelStyle: TextStyle(fontSize = 12.sp) (explicit, no LocalTextStyle merge).
private val PickerPromptLabel = TextStyle(fontSize = 12.sp)

@Composable
fun AppPickerScreenPreview(model: XmbPreviewModel) {
    val sf = rememberDrawerPalette(model)
    Box(Modifier.fillMaxSize()) {
        XmbBackdrop(model)
        CompositionLocalProvider(LocalTextStyle provides DrawerBodyLarge) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(sf.backgroundDeep, sf.backgroundMid))),
            ) {
                Column(Modifier.fillMaxSize()) {
                    PickerHeader(title = "Add Apps", selectedCount = PickerSelected.size, sf = sf)
                    Box(Modifier.fillMaxWidth().height(1.dp).background(sf.chromeDivider))
                    BoxWithConstraints(Modifier.weight(1f)) {
                        val artwork = drawerAdaptiveArtworkSize(maxHeight)
                        DrawerStaticGrid(DrawerSampleApps, PickerColumns, maxRows = 4) { index, label ->
                            PickerTile(
                                label = label,
                                focused = index == 0,
                                checked = label in PickerSelected,
                                artwork = artwork,
                                sf = sf,
                                unselected = sf.unselectedLabel(model.pfp),
                            )
                        }
                    }
                    // Permanent footer: divider + controller prompt bar (never fades).
                    Box(Modifier.fillMaxWidth().height(1.dp).background(sf.chromeDivider))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
                    ) {
                        for ((glyph, label) in PickerPrompts) {
                            DrawerPrompt(glyph, label, sf.textSecondary, PickerPromptLabel, 16.dp)
                        }
                    }
                }
            }
        }
    }
}

// AppPickerHeader: ‹ + title on the left, "N Selected" then the magnifier + "Search" on the right.
@Composable
private fun PickerHeader(title: String, selectedCount: Int, sf: StorefrontColors) {
    Row(
        modifier = Modifier.fillMaxWidth().height(DrawerHeaderHeight).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Text(text = "‹", color = sf.textSecondary, fontSize = 18.sp, modifier = Modifier.padding(end = 8.dp))
            Text(
                text = title,
                color = sf.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = "$selectedCount Selected",
            color = sf.textSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(end = 16.dp),
        )
        Spacer(Modifier.width(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            DrawerMagnifier(sf.iconSecondary)
            Spacer(Modifier.width(5.dp))
            Text("Search", color = sf.textSecondary, fontSize = 13.sp)
        }
    }
}

// AppPickerTile: the drawer's focus chrome (glow plate, 1 dp edge, 1 dp inner hairline inset 2 dp),
// plus the independent selection layer — a tileSelectedInner @ 0.10 tint and the PfpCheckBadge
// (18 dp, tileSelectedEdge fill, backgroundDeep mark) top-right, 3 dp in.
@Composable
private fun PickerTile(label: String, focused: Boolean, checked: Boolean, artwork: Dp, sf: StorefrontColors, unselected: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Box(modifier = Modifier.size(artwork + DrawerFrameRoom)) {
            if (focused) {
                Box(Modifier.matchParentSize().background(sf.selectionGlow))
                Box(Modifier.matchParentSize().border(1.dp, sf.tileSelectedEdge.copy(alpha = 1f)))
                Box(Modifier.matchParentSize().padding(2.dp).border(1.dp, sf.tileSelectedInner.copy(alpha = 1f)))
            }
            if (checked) Box(Modifier.matchParentSize().background(sf.tileSelectedInner.copy(alpha = 0.10f)))
            DrawerAppIcon(label, artwork, Modifier.align(Alignment.Center))
            if (checked) {
                PickerCheckBadge(
                    fill = sf.tileSelectedEdge,
                    markColor = sf.backgroundDeep,
                    modifier = Modifier.align(Alignment.TopEnd).padding(3.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        DrawerTileLabel(label, if (focused) sf.textPrimary else unselected)
    }
}

// core-ui PfpCheck.kt PfpCheckBadge ([diameter] circle, 18 dp by default; the Game Picker's is 16)
// carrying PfpCheckMark at 0.67 of it: the launcher's own check path
// (0.21,0.52 → 0.40,0.71 → 0.79,0.31), stroke 0.14 of the box, round caps.
@Composable
internal fun PickerCheckBadge(fill: Color, markColor: Color, modifier: Modifier = Modifier, diameter: Dp = 18.dp) {
    val badge = diameter
    Box(
        modifier = modifier.size(badge).background(fill, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(badge * 0.67f)) {
            val path = Path().apply {
                moveTo(size.width * 0.21f, size.height * 0.52f)
                lineTo(size.width * 0.40f, size.height * 0.71f)
                lineTo(size.width * 0.79f, size.height * 0.31f)
            }
            drawPath(
                path,
                markColor,
                style = Stroke(width = size.minDimension * 0.14f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}
