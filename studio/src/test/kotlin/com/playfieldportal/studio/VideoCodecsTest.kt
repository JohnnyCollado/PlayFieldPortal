package com.playfieldportal.studio

import com.playfieldportal.studio.io.VideoCodecs
import com.playfieldportal.themekit.MotionLimits
import java.io.File
import java.io.RandomAccessFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins [VideoCodecs.accept]'s ladder: cheapest check first, and every rejection names its
 * reason with a [MotionLimits] string. The accept path runs against a REAL JCodec-encoded
 * MP4 — hand-built bytes can only test the rejections, because the last check is a genuine
 * header parse and frame decode.
 */
class VideoCodecsTest {

    private fun tempDir(): File = createTempDirectory("studio-video-codecs").toFile()

    @Test
    fun `wrong extension is rejected with the unsupported message before any decode`() {
        val dir = tempDir()
        try {
            // Even genuinely valid MP4 bytes under a .webm name are rejected — the Studio
            // cannot produce the poster JCodec's demuxer can't read.
            val webm = File(dir, "clip.webm")
            MotionTestMedia.writeTestMp4(webm)
            val outcome = VideoCodecs.accept(webm)
            assertEquals(VideoCodecs.Outcome.Rejected(VideoCodecs.MSG_UNSUPPORTED_SOURCE), outcome)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `bytes that merely claim to be mp4 are rejected as undecodable`() {
        val dir = tempDir()
        try {
            val fake = File(dir, "fake.mp4")
            fake.writeBytes(ByteArray(2048) { 'A'.code.toByte() })
            val outcome = VideoCodecs.accept(fake)
            assertEquals(VideoCodecs.Outcome.Rejected(MotionLimits.MSG_UNDECODABLE), outcome)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a missing file is rejected as undecodable`() {
        val outcome = VideoCodecs.accept(File("no-such-clip.mp4"))
        assertEquals(VideoCodecs.Outcome.Rejected(MotionLimits.MSG_UNDECODABLE), outcome)
    }

    @Test
    fun `a file over the size cap is rejected by the size check, not the decoder`() {
        val dir = tempDir()
        try {
            // Sparse length, no data: proves the length check runs BEFORE the header is
            // opened — the bytes inside are junk, so an UNDECODABLE answer would mean the
            // ladder had reordered itself into doing work it must not.
            val big = File(dir, "big.mp4")
            RandomAccessFile(big, "rw").use { it.setLength(MotionLimits.MAX_BYTES + 1) }
            val outcome = VideoCodecs.accept(big)
            assertEquals(VideoCodecs.Outcome.Rejected(MotionLimits.MSG_TOO_LARGE_BYTES), outcome)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a real mp4 is accepted with a poster, a probe, and the mp4 bundle extension`() {
        val dir = tempDir()
        try {
            val clip = File(dir, "clip.mp4")
            MotionTestMedia.writeTestMp4(clip)

            val outcome = VideoCodecs.accept(clip)
            val accepted = assertIs<VideoCodecs.Outcome.Accepted>(outcome)
            assertEquals("mp4", accepted.bundleExtension)
            assertEquals(320, accepted.poster.width)
            assertEquals(240, accepted.poster.height)
            assertEquals("video/mp4", accepted.probe.mime)
            assertTrue(accepted.probe.durationMs > 0, "probe must read a duration")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `probe returns null rather than throwing on a non-video file`() {
        val dir = tempDir()
        try {
            val text = File(dir, "notes.mp4")
            text.writeText("this was renamed from a txt")
            assertEquals(null, VideoCodecs.probe(text))
        } finally {
            dir.deleteRecursively()
        }
    }
}
