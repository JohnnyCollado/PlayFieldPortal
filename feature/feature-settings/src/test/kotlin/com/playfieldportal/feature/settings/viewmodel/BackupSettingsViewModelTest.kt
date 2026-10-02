package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.playfieldportal.core.data.repository.BackupFolderRepository
import com.playfieldportal.feature.backup.BackupInfo
import com.playfieldportal.feature.backup.BackupManager
import com.playfieldportal.feature.backup.RestoreWorker
import com.playfieldportal.feature.settings.ui.deleteBackupConfirmSpec
import com.playfieldportal.feature.settings.ui.restoreBackupConfirmSpec
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Backup & Restore > Saved Backups: Restore enqueues the RestoreWorker with that backup's uri, and
 * Delete Backup deletes the document only after its confirm.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupSettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val context = mockk<Context>(relaxed = true)
    private val workManager = mockk<WorkManager>(relaxed = true)
    private val backupManager = mockk<BackupManager>(relaxed = true)
    private val folders = mockk<BackupFolderRepository>(relaxed = true)
    private val uri = mockk<Uri>()
    private val info = BackupInfo("pfp-2026.pfpbackup", uri, 1L)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { uri.toString() } returns "content://tree/doc1"
        mockkObject(WorkManager.Companion)
        mockkStatic(DocumentsContract::class)
        every { WorkManager.getInstance(any<Context>()) } returns workManager
        every { workManager.getWorkInfoByIdFlow(any()) } returns flowOf()
        every { DocumentsContract.deleteDocument(any(), any()) } returns true
        coEvery { backupManager.listBackups() } returns listOf(info)
    }

    @After
    fun tearDown() {
        unmockkObject(WorkManager.Companion)
        mockkStatic(DocumentsContract::class)
        Dispatchers.resetMain()
    }

    private fun vm() = BackupSettingsViewModel(context, backupManager, folders)

    @Test
    fun `the saved backups list carries each backup's uri`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()

        assertEquals(listOf(info), vm.uiState.value.backups)
    }

    @Test
    fun `Restore enqueues the RestoreWorker with that backup's uri`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        val request = slot<OneTimeWorkRequest>()
        every { workManager.enqueue(capture(request)) } returns mockk(relaxed = true)

        vm.restoreFromUri(info.uri)
        advanceUntilIdle()

        assertEquals(
            "content://tree/doc1",
            request.captured.workSpec.input.getString(RestoreWorker.KEY_URI),
        )
    }

    @Test
    fun `deleteBackup deletes the document and refreshes the list`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        coEvery { backupManager.listBackups() } returns emptyList()

        vm.deleteBackup(info)
        advanceUntilIdle()

        verify { DocumentsContract.deleteDocument(any(), uri) }
        assertTrue(vm.uiState.value.backups.isEmpty())
    }

    @Test
    fun `a failed delete surfaces an error and keeps the list`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        every { DocumentsContract.deleteDocument(any(), any()) } returns false

        vm.deleteBackup(info)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.errorMessage != null)
        assertEquals(listOf(info), vm.uiState.value.backups)
    }

    @Test
    fun `the confirm is destructive, opens on cancel and names the backup`() {
        val spec = deleteBackupConfirmSpec(info, onConfirm = {}, onCancel = {})

        assertTrue(spec.destructive)
        assertTrue(spec.openOnCancel)
        assertTrue(spec.message.contains("pfp-2026.pfpbackup"))
    }

    @Test
    fun `the restore confirm is destructive, opens on cancel and says what it replaces`() {
        val named = restoreBackupConfirmSpec("pfp-2026.pfpbackup", onConfirm = {}, onCancel = {})
        val picked = restoreBackupConfirmSpec(null, onConfirm = {}, onCancel = {})

        assertTrue(named.destructive)
        assertTrue(named.openOnCancel)
        assertTrue(named.message.contains("pfp-2026.pfpbackup"))
        assertTrue(named.message.contains("replace"))
        assertTrue(picked.destructive)
        assertTrue(picked.openOnCancel)
    }

    @Test
    fun `the restore runs on Confirm and not on Cancel`() {
        var restored = 0
        var cancelled = 0
        val spec = restoreBackupConfirmSpec(null, onConfirm = { restored++ }, onCancel = { cancelled++ })

        spec.onCancel()
        assertEquals(0 to 1, restored to cancelled)

        spec.onConfirm()
        assertEquals(1 to 1, restored to cancelled)
    }

    @Test
    fun `the delete runs on Confirm and not on Cancel`() {
        var deleted = 0
        var cancelled = 0
        val spec = deleteBackupConfirmSpec(info, onConfirm = { deleted++ }, onCancel = { cancelled++ })

        spec.onCancel()
        assertEquals(0 to 1, deleted to cancelled)

        spec.onConfirm()
        assertEquals(1 to 1, deleted to cancelled)
    }
}
