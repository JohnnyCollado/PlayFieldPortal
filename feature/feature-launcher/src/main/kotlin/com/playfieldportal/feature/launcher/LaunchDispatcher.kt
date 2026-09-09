package com.playfieldportal.feature.launcher

import android.content.Context
import android.content.Intent
import com.playfieldportal.core.domain.model.Game
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Monotonic elapsed-realtime source so verification windows never depend on wall-clock changes. */
fun interface LaunchClock {
    fun now(): Long
}

/** A game launch hand-off request, snapshot at dispatch time so the outcome row is self-describing. */
data class PendingLaunch(
    val game: Game,
    val resolved: ResolvedLaunch?,
    val intentSummary: String,
    val dispatchedAtMs: Long,
)

/** Result of handing an intent to the system. */
sealed interface LaunchDispatchResult {
    /** startActivity threw — the intent never reached an emulator. */
    data class Rejected(val message: String) : LaunchDispatchResult
    /** startActivity succeeded; the launch is now pending foreground verification. */
    data object Accepted : LaunchDispatchResult
}

/**
 * The single funnel every game-path launch intent goes through (B1 — launch reliability).
 *
 * One dispatcher owns three things that used to be scattered across call sites:
 *  1. **startActivity with named failures** — every game launch lands here, so an
 *     [ActivityNotFoundException] / SecurityException can never be silently swallowed (the old
 *     XMB direct-launch path logged and vanished).
 *  2. **Outcome recording** — each settled launch writes a [LaunchOutcome] via [LaunchOutcomeRecorder]:
 *     `INTENT_FAILED` immediately when startActivity throws, and `SUCCEEDED` / `NEVER_FOREGROUNDED`
 *     when the host lifecycle classifies the pending hand-off.
 *  3. **Post-launch verification** — PFP is a HOME launcher, so no usage-stats permission is needed:
 *     a successful game dispatch backgrounds it. The verdict is lifecycle-driven, not timer-driven:
 *     [onHostStopped] proves the emulator actually covered the launcher, which records the session
 *     as success no matter how short it was (closing the emulator right after it opens is the
 *     user's choice, not a failure). If [onHostStopped] never arrives inside the stop window, the
 *     launch is treated as never-foregrounded.
 *
 * Failures emit a [LaunchRecoveryRequest] (via [recoveryRequests]) so the shell can offer force-stop,
 * a different emulator/core, and a copyable diagnostic instead of a dead end.
 */
@Singleton
class LaunchDispatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val outcomeRecorder: LaunchOutcomeRecorder,
    @LaunchDispatcherScope private val scope: CoroutineScope,
    @LaunchRealtimeClock private val clock: LaunchClock,
    private val gameBootGate: GameBootGate,
    // A refused launch is the ERROR event's flagship home — the custom-vs-default decision
    // lives in MenuSoundPlayer; the dispatcher only says "this launch did not happen".
    private val menuSound: com.playfieldportal.core.ui.sound.MenuSoundPlayer,
    private val autoCoreMemory: AutoCoreMemory,
) {
    private val _recoveryRequests = MutableStateFlow<LaunchRecoveryRequest?>(null)
    /** Non-null while a recovery sheet should be shown; cleared by [dismissRecovery]. */
    val recoveryRequests: StateFlow<LaunchRecoveryRequest?> = _recoveryRequests.asStateFlow()

    // ── Pending hand-off state (guarded by the scope's single-thread confinement) ───────────
    private var pending: PendingLaunch? = null
    private var hostStopped = false
    private var watchdog: Job? = null

    /**
     * Hands [intent] to the system for [game] and records what happens. [resolved] is the B4 ladder
     * result when this was an emulator launch (null for package/shortcut/native launches).
     *
     * Failure messages mirror the historic Game Detail copy so screens can keep their wording.
     */
    suspend fun launch(game: Game, resolved: ResolvedLaunch?, intent: Intent): LaunchDispatchResult {
        // The ONE GameBoot seam. Here, and not at the confirm moment, because everything above
        // this line is preflight: a game that cannot launch must never show a presentation. Both
        // game-launch call sites (Game Detail and the XMB's direct launch) get it for free.
        // No-op when GameBoot is disabled, and bounded by its own watchdog — see GameBootGate.
        gameBootGate.awaitPresentation(game.title)
        return try {
            // Every dispatcher launch comes from an app-graph context (ViewModel/Activity via the
            // shared singleton), so NEW_TASK is required to start outside our own task. Idempotent.
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            // A RetroArch core launch pins the console to that core (see AutoCoreMemory), so the
            // console's automatic pick — and its RetroArch configs — stay stable across detection
            // passes. Written only once the intent actually reached the emulator, so a launch that
            // never happened can never pin a broken core. Every game-launch call site (Game Detail
            // and the XMB's direct launch) funnels through here, so one write covers both.
            resolved?.profile?.takeIf { it.isRetroArchProfile() }?.let { profile ->
                autoCoreMemory.remember(game.platformId, profile.id)
            }
            acceptPending(
                PendingLaunch(
                    game         = game,
                    resolved     = resolved,
                    intentSummary = intent.toUri(Intent.URI_INTENT_SCHEME),
                    dispatchedAtMs = clock.now(),
                )
            )
            LaunchDispatchResult.Accepted
        } catch (e: android.content.ActivityNotFoundException) {
            Timber.w(e, "Launch startActivity failed: emulator activity not found (gameId=${game.id})")
            settleImmediateFailure(game, resolved, "Emulator not found. Is it installed?")
        } catch (e: SecurityException) {
            Timber.w(e, "Launch startActivity failed: permission denied (gameId=${game.id})")
            settleImmediateFailure(game, resolved, "Permission denied launching emulator")
        } catch (e: Exception) {
            Timber.w(e, "Launch startActivity failed (gameId=${game.id})")
            settleImmediateFailure(game, resolved, "Could not open emulator: ${e.message}")
        }
    }

    /**
     * A launch was refused before any intent existed (preflight) — recorded so the game's history
     * line stays honest. [offerRecovery] raises the recovery sheet; screens that already render an
     * inline error for the same failure (Game Detail) pass false to avoid double-surfacing it,
     * while the XMB's silent direct-launch path (no inline UI at all) always wants the sheet.
     */
    suspend fun recordPreflightFailure(
        game: Game,
        resolved: ResolvedLaunch?,
        reason: String,
        offerRecovery: Boolean = true,
    ) {
        Timber.w("Launch blocked by preflight: gameId=${game.id}, reason=$reason")
        outcomeRecorder.record(
            outcomeFor(game, resolved, LaunchOutcomeStatus.INTENT_FAILED, reason)
        )
        menuSound.play(com.playfieldportal.core.ui.sound.MenuSound.ERROR)
        if (offerRecovery) emitRecovery(game, resolved, reason)
    }

    /** Manual recovery request from a screen (e.g. Game Detail's help affordance). */
    suspend fun requestRecovery(game: Game, resolved: ResolvedLaunch?, message: String) {
        emitRecovery(game, resolved, message)
    }

    fun dismissRecovery() {
        _recoveryRequests.value = null
    }

    /** MainActivity reports PFP left the foreground (an activity covered the launcher). */
    fun onHostStopped() {
        if (pending == null) return
        hostStopped = true
        watchdog?.cancel()
        watchdog = null
    }

    /** MainActivity reports PFP is foreground again — classify the pending hand-off. */
    fun onHostResumed() {
        val p = pending ?: return
        pending = null
        val emulatorTookForeground = hostStopped
        hostStopped = false
        watchdog?.cancel()
        watchdog = null

        if (emulatorTookForeground) {
            // The emulator demonstrably covered the launcher, so the user was inside it. Coming back
            // — even instantly — is a choice, not a failure: closing the emulator right after it
            // opens is a legitimate session and must never pop recovery UI. Duration is irrelevant.
            Timber.i("Launch session ${clock.now() - p.dispatchedAtMs}ms — emulator covered the launcher — recording success")
            scope.launch {
                outcomeRecorder.record(
                    outcomeFor(
                        p.game, p.resolved, LaunchOutcomeStatus.SUCCEEDED, reason = null,
                    ).copy(returnedAtMs = clock.now())
                )
            }
        } else {
            // The launcher was never covered, so the emulator never demonstrably took the foreground
            // even though startActivity succeeded (the user is back before the stop window without
            // having seen the emulator). Same verdict the watchdog would reach — never-foregrounded.
            Timber.w("Launch returned without the emulator covering the launcher (${clock.now() - p.dispatchedAtMs}ms)")
            scope.launch {
                outcomeRecorder.record(
                    outcomeFor(
                        p.game, p.resolved, LaunchOutcomeStatus.NEVER_FOREGROUNDED,
                        "The emulator never came to the foreground after launch.",
                    )
                )
                emitRecovery(
                    p.game, p.resolved,
                    "The emulator never appeared. Check that it is installed and up to date, " +
                        "then try launching again.",
                )
            }
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────────────────

    private fun acceptPending(p: PendingLaunch) {
        pending = p
        hostStopped = false
        // If the emulator never covers the launcher inside the window, startActivity "succeeded"
        // but nothing came to front. Treat that as a failure rather than waiting forever.
        watchdog = scope.launch {
            delay(STOP_WINDOW_MS)
            val stillPending = pending?.game?.id == p.game.id
            if (stillPending && !hostStopped) {
                pending = null
                Timber.w("No activity covered the launcher within ${STOP_WINDOW_MS}ms of dispatch")
                outcomeRecorder.record(
                    outcomeFor(
                        p.game, p.resolved, LaunchOutcomeStatus.NEVER_FOREGROUNDED,
                        "The emulator never came to the foreground after launch.",
                    )
                )
                emitRecovery(
                    p.game, p.resolved,
                    "The emulator never appeared. Check that it is installed and up to date, " +
                        "then try launching again.",
                )
            }
        }
    }

    // All pending-state reads/writes above run on the caller thread (main in production) or on the
    // injected scope's dispatcher. The scope is provided as Main.immediate (see
    // LaunchDispatcherModule) so acceptPending (ViewModel launch site), onHostStopped/onHostResumed
    // (MainActivity lifecycle) and the watchdog (scope) are confined to one thread by construction.

    private suspend fun settleImmediateFailure(
        game: Game,
        resolved: ResolvedLaunch?,
        message: String,
    ): LaunchDispatchResult {
        outcomeRecorder.record(
            outcomeFor(game, resolved, LaunchOutcomeStatus.INTENT_FAILED, message)
        )
        menuSound.play(com.playfieldportal.core.ui.sound.MenuSound.ERROR)
        emitRecovery(game, resolved, message)
        return LaunchDispatchResult.Rejected(message)
    }

    private suspend fun emitRecovery(game: Game, resolved: ResolvedLaunch?, message: String) {
        val recent = runCatching { outcomeRecorder.recentForGame(game.id, RECENT_LIMIT) }
            .getOrDefault(emptyList())
        val recentFailures = recent.count { it.status != LaunchOutcomeStatus.SUCCEEDED }
        val historyLine = when {
            recent.isEmpty() -> null
            recentFailures == 0 -> null
            else -> "$recentFailures of the last ${recent.size} launches for this game failed."
        }
        _recoveryRequests.value = LaunchRecoveryRequest(
            gameId          = game.id,
            gameTitle       = game.title,
            platformId      = game.platformId,
            resolved        = resolved,
            message         = message,
            historyLine     = historyLine,
            diagnostic      = buildDiagnostic(game, resolved, recent.firstOrNull()),
        )
    }

    private fun outcomeFor(
        game: Game,
        resolved: ResolvedLaunch?,
        status: LaunchOutcomeStatus,
        reason: String?,
    ) = LaunchOutcome(
        gameId        = game.id,
        gameTitle     = game.title,
        platformId    = game.platformId,
        emulatorId    = resolved?.profile?.id,
        emulatorName  = resolved?.profile?.name,
        corePath      = resolved?.corePath,
        coreName      = resolved?.coreName,
        source        = resolved?.source,
        status        = status,
        failureReason = reason,
        launchedAtMs  = System.currentTimeMillis(),
    )

    private fun buildDiagnostic(
        game: Game,
        resolved: ResolvedLaunch?,
        last: LaunchOutcome?,
    ): String = buildString {
        appendLine("Play Field Portal — launch diagnostic")
        appendLine("Game: ${game.title} (id ${game.id})")
        appendLine("Platform: ${game.platformId}")
        appendLine("Emulator: ${resolved?.profile?.name ?: last?.emulatorName ?: "unknown"}")
        if (resolved?.coreName != null) appendLine("Core: ${resolved.coreName}")
        appendLine("Source: ${resolved?.source?.name ?: last?.source?.name ?: "n/a"}")
        appendLine("ROM: ${game.romPath ?: game.romUri ?: game.packageName ?: game.launchToken ?: "n/a"}")
        if (game.isMissing) appendLine("Missing: yes (file not found on last scan)")
        if (last?.failureReason != null) appendLine("Last failure: ${last.failureReason}")
    }

    companion object {
        /** How long a successfully dispatched launch may take to cover the launcher. */
        const val STOP_WINDOW_MS = 6_000L
        private const val RECENT_LIMIT = 5
    }
}
