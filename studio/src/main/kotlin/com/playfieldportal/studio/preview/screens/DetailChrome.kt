package com.playfieldportal.studio.preview.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import com.playfieldportal.studio.preview.StudioIconSet
import com.playfieldportal.studio.preview.WaveBackground
import com.playfieldportal.studio.preview.WaveMotion
import com.playfieldportal.studio.preview.XmbPreviewModel
import kotlin.math.cos
import kotlin.math.sin

/*
 * The launcher's console-style detail page chrome, shared by the Game Detail, achievements library
 * and per-game achievements previews. Every value is replicated from its launcher source:
 *   palette      — core-ui detail/DetailPalette.kt detailPaletteFor + theme/StorefrontColors.kt
 *                  storefrontColorsFor + theme/TextLegibility.kt (contrast engine)
 *   page frame   — core-ui detail/DetailScaffold.kt (PfpDetailBackground / Breadcrumb / HelperFooter)
 *   hint bar     — core-ui components/ControllerHintBar.kt + ControllerPrompt.kt (PromptRow)
 *   marks        — core-ui detail/DetailGlyphs.kt, components/PfpCheck.kt
 *   Shiba parts  — feature-xmb ui/detail/ShibaDetailParts.kt, ShibaCoinArt.kt
 *   backdrop     — feature-xmb XMBShell.kt: a detail page sets `waveCovered`, so the XMB behind it is
 *                  the FROZEN wave (or the wallpaper poster: MotionWallpaperPolicy releases the motion
 *                  loop while covered), and the XMB foreground is not composed at all.
 *
 * Theme inputs the detail pages read: PFPColors.waveColor (model.accent), the white accentColor,
 * backgroundTop/backgroundBottom (all through detailPaletteFor), the wallpaper / wave style behind the
 * page, icon overrides for the themeable slots, and the icon legibility matte on built-in vector
 * glyphs (ThemedGlyph -> VectorGlyphSurface). They never read PFPColors.textPrimary / textSecondary /
 * iconColor: their text is detailPaletteFor's ensureReadable(White, ...) pole.
 */

// ── Text style ──────────────────────────────────────────────────────────────

/**
 * Material3's default bodyLarge — the LocalTextStyle under the launcher's
 * `MaterialTheme(colorScheme = PfpDarkColorScheme)` (PFPTheme.kt, no custom typography), which every
 * `Text(fontSize = …)` on these pages merges onto.
 */
internal val DetailPreviewBodyLarge = TextStyle(
    fontWeight = FontWeight.Normal,
    fontSize = 16.sp,
    lineHeight = 24.sp,
    letterSpacing = 0.5.sp,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    ),
)

// ── Palette (DetailPalette.kt + StorefrontColors.kt) ────────────────────────

@Immutable
internal data class DetailPreviewPalette(
    val pageTop: Color,
    val pageBottom: Color,
    val divider: Color,
    val rowFill: Color,
    val rowEdge: Color,
    val track: Color,
    val focus: Color,
    val textPrimary: Color,
    val textMuted: Color,
)

/** DetailPalette.MUTED_TEXT_CONTRAST: muted text keeps the App Drawer's own 3.0 floor. */
private const val MUTED_TEXT_CONTRAST = 3.0

/** StorefrontColors.storefrontColorsFor's contrast floor. */
private const val DRAWER_TEXT_CONTRAST = 3.0f

/**
 * detailPaletteFor(PFPColors) over storefrontColorsFor(PFPColors), with PFPColors mapped from the
 * model: waveColor = [XmbPreviewModel.accent], accentColor = White (XMBViewModel never retints it),
 * backgroundTop/backgroundBottom = the model's anchors. Pure; callers remember it.
 */
internal fun detailPreviewPaletteFor(model: XmbPreviewModel): DetailPreviewPalette {
    val accentColor = Color.White
    val bgTop = model.backgroundTop
    val bgBottom = model.backgroundBottom
    // resolveHueSource: a neutral accent falls back to the wave, a neutral wave to its gradient anchor.
    val hue = when {
        accentColor.isVividHue() -> accentColor
        model.accent.isVividHue() -> model.accent
        else -> bgBottom
    }
    val accentEdge = lerp(hue, Color.White, 0.55f)
    val backgroundDeep = bgTop.copy(alpha = 0.88f)
    val backgroundMid = lerp(bgTop, bgBottom, 0.55f).copy(alpha = 0.88f)
    val drawerTextPrimary = ensureReadable(Color.White, backgroundMid, DRAWER_TEXT_CONTRAST)
    val lightChrome = drawerTextPrimary == Color.Black
    val drawerTextSecondary = ensureReadable(
        fg = if (lightChrome) lerp(Color.Black, Color.White, 0.25f) else lerp(hue, Color.White, 0.72f),
        bg = backgroundMid,
        minContrast = DRAWER_TEXT_CONTRAST,
    )
    val edge = if (lightChrome) lerp(hue, Color.Black, 0.45f) else accentEdge

    // detailPaletteFor
    val rowFill = if (lightChrome) Color.White.copy(alpha = 0.30f)
    else lerp(bgTop, Color.Black, 0.30f).copy(alpha = 0.60f)
    val rowOnScreen = composite(rowFill, composite(backgroundDeep, Color.White))
    val textMuted = if (contrastRatio(drawerTextSecondary, rowOnScreen) >= MUTED_TEXT_CONTRAST) {
        drawerTextSecondary
    } else {
        ensureReadable(lerp(drawerTextPrimary, rowOnScreen, 0.18f), rowOnScreen, MUTED_TEXT_CONTRAST.toFloat())
    }
    return DetailPreviewPalette(
        pageTop = backgroundDeep,
        pageBottom = backgroundMid,
        divider = edge,
        rowFill = rowFill,
        rowEdge = edge.copy(alpha = 0.35f),
        track = edge.copy(alpha = 0.25f),
        focus = edge,
        textPrimary = drawerTextPrimary,
        textMuted = textMuted,
    )
}

/** The model's palette, recomputed only when one of its three inputs changes. */
@Composable
internal fun rememberDetailPreviewPalette(model: XmbPreviewModel): DetailPreviewPalette =
    remember(model.accent, model.backgroundTop, model.backgroundBottom) { detailPreviewPaletteFor(model) }

// StorefrontColors.isVividHue
private fun Color.isVividHue(): Boolean {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    return max - min >= 0.10f && max >= 0.30f
}

// TextLegibility.kt: relativeLuminance / contrastRatio / ensureReadable / bestPolarity / composite.
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

private fun ensureReadable(fg: Color, bg: Color, minContrast: Float): Color {
    if (contrastRatio(fg, bg) >= minContrast) return fg
    return if (contrastRatio(Color.Black, bg) >= contrastRatio(Color.White, bg)) Color.Black else Color.White
}

private fun composite(top: Color, bottom: Color): Color {
    val a = top.alpha
    return Color(
        red = top.red * a + bottom.red * (1f - a),
        green = top.green * a + bottom.green * (1f - a),
        blue = top.blue * a + bottom.blue * (1f - a),
    )
}

// ── Page frame (DetailScaffold.kt) ──────────────────────────────────────────

/** DetailScaffold.DetailContentPadding. */
internal val DetailPreviewContentPadding: Dp = 28.dp

/** DetailScaffold.DetailContentMaxWidth. */
internal val DetailPreviewContentMaxWidth: Dp = 920.dp

/** DetailScaffold.DetailFooterHeight. */
private val DetailFooterHeight: Dp = 58.dp

/** DetailScaffold.DetailTextShadow. */
internal val DetailPreviewTextShadow = Shadow(color = Color.Black.copy(alpha = 0.72f), offset = Offset(0f, 2f), blurRadius = 4f)

// XmbBackground.WallpaperBackground's legibility scrim.
private val WallpaperScrim = Color(0x59000000)

/**
 * The XMB background as it stands behind a detail page: the wallpaper poster under its scrim, or the
 * wave frozen at its static pose (XMBShell `waveCovered` -> `waveStyle.frozen`, which keeps the
 * style's amplitude and alpha). Never ticks.
 */
@Composable
internal fun DetailPreviewBackdrop(model: XmbPreviewModel) {
    val wallpaper = model.wallpaper
    if (wallpaper != null) {
        Image(
            bitmap = wallpaper,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(WallpaperScrim))
    } else {
        val params = remember(model.waveStyle) { WaveMotion.paramsFor(model.waveStyle).copy(animated = false) }
        WaveBackground(model, params) { WaveMotion.STATIC_TIME }
    }
}

/**
 * The XMB backdrop plus PfpDetailBackground: the App Drawer's translucent theme gradient (deep top
 * easing into the midtone), so the frozen wave reads through the page. Text inside merges onto
 * Material3's bodyLarge, as on the device.
 */
@Composable
internal fun DetailPreviewPage(
    model: XmbPreviewModel,
    palette: DetailPreviewPalette,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().clipToBounds()) {
        DetailPreviewBackdrop(model)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0f to palette.pageTop, 1f to palette.pageBottom)),
        ) {
            androidx.compose.material3.ProvideTextStyle(DetailPreviewBodyLarge) { content() }
        }
    }
}

/** MenuGlyph.MENU_BACK_KEY: the breadcrumb's ◀ is a themeable menu slot. */
private const val MENU_BACK_KEY = "menu_back"

/**
 * PfpDetailBreadcrumb: `◀` (or the theme's menu_back art at 18dp) + title over a small subtitle, an
 * optional [trailing] block pushed right, and the 1dp divider. The header band itself is transparent.
 */
@Composable
internal fun DetailPreviewBreadcrumb(
    model: XmbPreviewModel,
    palette: DetailPreviewPalette,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = DetailPreviewContentPadding, end = DetailPreviewContentPadding, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    val back = model.iconOverrides[MENU_BACK_KEY]
                    if (back != null) {
                        // CustomIconSurface: the override as authored, no tint.
                        Image(
                            painter = remember(back) { BitmapPainter(back) },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    } else {
                        Text(text = "◀", color = palette.textMuted, fontSize = 16.sp)
                    }
                }
                Spacer(Modifier.width(4.dp))
                Column {
                    Text(
                        text = title,
                        color = palette.textPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subtitle,
                        color = palette.textMuted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(16.dp))
                Spacer(Modifier.weight(1f))
                Box(modifier = Modifier.clipToBounds(), contentAlignment = Alignment.CenterEnd) { trailing() }
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(palette.divider))
    }
}

// ── Controller prompts (Xbox family, default bindings) ──────────────────────

/** ControllerButtonGlyph xbTable art, copied byte-for-byte under resources/xmb/. */
internal object DetailPadGlyphs {
    /** FACE_SOUTH: SELECT (Confirm). */
    const val A = "xmb/ctl_xb_face_south.png"

    /** FACE_EAST: BACK. */
    const val B = "xmb/ctl_xb_face_east.png"

    /** FACE_WEST: CHANGE_SORT. */
    const val X = "xmb/ctl_xb_face_west.png"

    /** FACE_NORTH: OPEN_CONTEXT_MENU. */
    const val Y = "xmb/ctl_xb_face_north.png"

    /** BUMPER_LEFT: PREV_CATEGORY. */
    const val LB = "xmb/ctl_xb_bumper_left.png"

    /** BUMPER_RIGHT: NEXT_CATEGORY. */
    const val RB = "xmb/ctl_xb_bumper_right.png"
}

/** One ControllerPromptItem, already resolved to its glyph art (GamepadMappings.iconsFor). */
@Immutable
internal data class DetailPreviewHint(val glyphs: List<String>, val label: String) {
    constructor(glyph: String, label: String) : this(listOf(glyph), label)
}

// ControllerHintBar (regular): labels 14sp SemiBold white with the classic drop shadow, 20dp glyphs.
private val HintLabelStyle = TextStyle(
    fontSize = 14.sp,
    fontWeight = FontWeight.SemiBold,
    shadow = Shadow(color = Color.Black.copy(alpha = 0.75f), offset = Offset(0f, 2f), blurRadius = 4f),
)

/** ControllerIconGlyph: the family's drawable at [size]. */
@Composable
internal fun DetailPreviewPadGlyph(path: String, size: Dp) {
    Image(StudioIconSet.chromePainter(path), contentDescription = null, modifier = Modifier.size(size))
}

/**
 * PfpDetailHelperFooter: a fixed 58dp band (see-through), a 1dp divider, and the ControllerHintBar
 * centred in the rest on a 0.70 black rounded pill. Shown as a controller user sees it (hints up).
 */
@Composable
internal fun DetailPreviewFooter(palette: DetailPreviewPalette, items: List<DetailPreviewHint>) {
    Column(modifier = Modifier.fillMaxWidth().height(DetailFooterHeight)) {
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(palette.divider))
        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            if (items.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.70f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    for (item in items) {
                        // PromptRow: glyphs 2dp apart, then the label 4dp after.
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                for (glyph in item.glyphs) DetailPreviewPadGlyph(glyph, 20.dp)
                            }
                            Text(text = item.label, color = Color.White, style = HintLabelStyle)
                        }
                    }
                }
            }
        }
    }
}

// ── Themeable glyphs ────────────────────────────────────────────────────────

/**
 * ThemedGlyph: the slot's override drawn as authored (dimmed by [overrideAlpha], custom art only),
 * else the built-in [vector] in [tint] — with the theme's icon matte behind it, as
 * VectorGlyphSurface draws it. The detail pages tint with their own text colours, not the icon colour.
 */
@Composable
internal fun DetailPreviewThemedGlyph(
    model: XmbPreviewModel,
    slotKey: String,
    vector: ImageVector,
    tint: Color,
    modifier: Modifier,
    overrideAlpha: Float = 1f,
) {
    val override = model.iconOverrides[slotKey]
    if (override != null) {
        Image(
            painter = remember(override) { BitmapPainter(override) },
            contentDescription = null,
            alpha = if (overrideAlpha < 1f) overrideAlpha else 1f,
            modifier = modifier,
        )
        return
    }
    val matte = model.legibility.matteColor(tint)
    if (matte == null) {
        Icon(vector, contentDescription = null, tint = tint, modifier = modifier)
        return
    }
    val painter = rememberVectorPainter(vector)
    val offsets = model.legibility.matteOffsets()
    val radiusPx = with(LocalDensity.current) { model.legibility.matteRadiusDp.dp.toPx() }
    val matteFilter = ColorFilter.tint(matte, BlendMode.SrcIn)
    val glyphFilter = ColorFilter.tint(tint, BlendMode.SrcIn)
    Box(
        modifier.drawWithCache {
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

// ── Drawn marks (DetailGlyphs.kt, PfpCheck.kt) ──────────────────────────────

/** PfpStarMark: the favorite star, drawn as a path. */
@Composable
internal fun DetailPreviewStarMark(color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val outer = this.size.minDimension / 2f
        val inner = outer * 0.44f
        val path = Path()
        repeat(10) { i ->
            val radius = if (i % 2 == 0) outer else inner
            val angle = Math.toRadians(-90.0 + i * 36.0)
            val x = cx + (radius * cos(angle)).toFloat()
            val y = cy + (radius * sin(angle)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, color)
    }
}

/** PfpChevronMark: the disclosure chevron at a row's trailing edge. */
@Composable
internal fun DetailPreviewChevronMark(color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.34f, h * 0.16f)
            lineTo(w * 0.68f, h * 0.5f)
            lineTo(w * 0.34f, h * 0.84f)
        }
        drawPath(path, color, style = Stroke(width = w * 0.13f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** PfpCheckMark: the bare check, drawn as a path. */
@Composable
internal fun DetailPreviewCheckMark(color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.21f, h * 0.52f)
            lineTo(w * 0.40f, h * 0.71f)
            lineTo(w * 0.79f, h * 0.31f)
        }
        drawPath(
            path,
            color,
            style = Stroke(width = this.size.minDimension * 0.14f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

// ── Shiba Coins parts (ShibaDetailParts.kt, ShibaCoinArt.kt) ────────────────

/** core-domain ShibaTier, with its theme slot (ShibaSlotKeys.shibaCoinSlotKeyFor). */
internal enum class ShibaPreviewTier(val slotKey: String) {
    BRONZE("shiba_coin_bronze"),
    SILVER("shiba_coin_silver"),
    GOLD("shiba_coin_gold"),
    PLATINUM("shiba_coin_platinum"),
}

/** ShibaDetailParts.CoinOrder: Platinum, Gold, Silver, Bronze. */
internal val ShibaPreviewCoinOrder = listOf(
    ShibaPreviewTier.PLATINUM, ShibaPreviewTier.GOLD, ShibaPreviewTier.SILVER, ShibaPreviewTier.BRONZE,
)

/** ShibaDetailParts.RowHeight: every list row on both Shiba pages. */
internal val ShibaPreviewRowHeight: Dp = 64.dp

/** ShibaDetailParts.SearchRowHeight. */
private val ShibaSearchRowHeight: Dp = 48.dp

/** ShibaDetailParts.FocusShape. */
internal val ShibaPreviewFocusShape = RoundedCornerShape(4.dp)

/** ShibaDetailParts.headerShade: the page darkened in place, so it follows the theme. */
internal fun shibaPreviewHeaderShade(palette: DetailPreviewPalette): Color =
    Color.Black.copy(alpha = if (palette.textPrimary.luminance() < 0.5f) 0.10f else 0.28f)

/** ShibaDetailParts.shibaFocus: drawn inside the element's own bounds. */
internal fun Modifier.shibaPreviewFocus(focused: Boolean, palette: DetailPreviewPalette): Modifier =
    if (focused) {
        background(palette.focus.copy(alpha = 0.14f), ShibaPreviewFocusShape).border(1.5.dp, palette.focus, ShibaPreviewFocusShape)
    } else {
        this
    }

// CoinArt.DIMMED_ALPHA and the locked greyscale.
private const val DIMMED_ALPHA = 0.6f
private val Greyscale = ColorFilter.colorMatrix(
    androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(0f) },
)

/**
 * ShibaCoinIcon / CoinArt with no provider badge: the tier medallion (the theme's slot art as
 * authored, else the bundled webp), greyed and at 0.6 alpha when [dimmed]. Never tinted, no matte.
 */
@Composable
internal fun ShibaPreviewCoin(
    model: XmbPreviewModel,
    tier: ShibaPreviewTier,
    modifier: Modifier,
    dimmed: Boolean = false,
) {
    val override = model.iconOverrides[tier.slotKey]
    val painter: Painter = if (override != null) remember(override) { BitmapPainter(override) }
    else StudioIconSet.defaultPainter(tier.slotKey)
    Image(
        painter = painter,
        contentDescription = null,
        colorFilter = if (dimmed) Greyscale else null,
        alpha = if (dimmed) DIMMED_ALPHA else 1f,
        modifier = modifier,
    )
}

/** ShibaDetailParts.CoinCount: a tier's medallion beside a count. */
@Composable
internal fun ShibaPreviewCoinCount(
    model: XmbPreviewModel,
    tier: ShibaPreviewTier,
    count: Int,
    palette: DetailPreviewPalette,
    iconSize: Dp,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ShibaPreviewCoin(model, tier, Modifier.size(iconSize))
        Spacer(Modifier.width(4.dp))
        Text("$count", color = palette.textPrimary, fontSize = 13.sp, maxLines = 1, softWrap = false)
    }
}

/** ShibaDetailParts.ProgressLine: the thin accent line for completion, rarity and level progress. */
@Composable
internal fun ShibaPreviewProgressLine(
    fraction: Float,
    palette: DetailPreviewPalette,
    modifier: Modifier = Modifier,
    height: Dp = 3.dp,
) {
    Box(modifier.height(height).clip(RoundedCornerShape(2.dp)).background(palette.track)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(palette.focus))
    }
}

/** ShibaDetailParts.Separator: the hairline between list rows. */
@Composable
internal fun ShibaPreviewSeparator(palette: DetailPreviewPalette) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(palette.rowEdge))
}

/**
 * ShibaDetailParts.SearchRow with an empty query: the magnifier and placeholder (the field is
 * read-only until text entry), the focus ring when [focused], an optional [trailing] block beside
 * it, and the separator under the row.
 */
@Composable
internal fun ShibaPreviewSearchRow(
    palette: DetailPreviewPalette,
    focused: Boolean,
    placeholder: String,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = DetailPreviewContentPadding)) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(ShibaSearchRowHeight)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .shibaPreviewFocus(focused, palette)
                    .padding(horizontal = 12.dp),
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = palette.textMuted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                // BasicTextField's decorationBox: the placeholder over an empty field.
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    Text(placeholder, color = palette.textMuted, fontSize = 15.sp, maxLines = 1)
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(16.dp))
                trailing()
            }
        }
        ShibaPreviewSeparator(palette)
    }
}

/**
 * PspIcon0Icon with no ICON0 assigned (GameIconView.kt): the 144:80 letter card, the accent gradient
 * over the dark backing, a 1dp light border and the top gloss strip.
 */
@Composable
internal fun ShibaPreviewIcon0Tile(title: String, accent: Color, modifier: Modifier) {
    val shape = ShibaPreviewFocusShape // PspShape: RoundedCornerShape(4.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Icon0Backing)
            .border(1.dp, Icon0Border, shape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.6f), Icon0Backing))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.3f)
                .align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Icon0Shine, Color.Transparent))),
        )
    }
}

// GameIconView.kt constants.
private val Icon0Backing = Color(0xFF0A0A0F)
private val Icon0Border = Color(0x55FFFFFF)
private val Icon0Shine = Color(0x18FFFFFF)
