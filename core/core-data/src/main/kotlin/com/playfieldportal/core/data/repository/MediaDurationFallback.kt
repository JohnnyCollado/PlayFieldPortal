package com.playfieldportal.core.data.repository

import com.playfieldportal.themekit.MediaDurationProbe
import java.io.File

/**
 * Computes a media file's duration from its container headers when MediaMetadataRetriever
 * cannot. Some Android devices return a null `METADATA_KEY_DURATION` for otherwise-playable
 * MP3s — notably very short VBR clips (a handful of frames) and files encoded by ffmpeg's
 * native MP3 encoder — and for some WAVs. The length is trivially readable from the container,
 * so the import gate falls back to reading it deterministically instead of rejecting a file
 * the launcher could play.
 *
 * The container parsing (MP3, WAV, OGG, MP4/M4A) lives in theme-kit's [MediaDurationProbe] so the
 * Studio shares it; this stays the import gate's entry point. Anything the probe cannot time
 * returns null, so the gate still rejects genuinely unreadable media — this rescue only narrows
 * WHEN the gate rejects, it never widens what passes the caps.
 */
object MediaDurationFallback {

    /**
     * Returns the container's duration in milliseconds, or null when the container is not one
     * the probe understands. [mime] is the import gate's resolved MIME (from the retriever or
     * the stored extension), which decides which container parser runs.
     */
    fun durationMs(file: File, mime: String?): Long? = MediaDurationProbe.durationMs(file, mime)
}
