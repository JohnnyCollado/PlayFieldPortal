package com.playfieldportal.feature.achievements.match

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins the document-id surgery cueSiblingUri uses to reach a .cue's .bin through SAF. */
class SiblingDocumentIdTest {

    @Test
    fun `swaps the last path segment`() {
        assertEquals(
            "primary:Roms/psx/Game/Game.bin",
            siblingDocumentId("primary:Roms/psx/Game/Game.cue", "Game.bin"),
        )
    }

    @Test
    fun `keeps the root prefix for a file directly under the volume root`() {
        assertEquals("primary:Game.bin", siblingDocumentId("primary:Game.cue", "Game.bin"))
        // SD-card style volume ids too.
        assertEquals("408C-3861:Game.bin", siblingDocumentId("408C-3861:Game.cue", "Game.bin"))
    }

    @Test
    fun `returns null for an id with no separators to anchor on`() {
        assertNull(siblingDocumentId("opaque-doc-id", "Game.bin"))
    }
}
