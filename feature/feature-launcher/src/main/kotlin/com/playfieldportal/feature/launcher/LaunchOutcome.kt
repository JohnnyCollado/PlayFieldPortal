package com.playfieldportal.feature.launcher

/**
 * How a game launch settled, recorded once the outcome is known (B1 — launch reliability).
 *
 * The three-way split mirrors the plan's failure model:
 * - [SUCCEEDED]: the launcher was backgrounded for a real session and the user returned normally.
 * - [NEVER_FOREGROUNDED]: startActivity succeeded but the emulator never took the foreground
 *   (nothing covered the launcher inside the window) or the user was back almost immediately —
 *   the signature of an instant crash, a black screen the user escaped, or a refused hand-off.
 * - [INTENT_FAILED]: startActivity itself threw (ActivityNotFound, SecurityException, …).
 */
enum class LaunchOutcomeStatus {
    SUCCEEDED,
    NEVER_FOREGROUNDED,
    INTENT_FAILED,
}

/**
 * One settled launch verdict, snapshot at launch time so the row always says what WAS launched
 * even after the user changes defaults. Stored via [LaunchOutcomeRecorder]; the recovery sheet
 * reads recent rows to say "this failed N of the last M times with core X".
 */
data class LaunchOutcome(
    val gameId: Long,
    val gameTitle: String,
    val platformId: String?,
    val emulatorId: String?,
    val emulatorName: String?,
    val corePath: String?,
    val coreName: String?,
    /** The ladder level that decided the emulator; null for package/shortcut launches. */
    val source: LaunchSource?,
    val status: LaunchOutcomeStatus,
    val failureReason: String? = null,
    val launchedAtMs: Long,
    val returnedAtMs: Long? = null,
)
