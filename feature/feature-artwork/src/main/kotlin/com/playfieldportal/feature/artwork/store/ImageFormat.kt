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
    }
}
