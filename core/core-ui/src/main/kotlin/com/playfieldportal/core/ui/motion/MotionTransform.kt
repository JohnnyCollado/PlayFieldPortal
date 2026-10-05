package com.playfieldportal.core.ui.motion

import com.playfieldportal.themekit.MotionCrop

/**
 * Matrix parameters for a video TextureView, which stretches the frame to its bounds before the
 * matrix applies: `setScale(scaleX, scaleY, viewW / 2, viewH / 2)` then `postTranslate(translateX,
 * translateY)`.
 */
data class MotionTransform(
    val scaleX: Float,
    val scaleY: Float,
    val translateX: Float,
    val translateY: Float,
)

/**
 * Cover-fits the crop region of the video (the whole frame when [crop] is null) into the view,
 * centered on the region. A null crop is exactly the legacy center-crop. When the region's aspect
 * differs from the view's, the overflow is clamped so the frame always covers the view.
 */
fun motionTransform(
    viewW: Float,
    viewH: Float,
    videoW: Float,
    videoH: Float,
    crop: MotionCrop?,
): MotionTransform {
    val regionW = videoW * (crop?.w ?: 1f)
    val regionH = videoH * (crop?.h ?: 1f)
    val scale = maxOf(viewW / regionW, viewH / regionH)
    val drawnW = videoW * scale
    val drawnH = videoH * scale
    if (crop == null) return MotionTransform(drawnW / viewW, drawnH / viewH, 0f, 0f)
    val maxTx = maxOf(0f, (drawnW - viewW) / 2f)
    val maxTy = maxOf(0f, (drawnH - viewH) / 2f)
    return MotionTransform(
        scaleX = drawnW / viewW,
        scaleY = drawnH / viewH,
        translateX = ((0.5f - (crop.x + crop.w / 2f)) * drawnW).coerceIn(-maxTx, maxTx),
        translateY = ((0.5f - (crop.y + crop.h / 2f)) * drawnH).coerceIn(-maxTy, maxTy),
    )
}

/** Where the video's view sits relative to the screen-sized area it fills; see [motionSurfaceRect]. */
data class MotionSurfaceRect(val left: Float, val top: Float, val width: Float, val height: Float)

/**
 * The same placement as [motionTransform], expressed as a rectangle for a view that cannot take a
 * transform matrix (a SurfaceView): the view is laid out at this size and offset, oversized past the
 * screen edges, and the screen clips it. A frame pixel lands exactly where the matrix put it.
 */
fun motionSurfaceRect(
    viewW: Float,
    viewH: Float,
    videoW: Float,
    videoH: Float,
    crop: MotionCrop?,
): MotionSurfaceRect {
    val t = motionTransform(viewW, viewH, videoW, videoH, crop)
    val width = viewW * t.scaleX
    val height = viewH * t.scaleY
    return MotionSurfaceRect(
        left = (viewW - width) / 2f + t.translateX,
        top = (viewH - height) / 2f + t.translateY,
        width = width,
        height = height,
    )
}

/** The crop only applies to MP4/WebM; GIF/animated-WebP and unknown extensions ignore it. */
fun cropForMotionPath(path: String, crop: MotionCrop?): MotionCrop? =
    when (path.substringAfterLast('.', "").lowercase()) {
        "mp4", "webm" -> crop
        else -> null
    }
