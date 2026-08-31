package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.music.MusicIntentResolver
import com.playfieldportal.core.data.repository.MediaRootKind
import com.playfieldportal.core.data.repository.MediaRootRepository
import com.playfieldportal.core.domain.repository.MusicRepository
import com.playfieldportal.feature.library.scanner.MusicScanResult
import com.playfieldportal.feature.library.scanner.MusicScanner
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric (not plain mockk): BackgroundTaskNotifier posts real Android notifications, whose
// Notification.Builder throws "Stub!" on a bare JVM. Under Robolectric's shadow notification
// manager it works against a real application context.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MusicSettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val musicRepository = mockk<MusicRepository>(relaxed = true)
    private val musicScanner = mockk<MusicScanner>(relaxed = true)
    private val intentResolver = mockk<MusicIntentResolver>(relaxed = true)
    private val mediaRoots = mockk<MediaRootRepository>(relaxed = true)
    private lateinit var vm: MusicSettingsViewModel

    private val rootUri = "content://tree/primary%3AMusic"

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun build() {
        every { mediaRoots.roots(MediaRootKind.MUSIC) } returns flowOf(listOf(rootUri))
        vm = MusicSettingsViewModel(context, musicRepository, musicScanner, intentResolver, mediaRoots)
    }

    @Test fun `roots flow maps to rows with grant status`() = runTest(dispatcher) {
        build()
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.roots.size)
        val row = vm.uiState.value.roots.single()
        assertEquals(rootUri, row.treeUri)
        assertFalse("fresh Robolectric app has no persisted grants", row.linked)
        assertTrue(vm.uiState.value.hasRoots)
    }

    @Test fun `addRoot persists, adds, and starts a scan`() = runTest(dispatcher) {
        coEvery { mediaRoots.getAll(MediaRootKind.MUSIC) } returns listOf(rootUri)
        coEvery { musicRepository.getFolders() } returns emptyList()
        coEvery { musicRepository.observeTracksByFolder(any()) } returns flowOf(emptyList())
        coEvery { musicScanner.scan(any(), any(), any()) } returns flowOf(
            MusicScanResult.Complete(folderId = "folder-1", tracks = emptyList())
        )
        build()
        advanceUntilIdle()

        val uri = mockk<Uri> { every { this@mockk.toString() } returns rootUri }
        vm.addRoot(uri)
        advanceUntilIdle()

        coVerify { mediaRoots.persist(uri) }
        coVerify { mediaRoots.add(MediaRootKind.MUSIC, rootUri) }
        // Rescan ran to completion and reported the empty result.
        assertFalse(vm.uiState.value.scanning)
        assertTrue(vm.uiState.value.scanMessage.orEmpty().contains("Found 0 tracks"))
    }

    @Test fun `removeRoot removes and rescans to drop the orphan library`() = runTest(dispatcher) {
        build()
        advanceUntilIdle()

        vm.removeRoot(rootUri)
        advanceUntilIdle()

        coVerify { mediaRoots.remove(MediaRootKind.MUSIC, rootUri) }
    }

    @Test fun `rescan with no roots asks for a root folder first`() = runTest(dispatcher) {
        build()
        advanceUntilIdle()

        coEvery { mediaRoots.getAll(MediaRootKind.MUSIC) } returns emptyList()
        vm.rescan()
        advanceUntilIdle()

        assertEquals("Add a root folder first.", vm.uiState.value.scanMessage)
    }
}