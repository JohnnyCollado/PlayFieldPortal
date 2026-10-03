package com.playfieldportal.core.data.repository

import com.playfieldportal.core.data.database.dao.ListStateDao
import com.playfieldportal.core.data.database.entity.ListSettingEntity
import com.playfieldportal.core.domain.model.ListSortMode
import com.playfieldportal.core.domain.model.ListState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-list arrangement for the XMB: each list's Custom order, its pinned games, and its own sort
 * mode. Lists and items are addressed by [com.playfieldportal.core.domain.model.ListKeys].
 */
@Singleton
class ListStateRepository @Inject constructor(
    private val listStateDao: ListStateDao,
) {
    /** Every list that has stored order or pins, by list key. */
    val states: Flow<Map<String, ListState>> = listStateDao.observeItems().map { rows ->
        rows.groupBy { it.listKey }.mapValues { (_, items) ->
            ListState(
                positions = items.mapNotNull { row -> row.position?.let { row.itemKey to it } }.toMap(),
                pinned = items.filter { it.pinned }.map { it.itemKey }.toSet(),
            )
        }
    }

    /** Lists that set their own sort. A list absent from the map follows the global setting. */
    val sortOverrides: Flow<Map<String, ListSortMode>> = listStateDao.observeSettings().map { rows ->
        rows.mapNotNull { row -> ListSortMode.fromName(row.sortMode)?.let { row.listKey to it } }.toMap()
    }

    /** Saves [orderedKeys] as the list's whole Custom order. */
    suspend fun replaceOrder(listKey: String, orderedKeys: List<String>) {
        listStateDao.replaceOrder(listKey, orderedKeys)
        Timber.i("Custom order saved: list=$listKey items=${orderedKeys.size}")
    }

    suspend fun setPinned(listKey: String, itemKey: String, pinned: Boolean) {
        listStateDao.setPinned(listKey, itemKey, pinned)
        Timber.i("List pin: list=$listKey item=$itemKey pinned=$pinned")
    }

    /** [mode] = null clears the override so the list follows the global setting again. */
    suspend fun setSort(listKey: String, mode: ListSortMode?) {
        if (mode == null) listStateDao.deleteSetting(listKey)
        else listStateDao.upsertSetting(ListSettingEntity(listKey, mode.name))
        Timber.i("List sort: list=$listKey mode=${mode?.name ?: "global"}")
    }

    /** Forgets everything stored for lists whose owner (a category, a custom card) is gone. */
    suspend fun forgetLists(listKeys: List<String>) = listStateDao.deleteLists(listKeys)

    /** Removes an item that no longer exists from every list's order. */
    suspend fun forgetItem(itemKey: String) = listStateDao.deleteItemEverywhere(itemKey)
}
