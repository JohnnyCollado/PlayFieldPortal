package com.playfieldportal.core.data.repository

import com.playfieldportal.core.data.database.dao.CategoryDao
import com.playfieldportal.core.data.database.dao.ListStateDao
import com.playfieldportal.core.data.database.entity.CategoryEntity
import com.playfieldportal.core.domain.discord.DiscordSessionActivator
import com.playfieldportal.core.domain.model.BuiltInCategory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Deleting a custom category. Its custom memory cards used to be left pointing at a category that
 * no longer existed, so they vanished from the XMB. Now the caller chooses: they move to the
 * matching home category, or they are deleted — and either way no game leaves the library.
 */
class CategoryRepositoryDeleteTest {

    private val categoryDao: CategoryDao = mockk(relaxed = true)
    private val collections: CollectionRepository = mockk(relaxed = true)
    private val listStateDao: ListStateDao = mockk(relaxed = true)
    private val discord: DiscordSessionActivator = mockk(relaxed = true)
    private val repo = CategoryRepositoryImpl(categoryDao, discord, collections, listStateDao)

    private fun category(id: String, gaming: Boolean) = CategoryEntity(
        id = id, name = id, iconKey = "ic_games", type = "MANUAL", position = 9, isGamingCategory = gaming,
    )

    @Test
    fun `moving keeps a gaming category's custom cards by rehoming them to Main Game`() = runTest {
        coEvery { categoryDao.getById("custom_ff_5") } returns category("custom_ff_5", gaming = true)

        assertTrue(repo.delete("custom_ff_5", CollectionsOnDelete.MOVE))

        coVerifyOrder {
            collections.rehomeAll("custom_ff_5", BuiltInCategory.GAMES)
            categoryDao.deleteById("custom_ff_5")
        }
        coVerify(exactly = 0) { collections.deleteAllIn(any()) }
    }

    @Test
    fun `moving an app category's custom cards rehomes them to the App Store column`() = runTest {
        coEvery { categoryDao.getById("custom_stream_6") } returns category("custom_stream_6", gaming = false)

        repo.delete("custom_stream_6", CollectionsOnDelete.MOVE)

        coVerify { collections.rehomeAll("custom_stream_6", "app_store") }
    }

    @Test
    fun `deleting the custom cards too removes them before the category`() = runTest {
        coEvery { categoryDao.getById("custom_ff_5") } returns category("custom_ff_5", gaming = true)

        repo.delete("custom_ff_5", CollectionsOnDelete.DELETE)

        coVerifyOrder {
            collections.deleteAllIn("custom_ff_5")
            categoryDao.deleteById("custom_ff_5")
        }
        coVerify(exactly = 0) { collections.rehomeAll(any(), any()) }
    }

    @Test
    fun `the deleted category's stored list arrangement is forgotten`() = runTest {
        coEvery { categoryDao.getById("custom_ff_5") } returns category("custom_ff_5", gaming = true)

        repo.delete("custom_ff_5", CollectionsOnDelete.MOVE)

        coVerify {
            listStateDao.deleteLists(listOf("root:custom_ff_5", "catcard:custom_ff_5", "apps:custom_ff_5"))
        }
    }

    @Test
    fun `a protected built-in is never deleted and nothing of it is touched`() = runTest {
        assertFalse(repo.delete(BuiltInCategory.GAMES, CollectionsOnDelete.DELETE))

        coVerify(exactly = 0) { categoryDao.deleteById(any()) }
        coVerify(exactly = 0) { collections.deleteAllIn(any()) }
        coVerify(exactly = 0) { collections.rehomeAll(any(), any()) }
    }

    @Test
    fun `the home for rehomed cards matches the category's kind`() {
        assertEquals(BuiltInCategory.GAMES, CategoryRepositoryImpl.collectionHomeFor(isGamingCategory = true))
        assertEquals("app_store", CategoryRepositoryImpl.collectionHomeFor(isGamingCategory = false))
    }
}
