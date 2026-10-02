package com.playfieldportal.core.data.repository

import com.playfieldportal.core.data.database.dao.CategoryDao
import com.playfieldportal.core.data.database.entity.CategoryItemEntity
import com.playfieldportal.core.data.database.entity.toDomain
import com.playfieldportal.core.data.database.entity.toEntity
import com.playfieldportal.core.domain.model.BuiltInCategory
import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.domain.model.CategoryType
import com.playfieldportal.core.domain.model.ListKeys
import com.playfieldportal.core.ui.icons.UserCategoryIconKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject

/** What happens to a category's custom memory cards when the category is deleted. */
enum class CollectionsOnDelete {
    /** Keep them: move them to the built-in column of the same kind. */
    MOVE,
    /** Delete them. The games and apps in them stay in the library. */
    DELETE,
}

/**
 * CRUD and ordering for XMB categories (the horizontal bar). Seeds the built-in categories on first
 * run and reconciles their system-defined flags every launch so definition changes reach databases
 * seeded by older builds. Custom categories are fully editable; built-ins are protected from
 * deletion.
 */
class CategoryRepositoryImpl @Inject constructor(
    private val categoryDao: CategoryDao,
    private val discordSessionActivator: com.playfieldportal.core.domain.discord.DiscordSessionActivator,
    private val collectionRepository: CollectionRepository,
    private val listStateDao: com.playfieldportal.core.data.database.dao.ListStateDao,
    private val customIconStore: CustomIconStore,
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

    /**
     * Deletes a custom category. Its custom memory cards are not tied to it by a foreign key, so
     * [collections] says what becomes of them: moved to the home column of the category's kind,
     * or deleted. Either way the games and apps themselves are untouched. Returns false for a
     * protected built-in, which is never deleted.
     */
    suspend fun delete(id: String, collections: CollectionsOnDelete = CollectionsOnDelete.MOVE): Boolean {
        if (id in PROTECTED_BUILTINS) {
            Timber.w("Attempted to delete built-in category '$id' — blocked")
            return false
        }
        val isGaming = categoryDao.getById(id)?.isGamingCategory == true
        when (collections) {
            CollectionsOnDelete.MOVE   -> collectionRepository.rehomeAll(id, collectionHomeFor(isGaming))
            CollectionsOnDelete.DELETE -> collectionRepository.deleteAllIn(id)
        }
        categoryDao.deleteById(id)   // category_items and umd_slots rows cascade-delete
        listStateDao.deleteLists(ListKeys.listsOfCategory(id))
        // Its device image goes with it, so recreating the same name (same id) never inherits one.
        UserCategoryIconKeys.keyFor(id)?.let { customIconStore.clear(it) }
        Timber.i("Category deleted: $id (collections=$collections)")
        return true
    }

    /**
     * Removes category images whose category is gone, and any abandoned create-flow draft. Run once
     * at startup: a restore commits its files before its categories, so sweeping any later would
     * delete images that are about to match.
     */
    suspend fun pruneOrphanCategoryIcons() {
        customIconStore.pruneUserCategoryIcons(categoryDao.getAll().map { it.id }.toSet())
    }

    fun isProtected(id: String): Boolean = id in PROTECTED_BUILTINS

    suspend fun updatePosition(id: String, position: Int) =
        categoryDao.updatePosition(id, position)

    suspend fun setVisible(id: String, visible: Boolean) =
        categoryDao.setVisible(id, visible)

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

    /**
     * Saves the order a live Move left on the crossbar. [barOrder] is the bar's categories, left to
     * right; categories not on the bar (hidden ones) keep their slots among them — see
     * [mergeBarOrder]. Every category is rewritten to a compact 0..n position.
     */
    suspend fun reorder(barOrder: List<String>) {
        val ordered = mergeBarOrder(categoryDao.getAll().sortedBy { it.position }.map { it.id }, barOrder)
        ordered.forEachIndexed { index, id -> categoryDao.updatePosition(id, index) }
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
        /** Where a deleted category's custom memory cards go: Main Game for a gaming category,
         *  the App Store column for an app one — the built-in column of the same kind. */
        fun collectionHomeFor(isGamingCategory: Boolean): String =
            if (isGamingCategory) BuiltInCategory.GAMES else "app_store"

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
            // Appended after Social so it slots in without colliding with existing rows' positions
            // on databases seeded by older builds; the user can reorder it next to Games.
            Category(BuiltInCategory.ACHIEVEMENTS, "Shiba Coins", "ic_achievements", type = CategoryType.BUILT_IN, position = 8),
        )

        /**
         * [all] (every stored category id, in stored order) with the categories on the bar put in
         * [barOrder]'s order. The bar's categories fill exactly the slots they held between them, so
         * a hidden category stays where it was relative to its neighbours. Ids the store does not
         * hold are skipped.
         */
        internal fun mergeBarOrder(all: List<String>, barOrder: List<String>): List<String> {
            val onBar = barOrder.filter { it in all }
            val onBarSet = onBar.toSet()
            val next = onBar.iterator()
            return all.map { id -> if (id in onBarSet) next.next() else id }
        }

        // Built-in categories the user may hide/reorder but never delete.
        val PROTECTED_BUILTINS = setOf(
            BuiltInCategory.FAVORITES,
            BuiltInCategory.RECENTLY_PLAYED,
            BuiltInCategory.GAMES,
            BuiltInCategory.ANDROID,
            BuiltInCategory.APP_DRAWER,
            BuiltInCategory.SETTINGS,
            "photos", "music", "videos", "network", "app_store", BuiltInCategory.SOCIAL,
            BuiltInCategory.ACHIEVEMENTS,
        )
    }
}
