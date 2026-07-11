package com.playfieldportal.feature.xmb.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.aspectRatio
import coil.compose.AsyncImage
import com.playfieldportal.core.domain.model.IconDisplayMode
import com.playfieldportal.core.ui.icons.GameIconStyle
import com.playfieldportal.feature.xmb.R
import com.playfieldportal.feature.xmb.viewmodel.XMBItem
import com.playfieldportal.feature.xmb.viewmodel.resolveIconDisplay

// Canonical icon dimensions — portrait, close to actual PSP game case proportions
private val ICON_WIDTH  = 62.dp
private val ICON_HEIGHT = 86.dp

// Native PSP ICON0.PNG presentation — 144 × 80 (ratio 1.8). The icon FILLS the size the
// caller gives it (see GAME_ICON_* in XMBItemList) so it's a proper 144:80 rectangle, not a
// tiny square. Used for every console's game icons for a consistent XMB look.

private val PspShape    = RoundedCornerShape(4.dp)
private val SquircleShape = RoundedCornerShape(14.dp)

private val CartridgeBodyColor      = Color(0xFF1C1C22)
private val CartridgeConnectorColor = Color(0xFF111115)
private val CartridgePinColor       = Color(0xFF2E2E38)
private val IconBorder              = Color(0x55FFFFFF)
private val ShineColor              = Color(0x18FFFFFF)

// ── Entry point — routes to the right icon composable ─────────────────────────

@Composable
fun GameIcon(
    item: XMBItem,
    iconStyle: GameIconStyle,
    modifier: Modifier = Modifier,
) {
    when {
        // Non-game app rows keep their treatment (decorated tile or launcher squircle).
        // REAL games never land here — every platform, including package-backed
        // Android/Windows games, renders through the icon display modes below, so
        // Custom Icon's 144:80 letter-tile fallback is uniform across the library.
        item.isAndroidApp && !item.isRealGame && item.iconUri != null -> PspIcon0Icon(
            artworkUri  = item.iconUri,
            accentColor = item.accentColor?.let { Color(it) },
            title       = item.title,
            modifier    = modifier,
        )
        item.isAndroidApp && !item.isRealGame -> AndroidAppIcon(
            packageName = item.packageName,
            title       = item.title,
            modifier    = modifier,
        )

        // Legacy global icon style — the whole slot becomes the platform's media image.
        iconStyle == GameIconStyle.CARTRIDGE -> PhysicalMediaIcon(
            platformId  = item.platformId,
            accentColor = item.accentColor?.let { Color(it) },
            title       = item.title,
            modifier    = modifier,
        )

        // Icon display modes: per-game override ?: global. ICON0 keeps the authentic PSP
        // 144:80 edge-to-edge fill (and hosts the ICON1 video snap while focused); the other
        // modes render their art at natural aspect inside the same fixed layout slot.
        else -> {
            val resolved = resolveIconDisplay(item, LocalIconDisplayMode.current)
            when {
                // Physical Media with nothing scraped: the bundled per-platform cartridge/disc.
                resolved.mode == IconDisplayMode.PHYSICAL_MEDIA && resolved.uri == null ->
                    PhysicalMediaIcon(
                        platformId  = item.platformId,
                        accentColor = item.accentColor?.let { Color(it) },
                        title       = item.title,
                        modifier    = modifier,
                    )

                // Box Art / 3D Box with nothing scraped: a letter tile shaped like the
                // platform's box — the mode still reads visually even before a scrape.
                // (ICON0 with no art must NOT land here — it falls through to the else
                // branch, whose PspIcon0Icon draws the 144:80 landscape letter tile.)
                resolved.uri == null &&
                    (resolved.mode == IconDisplayMode.BOX_ART || resolved.mode == IconDisplayMode.BOX_3D) ->
                    BoxArtPlaceholderIcon(
                        platformId  = item.platformId,
                        accentColor = item.accentColor?.let { Color(it) },
                        title       = item.title,
                        modifier    = modifier,
                    )

                resolved.naturalAspect -> NaturalAspectArtIcon(
                    artworkUri  = resolved.uri!!,
                    // Box fronts are opaque rectangles and get the PSP frame; 3D boxes and
                    // cartridge shots are transparent silhouettes and render frameless.
                    framed      = resolved.uri == item.boxArtUri,
                    accentColor = item.accentColor?.let { Color(it) },
                    title       = item.title,
                    modifier    = modifier,
                )

                else -> {
                    val video = LocalFocusedGameVideo.current
                        ?.takeIf { it.gameId == item.gameId && resolved.mode == IconDisplayMode.ICON0 }
                    Box(modifier = modifier) {
                        PspIcon0Icon(
                            artworkUri  = resolved.uri,
                            accentColor = item.accentColor?.let { Color(it) },
                            title       = item.title,
                            modifier    = Modifier.fillMaxSize(),
                        )
                        if (video != null) {
                            // ICON1: the snap plays over the static icon, clipped to the same
                            // PSP tile shape; the static ICON0 stays under it as the poster.
                            Icon1VideoOverlay(
                                videoUri = video.uri,
                                modifier = Modifier.fillMaxSize().clip(PspShape),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Box-shaped letter placeholder (Box Art / 3D Box modes, no art yet) ────────

/**
 * Rough box-front aspect (width / height) per platform, for the placeholder tile — a PS1
 * jewel case reads square, a SNES box landscape, a PS2 keep case tall. Close enough beats
 * exact; the default is the common tall DVD-style case.
 */
fun boxArtAspectFor(platformId: String?): Float = when (platformId) {
    // Landscape North-American boxes
    "snes", "sfc", "n64"                          -> 1.37f
    // Square-ish jewel cases
    "psx", "ps1", "dreamcast", "dc", "segacd",
    "saturn", "gb", "gbc", "gba", "wonderswan",
    "wonderswancolor", "ngp", "ngpc"              -> 1.0f
    // Tall-but-narrow handheld cases
    "psp", "psvita"                               -> 0.59f
    "switch", "nx"                                -> 0.62f
    "nds", "ds", "n3ds", "3ds"                    -> 0.89f
    // Everything else: standard tall keep case (PS2/GC/Wii/Xbox/NES/MD…)
    else                                          -> 0.70f
}

// Letter tile in the platform's box shape — same accent-gradient + initial treatment as the
// ICON0 fallback, shrunk-wrapped exactly like real natural-aspect art would be.
@Composable
private fun BoxArtPlaceholderIcon(
    platformId: String?,
    accentColor: Color?,
    title: String,
    modifier: Modifier = Modifier,
) {
    val accent = accentColor ?: Color(0xFF4A9EFF)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .aspectRatio(boxArtAspectFor(platformId))
                .clip(PspShape)
                .background(
                    Brush.verticalGradient(listOf(accent.copy(alpha = 0.6f), Color(0xFF0A0A0F)))
                )
                .border(1.dp, IconBorder, PspShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text       = title.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                fontSize   = 22.sp,
                fontWeight = FontWeight.Bold,
                color      = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

// ── Natural-aspect tile (Box Art / Physical Media / 3D Box modes) ─────────────
//
// The layout slot the caller sizes stays fixed (row pitch and label alignment never move);
// the VISIBLE container shrink-wraps the artwork: largest natural-aspect rect that fits the
// slot, centered, with the chrome (frame, backing) hugging the fitted bounds — no pillarbox.
// Coil's intrinsic size drives the aspect, so nothing needs pre-recorded dimensions.
@Composable
private fun NaturalAspectArtIcon(
    artworkUri: String,
    framed: Boolean,
    accentColor: Color?,
    title: String,
    modifier: Modifier = Modifier,
) {
    // Size.ORIGINAL is load-bearing: the painter is only drawn AFTER it succeeds, so without
    // an explicit size the request would wait forever for draw-time constraints (blank tile).
    val painter = coil.compose.rememberAsyncImagePainter(
        model = coil.request.ImageRequest.Builder(LocalContext.current)
            .data(artworkUri)
            .size(coil.size.Size.ORIGINAL)
            .build()
    )
    val state = painter.state
    val ratio = (state as? coil.compose.AsyncImagePainter.State.Success)
        ?.painter?.intrinsicSize
        ?.takeIf { it.width > 0f && it.height > 0f }
        ?.let { it.width / it.height }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when {
            ratio != null -> Box(
                modifier = Modifier
                    .aspectRatio(ratio)   // largest natural-aspect rect inside the slot
                    .then(
                        if (framed) Modifier
                            .clip(PspShape)
                            .background(Color(0xFF0A0A0F))
                            .border(1.dp, IconBorder, PspShape)
                        else Modifier
                    ),
            ) {
                Image(
                    painter            = painter,
                    contentDescription = null,
                    contentScale       = ContentScale.Fit,
                    modifier           = Modifier.fillMaxSize(),
                )
            }
            state is coil.compose.AsyncImagePainter.State.Error -> PspIcon0Icon(
                artworkUri  = null,   // letter tile — the art is unreadable
                accentColor = accentColor,
                title       = title,
                modifier    = Modifier.fillMaxSize(),
            )
            else -> Unit   // loading: keep the slot quiet; art appears when decoded
        }
    }
}

// ── Native PSP ICON0.PNG (144 × 80, 16:9 landscape) ───────────────────────────

@Composable
fun PspIcon0Icon(
    artworkUri: String?,
    accentColor: Color?,
    title: String,
    modifier: Modifier = Modifier,
) {
    val accent = accentColor ?: Color(0xFF4A9EFF)

    Box(
        modifier = modifier
            .clip(PspShape)
            .background(Color(0xFF0A0A0F))   // backing behind the art
            .border(1.dp, IconBorder, PspShape),
        contentAlignment = Alignment.Center,
    ) {
        if (artworkUri != null) {
            // Crop fills the 144:80 tile edge-to-edge with the (landscape) hero art.
            AsyncImage(
                model              = artworkUri,
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(listOf(accent.copy(alpha = 0.6f), Color(0xFF0A0A0F)))
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text       = title.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    fontSize   = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color      = Color.White.copy(alpha = 0.85f),
                )
            }
        }

        // Gloss shine at top
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.3f)
                .align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(ShineColor, Color.Transparent)))
        )
    }
}

// ── PSP-style portrait rectangle ──────────────────────────────────────────────

@Composable
fun PspRectangleIcon(
    artworkUri: String?,
    accentColor: Color?,
    title: String,
    modifier: Modifier = Modifier,
) {
    val accent = accentColor ?: Color(0xFF4A9EFF)

    Box(
        modifier = modifier
            .size(width = ICON_WIDTH, height = ICON_HEIGHT)
            .clip(PspShape)
            .border(1.dp, IconBorder, PspShape),
        contentAlignment = Alignment.Center,
    ) {
        if (artworkUri != null) {
            AsyncImage(
                model              = artworkUri,
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize(),
            )
        } else {
            // Fallback: gradient with first-letter initial
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(accent.copy(alpha = 0.6f), Color(0xFF0A0A0F))
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text       = title.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    fontSize   = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color      = Color.White.copy(alpha = 0.85f),
                )
            }
        }

        // Gloss shine at top
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.3f)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(listOf(ShineColor, Color.Transparent))
                )
        )
    }
}

// ── Cartridge-style icon ──────────────────────────────────────────────────────

@Composable
fun CartridgeIcon(
    artworkUri: String?,
    accentColor: Color?,
    title: String,
    modifier: Modifier = Modifier,
) {
    val accent = accentColor ?: Color(0xFF4A9EFF)

    // Build the notch shape (top-right corner cut)
    val density = LocalDensity.current
    val cartridgeShape = remember(density) {
        object : Shape {
            override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
                Outline.Generic(Path().apply {
                    val notchPx = with(density) { 14.dp.toPx() }
                    val radiusPx = with(density) { 4.dp.toPx() }
                    moveTo(radiusPx, 0f)
                    lineTo(size.width - notchPx, 0f)
                    lineTo(size.width, notchPx)
                    lineTo(size.width, size.height - radiusPx)
                    quadraticBezierTo(size.width, size.height, size.width - radiusPx, size.height)
                    lineTo(radiusPx, size.height)
                    quadraticBezierTo(0f, size.height, 0f, size.height - radiusPx)
                    lineTo(0f, radiusPx)
                    quadraticBezierTo(0f, 0f, radiusPx, 0f)
                    close()
                })
        }
    }

    Box(
        modifier = modifier
            .size(width = ICON_WIDTH, height = ICON_HEIGHT)
            .clip(cartridgeShape)
            .background(CartridgeBodyColor)
            .border(1.dp, IconBorder, cartridgeShape),
    ) {
        // Label area: artwork or gradient, occupying top ~72% with horizontal padding
        Box(
            modifier = Modifier
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .fillMaxWidth()
                .fillMaxHeight(0.72f)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0xFF0A0A0F)),
            contentAlignment = Alignment.Center,
        ) {
            if (artworkUri != null) {
                AsyncImage(
                    model              = artworkUri,
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(accent.copy(alpha = 0.5f), Color(0xFF050508))
                            )
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text       = title.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                        fontSize   = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color      = Color.White.copy(alpha = 0.8f),
                    )
                }
            }
        }

        // Connector strip at bottom with simulated pin traces
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(22.dp)
                .background(CartridgeConnectorColor),
        ) {
            Row(
                modifier              = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                repeat(6) {
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(1.dp))
                            .background(CartridgePinColor),
                    )
                }
            }
        }

        // Accent stripe just above connector
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 22.dp)
                .fillMaxWidth()
                .height(2.dp)
                .background(accent.copy(alpha = 0.7f)),
        )
    }
}

// ── Physical media icon (Cartridge mode) ──────────────────────────────────────
//
// Replaces the entire game-icon slot with the platform's physical-media image.
// No container, no border, no dark background — just the PNG.
//
// Image source: drop PNGs named {platformId}.png into
//   feature/feature-xmb/src/main/assets/systems/physical-media/
//   e.g. psx.png, snes.png, psp.png, megadrive.png …
//
// Fallback: built-in generic shape vector when the PNG is absent.

private const val ASSET_BASE = "file:///android_asset/systems/physical-media"

@Composable
fun PhysicalMediaIcon(
    platformId: String?,
    accentColor: Color?,
    title: String,
    modifier: Modifier = Modifier,
) {
    val assetName   = physicalMediaAssetName(platformId)
    val fallbackRes = physicalMediaIconRes(platformId) ?: R.drawable.media_cartridge
    var assetFailed by remember(assetName) { mutableStateOf(false) }

    Box(
        modifier         = modifier,
        contentAlignment = Alignment.Center,
    ) {
        if (assetName != null && !assetFailed) {
            // Resolved filename: e.g. "ps1" → "psx.png", "dc" → "dreamcast.png".
            AsyncImage(
                model              = "$ASSET_BASE/$assetName.png",
                contentDescription = null,
                contentScale       = ContentScale.Fit,
                modifier           = Modifier.fillMaxSize(),
                onError            = { assetFailed = true },
            )
        } else {
            // Generic shape vector fallback (no container).
            Image(
                painter            = painterResource(id = fallbackRes),
                contentDescription = null,
                contentScale       = ContentScale.Fit,
                modifier           = Modifier.fillMaxSize(),
            )
        }
    }
}

// ── Android app icon (round-square / squircle) ────────────────────────────────

@Composable
fun AndroidAppIcon(
    packageName: String?,
    title: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val imageBitmap = remember(packageName) {
        if (packageName == null) return@remember null
        runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val bmp = Bitmap.createBitmap(
                drawable.intrinsicWidth.coerceAtLeast(96),
                drawable.intrinsicHeight.coerceAtLeast(96),
                Bitmap.Config.ARGB_8888,
            )
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bmp.asImageBitmap()
        }.getOrNull()
    }

    // Center a fixed square inside whatever box we're given, so a wide game-icon container
    // never stretches the app squircle.
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(SquircleShape)
            .background(Color(0xFF1A1A20)),
        contentAlignment = Alignment.Center,
    ) {
        if (imageBitmap != null) {
            Image(
                painter            = BitmapPainter(imageBitmap),
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize(),
            )
        } else {
            // Fallback when icon can't be loaded
            Text(
                text       = title.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                fontSize   = 20.sp,
                fontWeight = FontWeight.Bold,
                color      = Color.White.copy(alpha = 0.7f),
            )
        }
    }
    }
}
