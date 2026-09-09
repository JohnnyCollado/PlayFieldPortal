package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.data.repository.ControllerLayoutRepository
import com.playfieldportal.core.data.repository.ControllerMappingRepository
import com.playfieldportal.core.data.repository.MediaDisplayNames
import com.playfieldportal.core.data.repository.UiMediaStore
import com.playfieldportal.core.domain.model.UiMediaSlot
import com.playfieldportal.core.ui.media.BootSoundPreviewer
import com.playfieldportal.core.ui.sound.MenuSound
import com.playfieldportal.core.ui.sound.MenuSoundPlayer
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Interface ▸ Sound screen's contract (docs/plans/README.md (C10) Phase 2c): seven
 * rows — the six menu sounds plus Boot Sound — with Boot Sound behaving like any other row
 * (label, Preview, Use Default, reset), while its Preview uses the dedicated boot player rather
 * than [MenuSound] (Display ▸ Boot Sequence remains the full-presentation preview).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AudioSettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    /**
     * Main is UNCONFINED — sharing [dispatcher]'s scheduler, so `runTest(dispatcher)` and
     * `advanceUntilIdle()` still drive everything — and that is load-bearing, not a style choice.
     *
     * DataStore runs each queued update on the CALLER's dispatcher
     * (`DataStoreImpl.handleUpdate`: `withContext(update.callerContext + coroutineContext)`,
     * caller context first, so its dispatcher wins). A `viewModelScope` write therefore drags
     * whatever Main is into DataStore's write actor. With a StandardTestDispatcher, a write that
     * is still in flight when the test body ends parks a continuation on a scheduler that
     * `resetMain()` leaves undriven forever — and because that actor is strictly serial, the
     * process-global `pfpDataStore` wedges for the rest of the JVM. The next `@Before` then
     * blocks in `runBlocking` with no timeout, which is what made this whole module unrunnable.
     * (Whether a given write loses that race is timing, so subsets of this class could pass while
     * the full class hung.)
     *
     * Unconfined dispatches inline instead of queueing, so the continuation resumes on whatever
     * thread completes the write and nothing is ever stranded.
     */
    private val mainDispatcher = UnconfinedTestDispatcher(dispatcher.scheduler)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val menuSound: MenuSoundPlayer = mockk(relaxed = true)
    private val bootPreviewer: BootSoundPreviewer = mockk(relaxed = true)
    private lateinit var store: UiMediaStore
    private lateinit var vm: AudioSettingsViewModel

    @Before fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        // Bounded on purpose. This runBlocking is the one unbounded, uncancellable wait in the
        // class, so a wedged DataStore actor used to stall the suite silently and forever rather
        // than failing. If it ever wedges again, one test fails loudly in five seconds.
        runBlocking { withTimeout(5_000) { context.pfpDataStore.edit { it.clear() } } }
        File(context.filesDir, UiMediaStore.UI_MEDIA_DIR).deleteRecursively()
        MediaDisplayNames.clearCache()
        store = UiMediaStore(context)
        vm = AudioSettingsViewModel(
            context,
            store,
            menuSound,
            bootPreviewer,
            ControllerLayoutRepository(context, ControllerMappingRepository(context)),
        )
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    /** stateIn(WhileSubscribed) only computes with a live collector. */
    private fun kotlinx.coroutines.test.TestScope.collectUiState() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    }

    /**
     * Real-time wait, mirroring DisplaySettingsViewModelWallpaperTest: DataStore writes and the
     * uiState pipeline's flowOn(Dispatchers.IO) complete on REAL threads, which
     * advanceUntilIdle() cannot drive. Alternating a real sleep (lets those threads progress)
     * with advanceUntilIdle() (drains whatever they queued on the scheduler) is the only honest
     * way to observe their effects.
     */
    private fun kotlinx.coroutines.test.TestScope.eventually(
        what: String,
        timeoutMs: Long = 5_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("Timed out waiting for: $what")
            }
            Thread.sleep(50)
            testScheduler.advanceUntilIdle()
        }
    }

    /** Drops a file straight into `ui-media/` — assignments() is the directory. */
    private fun seedAssignment(slot: UiMediaSlot, ext: String = "wav") {
        val dir = File(context.filesDir, UiMediaStore.UI_MEDIA_DIR)
        dir.mkdirs()
        File(dir, "${slot.key}.$ext").writeBytes(ByteArray(64))
    }

    private fun seedDisplayName(slot: UiMediaSlot, name: String) {
        runBlocking {
            context.pfpDataStore.edit { it[UiMediaStore.displayNameKey(slot)] = name }
        }
    }

    private fun mediaFile(slot: UiMediaSlot, ext: String) =
        File(File(context.filesDir, UiMediaStore.UI_MEDIA_DIR), "${slot.key}.$ext")

    // ── the roster ───────────────────────────────────────────────────────────

    @Test fun `the Sound screen lists the seven rows with boot last`() = runTest(dispatcher) {
        assertEquals(
            listOf(
                "sound_scroll", "sound_back", "sound_confirm", "sound_error",
                "sound_launch", "sound_notification", "boot_audio",
            ),
            AudioSettingsViewModel.SOUND_SLOTS.map { it.key },
        )
    }

    // ── Boot Sound as an ordinary row ────────────────────────────────────────

    @Test fun `an assigned boot audio row gets its name and the use-default affordance`() =
        runTest(dispatcher) {
            seedAssignment(UiMediaSlot.BOOT_AUDIO)
            seedDisplayName(UiMediaSlot.BOOT_AUDIO, "opening.wav")
            collectUiState()

            eventually("the Boot row surfaces its name and Use Default") {
                val state = vm.uiState.value
                state.soundLabels[UiMediaSlot.BOOT_AUDIO] == "opening.wav" &&
                    UiMediaSlot.BOOT_AUDIO in state.assignedSlots
            }
        }

    @Test fun `only rows on this screen can be assigned slots`() = runTest(dispatcher) {
        seedAssignment(UiMediaSlot.BOOT_VIDEO, ext = "mp4")
        seedAssignment(UiMediaSlot.GAMEBOOT_AUDIO)
        collectUiState()

        eventually("assignments surface in the ui state") {
            vm.uiState.value.soundLabels.isNotEmpty()
        }

        val state = vm.uiState.value
        assertFalse(UiMediaSlot.BOOT_VIDEO in state.soundLabels.keys, "boot video is not a sound row")
        assertFalse(UiMediaSlot.BOOT_VIDEO in state.assignedSlots)
        assertFalse(UiMediaSlot.GAMEBOOT_AUDIO in state.assignedSlots, "GameBoot has its own screen")
    }

    @Test fun `preview on the boot row plays through the boot previewer, not SoundPool`() =
        runTest(dispatcher) {
            vm.preview(UiMediaSlot.BOOT_AUDIO)
            advanceUntilIdle()
            // Bundled default (no custom assignment): the previewer gets null and resolves the
            // resource URI itself.
            verify(exactly = 1) { bootPreviewer.play(null) }
            verify(exactly = 0) { menuSound.play(any(), any()) }
        }

    @Test fun `preview on the boot row hands the custom assignment to the previewer`() =
        runTest(dispatcher) {
            seedAssignment(UiMediaSlot.BOOT_AUDIO, ext = "mp3")
            collectUiState()
            eventually("the assignment is visible") {
                UiMediaSlot.BOOT_AUDIO in vm.uiState.value.assignedSlots
            }

            vm.preview(UiMediaSlot.BOOT_AUDIO)
            advanceUntilIdle()

            verify(exactly = 1) {
                bootPreviewer.play(mediaFile(UiMediaSlot.BOOT_AUDIO, "mp3").absolutePath)
            }
        }

    @Test fun `menu sound rows still preview through SoundPool, never the boot previewer`() =
        runTest(dispatcher) {
            vm.preview(UiMediaSlot.SOUND_SCROLL)
            advanceUntilIdle()
            verify(exactly = 1) { menuSound.play(any(), any()) }
            verify(exactly = 0) { bootPreviewer.play(any()) }
        }

    @Test fun `tearing the screen down stops any running boot preview`() = runTest(dispatcher) {
        // onCleared() delegates here (it is protected, so the test drives the public seam);
        // the override is a one-liner covered by review.
        vm.stopBootPreview()
        verify(exactly = 1) { bootPreviewer.stop() }
    }

    // ── reset semantics ──────────────────────────────────────────────────────

    @Test fun `confirmReset clears the seven sounds including boot audio and never touches video media`() =
        runTest(dispatcher) {
            seedAssignment(UiMediaSlot.SOUND_SCROLL)
            seedAssignment(UiMediaSlot.SOUND_NOTIFICATION)
            seedAssignment(UiMediaSlot.BOOT_AUDIO)
            seedAssignment(UiMediaSlot.BOOT_VIDEO, ext = "mp4")
            seedAssignment(UiMediaSlot.GAMEBOOT_AUDIO)
            collectUiState()
            advanceUntilIdle()

            vm.requestReset()
            vm.confirmReset()

            eventually("the seven sounds are cleared") {
                !mediaFile(UiMediaSlot.SOUND_SCROLL, "wav").isFile &&
                    !mediaFile(UiMediaSlot.SOUND_NOTIFICATION, "wav").isFile &&
                    !mediaFile(UiMediaSlot.BOOT_AUDIO, "wav").isFile
            }

            // The negatives are as load-bearing as the positives, and time can't prove a
            // negative — assert them only after the deletions have observably landed.
            assertTrue(mediaFile(UiMediaSlot.BOOT_VIDEO, "mp4").isFile, "reset must never touch the boot video")
            assertTrue(mediaFile(UiMediaSlot.GAMEBOOT_AUDIO, "wav").isFile, "reset must never touch GameBoot media")
        }

    @Test fun `useDefault drops the boot audio assignment`() = runTest(dispatcher) {
        seedAssignment(UiMediaSlot.BOOT_AUDIO)

        vm.useDefault(UiMediaSlot.BOOT_AUDIO)

        eventually("the boot audio file is removed") {
            !mediaFile(UiMediaSlot.BOOT_AUDIO, "wav").isFile
        }
    }

    // ── the wired sounds (Phase 4) ───────────────────────────────────────────

    @Test fun `a rejected import surfaces the reason and plays the error sound`() = runTest(dispatcher) {
        // Registered as .mp4 while the bytes are a WAV: the resolver reports video/mp4, which the
        // sound gate refuses before anything is copied.
        val uri = Uri.parse("content://test/rejected.mp4")
        org.robolectric.Shadows.shadowOf(context.contentResolver)
            .registerInputStream(uri, java.io.ByteArrayInputStream(ByteArray(64)))
        vm.onPickerLaunchedFor(UiMediaSlot.SOUND_SCROLL)
        collectUiState()
        vm.onSoundPicked(uri)

        // The import runs the gate on Dispatchers.IO — wait for the message channel, which is
        // set AFTER the error sound plays, so the verify below can never race.
        eventually("the rejection dialog surfaces") { vm.uiState.value.message != null }

        assertNotNull(vm.uiState.value.message, "the rejection dialog must surface")
        verify { menuSound.play(MenuSound.ERROR) }
    }

    @Test fun `a confirmed reset plays the confirm sound`() = runTest(dispatcher) {
        vm.confirmReset()
        advanceUntilIdle()
        verify { menuSound.play(MenuSound.CONFIRM) }
    }
}
