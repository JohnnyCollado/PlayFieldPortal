package com.playfieldportal.feature.social.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders a QR code bitmap for the device-authorization verification URL. Generation only — the
 * phone scans; the TV displays. The encoded content is a public `discord.com/activate?...` URL,
 * never a token.
 */
object QrEncoder {
    fun encode(
        content: String,
        sizePx: Int,
        darkColor: Int = Color.WHITE,
        lightColor: Int = Color.TRANSPARENT,
    ): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height) { i ->
            if (matrix.get(i % width, i / width)) darkColor else lightColor
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }
}
