package com.playfieldportal.feature.xmb.ui

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
import com.playfieldportal.feature.xmb.R

// ── Sprite sheet layout ───────────────────────────────────────────────────────
//
//  xmb_icon_sprite_sheet.png — 1536 × 1024 px, 6 columns × 5 rows
//  Each cell: 256 × 204 px  (last row has 208 px due to 1024 mod 5 = 4)
//
//  Row 0 — category bar icons:
//   col 0 = Settings (toolbox)
//   col 1 = Music (note)
//   col 2 = Videos (film strip)
//   col 3 = Games (PSP-style controller)
//   col 4 = Network (globe)
//   col 5 = Photos (sharing circles)
//
//  Rows 1–4 — platform / console icons (used by XMB vertical list):
//   row 1: Arcade, Atari/Joystick, NES, Genesis, N64, GameCube
//   row 2: Wii, Switch, Game Boy, GBA, DS, SNES
//   row 3: TurboGrafx, Sega CD, Saturn, PS1, PS2, PS3
//   row 4: PSP, PS Vita, Xbox, PC, PS-generic, Android

private const val CELL_W = 256
private const val CELL_H = 204   // uniform; last row clips 4px — acceptable

/**
 * Maps an iconKey string to its (column, row) position in the sprite sheet.
 * Default fallback is (3, 0) — the Games/controller icon.
 */
internal val XMB_SPRITE_MAP: Map<String, Pair<Int, Int>> = mapOf(
    // ── Category bar (row 0) ──────────────────────────────────────────────
    "ic_settings"    to (0 to 0),
    "ic_music"       to (1 to 0),
    "ic_videos"      to (2 to 0),
    "ic_games"       to (3 to 0),   // PSP-style controller — Games category
    "ic_network"     to (4 to 0),
    "ic_photos"      to (5 to 0),
    // ── Platform / console icons (rows 1–4) ──────────────────────────────
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

private val FALLBACK_SPRITE = 3 to 0   // Games controller

/** Loads and caches the sprite sheet bitmap for the current composition. */
@Composable
internal fun rememberXmbSpriteSheet(): ImageBitmap {
    val context = LocalContext.current
    return remember(context) {
        BitmapFactory.decodeResource(context.resources, R.drawable.xmb_icon_sprite_sheet)
            .asImageBitmap()
    }
}

/**
 * Returns a [BitmapPainter] cropped to the sprite for [iconKey].
 * Must be called inside a Composable scope.
 */
@Composable
internal fun rememberXmbSpritePainter(iconKey: String): BitmapPainter {
    val sheet          = rememberXmbSpriteSheet()
    val (col, row)     = XMB_SPRITE_MAP[iconKey] ?: FALLBACK_SPRITE
    return remember(sheet, col, row) {
        BitmapPainter(
            image     = sheet,
            srcOffset = IntOffset(col * CELL_W, row * CELL_H),
            srcSize   = IntSize(CELL_W, CELL_H),
        )
    }
}

/**
 * Renders a single icon from the XMB sprite sheet.
 *
 * Alpha and size are applied via [modifier]. This renders the sprite in full
 * color (no tint) — appropriate for photographic/3D icons.
 */
@Composable
fun XmbSpriteIcon(
    iconKey: String,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    val painter = rememberXmbSpritePainter(iconKey)
    Image(
        painter            = painter,
        contentDescription = contentDescription,
        contentScale       = ContentScale.Fit,
        modifier           = modifier,
    )
}
