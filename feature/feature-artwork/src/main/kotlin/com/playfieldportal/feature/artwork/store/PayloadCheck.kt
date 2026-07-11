package com.playfieldportal.feature.artwork.store

/**
 * Kind-aware magic-byte validation for downloaded payloads. Image kinds go through
 * [ImageFormat]; manuals must be PDF; video snaps must be an MP4-family or WebM container.
 * This is what keeps a CDN error page from being stored as `manual.pdf`.
 */
object PayloadCheck {

    /** [header] should be the first ≥12 bytes of the payload. */
    fun accepts(kind: ArtworkKind, header: ByteArray): Boolean = extFor(kind, header) != null

    /**
     * The canonical file extension for a validated payload of [kind], or null when the header
     * isn't an acceptable type. Single-sources the kind→extension decision so the video kinds
     * can't fall through an image-only sniff.
     */
    fun extFor(kind: ArtworkKind, header: ByteArray): String? = when (kind) {
        ArtworkKind.MANUAL -> "pdf".takeIf { header.startsWithAscii("%PDF") }
        ArtworkKind.VIDEO  -> when {
            isFtyp(header) -> "mp4"
            isEbml(header) -> "webm"
            else           -> null
        }
        else -> ImageFormat.sniff(header)?.ext
    }

    // MP4 family: "ftyp" box tag at offset 4.
    private fun isFtyp(header: ByteArray): Boolean = header.size >= 8 &&
        header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
        header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()

    // WebM/Matroska: EBML magic 1A 45 DF A3.
    private fun isEbml(header: ByteArray): Boolean = header.size >= 4 &&
        header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() &&
        header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte()

    private fun ByteArray.startsWithAscii(text: String): Boolean =
        size >= text.length && text.withIndex().all { (i, c) -> this[i] == c.code.toByte() }
}
