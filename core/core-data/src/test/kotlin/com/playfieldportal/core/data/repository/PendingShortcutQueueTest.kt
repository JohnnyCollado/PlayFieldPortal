package com.playfieldportal.core.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals

/** The waiting shortcut requests: asked about oldest first, never twice, never piling up. */
class PendingShortcutQueueTest {

    private fun request(id: String, name: String = id) =
        PendingShortcutRequest(id = id, name = name, intentUri = "intent:$id", hostLabel = "Chrome", requestedAt = 0)

    @Test
    fun `requests queue oldest first`() {
        val q = PendingShortcutQueue.enqueue(PendingShortcutQueue.enqueue(emptyList(), request("a")), request("b"))
        assertEquals(listOf("a", "b"), q.map { it.id })
    }

    @Test
    fun `the same id replaces its old entry and moves to the end`() {
        var q = listOf(request("a"), request("b"))
        q = PendingShortcutQueue.enqueue(q, request("a", name = "Gmail"))
        assertEquals(listOf("b", "a"), q.map { it.id })
        assertEquals("Gmail", q.last().name)
    }

    @Test
    fun `past the cap the oldest are dropped`() {
        var q = emptyList<PendingShortcutRequest>()
        (1..12).forEach { q = PendingShortcutQueue.enqueue(q, request("r$it")) }
        assertEquals(PendingShortcutQueue.MAX, q.size)
        assertEquals("r3", q.first().id)
    }

    @Test
    fun `remove takes out only that request`() {
        val q = PendingShortcutQueue.remove(listOf(request("a"), request("b")), "a")
        assertEquals(listOf("b"), q.map { it.id })
    }
}
