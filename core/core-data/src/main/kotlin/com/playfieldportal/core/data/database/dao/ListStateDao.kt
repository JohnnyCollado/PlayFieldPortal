package com.playfieldportal.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.playfieldportal.core.data.database.entity.ListItemEntity
import com.playfieldportal.core.data.database.entity.ListSettingEntity
import kotlinx.coroutines.flow.Flow

/**
 * Per-list arrangement: `list_items` (Custom order + pins) and `list_settings` (sort override).
 * A `list_items` row exists only while it carries a position or a pin.
 */
@Dao
interface ListStateDao {

    // ── Items ──────────────────────────────────────────────────────────

    @Query("SELECT * FROM list_items")
    fun observeItems(): Flow<List<ListItemEntity>>

    @Query("SELECT * FROM list_items")
    suspend fun getAllItems(): List<ListItemEntity>

    @Query("SELECT * FROM list_items WHERE list_key = :listKey")
    suspend fun getItems(listKey: String): List<ListItemEntity>

    @Query("SELECT * FROM list_items WHERE list_key = :listKey AND item_key = :itemKey")
    suspend fun getItem(listKey: String, itemKey: String): ListItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItems(items: List<ListItemEntity>)

    @Query("UPDATE list_items SET position = NULL WHERE list_key = :listKey")
    suspend fun clearPositions(listKey: String)

    @Query("DELETE FROM list_items WHERE list_key = :listKey AND position IS NULL AND pinned = 0")
    suspend fun deleteEmptyItems(listKey: String)

    @Query("DELETE FROM list_items WHERE list_key IN (:listKeys)")
    suspend fun deleteItemsOfLists(listKeys: List<String>)

    @Query("DELETE FROM list_items WHERE item_key = :itemKey")
    suspend fun deleteItemEverywhere(itemKey: String)

    @Query("DELETE FROM list_items")
    suspend fun clearItems()

    /** Makes [orderedKeys] the list's whole Custom order. Pins are kept, on or off the order. */
    @Transaction
    suspend fun replaceOrder(listKey: String, orderedKeys: List<String>) {
        val pinned = getItems(listKey).filter { it.pinned }.map { it.itemKey }.toSet()
        clearPositions(listKey)
        upsertItems(
            orderedKeys.mapIndexed { index, key ->
                ListItemEntity(listKey, key, position = index, pinned = key in pinned)
            }
        )
        deleteEmptyItems(listKey)
    }

    @Transaction
    suspend fun setPinned(listKey: String, itemKey: String, pinned: Boolean) {
        val existing = getItem(listKey, itemKey) ?: ListItemEntity(listKey, itemKey)
        upsertItems(listOf(existing.copy(pinned = pinned)))
        deleteEmptyItems(listKey)
    }

    // ── Sort settings ──────────────────────────────────────────────────

    @Query("SELECT * FROM list_settings")
    fun observeSettings(): Flow<List<ListSettingEntity>>

    @Query("SELECT * FROM list_settings")
    suspend fun getAllSettings(): List<ListSettingEntity>

    @Query("SELECT * FROM list_settings WHERE list_key = :listKey")
    suspend fun getSetting(listKey: String): ListSettingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSetting(setting: ListSettingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSettings(settings: List<ListSettingEntity>)

    @Query("DELETE FROM list_settings WHERE list_key = :listKey")
    suspend fun deleteSetting(listKey: String)

    @Query("DELETE FROM list_settings WHERE list_key IN (:listKeys)")
    suspend fun deleteSettingsOfLists(listKeys: List<String>)

    @Query("DELETE FROM list_settings")
    suspend fun clearSettings()

    /** Forgets everything stored for [listKeys] — their order, pins and sort override. */
    @Transaction
    suspend fun deleteLists(listKeys: List<String>) {
        if (listKeys.isEmpty()) return
        deleteItemsOfLists(listKeys)
        deleteSettingsOfLists(listKeys)
    }
}
