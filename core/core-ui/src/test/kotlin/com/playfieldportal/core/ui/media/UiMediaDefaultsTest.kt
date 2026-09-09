package com.playfieldportal.core.ui.media

import com.playfieldportal.core.domain.model.UiMediaKind
import com.playfieldportal.core.domain.model.UiMediaSlot
import com.playfieldportal.core.ui.R
import com.playfieldportal.core.ui.sound.MenuSound
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the bundled-default seam (docs/plans/README.md (C10)): every playable event
 * falls back to a bundled sample, Navigation's three events share one slot and one sample, boot
 * audio's default resolves to a URI ExoPlayer can open, and the presentation rule that a custom
 * boot video keeps its own audio track unless the user explicitly assigned a boot sound.
 */
class UiMediaDefaultsTest {

    // ── the plan's drift test: events → surviving slots → bundled defaults ───

    @Test fun `every menu sound resolves to a slot that still exists with a bundled default`() {
        for (sound in MenuSound.entries) {
            val slot = sound.slot
            assertTrue(
                UiMediaSlot.entries.contains(slot),
                "${sound.name} resolves to slot ${slot.key}, which is no longer a slot",
            )
            assertNotNull(
                slot.bundledDefaultRes(),
                "${sound.name}'s slot ${slot.key} has no bundled default",
            )
        }
    }

    @Test fun `navigation covers scroll select and system browse`() {
        assertEquals(UiMediaSlot.SOUND_SCROLL, MenuSound.SCROLL.slot)
        assertEquals(UiMediaSlot.SOUND_SCROLL, MenuSound.SELECT.slot)
        assertEquals(UiMediaSlot.SOUND_SCROLL, MenuSound.SYSTEM_BROWSE.slot)
    }

    @Test fun `every other event keeps its own slot`() {
        assertEquals(UiMediaSlot.SOUND_BACK, MenuSound.BACK.slot)
        assertEquals(UiMediaSlot.SOUND_CONFIRM, MenuSound.CONFIRM.slot)
        assertEquals(UiMediaSlot.SOUND_ERROR, MenuSound.ERROR.slot)
        assertEquals(UiMediaSlot.SOUND_LAUNCH, MenuSound.LAUNCH.slot)
        assertEquals(UiMediaSlot.SOUND_NOTIFICATION, MenuSound.NOTIFICATION.slot)
    }

    // ── per-slot bundled defaults ─────────────────────────────────────────────

    @Test fun `sound slots and boot audio map to the seven bundled samples`() {
        assertEquals(R.raw.sfx_cursor, UiMediaSlot.SOUND_SCROLL.bundledDefaultRes())
        assertEquals(R.raw.sfx_back, UiMediaSlot.SOUND_BACK.bundledDefaultRes())
        assertEquals(R.raw.sfx_confirm, UiMediaSlot.SOUND_CONFIRM.bundledDefaultRes())
        assertEquals(R.raw.sfx_error, UiMediaSlot.SOUND_ERROR.bundledDefaultRes())
        assertEquals(R.raw.sfx_launch, UiMediaSlot.SOUND_LAUNCH.bundledDefaultRes())
        assertEquals(R.raw.sfx_notification, UiMediaSlot.SOUND_NOTIFICATION.bundledDefaultRes())
        assertEquals(R.raw.sfx_opening, UiMediaSlot.BOOT_AUDIO.bundledDefaultRes())
    }

    @Test fun `video slots have no bundled default - none should ever be added`() {
        for (slot in UiMediaSlot.entries) {
            if (slot.kind == UiMediaKind.VIDEO) {
                assertNull(slot.bundledDefaultRes(), "${slot.key} must never gain a bundled default")
            }
        }
    }

    @Test fun `bundled default resolves to an android resource uri`() {
        // The numeric resource-id form: ExoPlayer's RawResourceDataSource opens it directly.
        val uri = assertNotNull(UiMediaSlot.BOOT_AUDIO.bundledDefaultUri("com.playfieldportal.launcher"))
        assertTrue(
            uri.startsWith("android.resource://com.playfieldportal.launcher/"),
            "not an android.resource URI ExoPlayer can open: $uri",
        )
        assertNull(UiMediaSlot.BOOT_VIDEO.bundledDefaultUri("com.playfieldportal.launcher"))
    }

    // ── boot presentation resolution ─────────────────────────────────────────

    @Test fun `custom boot audio always wins over both the bundled default and the clip`() {
        assertEquals("/custom.wav", resolveBootAudio("/video.mp4", "/custom.wav", "/bundled"))
        assertEquals("/custom.wav", resolveBootAudio(null, "/custom.wav", "/bundled"))
    }

    @Test fun `custom video with no custom boot audio keeps its own track`() {
        // Do NOT fall back to the bundled chime here: every custom boot video would play muted
        // under the PFP opening. Silence means the clip's own audio.
        assertNull(resolveBootAudio("/video.mp4", null, "/bundled"))
    }

    @Test fun `no custom media falls back to the bundled opening chime`() {
        assertEquals("/bundled", resolveBootAudio(null, null, "/bundled"))
    }
}
