package com.playfieldportal.feature.achievements.match

/**
 * Exposes a CD-based CHD as a [DiscImage.SectorSource] of logical user-data sectors, so the existing
 * PlayStation / Sega disc hashers read a `.chd` exactly as they read a `.bin`. CHD stores the disc as
 * (2352 + 96)-byte frames grouped into hunks; this maps a disc LBA to its frame, decompresses the
 * owning hunk (via [ChdReader]), and returns the sector's user data.
 *
 * Addressing follows libchdr / Flycast: `fad = lba + 150`, and each track's frames sit at a running,
 * 4-frame-padded offset within the hunk stream (`CD_TRACK_PADDING`). Handles both plain CDs
 * (PSX/PS2/Saturn/Sega CD; ISO on track 1, LBA 0) and Dreamcast GD-ROM (ISO on the high-density data
 * track at LBA ~45000, exposed via [firstTrackSector]). See docs/shiba-coins-achievements-plan.md.
 */
class ChdSectorSource private constructor(
    private val chd: ChdReader,
    private val tracks: List<Track>,
    private val framesPerHunk: Int,
    /** Disc LBA of the track the ISO9660 filesystem lives on: 0 for a CD, ~45000 for a GD-ROM. */
    val firstTrackSector: Int,
) : DiscImage.SectorSource {

    private class Track(
        val startFad: Int,
        val endFad: Int,
        val chdFrameStart: Int,
        val userOffset: Int,
        val isData: Boolean,
    )

    override fun readSector(lba: Int, count: Int): ByteArray {
        val fad = lba + PREGAP_FRAMES
        val track = tracks.firstOrNull { it.isData && fad >= it.startFad && fad <= it.endFad }
            ?: return ByteArray(0)
        val chdFrame = fad - track.startFad + track.chdFrameStart
        val hunk = runCatching { chd.readHunk(chdFrame / framesPerHunk) }.getOrNull() ?: return ByteArray(0)
        val frameOffset = (chdFrame % framesPerHunk) * ChdReader.CD_FRAME_SIZE + track.userOffset

        val out = ByteArray(count)
        val avail = minOf(count, hunk.size - frameOffset).coerceAtLeast(0)
        if (avail > 0) System.arraycopy(hunk, frameOffset, out, 0, avail)
        return if (avail == count) out else out.copyOf(avail)
    }

    override fun close() = chd.close()

    companion object {
        private const val PREGAP_FRAMES = 150       // FAD = LBA + 150
        private const val CD_TRACK_PADDING = 4      // hunk stream pads each track to 4 frames
        private const val HIGH_DENSITY_FAD = 45000  // GD-ROM high-density area (Dreamcast data track)

        private val TYPE = Regex("""TYPE:(\S+)""")
        private val FRAMES = Regex("""FRAMES:(\d+)""")

        /**
         * Builds a source from an open [chd], or null if it isn't a hashable CD/GD-ROM CHD (wrong hunk
         * geometry, no readable codec, or no data track). Takes ownership of [chd] on success.
         */
        fun of(chd: ChdReader): ChdSectorSource? {
            val hunkBytes = chd.header.hunkBytes
            if (hunkBytes == 0 || hunkBytes % ChdReader.CD_FRAME_SIZE != 0) return null
            if (chd.header.compressed && chd.header.compressors.none {
                    it == ChdReader.CODEC_CD_ZLIB || it == ChdReader.CODEC_CD_LZMA ||
                        it == ChdReader.CODEC_ZLIB || it == ChdReader.CODEC_LZMA || it == ChdReader.CODEC_NONE
                }
            ) {
                return null // all codecs are FLAC/ZSTD — nothing we can decode
            }
            val tracks = parseTracks(chd)
            val dataTracks = tracks.filter { it.isData }
            if (dataTracks.isEmpty()) return null
            // The ISO9660 filesystem lives on the high-density data track for a GD-ROM (StartFAD
            // ~45150), or track 1 for a plain CD. Anchor at that track's LBA (StartFAD - 150).
            val isoTrack = dataTracks.firstOrNull { it.startFad >= HIGH_DENSITY_FAD } ?: dataTracks.first()
            return ChdSectorSource(
                chd, tracks, hunkBytes / ChdReader.CD_FRAME_SIZE, isoTrack.startFad - PREGAP_FRAMES,
            )
        }

        private fun parseTracks(chd: ChdReader): List<Track> {
            val tracks = mutableListOf<Track>()
            var totalFrames = PREGAP_FRAMES
            var chdOffset = 0
            var index = 0
            while (true) {
                val text = chd.metadata(ChdReader.CDROM_TRACK_METADATA2_TAG, index)
                    ?: chd.metadata(ChdReader.CDROM_TRACK_METADATA_TAG, index)
                    ?: chd.metadata(ChdReader.GDROM_TRACK_METADATA_TAG, index)
                    ?: chd.metadata(ChdReader.GDROM_OLD_METADATA_TAG, index)
                    ?: break
                val type = TYPE.find(text)?.groupValues?.get(1) ?: break
                val frames = FRAMES.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: break

                val startFad = totalFrames
                totalFrames += frames
                tracks += Track(
                    startFad = startFad,
                    endFad = totalFrames - 1,
                    chdFrameStart = chdOffset,
                    userOffset = userDataOffset(type),
                    isData = type != "AUDIO",
                )
                chdOffset += ((frames + CD_TRACK_PADDING - 1) / CD_TRACK_PADDING) * CD_TRACK_PADDING
                index++
            }
            return tracks
        }

        // Byte offset of the 2048-byte user data within a stored frame, by track type: cooked Mode 1
        // is user-at-0; raw Mode 1 skips the 16-byte sync+header; Mode 2 Form 1 skips 24; XA/2336 skips 8.
        private fun userDataOffset(type: String): Int = when {
            type.startsWith("MODE1") && !type.contains("RAW") && !type.contains("2352") -> 0
            type.startsWith("MODE1") -> 16
            type.contains("2336") -> 8
            type.startsWith("MODE2") || type.startsWith("CDI") -> 24
            else -> 16
        }
    }
}
