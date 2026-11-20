package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.update.ApkDownload
import com.playfieldportal.core.data.update.AppUpdateCheckResult
import com.playfieldportal.core.data.update.AppUpdateChecker
import com.playfieldportal.core.data.update.AppUpdateDownloader
import com.playfieldportal.core.data.update.AppUpdateSnapshot
import com.playfieldportal.core.domain.model.appupdate.AppRelease
import com.playfieldportal.core.domain.model.appupdate.AppReleaseApk
import com.playfieldportal.core.domain.model.appupdate.SemVer
import com.playfieldportal.core.ui.notification.BackgroundTaskCenter
import com.playfieldportal.feature.settings.update.InstallStart
import com.playfieldportal.feature.settings.update.UpdateInstaller
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Settings ▸ System ▸ System Update. Robolectric's application id has no `.lite` segment, so these
 * run as the Full edition.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SystemUpdateViewModelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val base = "https://github.com/JohnnyCollado/PlayFieldPortal/releases"

    private fun release(version: String, summary: List<String> = listOf("Improved system performance"), withApk: Boolean = true) =
        AppRelease(
            version = version,
            publishedOn = "2026-11-01",
            summary = summary,
            notesUrl = "$base/tag/$version",
            apks = if (withApk) {
                listOf(AppReleaseApk("PlayFieldPortal-$version-full.apk", "$base/download/$version/PlayFieldPortal-$version-full.apk", 1000))
            } else {
                emptyList()
            },
        )

    private val snapshot = MutableStateFlow(
        AppUpdateSnapshot(installed = SemVer(1, 3, 3), releases = listOf(release("1.3.4"), release("1.3.3"), release("1.3.2")), lastCheckAt = 0),
    )
    private val notify = MutableStateFlow(true)
    private val checker = mockk<AppUpdateChecker>(relaxed = true)
    private val downloader = mockk<AppUpdateDownloader>()
    private val tasks = mockk<BackgroundTaskCenter>(relaxed = true)

    private class FakeInstaller(override val supported: Boolean = true, var result: InstallStart = InstallStart.Started) : UpdateInstaller {
        val installed = mutableListOf<File>()
        override suspend fun install(apk: File): InstallStart {
            installed += apk
            return result
        }
    }

    private var installer = FakeInstaller()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { checker.snapshot } returns snapshot
        every { checker.notificationsEnabled } returns notify
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun newVm() = SystemUpdateViewModel(context, checker, downloader, installer, tasks)

    /** The state once everything the last action launched has run. */
    private fun TestScope.state(vm: SystemUpdateViewModel): SystemUpdateUiState {
        advanceUntilIdle()
        return vm.uiState.value
    }

    // ── Status ────────────────────────────────────────────────────────────────

    @Test
    fun `a newer release is offered for install`() = runTest {
        val state = state(newVm())

        assertEquals("1.3.3 · Full", state.installedLabel)
        assertEquals("1.3.4", state.availableVersion)
        assertTrue(state.canInstall)
    }

    @Test
    fun `nothing is offered when the installed build is the newest`() = runTest {
        snapshot.value = snapshot.value.copy(installed = SemVer(1, 3, 4))

        val state = state(newVm())

        assertNull(state.availableVersion)
        assertFalse(state.canInstall)
        assertEquals("Up to date", state.latestLabel)
    }

    @Test
    fun `a build that cannot take a release apk shows the update but not the install`() = runTest {
        installer = FakeInstaller(supported = false)

        val state = state(newVm())

        assertEquals("1.3.4", state.availableVersion)
        assertFalse(state.canInstall)
    }

    // ── Changelog ─────────────────────────────────────────────────────────────

    @Test
    fun `the changelog lists every release, marks new and installed, and opens the new one`() = runTest {
        val changelog = state(newVm()).changelog

        assertEquals(listOf("1.3.4", "1.3.3", "1.3.2"), changelog.map { it.version })
        assertEquals(listOf("New", "Installed", null), changelog.map { it.tag })
        assertEquals(listOf(true, false, false), changelog.map { it.expanded })
        assertEquals(listOf("Improved system performance"), changelog.first().summary)
    }

    @Test
    fun `a release expands and collapses on select`() = runTest {
        val vm = newVm()

        vm.toggleRelease("1.3.2")
        assertTrue(state(vm).changelog.single { it.version == "1.3.2" }.expanded)

        vm.toggleRelease("1.3.2")
        assertFalse(state(vm).changelog.single { it.version == "1.3.2" }.expanded)

        // The new release starts open, so its first select closes it.
        vm.toggleRelease("1.3.4")
        assertFalse(state(vm).changelog.single { it.version == "1.3.4" }.expanded)
    }

    // ── Checking ──────────────────────────────────────────────────────────────

    @Test
    fun `a manual check reports what it found`() = runTest {
        coEvery { checker.check(manual = true) } returns AppUpdateCheckResult.Offline
        val vm = newVm()

        vm.checkNow()

        coVerify(exactly = 1) { checker.check(manual = true) }
        assertFalse(state(vm).isChecking)
        assertEquals("Couldn't reach GitHub. Try again later.", state(vm).checkMessage)
    }

    @Test
    fun `a manual check's message gives way once a later check has run`() = runTest {
        coEvery { checker.check(manual = true) } returns AppUpdateCheckResult.UpToDate
        val vm = newVm()

        vm.checkNow()
        assertEquals("You have the latest version.", state(vm).checkMessage)

        // The next day's automatic check: its answer is on the rows above, so the old message
        // must not sit beside it saying something else.
        snapshot.value = snapshot.value.copy(lastCheckAt = 5_000)

        assertNull(state(vm).checkMessage)
    }

    @Test
    fun `the notification switch is stored`() = runTest {
        newVm().setNotificationsEnabled(false)

        coVerify { checker.setNotificationsEnabled(false) }
    }

    // ── Download and install ──────────────────────────────────────────────────

    @Test
    fun `download hands the finished apk to the installer`() = runTest {
        coEvery { downloader.downloadApk(any(), any(), any(), any(), any()) } returns ApkDownload.Done
        val vm = newVm()

        vm.downloadAndInstall()

        coVerify(exactly = 1) {
            downloader.downloadApk("$base/download/1.3.4/PlayFieldPortal-1.3.4-full.apk", any(), 1000, any(), any())
        }
        assertEquals(1, installer.installed.size)
        assertTrue(installer.installed.single().startsWith(context.cacheDir), "the apk stays in the app's own cache")
        assertIs<UpdateDownload.HandedToInstaller>(state(vm).download)
    }

    @Test
    fun `a failed download is reported and nothing is installed`() = runTest {
        coEvery { downloader.downloadApk(any(), any(), any(), any(), any()) } returns ApkDownload.Failure("http 404")
        val vm = newVm()

        vm.downloadAndInstall()

        assertTrue(installer.installed.isEmpty())
        assertIs<UpdateDownload.Failed>(state(vm).download)
    }

    @Test
    fun `a release without this edition's apk fails without downloading`() = runTest {
        snapshot.value = snapshot.value.copy(releases = listOf(release("1.3.4", withApk = false)))
        val vm = newVm()

        vm.downloadAndInstall()

        coVerify(exactly = 0) { downloader.downloadApk(any(), any(), any(), any(), any()) }
        assertIs<UpdateDownload.Failed>(state(vm).download)
    }

    @Test
    fun `a download that is not a newer build of this app is refused`() = runTest {
        coEvery { downloader.downloadApk(any(), any(), any(), any(), any()) } returns ApkDownload.Done
        installer = FakeInstaller(result = InstallStart.NotAnUpdate)
        val vm = newVm()

        vm.downloadAndInstall()

        val download = state(vm).download
        assertIs<UpdateDownload.Failed>(download)
        assertEquals("The download is not a newer version of this app, so it was not installed.", download.message)
    }
}
