package com.playfieldportal.feature.settings.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.repository.MediaRootKind
import com.playfieldportal.core.data.repository.MediaRootRepository
import com.playfieldportal.core.domain.model.MusicFolder
import com.playfieldportal.core.domain.model.PhotoLibrary
import com.playfieldportal.core.domain.model.VideoLibrary
import com.playfieldportal.core.domain.repository.MusicRepository
import com.playfieldportal.core.domain.repository.PhotoRepository
import com.playfieldportal.core.domain.repository.VideoRepository
import com.playfieldportal.feature.library.scanner.MusicScanResult
import com.playfieldportal.feature.library.scanner.MusicScanner
import com.playfieldportal.feature.library.scanner.PhotoScanResult
import com.playfieldportal.feature.library.scanner.PhotoScanner
import com.playfieldportal.feature.library.scanner.VideoScanResult
import com.playfieldportal.feature.library.scanner.VideoScanner
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric (not plain mockk): the shared BackgroundTaskCenter builds a real notifier, and
// NotificationChannel/Notification.Builder throw "Stub!" on a bare JVM.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WizardMediaScanRunnerTest {

    private val scheduler = TestCoroutineScheduler()
    private val scope = TestScope(StandardTestDispatcher(scheduler))
    private val context: Context = ApplicationProvider.getApplicationContext()

    // Relaxed: this test is about scan orchestration, not about what each pass reports. The
    // center's own behaviour is covered where it lives.
    private val taskCenter =
        mockk<com.playfieldportal.core.ui.notification.BackgroundTaskCenter>(relaxed = true)
    private val mediaRoots = mockk<MediaRootRepository>(relaxed = true)
    private val musicRepository = mockk<MusicRepository>(relaxed = true)
    private val photoRepository = mockk<PhotoRepository>(relaxed = true)
    private val videoRepository = mockk<VideoRepository>(relaxed = true)
    private val musicScanner = mockk<MusicScanner>(relaxed = true)
    private val photoScanner = mockk<PhotoScanner>(relaxed = true)
    private val videoScanner = mockk<VideoScanner>(relaxed = true)

    private lateinit var runner: WizardMediaScanRunner

    /** Virtual time at which each kind's scan actually began. */
    private val startedAt = mutableMapOf<MediaRootKind, Long>()

    @Before fun setUp() {
        val now = 0L
        val musicFolder = MusicFolder(
            id = "music-1", displayName = "Music", treeUri = root(MediaRootKind.MUSIC),
            createdAt = now, updatedAt = now,
        )
        val photoLibrary = PhotoLibrary(
            id = "photo-1", displayName = "Photos", treeUri = root(MediaRootKind.PHOTO),
            createdAt = now, updatedAt = now,
        )
        val videoLibrary = VideoLibrary(
            id = "video-1", displayName = "Videos", treeUri = root(MediaRootKind.VIDEO),
            createdAt = now, updatedAt = now,
        )

        coEvery { mediaRoots.getAll(any()) } answers { listOf(root(firstArg())) }

        // Each kind already has its library row, so the orphan sweep is a no-op and no row is
        // created — the scan is the only work the pass does.
        coEvery { musicRepository.getFolders() } returns listOf(musicFolder)
        coEvery { musicRepository.getFolder("music-1") } returns musicFolder
        coEvery { musicRepository.getTracksForFolder("music-1") } returns emptyList()
        coEvery { photoRepository.getLibraries() } returns listOf(photoLibrary)
        coEvery { photoRepository.getLibrary("photo-1") } returns photoLibrary
        coEvery { photoRepository.getPhotosForLibrary("photo-1") } returns emptyList()
        coEvery { videoRepository.getLibraries() } returns listOf(videoLibrary)
        coEvery { videoRepository.getLibrary("video-1") } returns videoLibrary
        coEvery { videoRepository.getVideosForLibrary("video-1") } returns emptyList()

        every { musicScanner.scan(any(), any(), any(), any()) } returns
            timedScan(MediaRootKind.MUSIC, MusicScanResult.Complete("music-1", emptyList(), SIGNATURE))
        every { photoScanner.scan(any(), any(), any(), any()) } returns
            timedScan(MediaRootKind.PHOTO, PhotoScanResult.Complete("photo-1", emptyList(), SIGNATURE))
        every { videoScanner.scan(any(), any(), any(), any()) } returns
            timedScan(MediaRootKind.VIDEO, VideoScanResult.Complete("video-1", emptyList(), SIGNATURE))

        runner = WizardMediaScanRunner(
            context = context,
            mediaRootRepository = mediaRoots,
            musicRepository = musicRepository,
            musicScanner = musicScanner,
            photoRepository = photoRepository,
            photoScanner = photoScanner,
            videoRepository = videoRepository,
            videoScanner = videoScanner,
            scope = scope,
            tasks = taskCenter,
        )
    }

    // The three scanners are final classes, so mockk instruments them inline and keeps a
    // process-wide registry. This class builds seven mocks per test method, and without an
    // explicit teardown that registry accumulates for the lifetime of the JVM — which this
    // module shares with every other test class, including ones that depend on the single
    // global pfp_prefs DataStore.
    @After fun tearDown() {
        unmockkAll()
    }

    // The regression this whole change exists for: before the per-kind mutex, the three kinds ran
    // back to back, so PHOTO started only once MUSIC had finished and the pass cost 3 × SCAN_MS.
    @Test fun `kickoffAll runs the three kinds concurrently`() = scope.runTest {
        runner.kickoffAll()
        advanceUntilIdle()

        assertEquals(
            "every kind should start immediately, not after the previous one finished",
            mapOf(
                MediaRootKind.MUSIC to 0L,
                MediaRootKind.PHOTO to 0L,
                MediaRootKind.VIDEO to 0L,
            ),
            startedAt.toMap(),
        )
        assertEquals("the pass should cost the slowest kind, not the sum", SCAN_MS, scheduler.currentTime)
    }

    // A user-initiated scan of one kind must not wait on the other two — the case where hitting
    // Rescan on Video Settings during a resume pass appeared to hang.
    @Test fun `kickoff for one kind does not wait on another kind`() = scope.runTest {
        runner.kickoff(MediaRootKind.MUSIC)
        runner.kickoff(MediaRootKind.VIDEO)
        advanceUntilIdle()

        assertEquals(0L, startedAt.getValue(MediaRootKind.VIDEO))
        assertEquals(SCAN_MS, scheduler.currentTime)
    }

    // Per-kind exclusion still holds: the second request is dropped rather than starting a
    // duplicate concurrent scan of the same roots.
    @Test fun `a second kickoff of the same kind while one is running is dropped`() = scope.runTest {
        runner.kickoff(MediaRootKind.VIDEO)
        runner.kickoff(MediaRootKind.VIDEO)
        advanceUntilIdle()

        coVerify(exactly = 1) { videoScanner.scan(any(), any(), any(), any()) }
    }

    // Once a kind's scan has finished, the next request starts a fresh one.
    @Test fun `a kickoff after the previous scan finished starts a new one`() = scope.runTest {
        runner.kickoff(MediaRootKind.VIDEO)
        advanceUntilIdle()
        runner.kickoff(MediaRootKind.VIDEO)
        advanceUntilIdle()

        coVerify(exactly = 2) { videoScanner.scan(any(), any(), any(), any()) }
    }

    private fun root(kind: MediaRootKind) = "content://tree/primary%3A${kind.name}"

    /** A scan that notes when it began and then occupies SCAN_MS of virtual time. */
    private fun <T> timedScan(kind: MediaRootKind, complete: T): Flow<T> = flow {
        startedAt[kind] = scheduler.currentTime
        delay(SCAN_MS)
        emit(complete)
    }

    private companion object {
        const val SCAN_MS = 1_000L
        const val SIGNATURE = "3:2048:900"
    }
}
