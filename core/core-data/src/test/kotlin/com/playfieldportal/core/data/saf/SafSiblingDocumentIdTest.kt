package com.playfieldportal.core.data.saf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reaching a sheet's sibling through SAF: a .cue/.gdi names its .bin/track files, and the tree
 * grant that covers the sheet covers them too — but only if the sibling's document id is derived
 * correctly and the name it came from is trustworthy.
 *
 * Both halves are security-relevant. The id surgery decides which document gets opened; the name
 * guard is what stops untrusted sheet contents naming a file outside the sheet's own directory.
 *
 * Supersedes feature-achievements' SiblingDocumentIdTest, whose cases are carried over here.
 */
class SafSiblingDocumentIdTest {

    // ── safSiblingDocumentId ─────────────────────────────────────────────────────

    @Test
    fun `swaps the last path segment`() {
        assertEquals(
            "primary:Roms/psx/Game/Game.bin",
            safSiblingDocumentId("primary:Roms/psx/Game/Game.cue", "Game.bin"),
        )
    }

    @Test
    fun `keeps the volume prefix for a file directly under the root`() {
        // No '/' to split on — the volume's ':' is the only anchor. Splitting on the last '/' of a
        // string that has none returns the whole id, which would build "primary:Game.cue/Game.bin".
        assertEquals("primary:Game.bin", safSiblingDocumentId("primary:Game.cue", "Game.bin"))
        assertEquals("408C-3861:Game.bin", safSiblingDocumentId("408C-3861:Game.cue", "Game.bin"))
    }

    @Test
    fun `splits on the last slash, not the volume colon, for a deep path`() {
        assertEquals(
            "408C-3861:Games/psx/Parasite Eve II (Disc 2)/Parasite Eve II (Disc 2).bin",
            safSiblingDocumentId(
                "408C-3861:Games/psx/Parasite Eve II (Disc 2)/Parasite Eve II (Disc 2).cue",
                "Parasite Eve II (Disc 2).bin",
            ),
        )
    }

    @Test
    fun `preserves the sibling name's case`() {
        // The whole point of the case-preserving sheet parse: an id built from a lowercased name
        // resolves to nothing on a case-sensitive volume.
        assertEquals(
            "primary:Roms/psx/PE2/Parasite Eve II (Disc 2).bin",
            safSiblingDocumentId("primary:Roms/psx/PE2/Parasite Eve II (Disc 2).cue", "Parasite Eve II (Disc 2).bin"),
        )
    }

    @Test
    fun `returns null for an id with no separator to anchor on`() {
        assertNull(safSiblingDocumentId("opaque-doc-id", "Game.bin"))
    }

    // ── isSafeSiblingName ────────────────────────────────────────────────────────

    @Test
    fun `accepts a plain sibling name`() {
        assertTrue(isSafeSiblingName("Game.bin"))
        assertTrue(isSafeSiblingName("Parasite Eve II (Disc 2).bin"))
        assertTrue(isSafeSiblingName("Track01.RAW"))
    }

    @Test
    fun `rejects a name carrying a path component`() {
        assertFalse(isSafeSiblingName("sub/track02.bin"))
        assertFalse(isSafeSiblingName("../../etc/passwd"))
        assertFalse(isSafeSiblingName("..\\..\\evil.bin"))
        assertFalse(isSafeSiblingName("/absolute/path.bin"))
    }

    @Test
    fun `rejects an empty name`() {
        assertFalse(isSafeSiblingName(""))
    }

    @Test
    fun `rejects a bare parent-directory reference`() {
        // Deliberately tighter than the feature-achievements original, which accepts ".." because
        // File("..").name == "..". Not exploitable today (SAF treats ids opaquely, and the raw path
        // resolves to a directory that fails the isFile check), but it is the canonical traversal
        // token and this is the guard that is supposed to reject it.
        assertFalse(isSafeSiblingName(".."))
    }
}
