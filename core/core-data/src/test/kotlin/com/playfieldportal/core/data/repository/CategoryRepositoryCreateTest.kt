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
import kotlin.test.assertTrue

/**
 * A new custom category never reuses an existing category's id.
 *
 * Survey L4 (docs/audits/codebase-survey-2026-10-22.md): the id was `custom_<slug>_<maxPosition+1>`
 * and was saved with a REPLACE upsert. After a reorder compacts positions and a delete lowers the
 * max, the same id comes back, and REPLACE deletes the old row — cascading away its items.
 */
class CategoryRepositoryCreateTest {

    private val categoryDao: CategoryDao = mockk(relaxed = true)
    private val repo = CategoryRepositoryImpl(
        categoryDao,
        mockk<DiscordSessionActivator>(relaxed = true),
        mockk<CollectionRepository>(relaxed = true),
        mockk<ListStateDao>(relaxed = true),
        mockk<CustomIconStore>(relaxed = true),
    )

    private fun row(id: String, position: Int) =
        CategoryEntity(id = id, name = id, iconKey = "ic", type = "MANUAL", position = position)

    @Test
    fun `a name whose next id is already taken gets a fresh id`() = runTest {
        // "Retro" was created at position 10, moved to 2, and the row at the end was deleted.
        val existing = (0..9).map { row("cat$it", it) }.toMutableList()
        existing[2] = row("custom_retro_10", 2)
        coEvery { categoryDao.getAll() } returns existing

        val id = repo.createCustomCategory("Retro", "ic_star")

        assertTrue(id !in existing.map { it.id }, "new id $id collides with an existing category")
        coVerify(exactly = 0) { categoryDao.upsert(match { it.id == "custom_retro_10" }) }
    }

    @Test
    fun `a new category is inserted without replacing anything`() = runTest {
        coEvery { categoryDao.getAll() } returns listOf(row("games", 0))

        val id = repo.createCustomCategory("Retro", "ic_star")

        assertEquals("custom_retro_1", id)
        coVerify { categoryDao.insertNew(match { it.id == id && it.position == 1 }) }
        coVerify(exactly = 0) { categoryDao.upsert(any()) }
    }
}
