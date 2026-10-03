package com.playfieldportal.core.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The payload column outlives the build that wrote it, so the codec's rules are about survival:
 * a bad or foreign payload must read as "no detail" rather than crash the panel, and a huge one
 * must be cut down before it bloats a 200-row history.
 */
class NotificationDetailCodecTest {

    @Test
    fun `notes survive an encode and decode unchanged`() {
        val notes = NotificationDetail.Notes(
            summary = "DuckStation opened, but it never came to the front.",
            sections = listOf(NoteSection("Why", "It closed as it started.")),
            facts = listOf(NoteFact("Emulator", "DuckStation")),
            code = "LN-4003",
            diagnostic = "game=42\nemulator=duckstation",
        )
        assertEquals(notes, NotificationDetailCodec.decode(NotificationDetailCodec.encode(notes)))
    }

    @Test
    fun `results survive an encode and decode unchanged`() {
        val results = NotificationDetail.Results(
            summary = "61 added",
            items = listOf(
                ResultItem("PSP", ResultOutcome.FAILED, reason = "No access", code = "SC-2001",
                    path = "/storage/psp", action = DetailAction("open_memory_card", "psp")),
                ResultItem("SNES", ResultOutcome.DONE, badge = "+12"),
            ),
            labels = ResultsLabels(done = "Updated"),
            truncated = 3,
        )
        assertEquals(results, NotificationDetailCodec.decode(NotificationDetailCodec.encode(results)))
    }

    @Test
    fun `missing blank or broken payloads decode to null`() {
        assertNull(NotificationDetailCodec.decode(null))
        assertNull(NotificationDetailCodec.decode(""))
        assertNull(NotificationDetailCodec.decode("   "))
        assertNull(NotificationDetailCodec.decode("not json"))
        assertNull(NotificationDetailCodec.decode("""{"t":"hologram","x":1}"""))
    }

    @Test
    fun `unknown keys from a newer build are ignored`() {
        val json = """{"t":"notes","summary":"Hi","sparkle":true}"""
        assertEquals(NotificationDetail.Notes(summary = "Hi"), NotificationDetailCodec.decode(json))
    }

    @Test
    fun `results are capped with failures first and the rest counted`() {
        val items = (0 until 600).map { i ->
            ResultItem("game $i", if (i % 100 == 99) ResultOutcome.FAILED else ResultOutcome.DONE)
        }
        val results = NotificationDetail.results(items)

        assertEquals(NotificationDetail.MAX_ITEMS, results.items.size)
        assertEquals(100, results.truncated)
        assertTrue(results.items.take(6).all { it.outcome == ResultOutcome.FAILED }, "failures lead")
    }

    @Test
    fun `results order failures then skips then successes and keep input order within each`() {
        val results = NotificationDetail.results(
            listOf(
                ResultItem("a", ResultOutcome.DONE),
                ResultItem("b", ResultOutcome.SKIPPED),
                ResultItem("c", ResultOutcome.FAILED),
                ResultItem("d", ResultOutcome.FAILED),
            )
        )
        assertEquals(listOf("c", "d", "b", "a"), results.items.map { it.primary })
        assertEquals(0, results.truncated)
    }

    @Test
    fun `an oversized diagnostic is dropped to meet the byte cap`() {
        val notes = NotificationDetail.Notes(summary = "Boom", diagnostic = "x".repeat(200 * 1024))
        val encoded = NotificationDetailCodec.encode(notes)

        assertTrue(encoded.toByteArray().size <= NotificationDetailCodec.MAX_PAYLOAD_BYTES)
        val decoded = NotificationDetailCodec.decode(encoded) as NotificationDetail.Notes
        assertEquals("Boom", decoded.summary)
        assertNull(decoded.diagnostic)
    }

    @Test
    fun `oversized results are trimmed further and the trimmed items counted`() {
        val items = (0 until 500).map { ResultItem("item $it", ResultOutcome.FAILED, reason = "r".repeat(400)) }
        val encoded = NotificationDetailCodec.encode(NotificationDetail.Results(items = items))

        assertTrue(encoded.toByteArray().size <= NotificationDetailCodec.MAX_PAYLOAD_BYTES)
        val decoded = NotificationDetailCodec.decode(encoded) as NotificationDetail.Results
        assertEquals(500, decoded.items.size + decoded.truncated, "nothing is lost from the count")
        assertTrue(decoded.items.isNotEmpty())
    }
}
