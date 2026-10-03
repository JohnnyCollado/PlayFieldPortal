package com.playfieldportal.feature.artwork.api

import android.graphics.drawable.Animatable
import coil3.asDrawable
import coil3.asImage
import coil3.getExtra
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.size.Dimension
import coil3.size.Size
import com.playfieldportal.core.ui.motion.CropDrawable
import com.playfieldportal.core.ui.motion.GatedAnimationDrawable
import com.playfieldportal.core.ui.motion.MotionGate
import com.playfieldportal.core.ui.motion.MotionGateKey
import com.playfieldportal.feature.artwork.store.DrawCropIndex

/**
 * Applies the Animated Images rules to every image the app loads, so the dozens of places that
 * pass artwork paths around never have to know about them:
 *
 *  - **Draw-time crop.** A file [DrawCropIndex] lists is loaded large enough that its cropped
 *    part keeps full resolution, then wrapped in a [CropDrawable]. The crop rides the memory-cache
 *    key, so a re-crop reloads and an uncropped load of the same file never shares its entry.
 *  - **Gated animation.** An animated result (GIF / animated WebP, decoded as usual by Coil's
 *    AnimatedImageDecoder) is wrapped in a [GatedAnimationDrawable] that plays only while the
 *    request's [MotionGate] is open — [MotionGate.Shared] for images drawn without ArtworkImage.
 *
 * A still, uncropped image is returned untouched.
 */
class ArtworkDisplayInterceptor(private val crops: DrawCropIndex) : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val crop = crops.cropFor(request.data)

        val result = if (crop == null) {
            chain.proceed()
        } else {
            val size = chain.size
            val width = size.width
            val height = size.height
            val sourceSize = if (width is Dimension.Pixels && height is Dimension.Pixels) {
                Size(crop.sourceWidthFor(width.px), crop.sourceHeightFor(height.px))
            } else {
                size
            }
            chain
                .withRequest(request.newBuilder().memoryCacheKeyExtra(CROP_KEY, crop.key).build())
                .withSize(sourceSize)
                .proceed()
        }
        if (result !is SuccessResult) return result

        val drawable = result.image.asDrawable(request.context.resources)
        val animatable = drawable as? Animatable
        if (crop == null && animatable == null) return result

        var shown = drawable
        if (crop != null) shown = CropDrawable(shown, crop)
        if (animatable != null) {
            shown = GatedAnimationDrawable(shown, animatable, request.getExtra(MotionGateKey) ?: MotionGate.Shared)
        }
        return result.copy(image = shown.asImage())
    }

    private companion object {
        const val CROP_KEY = "pfp.drawCrop"
    }
}
