package com.playfieldportal.core.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun `take removes the request only when it is exactly the one shown`() {
        val shown = request("a")
        val (queue, taken) = PendingShortcutQueue.take(listOf(shown, request("b")), shown)
        assertEquals(shown, taken)
        assertEquals(listOf("b"), queue.map { it.id })
    }

    @Test
    fun `take refuses a request replaced under the same id while it was on screen`() {
        val shown = request("a")
        val replaced = shown.copy(intentUri = "intent:other", requestedAt = 1)
        val (queue, taken) = PendingShortcutQueue.take(listOf(replaced), shown)
        assertNull(taken)
        // The replacement stays queued, so it is reviewed on its own.
        assertEquals(listOf(replaced), queue)
    }

    @Test
    fun `ids are a collision-resistant digest of the intent`() {
        val id = PendingShortcutRequest.idFor("intent:#Intent;end")
        assertEquals(64, id.length)
        assertTrue(id.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(id, PendingShortcutRequest.idFor("intent:#Intent;end"))
        // "Aa" and "BB" share a String.hashCode(); the old hashCode() ids collided on them.
        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertNotEquals(PendingShortcutRequest.idFor("Aa"), PendingShortcutRequest.idFor("BB"))
    }

    @Test
    fun `take of a request that is already gone takes nothing`() {
        val (queue, taken) = PendingShortcutQueue.take(listOf(request("b")), request("a"))
        assertNull(taken)
        assertFalse(queue.isEmpty())
    }
}
