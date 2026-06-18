package com.playfieldportal.core.data.repository

import com.playfieldportal.core.data.database.dao.CategoryDao
import com.playfieldportal.core.data.database.dao.PlatformDao
import com.playfieldportal.core.data.database.entity.CategoryItemEntity
import com.playfieldportal.core.data.database.entity.toDomain
import com.playfieldportal.core.data.database.entity.toEntity
import com.playfieldportal.core.domain.model.BuiltInCategory
import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.domain.model.CategoryType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject

class CategoryRepositoryImpl @Inject constructor(
    private val categoryDao: CategoryDao,
    private val platformDao: PlatformDao,
) {
    fun observeVisible(): Flow<List<Category>> =
        combine(
            categoryDao.observeVisible(),
            platformDao.observePinnedToBar(),
        ) { categoryEntities, pinnedPlatforms ->
            val categories = categoryEntities.map { it.toDomain() }
            val gamesPosition = categories
                .firstOrNull { it.id == BuiltInCategory.GAMES }
                ?.position
                ?: 2

            val pinnedCategories = pinnedPlatforms.mapIndexed { index, platform ->
                Category(
                    id = platform.id,
                    name = platform.name,
                    iconKey = platform.iconRes ?: "ic_platform_${platform.id}",
                    accentColor = platform.accentColor,
                    type = CategoryType.PLATFORM,
                    position = gamesPosition + 1 + index,
                )
            }

            val beforePinned = categories.filter { it.position <= gamesPosition }
            val afterPinned = categories
                .filter { it.position > gamesPosition }
                .map { it.copy(position = it.position + pinnedCategories.size) }

            beforePinned + pinnedCategories + afterPinned
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
            Category(BuiltInCategory.FAVORITES,       "Favorites",       "ic_favorites",      type = CategoryType.BUILT_IN, position = 0),
            Category(BuiltInCategory.RECENTLY_PLAYED, "Recently Played", "ic_recent",         type = CategoryType.BUILT_IN, position = 1),
            Category(BuiltInCategory.GAMES,           "Games",           "ic_games",          type = CategoryType.BUILT_IN, position = 2),
            Category(BuiltInCategory.ANDROID,         "Android",         "ic_android",        type = CategoryType.BUILT_IN, position = 3),
            Category(BuiltInCategory.APP_DRAWER,      "App Drawer",      "ic_apps",           type = CategoryType.BUILT_IN, position = 4),
            Category(BuiltInCategory.SETTINGS,        "Settings",        "ic_settings",       type = CategoryType.BUILT_IN, position = 5),
        )
        categoryDao.insertAll(builtIns.map { it.toEntity() })
        Timber.i("Built-in categories seeded")
    }
}
