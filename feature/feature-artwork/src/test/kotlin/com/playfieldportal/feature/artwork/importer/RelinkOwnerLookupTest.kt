package com.playfieldportal.feature.artwork.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** C18 task X.5 — who a library file belongs to during Scan & Relink. */
class RelinkOwnerLookupTest {

    private val asked = mutableListOf<String>()

    private fun fuzzy(answers: Map<String, List<Long>> = emptyMap()): (String) -> List<Long>? = { name ->
        asked += name
        answers[name]
    }

    private fun owners(
        fileName: String,
        fileStem: String,
        baseStem: String = fileStem,
        kind: String = "ICON",
        claims: Map<Triple<String, String, String>, Long> = emptyMap(),
        records: Map<Triple<String, String, String>, Set<Long>> = emptyMap(),
        fuzzyMatch: (String) -> List<Long>? = fuzzy(),
        identity: Map<String, List<Long>> = emptyMap(),
    ) = RelinkOwnerLookup.owners(
        "windows", kind, fileName, fileStem, baseStem, claims, records, fuzzyMatch,
    ) { stem -> identity[stem] }

    @Test
    fun `a claimed name reconnects to its game even when the fuzzy matcher would pick another`() {
        val ids = owners(
            fileName = "Portal 2 (Co-op).png",
            fileStem = "Portal 2 (Co-op)",
            claims = mapOf(Triple("windows", "ICON", "portal 2 (co-op)") to 12L),
            fuzzyMatch = fuzzy(mapOf("Portal 2 (Co-op).png" to listOf(99L))),
        )

        assertEquals(listOf(12L), ids)
        assertEquals("the claim answered, so the matcher is never asked", emptyList<String>(), asked)
    }

    @Test
    fun `a collision-suffixed name reconnects by its claim`() {
        val ids = owners(
            fileName = "Portal 2 (2).png",
            fileStem = "Portal 2 (2)",
            claims = mapOf(Triple("windows", "ICON", "portal 2 (2)") to 13L),
        )

        assertEquals(listOf(13L), ids)
    }

    @Test
    fun `an extra screenshot reconnects to its claimed base before any fuzzy guess`() {
        val ids = owners(
            fileName = "Portal 2_01.jpg",
            fileStem = "Portal 2_01",
            baseStem = "Portal 2",
            kind = "SCREENSHOT",
            claims = mapOf(Triple("windows", "SCREENSHOT", "portal 2") to 12L),
            fuzzyMatch = fuzzy(mapOf("Portal 2_01.jpg" to listOf(99L))),
        )

        assertEquals(listOf(12L), ids)
    }

    @Test
    fun `a claim is for its own kind and platform only`() {
        val claims = mapOf(Triple("windows", "HERO", "portal 2") to 12L)

        assertNull(owners(fileName = "Portal 2.png", fileStem = "Portal 2", claims = claims))
        assertNull(
            RelinkOwnerLookup.owners("psx", "HERO", "Portal 2.png", "Portal 2", "Portal 2", claims, emptyMap(), fuzzy()),
        )
    }

    @Test
    fun `an unclaimed file still reaches the fuzzy matcher`() {
        val ids = owners(
            fileName = "Half-Life.png",
            fileStem = "Half-Life",
            claims = mapOf(Triple("windows", "ICON", "portal 2") to 12L),
            fuzzyMatch = fuzzy(mapOf("Half-Life.png" to listOf(7L))),
        )

        assertEquals(listOf(7L), ids)
        assertEquals(listOf("Half-Life.png"), asked)
    }

    @Test
    fun `with no claims the order is unchanged - record, fuzzy, record on the base, fuzzy on the base`() {
        val recordOnBase = mapOf(Triple("windows", "SCREENSHOT", "portal 2") to setOf(5L))

        val ids = owners(
            fileName = "Portal 2_07.jpg",
            fileStem = "Portal 2_07",
            baseStem = "Portal 2",
            kind = "SCREENSHOT",
            records = recordOnBase,
        )

        assertEquals(listOf(5L), ids)
        assertEquals("the full name is tried before the base", listOf("Portal 2_07.jpg"), asked)
    }

    @Test
    fun `a record on the full name outranks a claim on the base`() {
        val ids = owners(
            fileName = "Tetris_07.png",
            fileStem = "Tetris_07",
            baseStem = "Tetris",
            kind = "SCREENSHOT",
            claims = mapOf(Triple("windows", "SCREENSHOT", "tetris") to 12L),
            records = mapOf(Triple("windows", "SCREENSHOT", "tetris_07") to setOf(3L)),
        )

        assertEquals(listOf(3L), ids)
    }

    @Test
    fun `the base falls back to the fuzzy matcher with the file's extension`() {
        val ids = owners(
            fileName = "Portal 2_03.webp",
            fileStem = "Portal 2_03",
            baseStem = "Portal 2",
            kind = "SCREENSHOT",
            fuzzyMatch = fuzzy(mapOf("Portal 2.webp" to listOf(8L))),
        )

        assertEquals(listOf(8L), ids)
        assertEquals(listOf("Portal 2_03.webp", "Portal 2.webp"), asked)
    }

    @Test
    fun `nothing that claims, records or matches the file is no owner`() {
        assertNull(owners(fileName = "Unknown.png", fileStem = "Unknown"))
    }

    // ── Durable identity (C16 task D.3) ───────────────────────────────────────

    // The bug this tier exists for: the ROM was renamed, so the file's name matches nothing, but
    // the identity index still knows which game's ids it was written for.
    @Test
    fun `identity reconnects a file whose name no longer matches anything`() {
        val ids = owners(
            fileName = "Final Fantasy VI.png",
            fileStem = "Final Fantasy VI",
            identity = mapOf("Final Fantasy VI" to listOf(7L)),
        )

        assertEquals(listOf(7L), ids)
        assertEquals("identity answered, so the matcher is never asked", emptyList<String>(), asked)
    }

    @Test
    fun `identity outranks a claim, a record and the fuzzy matcher`() {
        val ids = owners(
            fileName = "Portal 2.png",
            fileStem = "Portal 2",
            claims = mapOf(Triple("windows", "ICON", "portal 2") to 12L),
            records = mapOf(Triple("windows", "ICON", "portal 2") to setOf(34L)),
            fuzzyMatch = fuzzy(mapOf("Portal 2.png" to listOf(99L))),
            identity = mapOf("Portal 2" to listOf(7L)),
        )

        assertEquals(listOf(7L), ids)
    }

    // An index row naming a game that no longer exists must not swallow the file: relink hands back
    // an empty owner list for it, and the name tiers below still get their turn.
    @Test
    fun `an identity row for a departed game falls through to the name tiers`() {
        val ids = owners(
            fileName = "Portal 2.png",
            fileStem = "Portal 2",
            claims = mapOf(Triple("windows", "ICON", "portal 2") to 12L),
            identity = mapOf("Portal 2" to emptyList()),
        )

        assertEquals(listOf(12L), ids)
    }

    // A library written before D.2 has no rows at all; nothing about its matching may change.
    @Test
    fun `no identity at all behaves exactly as before`() {
        val ids = owners(
            fileName = "Portal 2.png",
            fileStem = "Portal 2",
            records = mapOf(Triple("windows", "ICON", "portal 2") to setOf(34L)),
        )

        assertEquals(listOf(34L), ids)
    }

    // Multi-asset kinds store the ordinal in the name ("Name_02"), so the full stem is tried before
    // the base — the same rule the name tiers follow, for the same reason.
    @Test
    fun `identity on the full stem wins over identity on the base`() {
        val ids = owners(
            fileName = "Halo_02.png",
            fileStem = "Halo_02",
            baseStem = "Halo",
            kind = "SCREENSHOT",
            identity = mapOf("Halo_02" to listOf(5L), "Halo" to listOf(6L)),
        )

        assertEquals(listOf(5L), ids)
    }

    @Test
    fun `identity on the base reconnects an ordinal file whose own stem has no row`() {
        val ids = owners(
            fileName = "Halo_02.png",
            fileStem = "Halo_02",
            baseStem = "Halo",
            kind = "SCREENSHOT",
            identity = mapOf("Halo" to listOf(6L)),
        )

        assertEquals(listOf(6L), ids)
    }
}
