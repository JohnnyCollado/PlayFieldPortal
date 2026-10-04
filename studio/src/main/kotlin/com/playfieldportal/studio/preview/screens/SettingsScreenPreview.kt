package com.playfieldportal.studio.preview.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.studio.preview.IconMatteStyle
import com.playfieldportal.studio.preview.LabelProtection
import com.playfieldportal.studio.preview.StudioIconSet
import com.playfieldportal.studio.preview.XmbBackdrop
import com.playfieldportal.studio.preview.XmbPreviewModel

/*
 * Settings ▸ Interface ▸ Display, replicated from feature-settings:
 *  - SettingsScaffold.kt (scrim 850-862, header 886-931, content fade 984-998, helper footer 291-315)
 *  - the row family in SettingsScaffold.kt (SettingsGroup 1048, SettingsRow 1092, SettingsToggleRow
 *    1313, SettingsValueRow 1349), SettingsSliderRow.kt and MediaAssignmentRow.kt
 *  - DisplaySettingsScreen.kt 244-566 for the rows, in order, with DisplaySettingsUiState's defaults.
 *
 * Shown as it opens with a controller: the cursor on the first action row and the idle Enter / Back
 * pill up. The body scrolls with the mouse wheel, as the column does on the device, so every row
 * (switches, the Hint Delay slider, the media rows) can be inspected.
 *
 * What the theme drives here (XMBShell's PFPTheme + XmbBackground):
 *  - the backdrop behind the overlay: wallpaper + scrim, or the wave ([XmbBackdrop]);
 *  - the scaffold's scrim, solved from PFPColors.backgroundTop / backgroundBottom (solveScrimColor);
 *  - SettingsText = LocalPfpTextColors.primary = PFPColors.textPrimary ([XmbPreviewModel.textPrimary]),
 *    used by the screen subtitle, unfocused row labels and value text. SettingsSubtext =
 *    LocalPfpTextColors.secondary = subTextOr(PfpPalette.Subtext, weight 0xAA): #AAAAAA until the
 *    theme sets a Sub (or Main) colour ([settingsSubtext]); the group band title is
 *    themedText(White). The launcher renders the colours unclamped; the clamp only feeds
 *    DisplaySettingsViewModel's notice dialog;
 *  - the row values a theme writes into Display's prefs on apply (PfpThemeStore.apply): wallpaper,
 *    wave style, icon legibility, solid unfocused icons, the font colour.
 * The cursor fill derives from PFPColors.accentColor, which stays white on every theme, and
 * SettingsAccent / Subtext / Divider are fixed palette colours.
 */

// ── Palette (core-ui PFPTheme.kt PfpPalette + SettingsScaffold.kt 214-239) ─────

private val SettingsAccent = Color(0xFF4A90D9)   // PfpPalette.Accent
private val SettingsSubtext = Color(0xFFAAAAAA)  // PfpPalette.Subtext — raw; text reads [settingsSubtext]

/** The preview model for the Settings rows' text roles, provided by [SettingsScreenPreview]. */
private val LocalSettingsModel = staticCompositionLocalOf<XmbPreviewModel?> { null }

/** LocalPfpTextColors.secondary: the theme's Sub (else Main) colour at #AAAAAA's weight, else #AAAAAA. */
@Composable
private fun settingsSubtext(): Color =
    LocalSettingsModel.current?.subTextOr(SettingsSubtext, 0xAA / 255f) ?: SettingsSubtext
private val SettingsDivider = Color(0xFF2A2A2A)  // PfpPalette.Divider

// SettingsTextShadow: black 0.75, (0, 2), blur 4.
private val SettingsTextShadow = Shadow(color = Color.Black.copy(alpha = 0.75f), offset = Offset(0f, 2f), blurRadius = 4f)
private val ShadowStyle = TextStyle(shadow = SettingsTextShadow)

// SettingsRow: a disabled row's text fades to 0.4 of its alpha.
private const val DISABLED_ROW_ALPHA = 0.4f

// SettingsScaffold CONTENT_EDGE_MARGIN: the bottom fade's height.
private val ContentEdgeMargin = 16.dp

// PFPTheme's PfpDarkColorScheme: what the stock Switch / Slider / Divider read for the colours the
// launcher does not pass (Switch's unchecked border = outline, the slider's tick marks, ...).
private val PfpDarkColorScheme = darkColorScheme(
    primary = SettingsAccent,
    onPrimary = Color.White,
    secondary = SettingsAccent,
    onSecondary = Color.White,
    background = Color(0xFF10141C),
    onBackground = Color.White,
    surface = Color(0xFF141A24),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1B2230),
    onSurfaceVariant = SettingsSubtext,
    surfaceContainerLowest = Color(0xFF10141C),
    surfaceContainerLow = Color(0xFF141A24),
    surfaceContainer = Color(0xFF181F2B),
    surfaceContainerHigh = Color(0xFF1B2230),
    surfaceContainerHighest = Color(0xFF202838),
    outline = Color(0xFF3A4356),
    outlineVariant = SettingsDivider,
)

// ── Prompt glyphs (default Xbox bindings: SELECT = A, BACK = B) ───────────────

private const val GLYPH_A = "xmb/ctl_xb_face_south.png"
private const val GLYPH_B = "xmb/ctl_xb_face_east.png"

@Composable
fun SettingsScreenPreview(model: XmbPreviewModel) {
    // SettingsScaffold 385-390: each anchor solved once per theme.
    val scrimTop = remember(model.backgroundTop) { solveScrimColor(model.backgroundTop, alpha = 0.72f) }
    val scrimBottom = remember(model.backgroundBottom) { solveScrimColor(model.backgroundBottom, alpha = 0.90f) }
    val text = model.textPrimary
    // core-ui MenuCursor.menuCursorFill: lerp(accentColor, White, 0.20) at 0.34 (accentColor stays white).
    val cursorFill = lerp(model.drillCursor, Color.White, 0.20f).copy(alpha = 0.34f)
    val state = remember(model) { DisplayState.of(model) }

    MaterialTheme(colorScheme = PfpDarkColorScheme) {
      CompositionLocalProvider(LocalSettingsModel provides model) {
        Box(Modifier.fillMaxSize()) {
            // XMBShell keeps XmbBackground composed under Settings and hides only the XMB foreground.
            XmbBackdrop(model)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to scrimTop.copy(alpha = 0.72f),
                            1f to scrimBottom.copy(alpha = 0.90f),
                        ),
                    ),
            ) {
                Column(Modifier.fillMaxSize()) {
                    SettingsHeader(title = "Settings", subtitle = "Display", text = text)
                    HorizontalDivider(color = SettingsDivider)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            // Bottom edge fade: the content's own alpha, DstIn over an offscreen layer.
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .drawWithContent {
                                drawContent()
                                val fade = ContentEdgeMargin.toPx().coerceAtMost(size.height)
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(Color.Black, Color.Transparent),
                                        startY = size.height - fade,
                                        endY = size.height,
                                    ),
                                    topLeft = Offset(0f, size.height - fade),
                                    size = Size(size.width, fade),
                                    blendMode = BlendMode.DstIn,
                                )
                            },
                    ) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            DisplayRows(state, text, cursorFill)
                        }
                    }
                    HelperFooter()
                }
            }
        }
      }
    }
}

// ── The screen's rows (DisplaySettingsScreen.kt 249-565) ──────────────────────

/** The rows' values: DisplaySettingsUiState's defaults, with what applying this theme writes. */
private data class DisplayState(
    val hasWallpaper: Boolean,
    val waveStyleLabel: String,
    val iconLegibilityLabel: String,
    val solidUnfocusedIcons: Boolean,
    /** KEY_TEXT_COLOR: a theme text colour lands in the user's Font Colour pref. */
    val fontColour: Color?,
    /** KEY_SUB_TEXT_COLOR: a theme sub text colour lands in the user's Sub Font Colour pref. */
    val subFontColour: Color?,
    val textLegibilityLabel: String,
) {
    companion object {
        fun of(model: XmbPreviewModel) = DisplayState(
            hasWallpaper = model.wallpaper != null,
            // WAVE_STYLE_LABELS.
            waveStyleLabel = when (model.waveStyle) {
                "reduced" -> "Reduced"
                "static" -> "Static"
                "reduced_static" -> "Reduced + Static"
                else -> "Animated"
            },
            // IconLegibilityStyle labels.
            iconLegibilityLabel = when (model.legibility.icon) {
                IconMatteStyle.NONE -> "None"
                IconMatteStyle.OFFSET_SHADOW -> "Offset Shadow (Dark)"
                IconMatteStyle.OFFSET_SHADOW_LIGHT -> "Offset Shadow (Light)"
                IconMatteStyle.CONTOUR_DARK -> "Contour (Dark)"
                IconMatteStyle.CONTOUR_LIGHT -> "Contour (Light)"
                IconMatteStyle.CONTOUR_AUTO -> "Contour (Auto)"
            },
            solidUnfocusedIcons = model.legibility.solidUnfocusedIcons,
            fontColour = model.textOverride,
            subFontColour = model.subTextOverride,
            // TextLegibilityStyle labels. The preview model folds "shadow" into AUTO's floor, so
            // SHADOW reads as the default, Automatic.
            textLegibilityLabel = when (model.legibility.text) {
                LabelProtection.NONE -> "None"
                LabelProtection.OUTLINE -> "Outline"
                LabelProtection.PLATE -> "Contrast Plate"
                LabelProtection.SHADOW -> "Automatic"
            },
        )
    }
}

@Composable
private fun ColumnScope.DisplayRows(state: DisplayState, text: Color, cursorFill: Color) {
    SettingsGroup("Appearance")
    // The screen opens on its first action row.
    SettingsRow(
        "Choose Wallpaper",
        if (state.hasWallpaper) "Custom wallpaper set — replaces the wave"
        else "Pick an image or a short video (PNG, JPG, WEBP, MP4, WEBM, GIF) — replaces the wave",
        text, cursorFill, focused = true,
    )
    SettingsRow("Preview Wallpaper", "See the selected wallpaper full-screen", text, cursorFill)
    if (state.hasWallpaper) {
        SettingsRow("Reset Wallpaper", "Remove custom wallpaper and restore the default background", text, cursorFill)
    } else {
        ValueRow(
            "Wave Style",
            "Animated   |   Reduced (dimmer, calmer)   |   Static (frozen)   |   Reduced + Static",
            state.waveStyleLabel, text, cursorFill,
        )
    }
    ValueRow(
        "Icon Legibility",
        "How XMB icons separate from the background.  " +
            "None  |  Offset Shadow (Dark)  |  Offset Shadow (Light — for dark icons)  |  Contour (Dark)  |  Contour (Light)  |  Contour (Auto — follows the icon color)",
        state.iconLegibilityLabel, text, cursorFill,
    )
    ToggleRow(
        "Solid Unfocused Icons",
        "Draw unselected icons at full opacity — selection still reads by size and label",
        state.solidUnfocusedIcons, text, cursorFill,
    )
    ToggleRow(
        "Text Shadow",
        "Drop shadow behind row helper text — keeps it readable over bright wallpaper regions",
        checked = true, text = text, cursorFill = cursorFill,
    )
    ValueRow(
        "Item List Motion",
        "Rewind (the focused item slips behind the category icon, the list follows; up plays it in reverse)   |   Glide (the whole list moves together)",
        "Rewind", text, cursorFill,
    )
    ValueRow(
        "UMD Slot",
        "Off   |   Inserted (only a game you inserted)   |   Inserted & Recent (else the last game played)",
        "Inserted & Recent", text, cursorFill,
    )
    ValueRow(
        "Font Colour",
        "Colour for labels and body text across the interface",
        // hexOf: "#RRGGBB".
        state.fontColour?.let { String.format("#%06X", 0xFFFFFF and it.toArgb()) } ?: "Theme Default",
        text, cursorFill,
    )
    ValueRow(
        "Sub Font Colour",
        "Colour for subtitles, sublabels and values. Follows Font Colour until set",
        state.subFontColour?.let { String.format("#%06X", 0xFFFFFF and it.toArgb()) } ?: "Same as Font Colour",
        text, cursorFill,
    )
    if (state.subFontColour != null) {
        SettingsRow("Reset Sub Font Colour", "Go back to following Font Colour", text, cursorFill)
    }
    if (state.fontColour != null) {
        SettingsRow("Reset Font Colour", "Go back to the colour the current theme supplies", text, cursorFill)
        ToggleRow(
            "Use My Exact Colour",
            "Render the colour exactly as picked. Legibility protection still " +
                "applies — text may get a shadow or a plate behind it",
            checked = false, text = text, cursorFill = cursorFill,
        )
    }
    ValueRow(
        "Text Legibility",
        "How text separates from what is behind it.  " +
            "Automatic  |  None  |  Drop Shadow  |  Outline  |  Contrast Plate",
        state.textLegibilityLabel, text, cursorFill,
    )

    SettingsGroup("XMB Layout")
    Text(
        text = "Position the XMB live for this screen — scale it, and shift the crossbar " +
            "up/down and left/right — over the real interface. Each screen size (handheld, " +
            "foldable, tablet) keeps its own tuning.",
        color = settingsSubtext(),
        fontSize = 12.sp,
        style = ShadowStyle,
        modifier = Modifier.padding(horizontal = 48.dp, vertical = 4.dp),
    )
    SettingsRow("Adjust XMB Layout", "Live editor — scale + reposition the crossbar with the D-pad or sliders", text, cursorFill)
    SettingsRow("Biblically Accurate PSP XMB", "Apply the PSP's own proportions to this screen", text, cursorFill)
    SettingsRow("Customize XMB Icons", "Replace any icon with your own image or GIF — live over the XMB", text, cursorFill)

    SettingsGroup("Boot Sequence")
    ToggleRow("Show Boot Sequence", "PSP-style boot animation on every launch", checked = true, text = text, cursorFill = cursorFill)
    ToggleRow("Show Boot Sequence on Resume", "Also play when returning from a game", checked = false, text = text, cursorFill = cursorFill)
    MediaRow(
        "Boot Video",
        "Play your own video instead of the PFP logo animation (MP4 or WebM, up to 10 seconds)",
        text, cursorFill,
    )

    SettingsGroup("GameBoot")
    ToggleRow(
        "GameBoot",
        "A short presentation between confirming a game and the emulator " +
            "opening — five seconds built in, up to ten with your own clip — skippable " +
            "with Confirm or Back.  Off is a silent launch — no animation, no sound.",
        checked = true, text = text, cursorFill = cursorFill,
    )
    MediaRow(
        "GameBoot Video",
        "Replace the built-in sequence with your own clip, which plays with " +
            "its own sound — even with Menu Sounds off (MP4 or WebM, up to 10 seconds)",
        text, cursorFill,
    )

    SettingsGroup("Orientation")
    ValueRow("Screen Orientation", "PFP is designed for landscape use", "Landscape (fixed)", text, cursorFill)

    SettingsGroup("Interface")
    ValueRow(
        "Touch Navigation Button",
        "On-screen App Drawer / Back button.  Auto — show only while using touch  |  " +
            "Always Show  |  Always Hide (controller-only)",
        "Auto", text, cursorFill,
    )
    ValueRow(
        "Touch Sensitivity",
        "How far a swipe travels per XMB step.  Low — steadier  |  Normal  |  High — faster scrubbing",
        "Normal", text, cursorFill,
    )
    ToggleRow(
        "Options Hint",
        "Show the idle “Options” pill over XMB items with a context menu",
        checked = true, text = text, cursorFill = cursorFill,
    )
    SliderRow(
        "Hint Delay",
        "Show after 2.5s of inactivity (1–5 seconds)",
        value = 2.5f, valueText = "2.5s", text = text,
    )

    SettingsGroup("Performance")
    ToggleRow(
        "Thermal Throttle Awareness",
        "Automatically reduce background quality when device runs hot",
        checked = true, text = text, cursorFill = cursorFill,
    )
    ToggleRow(
        "Battery Saver Mode",
        "Freeze the background (wave or motion wallpaper) when Battery Saver is active",
        checked = true, text = text, cursorFill = cursorFill,
    )

    SettingsGroup("Games")
    ToggleRow(
        "Launch Games Directly",
        "Confirm starts the game immediately instead of opening Game Details — use \"View Game Details\" in a game's Options menu to edit",
        checked = false, text = text, cursorFill = cursorFill,
    )
}

// ── Chrome ───────────────────────────────────────────────────────────────────

/** SettingsScaffold's breadcrumb header: ◀, then the uppercase title over the subtitle. */
@Composable
private fun SettingsHeader(title: String, subtitle: String, text: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "◀",
            color = settingsSubtext(),
            fontSize = 18.sp,
            style = ShadowStyle,
            modifier = Modifier.padding(end = 20.dp),
        )
        Column {
            Text(
                text = title.uppercase(),
                color = SettingsAccent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                style = ShadowStyle,
            )
            Text(
                text = subtitle,
                color = text,
                fontSize = 22.sp,
                fontWeight = FontWeight.Light,
                style = ShadowStyle,
            )
        }
    }
}

/**
 * SettingsHelperFooter: a divider, then the ControllerHintBar pill (black 0.70) centred in a 12 dp
 * band. Drawn at full alpha: the controller user's idle state, as the XMB preview shows its pill.
 */
@Composable
private fun HelperFooter() {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = SettingsDivider)
        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            // ControllerHintBar (regular): 10 dp corners, 14 x 8 padding, 16 dp between prompts.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.70f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                HintPrompt(GLYPH_A, "Enter")
                HintPrompt(GLYPH_B, "Back")
            }
        }
    }
}

// ControllerHintBar's prompt: a 20 dp glyph, 4 dp, then a 14 sp SemiBold white label with the drop shadow.
private val HintLabelStyle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, shadow = SettingsTextShadow)

@Composable
private fun HintPrompt(glyph: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Image(StudioIconSet.chromePainter(glyph), contentDescription = null, modifier = Modifier.size(20.dp))
        Text(text = label, color = Color.White, style = HintLabelStyle)
    }
}

// ── Row family ───────────────────────────────────────────────────────────────

/** SettingsGroup: an uppercase band at white 0.1. */
@Composable
private fun SettingsGroup(title: String) {
    Text(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.1f))
            .padding(start = 48.dp, top = 10.dp, bottom = 10.dp),
        text = title.uppercase(),
        // SettingsScaffold: themedText(Color.White).
        color = LocalSettingsModel.current?.textOr(Color.White) ?: Color.White,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.8.sp,
        style = ShadowStyle,
    )
}

/**
 * SettingsRow: the cursor fill on the focused row (its label turns white), a 15 sp label over a 12 sp
 * Subtext sublabel, the trailing slot after 16 dp, then a divider inset 48 dp.
 */
@Composable
private fun SettingsRow(
    label: String,
    sublabel: String?,
    text: Color,
    cursorFill: Color,
    focused: Boolean = false,
    enabled: Boolean = true,
    actions: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (focused) cursorFill else Color.Transparent)
            .padding(horizontal = 48.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = (if (focused) Color.White else text).let { if (enabled) it else it.copy(alpha = it.alpha * DISABLED_ROW_ALPHA) },
                fontSize = 15.sp,
                style = ShadowStyle,
            )
            if (!sublabel.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    sublabel,
                    color = settingsSubtext().let { if (enabled) it else it.copy(alpha = it.alpha * DISABLED_ROW_ALPHA) },
                    fontSize = 12.sp,
                    style = ShadowStyle,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(16.dp))
            trailing()
        }
        if (actions != null) {
            Spacer(Modifier.width(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                actions()
            }
        }
    }
    HorizontalDivider(color = SettingsDivider, modifier = Modifier.padding(start = 48.dp))
}

/** SettingsValueRow: the value in SettingsText at 13 sp (never the accent). */
@Composable
private fun ValueRow(label: String, sublabel: String, value: String, text: Color, cursorFill: Color) {
    SettingsRow(label, sublabel, text, cursorFill) {
        Text(text = value, color = text, fontSize = 13.sp, style = ShadowStyle)
    }
}

/** SettingsToggleRow: the stock Material 3 Switch with the launcher's four colours. */
@Composable
private fun ToggleRow(label: String, sublabel: String, checked: Boolean, text: Color, cursorFill: Color) {
    SettingsRow(label, sublabel, text, cursorFill) {
        Switch(
            checked = checked,
            // Non-null as on the device, so the switch keeps its 48 dp touch-target height; the
            // preview changes nothing.
            onCheckedChange = {},
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = SettingsAccent,
                uncheckedThumbColor = settingsSubtext(),
                uncheckedTrackColor = SettingsDivider,
            ),
        )
    }
}

/**
 * MediaAssignmentRow with nothing assigned: the value ("PFP Default") in SettingsAccent, and the one
 * inline action, Preview — a PlayArrow on black 0.1 inside a 48 dp IconButton.
 */
@Composable
private fun MediaRow(label: String, sublabel: String, text: Color, cursorFill: Color) {
    SettingsRow(
        label, sublabel, text, cursorFill,
        actions = {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = SettingsAccent,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.1f), RoundedCornerShape(6.dp))
                        .padding(4.dp),
                )
            }
        },
    ) {
        Text(text = "PFP Default", color = SettingsAccent, fontSize = 13.sp, style = ShadowStyle)
    }
}

/**
 * SettingsSliderRow, not adjusting: the value in Subtext, then the stock Slider (thumb Subtext, active
 * track Divider, inactive Divider at 0.4). It is not on the cursor in this frame.
 */
@Composable
private fun SliderRow(label: String, sublabel: String, value: Float, valueText: String, text: Color) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, color = text, fontSize = 15.sp, style = ShadowStyle)
                Spacer(Modifier.height(2.dp))
                Text(sublabel, color = settingsSubtext(), fontSize = 12.sp, style = ShadowStyle)
            }
            Spacer(Modifier.width(16.dp))
            Text(text = valueText, color = settingsSubtext(), fontSize = 13.sp, style = ShadowStyle)
        }
        Spacer(Modifier.height(8.dp))
        Slider(
            value = value,
            onValueChange = {},
            valueRange = 1f..5f,
            steps = 7,
            colors = SliderDefaults.colors(
                thumbColor = settingsSubtext(),
                activeTrackColor = SettingsDivider,
                inactiveTrackColor = SettingsDivider.copy(alpha = 0.4f),
            ),
        )
    }
    HorizontalDivider(color = SettingsDivider, modifier = Modifier.padding(start = 48.dp))
}

// ── core-ui TextLegibility.kt: the scrim solve, verbatim ─────────────────────

private const val BODY_CONTRAST = 4.5 // TextContrastRole.BODY

private fun relativeLuminance(c: Color): Double {
    fun linearize(channel: Float): Double {
        val v = channel.toDouble()
        return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * linearize(c.red) + 0.7152 * linearize(c.green) + 0.0722 * linearize(c.blue)
}

private fun contrastRatio(a: Color, b: Color): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/** Source-over of [top] (its alpha) onto opaque [bottom], in sRGB channel space like Modifier.background. */
private fun composite(top: Color, bottom: Color): Color {
    val a = top.alpha
    return Color(
        red = top.red * a + bottom.red * (1f - a),
        green = top.green * a + bottom.green * (1f - a),
        blue = top.blue * a + bottom.blue * (1f - a),
    )
}

/**
 * solveScrimColor: darken [base] toward black just far enough that, at [alpha] over a pure-white
 * wallpaper, white text clears 4.5:1 (12 bisection steps).
 */
private fun solveScrimColor(base: Color, alpha: Float): Color {
    fun passes(t: Float): Boolean =
        contrastRatio(Color.White, composite(lerp(base, Color.Black, t).copy(alpha = alpha), Color.White)) >= BODY_CONTRAST

    if (passes(0f)) return base
    if (!passes(1f)) return Color.Black
    var lo = 0f
    var hi = 1f
    repeat(12) {
        val mid = (lo + hi) / 2f
        if (passes(mid)) hi = mid else lo = mid
    }
    return lerp(base, Color.Black, hi)
}
