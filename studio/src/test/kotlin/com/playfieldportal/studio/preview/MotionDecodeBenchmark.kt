package com.playfieldportal.studio.preview

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import org.junit.Assume.assumeTrue

/**
 * Diagnostic, not a regression test: how fast FFmpeg decodes and frames a real clip on this machine,
 * and what turning each frame into a bitmap costs. Skipped unless PFP_CLIP names a video. Prints one
 * report line per pass; the warm pass is what steady playback gets.
 */
class MotionDecodeBenchmark {

    @Test
    fun `decode speed of PFP_CLIP`() {
        val path = System.getenv("PFP_CLIP")
        assumeTrue("set PFP_CLIP to a video to run this", !path.isNullOrBlank())
        val file = File(path!!)
        val w = MotionPlayer.OUT_WIDTH
        val h = MotionPlayer.OUT_HEIGHT
        assertNotNull(FfmpegFrameReader.open(file, crop = null, outW = w, outH = h), "FFmpeg cannot open $file").use { reader ->
            println("PFP_CLIP ${file.name}: ${reader.sourceWidth}x${reader.sourceHeight} @ ${"%.2f".format(reader.fps)} fps")
            val frame = BgraFrame(w, h)
            val bitmaps = FrameBitmaps(w, h)
            for (pass in listOf("cold", "warm")) {
                val decode = mutableListOf<Double>()
                val fill = mutableListOf<Double>()
                while (decode.size < MAX_FRAMES) {
                    var t = System.nanoTime()
                    if (!reader.next(frame)) break
                    decode += (System.nanoTime() - t) / 1e6
                    t = System.nanoTime()
                    bitmaps.fill(frame.bytes)
                    fill += (System.nanoTime() - t) / 1e6
                }
                val dec = decode.average()
                val fil = fill.average()
                // Read-ahead overlaps the two, so steady playback is bounded by the slower one.
                val ceiling = 1000.0 / maxOf(dec, fil)
                println(
                    "PFP_CLIP $pass: ${decode.size} frames, decode+frame avg %.1f ms (p95 %.1f), bitmap avg %.2f ms -> ceiling %.1f fps vs clip %.1f fps"
                        .format(dec, decode.p95(), fil, ceiling, reader.fps),
                )
                reader.rewind()
            }
        }
    }

    private fun List<Double>.p95(): Double = sorted()[((size - 1) * 0.95).toInt()]

    private companion object {
        const val MAX_FRAMES = 300
    }
}
