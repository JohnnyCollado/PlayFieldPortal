package com.playfieldportal.core.data.repository

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.domain.model.UiMediaKind
import com.playfieldportal.core.domain.model.UiMediaSlot
import com.playfieldportal.themekit.UiMediaLimits
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behaviour of the user's per-slot UI-media storage: `filesDir/ui-media/<slotKey>.<ext>` is the
 * source of truth, staged import commits only on a passing gate, and a failing import leaves the
 * previous assignment intact (the design doc's "failed replacement rule").
 *
 * MediaMetadataRetriever is mocked (Robolectric's real one can't decode the synthetic bytes the
 * tests register), so each case pins exactly the probe values it wants the gate to see. A null
 * duration read no longer means rejection by itself: the gate falls back to
 * [MediaDurationFallback]'s container-header math, so the tests that exercise that path register
 * real WAV/MP3 bytes and assert the computed length is what the gate sees.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class UiMediaStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private lateinit var store: UiMediaStore

    @Before
    fun setUp() {
        // The prefs DataStore and the media dir persist within the test JVM; wipe both so each
        // case starts empty.
        runBlocking { context.pfpDataStore.edit { it.clear() } }
        File(context.filesDir, UiMediaStore.UI_MEDIA_DIR).deleteRecursively()
        MediaDisplayNames.clearCache()
        mockkStatic(MediaMetadataRetriever::class)
        // Robolectric's ShadowContentResolver.getType returns null for URIs with no registered
        // provider, and its query() consults no cursor — the store's import path would reject
        // every pick as "Unsupported format" and every display name would fall back to
        // "Custom sound". Stub both to mimic a real SAF provider: MIME derived from the pick's
        // extension, and a DISPLAY_NAME cursor for content://test picks (the fallback case uses
        // a different authority precisely to see the null-query path).
        mockkObject(context.contentResolver)
        every { context.contentResolver.getType(any()) } answers {
            when (firstArg<Uri>().lastPathSegment?.substringAfterLast('.', missingDelimiterValue = "")) {
                "wav" -> "audio/wav"
                "mp3" -> "audio/mpeg"
                "ogg" -> "audio/ogg"
                "m4a" -> "audio/mp4"
                "mp4" -> "video/mp4"
                else -> null
            }
        }
        every { context.contentResolver.query(any(), any(), any(), any(), any()) } answers {
            val uri = firstArg<Uri>()
            if (uri.host != "test") {
                null
            } else {
                android.database.MatrixCursor(arrayOf(android.provider.OpenableColumns.DISPLAY_NAME))
                    .apply { addRow(arrayOf(uri.lastPathSegment)) }
            }
        }
        store = UiMediaStore(context)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    /** Makes every MediaMetadataRetriever created in this test report [durationMs] and [mime]. */
    private fun probeReturns(durationMs: Long?, mime: String = "audio/mpeg") {
        val retriever = mockk<MediaMetadataRetriever>(relaxed = true)
        every { retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) }
            .returns(durationMs?.toString())
        every { retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) }
            .returns(mime)
        every { retriever.setDataSource(any<String>()) } returns Unit
        every { retriever.release() } returns Unit
        mockkConstructor(MediaMetadataRetriever::class)
        every { anyConstructed<MediaMetadataRetriever>().setDataSource(any<String>()) } returns Unit
        every { anyConstructed<MediaMetadataRetriever>().extractMetadata(any()) } answers { retriever.extractMetadata(firstArg()) }
        every { anyConstructed<MediaMetadataRetriever>().release() } returns Unit
    }

    // ── import ────────────────────────────────────────────────────────────────

    @Test
    fun `import writes a slot-keyed file and bumps the stamp`() = runTest {
        probeReturns(100L)
        val result = store.import(UiMediaSlot.SOUND_SCROLL, register(wavBytes()))

        assertTrue(result.ok, result.message ?: "import rejected")
        assertTrue(wavFile(UiMediaSlot.SOUND_SCROLL).isFile, "stored as <slotKey>.<ext>")
        assertNotNull(stampPref(), "import bumps the stamp so observers reload")
    }

    @Test
    fun `a failing import leaves the previous assignment intact`() = runTest {
        probeReturns(100L)
        assertTrue(store.import(UiMediaSlot.SOUND_SCROLL, register(wavBytes())).ok)

        // Second pick is over the 0.5 s Navigation cap: it must be refused AND the first file
        // must still be on disk, still resolvable through pathFor.
        probeReturns(900L)
        val result = store.import(UiMediaSlot.SOUND_SCROLL, register(wavBytes()))

        assertFalse(result.ok, "a 0.9 s Navigation sound must be refused")
        assertTrue(wavFile(UiMediaSlot.SOUND_SCROLL).isFile, "the working assignment must survive a rejected pick")
        assertNotNull(store.pathFor(UiMediaSlot.SOUND_SCROLL))
    }

    @Test
    fun `an unreadable duration is rejected and leaves nothing behind`() = runTest {
        probeReturns(null)
        // Bytes with no container headers at all: neither the extractor nor the fallback can
        // time it, so the gate must refuse it.
        val garbage = ByteArray(256) { it.toByte() }
        val result = store.import(UiMediaSlot.SOUND_BACK, register(garbage, name = "junk.mp3"))

        assertFalse(result.ok, "unreadable duration must be a rejection, not a pass")
        assertTrue(mediaDir().listFiles().isNullOrEmpty(), "rejected picks must not leave files behind")
        assertNull(stampPref(), "no stamp bump without a successful import")
    }

    @Test
    fun `a null MMR duration falls back to the WAV header and passes`() = runTest {
        // Some devices return no METADATA_KEY_DURATION for WAVs; the RIFF header still times it.
        probeReturns(null, mime = "audio/wav")
        val result = store.import(UiMediaSlot.SOUND_BACK, register(wavBytes()))

        assertTrue(result.ok, result.message ?: "WAV with a readable header must pass")
        assertTrue(wavFile(UiMediaSlot.SOUND_BACK).isFile)
    }

    @Test
    fun `a null MMR duration falls back to the Xing table and passes for a tiny VBR clip`() = runTest {
        // The on-device failure this fixes: a 3-frame ffmpeg-native VBR mp3 whose duration this
        // device's extractor returns as null — the 78 ms clip must still pass the 0.5 s
        // Navigation cap via the Xing frame count.
        probeReturns(null)
        val cursor = xingVbrMp3(frames = 3)
        val result = store.import(UiMediaSlot.SOUND_SCROLL, register(cursor, name = "snd_cursor.mp3"))

        assertTrue(result.ok, result.message ?: "Xing-timed clip must pass")
        assertTrue(File(mediaDir(), "${UiMediaSlot.SOUND_SCROLL.key}.mp3").isFile)
    }

    @Test
    fun `oversized pick is rejected off the descriptor without transferring a byte`() = runTest {
        probeReturns(100L)
        // This case used to push 3 MB against a 2 MB sound cap. That cap is gone: the AUDIO
        // policy (owner decision 2026-09-08, UiMediaLimits KDoc) is no user-facing byte limit,
        // and every audio spec's maxBytes is now AUDIO_STAGE_MAX_BYTES — a 128 MB anti-DoS
        // staging ceiling. A few spare megabytes are simply a legal pick now, so "oversized"
        // has to be provoked at the gate that still enforces it.
        //
        // That gate is the SAF descriptor pre-check, and faking the length is the honest way to
        // test it: its whole contract is that an oversized pick costs zero transferred bytes, so
        // a test that actually allocated 128 MB would be testing the opposite of the claim.
        every { context.contentResolver.openAssetFileDescriptor(any(), any()) } returns
            mockk<AssetFileDescriptor>(relaxed = true) {
                every { length } returns UiMediaLimits.AUDIO_STAGE_MAX_BYTES + 1
            }

        val result = store.import(UiMediaSlot.SOUND_SCROLL, register(wavBytes(), name = "huge.wav"))

        assertFalse(result.ok, "a pick past the staging ceiling must be refused")
        assertEquals(UiMediaLimits.MSG_TOO_LARGE_BYTES_SOUND, result.message)
        assertTrue(mediaDir().listFiles().isNullOrEmpty(), "a refused pick must not leave a staged copy")
        assertNull(stampPref(), "no stamp bump without a successful import")
    }

    @Test
    fun `unsupported mime is rejected before any copy`() = runTest {
        // A video picked for a sound row: the resolver reports video/mp4 and the probed copy
        // does too — the gate must refuse it for the SOUND slot before anything is committed.
        probeReturns(100L, mime = "video/mp4")
        val result = store.import(UiMediaSlot.SOUND_SCROLL, register(wavBytes(), name = "clip.mp4"))

        assertFalse(result.ok)
        assertTrue(mediaDir().listFiles().isNullOrEmpty())
    }

    @Test
    fun `re-importing with a different extension removes the old file`() = runTest {
        probeReturns(100L)
        store.import(UiMediaSlot.SOUND_BACK, register(wavBytes()))

        // A slot holds ONE file: a new pick under a different container must remove the old one.
        val result = store.import(UiMediaSlot.SOUND_BACK, register(wavBytes(), name = "sel.mp3"))

        assertTrue(result.ok, result.message ?: "import rejected")
        assertTrue(File(mediaDir(), "${UiMediaSlot.SOUND_BACK.key}.mp3").isFile)
        assertFalse(wavFile(UiMediaSlot.SOUND_BACK).isFile, "the old-extension file must be removed")
    }

    // ── pathFor / assignments ─────────────────────────────────────────────────

    @Test
    fun `pathFor resolves the stored file and null when unset`() = runTest {
        assertNull(store.pathFor(UiMediaSlot.SOUND_BACK), "unset slot is on the PFP default")

        probeReturns(100L)
        store.import(UiMediaSlot.SOUND_BACK, register(wavBytes()))

        assertEquals(wavFile(UiMediaSlot.SOUND_BACK).absolutePath, store.pathFor(UiMediaSlot.SOUND_BACK))
    }

    @Test
    fun `assignments lists only assigned slots of stored extensions`() = runTest {
        probeReturns(100L)
        store.import(UiMediaSlot.SOUND_SCROLL, register(wavBytes()))
        // A video pick must probe as a video — the retriever's MIME is the gate's authority.
        probeReturns(100L, mime = "video/mp4")
        store.import(UiMediaSlot.BOOT_VIDEO, register(wavBytes(), name = "boot.mp4"))
        // Hostile/foreign files that could only arrive outside the store's own writes.
        File(mediaDir(), "not_a_slot.wav").writeBytes(wavBytes())

        val assignments = store.assignments()
        assertEquals(setOf(UiMediaSlot.SOUND_SCROLL, UiMediaSlot.BOOT_VIDEO), assignments.keys)
    }

    // ── clear / clearAll ──────────────────────────────────────────────────────

    @Test
    fun `clear removes the slot file and bumps the stamp`() = runTest {
        probeReturns(100L)
        store.import(UiMediaSlot.SOUND_NOTIFICATION, register(wavBytes()))
        val stampBefore = stampPref()

        assertTrue(store.clear(UiMediaSlot.SOUND_NOTIFICATION))
        assertNull(store.pathFor(UiMediaSlot.SOUND_NOTIFICATION))
        assertTrue(stampPref()!! > stampBefore!!, "clear must bump the stamp so observers reload")
    }

    @Test
    fun `clear reports false when the slot has no pick`() = runTest {
        assertFalse(store.clear(UiMediaSlot.SOUND_BACK))
    }

    /**
     * clearAll(SOUND) clears the six menu-sound rows and nothing else. Boot Sound is the Sound
     * screen's SEVENTH row and IS cleared by that screen's reset — but by the ViewModel, not by
     * the store: BOOT_AUDIO is AUDIO_TRACK kind, so clearAll(SOUND) structurally cannot see it
     * (see AudioSettingsViewModel.confirmReset and its test). Videos are never touched either —
     * the Phase 2c rule from docs/plans/README.md (C10).
     */
    @Test
    fun `clearAll of SOUND clears the six sound rows and never touches boot or gameboot media`() = runTest {
        probeReturns(100L)
        store.import(UiMediaSlot.SOUND_SCROLL, register(wavBytes()))
        store.import(UiMediaSlot.SOUND_BACK, register(wavBytes()))
        store.import(UiMediaSlot.BOOT_AUDIO, register(wavBytes(), name = "boot.wav"))
        probeReturns(100L, mime = "video/mp4")
        store.import(UiMediaSlot.BOOT_VIDEO, register(wavBytes(), name = "boot.mp4"))
        probeReturns(100L)
        store.import(UiMediaSlot.GAMEBOOT_AUDIO, register(wavBytes()))

        assertTrue(store.clearAll(UiMediaKind.SOUND))

        assertNull(store.pathFor(UiMediaSlot.SOUND_SCROLL))
        assertNull(store.pathFor(UiMediaSlot.SOUND_BACK))
        assertNotNull(store.pathFor(UiMediaSlot.BOOT_AUDIO), "clearAll(SOUND) must not clear boot audio — its screen's ViewModel owns that")
        assertNotNull(store.pathFor(UiMediaSlot.BOOT_VIDEO), "reset audio must never touch the boot video")
        assertNotNull(store.pathFor(UiMediaSlot.GAMEBOOT_AUDIO), "reset audio must never touch GameBoot media")
    }

    // ── pruneOrphans ─────────────────────────────────────────────────────────

    /**
     * Slots removed from the enum leave files (and display-name prefs) behind on user installs,
     * and a restored OLD backup re-creates them — pruneOrphans sweeps anything that is not a
     * live slot key, including a crashed import's staging file.
     */
    @Test
    fun `pruneOrphans removes files that are not slot keys and keeps the real ones`() = runTest {
        mediaDir().mkdirs()
        File(mediaDir(), "sound_select.wav").writeBytes(wavBytes())
        File(mediaDir(), "sound_systembrowse.ogg").writeBytes(wavBytes())
        File(mediaDir(), "not_a_slot.wav").writeBytes(wavBytes())
        File(mediaDir(), "staging_1725700000000.wav").writeBytes(wavBytes())
        File(mediaDir(), "${UiMediaSlot.SOUND_SCROLL.key}.wav").writeBytes(wavBytes())

        assertTrue(store.pruneOrphans(), "orphan files existed")

        assertFalse(File(mediaDir(), "sound_select.wav").isFile)
        assertFalse(File(mediaDir(), "sound_systembrowse.ogg").isFile)
        assertFalse(File(mediaDir(), "not_a_slot.wav").isFile)
        assertFalse(File(mediaDir(), "staging_1725700000000.wav").isFile, "a crashed import's staging file is garbage")
        assertTrue(File(mediaDir(), "${UiMediaSlot.SOUND_SCROLL.key}.wav").isFile, "a live slot's file is kept")
    }

    @Test
    fun `pruneOrphans drops display-name prefs for orphaned keys only`() = runTest {
        val orphanKey = stringPreferencesKey("ui_media_name_sound_select")
        val liveKey = UiMediaStore.displayNameKey(UiMediaSlot.SOUND_SCROLL)
        context.pfpDataStore.edit { prefs ->
            prefs[orphanKey] = "old pick.wav"
            prefs[liveKey] = "cursor.wav"
        }

        assertTrue(store.pruneOrphans())

        val prefs = context.pfpDataStore.data.first()
        assertNull(prefs[orphanKey], "a slot that no longer exists must not keep a name pref")
        assertEquals("cursor.wav", prefs[liveKey])
    }

    @Test
    fun `pruneOrphans on a clean store reports false and leaves the stamp alone`() = runTest {
        probeReturns(100L)
        store.import(UiMediaSlot.SOUND_SCROLL, register(wavBytes()))
        val stampBefore = stampPref()

        assertFalse(store.pruneOrphans())
        assertEquals(stampBefore, stampPref(), "no bump when nothing was pruned")
    }

    @Test
    fun `pruneOrphans bumps the stamp when it removed something`() = runTest {
        mediaDir().mkdirs()
        File(mediaDir(), "sound_select.wav").writeBytes(wavBytes())

        assertTrue(store.pruneOrphans())
        assertTrue(stampPref()!! > 0L, "observers must reload after a prune")
    }

    // ── path-escape guard ─────────────────────────────────────────────────────

    @Test
    fun `isValidKey keeps crafted keys from escaping the directory`() {
        for (key in listOf("../evil", "boot_video/../../x", "", ".hidden")) {
            assertFalse(
                UiMediaSlot.isValidKey(key),
                "key '$key' must not be a valid slot key",
            )
        }
        for (slot in UiMediaSlot.entries) {
            assertTrue(UiMediaSlot.isValidKey(slot.key))
        }
    }

    // ── display names ─────────────────────────────────────────────────────────

    @Test
    fun `recordDisplayName stores the provider name and falls back when it reports none`() = runTest {
        store.recordDisplayName(UiMediaSlot.SOUND_SCROLL, register(wavBytes(), name = "cursor.wav"))
        assertEquals("cursor.wav", store.displayNameFor(UiMediaSlot.SOUND_SCROLL))

        val unnamed = Uri.parse("content://opaque/no-name-here")
        store.recordDisplayName(UiMediaSlot.SOUND_BACK, unnamed)
        assertEquals("Custom sound", store.displayNameFor(UiMediaSlot.SOUND_BACK))
    }

    @Test
    fun `recordDisplayName clamps hostile names`() = runTest {
        val hostile = register(wavBytes(), name = "x".repeat(500))
        store.recordDisplayName(UiMediaSlot.SOUND_SCROLL, hostile)
        val stored = assertNotNull(store.displayNameFor(UiMediaSlot.SOUND_SCROLL))
        assertTrue(
            stored.length <= MediaDisplayNames.MAX_LENGTH + 1,
            "provider-controlled names are clamped",
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Minimal well-formed PCM RIFF/WAVE (44.1 kHz mono 16-bit) + payload — the gate probes
     * metadata only, but the header must be honest so the duration fallback can time it.
     */
    private fun wavBytes(): ByteArray {
        val data = ByteArray(2048)
        val out = java.io.ByteArrayOutputStream()
        out.write("RIFF".toByteArray())
        writeIntLe(out, 36 + data.size)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        writeIntLe(out, 16)
        writeShortLe(out, 1) // PCM
        writeShortLe(out, 1) // mono
        writeIntLe(out, 44_100)
        writeIntLe(out, 88_200) // byte rate
        writeShortLe(out, 2) // block align
        writeShortLe(out, 16) // bits per sample
        out.write("data".toByteArray())
        writeIntLe(out, data.size)
        out.write(data)
        return out.toByteArray()
    }

    /**
     * A 45-byte ID3v2.4 tag + one MPEG1 Layer III frame carrying a Xing table declaring
     * [frames] — the shape of the device's tiny ffmpeg-native clips that MMR cannot time.
     */
    private fun xingVbrMp3(frames: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0, 0, 0x23))
        out.write(ByteArray(35))
        out.write(0xFF)
        out.write(0xFB) // MPEG1, Layer III, no CRC
        out.write(0x50) // bitrate idx 5, 44100 Hz
        out.write(0x00) // stereo
        out.write(ByteArray(32)) // MPEG1 stereo side info
        out.write("Xing".toByteArray())
        out.write(byteArrayOf(0, 0, 0, 0x0F))
        out.write(byteArrayOf(0, 0, 0, frames.toByte()))
        out.write(ByteArray(8))
        out.write(ByteArray(700)) // a few audio frames' worth of junk
        return out.toByteArray()
    }

    private fun writeIntLe(out: java.io.ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
        out.write((v shr 16) and 0xFF)
        out.write((v shr 24) and 0xFF)
    }

    private fun writeShortLe(out: java.io.ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    private fun register(bytes: ByteArray, name: String = "pick.wav"): Uri {
        val uri = Uri.parse("content://test/$name")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        return uri
    }

    private fun mediaDir() = File(context.filesDir, UiMediaStore.UI_MEDIA_DIR)
    private fun wavFile(slot: UiMediaSlot) = File(mediaDir(), "${slot.key}.wav")

    private suspend fun stampPref(): Long? =
        context.pfpDataStore.data.first()[longPreferencesKey("ui_media_stamp")]
}
