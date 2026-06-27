package com.playfieldportal.core.ui.icons

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.playfieldportal.core.ui.R

// Shared XMB icon sprite sheet (xmb_icon_sprite_sheet.png): 6 columns × 5 rows, each cell
// 256 × 204 px. Maps a category / platform iconKey to its sprite cell so the same glyphs the
// XMB uses can be previewed elsewhere (e.g. the category icon picker in Settings).
//
//  Row 0 — category bar icons: settings, music, videos, games (controller), network, photos
//  Rows 1–4 — platform / console icons (arcade, SNES, N64, PSP, PlayStation, Switch, PC, …)
private const val CELL_W = 256
private const val CELL_H = 204

val XMB_ICON_SPRITE_MAP: Map<String, Pair<Int, Int>> = mapOf(
    // Category-bar row
    "ic_settings"    to (0 to 0),
    "ic_music"       to (1 to 0),
    "ic_videos"      to (2 to 0),
    "ic_games"       to (3 to 0),
    "ic_network"     to (4 to 0),
    "ic_photos"      to (5 to 0),
    "ic_appstore"    to (5 to 0),   // reuses the connected-circles cell
    // Platform / console rows
    "ic_arcade"      to (0 to 1),
    "ic_atari"       to (1 to 1),
    "ic_nes"         to (2 to 1),
    "ic_genesis"     to (3 to 1),
    "ic_n64"         to (4 to 1),
    "ic_gamecube"    to (5 to 1),
    "ic_wii"         to (0 to 2),
    "ic_switch"      to (1 to 2),
    "ic_gameboy"     to (2 to 2),
    "ic_gba"         to (3 to 2),
    "ic_ds"          to (4 to 2),
    "ic_snes"        to (5 to 2),
    "ic_turbografx"  to (0 to 3),
    "ic_sega_cd"     to (1 to 3),
    "ic_saturn"      to (2 to 3),
    "ic_ps1"         to (3 to 3),
    "ic_ps2"         to (4 to 3),
    "ic_ps3"         to (5 to 3),
    "ic_psp"         to (0 to 4),
    "ic_vita"        to (1 to 4),
    "ic_xbox"        to (2 to 4),
    "ic_pc"          to (3 to 4),
    "ic_ps_generic"  to (4 to 4),
    "ic_android"     to (5 to 4),
)

private val FALLBACK_SPRITE = 3 to 0   // games controller

@Composable
private fun rememberSpriteSheet(): ImageBitmap {
    val context = LocalContext.current
    return remember(context) {
        BitmapFactory.decodeResource(context.resources, R.drawable.xmb_icon_sprite_sheet)
            .asImageBitmap()
    }
}

/** Renders the sprite glyph for [iconKey] (full colour, no tint). Unknown keys fall back to
 *  the games controller. Size via [modifier]. */
@Composable
fun CategoryIconGlyph(
    iconKey: String,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    val sheet = rememberSpriteSheet()
    val (col, row) = XMB_ICON_SPRITE_MAP[iconKey] ?: FALLBACK_SPRITE
    val painter = remember(sheet, col, row) {
        BitmapPainter(
            image     = sheet,
            srcOffset = IntOffset(col * CELL_W, row * CELL_H),
            srcSize   = IntSize(CELL_W, CELL_H),
        )
    }
    Image(
        painter            = painter,
        contentDescription = contentDescription,
        contentScale       = ContentScale.Fit,
        modifier           = modifier,
    )
}
