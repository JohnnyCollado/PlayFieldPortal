package com.playfieldportal.feature.launcher

/**
 * Why a launch failed + what the user can do about it, produced by [LaunchDispatcher] whenever a
 * game-path launch needs a repair surface instead of a dead end (B1 — launch reliability).
 *
 * The recovery sheet renders from this: the failure message, a history line when the same game has
 * failed recently ("2 of the last 3 launches failed"), and a copyable diagnostic. The emulator
 * identity ([resolved]) is nullable — package/shortcut/native launches have no emulator ladder.
 */
data class LaunchRecoveryRequest(
    val gameId: Long,
    val gameTitle: String,
    val platformId: String?,
    val resolved: ResolvedLaunch?,
    val message: String,
    val historyLine: String?,
    val diagnostic: String,
)

/** Buttons the recovery sheet offers, mapped by the host shell to its own navigation. */
enum class LaunchRecoveryAction {
    /** Close the sheet (BACK / dismiss button). */
    DISMISS,
    /** Try the same game again. */
    RETRY,
    /** Open the per-game emulator picker for the failed game. */
    CHANGE_EMULATOR,
    /** Open the per-system defaults screen (per-platform fix). */
    PER_SYSTEM_DEFAULTS,
    /** Copy the diagnostic text to the clipboard. */
    COPY_DIAGNOSTIC,
}
