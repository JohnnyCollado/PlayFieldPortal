package com.playfieldportal.studio

import com.playfieldportal.studio.io.AndroidVideo
import com.playfieldportal.studio.io.MediaGates
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Clips the Studio takes in are made playable on Android: anything every device is not guaranteed
 * to decode (H.264 High 4:4:4 / 4:2:2 / 10-bit, non-4:2:0 or 10-bit pixels, a codec other than
 * H.264) is re-encoded to H.264 4:2:0 with the bundled FFmpeg on import; an ordinary H.264 clip is
 * left exactly as it is.
 */
class AndroidVideoTest {

    private val dir = createTempDirectory("studio-android-video").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** An MPEG-4 Part 2 clip (a codec Android devices need not decode), made by the bundled ffmpeg. */
    private fun mpeg4Clip(name: String = "clip.mp4", seconds: Int = 2): File {
        val out = File(dir, name)
        val process = ProcessBuilder(
            AndroidVideo.ffmpegPath(), "-y", "-v", "error",
            "-f", "lavfi", "-i", "testsrc=size=320x240:rate=30",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
            "-t", "$seconds", "-c:v", "mpeg4", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", out.absolutePath,
        ).redirectErrorStream(true).start()
        val log = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), log)
        return out
    }

    // ── What Android can play ────────────────────────────────────────────────

    @Test
    fun `ordinary H264 in 4-2-0 plays as it is`() {
        for (profile in listOf(66, 578, 77, 100)) {
            assertNull(AndroidVideo.reasonToConvert(AndroidVideo.Format("h264", profile, "yuv420p")), "profile $profile")
        }
        assertNull(AndroidVideo.reasonToConvert(AndroidVideo.Format("h264", 100, "yuvj420p")))
    }

    @Test
    fun `high 4-4-4, 4-2-2 and 10-bit H264 are converted, naming why`() {
        val r444 = assertNotNull(AndroidVideo.reasonToConvert(AndroidVideo.Format("h264", 244, "yuv444p")))
        assertTrue("4:4:4" in r444, r444)
        assertNotNull(AndroidVideo.reasonToConvert(AndroidVideo.Format("h264", 122, "yuv422p")))
        assertNotNull(AndroidVideo.reasonToConvert(AndroidVideo.Format("h264", 110, "yuv420p10le")))
        // A 4:2:0 profile tag over pixels that are not 4:2:0 is still not playable.
        assertNotNull(AndroidVideo.reasonToConvert(AndroidVideo.Format("h264", 100, "yuv444p")))
    }

    @Test
    fun `other codecs are converted`() {
        assertNotNull(AndroidVideo.reasonToConvert(AndroidVideo.Format("mpeg4", null, "yuv420p")))
        assertNotNull(AndroidVideo.reasonToConvert(AndroidVideo.Format("hevc", 1, "yuv420p")))
    }

    // ── Probe and convert ────────────────────────────────────────────────────

    @Test
    fun `an ordinary H264 clip is left untouched`() {
        val clip = File(dir, "h264.mp4").also { MotionTestMedia.writeTestMp4(it, frames = 10) }
        val format = assertNotNull(AndroidVideo.probe(clip))
        assertEquals("h264", format.codec)
        assertIs<AndroidVideo.Playable.AsIs>(AndroidVideo.playable(clip, dir))
    }

    @Test
    fun `an unplayable clip is re-encoded to H264 4-2-0 of the same length`() {
        val clip = mpeg4Clip()
        assertEquals("mpeg4", AndroidVideo.probe(clip)?.codec)
        val converted = assertIs<AndroidVideo.Playable.Converted>(AndroidVideo.playable(clip, dir))
        assertNotEquals(clip, converted.file)
        val format = assertNotNull(AndroidVideo.probe(converted.file))
        assertEquals("h264", format.codec)
        assertEquals("yuv420p", format.pixelFormat)
        assertNull(AndroidVideo.reasonToConvert(format))
        assertTrue("MPEG-4" in converted.reason || "mpeg4" in converted.reason, converted.reason)
        val src = AndroidVideo.probe(clip)!!
        assertTrue(kotlin.math.abs(format.durationMs - src.durationMs) < 200, "${format.durationMs} vs ${src.durationMs}")
        assertTrue(clip.isFile, "the pick itself is never changed")
    }

    // ── Wired into the imports ───────────────────────────────────────────────

    @Test
    fun `the GameBoot gate stages the playable conversion`() {
        val clip = mpeg4Clip()
        val accepted = assertIs<MediaGates.Outcome.Accepted>(MediaGates.check("gameboot_video", clip, dir))
        assertNotEquals(clip, accepted.source)
        assertEquals("h264", AndroidVideo.probe(accepted.source)?.codec)
        assertNotNull(accepted.note)
    }

    @Test
    fun `importing an unplayable GameBoot clip says it was converted`() = runBlocking {
        val vm = StudioViewModel(CoroutineScope(Dispatchers.Default))
        vm.importGameBoot(mpeg4Clip())
        withTimeout(60_000) {
            delay(50)
            while (vm.state.value.busy) delay(25)
        }
        val staged = assertNotNull(vm.state.value.mediaFiles["gameboot_video"])
        assertEquals("h264", AndroidVideo.probe(staged)?.codec)
        val status = vm.state.value.statusMessage.orEmpty()
        assertTrue("onverted" in status, status)
    }
}
