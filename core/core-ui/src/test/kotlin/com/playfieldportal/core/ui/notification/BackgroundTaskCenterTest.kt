package com.playfieldportal.core.ui.notification

import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.domain.model.TaskKind
import com.playfieldportal.core.domain.repository.NotificationRepository
import com.playfieldportal.core.domain.repository.NotificationSettings
import com.playfieldportal.core.ui.sound.MenuSound
import com.playfieldportal.core.ui.sound.MenuSoundPlayer
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The one behaviour this file pins: a settled row rings the notification cue exactly once, from the
 * single settle seam. That is what lets every producer (settings scans, workers, backups) get the
 * "a notification popped up" sound without each one wiring a player. Progress and dropped tasks stay
 * silent, so an automatic pass that finds nothing makes no sound.
 */
@RunWith(RobolectricTestRunner::class)
class BackgroundTaskCenterTest {

    private val notifications = mockk<NotificationRepository>(relaxed = true)
    private val settings = object : NotificationSettings {
        override val enabled = flowOf(true)
        override val mirrorToShade = flowOf(true)
    }
    private val menuSound = mockk<MenuSoundPlayer>(relaxed = true)

    private fun center() = BackgroundTaskCenter(
        ApplicationProvider.getApplicationContext(),
        notifications,
        settings,
        menuSound,
    )

    @Test
    fun `completing a task rings the notification cue`() {
        val center = center()
        center.start("scan_psx", "Scanning PlayStation", TaskKind.SCAN)
        center.complete("scan_psx", "3 new ROM(s)")

        verify(exactly = 1) { menuSound.play(MenuSound.NOTIFICATION, any()) }
    }

    @Test
    fun `a one-shot report rings the cue`() {
        val center = center()
        center.report("backup_create", "Backup created")

        verify(exactly = 1) { menuSound.play(MenuSound.NOTIFICATION, any()) }
    }

    @Test
    fun `progress ticks never ring the cue`() {
        val center = center()
        center.start("scan_psx", "Scanning PlayStation", TaskKind.SCAN)
        center.progress("scan_psx", current = 5, total = 10)

        verify(exactly = 0) { menuSound.play(MenuSound.NOTIFICATION, any()) }
    }

    @Test
    fun `cancelling a task is silent — no row, no cue`() {
        val center = center()
        center.start("scan_psx", "Scanning PlayStation", TaskKind.SCAN)
        center.cancel("scan_psx")

        verify(exactly = 0) { menuSound.play(MenuSound.NOTIFICATION, any()) }
    }
}
