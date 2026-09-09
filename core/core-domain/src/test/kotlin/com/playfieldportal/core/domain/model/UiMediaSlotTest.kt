package com.playfieldportal.core.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the seven-sound roster (docs/plans/README.md (C10)): six SOUND-kind rows in the
 * plan table's order, Boot Sound as the seventh row on the Sound screen, and the storage-key
 * decisions that keep old installs and backups from breaking — `sound_scroll` survives its
 * rename to "Navigation" with no migration, and the collapsed slots' keys (the two merged menu
 * sounds, and GameBoot's retired audio slot) become invalid so `pruneOrphans` can sweep their
 * leftovers.
 */
class UiMediaSlotTest {

    // ── the roster ───────────────────────────────────────────────────────────

    @Test fun `sound slots are exactly the six merged rows in roster order`() {
        assertEquals(
            listOf(
                "sound_scroll" to "Navigation",
                "sound_back" to "Back / Cancel",
                "sound_confirm" to "Confirm / Apply",
                "sound_error" to "Error / Invalid",
                "sound_launch" to "Launch Sound",
                "sound_notification" to "Notification",
            ),
            UiMediaSlot.ofKind(UiMediaKind.SOUND).map { it.key to it.displayName },
            "the settings screen iterates enum order — this is the Sound screen's row order",
        )
    }

    @Test fun `boot sound is the seventh row and stays an audio track`() {
        assertEquals(UiMediaKind.AUDIO_TRACK, UiMediaSlot.BOOT_AUDIO.kind)
        assertFalse(UiMediaSlot.BOOT_AUDIO.isSound, "boot audio is not a SoundPool menu sound")
        assertEquals("boot_audio", UiMediaSlot.BOOT_AUDIO.key)
        assertEquals("Boot Sound", UiMediaSlot.BOOT_AUDIO.displayName)
    }

    // ── the collapse: removed slots ──────────────────────────────────────────

    @Test fun `removed slots are gone and their keys are invalid`() {
        assertNull(UiMediaSlot.fromKey("sound_select"), "SOUND_SELECT was merged into Navigation")
        assertNull(UiMediaSlot.fromKey("sound_systembrowse"), "SOUND_SYSTEM_BROWSE was merged into Navigation")
        assertFalse(UiMediaSlot.isValidKey("sound_select"))
        assertFalse(UiMediaSlot.isValidKey("sound_systembrowse"))
    }

    @Test fun `gameboot is one slot - the replaceable clip, with no separate sound`() {
        // GameBoot is ONE thing: the built-in sequence with its own bundled sound, or a user clip
        // that replaces the whole presentation. An invalid gameboot_audio key is what lets
        // pruneOrphans sweep the retired slot's file and display name.
        assertEquals(UiMediaSlot.GAMEBOOT_VIDEO, UiMediaSlot.fromKey("gameboot_video"))
        assertEquals(UiMediaKind.VIDEO, UiMediaSlot.GAMEBOOT_VIDEO.kind)
        assertNull(UiMediaSlot.fromKey("gameboot_audio"), "GAMEBOOT_AUDIO was retired")
        assertFalse(UiMediaSlot.isValidKey("gameboot_audio"))
    }

    @Test fun `sound_scroll keeps its storage key through the rename to Navigation`() {
        // No migration: a user who customized the old Navigation row keeps their assignment.
        assertEquals(UiMediaSlot.SOUND_SCROLL, UiMediaSlot.fromKey("sound_scroll"))
        assertEquals("sound_scroll", UiMediaSlot.SOUND_SCROLL.key)
    }

    // ── drift pins ───────────────────────────────────────────────────────────

    @Test fun `every slot's kind matches its limits spec kind`() {
        // UiMediaSlot carries the spec directly, so a slot can never LACK one — the drift risk is
        // the two kind enums disagreeing (e.g. a SOUND slot holding an AUDIO_TRACK spec would
        // accept the wrong MIME set at import).
        for (slot in UiMediaSlot.entries) {
            assertEquals(
                slot.kind.name,
                slot.limits.kind.name,
                "${slot.key}: UiMediaKind and UiMediaLimits.Kind have drifted",
            )
        }
    }

    @Test fun `slot keys stay filename-safe lowercase identifiers`() {
        // The keys are used verbatim as file names under filesDir/ui-media — see the path-escape
        // guard's own tests in UiMediaStoreTest.
        for (slot in UiMediaSlot.entries) {
            assertTrue(Regex("[a-z0-9_]+").matches(slot.key), "${slot.key} is not filename-safe")
        }
    }

    @Test fun `slot keys are unique`() {
        val keys = UiMediaSlot.entries.map { it.key }
        assertEquals(keys.size, keys.distinct().size, "keys are file names — a collision overwrites")
    }
}
