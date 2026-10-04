package com.playfieldportal.core.data.repository

import com.playfieldportal.core.domain.model.UiMediaSlot
import com.playfieldportal.themekit.ThemeMediaSlots
import com.playfieldportal.themekit.ThemeMotion
import com.playfieldportal.themekit.UiMediaLimits
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test

/**
 * One gate for every UI-media file, whoever supplies it: a user's own pick and a theme's entry are
 * normalized, probed, held to the slot's limits and — for boot / GameBoot clips — asked of the
 * device's decoders the same way, so the same file gets the same verdict and the same words.
 */
class MediaGateTest {

    private val dir: File = Files.createTempDirectory("media-gate").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private val undecodable: VideoDecodeCheck = { "This device can't play this video (H.264 High 4:4:4)" }

    private fun video(ms: Long): MediaProbe = { _, _ -> MediaFacts("video/mp4", 1920, 1080, ms) }

    private fun staged(name: String): File = File(dir, name).apply { writeBytes(ByteArray(64) { it.toByte() }) }

    @Test
    fun `a playable clip inside its limits passes`() {
        val gate = MediaGate(probe = video(5_000), decodeCheck = { null })
        assertNull(gate.check(staged("boot.mp4"), "mp4", UiMediaSlot.BOOT_VIDEO.limits))
    }

    @Test
    fun `a boot clip this device cannot decode is refused on the user path`() {
        val gate = MediaGate(probe = video(5_000), decodeCheck = undecodable)
        val reason = assertNotNull(gate.check(staged("boot.mp4"), "mp4", UiMediaSlot.BOOT_VIDEO.limits))
        assertTrue(reason.contains("High 4:4:4"), reason)
    }

    @Test
    fun `sounds are never asked of the video decoders`() {
        val audio: MediaProbe = { _, _ -> MediaFacts("audio/mpeg", 0, 0, 300) }
        val gate = MediaGate(probe = audio, decodeCheck = undecodable)
        assertNull(gate.check(staged("scroll.mp3"), "mp3", UiMediaSlot.SOUND_SCROLL.limits))
    }

    @Test
    fun `a file nothing can read is undecodable`() {
        val gate = MediaGate(probe = { _, _ -> null }, decodeCheck = { null })
        assertEquals(UiMediaLimits.MSG_UNDECODABLE, gate.check(staged("boot.mp4"), "mp4", UiMediaSlot.BOOT_VIDEO.limits))
    }

    @Test
    fun `a user pick and a theme entry get the same verdict and the same words`() {
        val gate = MediaGate(probe = video(UiMediaSlot.BOOT_VIDEO.limits.hardMaxMs + 5_000), decodeCheck = { null })
        val userVerdict = gate.check(staged("pick.mp4"), "mp4", UiMediaSlot.BOOT_VIDEO.limits)

        val report = ThemeMediaInstaller(gate).installMedia(
            mapOf("boot_video" to ThemeMotion.ofBytes(ByteArray(64) { it.toByte() }, "mp4")),
            File(dir, "theme-media"),
        )

        assertNotNull(userVerdict)
        assertEquals(mapOf("boot_video" to userVerdict), report.dropped)
    }

    @Test
    fun `the theme path refuses an undecodable clip through the same gate`() {
        val gate = MediaGate(probe = video(5_000), decodeCheck = undecodable)
        val report = ThemeMediaInstaller(gate).installMedia(
            mapOf("gameboot_video" to ThemeMotion.ofBytes(ByteArray(64) { it.toByte() }, "mp4")),
            File(dir, "theme-media"),
        )
        assertTrue(report.installed.isEmpty())
        assertTrue(report.dropped.getValue("gameboot_video").contains("High 4:4:4"))
        assertTrue(ThemeMediaSlots.slot("gameboot_video") != null)
    }
}
