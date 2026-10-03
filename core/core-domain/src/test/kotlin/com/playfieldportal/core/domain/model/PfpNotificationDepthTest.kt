package com.playfieldportal.core.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Depth decides what Confirm does: a Simple row acts, a Notes or Results row opens its sheet.
 * Every row with something to say must end up readable, including rows written before payloads.
 */
class PfpNotificationDepthTest {

    private fun row(
        title: String = "Done",
        body: String? = null,
        action: NotificationAction = NotificationAction.None,
        payload: String? = null,
    ) = PfpNotification(
        id = 1, kind = NotificationKind.SCAN, severity = NotificationSeverity.INFO,
        title = title, body = body, action = action, payload = payload, createdAt = 0,
    )

    @Test
    fun `a payload decides the depth`() {
        val notes = NotificationDetailCodec.encode(NotificationDetail.Notes(summary = "Why"))
        val results = NotificationDetailCodec.encode(
            NotificationDetail.Results(items = listOf(ResultItem("a", ResultOutcome.DONE)))
        )
        assertEquals(NotificationDepth.NOTES, row(payload = notes).depth)
        assertEquals(NotificationDepth.RESULTS, row(payload = results).depth)
    }

    @Test
    fun `a row with no payload and nothing more to say is simple`() {
        assertEquals(NotificationDepth.SIMPLE, row().depth)
        assertEquals(NotificationDepth.SIMPLE, row(body = "  ").depth)
    }

    @Test
    fun `a body with nowhere to go becomes a one section note`() {
        val r = row(body = "3 item(s) skipped")
        assertEquals(NotificationDepth.NOTES, r.depth)
        assertEquals("3 item(s) skipped", assertIs<NotificationDetail.Notes>(r.detail).summary)
    }

    @Test
    fun `a simple row with an action keeps its body visible in the title`() {
        val r = row(title = "Music scan finished", body = "120 tracks",
            action = NotificationAction.OpenCategory("music"))
        assertEquals(NotificationDepth.SIMPLE, r.depth)
        assertEquals("Music scan finished — 120 tracks", r.displayTitle)
    }

    @Test
    fun `a detail row shows its title alone`() {
        val notes = NotificationDetailCodec.encode(NotificationDetail.Notes(summary = "Why"))
        val r = row(title = "Couldn't launch Ape Escape", body = "never foregrounded",
            action = NotificationAction.OpenGame(4), payload = notes)
        assertEquals("Couldn't launch Ape Escape", r.displayTitle)
    }

    @Test
    fun `a broken payload falls back as if there were none`() {
        assertEquals(NotificationDepth.SIMPLE, row(payload = "{oops").depth)
    }
}
