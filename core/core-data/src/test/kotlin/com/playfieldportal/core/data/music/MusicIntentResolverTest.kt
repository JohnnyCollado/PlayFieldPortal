package com.playfieldportal.core.data.music

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.domain.model.MusicTrack
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MusicIntentResolverTest {

    private val resolver = MusicIntentResolver(ApplicationProvider.getApplicationContext())

    private fun track(mime: String? = "audio/mpeg") = MusicTrack(
        id = "t1",
        folderId = "f1",
        uri = "content://com.example/tree/song.mp3",
        displayName = "song.mp3",
        mimeType = mime,
    )

    @Test
    fun `intent is ACTION_VIEW with the track uri, mime, and read-grant flag`() {
        val intent = resolver.buildIntent(track(), defaultPlayerPackage = null)

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(track().uri, intent.data?.toString())
        assertEquals("audio/mpeg", intent.type)
        assertTrue(
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
            "Expected FLAG_GRANT_READ_URI_PERMISSION",
        )
    }

    @Test
    fun `default player package is respected when set`() {
        val intent = resolver.buildIntent(track(), defaultPlayerPackage = "com.example.player")
        assertEquals("com.example.player", intent.`package`)
    }

    @Test
    fun `no package is pinned when default player is unset`() {
        assertNull(resolver.buildIntent(track(), defaultPlayerPackage = null).`package`)
        assertNull(resolver.buildIntent(track(), defaultPlayerPackage = "").`package`)
    }

    @Test
    fun `missing track mime falls back to generic audio type`() {
        val intent = resolver.buildIntent(track(mime = null), defaultPlayerPackage = null)
        assertEquals("audio/*", intent.type)
        assertNotEquals(null, intent.data)
    }
}
