package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.data.repository.LockScreenImage
import com.playfieldportal.core.data.repository.ThemePrefKeys
import com.playfieldportal.core.domain.model.NotificationSeverity
import com.playfieldportal.core.ui.notification.BackgroundTaskCenter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Display ▸ Lock Screen Image: choose an image, reuse the launcher's own wallpaper, or reset.
 * Outcomes go to the notification tray.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LockScreenViewModelTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val lockScreen = mockk<LockScreenImage>(relaxed = true) {
        every { state } returns flowOf(LockScreenImage.State())
    }
    private val tasks = mockk<BackgroundTaskCenter>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        runBlocking { context.pfpDataStore.edit { it.clear() } }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = LockScreenViewModel(context, lockScreen, tasks)

    private fun awaitReport(severity: NotificationSeverity) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            runCatching {
                verify { tasks.report(id = LockScreenViewModel.TRAY_ID, label = any(), message = any(), severity = severity, kind = any(), action = any(), detail = any(), read = any()) }
            }.onSuccess { return }
            Thread.sleep(50)
        }
        verify { tasks.report(id = LockScreenViewModel.TRAY_ID, label = any(), message = any(), severity = severity, kind = any(), action = any(), detail = any(), read = any()) }
    }

    @Test
    fun `a picked image is set as the user's lock screen`() {
        val file = File(context.cacheDir, "pick.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        coEvery { lockScreen.set(any(), LockScreenImage.Source.USER) } returns LockScreenImage.Result.Set

        vm().onImagePicked(Uri.fromFile(file))

        awaitReport(NotificationSeverity.SUCCESS)
        coVerify { lockScreen.set(byteArrayOf(1, 2, 3), LockScreenImage.Source.USER) }
    }

    @Test
    fun `the launcher wallpaper is reused as is`() {
        val poster = File(context.filesDir, "wallpaper/poster.jpg").apply { parentFile?.mkdirs(); writeBytes(ByteArray(4)) }
        runBlocking { context.pfpDataStore.edit { it[ThemePrefKeys.CUSTOM_WALLPAPER] = poster.absolutePath } }
        coEvery { lockScreen.setFromFile(poster.absolutePath, LockScreenImage.Source.USER) } returns LockScreenImage.Result.Set

        vm().useLauncherWallpaper()

        awaitReport(NotificationSeverity.SUCCESS)
        coVerify { lockScreen.setFromFile(poster.absolutePath, LockScreenImage.Source.USER) }
    }

    @Test
    fun `with no launcher wallpaper there is nothing to reuse`() {
        vm().useLauncherWallpaper()

        awaitReport(NotificationSeverity.ERROR)
        coVerify(exactly = 0) { lockScreen.setFromFile(any(), any()) }
    }

    @Test
    fun `a refused image reports why`() {
        val file = File(context.cacheDir, "bad.png").apply { writeBytes(byteArrayOf(9)) }
        coEvery { lockScreen.set(any(), any()) } returns LockScreenImage.Result.Failed("nope")

        vm().onImagePicked(Uri.fromFile(file))

        awaitReport(NotificationSeverity.ERROR)
    }

    @Test
    fun `reset clears it`() {
        vm().reset()

        awaitReport(NotificationSeverity.SUCCESS)
        coVerify { lockScreen.clear() }
    }
}
