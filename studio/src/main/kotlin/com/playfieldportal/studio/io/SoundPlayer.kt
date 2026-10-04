package com.playfieldportal.studio.io

import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread

/**
 * Studio-side audition of a theme sound in any format a theme takes (MP3, OGG, M4A, WAV). FFmpeg
 * decodes ([FfmpegAudioReader]) and the PCM streams to a `javax.sound.sampled` line, so a ten-minute
 * ambience never sits in memory whole. One sound at a time; starting another stops the first.
 */
object SoundPlayer {
    private var session: Session? = null

    /**
     * Starts [file]; [onStopped] runs when it ends or is stopped. Returns false (and plays nothing)
     * when the system has no audio line or FFmpeg cannot decode the file.
     */
    @Synchronized
    fun play(file: File, loop: Boolean, onStopped: () -> Unit): Boolean {
        stop()
        val reader = FfmpegAudioReader.open(file) ?: return false
        val format = AudioFormat(reader.sampleRate.toFloat(), 16, reader.channels, true, false)
        val line = try {
            AudioSystem.getSourceDataLine(format).apply {
                open(format)
                start()
            }
        } catch (e: Exception) {
            // No mixer (headless) or no line for this rate and channel count.
            reader.close()
            return false
        }
        val next = Session(reader, line, loop, onStopped)
        session = next
        thread(name = "studio-sound", isDaemon = true) { next.run() }
        return true
    }

    @Synchronized
    fun stop() {
        session?.cancel()
        session = null
    }

    private class Session(
        private val reader: FfmpegAudioReader,
        private val line: SourceDataLine,
        private val loop: Boolean,
        private val onStopped: () -> Unit,
    ) {
        @Volatile private var cancelled = false

        fun run() {
            try {
                var pass = 0L
                while (!cancelled) {
                    val chunk = reader.read()
                    if (chunk == null) {
                        // A clip that yields no audio at all would spin on rewinds forever.
                        if (!loop || pass == 0L) break
                        reader.rewind()
                        pass = 0L
                        continue
                    }
                    pass += chunk.size
                    line.write(chunk, 0, chunk.size)
                }
                if (!cancelled) line.drain()
            } catch (e: Exception) {
                // A decode error mid-clip ends the audition like reaching the end would.
            } finally {
                line.stop()
                line.close()
                reader.close()
                onStopped()
            }
        }

        /** Unblocks a pending write: a stopped and flushed line returns from it at once. */
        fun cancel() {
            cancelled = true
            line.stop()
            line.flush()
        }
    }
}
