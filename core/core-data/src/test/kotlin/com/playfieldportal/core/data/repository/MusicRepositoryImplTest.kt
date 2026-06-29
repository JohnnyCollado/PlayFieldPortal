package com.playfieldportal.core.data.repository

import android.content.Context
import com.playfieldportal.core.data.database.dao.MusicFolderDao
import com.playfieldportal.core.data.database.dao.MusicTrackDao
import com.playfieldportal.core.data.database.entity.MusicFolderEntity
import com.playfieldportal.core.data.database.entity.MusicTrackEntity
import com.playfieldportal.core.domain.model.MusicTrack
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Repository behaviour over fake DAOs. The fake track DAO mirrors the real @Transaction
 * replaceForFolder contract (delete only the target folder's rows, then insert), so these tests
 * pin the "replace one folder doesn't touch others" and "remove folder removes its tracks"
 * guarantees the SQL provides.
 */
class MusicRepositoryImplTest {

    private val context = mockk<Context>(relaxed = true) // folder/track paths never touch context
    private val folderDao = FakeMusicFolderDao()
    private val trackDao = FakeMusicTrackDao()
    private val repo = MusicRepositoryImpl(context, folderDao, trackDao)

    private fun track(id: String, folderId: String) =
        MusicTrack(id = id, folderId = folderId, uri = "content://$id", displayName = "$id.mp3")

    @Test
    fun `replacing one folder's tracks leaves other folders untouched`() = runTest {
        val a = repo.addFolder("A", "content://tree/a")
        val b = repo.addFolder("B", "content://tree/b")

        repo.replaceTracksForFolder(a.id, listOf(track("a1", a.id), track("a2", a.id)), 1L)
        repo.replaceTracksForFolder(b.id, listOf(track("b1", b.id), track("b2", b.id), track("b3", b.id)), 1L)

        // Re-scan folder A with a single track — B must be unaffected.
        repo.replaceTracksForFolder(a.id, listOf(track("a3", a.id)), 2L)

        assertEquals(1, repo.observeTracksByFolder(a.id).first().size)
        assertEquals(3, repo.observeTracksByFolder(b.id).first().size)
        assertEquals(4, repo.observeAllTracks().first().size)
        // Folder A's stored count reflects the latest scan.
        assertEquals(1, repo.getFolder(a.id)!!.trackCount)
    }

    @Test
    fun `removing a folder removes its tracks but keeps other folders`() = runTest {
        val a = repo.addFolder("A", "content://tree/a")
        val b = repo.addFolder("B", "content://tree/b")
        repo.replaceTracksForFolder(a.id, listOf(track("a1", a.id)), 1L)
        repo.replaceTracksForFolder(b.id, listOf(track("b1", b.id), track("b2", b.id)), 1L)

        repo.removeFolder(a.id)

        assertNull(repo.getFolder(a.id))
        assertTrue(repo.observeTracksByFolder(a.id).first().isEmpty())
        assertEquals(2, repo.observeTracksByFolder(b.id).first().size)
    }

    @Test
    fun `addFolder then setEnabled and rename are reflected`() = runTest {
        val f = repo.addFolder("Original", "content://tree/x")
        repo.renameFolder(f.id, "Renamed")
        repo.setFolderEnabled(f.id, false)

        val stored = repo.getFolder(f.id)!!
        assertEquals("Renamed", stored.displayName)
        assertEquals(false, stored.enabled)
    }
}

// ── Fakes ───────────────────────────────────────────────────────────────────────

private class FakeMusicFolderDao : MusicFolderDao {
    private val map = linkedMapOf<String, MusicFolderEntity>()
    override fun observeAll(): Flow<List<MusicFolderEntity>> = flowOf(map.values.toList())
    override fun observeEnabled(): Flow<List<MusicFolderEntity>> = flowOf(map.values.filter { it.enabled })
    override suspend fun getAll() = map.values.toList()
    override suspend fun getById(id: String) = map[id]
    override suspend fun upsert(folder: MusicFolderEntity) { map[folder.id] = folder }
    override suspend fun delete(id: String) { map.remove(id) }
    override suspend fun setEnabled(id: String, enabled: Boolean, now: Long) {
        map[id]?.let { map[id] = it.copy(enabled = enabled, updatedAt = now) }
    }
    override suspend fun setDisplayName(id: String, name: String, now: Long) {
        map[id]?.let { map[id] = it.copy(displayName = name, updatedAt = now) }
    }
    override suspend fun updateScanResult(id: String, count: Int, scannedAt: Long) {
        map[id]?.let { map[id] = it.copy(trackCount = count, lastScannedAt = scannedAt, updatedAt = scannedAt) }
    }
}

private class FakeMusicTrackDao : MusicTrackDao {
    // Keyed by folderId to mirror per-folder isolation in SQL.
    private val byFolder = linkedMapOf<String, MutableList<MusicTrackEntity>>()
    override fun observeAll(): Flow<List<MusicTrackEntity>> = flowOf(byFolder.values.flatten())
    override fun observeByFolder(folderId: String): Flow<List<MusicTrackEntity>> =
        flowOf(byFolder[folderId]?.toList().orEmpty())
    override suspend fun getById(id: String) = byFolder.values.flatten().firstOrNull { it.id == id }
    override suspend fun countForFolder(folderId: String) = byFolder[folderId]?.size ?: 0
    override suspend fun insertAll(tracks: List<MusicTrackEntity>) {
        tracks.forEach { byFolder.getOrPut(it.folderId) { mutableListOf() }.add(it) }
    }
    override suspend fun deleteForFolder(folderId: String) { byFolder[folderId]?.clear() }
    // replaceForFolder is a default interface method (deleteForFolder + insertAll) — inherited.
}
