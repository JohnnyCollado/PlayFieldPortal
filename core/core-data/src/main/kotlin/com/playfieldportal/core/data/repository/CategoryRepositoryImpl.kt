package com.playfieldportal.core.data.repository

import com.playfieldportal.core.data.database.dao.CategoryDao
import com.playfieldportal.core.data.database.entity.CategoryItemEntity
import com.playfieldportal.core.data.database.entity.toDomain
import com.playfieldportal.core.data.database.entity.toEntity
import com.playfieldportal.core.domain.model.BuiltInCategory
import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.domain.model.CategoryType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject

class CategoryRepositoryImpl @Inject constructor(
    private val categoryDao: CategoryDao,
) {
    fun observeVisible(): Flow<List<Category>> =
        categoryDao.observeVisible().map { categoryEntities ->
            categoryEntities.map { it.toDomain() }
        }

    fun observeAll(): Flow<List<Category>> =
        categoryDao.observeAll().map { it.map { entity -> entity.toDomain() } }

    suspend fun upsert(category: Category) =
        categoryDao.upsert(category.toEntity())

    suspend fun delete(id: String) {
        // Protect built-in categories from deletion
        if (id in setOf(
                BuiltInCategory.FAVORITES,
                BuiltInCategory.RECENTLY_PLAYED,
                BuiltInCategory.GAMES,
                BuiltInCategory.ANDROID,
                BuiltInCategory.APP_DRAWER,
                BuiltInCategory.SETTINGS,
                "photos",
                "music",
                "videos",
                "network",
            )
        ) {
            Timber.w("Attempted to delete built-in category '$id' — blocked")
            return
        }
        categoryDao.deleteById(id)
    }

    suspend fun updatePosition(id: String, position: Int) =
        categoryDao.updatePosition(id, position)

    suspend fun setVisible(id: String, visible: Boolean) =
        categoryDao.setVisible(id, visible)

    suspend fun addItemToCategory(categoryId: String, itemId: String, itemType: String, order: Int = 0) =
        categoryDao.addItem(CategoryItemEntity(categoryId, itemId, itemType, order))

    suspend fun removeItemFromCategory(categoryId: String, itemId: String) =
        categoryDao.removeItem(categoryId, itemId)

    fun observeCategoryItems(categoryId: String) =
        categoryDao.observeItemsForCategory(categoryId)

    // Seeds built-in categories on first launch — idempotent
    suspend fun seedBuiltInCategories() {
        val builtIns = listOf(
            Category(BuiltInCategory.SETTINGS, "Settings", "ic_settings", type = CategoryType.BUILT_IN, position = 0),
            Category("photos",                 "Photos",   "ic_photos",   type = CategoryType.BUILT_IN, position = 1),
            Category("music",                  "Music",    "ic_music",    type = CategoryType.BUILT_IN, position = 2),
            Category("videos",                 "Videos",   "ic_videos",   type = CategoryType.BUILT_IN, position = 3),
            Category(BuiltInCategory.GAMES,    "Games",    "ic_games",    type = CategoryType.BUILT_IN, position = 4),
            Category("network",                "Network",  "ic_network",  type = CategoryType.BUILT_IN, position = 5),
        )
        categoryDao.insertAll(builtIns.map { it.toEntity() })
        Timber.i("Built-in categories seeded")
    }
}
