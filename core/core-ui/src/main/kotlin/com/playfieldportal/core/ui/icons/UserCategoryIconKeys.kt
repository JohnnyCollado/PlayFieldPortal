package com.playfieldportal.core.ui.icons

/**
 * Store keys for user-picked category images: `usercat_<categoryId>` in the custom icon store.
 *
 * The strict pattern is load-bearing, not cosmetic. Keys are used verbatim as file names under
 * `custom-icons/`, so admitting only `custom_[a-z0-9_]{1,120}` (the shape `createCustomCategory`
 * produces) means no `/`, `.` or `..` can reach a path, and the length cap keeps the name well
 * under the 255-byte file-name limit. An id that doesn't match gets no key — fail closed.
 *
 * Deliberately NOT part of CustomizableIcons / IconSlots: these images are device-local, so
 * theme export, theme import gating and Theme Studio never see them.
 */
object UserCategoryIconKeys {

    const val PREFIX = "usercat_"

    /** Holds the image picked during category creation, before the category has an id. */
    const val DRAFT_KEY = "usercat_draft"

    // Real keys always start `usercat_custom_`, so the draft key cannot collide with one.
    private val CATEGORY_ID = Regex("^custom_[a-z0-9_]{1,120}$")

    /** The store key for [categoryId], or null when the id isn't a user-created category id. */
    fun keyFor(categoryId: String): String? =
        if (CATEGORY_ID.matches(categoryId)) PREFIX + categoryId else null

    /** True for a key [keyFor] could have produced, or [DRAFT_KEY]. */
    fun isValidKey(key: String): Boolean =
        key == DRAFT_KEY || (key.startsWith(PREFIX) && categoryIdFor(key) != null)

    /** Inverse of [keyFor]; null for the draft key and for anything that isn't a valid key. */
    fun categoryIdFor(key: String): String? =
        key.removePrefix(PREFIX).takeIf { key.startsWith(PREFIX) && CATEGORY_ID.matches(it) }
}
