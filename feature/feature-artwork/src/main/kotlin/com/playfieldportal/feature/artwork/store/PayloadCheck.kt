package com.playfieldportal.feature.artwork.store

/**
 * Kind-aware magic-byte validation for downloaded payloads. Image kinds go through
 * [ImageFormat]; manuals must be PDF; video snaps must be an MP4-family or WebM container.
 * This is what keeps a CDN error page from being stored as `manual.pdf`.
 */
object PayloadCheck {

    /** [header] should be the first ≥12 bytes of the payload. */
    fun accepts(kind: ArtworkKind, header: ByteArray): Boolean = when (kind) {
        ArtworkKind.MANUAL -> header.startsWithAscii("%PDF")
        ArtworkKind.VIDEO  -> isVideoContainer(header)
        else               -> ImageFormat.sniff(header) != null
    }

    private fun isVideoContainer(header: ByteArray): Boolean {
        // MP4 family: "ftyp" box tag at offset 4. WebM/Matroska: EBML magic 1A 45 DF A3.
        val ftyp = header.size >= 8 &&
            header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
            header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()
        val ebml = header.size >= 4 &&
            header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() &&
            header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte()
        return ftyp || ebml
    }

    private fun ByteArray.startsWithAscii(text: String): Boolean =
        size >= text.length && text.withIndex().all { (i, c) -> this[i] == c.code.toByte() }
}
