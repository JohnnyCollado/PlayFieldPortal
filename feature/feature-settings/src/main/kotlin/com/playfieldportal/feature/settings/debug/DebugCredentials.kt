package com.playfieldportal.feature.settings.debug

import java.io.InputStream

// ── Debug credentials file: the parts every variant shares ────────────────────
//
// Settings ▸ Artwork can load every artwork and achievement credential from one `.properties` file
// in debug builds. Everything that reads or saves credential values — the parser and the loader —
// lives in the `debug` source set; `release` carries a stub loader, so none of it ships. What stays
// here is only what the ViewModel needs to compile in both variants.

/** What a load did, as one status line for the Settings row. */
data class DebugCredentialsResult(
    val status: String,
    /** A secret was stored in plaintext because the device keystore was unavailable. */
    val anyUnprotected: Boolean,
)

/** Bigger than any real credentials file by far: a real one is well under 2 KB. */
const val DEBUG_CREDENTIALS_MAX_BYTES = 64 * 1024

/**
 * Reads a picked credentials file as UTF-8, or returns null when it is larger than [maxBytes].
 * Reads at most one byte past the cap, so a wrong pick (a video, a ROM) is never read whole.
 */
fun readCredentialsText(input: InputStream, maxBytes: Int = DEBUG_CREDENTIALS_MAX_BYTES): String? {
    val buffer = ByteArray(maxBytes + 1)
    var filled = 0
    while (filled < buffer.size) {
        val n = input.read(buffer, filled, buffer.size - filled)
        if (n < 0) break
        filled += n
    }
    if (filled > maxBytes) return null
    return String(buffer, 0, filled, Charsets.UTF_8)
}
