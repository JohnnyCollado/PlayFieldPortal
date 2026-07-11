package com.playfieldportal.feature.artwork.portable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Layout-v2 portable names: ROM stems preserved verbatim (they ARE the compatibility),
// sanitized only where a filesystem would reject them.
class PortableNameResolverTest {

    @Test
    fun `rom stems keep case, spaces and tags`() {
        assertEquals(
            "Final Fantasy X (USA)",
            PortableNameResolver.fromRomFileName("Final Fantasy X (USA).chd"),
        )
        assertEquals(
            "Yu-Gi-Oh! GX - Tag Force 3 (Europe) (En,Fr,De,Es,It)",
            PortableNameResolver.fromRomFileName("Yu-Gi-Oh! GX - Tag Force 3 (Europe) (En,Fr,De,Es,It).iso"),
        )
        assertEquals(
            "Final Fantasy 6 Advance # GBA",
            PortableNameResolver.fromRomFileName("Final Fantasy 6 Advance # GBA.GBA"),
        )
    }

    @Test
    fun `illegal filesystem characters are sanitized, nothing else`() {
        assertEquals("Ico Shadow", PortableNameResolver.sanitize("Ico/Shadow"))
        assertEquals("What if", PortableNameResolver.sanitize("What if?"))
        assertEquals("A B", PortableNameResolver.sanitize("A:B"))
        assertEquals("quote", PortableNameResolver.sanitize("\"quote\""))
    }

    @Test
    fun `output is always safe as a single filename component`() {
        val hostile = listOf(
            "../../../etc/passwd", "..\\..\\sys", "con", "COM1", "...", "", "   ",
            ".hidden", "trailing.", "a".repeat(500), "CON.png|x",
        )
        hostile.forEach { input ->
            val name = PortableNameResolver.sanitize(input)
            assertFalse("'$input' → '$name' has separator", name.contains('/') || name.contains('\\'))
            assertFalse("'$input' → '$name' starts with dot", name.startsWith("."))
            assertFalse("'$input' → '$name' ends with dot/space", name.endsWith(".") || name.endsWith(" "))
            assertTrue("'$input' → '$name' too long", name.length <= PortableNameResolver.MAX_LENGTH)
            assertTrue("'$input' → '$name' blank", name.isNotBlank())
            assertFalse("'$input' → '$name' reserved", name.lowercase() in listOf("con", "com1", "nul", "aux", "prn"))
        }
    }

    @Test
    fun `path resolver round-trips kinds and dirs`() {
        com.playfieldportal.feature.artwork.store.ArtworkKind.entries.forEach { kind ->
            val dir = ArtworkPathResolver.mediaDirFor(kind)
            assertEquals(kind, ArtworkPathResolver.kindForMediaDir(dir))
            assertTrue(ArtworkPathResolver.isMediaDirName(dir))
        }
        assertEquals(
            "Artwork/ps2/covers/Final Fantasy X (USA).png",
            ArtworkPathResolver.relativePath("ps2", com.playfieldportal.feature.artwork.store.ArtworkKind.ICON, "Final Fantasy X (USA).png"),
        )
    }
}
