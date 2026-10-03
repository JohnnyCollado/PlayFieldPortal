package com.playfieldportal.core.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * How one XMB list is arranged: which sort applies (the list's own, else the global one), where a
 * Custom order puts items it has never seen, and that a pin outranks every sort.
 */
class ListArrangementTest {

    private val identity: (String) -> String = { it }

    // ── Custom order ──────────────────────────────────────────────────────────

    @Test
    fun `no stored order keeps the default order`() {
        val items = listOf("Apple", "Banana", "Cherry")

        assertEquals(items, ListArrangement.customOrder(items, emptyMap(), identity))
    }

    @Test
    fun `stored items come back in their stored order`() {
        val items = listOf("Apple", "Banana", "Cherry")
        val positions = mapOf("Cherry" to 0, "Apple" to 1, "Banana" to 2)

        assertEquals(
            listOf("Cherry", "Apple", "Banana"),
            ListArrangement.customOrder(items, positions, identity),
        )
    }

    @Test
    fun `a new item slots in after its default-order neighbour`() {
        // Stored when the list was Apple, Cherry (user put Cherry first). Banana arrives later.
        val items = listOf("Apple", "Banana", "Cherry")
        val positions = mapOf("Cherry" to 0, "Apple" to 1)

        assertEquals(
            listOf("Cherry", "Apple", "Banana"),
            ListArrangement.customOrder(items, positions, identity),
        )
    }

    @Test
    fun `a new item with no earlier neighbour goes first`() {
        val items = listOf("Aardvark", "Banana", "Cherry")
        val positions = mapOf("Cherry" to 0, "Banana" to 1)

        assertEquals(
            listOf("Aardvark", "Cherry", "Banana"),
            ListArrangement.customOrder(items, positions, identity),
        )
    }

    @Test
    fun `several new items keep their default order among themselves`() {
        val items = listOf("A", "B", "C", "D")
        val positions = mapOf("D" to 0, "A" to 1)

        assertEquals(listOf("D", "A", "B", "C"), ListArrangement.customOrder(items, positions, identity))
    }

    @Test
    fun `stored keys whose item is gone are ignored`() {
        val items = listOf("Apple", "Cherry")
        val positions = mapOf("Cherry" to 0, "Deleted" to 1, "Apple" to 2)

        assertEquals(listOf("Cherry", "Apple"), ListArrangement.customOrder(items, positions, identity))
    }

    // ── Pins ──────────────────────────────────────────────────────────────────

    @Test
    fun `pinned items precede the rest and both groups keep their order`() {
        val items = listOf("A", "B", "C", "D")

        assertEquals(
            listOf("B", "D", "A", "C"),
            ListArrangement.pinnedFirst(items) { it == "B" || it == "D" },
        )
    }

    // ── Sort tiers ────────────────────────────────────────────────────────────

    @Test
    fun `a list with no override follows the global sort`() {
        assertEquals(
            ListSortMode.RECENT_PLAYED,
            ListArrangement.resolveSort(global = ListSortMode.RECENT_PLAYED, override = null),
        )
    }

    @Test
    fun `a list's own sort wins over the global sort`() {
        assertEquals(
            ListSortMode.CUSTOM,
            ListArrangement.resolveSort(global = ListSortMode.TITLE, override = ListSortMode.CUSTOM),
        )
    }

    @Test
    fun `custom is never a global sort`() {
        assertEquals(ListSortMode.TITLE, ListSortMode.asGlobal(ListSortMode.CUSTOM))
        assertEquals(ListSortMode.DATE_ADDED, ListSortMode.asGlobal(ListSortMode.DATE_ADDED))
    }

    @Test
    fun `an unknown stored sort name reads as no override`() {
        assertNull(ListSortMode.fromName("ALPHABETICAL"))
        assertNull(ListSortMode.fromName(null))
        assertEquals(ListSortMode.CUSTOM, ListSortMode.fromName("CUSTOM"))
    }

    // ── Keys ──────────────────────────────────────────────────────────────────

    @Test
    fun `a category's lists are its root, its memory card and its app list`() {
        assertEquals(
            listOf("root:custom_rpg_9", "catcard:custom_rpg_9", "apps:custom_rpg_9"),
            ListKeys.listsOfCategory("custom_rpg_9"),
        )
    }

    @Test
    fun `a collection is one list and one item under the same key`() {
        assertEquals("collection:5", ListKeys.collection(5))
        assertEquals("collection:5", ListKeys.collectionItem(5))
    }
}
