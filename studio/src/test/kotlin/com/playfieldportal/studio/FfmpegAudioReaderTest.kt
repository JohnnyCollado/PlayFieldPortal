package com.playfieldportal.studio

import com.playfieldportal.studio.io.FfmpegAudioReader
import com.playfieldportal.themekit.WavFixtures
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.javacv.FFmpegFrameRecorder

class FfmpegAudioReaderTest {
    private val dir = File(System.getProperty("java.io.tmpdir"), "pfp-audio-reader-${System.nanoTime()}").apply { mkdirs() }

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** Interleaved 16-bit stereo: a 440 Hz tone on the left, its inverse on the right. */
    private fun tone(frames: Int, rate: Int): ShortArray = ShortArray(frames * 2) { i ->
        val v = (sin(2 * PI * 440 * (i / 2) / rate) * 12_000).toInt()
        (if (i % 2 == 0) v else -v).toShort()
    }

    private fun leBytes(samples: ShortArray): ByteArray =
        ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { asShortBuffer().put(samples) }.array()

    private fun FfmpegAudioReader.readAll(): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) out.write(read() ?: break)
        return out.toByteArray()
    }

    private fun m4a(name: String, frames: Int, rate: Int): File {
        val file = File(dir, name)
        FFmpegFrameRecorder(file, 2).apply {
            format = "mp4"
            audioCodec = avcodec.AV_CODEC_ID_AAC
            sampleRate = rate
            audioBitrate = 128_000
            start()
            recordSamples(rate, 2, ShortBuffer.wrap(tone(frames, rate)))
            stop()
            release()
        }
        return file
    }

    @Test
    fun `a 16-bit wav decodes to its own samples`() {
        val pcm = leBytes(tone(4410, 44_100))
        val file = File(dir, "tone.wav").also { WavFixtures.writeSampleWav(it, 1, 16, 2, 44_100, pcm) }
        FfmpegAudioReader.open(file).let { reader ->
            assertNotNull(reader)
            reader.use {
                assertEquals(44_100, it.sampleRate)
                assertEquals(2, it.channels)
                assertContentEquals(pcm, it.readAll())
            }
        }
    }

    @Test
    fun `a float wav comes out as 16-bit pcm`() {
        val file = File(dir, "float.wav")
        WavFixtures.writeSampleWav(file, 3, 32, 1, 22_050, WavFixtures.floats32(0f, 0.5f, -0.5f, 1f))
        val reader = assertNotNull(FfmpegAudioReader.open(file))
        reader.use {
            assertEquals(22_050, it.sampleRate)
            assertEquals(1, it.channels)
            val samples = ByteBuffer.wrap(it.readAll()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val got = ShortArray(samples.remaining()).also(samples::get)
            assertEquals(4, got.size)
            assertEquals(0, got[0].toInt())
            assertTrue(abs(got[1] - 16_384) <= 1, "0.5 -> ${got[1]}")
            assertTrue(abs(got[2] + 16_384) <= 1, "-0.5 -> ${got[2]}")
            assertEquals(Short.MAX_VALUE, got[3])
        }
    }

    @Test
    fun `an aac track in m4a decodes to audible pcm of about its length`() {
        val rate = 44_100
        val file = m4a("amb.m4a", rate, rate)
        val reader = assertNotNull(FfmpegAudioReader.open(file))
        reader.use {
            assertEquals(rate, it.sampleRate)
            assertEquals(2, it.channels)
            val bytes = it.readAll()
            val frames = bytes.size / 4
            assertTrue(abs(frames - rate) < rate / 10, "decoded $frames frames for a 1 s clip")
            val samples = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            var peak = 0
            while (samples.hasRemaining()) peak = maxOf(peak, abs(samples.get().toInt()))
            assertTrue(peak > 6_000, "peak $peak")
        }
    }

    @Test
    fun `rewinding plays the clip again from the start`() {
        val pcm = leBytes(tone(2205, 44_100))
        val file = File(dir, "loop.wav").also { WavFixtures.writeSampleWav(it, 1, 16, 2, 44_100, pcm) }
        val reader = assertNotNull(FfmpegAudioReader.open(file))
        reader.use {
            val first = it.readAll()
            it.rewind()
            assertContentEquals(first, it.readAll())
        }
    }

    @Test
    fun `a file that is not audio does not open`() {
        val junk = File(dir, "junk.mp3").apply { writeText("not audio at all") }
        assertNull(FfmpegAudioReader.open(junk))
        assertNull(FfmpegAudioReader.open(File(dir, "missing.ogg")))
    }
}
