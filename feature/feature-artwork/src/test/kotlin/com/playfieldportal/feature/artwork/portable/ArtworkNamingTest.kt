package com.playfieldportal.feature.artwork.portable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Frozen normalization rules (NORMALIZATION_VERSION 1) — these expectations must never change
// without a version bump; existing portable libraries stop matching if slugs drift.
class ArtworkNamingTest {

    @Test
    fun `file stem strips only the final extension`() {
        assertEquals("Ratchet & Clank (USA)", ArtworkNaming.fileStem("Ratchet & Clank (USA).chd"))
        assertEquals("game.v1.2", ArtworkNaming.fileStem("game.v1.2.iso"))
        assertEquals("no-extension", ArtworkNaming.fileStem("no-extension"))
        assertEquals(".hidden", ArtworkNaming.fileStem(".hidden")) // leading dot is not an extension
    }

    @Test
    fun `normalizeForMatch equates case, unicode apostrophes and runs of spaces`() {
        assertEquals(
            ArtworkNaming.normalizeForMatch("Ratchet & Clank (USA)"),
            ArtworkNaming.normalizeForMatch("  ratchet  &  clank (usa) "),
        )
        assertEquals(
            ArtworkNaming.normalizeForMatch("Tony Hawk's Pro Skater"),
            ArtworkNaming.normalizeForMatch("Tony Hawk’s Pro Skater"),
        )
        // Tags are preserved at this pass — releases stay distinguishable.
        assertFalse(
            ArtworkNaming.normalizeForMatch("Game (USA)") == ArtworkNaming.normalizeForMatch("Game (Europe)"),
        )
    }

    @Test
    fun `simplifyTitle strips tags and unifies punctuation`() {
        val a = ArtworkNaming.simplifyTitle("Jak & Daxter - The Precursor Legacy (USA) [!]")
        val b = ArtworkNaming.simplifyTitle("Jak and Daxter: The Precursor Legacy")
        assertEquals(a, b)
        assertEquals("final fantasy vii", ArtworkNaming.simplifyTitle("Final Fantasy VII (Disc 1) (USA)"))
    }

    @Test
    fun `slug drops tags but keeps disc numbers`() {
        assertEquals("final-fantasy-vii-disc1", ArtworkNaming.slug("Final Fantasy VII (Disc 1) (USA)"))
        assertEquals("final-fantasy-vii-disc2", ArtworkNaming.slug("Final Fantasy VII (Disc 2) (USA)"))
        assertEquals("ratchet-clank", ArtworkNaming.slug("Ratchet & Clank (USA)"))
    }

    @Test
    fun `slug output is always folder-safe`() {
        val hostile = listOf(
            "../../../etc/passwd",
            "..\\..\\windows\\system32",
            "con",
            "COM1",
            "...",
            "",
            "   ",
            "日本語のゲーム",
            "a".repeat(500),
        )
        hostile.forEach { input ->
            val slug = ArtworkNaming.slug(input)
            assertTrue("slug for '$input' was '$slug'", slug.matches(Regex("[a-z0-9][a-z0-9._-]*")))
            assertFalse("slug for '$input' contains separator", slug.contains('/') || slug.contains('\\'))
            assertFalse("slug for '$input' is traversal", slug.contains(".."))
            assertTrue("slug for '$input' too long", slug.length <= ArtworkNaming.MAX_SLUG_LENGTH)
            assertFalse("slug for '$input' reserved", slug in listOf("con", "prn", "aux", "nul", "com1", "lpt1"))
        }
    }

    @Test
    fun `slug is deterministic and case-insensitive`() {
        assertEquals(ArtworkNaming.slug("Crash Bandicoot"), ArtworkNaming.slug("CRASH BANDICOOT"))
    }
}
