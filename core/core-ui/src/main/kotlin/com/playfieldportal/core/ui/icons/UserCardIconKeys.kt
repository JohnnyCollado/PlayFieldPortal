package com.playfieldportal.core.ui.icons

/**
 * Store keys for a custom memory card's own image: `usercard_<collectionId>` in the custom icon
 * store — the card counterpart of [UserCategoryIconKeys].
 *
 * Keys become file names under `custom-icons/`, so only a positive decimal id with no leading zero
 * is admitted: no `/`, `.` or `-` can reach a path. Device-local like a category image: not a
 * themeable slot, so theme export and Theme Studio never see these, and a theme cannot supply one.
 */
object UserCardIconKeys {

    const val PREFIX = "usercard_"

    private val COLLECTION_ID = Regex("^[1-9][0-9]{0,18}$")

    /** The store key for [collectionId], or null when it is not a real card id. */
    fun keyFor(collectionId: Long): String? = if (collectionId > 0) PREFIX + collectionId else null

    /** True for a key [keyFor] could have produced. */
    fun isValidKey(key: String): Boolean = collectionIdFor(key) != null

    /** Inverse of [keyFor]; null for anything that is not a valid key. */
    fun collectionIdFor(key: String): Long? =
        key.removePrefix(PREFIX)
            .takeIf { key.startsWith(PREFIX) && COLLECTION_ID.matches(it) }
            ?.toLongOrNull()
}
