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
 * Pins the bundled-default seam (docs/plans/README.md (C10)): every playable event falls back to
 * a bundled sample, Navigation's three events share one slot and one sample, each presentation's
 * baked-in sound resolves to a URI ExoPlayer can open, and the rule both presentations share —
 * a custom clip keeps its own audio track, and only the built-in one gets the bundled sound.
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
        assertEquals(UiMediaSlot.SOUND_NOTIFICATION, MenuSound.NOTIFICATION.slot)
    }

    @Test fun `there is no launch event - opening something is never a menu sound`() {
        // The regression this whole change exists for: a LAUNCH event coming back would put the
        // chirp on every app open again, and it would do it with a 5-second SoundPool sample.
        assertNull(
            MenuSound.entries.firstOrNull { it.name == "LAUNCH" },
            "opening an app is silent; opening a game is GameBoot's job",
        )
    }

    // ── per-slot bundled defaults ─────────────────────────────────────────────

    @Test fun `every sound slot maps to its bundled sample`() {
        assertEquals(R.raw.sfx_cursor, UiMediaSlot.SOUND_SCROLL.bundledDefaultRes())
        assertEquals(R.raw.sfx_back, UiMediaSlot.SOUND_BACK.bundledDefaultRes())
        assertEquals(R.raw.sfx_confirm, UiMediaSlot.SOUND_CONFIRM.bundledDefaultRes())
        assertEquals(R.raw.sfx_error, UiMediaSlot.SOUND_ERROR.bundledDefaultRes())
        assertEquals(R.raw.sfx_notification, UiMediaSlot.SOUND_NOTIFICATION.bundledDefaultRes())
    }

    @Test fun `no slot maps to a presentation sample - those are baked in, not assignable`() {
        // sfx_launch and sfx_opening still ship; what is gone is any SLOT pointing at them. A
        // slot here again would mean an assignable row, which is what we just retired.
        val presentationSamples = setOf(R.raw.sfx_launch, R.raw.sfx_opening)
        for (slot in UiMediaSlot.entries) {
            val res = slot.bundledDefaultRes()
            assertTrue(
                res == null || res !in presentationSamples,
                "${slot.key} points at a presentation's baked-in sound",
            )
        }
    }

    @Test fun `ambience ships with no bundled track - an assignment is what turns it on`() {
        // A bundled default here would mean every install starts playing music nobody chose.
        assertNull(UiMediaSlot.AMBIENCE_AUDIO.bundledDefaultRes())
        assertNull(UiMediaSlot.AMBIENCE_AUDIO.bundledDefaultUri("com.playfieldportal.launcher"))
    }

    @Test fun `neither presentation has a media slot beyond its replaceable video`() {
        // The whole point of the one-slot shape: there is nothing to assign but the clip.
        for (slot in listOf(UiMediaSlot.GAMEBOOT_VIDEO, UiMediaSlot.BOOT_VIDEO)) {
            assertNull(slot.bundledDefaultRes())
            assertNull(slot.bundledDefaultUri("com.playfieldportal.launcher"))
        }
        assertNull(
            UiMediaSlot.fromKey("gameboot_audio"),
            "the retired GameBoot audio slot must not come back",
        )
        assertNull(
            UiMediaSlot.fromKey("boot_audio"),
            "the retired Boot Sound slot must not come back",
        )
    }

    @Test fun `the built-in gameboot sound resolves to the bundled launch sample`() {
        val uri = gameBootDefaultAudioUri("com.playfieldportal.launcher")
        assertEquals("android.resource://com.playfieldportal.launcher/${R.raw.sfx_launch}", uri)
    }

    @Test fun `the built-in boot chime resolves to the bundled opening sample`() {
        val uri = bootDefaultAudioUri("com.playfieldportal.launcher")
        assertEquals("android.resource://com.playfieldportal.launcher/${R.raw.sfx_opening}", uri)
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
        val uri = assertNotNull(UiMediaSlot.SOUND_CONFIRM.bundledDefaultUri("com.playfieldportal.launcher"))
        assertTrue(
            uri.startsWith("android.resource://com.playfieldportal.launcher/"),
            "not an android.resource URI ExoPlayer can open: $uri",
        )
        assertNull(UiMediaSlot.BOOT_VIDEO.bundledDefaultUri("com.playfieldportal.launcher"))
    }

    // ── boot presentation resolution ─────────────────────────────────────────

    @Test fun `a custom boot clip keeps its own track`() {
        // Do NOT fall back to the bundled chime here: every custom boot video would play muted
        // under the PFP opening. Null means the clip's own audio.
        assertNull(resolveBootAudio("/video.mp4", "/bundled"))
    }

    @Test fun `no custom clip falls back to the bundled opening chime`() {
        assertEquals("/bundled", resolveBootAudio(null, "/bundled"))
    }

    @Test fun `boot and gameboot resolve audio by the same rule`() {
        // The two presentations struck the same bargain — replace the clip, bring your own sound.
        // If one grows a branch the other lacks, that asymmetry is a bug, not a feature.
        for (video in listOf(null, "/video.mp4")) {
            assertEquals(
                resolveGameBootAudio(video, "/bundled"),
                resolveBootAudio(video, "/bundled"),
                "boot and GameBoot audio resolution disagree for customVideoPath=$video",
            )
        }
    }

    // ── GameBoot presentation resolution ─────────────────────────────────────

    @Test fun `a custom gameboot clip keeps its own track`() {
        // Do NOT play the built-in sound under someone's clip: it would score their video with
        // audio they never asked for. Null means "the clip's own track".
        assertNull(resolveGameBootAudio("/video.mp4", "/bundled"))
    }

    @Test fun `no custom clip plays the built-in sound the sequence is timed to`() {
        assertEquals("/bundled", resolveGameBootAudio(null, "/bundled"))
    }
}
