package com.playfieldportal.feature.artwork.card

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads source artwork and composites it into a platform-specific card PNG.
 *
 * Output contract:
 *   • Box art → card-composited PNG at [template].cardW × [template].cardH
 *   • Hero / logo → downloaded as-is (no card template applied — they're
 *     used for full-screen backgrounds and overlay logos respectively)
 *
 * The result is saved to:
 *   {filesDir}/artwork/cards/{platformId}/{gameId}.png        (box art card)
 *   {filesDir}/artwork/{gameId}/hero.jpg                      (hero)
 *   {filesDir}/artwork/{gameId}/logo.png                      (logo)
 *
 * Coil is used for downloading so we benefit from its disk cache.
 * `allowHardware(false)` forces a software-backed bitmap so Canvas can draw on it.
 */
@Singleton
class CardArtworkProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val imageLoader: ImageLoader,
) {
    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Processes box-art for the given game/platform.
     * Returns the absolute path of the saved PNG, or null on failure.
     */
    suspend fun processBoxArt(
        gameId: Long,
        platformId: String,
        sourceUrl: String,
    ): String? {
        val template = CardTemplateRegistry.forPlatform(platformId)
        val source   = downloadBitmap(sourceUrl) ?: return null

        val output = composite(source, template, template.scaleMode)
        source.recycle()

        return savePng(
            bitmap = output,
            file   = cardFile(platformId, gameId),
        )
    }

    /**
     * Downloads a hero / logo URL to local storage without card compositing.
     * Returns the absolute path, or null on failure.
     */
    suspend fun downloadRaw(
        gameId: Long,
        url: String,
        fileName: String,           // e.g. "hero.jpg" or "logo.png"
        asPng: Boolean = false,
    ): String? {
        val bitmap = downloadBitmap(url) ?: return null
        val file   = rawFile(gameId, fileName)
        val result = if (asPng) savePng(bitmap, file) else saveJpeg(bitmap, file)
        bitmap.recycle()
        return result
    }

    // ── Compositing ───────────────────────────────────────────────────────────

    private fun composite(
        source: Bitmap,
        template: PlatformCardTemplate,
        scaleMode: ArtworkScaleMode,
    ): Bitmap {
        val output = Bitmap.createBitmap(
            template.cardW,
            template.cardH,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(output)

        // 1. Background
        if (!template.preserveAlpha) {
            canvas.drawColor(template.bgColor)
        } else {
            canvas.drawColor(0, PorterDuff.Mode.CLEAR)   // transparent
        }

        // 2. Compute placement matrix — scale source into artWindow
        val matrix = buildMatrix(source, template.artWindow, scaleMode)

        // 3. Clip to art window so overflow is hidden
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.save()
        canvas.clipRect(template.artWindow)
        canvas.drawBitmap(source, matrix, paint)
        canvas.restore()

        return output
    }

    private fun buildMatrix(
        source: Bitmap,
        window: RectF,
        scaleMode: ArtworkScaleMode,
    ): Matrix {
        val srcW = source.width.toFloat()
        val srcH = source.height.toFloat()
        val winW = window.width()
        val winH = window.height()

        val scale = when (scaleMode) {
            ArtworkScaleMode.CROP    -> maxOf(winW / srcW, winH / srcH)
            ArtworkScaleMode.CONTAIN -> minOf(winW / srcW, winH / srcH)
        }

        val scaledW = srcW * scale
        val scaledH = srcH * scale

        // Centre the scaled image in the window
        val tx = window.left + (winW - scaledW) / 2f
        val ty = window.top  + (winH - scaledH) / 2f

        return Matrix().apply {
            setScale(scale, scale)
            postTranslate(tx, ty)
        }
    }

    // ── Download ──────────────────────────────────────────────────────────────

    private suspend fun downloadBitmap(url: String): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(url)
            .allowHardware(false)           // must be software-backed for Canvas operations
            .memoryCachePolicy(CachePolicy.DISABLED) // we recycle the bitmap after saving; disabling
                                            // memory cache prevents Coil returning the recycled
                                            // instance on a subsequent request for the same URL
            .build()

        return when (val result = imageLoader.execute(request)) {
            is SuccessResult -> {
                (result.drawable as? BitmapDrawable)?.bitmap?.also { bmp ->
                    Timber.d("CardArtworkProcessor: downloaded ${bmp.width}×${bmp.height} from $url")
                }
            }
            else -> {
                Timber.w("CardArtworkProcessor: download failed for $url")
                null
            }
        }
    }

    // ── Save helpers ──────────────────────────────────────────────────────────

    private fun savePng(bitmap: Bitmap, file: File): String? = trySave(file) {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
    }

    private fun saveJpeg(bitmap: Bitmap, file: File): String? = trySave(file) {
        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)
    }

    private inline fun trySave(file: File, block: (java.io.OutputStream) -> Unit): String? {
        return try {
            file.parentFile?.mkdirs()
            file.outputStream().use { block(it) }
            file.absolutePath
        } catch (e: Exception) {
            Timber.e(e, "CardArtworkProcessor: save failed for ${file.path}")
            null
        }
    }

    // ── Path helpers ──────────────────────────────────────────────────────────

    fun cardFile(platformId: String, gameId: Long): File =
        File(context.filesDir, "artwork/cards/$platformId/$gameId.png")

    fun rawFile(gameId: Long, fileName: String): File =
        File(context.filesDir, "artwork/$gameId/$fileName")

    // Returns true if a processed card already exists for this game.
    fun hasCard(platformId: String, gameId: Long): Boolean =
        cardFile(platformId, gameId).exists()
}
