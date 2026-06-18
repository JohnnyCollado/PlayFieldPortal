package com.playfieldportal.feature.xmb.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.GenericShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.playfieldportal.core.ui.icons.GameIconStyle
import com.playfieldportal.feature.xmb.viewmodel.XMBItem

// Canonical icon dimensions — portrait, close to actual PSP game case proportions
private val ICON_WIDTH  = 62.dp
private val ICON_HEIGHT = 86.dp

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
        item.isAndroidApp -> AndroidAppIcon(
            packageName = item.packageName,
            title       = item.title,
            modifier    = modifier,
        )

        iconStyle == GameIconStyle.CARTRIDGE -> CartridgeIcon(
            artworkUri  = item.artworkUri,
            accentColor = item.accentColor?.let { Color(it) },
            title       = item.title,
            modifier    = modifier,
        )

        else -> PspRectangleIcon(
            artworkUri  = item.artworkUri,
            accentColor = item.accentColor?.let { Color(it) },
            title       = item.title,
            modifier    = modifier,
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
        GenericShape { size, d ->
            val notchPx = with(d) { 14.dp.toPx() }
            val radiusPx = with(d) { 4.dp.toPx() }

            moveTo(radiusPx, 0f)
            lineTo(size.width - notchPx, 0f)
            lineTo(size.width, notchPx)
            lineTo(size.width, size.height - radiusPx)
            quadTo(size.width, size.height, size.width - radiusPx, size.height)
            lineTo(radiusPx, size.height)
            quadTo(0f, size.height, 0f, size.height - radiusPx)
            lineTo(0f, radiusPx)
            quadTo(0f, 0f, radiusPx, 0f)
            close()
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

    Box(
        modifier = modifier
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
