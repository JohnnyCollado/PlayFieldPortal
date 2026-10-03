package com.playfieldportal.core.ui.notification

import com.playfieldportal.core.domain.model.NotificationAction
import com.playfieldportal.core.domain.model.NotificationDetail
import com.playfieldportal.core.domain.model.NotificationDetailCodec
import com.playfieldportal.core.domain.model.NotificationKind
import com.playfieldportal.core.domain.model.NotificationSeverity
import com.playfieldportal.core.domain.model.ResultItem
import com.playfieldportal.core.domain.model.ResultOutcome
import com.playfieldportal.core.domain.model.TaskKind
import com.playfieldportal.core.domain.repository.NotificationRepository
import com.playfieldportal.core.domain.repository.NotificationSettings
import com.playfieldportal.core.ui.sound.MenuSound
import com.playfieldportal.core.ui.sound.MenuSoundPlayer
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The center is the single settle seam every producer goes through, so it is where the tray's
 * rules live: a settled row rings the cue once, titles and bodies stay separate, details ride in
 * the payload, and a stop the user asked for is honoured once and recorded quietly.
 */
class BackgroundTaskCenterTest {

    private val notifications = mockk<NotificationRepository>(relaxed = true)
    private val settings = object : NotificationSettings {
        override val enabled = flowOf(true)
    }
    private val menuSound = mockk<MenuSoundPlayer>(relaxed = true)

    private fun center() = BackgroundTaskCenter(notifications, settings, menuSound)

    // ── The cue ───────────────────────────────────────────────────────────────

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

    // ── Title, body and detail ────────────────────────────────────────────────

    @Test
    fun `a settled row keeps its title and body apart`() {
        val center = center()
        center.start("scan_psx", "Scanning PlayStation…", TaskKind.SCAN)
        center.complete("scan_psx", "3 new ROM(s)", title = "PlayStation scan finished")

        coVerify(timeout = 2_000) {
            notifications.post(
                kind = NotificationKind.SCAN,
                severity = NotificationSeverity.SUCCESS,
                title = "PlayStation scan finished",
                body = "3 new ROM(s)",
                sourceKey = "task:scan_psx",
                action = NotificationAction.None,
                payload = null,
                read = false,
            )
        }
    }

    @Test
    fun `with no title given the trimmed task label is the title`() {
        val center = center()
        center.start("scan_psx", "Scanning PlayStation…", TaskKind.SCAN)
        center.fail("scan_psx", "Folder missing")

        coVerify(timeout = 2_000) {
            notifications.post(any(), NotificationSeverity.ERROR, "Scanning PlayStation", "Folder missing",
                any(), any(), any(), any())
        }
    }

    @Test
    fun `a detail is written to the payload`() {
        val detail = NotificationDetail.results(listOf(ResultItem("PSP", ResultOutcome.FAILED)))
        val center = center()
        center.start("scan_all", "Scanning all cards", TaskKind.SCAN)
        center.complete("scan_all", detail = detail)

        coVerify(timeout = 2_000) {
            notifications.post(any(), any(), any(), any(), any(), any(),
                payload = NotificationDetailCodec.encode(detail), read = false)
        }
    }

    // ── Stop ──────────────────────────────────────────────────────────────────

    @Test
    fun `a task with a stop handler is stoppable and one without is not`() {
        val center = center()
        center.start("a", "Scanning", TaskKind.SCAN, onStop = {})
        center.start("b", "Restoring", TaskKind.SCAN)

        val byId = center.running.value.associateBy { it.id }
        assertTrue(byId.getValue("a").stoppable)
        assertFalse(byId.getValue("b").stoppable)
    }

    @Test
    fun `requesting a stop marks the task stopping and calls the handler once`() {
        var calls = 0
        val center = center()
        center.start("scan", "Scanning", TaskKind.SCAN, onStop = { calls++ })

        assertTrue(center.requestStop("scan"))
        assertTrue(center.running.value.single().stopping)
        assertFalse(center.requestStop("scan"), "a second request is a no-op")
        assertEquals(1, calls)
    }

    @Test
    fun `a stop cannot be requested for a task without a handler or one that is gone`() {
        val center = center()
        center.start("restore", "Restoring", TaskKind.SCAN)

        assertFalse(center.requestStop("restore"))
        assertFalse(center.requestStop("nothing_here"))
        assertFalse(center.running.value.single().stopping)
    }

    @Test
    fun `a handler that throws still leaves the task stopping`() {
        val center = center()
        center.start("scan", "Scanning", TaskKind.SCAN, onStop = { error("boom") })

        assertTrue(center.requestStop("scan"))
        assertTrue(center.running.value.single().stopping)
    }

    @Test
    fun `progress ticks while stopping are ignored so the bar stays frozen`() {
        val center = center()
        center.start("scan", "Scanning", TaskKind.SCAN, current = 2, total = 10, onStop = {})
        center.requestStop("scan")
        center.progress("scan", current = 7, total = 10)

        assertEquals(2, center.running.value.single().current)
    }

    @Test
    fun `a stopped task records a read warning row without the cue`() {
        val center = center()
        center.start("scan", "Scanning PlayStation", TaskKind.SCAN, onStop = {})
        center.requestStop("scan")
        center.stopped("scan", "Stopped after 14 of 56", title = "PlayStation scan stopped")

        assertTrue(center.running.value.isEmpty())
        coVerify(timeout = 2_000) {
            notifications.post(any(), NotificationSeverity.WARNING, "PlayStation scan stopped",
                "Stopped after 14 of 56", "task:scan", any(), any(), read = true)
        }
        verify(exactly = 0) { menuSound.play(MenuSound.NOTIFICATION, any()) }
    }

    @Test
    fun `settling or cancelling drops the stop handler`() {
        var calls = 0
        val center = center()
        center.start("a", "Scanning", TaskKind.SCAN, onStop = { calls++ })
        center.complete("a")
        center.start("b", "Scanning", TaskKind.SCAN, onStop = { calls++ })
        center.cancel("b")

        assertFalse(center.requestStop("a"))
        assertFalse(center.requestStop("b"))
        assertEquals(0, calls)
    }

    @Test
    fun `startStoppable ties the stop to the calling coroutine`() = kotlinx.coroutines.test.runTest {
        val center = center()
        val job = launch {
            center.startStoppable("scan", "Scanning", TaskKind.SCAN)
            kotlinx.coroutines.awaitCancellation()
        }
        testScheduler.runCurrent()
        assertTrue(center.running.value.single().stoppable)

        center.requestStop("scan")
        testScheduler.runCurrent()
        assertTrue(job.isCancelled)
    }

    @Test
    fun `a cancellation the user asked for is recorded, any other one is dropped`() {
        val center = center()
        center.start("a", "Scanning", TaskKind.SCAN, onStop = {})
        center.requestStop("a")
        center.settleCancelled("a", "Stopped after 3 of 9")
        coVerify(timeout = 2_000) {
            notifications.post(any(), NotificationSeverity.WARNING, any(), "Stopped after 3 of 9", "task:a",
                any(), any(), read = true)
        }

        center.start("b", "Scanning", TaskKind.SCAN, onStop = {})
        center.settleCancelled("b", "Stopped")
        assertTrue(center.running.value.isEmpty())
        coVerify(exactly = 0) { notifications.post(any(), any(), any(), any(), "task:b", any(), any(), any()) }
    }

    @Test
    fun `restarting a live id without a handler makes it unstoppable`() {
        val center = center()
        center.start("a", "Scanning", TaskKind.SCAN, onStop = {})
        center.start("a", "Scanning again", TaskKind.SCAN)

        assertFalse(center.running.value.single().stoppable)
        assertFalse(center.requestStop("a"))
    }
}
