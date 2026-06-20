package com.playfieldportal.core.data.repository

import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.database.dao.MemoryCardDao
import com.playfieldportal.core.data.database.dao.PlatformDao
import com.playfieldportal.core.data.database.entity.toDomain
import com.playfieldportal.core.data.database.entity.toEntity
import com.playfieldportal.core.domain.model.MemoryCard
import com.playfieldportal.core.domain.model.Platform
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// Single source of truth for the user's manually configured console libraries.
// Nothing here runs automatically — every card is created, edited and scanned in
// response to an explicit user action in the Library Manager or a context menu.
@Singleton
class MemoryCardRepository @Inject constructor(
    private val memoryCardDao: MemoryCardDao,
    private val platformDao: PlatformDao,
    private val gameDao: GameDao,
) {
    fun observeAll(): Flow<List<MemoryCard>> =
        memoryCardDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeEnabled(): Flow<List<MemoryCard>> =
        memoryCardDao.observeEnabled().map { list -> list.map { it.toDomain() } }

    suspend fun getAll(): List<MemoryCard> = memoryCardDao.getAll().map { it.toDomain() }

    suspend fun getById(platformId: String): MemoryCard? =
        memoryCardDao.getById(platformId)?.toDomain()

    // The catalog of platform definitions the user can pick from when adding a console.
    suspend fun availablePlatformCatalog(): List<Platform> =
        platformDao.observeAll().first().map { it.toDomain() }

    // Platforms that don't already have a card — used by the Add Console picker so a
    // platform can never be added twice (one card per platform).
    suspend fun unconfiguredPlatforms(): List<Platform> {
        val configured = memoryCardDao.getAll().map { it.platformId }.toSet()
        return availablePlatformCatalog().filter { it.id !in configured }
    }

    // Creates a card for a platform. Default extensions come from the platform catalog
    // but can be overridden by the caller.
    suspend fun addCard(
        platformId: String,
        displayName: String,
        romDirectory: String?,
        emulatorId: String?,
        extensions: List<String>? = null,
        scanRecursively: Boolean = true,
    ): MemoryCard {
        val platform = platformDao.getById(platformId)
        val exts = (extensions ?: platform?.romExtensions
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList())
        val card = MemoryCard(
            platformId          = platformId,
            displayName         = displayName,
            romDirectory        = romDirectory,
            emulatorId          = emulatorId,
            supportedExtensions = exts,
            scanRecursively     = scanRecursively,
            sortOrder           = memoryCardDao.maxSortOrder() + 1,
        )
        memoryCardDao.upsert(card.toEntity())
        Timber.i("Memory Card created: $platformId dir=$romDirectory exts=$exts")
        return card
    }

    // Removing a card also removes that platform's games from the library. ROM files on
    // disk are never touched.
    suspend fun remove(platformId: String) {
        gameDao.deleteByPlatform(platformId)
        memoryCardDao.delete(platformId)
        Timber.i("Memory Card removed and games cleared: $platformId")
    }

    suspend fun setEnabled(platformId: String, enabled: Boolean) =
        memoryCardDao.setEnabled(platformId, enabled)

    suspend fun setPinned(platformId: String, pinned: Boolean) =
        memoryCardDao.setPinned(platformId, pinned)

    suspend fun rename(platformId: String, name: String) =
        memoryCardDao.setDisplayName(platformId, name)

    suspend fun setRomDirectory(platformId: String, dir: String?) =
        memoryCardDao.setRomDirectory(platformId, dir)

    suspend fun setEmulator(platformId: String, emulatorId: String?) =
        memoryCardDao.setEmulator(platformId, emulatorId)

    // Recomputes the persisted game count from the actual games table.
    suspend fun recountGames(platformId: String) {
        memoryCardDao.updateGameCount(platformId, gameDao.countByPlatform(platformId))
    }

    suspend fun recordScan(platformId: String, scannedAt: Long) {
        memoryCardDao.updateScanResult(platformId, scannedAt, gameDao.countByPlatform(platformId))
    }

    // Swaps sort_order with the adjacent card in the given direction (within the full
    // ordered list). Pinned grouping is preserved because the list is already ordered
    // pinned-first. Returns true when a move happened.
    suspend fun move(platformId: String, up: Boolean): Boolean {
        val ordered = memoryCardDao.getAll()
        val index = ordered.indexOfFirst { it.platformId == platformId }
        if (index < 0) return false
        val targetIndex = if (up) index - 1 else index + 1
        if (targetIndex !in ordered.indices) return false

        val current = ordered[index]
        val target  = ordered[targetIndex]
        // Only reorder within the same pinned group to keep pinned cards on top.
        if (current.pinned != target.pinned) return false

        memoryCardDao.setSortOrder(current.platformId, target.sortOrder)
        memoryCardDao.setSortOrder(target.platformId, current.sortOrder)
        return true
    }
}
