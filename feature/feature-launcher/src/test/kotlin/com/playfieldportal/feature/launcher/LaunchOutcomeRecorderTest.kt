package com.playfieldportal.feature.launcher

import com.playfieldportal.core.data.database.dao.LaunchOutcomeDao
import com.playfieldportal.core.domain.model.NotificationAction
import com.playfieldportal.core.domain.model.NotificationDetail
import com.playfieldportal.core.domain.model.NotificationKind
import com.playfieldportal.core.domain.model.NotificationSeverity
import com.playfieldportal.core.ui.notification.BackgroundTaskCenter
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A failed launch becomes a Notes row in the panel — the record the user finds after the recovery
 * sheet is long gone — through the shared task center, so it gets the cue like every other row.
 */
class LaunchOutcomeRecorderTest {

    private val dao = mockk<LaunchOutcomeDao>(relaxed = true)
    private val tasks = mockk<BackgroundTaskCenter>(relaxed = true)

    private fun outcome(status: LaunchOutcomeStatus, code: String? = null) = LaunchOutcome(
        gameId = 7L, gameTitle = "Ape Escape", platformId = "psx",
        emulatorId = "duckstation", emulatorName = "DuckStation", corePath = null, coreName = null,
        source = LaunchSource.PLATFORM_DEFAULT, status = status,
        failureReason = if (status == LaunchOutcomeStatus.SUCCEEDED) null else "never foregrounded",
        launchedAtMs = 1L, errorCode = code,
    )

    @Test
    fun `a failed launch posts a notes row with its code and facts`() = runTest {
        coEvery { dao.recentForGame(7L, any()) } returns emptyList()
        val recorder = LaunchOutcomeRecorder(dao, StandardTestDispatcher(testScheduler), tasks)
        recorder.record(outcome(LaunchOutcomeStatus.NEVER_FOREGROUNDED, code = "LN-4003"))

        val detail = slot<NotificationDetail>()
        verify {
            tasks.report(
                id = "launch_7",
                label = "Couldn't launch Ape Escape",
                message = "never foregrounded",
                severity = NotificationSeverity.ERROR,
                kind = NotificationKind.LAUNCH,
                action = NotificationAction.OpenGame(7L),
                detail = capture(detail),
                read = false,
            )
        }
        val notes = assertIs<NotificationDetail.Notes>(detail.captured)
        assertEquals("LN-4003", notes.code)
        assertTrue(notes.facts.any { it.label == "Emulator" && it.value == "DuckStation" })
    }

    @Test
    fun `a success posts nothing`() = runTest {
        val recorder = LaunchOutcomeRecorder(dao, StandardTestDispatcher(testScheduler), tasks)
        recorder.record(outcome(LaunchOutcomeStatus.SUCCEEDED))

        verify(exactly = 0) { tasks.report(any(), any(), any(), any(), any(), any(), any(), any()) }
    }
}
