package com.playfieldportal.feature.xmb.ui.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.feature.artwork.store.ArtworkKind
import kotlin.math.roundToInt

/**
 * C16 tasks 6.2 and 6.6 — the live "final result" inset in the crop editor.
 *
 * The editor's frame shows which *region* of the source is kept; this shows what that region will
 * look like once it is the finished artwork, in that artwork's own chrome. It reads the bitmap (or
 * clip) the editor already opened and the crop window the editor already maintains, so it adds no
 * decode, no UI-state field and no ViewModel work — it is a second view of state that exists.
 */

/**
 * The two tile treatments in [com.playfieldportal.feature.xmb.ui.GameIconView] — there are only
 * two, not one per kind.
 *
 * [PSP_TILE] mirrors `PspIcon0Icon`: rounded clip, dark backing, hairline border and a top gloss.
 * [FRAMELESS] mirrors `NaturalAspectArtIcon` in its unframed form, which is what 3D boxes and
 * physical media use because they are transparent silhouettes rather than opaque rectangles —
 * the Artwork Dimension & Aspect Ratio Policy requires those transparent bounds to be preserved,
 * so the preview draws the cropped region alone and invents no rectangle around it.
 */
enum class CropPreviewChrome { PSP_TILE, FRAMELESS }

/**
 * The chrome [kind] is drawn in where it finally appears, or null when the kind has no on-screen
 * representation and therefore nothing a "final result" preview could honestly show.
 *
 * Total over [ArtworkKind] on purpose: a kind added later falls through to null and gets no inset,
 * rather than borrowing whichever treatment happened to sit above it in a `when`.
 */
fun cropPreviewChromeFor(kind: ArtworkKind): CropPreviewChrome? = when (kind) {
    // Opaque rectangles in the XMB tile slot: framed like the native PSP ICON0. ICON1 is the same
    // slot in motion (PSP ICON1.PMF), so it wears the same chrome its still counterpart does.
    ArtworkKind.ICON, ArtworkKind.BOX_ART, ArtworkKind.ICON1 -> CropPreviewChrome.PSP_TILE
    // Transparent silhouettes: no frame, no backing. VIDEO joins them because it plays in the Game
    // Details media strip, which draws no PSP frame — framing it would invent chrome it never has.
    ArtworkKind.BOX_3D, ArtworkKind.PHYSICAL_MEDIA, ArtworkKind.VIDEO -> CropPreviewChrome.FRAMELESS
    // No tile or strip slot of their own — banner, background, overlay, strip stills, document.
    ArtworkKind.HERO, ArtworkKind.BACKGROUND, ArtworkKind.LOGO,
    ArtworkKind.SCREENSHOT, ArtworkKind.TITLESCREEN, ArtworkKind.MANUAL -> null
}

/** Caption under the inset, so it reads as a preview of a named slot and not a stray thumbnail. */
fun cropPreviewCaptionFor(kind: ArtworkKind): String? = when (kind) {
    ArtworkKind.ICON -> "XMB tile"
    ArtworkKind.ICON1 -> "XMB icon animation"
    ArtworkKind.BOX_ART -> "Box Art tile"
    ArtworkKind.BOX_3D -> "3D Box tile"
    ArtworkKind.PHYSICAL_MEDIA -> "Phys. Media tile"
    ArtworkKind.VIDEO -> "Media strip"
    else -> null
}

// Lifted from GameIconView's private chrome constants so the inset matches the real tile. They are
// duplicated rather than exposed because these four values ARE the PSP tile's look: if the tile
// changes, this preview is meant to be revisited alongside it, not to follow silently.
private val PreviewShape = RoundedCornerShape(4.dp)
private val PreviewBacking = Color(0xFF0A0A0F)
private val PreviewBorder = Color(0x55FFFFFF)
private val PreviewShine = Color(0x18FFFFFF)

/** Inset width. Big enough to judge framing at a glance, small enough to stay out of the way. */
private val PreviewWidth = 132.dp

/**
 * The chrome, the caption and the tile's shape — everything the still and video insets share.
 *
 * [aspect] must come from the editor's own frame geometry (`frameAspectFor`): the whole point is
 * that this and the crop frame show the same rectangle, and a locally re-derived ratio would drift
 * from it the moment a crop profile changed.
 */
@Composable
private fun CropPreviewFrame(
    aspect: Float,
    chrome: CropPreviewChrome,
    caption: String,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Column(
        modifier = modifier.width(PreviewWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(aspect.coerceAtLeast(0.0001f))
                .then(
                    if (chrome == CropPreviewChrome.PSP_TILE) {
                        Modifier
                            .clip(PreviewShape)
                            .background(PreviewBacking)
                            .border(1.dp, PreviewBorder, PreviewShape)
                    } else {
                        Modifier
                    }
                ),
        ) {
            content()
            if (chrome == CropPreviewChrome.PSP_TILE) {
                // Gloss shine across the top, as PspIcon0Icon draws it.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.3f)
                        .align(Alignment.TopCenter)
                        .background(Brush.verticalGradient(listOf(PreviewShine, Color.Transparent)))
                )
            }
        }
        Text(
            caption,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Still inset: the crop window's region of [image], drawn at the frame's aspect in [chrome].
 *
 * The crop bounds are fractions of the source, so they map onto the decoded bitmap directly: the
 * bitmap is downsampled (≤1600 px) and its pixel dimensions are not the source's, but the
 * fractions are resolution-independent. `srcOffset`/`srcSize` blit the region straight out of the
 * existing bitmap — exact, and no scaled copy is allocated.
 */
@Composable
fun StudioCropPreviewTile(
    image: ImageBitmap,
    aspect: Float,
    cropL: Float, cropT: Float, cropR: Float, cropB: Float,
    chrome: CropPreviewChrome,
    caption: String,
    modifier: Modifier = Modifier,
) {
    CropPreviewFrame(aspect, chrome, caption, modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val srcX = (cropL * image.width).roundToInt().coerceIn(0, image.width)
            val srcY = (cropT * image.height).roundToInt().coerceIn(0, image.height)
            val srcW = ((cropR - cropL) * image.width).roundToInt()
                .coerceIn(1, image.width - srcX)
            val srcH = ((cropB - cropT) * image.height).roundToInt()
                .coerceIn(1, image.height - srcY)
            drawImage(
                image = image,
                srcOffset = IntOffset(srcX, srcY),
                srcSize = IntSize(srcW, srcH),
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            )
        }
    }
}

/**
 * Video inset (task 6.6): the same tile, playing.
 *
 * There is no cropping API on a video surface, so the crop is done by layout — the clip is laid
 * out at `tileSize / cropSize` and shifted by `-crop origin × that`, inside a clipped tile. That
 * is the same mapping the full-screen canvas uses, one scale down, so the two agree by
 * construction.
 */
@Composable
fun StudioCropPreviewVideoTile(
    player: androidx.media3.exoplayer.ExoPlayer,
    aspect: Float,
    cropL: Float, cropT: Float, cropR: Float, cropB: Float,
    chrome: CropPreviewChrome,
    caption: String,
    modifier: Modifier = Modifier,
) {
    CropPreviewFrame(aspect, chrome, caption, modifier) {
        BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
            val density = LocalDensity.current
            val tileW = with(density) { maxWidth.toPx() }
            val tileH = with(density) { maxHeight.toPx() }
            val dispW = tileW / (cropR - cropL).coerceAtLeast(0.0001f)
            val dispH = tileH / (cropB - cropT).coerceAtLeast(0.0001f)
            CropVideoSurface(
                player = player,
                modifier = Modifier
                    .offset {
                        IntOffset((-cropL * dispW).roundToInt(), (-cropT * dispH).roundToInt())
                    }
                    .size(with(density) { dispW.toDp() }, with(density) { dispH.toDp() }),
            )
        }
    }
}
