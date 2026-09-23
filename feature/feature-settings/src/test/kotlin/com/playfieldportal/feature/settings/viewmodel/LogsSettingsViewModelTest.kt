package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.repository.ControllerLayoutRepository
import com.playfieldportal.core.domain.model.ControllerLayoutPrefs
import com.playfieldportal.core.domain.model.NotificationAction
import com.playfieldportal.core.domain.model.NotificationSeverity
import com.playfieldportal.core.domain.model.XYLayout
import com.playfieldportal.core.ui.notification.BackgroundTaskCenter
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Settings ▸ Logs: the file list (newest first, the running session tagged), the Clear All Logs
 * confirmation, the tray report for a clear, and the X/Y layout the Share shortcut resolves through.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LogsSettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    // Unconfined Main on the same scheduler — see AudioSettingsViewModelTest for why.
    private val mainDispatcher = UnconfinedTestDispatcher(dispatcher.scheduler)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val logsDir get() = File(context.filesDir, "logs")
    private val tasks = mockk<BackgroundTaskCenter>(relaxed = true)
    private val controllerLayout = mockk<ControllerLayoutRepository>()

    @Before fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        logsDir.deleteRecursively()
        every { controllerLayout.prefs } returns flowOf(ControllerLayoutPrefs())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        logsDir.deleteRecursively()
    }

    private fun newVm() = LogsSettingsViewModel(context, controllerLayout, tasks)

    /** File IO runs on real IO threads — poll like AudioSettingsViewModelTest does. */
    private fun TestScope.eventually(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out waiting for: $what")
            Thread.sleep(50)
            testScheduler.advanceUntilIdle()
        }
    }

    private fun seedLog(name: String, bytes: Int, modifiedAt: Long): File {
        logsDir.mkdirs()
        return File(logsDir, name).apply {
            writeBytes(ByteArray(bytes))
            setLastModified(modifiedAt)
        }
    }

    // ── Loading ───────────────────────────────────────────────────────────────

    @Test fun `state is not loaded until the directory has been read`() = runTest(dispatcher) {
        // The screen composes no rows before this flips, so Clear All Logs can never take the
        // screen's first focus while the list is still empty.
        val vm = newVm()
        eventually("loaded") { vm.uiState.value.loaded }
    }

    @Test fun `no logs directory loads as an empty list`() = runTest(dispatcher) {
        val vm = newVm()
        eventually("loaded") { vm.uiState.value.loaded }
        assertTrue(vm.uiState.value.logFiles.isEmpty())
        assertEquals("No files", vm.uiState.value.totalSummary)
    }

    @Test fun `logs load newest first and only the newest is the current session`() = runTest(dispatcher) {
        seedLog("pfp-20260922-033206.log", 2048, 1_000_000L)
        seedLog("pfp-20260923-031805.log", 4096, 3_000_000L)
        seedLog("pfp-20260923-000954.log", 1024, 2_000_000L)

        val vm = newVm()
        eventually("loaded") { vm.uiState.value.loaded }

        val files = vm.uiState.value.logFiles
        assertEquals(
            listOf("pfp-20260923-031805.log", "pfp-20260923-000954.log", "pfp-20260922-033206.log"),
            files.map { it.name },
        )
        assertEquals(listOf(true, false, false), files.map { it.isCurrent })
        assertEquals(listOf("4 KB", "1 KB", "2 KB"), files.map { it.size })
    }

    @Test fun `total summary counts files and bytes`() = runTest(dispatcher) {
        seedLog("pfp-20260923-031805.log", 512 * 1024, 2_000L)
        seedLog("pfp-20260923-000954.log", 138 * 1024, 1_000L)

        val vm = newVm()
        eventually("loaded") { vm.uiState.value.loaded }
        assertEquals("2 files · 650 KB", vm.uiState.value.totalSummary)
    }

    @Test fun `refresh picks up files written after the screen opened`() = runTest(dispatcher) {
        val vm = newVm()
        eventually("loaded") { vm.uiState.value.loaded }

        seedLog("pfp-20260923-031805.log", 1024, 1_000L)
        vm.refresh()
        eventually("new file listed") { vm.uiState.value.logFiles.size == 1 }
    }

    @Test fun `xy layout follows the controller preference`() = runTest(dispatcher) {
        every { controllerLayout.prefs } returns flowOf(ControllerLayoutPrefs(xyLayout = XYLayout.SWAPPED))
        val vm = newVm()
        eventually("layout applied") { vm.uiState.value.xyLayout == XYLayout.SWAPPED }
    }

    // ── Clear All Logs ────────────────────────────────────────────────────────

    @Test fun `request clear only asks when there is something to clear`() = runTest(dispatcher) {
        val vm = newVm()
        eventually("loaded") { vm.uiState.value.loaded }
        vm.requestClear()
        assertFalse(vm.uiState.value.confirmClearVisible)
    }

    @Test fun `request then dismiss leaves every file in place`() = runTest(dispatcher) {
        val file = seedLog("pfp-20260923-031805.log", 1024, 1_000L)
        val vm = newVm()
        eventually("loaded") { vm.uiState.value.loaded }

        vm.requestClear()
        assertTrue(vm.uiState.value.confirmClearVisible)
        vm.dismissClear()
        assertFalse(vm.uiState.value.confirmClearVisible)
        assertTrue(file.exists())
        verify(exactly = 0) { tasks.report(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `confirm clear deletes the files, reloads and reports to the tray`() = runTest(dispatcher) {
        seedLog("pfp-20260923-031805.log", 512 * 1024, 2_000L)
        seedLog("pfp-20260923-000954.log", 138 * 1024, 1_000L)
        val vm = newVm()
        eventually("loaded") { vm.uiState.value.logFiles.size == 2 }

        vm.requestClear()
        vm.confirmClear()
        assertFalse(vm.uiState.value.confirmClearVisible)

        eventually("files deleted") { logsDir.listFiles().isNullOrEmpty() }
        eventually("tray report") {
            runCatching {
                verify {
                    tasks.report(
                        id = "logs_clear",
                        label = "Logs",
                        message = "Deleted 2 files (650 KB)",
                        severity = NotificationSeverity.SUCCESS,
                        kind = any(),
                        action = NotificationAction.OpenSettingsScreen("settings_logs"),
                    )
                }
            }.isSuccess
        }
        // The list is re-read rather than blanked: the running session writes straight back into
        // a fresh file, and the screen should show it.
        eventually("list reloaded") { vm.uiState.value.logFiles.none { it.name == "pfp-20260923-000954.log" } }
    }

    // ── Labels ────────────────────────────────────────────────────────────────

    @Test fun `started label reads today, yesterday or a date from the file name`() {
        val now = LocalDateTime.of(2026, 9, 23, 9, 0)
        assertEquals("Started today, 3:18 AM", LogsSettingsViewModel.startedLabel("pfp-20260923-031805.log", now))
        assertEquals("Started today, 12:09 AM", LogsSettingsViewModel.startedLabel("pfp-20260923-000954.log", now))
        assertEquals("Started yesterday, 3:40 PM", LogsSettingsViewModel.startedLabel("pfp-20260922-154047.log", now))
        assertEquals("Started Sep 1, 8:05 AM", LogsSettingsViewModel.startedLabel("pfp-20260901-080500.log", now))
        assertNull(LogsSettingsViewModel.startedLabel("something-else.txt", now))
    }

    @Test fun `sizes round to whole units and never read as zero`() {
        assertEquals("<1 KB", LogsSettingsViewModel.formatSize(200))
        assertEquals("1 KB", LogsSettingsViewModel.formatSize(1024))
        assertEquals("650 KB", LogsSettingsViewModel.formatSize(665_783))
        assertEquals("1.5 MB", LogsSettingsViewModel.formatSize(1_572_864))
    }
}
