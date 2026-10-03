package com.playfieldportal.studio.io

import java.io.File
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent

/**
 * Studio-side audition of a theme sound. Plays WAV only, through the JVM's own `javax.sound.sampled`
 * (plan A10): the JVM has no MP3/OGG/AAC decoder and a new dependency needs approval, so the other
 * formats are played on the device. One clip at a time; starting another stops the first.
 */
object WavPlayer {
    private var clip: Clip? = null

    /**
     * Starts [file]; [onStopped] runs when it ends or is stopped. Returns false (and plays nothing)
     * when the system has no audio line or the file is not PCM it can read.
     */
    @Synchronized
    fun play(file: File, loop: Boolean, onStopped: () -> Unit): Boolean {
        stop()
        return try {
            val next = AudioSystem.getAudioInputStream(file).use { stream ->
                AudioSystem.getClip().also { it.open(stream) }
            }
            next.addLineListener { event ->
                if (event.type == LineEvent.Type.STOP) {
                    synchronized(this) {
                        if (clip === next) clip = null
                    }
                    next.close()
                    onStopped()
                }
            }
            clip = next
            if (loop) next.loop(Clip.LOOP_CONTINUOUSLY) else next.start()
            true
        } catch (e: Exception) {
            // No mixer (headless), an unsupported encoding, or an unreadable file: nothing plays.
            false
        }
    }

    @Synchronized
    fun stop() {
        clip?.stop()
    }
}
