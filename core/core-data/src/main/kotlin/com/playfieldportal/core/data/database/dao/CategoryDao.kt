package com.playfieldportal.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.playfieldportal.core.data.database.entity.CategoryEntity
import com.playfieldportal.core.data.database.entity.CategoryItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {

    // ── Categories ─────────────────────────────────────────────────────

    @Query("SELECT * FROM categories WHERE is_visible = 1 ORDER BY position ASC")
    fun observeVisible(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY position ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY position ASC")
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT * FROM category_items ORDER BY category_id, sort_order ASC")
    suspend fun getAllItems(): List<CategoryItemEntity>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: String): CategoryEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(category: CategoryEntity)

    @Update
    suspend fun update(category: CategoryEntity)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE categories SET position = :position WHERE id = :id")
    suspend fun updatePosition(id: String, position: Int)

    @Query("UPDATE categories SET is_visible = :visible WHERE id = :id")
    suspend fun setVisible(id: String, visible: Boolean)

    // ── Category Items (junction table) ────────────────────────────────

    @Query("SELECT * FROM category_items WHERE category_id = :categoryId ORDER BY sort_order ASC")
    fun observeItemsForCategory(categoryId: String): Flow<List<CategoryItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addItem(item: CategoryItemEntity)

    @Query("DELETE FROM category_items WHERE category_id = :categoryId AND item_id = :itemId")
    suspend fun removeItem(categoryId: String, itemId: String)

    @Query("DELETE FROM category_items WHERE category_id = :categoryId")
    suspend fun clearCategory(categoryId: String)

    @Query("UPDATE category_items SET sort_order = :order WHERE category_id = :categoryId AND item_id = :itemId")
    suspend fun updateItemOrder(categoryId: String, itemId: String, order: Int)
}
