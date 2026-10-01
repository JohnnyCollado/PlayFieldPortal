package com.playfieldportal.core.domain.model

// How one XMB list is ordered. TITLE / RECENT_PLAYED / DATE_ADDED are automatic; CUSTOM is the
// user's own arrangement, saved per list. Names match XmbSortMode's so the two map by name.
enum class ListSortMode {
    TITLE,
    RECENT_PLAYED,
    DATE_ADDED,
    CUSTOM;

    companion object {
        fun fromName(name: String?): ListSortMode? = entries.firstOrNull { it.name == name }

        /** CUSTOM belongs to one list, so it can never be the global setting. */
        fun asGlobal(mode: ListSortMode): ListSortMode = if (mode == CUSTOM) TITLE else mode
    }
}

/**
 * Keys for the per-list state tables. A list key names one list; an item key names one row in it.
 * Both are plain strings so lists with no junction table — a Memory Card, All Games, a column's
 * root — can be arranged like any other.
 */
object ListKeys {
    const val ALL_GAMES = "all_games"
    const val FAVORITES = "favorites"

    fun root(categoryId: String) = "root:$categoryId"
    fun card(platformId: String) = "card:$platformId"
    fun categoryCard(categoryId: String) = "catcard:$categoryId"
    fun collection(collectionId: Long) = "collection:$collectionId"
    fun apps(sectionId: String) = "apps:$sectionId"

    fun game(gameId: Long) = "game:$gameId"
    fun app(packageName: String) = "app:$packageName"
    fun collectionItem(collectionId: Long) = "collection:$collectionId"
    fun cardItem(platformId: String) = "card:$platformId"

    // Root rows that are not backed by a record of their own.
    const val ROW_ALL_GAMES = "row:all_games"
    const val ROW_FAVORITES = "row:favorites"
    const val ROW_MISSING = "row:missing"
    const val ROW_CATEGORY_CARD = "row:catcard"

    /** Every list a category owns — what to forget when the category is deleted. */
    fun listsOfCategory(categoryId: String): List<String> =
        listOf(root(categoryId), categoryCard(categoryId), apps(categoryId))
}

/** One list's stored arrangement: each item's Custom slot, and which items are pinned. */
data class ListState(
    val positions: Map<String, Int> = emptyMap(),
    val pinned: Set<String> = emptySet(),
) {
    companion object {
        val EMPTY = ListState()
    }
}

object ListArrangement {

    fun resolveSort(global: ListSortMode, override: ListSortMode?): ListSortMode = override ?: global

    /**
     * [items] (given in the list's default order) rearranged by the stored Custom [positions].
     * An item the order has never seen goes right after the item that precedes it in the default
     * order, so a new entry lands beside its alphabetical neighbour instead of at the bottom.
     */
    fun <T> customOrder(items: List<T>, positions: Map<String, Int>, key: (T) -> String): List<T> {
        if (positions.isEmpty()) return items
        val stored = items.filter { key(it) in positions }.sortedBy { positions.getValue(key(it)) }
        if (stored.size == items.size) return stored

        val result = stored.toMutableList()
        var previous: T? = null
        for (item in items) {
            if (key(item) !in positions) {
                val at = previous?.let { result.indexOf(it) + 1 } ?: 0
                result.add(at, item)
            }
            previous = item
        }
        return result
    }

    /** Pinned items first; each group keeps the order it came in. */
    fun <T> pinnedFirst(items: List<T>, isPinned: (T) -> Boolean): List<T> {
        val (pinned, rest) = items.partition(isPinned)
        return pinned + rest
    }
}

object UmdSlotResolver {

    /**
     * The game a column's UMD slot shows. [columnGames] is the column's displayable games (disc
     * sets already projected to one row). [inserted] wins while it — or its disc set — is still
     * there; otherwise the most recently played game; otherwise none.
     */
    fun resolve(inserted: Game?, columnGames: List<Game>): Game? {
        inserted?.let { pick ->
            columnGames.firstOrNull {
                it.id == pick.id || (pick.discSetKey != null && it.discSetKey == pick.discSetKey)
            }?.let { return it }
        }
        return columnGames.filter { it.lastPlayedAt != null }.maxByOrNull { it.lastPlayedAt ?: 0L }
    }
}
