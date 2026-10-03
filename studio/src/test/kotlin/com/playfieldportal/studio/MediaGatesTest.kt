package com.playfieldportal.studio

import com.playfieldportal.studio.io.MediaGates
import com.playfieldportal.themekit.ThemeMediaSlots
import com.playfieldportal.themekit.UiMediaLimits
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MediaGatesTest {

    private val dir: File = createTempDirectory("studio-media-gates").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun rejected(slot: String, file: File): String =
        assertIs<MediaGates.Outcome.Rejected>(MediaGates.check(slot, file)).message

    private fun accepted(slot: String, file: File): MediaGates.Outcome.Accepted =
        assertIs<MediaGates.Outcome.Accepted>(MediaGates.check(slot, file))

    private fun wav(name: String, ms: Long, padTo: Long = 0L) =
        File(dir, name).also { MediaTestFiles.writeWav(it, ms, padTo) }

    // ── Menu sounds: per-slot duration caps ──────────────────────────────────

    @Test
    fun `each sound slot accepts at its cap and names the cap just past it`() {
        val caps = mapOf(
            "sound_scroll" to UiMediaLimits.NAVIGATION,
            "sound_back" to UiMediaLimits.BACK,
            "sound_confirm" to UiMediaLimits.CONFIRM,
            "sound_error" to UiMediaLimits.ERROR,
            "sound_notification" to UiMediaLimits.NOTIFICATION,
        )
        for ((key, spec) in caps) {
            val ok = accepted(key, wav("$key-ok.wav", spec.hardMaxMs))
            assertEquals("wav", ok.extension)
            assertEquals(UiMediaLimits.tooLong(spec), rejected(key, wav("$key-long.wav", spec.hardMaxMs + 1)))
        }
    }

    @Test
    fun `scroll sound over 0_5 s is rejected with the by-name message`() {
        assertEquals(
            "That clip is too long — 0.5 s or less",
            rejected("sound_scroll", wav("long.wav", 600)),
        )
    }

    @Test
    fun `m4a sound is timed from its mvhd`() {
        val f = File(dir, "tap.m4a").also { MediaTestFiles.writeM4a(it, 400) }
        assertEquals("m4a", accepted("sound_scroll", f).extension)
        val long = File(dir, "tap-long.m4a").also { MediaTestFiles.writeM4a(it, 900) }
        assertEquals(UiMediaLimits.tooLong(UiMediaLimits.NAVIGATION), rejected("sound_scroll", long))
    }

    @Test
    fun `unreadable length is rejected with the no-duration message`() {
        val junk = File(dir, "junk.mp3").also { it.writeBytes(ByteArray(2048) { 7 }) }
        assertEquals(UiMediaLimits.MSG_NO_DURATION, rejected("sound_scroll", junk))
    }

    @Test
    fun `non-audio extensions are rejected as unsupported audio`() {
        for (name in listOf("a.txt", "a.flac", "a.png", "a.mp4", "a.webm", "a")) {
            val f = File(dir, name).also { it.writeBytes(ByteArray(64)) }
            assertEquals(UiMediaLimits.MSG_UNSUPPORTED_FORMAT_AUDIO, rejected("sound_confirm", f), name)
        }
    }

    @Test
    fun `sound over the 8 MB theme cap is rejected before probing`() {
        val f = wav("big.wav", 100, padTo = UiMediaLimits.THEME_SOUND_MAX_BYTES + 1)
        assertEquals(MediaGates.tooLargeForTheme(ThemeMediaSlots.slot("sound_scroll")!!), rejected("sound_scroll", f))
        assertEquals("That file is too large for a theme — 8 MB or less", rejected("sound_scroll", f))
        accepted("sound_scroll", wav("edge.wav", 100, padTo = UiMediaLimits.THEME_SOUND_MAX_BYTES))
    }

    // ── Ambience ─────────────────────────────────────────────────────────────

    @Test
    fun `ambience accepts ten minutes and rejects past it`() {
        assertEquals("wav", accepted("ambience_audio", wav("amb.wav", UiMediaLimits.AMBIENCE_MAX_MS)).extension)
        assertEquals(
            UiMediaLimits.tooLong(UiMediaLimits.AMBIENCE),
            rejected("ambience_audio", wav("amb-long.wav", UiMediaLimits.AMBIENCE_MAX_MS + 1)),
        )
        assertEquals("That clip is too long — 600 s or less", rejected("ambience_audio", wav("a2.wav", 700_000)))
    }

    @Test
    fun `ambience over the 32 MB theme cap is rejected`() {
        val f = wav("amb-big.wav", 60_000, padTo = UiMediaLimits.THEME_AMBIENCE_MAX_BYTES + 1)
        assertEquals("That file is too large for a theme — 32 MB or less", rejected("ambience_audio", f))
        accepted("ambience_audio", wav("amb-edge.wav", 60_000, padTo = UiMediaLimits.THEME_AMBIENCE_MAX_BYTES))
    }

    // ── Boot / GameBoot ──────────────────────────────────────────────────────

    private fun mp4(name: String, frames: Int = 6) =
        File(dir, name).also { MotionTestMedia.writeTestMp4(it, width = 64, height = 48, frames = frames) }

    @Test
    fun `boot and gameboot accept an MP4 within the cap`() {
        val clip = mp4("clip.mp4")
        assertEquals("mp4", accepted("boot_video", clip).extension)
        assertEquals("mp4", accepted("gameboot_video", clip).extension)
    }

    @Test
    fun `m4v is stored as mp4`() {
        val clip = File(dir, "clip.m4v").also { mp4("src.mp4").copyTo(it) }
        assertEquals("mp4", accepted("boot_video", clip).extension)
    }

    @Test
    fun `webm boot is rejected with the A10 reason`() {
        val webm = File(dir, "boot.webm").also { it.writeBytes(ByteArray(128)) }
        assertEquals(MediaGates.MSG_WEBM_NOT_AUTHORABLE, rejected("boot_video", webm))
        assertEquals(MediaGates.MSG_WEBM_NOT_AUTHORABLE, rejected("gameboot_video", webm))
        assertTrue(MediaGates.MSG_WEBM_NOT_AUTHORABLE.contains("MP4"))
    }

    @Test
    fun `video over ten seconds is rejected naming the cap`() {
        val long = mp4("long.mp4", frames = 110) // 10 fps -> 11 s
        assertEquals(UiMediaLimits.tooLong(UiMediaLimits.BOOT_CLIP), rejected("boot_video", long))
        assertEquals(UiMediaLimits.tooLong(UiMediaLimits.GAMEBOOT_CLIP), rejected("gameboot_video", long))
        assertEquals("That clip is too long — 10 s or less", rejected("boot_video", long))
    }

    @Test
    fun `video over 25 MB is rejected`() {
        val f = mp4("fat.mp4")
        java.io.RandomAccessFile(f, "rw").use { it.setLength(UiMediaLimits.VIDEO_MAX_BYTES + 1) }
        assertEquals(UiMediaLimits.MSG_TOO_LARGE_BYTES_VIDEO, rejected("boot_video", f))
    }

    @Test
    fun `garbage mp4 is undecodable and audio in a video slot is unsupported`() {
        val bad = File(dir, "bad.mp4").also { it.writeBytes(ByteArray(4096) { 3 }) }
        assertEquals(UiMediaLimits.MSG_UNDECODABLE, rejected("boot_video", bad))
        val audio = File(dir, "a.mp3").also { it.writeBytes(ByteArray(64)) }
        assertEquals(UiMediaLimits.MSG_UNSUPPORTED_FORMAT_VIDEO, rejected("gameboot_video", audio))
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    @Test
    fun `unknown slot and missing file are rejected`() {
        assertEquals(MediaGates.MSG_UNKNOWN_SLOT, rejected("sound_launch", wav("x.wav", 100)))
        assertEquals(UiMediaLimits.MSG_UNDECODABLE, rejected("sound_scroll", File(dir, "nope.wav")))
    }

    /** Studio gate ≡ launcher gate: the format verdict for every slot x extension is [UiMediaLimits.validate]'s. */
    @Test
    fun `format verdict matches the launcher gate for every slot and extension`() {
        for (slot in ThemeMediaSlots.ALL) {
            for (ext in UiMediaLimits.knownUiMediaExtensions) {
                val f = File(dir, "${slot.key}-probe.$ext").also { it.writeBytes(ByteArray(0)) }
                val launcher = UiMediaLimits.validate(
                    slot.spec,
                    UiMediaLimits.Probe(UiMediaLimits.mimeForExtension(ext), 1L, 1L),
                )
                val studio = (MediaGates.check(slot.key, f) as? MediaGates.Outcome.Rejected)?.message
                val isFormatRejection = launcher == UiMediaLimits.MSG_UNSUPPORTED_FORMAT_AUDIO ||
                    launcher == UiMediaLimits.MSG_UNSUPPORTED_FORMAT_VIDEO
                when {
                    ext == "webm" && slot.kind == UiMediaLimits.Kind.VIDEO ->
                        assertEquals(MediaGates.MSG_WEBM_NOT_AUTHORABLE, studio, "${slot.key} .$ext")
                    isFormatRejection -> assertEquals(launcher, studio, "${slot.key} .$ext")
                    // An empty file fails later (length/duration), never as a format error.
                    else -> assertTrue(
                        studio != UiMediaLimits.MSG_UNSUPPORTED_FORMAT_AUDIO &&
                            studio != UiMediaLimits.MSG_UNSUPPORTED_FORMAT_VIDEO,
                        "${slot.key} .$ext wrongly format-rejected",
                    )
                }
            }
        }
    }
}
