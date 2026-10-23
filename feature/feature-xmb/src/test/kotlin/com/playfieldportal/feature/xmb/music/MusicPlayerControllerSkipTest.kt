package com.playfieldportal.feature.xmb.music

import android.media.MediaPlayer
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.domain.model.MusicTrack
import com.playfieldportal.core.ui.sound.AmbienceController
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A track that cannot be loaded is skipped, and the controller keeps the NEXT track's player.
 *
 * Survey L6 (docs/audits/codebase-survey-2026-10-22.md): `next()` ran inside the `MediaPlayer().apply`
 * block, before `player =` was assigned. The skip built and stored a working player for the next
 * track, then the outer assignment overwrote it with the failed one — so pause and stop acted on a
 * dead player while the real one leaked and kept playing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MusicPlayerControllerSkipTest {

    private fun track(id: String) = MusicTrack(
        id = id, folderId = "f", uri = "content://music/$id", displayName = "$id.mp3",
    )

    @Test
    fun `an unloadable track is released and the next track's player is the one kept`() {
        val broken = mockk<MediaPlayer>(relaxed = true) {
            every { setDataSource(any<android.content.Context>(), any<android.net.Uri>()) } throws
                java.io.IOException("SAF grant revoked")
        }
        val working = mockk<MediaPlayer>(relaxed = true) { every { isPlaying } returns false }
        val players = ArrayDeque(listOf(broken, working))

        val controller = MusicPlayerController(
            ApplicationProvider.getApplicationContext(),
            mockk<AmbienceController>(relaxed = true),
        )
        controller.playerFactory = { players.removeFirst() }

        controller.setQueue(listOf(track("a"), track("b")), startIndex = 0)

        verify { broken.release() }
        assertEquals(1, controller.state.value.index)

        // Play/pause must reach the working player, never the released one.
        controller.playPause()
        verify { working.start() }
        verify(exactly = 0) { broken.start() }

        // Stop releases the working player too, so nothing keeps playing behind the UI.
        controller.stop()
        verify { working.release() }
    }
}
