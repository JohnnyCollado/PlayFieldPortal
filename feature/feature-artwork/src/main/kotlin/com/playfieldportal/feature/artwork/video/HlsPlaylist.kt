package com.playfieldportal.feature.artwork.video

import com.playfieldportal.feature.artwork.store.ArtworkKind

/**
 * Just enough HLS to save a Steam trailer: a master playlist's renditions and audio track, and a
 * media playlist's init segment and chunks. Steam packages its trailers as CMAF (fragmented mp4
 * chunks behind an `#EXT-X-MAP` init segment), which is what makes a plain remux possible — see
 * [HlsTrailerAssembler]. Anything this does not understand is skipped, never guessed at.
 */

/** One video rendition. [height] is 0 when the playlist gives no RESOLUTION. */
data class HlsVariant(val uri: String, val bandwidth: Long, val height: Int)

data class HlsMaster(val variants: List<HlsVariant>, val audioUri: String?) {

    /**
     * The tallest rendition no taller than [maxHeight] (by bandwidth when heights tie or are
     * missing), or the smallest one when every rendition is taller — something rather than nothing.
     */
    fun pick(maxHeight: Int): HlsVariant? {
        val ranked = variants.sortedWith(compareBy<HlsVariant> { it.height }.thenBy { it.bandwidth })
        return ranked.lastOrNull { it.height <= maxHeight } ?: ranked.firstOrNull()
    }

    companion object {
        fun parse(text: String): HlsMaster {
            val lines = text.lines().map { it.trim() }
            var audio: String? = null
            val variants = mutableListOf<HlsVariant>()
            lines.forEachIndexed { index, line ->
                when {
                    line.startsWith("#EXT-X-MEDIA:") && "TYPE=AUDIO" in line ->
                        audio = audio ?: attribute(line, "URI")
                    line.startsWith("#EXT-X-STREAM-INF:") -> {
                        val uri = lines.drop(index + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                            ?: return@forEachIndexed
                        variants += HlsVariant(
                            uri = uri,
                            bandwidth = attribute(line, "BANDWIDTH")?.toLongOrNull() ?: 0,
                            height = attribute(line, "RESOLUTION")?.substringAfter('x')?.toIntOrNull() ?: 0,
                        )
                    }
                }
            }
            return HlsMaster(variants, audio)
        }
    }
}

data class HlsSegment(val uri: String, val seconds: Double)

data class HlsMedia(val initUri: String?, val segments: List<HlsSegment>) {

    /**
     * The chunks up to [maxSeconds] (all of them when null). The chunk that crosses the limit is
     * kept: chunks are whole GOPs, and a snap a second long beats one cut short.
     */
    fun within(maxSeconds: Double?): HlsMedia {
        if (maxSeconds == null) return this
        var elapsed = 0.0
        val kept = segments.takeWhile { segment ->
            (elapsed < maxSeconds).also { elapsed += segment.seconds }
        }
        return copy(segments = kept)
    }

    companion object {
        fun parse(text: String): HlsMedia {
            var init: String? = null
            var pendingSeconds = 0.0
            val segments = mutableListOf<HlsSegment>()
            for (raw in text.lines()) {
                val line = raw.trim()
                when {
                    line.isEmpty() -> Unit
                    line.startsWith("#EXT-X-MAP:") -> init = attribute(line, "URI")
                    line.startsWith("#EXTINF:") ->
                        pendingSeconds = line.removePrefix("#EXTINF:").substringBefore(',').toDoubleOrNull() ?: 0.0
                    line.startsWith("#") -> Unit
                    else -> {
                        segments += HlsSegment(line, pendingSeconds)
                        pendingSeconds = 0.0
                    }
                }
            }
            return HlsMedia(init, segments)
        }
    }
}

object HlsUrls {
    /** An address whose path ends in `.m3u8`, whatever its query. */
    fun isHls(url: String): Boolean = url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)

    /** [ref] against the folder [playlistUrl] sits in. The playlist's own query is not carried over. */
    fun resolve(playlistUrl: String, ref: String): String {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        return playlistUrl.substringBefore('?').substringBeforeLast('/') + "/" + ref
    }
}

/**
 * What a tab takes from a streamed trailer. Video keeps the trailer whole, at a size a handheld's
 * media strip needs, with sound. ICON1 is a snap like every other: silent, the first minute, and
 * the smallest rendition, so it is small without a re-encode.
 */
data class HlsTrailerPlan(val maxHeight: Int, val withAudio: Boolean, val maxSeconds: Double?) {
    companion object {
        fun forKind(kind: ArtworkKind): HlsTrailerPlan? = when (kind) {
            ArtworkKind.VIDEO -> HlsTrailerPlan(maxHeight = 720, withAudio = true, maxSeconds = null)
            ArtworkKind.ICON1 -> HlsTrailerPlan(maxHeight = 360, withAudio = false, maxSeconds = 60.0)
            else -> null
        }
    }
}

/** A quoted or bare `NAME=value` from an HLS tag line. */
private fun attribute(line: String, name: String): String? =
    Regex("""(?:^|[:,])$name=("([^"]*)"|[^,]*)""").find(line)?.let { match ->
        match.groups[2]?.value ?: match.groups[1]?.value
    }
