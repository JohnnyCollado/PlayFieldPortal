package com.playfieldportal.feature.artwork.portable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C16 task D.1 — the durable-identity index that stops artwork identity being spelled as a filename.
 *
 * Pure model plus codec, so it pins without a device or a SAF tree.
 */
class ArtworkIdentityIndexTest {

    private fun entry(
        platformId: String = "snes",
        kind: String = "ICON",
        portableName: String = "Final Fantasy VI",
        romCrc32: String? = "A1B2C3D4",
        ssId: Long? = 1234,
        tgdbId: Long? = null,
        igdbId: Long? = null,
        sgdbId: Long? = null,
        artworkKey: String? = "rom/snes/final-fantasy-vi",
    ) = ArtworkIdentityIndex.Entry(
        platformId = platformId, kind = kind, portableName = portableName,
        romCrc32 = romCrc32, ssId = ssId, tgdbId = tgdbId, igdbId = igdbId, sgdbId = sgdbId,
        artworkKey = artworkKey,
    )

    // ── Codec ─────────────────────────────────────────────────────────────────

    @Test fun `round trips through encode and parse`() {
        val index = ArtworkIdentityIndex(entries = listOf(entry(), entry(kind = "HERO")))
        val back = ArtworkIdentityIndex.parse(ArtworkIdentityIndex.encode(index))
        assertEquals(index, back)
    }

    @Test fun `an empty index round trips as empty, not as null`() {
        val back = ArtworkIdentityIndex.parse(ArtworkIdentityIndex.encode(ArtworkIdentityIndex()))
        assertNotNull("an empty index is valid — it means 'nothing recorded yet'", back)
        assertTrue(back!!.entries.isEmpty())
    }

    @Test fun `malformed json parses to null rather than throwing`() {
        assertNull(ArtworkIdentityIndex.parse("{ not json"))
        assertNull(ArtworkIdentityIndex.parse(""))
    }

    // ── Tri-state read (task 1.3 / D3) ───────────────────────────────────────

    @Test fun `one bad row is dropped and the rest load`() {
        val json = """
            {"format_version":1,"entries":[
              {"platform_id":"snes","kind":"ICON","portable_name":"Final Fantasy VI",
               "rom_crc32":"A1B2C3D4"},
              {"platform_id":"snes","kind":"HERO"}
            ]}
        """.trimIndent()
        val parsed = ArtworkIdentityIndex.parse(json)
        assertNotNull("the readable rows must still load", parsed)
        assertEquals(1, parsed!!.entries.size)
        assertEquals("A1B2C3D4", parsed.entries.single().romCrc32)
    }

    @Test fun `format_version 2 is unreadable`() {
        val json = """{"format_version":2,"entries":[]}"""
        assertNull(ArtworkIdentityIndex.parse(json))
    }

    // A future version may add fields; an older build must keep reading the file rather than
    // treating the whole library as unidentified.
    @Test fun `unknown keys are ignored`() {
        val json = """
            {"format_version":1,"entries":[
              {"platform_id":"snes","kind":"ICON","portable_name":"Final Fantasy VI",
               "rom_crc32":"A1B2C3D4","future_field":"whatever"}
            ],"tomorrows_key":42}
        """.trimIndent()
        val parsed = ArtworkIdentityIndex.parse(json)
        assertNotNull(parsed)
        assertEquals("A1B2C3D4", parsed!!.entries.single().romCrc32)
    }

    // The serialized names are ArtworkEntryMetadata's, so a v1 metadata.json's identity fields stay
    // readable. Renaming them would strand every library written before this task.
    @Test fun `serial names match the v1 entry metadata`() {
        val text = ArtworkIdentityIndex.encode(ArtworkIdentityIndex(entries = listOf(entry())))
        listOf("rom_crc32", "ss_id", "tgdb_id", "igdb_id", "sgdb_id", "platform_id", "portable_name")
            .forEach { assertTrue("$it must be the serialized name", text.contains("\"$it\"")) }
    }

    // ── Identity tokens ───────────────────────────────────────────────────────

    @Test fun `tokens are emitted only for the ids the entry actually has`() {
        assertEquals(
            listOf("crc:A1B2C3D4", "ss:1234", "key:rom/snes/final-fantasy-vi"),
            entry().tokens(),
        )
    }

    @Test fun `an entry with no identity at all yields no tokens`() {
        val bare = entry(romCrc32 = null, ssId = null, artworkKey = null)
        assertTrue("with nothing durable it must fall through to name matching", bare.tokens().isEmpty())
    }

    // CRC is content-derived, so it outranks a scraper id, which outranks the name-derived key.
    @Test fun `tokens come out strongest evidence first`() {
        val all = entry(romCrc32 = "FF", ssId = 1, tgdbId = 2, igdbId = 3, sgdbId = 4, artworkKey = "k")
        assertEquals(listOf("crc:FF", "ss:1", "tgdb:2", "igdb:3", "sgdb:4", "key:k"), all.tokens())
    }

    @Test fun `crc tokens are case-insensitive on the hex`() {
        assertEquals(entry(romCrc32 = "a1b2c3d4").tokens(), entry(romCrc32 = "A1B2C3D4").tokens())
    }

    // ── Lookup ────────────────────────────────────────────────────────────────

    @Test fun `find locates an entry by platform, kind and portable name`() {
        val index = ArtworkIdentityIndex(entries = listOf(entry(), entry(kind = "HERO", ssId = 99)))
        assertEquals(1234L, index.find("snes", "ICON", "Final Fantasy VI")?.ssId)
        assertEquals(99L, index.find("snes", "HERO", "Final Fantasy VI")?.ssId)
    }

    // Relink lowercases portable names everywhere it matches them; the index must agree or a file
    // saved as "Final Fantasy VI" would not be found from "final fantasy vi".
    @Test fun `find is case-insensitive on name and platform`() {
        val index = ArtworkIdentityIndex(entries = listOf(entry()))
        assertNotNull(index.find("SNES", "ICON", "final fantasy vi"))
    }

    @Test fun `find returns null for a file with no row`() {
        val index = ArtworkIdentityIndex(entries = listOf(entry()))
        assertNull(index.find("snes", "ICON", "Chrono Trigger"))
        assertNull(index.find("snes", "LOGO", "Final Fantasy VI"))
    }

    // ── Upsert ────────────────────────────────────────────────────────────────

    @Test fun `upsert replaces the row for the same file rather than duplicating it`() {
        val index = ArtworkIdentityIndex(entries = listOf(entry()))
            .upsert(entry(ssId = 4321))
        assertEquals(1, index.entries.size)
        assertEquals(4321L, index.find("snes", "ICON", "Final Fantasy VI")?.ssId)
    }

    @Test fun `upsert keeps rows for other kinds of the same game`() {
        val index = ArtworkIdentityIndex(entries = listOf(entry()))
            .upsert(entry(kind = "HERO"))
        assertEquals(2, index.entries.size)
    }

    // ── Bulk upsert (task D.4 backfill) ───────────────────────────────────────

    @Test fun `upsertAll adds new rows and replaces matching ones in one pass`() {
        val index = ArtworkIdentityIndex(entries = listOf(entry(), entry(kind = "HERO")))
            .upsertAll(listOf(entry(ssId = 999), entry(kind = "LOGO")))

        assertEquals(3, index.entries.size)
        assertEquals(999L, index.find("snes", "ICON", "Final Fantasy VI")?.ssId)
        assertNotNull(index.find("snes", "LOGO", "Final Fantasy VI"))
    }

    @Test fun `upsertAll with nothing to add returns the same index`() {
        val index = ArtworkIdentityIndex(entries = listOf(entry()))
        assertEquals(index, index.upsertAll(emptyList()))
    }

    // Backfill runs on every relink, so a second scan over an unchanged library must produce an
    // identical index — otherwise it would rewrite the file on the SD card every time.
    @Test fun `upsertAll is idempotent`() {
        val rows = listOf(entry(), entry(kind = "HERO"))
        val once = ArtworkIdentityIndex().upsertAll(rows)
        assertEquals(once, once.upsertAll(rows))
    }

    @Test fun `later rows win within one upsertAll call`() {
        val index = ArtworkIdentityIndex().upsertAll(listOf(entry(ssId = 1), entry(ssId = 2)))
        assertEquals(1, index.entries.size)
        assertEquals(2L, index.entries.single().ssId)
    }
}
