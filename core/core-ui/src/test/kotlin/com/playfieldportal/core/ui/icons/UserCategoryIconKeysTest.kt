package com.playfieldportal.core.ui.icons

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `usercat_` key family is the only thing standing between a category id and a file name in
 * `custom-icons/`, so the pattern's rejections are as important as its accepts.
 */
class UserCategoryIconKeysTest {

    @Test
    fun `keyFor prefixes a created custom category id`() {
        assertEquals("usercat_custom_retro_shelf_9", UserCategoryIconKeys.keyFor("custom_retro_shelf_9"))
    }

    @Test
    fun `keyFor is null for built-in, legacy, malformed and over-long ids`() {
        val rejected = listOf(
            "games", "settings", "app_store", "music_apps", "",
            "Custom_x", "custom_A", "custom_../x", "custom_a/b", "custom_a.b", "custom_", "custom_a b",
            "custom_" + "a".repeat(121),
        )
        for (id in rejected) assertNull(UserCategoryIconKeys.keyFor(id), "id '$id' must not get a key")
    }

    @Test
    fun `keyFor accepts an id exactly at the length cap`() {
        val id = "custom_" + "a".repeat(120)
        assertEquals("usercat_$id", UserCategoryIconKeys.keyFor(id))
    }

    @Test
    fun `isValidKey accepts every keyFor output and the draft key`() {
        assertTrue(UserCategoryIconKeys.isValidKey(UserCategoryIconKeys.keyFor("custom_retro_shelf_9")!!))
        assertTrue(UserCategoryIconKeys.isValidKey(UserCategoryIconKeys.DRAFT_KEY))
    }

    @Test
    fun `isValidKey rejects bare prefix, built-ins, traversal, extensions and other families`() {
        for (key in listOf(
            "usercat_", "usercat_games", "usercat_custom_../x", "usercat_custom_x.png",
            "usercat_custom_A", "catbar_games", "", "custom_x_1", "usercat_draft_2",
        )) {
            assertFalse(UserCategoryIconKeys.isValidKey(key), "key '$key' must be rejected")
        }
    }

    @Test
    fun `categoryIdFor inverts keyFor and is null for the draft and invalid keys`() {
        assertEquals("custom_retro_shelf_9", UserCategoryIconKeys.categoryIdFor("usercat_custom_retro_shelf_9"))
        assertNull(UserCategoryIconKeys.categoryIdFor(UserCategoryIconKeys.DRAFT_KEY))
        assertNull(UserCategoryIconKeys.categoryIdFor("usercat_games"))
        assertNull(UserCategoryIconKeys.categoryIdFor("catbar_games"))
    }
}
