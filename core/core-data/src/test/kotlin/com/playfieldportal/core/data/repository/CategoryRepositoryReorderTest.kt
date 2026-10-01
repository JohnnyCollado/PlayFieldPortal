package com.playfieldportal.core.data.repository

import com.playfieldportal.core.data.database.dao.CategoryDao
import com.playfieldportal.core.data.database.dao.ListStateDao
import com.playfieldportal.core.data.database.entity.CategoryEntity
import com.playfieldportal.core.domain.discord.DiscordSessionActivator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Saving the order a live Move left on the crossbar. The bar shows only the visible categories,
 * so the hidden ones must keep the slots they had among them rather than all drifting to one end.
 */
class CategoryRepositoryReorderTest {

    @Test
    fun `the bar's new order fills the visible slots and hidden ones stay put`() {
        // Video is hidden between Music and Game; Game moves to the front of the bar.
        val all = listOf("settings", "music", "videos", "games", "network")
        val bar = listOf("games", "settings", "music", "network")
        assertEquals(
            listOf("games", "settings", "videos", "music", "network"),
            CategoryRepositoryImpl.mergeBarOrder(all, bar),
        )
    }

    @Test
    fun `an unchanged bar leaves the stored order alone`() {
        val all = listOf("settings", "music", "videos", "games")
        assertEquals(all, CategoryRepositoryImpl.mergeBarOrder(all, listOf("settings", "music", "games")))
    }

    @Test
    fun `an id the store does not know is ignored`() {
        // The bar always shows Settings even when its row is missing; nothing to write for it.
        val all = listOf("music", "games")
        assertEquals(listOf("games", "music"), CategoryRepositoryImpl.mergeBarOrder(all, listOf("settings", "games", "music")))
    }

    @Test
    fun `reorder writes a compact position for every category`() = runTest {
        val categoryDao: CategoryDao = mockk(relaxed = true)
        val repo = CategoryRepositoryImpl(
            categoryDao,
            mockk<DiscordSessionActivator>(relaxed = true),
            mockk<CollectionRepository>(relaxed = true),
            mockk<ListStateDao>(relaxed = true),
        )
        fun row(id: String, position: Int) =
            CategoryEntity(id = id, name = id, iconKey = "ic", type = "BUILT_IN", position = position)
        coEvery { categoryDao.getAll() } returns listOf(row("settings", 0), row("music", 2), row("games", 4))

        repo.reorder(listOf("games", "settings", "music"))

        coVerify { categoryDao.updatePosition("games", 0) }
        coVerify { categoryDao.updatePosition("settings", 1) }
        coVerify { categoryDao.updatePosition("music", 2) }
    }
}
