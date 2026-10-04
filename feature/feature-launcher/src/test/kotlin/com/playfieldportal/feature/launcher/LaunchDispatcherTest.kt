package com.playfieldportal.feature.launcher

import android.content.Context
import android.content.Intent
import com.playfieldportal.core.domain.model.EmulatorProfile
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.IntentType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the B1 launch funnel: outcomes recorded per settled launch and the lifecycle-driven
 * foreground verification ([LaunchDispatcher.STOP_WINDOW_MS]). The verdict comes from whether the
 * emulator actually covered the launcher, never from a session-duration threshold. The clock is
 * injected and shares the runTest scheduler, so the watchdog window is driven by [advanceTimeBy]
 * — no real sleeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LaunchDispatcherTest {

    private val game = Game(
        id = 7L,
        title = "Crash Bandicoot",
        platformId = "psx",
        romPath = "/roms/psx/crash.bin",
    )

    private val resolved = ResolvedLaunch(
        profile = EmulatorProfile(
            id = "duckstation",
            name = "DuckStation",
            packageName = "com.github.stenzek.duckstation",
            intentType = IntentType.ACTION_VIEW,
            supportedPlatformIds = listOf("ps1"),
        ),
        source = LaunchSource.PLATFORM_DEFAULT,
    )

    private class Harness(val scope: TestScope, gameBootOn: Boolean = false) {
        val context: Context = mockk(relaxed = true) {
            every { packageName } returns "com.test"
        }
        val recorder: LaunchOutcomeRecorder = mockk(relaxed = true)
        val intent: Intent = mockk(relaxed = true)
        var now = 0L

        // GameBoot switched off unless a case asks for it: awaitPresentation returns immediately,
        // so most tests keep pinning the dispatcher's own behaviour rather than the gate's.
        val gameBootPreferences: com.playfieldportal.core.data.repository.GameBootPreferences =
            mockk(relaxed = true) {
                every { gameBootEnabledFlow } returns kotlinx.coroutines.flow.flowOf(gameBootOn)
            }
        val uiMediaStore: com.playfieldportal.core.data.repository.UiMediaStore = mockk(relaxed = true) {
            every { pathFor(any()) } returns null
        }
        val gameBootAudioPlayer: com.playfieldportal.core.ui.media.UiMediaAudioPlayer = mockk(relaxed = true)
        val gameBootGate = GameBootGate(context, gameBootPreferences, uiMediaStore, gameBootAudioPlayer)
        val menuSound: com.playfieldportal.core.ui.sound.MenuSoundPlayer = mockk(relaxed = true)
        val autoCoreMemory: AutoCoreMemory = mockk(relaxed = true)
        val gameRepository: com.playfieldportal.core.domain.repository.GameRepository = mockk(relaxed = true)

        /** Who is holding ambience down right now (AmbienceController's suppressor set). */
        val ambienceOwners = mutableSetOf<String>()
        val ambience = com.playfieldportal.core.ui.sound.AmbienceSuppressor { owner, suppressed ->
            if (suppressed) ambienceOwners += owner else ambienceOwners -= owner
        }
        val gameHoldsAmbience: Boolean
            get() = com.playfieldportal.core.ui.sound.AmbienceController.OWNER_GAME in ambienceOwners

        val dispatcher = LaunchDispatcher(
            context = context,
            outcomeRecorder = recorder,
            scope = scope,
            clock = LaunchClock { now },
            gameBootGate = gameBootGate,
            menuSound = menuSound,
            autoCoreMemory = autoCoreMemory,
            handoffTracker = GameHandoffTracker({ now }, emptySet()),
            gameRepository = gameRepository,
            ambience = ambience,
        )
    }

    private fun TestScope.harness(gameBootOn: Boolean = false) = Harness(this, gameBootOn)

    /**
     * The gate reads its media paths on Dispatchers.IO, which the test scheduler cannot drive, so
     * waiting on it polls with a real sleep (same pattern as GameBootGateTest.eventually).
     */
    private fun TestScope.eventually(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out waiting for: $what")
            Thread.sleep(20)
            testScheduler.runCurrent()
        }
    }

    // runTest auto-advances the virtual clock; keep every dispatcher job on that same scheduler so
    // scope.launch work (verdict recording, watchdog) is driven by advanceUntilIdle/advanceTimeBy.

    private suspend fun Harness.launchAccepted() {
        coEvery { recorder.record(any()) } returns Unit
        val result = dispatcher.launch(game, resolved, intent)
        assertIs<LaunchDispatchResult.Accepted>(result)
    }

    // A RetroArch core hand-off: the launch that must pin the console to its core.
    private val retroarchResolved = ResolvedLaunch(
        profile = EmulatorProfile(
            id = "auto_retroarch_gambatte_libretro_android",
            name = "RetroArch · Gambatte (GB/GBC)",
            packageName = "com.retroarch",
            intentType = IntentType.COMPONENT,
            supportedPlatformIds = listOf("gb", "gbc"),
            autoSource = "retroarch-core",
        ),
        source = LaunchSource.CATALOG_DEFAULT,
    )

    @Test
    fun `accepted retroarch core launch remembers the console's core`() = runTest {
        val h = harness()
        coEvery { h.recorder.record(any()) } returns Unit

        h.dispatcher.launch(game, retroarchResolved, h.intent)

        // One write per successful core launch: Game Detail and the XMB direct-launch path both
        // funnel here, so the record stays consistent for both entry points.
        coVerify(exactly = 1) {
            h.autoCoreMemory.remember("psx", "auto_retroarch_gambatte_libretro_android")
        }
    }

    // ── Last played ───────────────────────────────────────────────────────────
    // The launch is the only moment PFP knows a game was played, so this stamp is what
    // "Recently Played" and the UMD slot's fallback rest on.

    @Test
    fun `an accepted launch stamps the game as played`() = runTest {
        val h = harness()
        coEvery { h.recorder.record(any()) } returns Unit

        h.dispatcher.launch(game, resolved, h.intent)

        coVerify(exactly = 1) { h.gameRepository.markLaunched(7L, any()) }
    }

    @Test
    fun `a launch that never started does not stamp the game`() = runTest {
        val h = harness()
        coEvery { h.recorder.record(any()) } returns Unit
        every { h.context.startActivity(any()) } throws android.content.ActivityNotFoundException()

        h.dispatcher.launch(game, resolved, h.intent)

        coVerify(exactly = 0) { h.gameRepository.markLaunched(any(), any()) }
    }

    @Test
    fun `a failed stamp never fails the launch`() = runTest {
        val h = harness()
        coEvery { h.recorder.record(any()) } returns Unit
        coEvery { h.gameRepository.markLaunched(any(), any()) } throws IllegalStateException("db closed")

        val result = h.dispatcher.launch(game, resolved, h.intent)

        assertIs<LaunchDispatchResult.Accepted>(result)
    }

    @Test
    fun `a shortcut launch stamps the game only when it started`() = runTest {
        val h = harness()

        h.dispatcher.launchShortcut(game) { Result.failure(IllegalStateException("no shortcut")) }
        coVerify(exactly = 0) { h.gameRepository.markLaunched(any(), any()) }

        h.dispatcher.launchShortcut(game) { Result.success(Unit) }
        coVerify(exactly = 1) { h.gameRepository.markLaunched(7L, any()) }
    }

    @Test
    fun `standalone launch does not write core memory`() = runTest {
        val h = harness()
        coEvery { h.recorder.record(any()) } returns Unit

        h.dispatcher.launch(game, resolved, h.intent)

        coVerify(exactly = 0) { h.autoCoreMemory.remember(any(), any()) }
    }

    @Test
    fun `intent failure records INTENT_FAILED and offers recovery`() = runTest {
        val h = harness()
        every { h.context.startActivity(any()) } throws android.content.ActivityNotFoundException("nope")

        val result = h.dispatcher.launch(game, resolved, h.intent)

        assertIs<LaunchDispatchResult.Rejected>(result)
        assertEquals("Emulator not found. Is it installed?", result.message)
        coVerify { h.recorder.record(match {
            it.status == LaunchOutcomeStatus.INTENT_FAILED &&
                it.emulatorId == "duckstation" &&
                it.failureReason == "Emulator not found. Is it installed?"
        }) }
        assertIs<LaunchRecoveryRequest>(h.dispatcher.recoveryRequests.value)
    }

    @Test
    fun `a failed dispatch plays the error sound and offers recovery`() = runTest {
        val h = harness()
        every { h.context.startActivity(any()) } throws android.content.ActivityNotFoundException("nope")

        val result = h.dispatcher.launch(game, resolved, h.intent)

        assertIs<LaunchDispatchResult.Rejected>(result)
        verify { h.menuSound.play(com.playfieldportal.core.ui.sound.MenuSound.ERROR) }
        assertIs<LaunchRecoveryRequest>(h.dispatcher.recoveryRequests.value)
    }

    @Test
    fun `an accepted dispatch does not play the error sound`() = runTest {
        val h = harness()
        h.launchAccepted()
        verify(exactly = 0) { h.menuSound.play(any(), any()) }
    }

    @Test
    fun `successful dispatch leaves recovery clear and arms verification`() = runTest {
        val h = harness()
        h.launchAccepted()
        assertNull(h.dispatcher.recoveryRequests.value)
        // Nothing yet settled — the verdict comes from the lifecycle or the stop-window watchdog.
        coVerify(exactly = 0) { h.recorder.record(any()) }
    }

    @Test
    fun `host never stops inside the window records never-foregrounded and offers recovery`() = runTest {
        val h = harness()
        h.launchAccepted()

        h.now = LaunchDispatcher.STOP_WINDOW_MS + 1
        advanceTimeBy(LaunchDispatcher.STOP_WINDOW_MS + 1)
        advanceUntilIdle()

        coVerify { h.recorder.record(match {
            it.status == LaunchOutcomeStatus.NEVER_FOREGROUNDED &&
                it.failureReason!!.contains("never came to the foreground")
        }) }
        assertIs<LaunchRecoveryRequest>(h.dispatcher.recoveryRequests.value)
    }

    @Test
    fun `returning instantly after the emulator covered the launcher records SUCCEEDED`() = runTest {
        val h = harness()
        h.launchAccepted()

        // The emulator took the foreground and the user chose to close it right away. That is a
        // deliberate decision, not a crash — an instant close must record success and never pop
        // recovery UI, however short the session was.
        h.dispatcher.onHostStopped()
        h.now = 1_000
        h.dispatcher.onHostResumed()
        advanceUntilIdle()

        coVerify { h.recorder.record(match {
            it.status == LaunchOutcomeStatus.SUCCEEDED && it.failureReason == null
        }) }
        assertNull(h.dispatcher.recoveryRequests.value, "an instant deliberate close is a success")
    }

    @Test
    fun `returning before the emulator covered the launcher records never-foregrounded`() = runTest {
        val h = harness()
        h.launchAccepted()

        // startActivity succeeded, but the launcher was never covered and the user is back almost
        // immediately (before the stop window): the emulator never demonstrably ran.
        h.now = 1_000
        h.dispatcher.onHostResumed()
        advanceUntilIdle()

        coVerify { h.recorder.record(match {
            it.status == LaunchOutcomeStatus.NEVER_FOREGROUNDED &&
                it.failureReason!!.contains("never came to the foreground")
        }) }
        assertIs<LaunchRecoveryRequest>(h.dispatcher.recoveryRequests.value)
    }

    @Test
    fun `a real session records SUCCEEDED silently`() = runTest {
        val h = harness()
        h.launchAccepted()

        h.dispatcher.onHostStopped()
        h.now = 60_000
        h.dispatcher.onHostResumed()
        advanceUntilIdle()

        coVerify { h.recorder.record(match {
            it.status == LaunchOutcomeStatus.SUCCEEDED && it.failureReason == null
        }) }
        assertNull(h.dispatcher.recoveryRequests.value, "success must never pop recovery UI")
    }

    @Test
    fun `host stop inside the window cancels the watchdog and leaves the launch pending`() = runTest {
        val h = harness()
        h.launchAccepted()

        // The emulator covers the launcher well inside the stop window (the normal case), then the
        // user plays on. The watchdog must not flag anything: the verdict waits for onHostResumed.
        h.now = 1_000
        h.dispatcher.onHostStopped()
        advanceTimeBy(LaunchDispatcher.STOP_WINDOW_MS + 1_000)
        advanceUntilIdle()

        coVerify(exactly = 0) { h.recorder.record(any()) }
        assertNull(h.dispatcher.recoveryRequests.value)

        // And the eventual return (a real session) settles it as a success.
        h.now = 60_000
        h.dispatcher.onHostResumed()
        advanceUntilIdle()
        coVerify { h.recorder.record(match { it.status == LaunchOutcomeStatus.SUCCEEDED }) }
    }

    @Test
    fun `resume with no pending launch is ignored`() = runTest {
        val h = harness()
        h.dispatcher.onHostStopped()
        h.dispatcher.onHostResumed()
        advanceUntilIdle()
        coVerify(exactly = 0) { h.recorder.record(any()) }
        assertNull(h.dispatcher.recoveryRequests.value)
    }

    @Test
    fun `recovery history line counts recent failures for the game`() = runTest {
        val h = harness()
        coEvery { h.recorder.record(any()) } returns Unit
        coEvery { h.recorder.recentForGame(7L, 5) } returns listOf(
            outcome(LaunchOutcomeStatus.INTENT_FAILED),
            outcome(LaunchOutcomeStatus.SUCCEEDED),
            outcome(LaunchOutcomeStatus.NEVER_FOREGROUNDED),
        )
        every { h.context.startActivity(any()) } throws android.content.ActivityNotFoundException("x")

        h.dispatcher.launch(game, resolved, h.intent)

        val request = h.dispatcher.recoveryRequests.value
        assertIs<LaunchRecoveryRequest>(request)
        assertTrue(
            request.historyLine!!.contains("2 of the last 3"),
            "expected failure summary, got: ${request.historyLine}",
        )
        assertTrue(request.diagnostic.contains("DuckStation"), "diagnostic must name the emulator")
        assertTrue(request.diagnostic.contains("crash.bin"), "diagnostic must name the ROM")
    }

    @Test
    fun `preflight failure records and offers recovery without an intent`() = runTest {
        val h = harness()
        h.dispatcher.recordPreflightFailure(game, resolved, "ROM file not found")
        advanceUntilIdle()

        coVerify { h.recorder.record(match {
            it.status == LaunchOutcomeStatus.INTENT_FAILED && it.failureReason == "ROM file not found"
        }) }
        assertIs<LaunchRecoveryRequest>(h.dispatcher.recoveryRequests.value)
    }

    @Test
    fun `dismiss clears the recovery request`() = runTest {
        val h = harness()
        every { h.context.startActivity(any()) } throws android.content.ActivityNotFoundException("x")
        coEvery { h.recorder.record(any()) } returns Unit

        h.dispatcher.launch(game, resolved, h.intent)
        assertIs<LaunchRecoveryRequest>(h.dispatcher.recoveryRequests.value)

        h.dispatcher.dismissRecovery()
        assertNull(h.dispatcher.recoveryRequests.value)
    }

    // ── Shortcut launches (BannerHub / GameHub / any pinned launcher shortcut) ─────────────

    @Test
    fun `shortcut launch waits for GameBoot before starting the shortcut`() = runTest {
        val h = harness(gameBootOn = true)
        var started = false

        val launching = async { h.dispatcher.launchShortcut(game) { started = true; Result.success(Unit) } }
        eventually("the GameBoot presentation is raised") { h.gameBootGate.active.value != null }

        assertFalse(started, "The shortcut must not start while GameBoot is on screen")
        h.gameBootGate.onPresentationFinished()
        eventually("the shortcut launch completes") { launching.isCompleted }

        assertTrue(started, "The shortcut must start once GameBoot finishes")
        assertTrue(launching.await().isSuccess)
    }

    @Test
    fun `shortcut launch with GameBoot off starts immediately`() = runTest {
        val h = harness()
        var started = false

        val result = h.dispatcher.launchShortcut(game) { started = true; Result.success(Unit) }

        assertTrue(started)
        assertTrue(result.isSuccess)
        assertNull(h.gameBootGate.active.value)
    }

    @Test
    fun `a failed shortcut start is handed back to the caller`() = runTest {
        val h = harness()

        val result = h.dispatcher.launchShortcut(game) { Result.failure(IllegalStateException("gone")) }

        assertTrue(result.isFailure)
        assertEquals("gone", result.exceptionOrNull()?.message)
    }

    // ── Ambience: silent from the moment a launch starts until PFP is back ──────────────────

    @Test
    fun `a launch silences ambience and keeps it down until the launcher is back`() = runTest {
        val h = harness()
        h.launchAccepted()
        assertTrue(h.gameHoldsAmbience, "ambience stops the moment the game launches")
        h.dispatcher.onHostStopped()
        assertTrue(h.gameHoldsAmbience)
        h.now = 60_000
        h.dispatcher.onHostResumed()
        advanceUntilIdle()
        assertFalse(h.gameHoldsAmbience, "back in the launcher, ambience may play again")
    }

    @Test
    fun `ambience is already down while GameBoot presents`() = runTest {
        val h = harness(gameBootOn = true)
        val launching = async { h.dispatcher.launchShortcut(game) { Result.success(Unit) } }
        eventually("the GameBoot presentation is raised") { h.gameBootGate.active.value != null }
        assertTrue(h.gameHoldsAmbience, "GameBoot never plays over the background music")
        h.gameBootGate.onPresentationFinished()
        eventually("the shortcut launch completes") { launching.isCompleted }
    }

    @Test
    fun `a launch that never started gives ambience back`() = runTest {
        val h = harness()
        every { h.context.startActivity(any()) } throws android.content.ActivityNotFoundException("nope")
        h.dispatcher.launch(game, resolved, h.intent)
        assertFalse(h.gameHoldsAmbience)
    }

    @Test
    fun `a failed shortcut gives ambience back`() = runTest {
        val h = harness()
        h.dispatcher.launchShortcut(game) { Result.failure(IllegalStateException("gone")) }
        assertFalse(h.gameHoldsAmbience)
    }

    @Test
    fun `a launch nothing covered gives ambience back when the watchdog settles it`() = runTest {
        val h = harness()
        h.launchAccepted()
        h.now = LaunchDispatcher.STOP_WINDOW_MS + 1
        advanceTimeBy(LaunchDispatcher.STOP_WINDOW_MS + 1)
        advanceUntilIdle()
        assertFalse(h.gameHoldsAmbience)
    }

    // ── Error codes (notification details plan §10) ─────────────────────────────────────────

    @Test
    fun `each immediate failure carries its error code`() = runTest {
        val cases = listOf(
            android.content.ActivityNotFoundException("nope") to "LN-4001",
            SecurityException("denied") to "LN-4002",
            IllegalStateException("weird") to "LN-9001",
        )
        for ((thrown, code) in cases) {
            val h = harness()
            every { h.context.startActivity(any()) } throws thrown
            h.dispatcher.launch(game, resolved, h.intent)
            coVerify { h.recorder.record(match { it.errorCode == code }) }
        }
    }

    @Test
    fun `a launch that never came to the front carries LN-4003`() = runTest {
        val h = harness()
        h.launchAccepted()
        h.now = LaunchDispatcher.STOP_WINDOW_MS + 1
        advanceTimeBy(LaunchDispatcher.STOP_WINDOW_MS + 1)
        advanceUntilIdle()

        coVerify { h.recorder.record(match { it.errorCode == "LN-4003" }) }
    }

    @Test
    fun `a successful launch carries no error code`() = runTest {
        val h = harness()
        h.launchAccepted()
        h.dispatcher.onHostStopped()
        h.now = 60_000
        h.dispatcher.onHostResumed()
        advanceUntilIdle()

        coVerify { h.recorder.record(match { it.status == LaunchOutcomeStatus.SUCCEEDED && it.errorCode == null }) }
    }

    private fun outcome(status: LaunchOutcomeStatus) = LaunchOutcome(
        gameId = 7L,
        gameTitle = "Crash Bandicoot",
        platformId = "psx",
        emulatorId = "duckstation",
        emulatorName = "DuckStation",
        corePath = null,
        coreName = null,
        source = LaunchSource.PLATFORM_DEFAULT,
        status = status,
        failureReason = null,
        launchedAtMs = 1L,
    )
}
