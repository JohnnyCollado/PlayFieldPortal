package com.playfieldportal.feature.artwork.store

/**
 * Magic-byte image sniffing. Downloads are streamed to disk without decoding (no more
 * decode→re-encode quality loss), so this header check is what stops a CDN error page or
 * truncated response from being saved as artwork.
 */
enum class ImageFormat(val ext: String) {
    JPEG("jpg"),
    PNG("png"),
    WEBP("webp"),
    GIF("gif"),
    BMP("bmp");

    companion object {
        /** Sniffs the first bytes of a file ([header] should be ≥ 12 bytes). Null = not an image. */
        fun sniff(header: ByteArray): ImageFormat? {
            fun at(i: Int) = header.getOrNull(i)?.toInt()?.and(0xFF)
            fun ascii(from: Int, text: String) =
                text.withIndex().all { (i, c) -> at(from + i) == c.code }
            return when {
                at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> JPEG
                at(0) == 0x89 && ascii(1, "PNG")                -> PNG
                ascii(0, "RIFF") && ascii(8, "WEBP")            -> WEBP
                ascii(0, "GIF8")                                -> GIF
                ascii(0, "BM")                                  -> BMP
                else                                            -> null
            }
        }

        /**
         * Whether [header] is an animated image: any GIF, or a WebP whose VP8X header sets the
         * animation flag. Decides whether a crop is baked into a still PNG or applied at draw time
         * so the animation survives. [header] should be the first ≥ 21 bytes.
         */
        fun isAnimated(header: ByteArray): Boolean {
            fun at(i: Int) = header.getOrNull(i)?.toInt()?.and(0xFF)
            fun ascii(from: Int, text: String) =
                text.withIndex().all { (i, c) -> at(from + i) == c.code }
            return when (sniff(header)) {
                GIF  -> true
                // RIFF(0) size(4) WEBP(8) VP8X(12) chunk size(16) flags(20); bit 1 = animation.
                WEBP -> ascii(12, "VP8X") && (at(20)?.and(0x02) ?: 0) != 0
                else -> false
            }
        }
    }
}
