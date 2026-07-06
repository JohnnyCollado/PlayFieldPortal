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

/**
 * CRUD and ordering for XMB categories (the horizontal bar). Seeds the built-in categories on first
 * run and reconciles their system-defined flags every launch so definition changes reach databases
 * seeded by older builds. Custom categories are fully editable; built-ins are protected from
 * deletion.
 */
class CategoryRepositoryImpl @Inject constructor(
    private val categoryDao: CategoryDao,
    private val discordSessionActivator: com.playfieldportal.core.domain.discord.DiscordSessionActivator,
) {
    // Built-ins to seed/reconcile — the Social column is dropped in the "lite" build (no Discord SDK),
    // so it never appears in the XMB, the Category Manager, or backups there.
    private fun builtInCategories(): List<Category> =
        BUILT_IN_CATEGORIES.filter { it.id != BuiltInCategory.SOCIAL || discordSessionActivator.sdkAvailable }

    fun observeVisible(): Flow<List<Category>> =
        categoryDao.observeVisible().map { categoryEntities ->
            categoryEntities.map { it.toDomain() }
        }

    fun observeAll(): Flow<List<Category>> =
        categoryDao.observeAll().map { it.map { entity -> entity.toDomain() } }

    suspend fun upsert(category: Category) =
        categoryDao.upsert(category.toEntity())

    suspend fun delete(id: String) {
        if (id in PROTECTED_BUILTINS) {
            Timber.w("Attempted to delete built-in category '$id' — blocked")
            return
        }
        categoryDao.deleteById(id)   // category_items rows cascade-delete
    }

    fun isProtected(id: String): Boolean = id in PROTECTED_BUILTINS

    suspend fun updatePosition(id: String, position: Int) =
        categoryDao.updatePosition(id, position)

    suspend fun setVisible(id: String, visible: Boolean) =
        categoryDao.setVisible(id, visible)

    suspend fun setGamingCategory(id: String, isGaming: Boolean) {
        val existing = categoryDao.getById(id) ?: return
        categoryDao.update(existing.copy(isGamingCategory = isGaming))
    }

    suspend fun rename(id: String, name: String) {
        val existing = categoryDao.getById(id) ?: return
        categoryDao.update(existing.copy(name = name))
    }

    suspend fun setIcon(id: String, iconKey: String) {
        val existing = categoryDao.getById(id) ?: return
        categoryDao.update(existing.copy(iconKey = iconKey))
    }

    // Creates a user category appended after the existing ones. Returns its generated id.
    suspend fun createCustomCategory(name: String, iconKey: String, isGamingCategory: Boolean = false): String {
        val maxPosition = categoryDao.getAll().maxOfOrNull { it.position } ?: -1
        val id = "custom_" + name.trim().lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { System.currentTimeMillis().toString() } + "_" + (maxPosition + 1)
        upsert(
            Category(
                id                 = id,
                name               = name.trim(),
                iconKey            = iconKey,
                type               = CategoryType.MANUAL,
                position           = maxPosition + 1,
                isGamingCategory   = isGamingCategory,
            )
        )
        Timber.i("Custom category created: $id ($name, isGaming=$isGamingCategory)")
        return id
    }

    // Swaps position with the adjacent category in the given direction. Returns true on move.
    suspend fun move(id: String, up: Boolean): Boolean {
        val ordered = categoryDao.getAll().sortedBy { it.position }
        val index = ordered.indexOfFirst { it.id == id }
        if (index < 0) return false
        val targetIndex = if (up) index - 1 else index + 1
        if (targetIndex !in ordered.indices) return false
        val current = ordered[index]
        val target  = ordered[targetIndex]
        categoryDao.updatePosition(current.id, target.position)
        categoryDao.updatePosition(target.id, current.position)
        return true
    }

    suspend fun addItemToCategory(categoryId: String, itemId: String, itemType: String, order: Int = 0) =
        categoryDao.addItem(CategoryItemEntity(categoryId, itemId, itemType, order))

    suspend fun removeItemFromCategory(categoryId: String, itemId: String) =
        categoryDao.removeItem(categoryId, itemId)

    fun observeCategoryItems(categoryId: String) =
        categoryDao.observeItemsForCategory(categoryId)

    // Seeds built-in categories on first launch — idempotent (INSERT OR IGNORE).
    suspend fun seedBuiltInCategories() {
        categoryDao.insertAll(builtInCategories().map { it.toEntity() })
        Timber.i("Built-in categories seeded")
    }

    // Corrects system-defined flags on built-in rows that already exist. Runs on every
    // launch so changes to built-in definitions (e.g. marking Games as a gaming category)
    // propagate to databases seeded by older builds — without wiping user data. Only the
    // gaming flag is reconciled; user-editable fields (name, position, visibility, icon)
    // are deliberately left alone.
    suspend fun reconcileBuiltInCategories() {
        // INSERT OR IGNORE adds built-ins introduced after this DB was first seeded (e.g. Social)
        // without disturbing existing rows or user edits.
        categoryDao.insertAll(builtInCategories().map { it.toEntity() })
        for (category in builtInCategories()) {
            categoryDao.setGamingFlag(category.id, category.isGamingCategory)
        }
        Timber.i("Built-in category flags reconciled")
    }

    companion object {
        // Canonical built-in category definitions — single source of truth for both
        // first-launch seeding and per-launch flag reconciliation.
        private val BUILT_IN_CATEGORIES = listOf(
            Category(BuiltInCategory.SETTINGS, "Settings",  "ic_settings", type = CategoryType.BUILT_IN, position = 0),
            Category("photos",                 "Photo",     "ic_photos",   type = CategoryType.BUILT_IN, position = 1),
            Category("music",                  "Music",     "ic_music",    type = CategoryType.BUILT_IN, position = 2),
            Category("videos",                 "Video",     "ic_videos",   type = CategoryType.BUILT_IN, position = 3),
            Category(BuiltInCategory.GAMES,    "Game",      "ic_games",    type = CategoryType.BUILT_IN, position = 4, isGamingCategory = true),
            Category("network",                "Network",   "ic_network",  type = CategoryType.BUILT_IN, position = 5),
            Category("app_store",              "App Store", "ic_appstore", type = CategoryType.BUILT_IN, position = 6),
            Category(BuiltInCategory.SOCIAL,   "Social",    "ic_social",   type = CategoryType.BUILT_IN, position = 7),
        )

        // Built-in categories the user may hide/reorder but never delete.
        val PROTECTED_BUILTINS = setOf(
            BuiltInCategory.FAVORITES,
            BuiltInCategory.RECENTLY_PLAYED,
            BuiltInCategory.GAMES,
            BuiltInCategory.ANDROID,
            BuiltInCategory.APP_DRAWER,
            BuiltInCategory.SETTINGS,
            "photos", "music", "videos", "network", "app_store", BuiltInCategory.SOCIAL,
        )
    }
}
