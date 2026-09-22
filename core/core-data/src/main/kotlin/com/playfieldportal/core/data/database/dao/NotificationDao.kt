package com.playfieldportal.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.playfieldportal.core.data.database.entity.NotificationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationDao {

    @Query("SELECT * FROM notifications ORDER BY created_at DESC, id DESC")
    fun observeAll(): Flow<List<NotificationEntity>>

    @Query("SELECT COUNT(*) FROM notifications WHERE read_at IS NULL")
    fun observeUnreadCount(): Flow<Int>

    @Query("SELECT * FROM notifications WHERE source_key = :sourceKey LIMIT 1")
    suspend fun findBySourceKey(sourceKey: String): NotificationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(notification: NotificationEntity): Long

    @Query("UPDATE notifications SET read_at = :readAt WHERE id = :id")
    suspend fun setReadAt(id: Long, readAt: Long?)

    @Query("UPDATE notifications SET read_at = :readAt WHERE read_at IS NULL")
    suspend fun markAllRead(readAt: Long)

    @Query("DELETE FROM notifications WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM notifications")
    suspend fun clearAll()

    @Query("DELETE FROM notifications WHERE read_at IS NOT NULL")
    suspend fun clearRead()

    @Query("SELECT COUNT(*) FROM notifications")
    suspend fun count(): Int

    @Query("DELETE FROM notifications WHERE created_at < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    /**
     * Evicts [excess] rows, read ones first and oldest-first within each group.
     *
     * `read_at IS NULL` sorts 0 before 1, so everything the user has already seen goes before
     * anything they have not — the cap should never silently swallow an unread failure while read
     * "scan complete" rows sit beside it.
     */
    @Query(
        """
        DELETE FROM notifications WHERE id IN (
            SELECT id FROM notifications
            ORDER BY (read_at IS NULL) ASC, created_at ASC, id ASC
            LIMIT :excess
        )
        """
    )
    suspend fun evictOldest(excess: Int)
}
